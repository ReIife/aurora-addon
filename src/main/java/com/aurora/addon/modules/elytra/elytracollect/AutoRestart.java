package com.aurora.addon.modules.elytra.elytracollect;

import com.aurora.addon.modules.AuroraModule;
import com.aurora.addon.modules.elytra.ElytraCollect;
import com.aurora.addon.modules.elytra.ElytraPitch40;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.Module;
import evernight.client.modules.ModuleCategory;
import evernight.client.utils.chat.ChatUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 自动重启（一次性）：被打开时清理子模块，按当前状态拉起入口 —— 滑翔中交给 {@link LockEnd}，
 * 已在末地船里交给 {@link CollectRun}，否则先升空到目标 Y 再交给 {@link WaypointLock} +
 * {@link ElytraPitch40}，交接完成即自行关闭。
 *
 * <p>移植自 Meteor 插件的 {@code AutoRestart}，计时与交接顺序逐条保留。
 */
public final class AutoRestart extends AuroraModule {
    private int globalTick;
    private int restartAt = -1;
    private int restartCount;
    private boolean flyPhase;
    private int flyTicks;
    private int fireworkCooldown;
    private int jumpTimer;
    private int pendingLockEndCheckAt = -1;
    private int flyToYDoneAt = -1;

    public AutoRestart(ModuleCategory category) {
        super("AutoRestart", "One-shot restarter: cleans up submodules and starts the right entry point.", category);
    }

    private static ElytraCollect cfg() {
        return ElytraCollect.cfg();
    }

    @Override
    public void onEnable() {
        ElytraCollect config = cfg();
        if (this.mc.player == null || this.mc.level == null) {
            this.disable();
            return;
        }
        if (config == null) {
            this.info("messages.collect.autoRestart.notRegistered");
            this.disable();
            return;
        }
        if (this.mc.level.dimension() != Level.END) {
            this.error("messages.collect.autoRestart.notInEnd");
            this.disable();
            return;
        }

        this.globalTick = 0;
        this.restartAt = -1;
        this.restartCount = 0;
        this.flyPhase = false;
        this.pendingLockEndCheckAt = -1;
        this.flyToYDoneAt = -1;
        this.restartAt = this.globalTick + Math.max(1, config.autoRestartStartupDelay());
    }

    @Override
    public void onDisable() {
        this.restartAt = -1;
        this.flyPhase = false;
        this.pendingLockEndCheckAt = -1;
        this.flyToYDoneAt = -1;
        if (this.mc.player != null) {
            this.mc.options.keyJump.setDown(false);
        }
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        ElytraCollect config = cfg();
        if (this.mc.player == null || this.mc.level == null || config == null) {
            return;
        }

        this.globalTick++;
        if (this.restartAt > 0 && this.globalTick >= this.restartAt) {
            this.restartAt = -1;
            this.doRestart(config);
            return;
        }

        if (this.pendingLockEndCheckAt > 0 && this.globalTick >= this.pendingLockEndCheckAt) {
            this.pendingLockEndCheckAt = -1;
            this.finishLockEndHandover();
            return;
        }

        if (this.flyToYDoneAt > 0 && this.globalTick - this.flyToYDoneAt >= 40) {
            this.flyToYDoneAt = -1;
            enableIfInactive(LockStart.class);
            this.log("messages.collect.autoRestart.flyHandover");
            this.disable();
            return;
        }

        if (this.flyPhase) {
            this.tickFlyToTargetY(config);
        }
    }

    private void finishLockEndHandover() {
        LockEnd lockEnd = ClientContext.getModule(LockEnd.class);
        boolean lockEndDead = lockEnd != null && !lockEnd.isToggled();
        if (lockEndDead) {
            this.log("messages.collect.autoRestart.lockEndDead");
            enableIfInactive(EndShipFinder.class);
            enableIfInactive(ElytraPitch40.class);
            enableIfInactive(WaypointLock.class);
            enableIfInactive(LockStart.class);
        } else {
            this.log("messages.collect.autoRestart.lockEndTookOver");
        }
        this.disable();
    }

