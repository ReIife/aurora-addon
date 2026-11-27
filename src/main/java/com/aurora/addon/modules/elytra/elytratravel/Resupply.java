/*
 * Port of Resupply from Aurora (Meteor Client addon, GPL-3.0-only, author n_lzy).
 * This file is distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.modules.elytra.elytratravel;

import evernight.client.ClientAccess;
import evernight.client.ClientContext;
import evernight.client.managers.player.RotationManager;
import evernight.client.utils.inventory.InventoryUtils;
import evernight.client.utils.network.PacketSender;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 补给：放置补给潜影盒、取烟花 / 食物 / 图腾 / 经验瓶、修鞘翅、挖掉并回收盒子。
 *
 * <p>移植自 Meteor 插件的 {@code Resupply}，状态机、重试次数与超时阈值保持一致。
 *
 * <p>与原版的差异（均有 {@code TODO(port)} 标注）：
 * <ul>
 *     <li>末影箱相关流程（{@code EnderChestResupply}）依赖 Aurora 自己的 600 行子系统，本次未移植，
 *     进入该分支时直接报错退出补给。</li>
 *     <li>挖掘盒子原先调用 {@code MineSingleBlock}（带自动换工具 / 卡住重启），这里改为直接向服务端
 *     发送挖掘包，不再自动切换工具。</li>
 * </ul>
 */
public final class Resupply {
    public static final Set<Item> FOODS = Set.of(
            Items.GOLDEN_CARROT, Items.BREAD, Items.COOKED_PORKCHOP, Items.COOKED_BEEF);

    private static final int HOTBAR_BOX_SLOT = 8;
    private static final int HOTBAR_XP_SLOT = 7;
    private static final Minecraft mc = ClientAccess.evernightMinecraft;

    private final Config config;
    private final TravelLog log;

    private State state;
    private int ticks;
    private int stage;
    private boolean needFireworks;
    private boolean needFood;
    private boolean needTotems;
    private boolean needRepair;
    private int boxSlot;
    private BlockPos placedPos;
    private int placeRetries;
    private int openRetries;
    private int mineRetries;
    private int xpRefillAttempts;
    private int preBoxCount;
    private boolean needXpOnly;
    private boolean xpChecked;
    private boolean miningStarted;
    private PostPickup postPickup = PostPickup.NONE;
    private boolean active;

    public Resupply(Config config, TravelLog log) {
        this.config = config;
        this.log = log;
    }

    public void start() {
        this.state = State.CHECK;
        this.ticks = this.stage = 0;
        this.needFireworks = this.needFood = this.needTotems = this.needRepair = false;
        this.boxSlot = -1;
        this.placedPos = null;
        this.placeRetries = this.openRetries = this.mineRetries = this.xpRefillAttempts = 0;
        this.preBoxCount = 0;
        this.needXpOnly = false;
        this.xpChecked = false;
        this.miningStarted = false;
        this.postPickup = PostPickup.NONE;
        this.active = true;
    }

