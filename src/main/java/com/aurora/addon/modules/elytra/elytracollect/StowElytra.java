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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.BlockItem;
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
 * 储存鞘翅：找一只还有空位的空潜影盒，放下、打开，把背包里未附魔未改名的鞘翅塞进去，然后挖掉
 * 盒子让服务端捡回，最后交给 {@link CollectRun} 继续下一轮。
 *
 * <p>移植自 Meteor 插件的 {@code StowElytra}，状态机与槽位换算逐条保留。挖盒子改走
 * {@link ElytraCollect#mineBlock}。
 *
 * <p>TODO(port): 盒子全满 / 一只都没有时，原实现会用 {@code EnderChestResupply} 去末影箱换盒子；
 * 该模块不在本次移植范围，这里只提示并关闭。
 */
public final class StowElytra extends AuroraModule {
    private static final int PLACE_RANGE = 4;

    private State state;
    private int ticks;
    private int chosenSlot;
    private BlockPos boxPos;
    private int preBoxCount;
    private boolean ecTakeAttempted;
    private boolean ecStoreAttempted;

    public StowElytra(ModuleCategory category) {
        super("StowElytra", "Stores plain elytra into an empty shulker box, then recycles the box.", category);
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
        this.state = State.FIND;
        this.ticks = 0;
        this.chosenSlot = -1;
        this.boxPos = null;
        this.preBoxCount = 0;
        this.ecTakeAttempted = false;
        this.ecStoreAttempted = false;
    }

    @Override
    public void onDisable() {
        this.mc.options.keyUse.setDown(false);
        this.mc.options.keyAttack.setDown(false);
        this.mc.options.keyJump.setDown(false);
        ElytraCollect config = cfg();
        if (config != null) {
            config.stopMining();
        }
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        ElytraCollect config = cfg();
        if (this.mc.player == null || config == null) {
            return;
        }
        this.ticks++;
        switch (this.state) {
            case FIND -> this.find(config);
            case PLACE -> this.place(config);
            case WAIT_BOX -> this.waitBox(config);
            case OPEN -> this.open(config);
            case STOW -> this.stow();
            case CLOSE -> this.close(config);
            case MINE -> this.mine(config);
            case PICKUP -> this.pickup(config);
            case EC_TAKE, EC_STORE -> this.enderChestUnavailable();
            case DONE -> {
            }
        }
    }

    private void find(ElytraCollect config) {
        String name = config.stowBoxName();
        int best = -1;
        int bestUsed = Integer.MAX_VALUE;
        boolean any = false;
        boolean allFull = true;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = this.mc.player.getInventory().getItem(i);
            if (!this.isStowBox(stack, name)) {
                continue;
            }
            any = true;
            ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
            int used = 0;
            if (contents != null) {
                for (ItemStack ignored : contents.nonEmptyItems()) {
                    used++;
                }
            }
            if (used < 27) {
                allFull = false;
                if (used < bestUsed) {
                    bestUsed = used;
                    best = i;
                }
            }
        }

        if (!any) {
            if (this.ecTakeAttempted) {
                this.info("messages.collect.stow.noBoxGiveUp");
                this.disable();
                return;
            }
            this.ecTakeAttempted = true;
            this.info("messages.collect.stow.noBoxFromEnderChest");
            this.state = State.EC_TAKE;
            this.ticks = 0;
            return;
        }

        if (!allFull && best >= 0) {
            this.chosenSlot = best;
            config.setSlotAndSync(8);
            if (this.chosenSlot != 8) {
                InventoryUtils.swapWithHotbarSlot(InventoryUtils.toMenuSlot(this.chosenSlot), 8);
                this.chosenSlot = 8;
            }
            this.state = State.PLACE;
            this.ticks = 0;
            this.place(config);
            return;
        }

        if (this.ecStoreAttempted) {
            this.error("messages.collect.stow.allFullGiveUp");
            this.disable();
            return;
        }
        this.ecStoreAttempted = true;
        this.info("messages.collect.stow.allFullToEnderChest");
        this.state = State.EC_STORE;
        this.ticks = 0;
    }

    private void enderChestUnavailable() {
        // TODO(port): 原实现用 EnderChestResupply 打开末影箱换盒子，该模块未移植。
        this.error("messages.collect.stow.enderChestUnavailable");
        this.disable();
    }

    private void place(ElytraCollect config) {
        BlockPos pos = this.findPlacePos();
        if (pos == null) {
            this.disable();
            return;
        }
        this.boxPos = pos;
        BlockPos clickPos = pos.below();
        Vec3 hitVec = new Vec3(clickPos.getX() + 0.5, clickPos.getY() + 1.0, clickPos.getZ() + 0.5);
        this.useItemOn(hitVec, new BlockHitResult(hitVec, Direction.UP, clickPos, false));
        this.state = State.WAIT_BOX;
        this.ticks = 0;
    }

    private void waitBox(ElytraCollect config) {
        if (this.boxPos == null) {
            this.error("messages.collect.stow.boxLost");
            this.state = State.FIND;
            this.ticks = 0;
            return;
        }
        if (this.mc.level.getBlockState(this.boxPos).getBlock() instanceof ShulkerBoxBlock) {
            this.state = State.OPEN;
            this.ticks = 0;
            this.open(config);
            return;
        }
        if (this.ticks > 40) {
            this.error("messages.collect.stow.boxMissing");
            this.boxPos = null;
            this.state = State.FIND;
            this.ticks = 0;
        }
    }

    private void open(ElytraCollect config) {
        if (this.boxPos == null || !(this.mc.level.getBlockState(this.boxPos).getBlock() instanceof ShulkerBoxBlock)) {
            this.boxPos = null;
            this.state = State.FIND;
            this.ticks = 0;
            return;
        }
        if (this.mc.player.containerMenu instanceof ShulkerBoxMenu) {
            if (this.ticks >= 10) {
                this.state = State.STOW;
                this.ticks = 0;
            }
            return;
        }
        if (this.ticks > 20) {
            if (this.mc.player.containerMenu != this.mc.player.inventoryMenu) {
                this.mc.player.closeContainer();
            }
            this.error("messages.collect.stow.openTimeout");
            this.state = State.OPEN;
            this.ticks = 0;
            return;
        }
        Vec3 hitVec = new Vec3(this.boxPos.getX() + 0.5, this.boxPos.getY() + 0.5, this.boxPos.getZ() + 0.5);
        this.useItemOn(hitVec, new BlockHitResult(hitVec, this.bestFace(this.boxPos), this.boxPos, false));
    }

    private void stow() {
        ShulkerBoxMenu menu = (ShulkerBoxMenu) this.mc.player.containerMenu;
        if (this.ticks % 3 != 0) {
            return;
        }
        for (int i = 0; i < 36; i++) {
            ItemStack stack = this.mc.player.getInventory().getItem(i);
            if (stack.getItem() == Items.ELYTRA && !stack.isEnchanted() && !stack.has(DataComponents.CUSTOM_NAME)) {
                int guiSlot = i < 9 ? 54 + i : 27 + (i - 9);
                this.mc.gameMode.handleInventoryMouseClick(menu.containerId, guiSlot, 0, ClickType.QUICK_MOVE, this.mc.player);
                return;
            }
        }
        this.mc.player.closeContainer();
        this.state = State.CLOSE;
        this.ticks = 0;
    }

    private void close(ElytraCollect config) {
        if (this.ticks < 5) {
            return;
        }
        config.setSlotAndSync(1);
        this.state = State.MINE;
        this.ticks = 0;
        this.mine(config);
    }

    private void mine(ElytraCollect config) {
        if (this.boxPos == null) {
            this.state = State.PICKUP;
            this.ticks = 0;
            this.pickup(config);
            return;
        }
        BlockState state = this.mc.level.getBlockState(this.boxPos);
        if (this.mc.level.isEmptyBlock(this.boxPos) || state.canBeReplaced()) {
            this.info("messages.collect.stow.boxMined");
            config.stopMining();
            this.state = State.PICKUP;
            this.ticks = 0;
            this.pickup(config);
            return;
        }
        config.mineBlock(this.boxPos);
        if (this.ticks > 200) {
            this.error("messages.collect.stow.mineTimeout");
            config.stopMining();
            this.state = State.PICKUP;
            this.ticks = 0;
            this.pickup(config);
        }
    }

    private void pickup(ElytraCollect config) {
        if (this.ticks == 0) {
            this.preBoxCount = this.countBoxByName(config);
            ChatUtils.sendChatOrCommand("#pickup minecraft:shulker_box");
        }
        if (this.ticks > 20 && this.countBoxByName(config) > this.preBoxCount) {
            this.info("messages.collect.stow.pickedUp", this.preBoxCount, this.countBoxByName(config));
            ChatUtils.sendChatOrCommand("#stop");
            this.disable();
            enableIfInactive(CollectRun.class);
            return;
        }
        if (this.ticks > 100) {
            this.error("messages.collect.stow.pickupTimeout");
            this.preBoxCount = this.countBoxByName(config);
            ChatUtils.sendChatOrCommand("#pickup minecraft:shulker_box");
            this.ticks = 0;
        }
    }

    private boolean isStowBox(ItemStack stack, String name) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock
                && stack.getHoverName().getString().contains(name);
    }

    private int countBoxByName(ElytraCollect config) {
        int count = 0;
        String target = config.stowBoxName();
        for (int i = 0; i < 36; i++) {
            if (this.isStowBox(this.mc.player.getInventory().getItem(i), target)) {
                count++;
            }
        }
        return count;
    }


    private BlockPos findPlacePos() {
        BlockPos playerPos = this.mc.player.blockPosition();
        BlockPos best = null;
        double bestDistance = 999.0;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int x = -PLACE_RANGE; x <= PLACE_RANGE; x++) {
            for (int z = -PLACE_RANGE; z <= PLACE_RANGE; z++) {
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

    private Direction bestFace(BlockPos pos) {
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
        return best;
    }

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

    private enum State {
        FIND,
        PLACE,
        WAIT_BOX,
        OPEN,
        STOW,
        CLOSE,
        MINE,
        PICKUP,
        DONE,
        EC_TAKE,
        EC_STORE
    }
}