    private void doRestart(ElytraCollect config) {
        if (this.mc.level.dimension() != Level.END) {
            this.disable();
            return;
        }
        if (this.restartCount >= config.autoRestartMaxCount()) {
            this.logError("messages.collect.autoRestart.tooManyRestarts", this.restartCount);
            this.disable();
            return;
        }

        this.log("messages.collect.autoRestart.restarting");
        disableIfActive(StoreResupply.class);
        disableIfActive(StowElytra.class);
        disableIfActive(WaypointLock.class);
        disableIfActive(LockStart.class);
        disableIfActive(LockEnd.class);
        disableIfActive(ElytraPitch40.class);
        disableIfActive(CollectRun.class);
        disableIfActive(ItemFrameSearch.class);
        ChatUtils.sendChatOrCommand("#stop");

        boolean gliding = this.mc.player.isFallFlying();
        boolean inShip = this.isInEndShip();
        this.log("messages.collect.autoRestart.state", gliding, inShip, (int) this.mc.player.getY());

        if (gliding) {
            enableIfInactive(LockEnd.class);
            this.pendingLockEndCheckAt = this.globalTick + 4;
            return;
        }
        if (inShip) {
            enableIfInactive(CollectRun.class);
            this.log("messages.collect.autoRestart.runHandover");
            this.disable();
            return;
        }
        this.startFlyToTargetY(config);
    }

    private boolean isInEndShip() {
        BlockPos below = this.mc.player.blockPosition().below();
        Block block = this.mc.level.getBlockState(below).getBlock();
        return block == Blocks.PURPUR_BLOCK
                || block == Blocks.PURPUR_PILLAR
                || block == Blocks.OBSIDIAN
                || block == Blocks.PURPUR_STAIRS
                || block == Blocks.PURPUR_SLAB
                || block == Blocks.MAGENTA_STAINED_GLASS;
    }

    private void startFlyToTargetY(ElytraCollect config) {
        ItemStack chest = this.mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() != Items.ELYTRA) {
            this.logError("messages.collect.autoRestart.noElytra");
            this.disable();
            return;
        }
        int targetY = config.autoRestartFlyToY();
        if (Math.abs(this.mc.player.getY() - targetY) < 5.0) {
            this.log("messages.collect.autoRestart.alreadyAtHeight");
            this.continueAfterFly();
            return;
        }
        this.flyPhase = true;
        this.flyTicks = 0;
        this.fireworkCooldown = 0;
        this.jumpTimer = 0;
        this.log("messages.collect.autoRestart.startFly", targetY, (int) this.mc.player.getY());
    }

    private void tickFlyToTargetY(ElytraCollect config) {
        this.flyTicks++;
        double y = this.mc.player.getY();
        int targetY = config.autoRestartFlyToY();
        this.mc.player.setXRot(-45.0F);

        if (this.mc.player.isFallFlying()) {
            if (this.fireworkCooldown > 0) {
                this.fireworkCooldown--;
            }
            if (y < targetY - 5 && this.fireworkCooldown <= 0) {
                config.useFirework(true);
                this.fireworkCooldown = 60;
            }
            if (y >= targetY - 5) {
                this.flyPhase = false;
                this.log("messages.collect.autoRestart.reachedHeight", (int) y);
                this.continueAfterFly();
                return;
            }
        } else if (this.jumpTimer > 0) {
            if (this.jumpTimer == 3) {
                this.mc.options.keyJump.setDown(false);
            }
            if (this.jumpTimer == 2) {
                this.mc.options.keyJump.setDown(true);
            }
            if (this.jumpTimer == 1) {
                this.mc.options.keyJump.setDown(false);
                config.useFirework(true);
            }
            this.jumpTimer--;
        } else {
            this.mc.options.keyJump.setDown(true);
            this.jumpTimer = 4;
        }

        if (this.flyTicks > config.autoRestartFlyTimeout()) {
            this.flyPhase = false;
            this.mc.options.keyJump.setDown(false);
            this.logError("messages.collect.autoRestart.flyTimeout");
            this.continueAfterFly();
        }
    }

    private void continueAfterFly() {
        enableIfInactive(WaypointLock.class);
        enableIfInactive(ElytraPitch40.class);
        this.flyToYDoneAt = this.globalTick;
    }

    private void log(String key, Object... args) {
        ElytraCollect config = cfg();
        if (config != null) {
            config.info(key, args);
        }
    }

    private void logError(String key, Object... args) {
        ElytraCollect config = cfg();
        if (config != null) {
            config.error(key, args);
        }
    }

    private static void enableIfInactive(Class<? extends Module> type) {
        Module module = ClientContext.getModule(type);
        if (module != null && !module.isToggled()) {
            module.enable();
        }
    }

    private static void disableIfActive(Class<? extends Module> type) {
        Module module = ClientContext.getModule(type);
        if (module != null && module.isToggled()) {
            module.disable();
        }
    }
}