    public void reset() {
        this.active = false;
        this.placedPos = null;
        this.miningStarted = false;
        this.postPickup = PostPickup.NONE;
        this.needXpOnly = false;
        this.xpChecked = false;
        if (mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
    }

    public boolean isActive() {
        return this.active && this.state != State.DONE && this.state != State.FAILED;
    }

    /** @return true 表示补给流程已经结束（成功或失败） */
    public boolean tick() {
        if (!this.active) {
            return true;
        }
        if (mc.player == null || mc.level == null) {
            return true;
        }

        this.ticks++;
        switch (this.state) {
            case CHECK -> this.check();
            case FIND -> this.find();
            case PLACE -> this.place();
            case WAIT_BOX -> this.waitBox();
            case OPEN_BOX -> this.openBox();
            case WAIT_OPEN -> this.waitOpen();
            case OPEN -> this.takeItems();
            case CLOSE -> this.close();
            case REPAIR -> this.repair();
            case NEED_XP -> this.needXp();
            case MINE_BOX -> this.mineBox();
            case PICKUP -> this.pickup();
            case STORE_BOX, TAKE_NEW_BOX, EC_RESUPPLY -> this.enderChestUnsupported();
            case DONE, FAILED -> {
                return true;
            }
        }
        return this.state == State.DONE || this.state == State.FAILED;
    }

    private void check() {
        int fireworks = 0;
        int food = 0;
        int totems = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.FIREWORK_ROCKET)) {
                fireworks += stack.getCount();
            }
            if (FOODS.contains(stack.getItem())) {
                food += stack.getCount();
            }
            if (stack.is(Items.TOTEM_OF_UNDYING)) {
                totems += stack.getCount();
            }
        }

        this.needFireworks = fireworks < this.config.minFireworks();
        this.needFood = food < this.config.minFood();
        this.needTotems = totems < this.config.minTotems();
        this.needRepair = this.isRepairNeeded();

        if (!this.needFireworks && !this.needFood && !this.needTotems && !this.needRepair) {
            this.log.info("messages.supplyEnough");
            this.state = State.DONE;
            return;
        }

        StringBuilder missing = new StringBuilder();
        if (this.needFireworks) {
            missing.append(this.log.text("messages.supplyNeedFireworks")).append(' ');
        }
        if (this.needFood) {
            missing.append(this.log.text("messages.supplyNeedFood")).append(' ');
        }
        if (this.needTotems) {
            missing.append(this.log.text("messages.supplyNeedTotems")).append(' ');
        }
        if (this.needRepair) {
            missing.append(this.log.text("messages.supplyNeedRepair"));
        }
        this.log.info("messages.supplyMissing", missing.toString().trim());

        if (!this.needFireworks && !this.needFood && !this.needTotems && this.needRepair
                && this.countItem(Items.EXPERIENCE_BOTTLE) > 64) {
            this.log.info("messages.supplyRepairOnly");
            this.postPickup = PostPickup.DONE;
            this.state = State.REPAIR;
            this.ticks = 0;
            return;
        }

        this.state = State.FIND;
        this.ticks = 0;
    }

    private boolean isRepairNeeded() {
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.is(Items.ELYTRA)) {
            int max = chest.getMaxDamage();
            if (max > 0 && (float) (max - chest.getDamageValue()) / max < 0.98F) {
                return true;
            }
        }
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.ELYTRA)) {
                int remaining = stack.getMaxDamage() - stack.getDamageValue();
                if (remaining <= this.config.repairThreshold()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void find() {
        int best = -1;
        int bestScore = -1;
        boolean foundEnough = false;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!this.isSupplyBox(stack)) {
                continue;
            }

            int fireworks = this.countInBox(stack, Items.FIREWORK_ROCKET);
            int food = this.countFoodInBox(stack);
            int xp = this.countInBox(stack, Items.EXPERIENCE_BOTTLE);
            int totems = this.countInBox(stack, Items.TOTEM_OF_UNDYING);

            boolean enough = true;
            if (this.needFireworks && fireworks <= this.config.minFireworks()) {
                enough = false;
            }
            if (this.needFood && food <= this.config.minFood()) {
                enough = false;
            }
            if (this.needTotems && totems <= 0) {
                enough = false;
            }

            int score = (this.needFireworks ? fireworks : 0)
                    + (this.needFood ? food : 0)
                    + (this.needRepair ? xp : 0)
                    + (this.needTotems ? totems * 64 : 0);

            if (enough) {
                if (!foundEnough || score > bestScore) {
                    bestScore = score;
                    best = i;
                    foundEnough = true;
                }
            } else if (!foundEnough && score > bestScore) {
                bestScore = score;
                best = i;
            }
        }

        if (best < 0) {
            this.toEnderChest("messages.supplyBoxNotFound");
            return;
        }

        ItemStack box = mc.player.getInventory().getItem(best);
        boolean enough = true;
        if (this.needFireworks && this.countInBox(box, Items.FIREWORK_ROCKET) <= this.config.minFireworks()) {
            enough = false;
        }
        if (this.needFood && this.countFoodInBox(box) <= this.config.minFood()) {
            enough = false;
        }
        if (this.needTotems && this.countInBox(box, Items.TOTEM_OF_UNDYING) <= 0) {
            enough = false;
        }
        if (!enough) {
            this.toEnderChest("messages.supplyBoxInsufficient");
            return;
        }

        this.boxSlot = best;
        this.log.info("messages.supplySwitched");
        if (this.boxSlot != HOTBAR_BOX_SLOT) {
            InventoryUtils.swapWithHotbarSlot(InventoryUtils.toMenuSlot(this.boxSlot), HOTBAR_BOX_SLOT);
            this.boxSlot = HOTBAR_BOX_SLOT;
        }
        if (InventoryUtils.getSelectedSlot() != HOTBAR_BOX_SLOT) {
            InventoryUtils.selectHotbarSlot(HOTBAR_BOX_SLOT);
        }

        this.state = State.PLACE;
        this.ticks = 0;
        this.place();
    }

    private void toEnderChest(String reasonKey) {
        this.log.info(reasonKey);
        // TODO(port): 原版在这里创建 EnderChestResupply（放末影箱 → 打开 → 取物资 → 收盒），
        //  该子系统（623 行，依赖 Aurora 自己的 WalkToPlace / 容器点击状态机）本次未移植。
        this.log.warning("messages.supplyEnderChestUnsupported");
        this.state = State.FAILED;
    }

    private void enderChestUnsupported() {
        // TODO(port): STORE_BOX / TAKE_NEW_BOX / EC_RESUPPLY 三个状态全部依赖 EnderChestResupply。
        this.log.warning("messages.supplyEnderChestUnsupported");
        this.state = State.FAILED;
    }

    private void place() {
        BlockPos pos = this.findPlacePos();
        if (pos == null) {
            this.log.error("messages.supplyPlaceFailed");
            this.state = State.FAILED;
            return;
        }

        this.placedPos = pos;
        BlockPos clickPos = pos.below();
        Vec3 hitVec = new Vec3(clickPos.getX() + 0.5, clickPos.getY() + 1.0, clickPos.getZ() + 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, clickPos, false);
        ClientContext.rotationManager.requestRotation(RotationManager.getRotationsToPosition(hitVec), 100);
        PacketSender.sendUseItemOn(InteractionHand.MAIN_HAND, hit);

        this.log.info("messages.supplyPlaced", pos.toShortString());
        this.state = State.WAIT_BOX;
        this.ticks = 0;
        this.placeRetries++;
    }

    private void waitBox() {
        if (this.placedPos == null) {
            this.log.error("messages.supplyPlaceLost");
            this.state = State.FAILED;
            return;
        }
        if (mc.level.getBlockState(this.placedPos).getBlock() instanceof ShulkerBoxBlock) {
            this.state = State.OPEN_BOX;
            this.ticks = 0;
            this.openBox();
            return;
        }
        if (this.ticks > 10) {
            this.log.error("messages.supplyPlaceTimeout", this.placeRetries);
            this.state = State.PLACE;
            this.ticks = 0;
            this.place();
        }
    }

    private void openBox() {
        if (this.placedPos == null) {
            this.log.error("messages.supplyPlaceLost");
            this.state = State.FAILED;
            return;
        }
        if (mc.player.containerMenu instanceof ShulkerBoxMenu) {
            this.log.info("messages.supplyOpened");
            this.state = State.WAIT_OPEN;
            this.ticks = 0;
            this.stage = 0;
            return;
        }
        if (this.ticks > 20) {
            this.log.error("messages.supplyCannotOpen");
            this.state = State.FAILED;
            return;
        }

        Hit hit = this.bestFace(this.placedPos);
        BlockHitResult result = new BlockHitResult(hit.hitVec(), hit.face(), this.placedPos, false);
        ClientContext.rotationManager.requestRotation(RotationManager.getRotationsToPosition(hit.hitVec()), 100);
        PacketSender.sendUseItemOn(InteractionHand.MAIN_HAND, result);
    }

    private void waitOpen() {
        if (mc.player.containerMenu instanceof ShulkerBoxMenu) {
            this.log.info("messages.supplyOpenDetected");
            this.state = State.OPEN;
            this.ticks = 0;
            this.stage = 0;
            return;
        }
        if (this.ticks > 40) {
            if (this.openRetries >= 3) {
                this.log.error("messages.supplyGiveUpOpen");
                this.state = State.FAILED;
                return;
            }
            this.openRetries++;
            this.log.error("messages.supplyOpenTimeout", this.openRetries);
            this.state = State.OPEN_BOX;
            this.ticks = 0;
        }
    }

    private void takeItems() {
        if (!(mc.player.containerMenu instanceof ShulkerBoxMenu)) {
            this.log.info("messages.supplyClosed");
            this.state = State.CLOSE;
            this.ticks = 0;
            return;
        }
        if (this.ticks > 200) {
            this.log.info("messages.supplyTimeout");
            mc.player.closeContainer();
            this.state = State.CLOSE;
            this.ticks = 0;
            return;
        }
        if (this.ticks < 10) {
            return;
        }

        if (this.needXpOnly) {
            this.takeExperienceOnly();
            return;
        }

        int targetFireworks = this.config.supplyFireworks() * 64;
        int targetFood = this.config.supplyFood() * 64;
        int targetTotems = this.config.supplyTotems();
        int targetXp = this.config.supplyXp() * 64;

        if (this.ticks % 3 != 0) {
            return;
        }

        switch (this.stage) {
            case 0 -> {
                if (this.needFireworks && this.countItem(Items.FIREWORK_ROCKET) < targetFireworks
                        && this.boxHas(Items.FIREWORK_ROCKET)) {
                    this.quickMove(Items.FIREWORK_ROCKET);
                } else {
                    this.stage = 1;
                }
            }
            case 1 -> {
                if (this.needFood && this.countFood() < targetFood && this.boxHasFood()) {
                    this.quickMoveFood();
                } else {
                    this.stage = 2;
                }
            }
            case 2 -> {
                if (this.needTotems && this.countItem(Items.TOTEM_OF_UNDYING) < targetTotems
                        && this.boxHas(Items.TOTEM_OF_UNDYING)) {
                    this.quickMove(Items.TOTEM_OF_UNDYING);
                } else {
                    this.stage = 3;
                }
            }
            case 3 -> {
                if (this.needRepair && this.countItem(Items.EXPERIENCE_BOTTLE) < targetXp
                        && this.boxHas(Items.EXPERIENCE_BOTTLE)) {
                    this.quickMove(Items.EXPERIENCE_BOTTLE);
                } else {
                    if (this.countItem(Items.EXPERIENCE_BOTTLE) > 0) {
                        this.xpRefillAttempts = 0;
                    }
                    this.stage = 4;
                }
            }
            default -> {
                this.log.info("messages.supplyItemsTaken");
                this.state = State.CLOSE;
                this.ticks = 0;
            }
        }
    }

    private void takeExperienceOnly() {
        if (!this.xpChecked) {
            this.xpChecked = true;
            if (!this.boxHas(Items.EXPERIENCE_BOTTLE)) {
                this.needXpOnly = false;
                mc.player.closeContainer();
                this.xpRefillAttempts++;
                if (this.xpRefillAttempts >= 3) {
                    this.log.warning("messages.supplyXpGiveUp", this.xpRefillAttempts);
                    this.postPickup = PostPickup.DONE;
                } else {
                    this.log.info("messages.supplyXpRefill", this.xpRefillAttempts);
                    this.postPickup = PostPickup.FIND_NEW_BOX;
                }
                this.state = State.MINE_BOX;
                this.ticks = 0;
                this.mineBox();
                return;
            }
        }

        if (!this.boxHas(Items.EXPERIENCE_BOTTLE)) {
            this.needXpOnly = false;
            mc.player.closeContainer();
            this.log.info("messages.supplyXpTaken");
            this.xpRefillAttempts = 0;
            this.state = State.REPAIR;
            this.ticks = 0;
            return;
        }

        if (this.ticks % 3 == 0) {
            this.quickMove(Items.EXPERIENCE_BOTTLE);
        }
    }

    private void close() {
        if (mc.player.containerMenu instanceof ShulkerBoxMenu) {
            mc.player.closeContainer();
        }
        if (this.ticks < 5) {
            return;
        }
        if (this.needRepair) {
            this.log.info("messages.supplyRepairStart");
            this.state = State.REPAIR;
            this.ticks = 0;
            return;
        }
        this.log.info("messages.supplyMineStart");
        this.postPickup = PostPickup.DONE;
        this.state = State.MINE_BOX;
        this.ticks = 0;
        this.mineBox();
    }

    private void repair() {
        ItemStack chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (!chest.is(Items.ELYTRA)) {
            int slot = this.findElytraToRepair();
            if (slot == -1) {
                this.finishRepair();
            } else {
                this.equipFromSlot(slot);
                this.ticks = 0;
            }
            return;
        }

        float percent = chest.getMaxDamage() > 0
                ? (1.0F - (float) chest.getDamageValue() / chest.getMaxDamage()) * 100.0F
                : 100.0F;
        if (percent >= 98.0F) {
            int slot = this.findElytraToRepair();
            if (slot == -1) {
                this.finishRepair();
            } else {
                this.equipFromSlot(slot);
                this.ticks = 0;
            }
            return;
        }

        if (this.ticks == 1) {
            mc.player.jumpFromGround();
        }

        int xpSlot = InventoryUtils.findHotbarSlot(Items.EXPERIENCE_BOTTLE);
        if (xpSlot == -1) {
            int inventorySlot = InventoryUtils.findInventorySlot(
                    stack -> stack.is(Items.EXPERIENCE_BOTTLE), true);
            if (inventorySlot == -1) {
                this.log.info("messages.supplyXpOut");
                this.state = State.NEED_XP;
                this.ticks = 0;
                return;
            }
            InventoryUtils.swapWithHotbarSlot(InventoryUtils.toMenuSlot(inventorySlot), HOTBAR_XP_SLOT);
            xpSlot = HOTBAR_XP_SLOT;
        }

        if (InventoryUtils.getSelectedSlot() != xpSlot) {
            InventoryUtils.selectHotbarSlot(xpSlot);
        }

        if (this.ticks % this.config.xpInterval() == 0) {
            float yaw = mc.player.getYRot();
            ClientContext.rotationManager.requestRotation(yaw, 90.0F, 100, true);
            PacketSender.sendUseItem(InteractionHand.MAIN_HAND, yaw, 90.0F);
        }
    }

    private void finishRepair() {
        this.log.info("messages.supplyAllRepaired");
        this.postPickup = PostPickup.DONE;
        this.state = State.MINE_BOX;
        this.ticks = 0;
        this.mineBox();
    }

    private int findElytraToRepair() {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.ELYTRA)) {
                int remaining = stack.getMaxDamage() - stack.getDamageValue();
                if (remaining <= this.config.repairThreshold()) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void needXp() {
        boolean boxExists = this.placedPos != null
                && mc.level.getBlockState(this.placedPos).getBlock() instanceof ShulkerBoxBlock;

        if (this.xpRefillAttempts >= 3) {
            this.log.warning("messages.supplyXpGiveUp", this.xpRefillAttempts);
            this.state = this.countBoxByName() > 0 ? State.STORE_BOX : State.DONE;
            this.ticks = 0;
            return;
        }
        if (boxExists) {
            this.log.info("messages.supplyReopenBox");
            this.needXpOnly = true;
            this.xpChecked = false;
            this.state = State.OPEN_BOX;
            this.ticks = 0;
            return;
        }
        if (this.countBoxByName() > 0) {
            this.xpRefillAttempts++;
            this.log.info("messages.supplyXpRefill", this.xpRefillAttempts);
            this.postPickup = PostPickup.FIND_NEW_BOX;
            this.state = State.STORE_BOX;
            this.ticks = 0;
            return;
        }
        this.xpRefillAttempts++;
        this.log.info("messages.supplyTakeNewBox", this.xpRefillAttempts);
        this.state = State.TAKE_NEW_BOX;
        this.ticks = 0;
    }

    private void mineBox() {
        if (this.placedPos == null) {
            this.log.info("messages.supplyNoBox");
            if (this.countBoxByName() > 0) {
                this.log.info("messages.supplyStoreFirst");
                this.state = State.STORE_BOX;
                this.ticks = 0;
            } else {
                this.proceedAfterBox();
            }
            return;
        }

        BlockState state = mc.level.getBlockState(this.placedPos);
        if (mc.level.isEmptyBlock(this.placedPos) || state.canBeReplaced()) {
            this.log.info("messages.supplyBoxGone");
            this.stopMining();
            this.state = State.PICKUP;
            this.ticks = 0;
            this.pickup();
            return;
        }

        if (this.ticks == 1) {
            this.miningStarted = false;
        }
        // TODO(port): 原版用 MineSingleBlock 自动换最佳工具并在无进展时重启；这里直接用当前手持物品挖掘。
        Direction face = this.bestFace(this.placedPos).face();
        if (!this.miningStarted) {
            mc.gameMode.startDestroyBlock(this.placedPos, face);
            mc.player.swing(InteractionHand.MAIN_HAND);
            this.miningStarted = true;
        } else {
            mc.gameMode.continueDestroyBlock(this.placedPos, face);
        }

        if (this.ticks > 200) {
            if (this.mineRetries >= 3) {
                this.log.error("messages.supplyMineGiveUp");
                this.stopMining();
                this.state = State.PICKUP;
                this.ticks = 0;
                this.pickup();
                return;
            }
            this.mineRetries++;
            this.log.error("messages.supplyMineRestart", this.mineRetries);
            this.stopMining();
            this.ticks = 0;
        }
    }

    private void stopMining() {
        if (this.miningStarted && mc.gameMode != null) {
            mc.gameMode.stopDestroyBlock();
        }
        this.miningStarted = false;
    }

    private void pickup() {
        if (this.ticks == 0) {
            this.preBoxCount = this.countBoxByName();
            if (mc.getConnection() != null) {
                mc.getConnection().sendChat("#pickup minecraft:shulker_box minecraft:white_shulker_box "
                        + "minecraft:orange_shulker_box minecraft:magenta_shulker_box minecraft:light_blue_shulker_box "
                        + "minecraft:yellow_shulker_box minecraft:lime_shulker_box minecraft:pink_shulker_box "
                        + "minecraft:gray_shulker_box minecraft:light_gray_shulker_box minecraft:cyan_shulker_box "
                        + "minecraft:purple_shulker_box minecraft:blue_shulker_box minecraft:brown_shulker_box "
                        + "minecraft:green_shulker_box minecraft:red_shulker_box minecraft:black_shulker_box");
            }
        }

        if (this.ticks > 20 && this.countBoxByName() > this.preBoxCount) {
            this.log.info("messages.supplyPickupConfirm", this.preBoxCount, this.countBoxByName());
            if (mc.getConnection() != null) {
                mc.getConnection().sendChat("#stop");
            }
            this.log.info("messages.supplyStoreStart");
            this.state = State.STORE_BOX;
            this.ticks = 0;
            return;
        }

        if (this.ticks > 100) {
            this.log.error("messages.supplyPickupTimeout");
            this.ticks = 0;
        }
    }

    private void proceedAfterBox() {
        PostPickup previous = this.postPickup;
        this.postPickup = PostPickup.NONE;
        if (previous == PostPickup.FIND_NEW_BOX) {
            this.log.info("messages.supplyTakeNewBoxFromEnderChest");
            this.state = State.TAKE_NEW_BOX;
            this.ticks = 0;
            return;
        }
        this.state = State.DONE;
    }

    private boolean isSupplyBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock
                && stack.getHoverName().getString().contains(this.config.boxName());
    }

    private int countInBox(ItemStack box, Item item) {
        ItemContainerContents contents = box.get(DataComponents.CONTAINER);
        if (contents == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack stack : contents.nonEmptyItems()) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countFoodInBox(ItemStack box) {
        ItemContainerContents contents = box.get(DataComponents.CONTAINER);
        if (contents == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack stack : contents.nonEmptyItems()) {
            if (FOODS.contains(stack.getItem())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countItem(Item item) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countFood() {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (FOODS.contains(stack.getItem())) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private ShulkerBoxMenu boxMenu() {
        return mc.player.containerMenu instanceof ShulkerBoxMenu menu ? menu : null;
    }

    private boolean boxHas(Item item) {
        ShulkerBoxMenu menu = this.boxMenu();
        if (menu == null) {
            return false;
        }
        for (int i = 0; i < 27; i++) {
            if (menu.getSlot(i).getItem().is(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean boxHasFood() {
        ShulkerBoxMenu menu = this.boxMenu();
        if (menu == null) {
            return false;
        }
        for (int i = 0; i < 27; i++) {
            if (FOODS.contains(menu.getSlot(i).getItem().getItem())) {
                return true;
            }
        }
        return false;
    }

    private void quickMove(Item item) {
        ShulkerBoxMenu menu = this.boxMenu();
        if (menu == null) {
            return;
        }
        for (int i = 0; i < 27; i++) {
            if (menu.getSlot(i).getItem().is(item)) {
                InventoryUtils.clickSlot(menu.containerId, i, 0, ClickType.QUICK_MOVE);
                return;
            }
        }
    }

    private void quickMoveFood() {
        ShulkerBoxMenu menu = this.boxMenu();
        if (menu == null) {
            return;
        }
        for (int i = 0; i < 27; i++) {
            if (FOODS.contains(menu.getSlot(i).getItem().getItem())) {
                InventoryUtils.clickSlot(menu.containerId, i, 0, ClickType.QUICK_MOVE);
                return;
            }
        }
    }

    private int countBoxByName() {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (this.isSupplyBox(mc.player.getInventory().getItem(i))) {
                count++;
            }
        }
        return count;
    }

    private void equipFromSlot(int inventorySlot) {
        // 快捷栏索引 → 菜单槽位，盔甲槽 6 = 胸甲。
        InventoryUtils.swapSlotsWithPickup(InventoryUtils.toMenuSlot(inventorySlot), 6);
    }

    private Hit bestFace(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        Direction best = Direction.UP;
        double bestDistance = Double.MAX_VALUE;

        for (Direction direction : Direction.values()) {
            double x = pos.getX() + 0.5 + direction.getUnitVec3i().getX() * 0.5;
            double y = pos.getY() + 0.5 + direction.getUnitVec3i().getY() * 0.5;
            double z = pos.getZ() + 0.5 + direction.getUnitVec3i().getZ() * 0.5;
            double distance = eye.distanceToSqr(x, y, z);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = direction;
            }
        }

        return new Hit(
                new Vec3(
                        pos.getX() + 0.5 + best.getUnitVec3i().getX() * 0.5,
                        pos.getY() + 0.5 + best.getUnitVec3i().getY() * 0.5,
                        pos.getZ() + 0.5 + best.getUnitVec3i().getZ() * 0.5),
                best);
    }

    private BlockPos findPlacePos() {
        int range = this.config.placeRange();
        BlockPos playerPos = mc.player.blockPosition();
        BlockPos best = null;
        double bestDistance = 999.0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = -range; x <= range; x++) {
            for (int z = -range; z <= range; z++) {
                for (int y = -2; y <= 2; y++) {
                    cursor.set(playerPos.getX() + x, playerPos.getY() + y, playerPos.getZ() + z);
                    BlockPos below = cursor.below();
                    BlockState belowState = mc.level.getBlockState(below);
                    if (!mc.level.isEmptyBlock(cursor)
                            || belowState.isAir()
                            || !belowState.isRedstoneConductor(mc.level, below)
                            || this.isClickable(belowState.getBlock())
                            || !mc.level.getBlockState(cursor).canBeReplaced()) {
                        continue;
                    }

                    AABB blockBox = new AABB(cursor);
                    if (mc.player.getBoundingBox().intersects(blockBox)
                            || !mc.level.getEntities(mc.player, blockBox, entity -> entity instanceof LivingEntity).isEmpty()) {
                        continue;
                    }

                    double distance = cursor.getCenter().distanceToSqr(mc.player.getEyePosition());
                    if (distance >= 1.0 && distance < bestDistance) {
                        bestDistance = distance;
                        best = cursor.immutable();
                    }
                }
            }
        }

        return best;
    }

    private boolean isClickable(Block block) {
        return block instanceof CraftingTableBlock
                || block instanceof AnvilBlock
                || block instanceof BaseEntityBlock
                || block instanceof BedBlock
                || block instanceof FenceGateBlock
                || block instanceof DoorBlock
                || block instanceof NoteBlock
                || block instanceof TrapDoorBlock;
    }

    public record Config(
            int minFireworks,
            int minFood,
            int minTotems,
            int supplyFireworks,
            int supplyFood,
            int supplyXp,
            int supplyTotems,
            String boxName,
            int placeRange,
            int xpInterval,
            int repairThreshold) {
    }

    private record Hit(Vec3 hitVec, Direction face) {
    }

    private enum PostPickup {
        NONE,
        DONE,
        FIND_NEW_BOX
    }

    private enum State {
        CHECK,
        FIND,
        PLACE,
        WAIT_BOX,
        OPEN_BOX,
        WAIT_OPEN,
        OPEN,
        CLOSE,
        REPAIR,
        NEED_XP,
        MINE_BOX,
        PICKUP,
        STORE_BOX,
        TAKE_NEW_BOX,
        EC_RESUPPLY,
        DONE,
        FAILED
    }
}
