package com.aurora.addon.modules.elytra.elytracollect;

import com.aurora.addon.modules.AuroraModule;
import com.aurora.addon.modules.elytra.ElytraCollect;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.Module;
import evernight.client.modules.ModuleCategory;
import evernight.client.utils.chat.ChatUtils;
import evernight.client.utils.inventory.InventoryUtils;
import java.util.Set;
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
 * 补给：检查烟花 / 食物 / 图腾 / 鞘翅耐久，找一只内容足够的补给潜影盒，放到地上打开、把东西
 * 拿进背包，需要时用经验瓶修鞘翅，最后挖掉盒子并让服务端捡回。
 *
 * <p>移植自 Meteor 插件的 {@code StoreResupply}，状态机与判定条件逐条保留。适配点：
 * <ul>
 *     <li>{@code Rotations.rotate(...)} → {@code RotationManager.requestRotation(..., force)} +
 *     {@code executeAction(...)}。</li>
 *     <li>{@code InvUtils.quickSwap().fromId(a).to(b)} → {@code InventoryUtils.swapWithHotbarSlot(...)}。</li>
 *     <li>挖盒子改走 {@link ElytraCollect#mineBlock}（原实现用单独的「单方块挖掘」模块）。</li>
 * </ul>
 *
 * <p>TODO(port): 原实现找不到合适补给盒时会回退到「末影箱补给」模块
 * （{@code EnderChestResupply}），该模块不在本次移植范围，这里只报错并关闭，不会真的开末影箱。
 */
public final class StoreResupply extends AuroraModule {
    private static final Set<Item> FOODS =
            Set.of(Items.GOLDEN_CARROT, Items.BREAD, Items.COOKED_PORKCHOP, Items.COOKED_BEEF);

    private State state;
    private int ticks;
    private int step;
    private boolean needFirework;
    private boolean needFood;
    private boolean needRepair;
    private boolean needTotem;
    private int boxSlot;
    private BlockPos placedPos;
    private int placeRetries;
    private int preBoxCount;
    private int openRetries;
    private int mineRetries;

    public StoreResupply(ModuleCategory category) {
        super("StoreResupply", "Tops up fireworks, food, totems and elytra durability from a supply shulker box.", category);
    }

    private static ElytraCollect cfg() {
        return ElytraCollect.cfg();
    }

    @Override
    public void onEnable() {
        if (this.mc.player == null) {
            this.disable();
            return;
        }
        this.mc.options.keyUse.setDown(false);
        this.mc.options.keyAttack.setDown(false);
        this.state = State.CHECK;
        this.ticks = 0;
        this.step = 0;
        this.needFirework = false;
        this.needFood = false;
        this.needRepair = false;
        this.needTotem = false;
        this.boxSlot = -1;
        this.placeRetries = 0;
        this.openRetries = 0;
        this.mineRetries = 0;
    }

    @Override
    public void onDisable() {
        this.mc.options.keyUse.setDown(false);
        this.mc.options.keyAttack.setDown(false);
        ElytraCollect config = cfg();
        if (config != null) {
            config.stopMining();
        }
        this.placedPos = null;
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        ElytraCollect config = cfg();
        if (this.mc.player == null || config == null) {
            return;
        }
        this.ticks++;
        switch (this.state) {
            case CHECK -> this.check(config);
            case FIND -> this.find(config);
            case PLACE -> this.place(config);
            case WAIT_BOX -> this.waitBox(config);
            case OPEN_BOX -> this.openBox(config);
            case WAIT_OPEN -> this.waitOpen(config);
            case OPEN -> this.withdraw(config);
            case CLOSE -> this.close(config);
            case REPAIR -> this.repair(config);
            case MINE_BOX -> this.mineBox(config);
            case PICKUP -> this.pickup(config);
            case EC_RESUPPLY -> this.enderChestResupply();
            case DONE -> {
            }
        }
    }

    // ------------------------------------------------------------------ 状态

    private void check(ElytraCollect config) {
        int fireworks = 0;
        int foods = 0;
        int totems = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = this.mc.player.getInventory().getItem(i);
            if (stack.getItem() == Items.FIREWORK_ROCKET) {
                fireworks += stack.getCount();
            }
            if (FOODS.contains(stack.getItem())) {
                foods += stack.getCount();
            }
            if (stack.getItem() == Items.TOTEM_OF_UNDYING) {
                totems += stack.getCount();
            }
        }

        this.needFirework = config.storeEnFW() && fireworks < config.storeMinFW();
        this.needFood = config.storeEnFD() && foods < config.storeMinFD();
        this.needTotem = config.storeEnTT() && totems < config.storeMinTotem();
        this.needRepair = false;
        if (config.storeEnRP()) {
            ItemStack chest = this.mc.player.getItemBySlot(EquipmentSlot.CHEST);
            if (chest.getItem() == Items.ELYTRA) {
                int max = chest.getMaxDamage();
                this.needRepair = max > 0
                        && (1.0F - (float) chest.getDamageValue() / max) * 100.0F < config.storeMinDur();
            }
        }

        if (!this.needFirework && !this.needFood && !this.needRepair && !this.needTotem) {
            this.info("messages.collect.store.sufficient");
            this.state = State.DONE;
            enableIfInactive(StowElytra.class);
            this.disable();
            return;
        }

        this.info("messages.collect.store.missing",
                this.needFirework, this.needFood, this.needTotem, this.needRepair);
        if (!this.needFirework && !this.needFood && !this.needTotem && this.needRepair
                && this.countItem(Items.EXPERIENCE_BOTTLE) > 64) {
            this.info("messages.collect.store.repairOnly");
            this.state = State.REPAIR;
            this.ticks = 0;
            return;
        }
        this.state = State.FIND;
        this.ticks = 0;
    }

    private void find(ElytraCollect config) {
        int best = -1;
        int bestScore = -1;
        boolean foundEnough = false;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = this.mc.player.getInventory().getItem(i);
            if (!this.isSupplyBox(stack, config)) {
                continue;
            }
            int fireworks = this.countInBox(stack, Items.FIREWORK_ROCKET);
            int foods = this.countFoodInBox(stack);
            int xp = this.countInBox(stack, Items.EXPERIENCE_BOTTLE);
            int totems = this.countInBox(stack, Items.TOTEM_OF_UNDYING);
            boolean enough = true;
            if (this.needFirework && fireworks <= config.storeMinFW()) {
                enough = false;
            }
            if (this.needFood && foods <= config.storeMinFD()) {
                enough = false;
            }
            if (this.needRepair && xp <= 64) {
                enough = false;
            }
            if (this.needTotem && totems <= 0) {
                enough = false;
            }
            int score = (this.needFirework ? fireworks : 0) + (this.needFood ? foods : 0)
                    + (this.needRepair ? xp : 0) + (this.needTotem ? totems * 64 : 0);
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
            this.startEnderChestResupply();
            return;
        }

        ItemStack box = this.mc.player.getInventory().getItem(best);
        boolean enough = true;
        if (this.needFirework && this.countInBox(box, Items.FIREWORK_ROCKET) <= config.storeMinFW()) {
            enough = false;
        }
        if (this.needFood && this.countFoodInBox(box) <= config.storeMinFD()) {
            enough = false;
        }
        if (this.needRepair && this.countInBox(box, Items.EXPERIENCE_BOTTLE) <= 64) {
            enough = false;
        }
        if (this.needTotem && this.countInBox(box, Items.TOTEM_OF_UNDYING) <= 0) {
            enough = false;
        }
        if (!enough) {
            this.startEnderChestResupply();
            return;
        }

        this.info("messages.collect.store.switchingToBox");
        config.setSlotAndSync(8);
        this.boxSlot = best;
        if (this.boxSlot != 8) {
            InventoryUtils.swapWithHotbarSlot(InventoryUtils.toMenuSlot(this.boxSlot), 8);
            this.boxSlot = 8;
        }
        this.state = State.PLACE;
        this.ticks = 0;
        this.place(config);
    }

    private void startEnderChestResupply() {
        // TODO(port): 原实现构造 EnderChestResupply 走末影箱补给，该模块未移植。
        this.info("messages.collect.store.enderChestUnavailable");
        this.state = State.EC_RESUPPLY;
        this.ticks = 0;
    }

    private void enderChestResupply() {
        this.error("messages.collect.store.enderChestUnavailable");
        this.disable();
    }

    private void place(ElytraCollect config) {
        BlockPos pos = this.findPlacePos(config);
        if (pos == null) {
            this.error("messages.collect.store.noPlaceSpot");
            this.disable();
            return;
        }
        this.placedPos = pos;
        BlockPos clickPos = pos.below();
        Vec3 hitVec = new Vec3(clickPos.getX() + 0.5, clickPos.getY() + 1.0, clickPos.getZ() + 0.5);
        this.useItemOn(hitVec, new BlockHitResult(hitVec, Direction.UP, clickPos, false));
        this.info("messages.collect.store.placed", pos.getX(), pos.getY(), pos.getZ());
        this.state = State.WAIT_BOX;
        this.ticks = 0;
        this.placeRetries++;
    }

    private void waitBox(ElytraCollect config) {
        if (this.placedPos == null) {
            this.error("messages.collect.store.placeLost");
            this.disable();
            return;
        }
        if (this.mc.level.getBlockState(this.placedPos).getBlock() instanceof ShulkerBoxBlock) {
            this.state = State.OPEN_BOX;
            this.ticks = 0;
            this.openBox(config);
            return;
        }
        if (this.ticks > 10) {
            this.error("messages.collect.store.placeTimeout", this.placeRetries);
            this.state = State.PLACE;
            this.ticks = 0;
            this.place(config);
        }
    }

    private void openBox(ElytraCollect config) {
        if (this.placedPos == null) {
            this.error("messages.collect.store.placeLost");
            this.disable();
            return;
        }
        if (this.mc.player.containerMenu instanceof ShulkerBoxMenu) {
            this.info("messages.collect.store.detectedOpen");
            this.state = State.WAIT_OPEN;
            this.ticks = 0;
            this.step = 0;
            return;
        }
        if (this.ticks > 20) {
            this.error("messages.collect.store.openFailed");
            this.disable();
            return;
        }
        BlockHitResult hit = this.bestHit(this.placedPos);
        this.useItemOn(hit.getLocation(), hit);
    }

    private void waitOpen(ElytraCollect config) {
        if (this.mc.player.containerMenu instanceof ShulkerBoxMenu) {
            this.info("messages.collect.store.detectedOpen");
            this.state = State.OPEN;
            this.ticks = 0;
            this.step = 0;
            return;
        }
        if (this.ticks <= 40) {
            return;
        }
        if (this.openRetries >= 3) {
            this.error("messages.collect.store.openGiveUp");
            this.disable();
            return;
        }
        this.openRetries++;
        this.error("messages.collect.store.openTimeout", this.openRetries);
        this.state = State.OPEN_BOX;
        this.ticks = 0;
    }

    private void withdraw(ElytraCollect config) {
        if (!(this.mc.player.containerMenu instanceof ShulkerBoxMenu)) {
            this.info("messages.collect.store.boxClosed");
            this.state = State.CLOSE;
            this.ticks = 0;
            return;
        }
        if (this.ticks > 200) {
            this.info("messages.collect.store.withdrawTimeout");
            this.mc.player.closeContainer();
            this.state = State.CLOSE;
            this.ticks = 0;
            return;
        }
        if (this.ticks < 10 || this.ticks % 3 != 0) {
            return;
        }

        int targetFirework = config.storeFwSupply() * 64;
        int targetFood = config.storeFdSupply() * 64;
        int targetXp = config.storeXpSupply() * 64;
        int targetTotem = config.storeTotemSupply();
        switch (this.step) {
            case 0 -> {
                if (this.needFirework && this.countItem(Items.FIREWORK_ROCKET) < targetFirework
                        && this.boxHas(Items.FIREWORK_ROCKET)) {
                    this.quickMove(Items.FIREWORK_ROCKET);
                } else {
                    this.step = 1;
                }
            }
            case 1 -> {
                if (this.needFood && this.countFood() < targetFood && this.boxHasFood()) {
                    this.quickMoveFood();
                } else {
                    this.step = 2;
                }
            }
            case 2 -> {
                if (this.needRepair && this.countItem(Items.EXPERIENCE_BOTTLE) < targetXp
                        && this.boxHas(Items.EXPERIENCE_BOTTLE)) {
                    this.quickMove(Items.EXPERIENCE_BOTTLE);
                } else {
                    this.step = 3;
                }
            }
            case 3 -> {
                if (this.needTotem && this.countItem(Items.TOTEM_OF_UNDYING) < targetTotem
                        && this.boxHas(Items.TOTEM_OF_UNDYING)) {
                    this.quickMove(Items.TOTEM_OF_UNDYING);
                } else {
                    this.step = 4;
                }
            }
            default -> {
                this.info("messages.collect.store.withdrawDone");
                this.state = State.CLOSE;
                this.ticks = 0;
            }
        }
    }

    private void close(ElytraCollect config) {
        if (this.mc.player.containerMenu instanceof ShulkerBoxMenu) {
            this.mc.player.closeContainer();
        }
        if (this.ticks < 5) {
            return;
        }
        if (this.needRepair) {
            this.info("messages.collect.store.startRepair");
            this.state = State.REPAIR;
            this.ticks = 0;
            return;
        }
        this.info("messages.collect.store.cleanupBox");
        this.state = State.MINE_BOX;
        this.ticks = 0;
        this.mineBox(config);
    }

    private void repair(ElytraCollect config) {
        ItemStack chest = this.mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() != Items.ELYTRA) {
            this.error("messages.collect.store.noElytra");
            this.disable();
            return;
        }
        float percent = chest.getMaxDamage() > 0
                ? (1.0F - (float) chest.getDamageValue() / chest.getMaxDamage()) * 100.0F
                : 100.0F;
        if (percent >= 98.0F) {
            this.info("messages.collect.store.repaired");
            this.state = State.MINE_BOX;
            this.ticks = 0;
            this.mineBox(config);
            return;
        }

        int xpSlot = InventoryUtils.findInventorySlot(Items.EXPERIENCE_BOTTLE);
        if (xpSlot < 0) {
            this.error("messages.collect.store.noXpBottle");
            this.disable();
            return;
        }
        config.setSlotAndSync(7);
        if (xpSlot != 7) {
            if (xpSlot < 9) {
                config.setSlotAndSync(xpSlot);
            } else {
                config.setSlotAndSync(7);
                InventoryUtils.swapWithHotbarSlot(InventoryUtils.toMenuSlot(xpSlot), 7);
            }
        }
        if (this.ticks == 1) {
            this.mc.player.jumpFromGround();
        }
        if (this.ticks % config.storeXpInterval() == 0) {
            this.rotateAndUse(this.mc.player.getYRot(), 90.0F);
        }
    }

    private void mineBox(ElytraCollect config) {
        if (this.placedPos == null) {
            this.info("messages.collect.store.noBoxSkipCleanup");
            this.done(config);
            return;
        }
        BlockState state = this.mc.level.getBlockState(this.placedPos);
        if (this.mc.level.isEmptyBlock(this.placedPos) || state.canBeReplaced()) {
            this.info("messages.collect.store.boxGone");
            config.stopMining();
            this.state = State.PICKUP;
            this.ticks = 0;
            this.pickup(config);
            return;
        }

        config.mineBlock(this.placedPos);
        if (this.ticks <= 200) {
            return;
        }
        if (this.mineRetries >= 3) {
            this.error("messages.collect.store.mineGiveUp");
            config.stopMining();
            this.state = State.PICKUP;
            this.ticks = 0;
            this.pickup(config);
            return;
        }
        this.mineRetries++;
        this.error("messages.collect.store.mineRestart", this.mineRetries);
        config.stopMining();
        this.ticks = 0;
    }

    private void pickup(ElytraCollect config) {
        if (this.ticks == 0) {
            this.preBoxCount = this.countBoxByName(config);
            ChatUtils.sendChatOrCommand("#pickup " + SHULKER_IDS);
        }
        if (this.ticks > 20 && this.countBoxByName(config) > this.preBoxCount) {
            this.info("messages.collect.store.pickedUp", this.preBoxCount, this.countBoxByName(config));
            this.done(config);
            return;
        }
        if (this.ticks > 100) {
            this.error("messages.collect.store.pickupTimeout");
            this.ticks = 0;
            this.pickup(config);
        }
    }

    private void done(ElytraCollect config) {
        ChatUtils.sendChatOrCommand("#stop");
        this.info("messages.collect.store.cleanupDone");
        enableIfInactive(StowElytra.class);
        this.state = State.DONE;
        this.disable();
    }

    // ------------------------------------------------------------------ 背包 / 盒子判定

    private boolean isSupplyBox(ItemStack stack, ElytraCollect config) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock
                && stack.getHoverName().getString().contains(config.storeBoxName());
    }

    private int countInBox(ItemStack box, Item item) {
        ItemContainerContents contents = box.get(DataComponents.CONTAINER);
        if (contents == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack stack : contents.nonEmptyItems()) {
            if (stack.getItem() == item) {
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
            ItemStack stack = this.mc.player.getInventory().getItem(i);
            if (stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countFood() {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (FOODS.contains(this.mc.player.getInventory().getItem(i).getItem())) {
                count += this.mc.player.getInventory().getItem(i).getCount();
            }
        }
        return count;
    }

    private boolean boxHas(Item item) {
        ShulkerBoxMenu menu = (ShulkerBoxMenu) this.mc.player.containerMenu;
        for (int i = 0; i < 27; i++) {
            if (menu.getSlot(i).getItem().getItem() == item) {
                return true;
            }
        }
        return false;
    }

    private boolean boxHasFood() {
        ShulkerBoxMenu menu = (ShulkerBoxMenu) this.mc.player.containerMenu;
        for (int i = 0; i < 27; i++) {
            if (FOODS.contains(menu.getSlot(i).getItem().getItem())) {
                return true;
            }
        }
        return false;
    }

    private void quickMove(Item item) {
        ShulkerBoxMenu menu = (ShulkerBoxMenu) this.mc.player.containerMenu;
        for (int i = 0; i < 27; i++) {
            if (menu.getSlot(i).getItem().getItem() == item) {
                this.mc.gameMode.handleInventoryMouseClick(menu.containerId, i, 0, ClickType.QUICK_MOVE, this.mc.player);
                return;
            }
        }
    }

    private void quickMoveFood() {
        ShulkerBoxMenu menu = (ShulkerBoxMenu) this.mc.player.containerMenu;
        for (int i = 0; i < 27; i++) {
            if (FOODS.contains(menu.getSlot(i).getItem().getItem())) {
                this.mc.gameMode.handleInventoryMouseClick(menu.containerId, i, 0, ClickType.QUICK_MOVE, this.mc.player);
                return;
            }
        }
    }

    private int countBoxByName(ElytraCollect config) {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = this.mc.player.getInventory().getItem(i);
            if (stack.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock() instanceof ShulkerBoxBlock
                    && stack.getHoverName().getString().contains(config.storeBoxName())) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ 放置

    private BlockPos findPlacePos(ElytraCollect config) {
        int range = config.storePlaceRange();
        BlockPos playerPos = this.mc.player.blockPosition();
        BlockPos best = null;
        double bestDistance = 999.0;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int x = -range; x <= range; x++) {
            for (int z = -range; z <= range; z++) {
                for (int y = -2; y <= 2; y++) {
                    mutable.set(playerPos.getX() + x, playerPos.getY() + y, playerPos.getZ() + z);
                    BlockPos below = mutable.below();
                    BlockState belowState = this.mc.level.getBlockState(below);
                    if (!this.mc.level.isEmptyBlock(mutable)
                            || belowState.isAir()
                            || !belowState.isRedstoneConductor(this.mc.level, below)
                            || this.isClickable(belowState.getBlock())) {
                        continue;
                    }
                    if (!this.mc.level.getBlockState(mutable).canBeReplaced()) {
                        continue;
                    }
                    AABB blockBox = new AABB(mutable);
                    if (this.mc.player.getBoundingBox().intersects(blockBox)) {
                        continue;
                    }
                    if (!this.mc.level.getEntities(this.mc.player, blockBox, entity -> entity instanceof LivingEntity).isEmpty()) {
                        continue;
                    }
                    double distance = mutable.getCenter().distanceToSqr(this.mc.player.getEyePosition());
                    if (distance >= 1.0 && distance < bestDistance) {
                        bestDistance = distance;
                        best = mutable.immutable();
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

    /** 离眼睛最近的那个面，连命中点一起返回。 */
    private BlockHitResult bestHit(BlockPos pos) {
        Vec3 eye = this.mc.player.getEyePosition();
        Direction best = Direction.UP;
        double bestDistance = Double.MAX_VALUE;
        for (Direction direction : Direction.values()) {
            double cx = pos.getX() + 0.5 + direction.getUnitVec3i().getX() * 0.5;
            double cy = pos.getY() + 0.5 + direction.getUnitVec3i().getY() * 0.5;
            double cz = pos.getZ() + 0.5 + direction.getUnitVec3i().getZ() * 0.5;
            double distance = eye.distanceToSqr(cx, cy, cz);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = direction;
            }
        }
        Vec3 hitVec = new Vec3(
                pos.getX() + 0.5 + best.getUnitVec3i().getX() * 0.5,
                pos.getY() + 0.5 + best.getUnitVec3i().getY() * 0.5,
                pos.getZ() + 0.5 + best.getUnitVec3i().getZ() * 0.5);
        return new BlockHitResult(hitVec, best, pos, false);
    }

    // ------------------------------------------------------------------ 朝向 / 使用

    private void useItemOn(Vec3 hitVec, BlockHitResult hit) {
        if (ClientContext.rotationManager != null) {
            float[] angles = this.lookAngles(hitVec);
            ClientContext.rotationManager.requestRotation(angles[0], angles[1], 100, true);
            ClientContext.rotationManager.executeAction(
                    () -> this.mc.gameMode.useItemOn(this.mc.player, InteractionHand.MAIN_HAND, hit));
            return;
        }
        this.mc.gameMode.useItemOn(this.mc.player, InteractionHand.MAIN_HAND, hit);
    }

    private void rotateAndUse(float yaw, float pitch) {
        if (ClientContext.rotationManager != null) {
            ClientContext.rotationManager.requestRotation(yaw, pitch, 100, true);
            ClientContext.rotationManager.executeAction(
                    () -> this.mc.gameMode.useItem(this.mc.player, InteractionHand.MAIN_HAND));
            return;
        }
        this.mc.gameMode.useItem(this.mc.player, InteractionHand.MAIN_HAND);
    }

    private float[] lookAngles(Vec3 target) {
        Vec3 eye = this.mc.player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return new float[]{
                (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F,
                (float) (-Math.toDegrees(Math.atan2(dy, horizontal)))
        };
    }

    private static void enableIfInactive(Class<? extends Module> type) {
        Module module = ClientContext.getModule(type);
        if (module != null && !module.isToggled()) {
            module.enable();
        }
    }

    private static final String SHULKER_IDS = "minecraft:shulker_box minecraft:white_shulker_box"
            + " minecraft:orange_shulker_box minecraft:magenta_shulker_box minecraft:light_blue_shulker_box"
            + " minecraft:yellow_shulker_box minecraft:lime_shulker_box minecraft:pink_shulker_box"
            + " minecraft:gray_shulker_box minecraft:light_gray_shulker_box minecraft:cyan_shulker_box"
            + " minecraft:purple_shulker_box minecraft:blue_shulker_box minecraft:brown_shulker_box"
            + " minecraft:green_shulker_box minecraft:red_shulker_box minecraft:black_shulker_box";

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
        MINE_BOX,
        PICKUP,
        DONE,
        EC_RESUPPLY
    }
}
