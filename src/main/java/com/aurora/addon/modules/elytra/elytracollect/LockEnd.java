package com.aurora.addon.modules.elytra.elytracollect;

import com.aurora.addon.modules.AuroraModule;
import com.aurora.addon.modules.elytra.ElytraCollect;
import com.aurora.addon.modules.elytra.ElytraPitch40;
import com.aurora.addon.utils.BlacklistUtils;
import com.aurora.addon.utils.TiziStorage;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.render.WorldRenderEvent;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.Module;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.mesh.WorldBoxRenderer;
import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * 锁定起飞：从 {@code zuobiao.json} 取最近的梯子链坐标，在其上方 {@code LockEndAimAbove} 格
 * 建锁定点并飞过去；到达后写黑名单、清空坐标文件，把控制权交给 {@link WaypointLock} +
 * {@link ElytraPitch40}，40 tick 后再交给 {@link LockStart}。
 *
 * <p>移植自 Meteor 插件的 {@code LockEnd}。原实现分别在 Pre / Post tick 各校正一次朝向，
 * Evernight 只有一个 {@code MinecraftTickEvent}，这里合并成每 tick 一次。
 */
public final class LockEnd extends AuroraModule {
    private BlockPos lockTarget;
    private BlockPos ladderPos;
    private int fireworkCooldown;
    private int jumpTimer;
    private boolean arrived;
    private int sequenceTimer;

    public LockEnd(ModuleCategory category) {
        super("LockEnd", "Reads the nearest saved coordinate and flies to a lock point above it.", category);
    }

    private static ElytraCollect cfg() {
        return ElytraCollect.cfg();
    }

    @Override
    public void onEnable() {
        this.lockTarget = null;
        this.ladderPos = null;
        this.fireworkCooldown = 0;
        this.jumpTimer = 0;
        this.arrived = false;
        this.sequenceTimer = 0;

        ElytraCollect config = cfg();
        if (config == null || this.mc.player == null) {
            this.disable();
            return;
        }
        BlockPos nearest = this.findNearest();
        if (nearest == null) {
            this.error("messages.collect.lockEnd.noTarget");
            this.disable();
            return;
        }
        this.ladderPos = nearest;
        this.lockTarget = new BlockPos(nearest.getX(), nearest.getY() + config.lockEndAimAbove(), nearest.getZ());
        this.info("messages.collect.lockEnd.target",
                this.lockTarget.getX(), this.lockTarget.getY(), this.lockTarget.getZ(),
                (int) Math.sqrt(this.lockTarget.distToLowCornerSqr(
                        this.mc.player.getX(), this.mc.player.getY(), this.mc.player.getZ())));
    }

    @Override
    public void onDisable() {
        this.mc.options.keyJump.setDown(false);
        this.lockTarget = null;
        this.ladderPos = null;
        this.arrived = false;
        this.sequenceTimer = 0;
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        ElytraCollect config = cfg();
        if (this.mc.player == null || this.lockTarget == null || config == null) {
            return;
        }

        double distance = Math.sqrt(this.lockTarget.distToLowCornerSqr(
                this.mc.player.getX(), this.mc.player.getY(), this.mc.player.getZ()));

        if (!this.arrived && distance <= config.lockEndArriveDistance()) {
            this.arrived = true;
            this.writeBlacklist();
            enableIfInactive(WaypointLock.class);
            enableIfInactive(ElytraPitch40.class);
            this.sequenceTimer = 0;
            return;
        }

        if (this.arrived) {
            this.sequenceTimer++;
            if (this.sequenceTimer >= 40) {
                enableIfInactive(LockStart.class);
                this.disable();
            }
            return;
        }

        if (!this.mc.player.isFallFlying() && this.jumpTimer <= 0) {
            this.mc.options.keyJump.setDown(true);
            this.jumpTimer = 4;
        }
        if (this.jumpTimer > 0) {
            if (this.jumpTimer == 3) {
                this.mc.options.keyJump.setDown(false);
            }
            if (this.jumpTimer == 2) {
                this.mc.options.keyJump.setDown(true);
            }
            if (this.jumpTimer == 1) {
                this.mc.options.keyJump.setDown(false);
                this.releaseFirework(config);
            }
            this.jumpTimer--;
        }

        if (this.mc.player.isFallFlying() && this.mc.player.getY() < this.lockTarget.getY()) {
            if (this.fireworkCooldown > 0) {
                this.fireworkCooldown--;
            } else {
                this.releaseFirework(config);
            }
        }

        this.lookAt(this.lockTarget);
    }

    private void lookAt(BlockPos target) {
        double dx = target.getX() + 0.5 - this.mc.player.getX();
        double dy = target.getY() + 0.5
                - (this.mc.player.getY() + this.mc.player.getEyeHeight(this.mc.player.getPose()));
        double dz = target.getZ() + 0.5 - this.mc.player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        this.mc.player.setYRot((float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F);
        this.mc.player.setXRot((float) (-Math.toDegrees(Math.atan2(dy, horizontal))));
    }

    private void releaseFirework(ElytraCollect config) {
        config.useFirework(config.lockEndQuickSwap());
        this.fireworkCooldown = config.lockEndFireworkInterval();
    }

    @EventHandler
    private void onWorldRender(WorldRenderEvent.Main event) {
        if (this.lockTarget == null || this.mc.player == null) {
            return;
        }
        AABB box = new AABB(this.lockTarget);
        Color color = new Color(0, 255, 0, 120);
        WorldBoxRenderer.fillBox(event.getPoseStack(), box, color);
        WorldBoxRenderer.outlineBox(event.getPoseStack(), box, color, 1.0F);
    }

    private static void enableIfInactive(Class<? extends Module> type) {
        Module module = ClientContext.getModule(type);
        if (module != null && !module.isToggled()) {
            module.enable();
        }
    }

    private BlockPos findNearest() {
        String world = TiziStorage.worldName();
        if (world == null) {
            return null;
        }
        Path file = TiziStorage.file(world, "zuobiao.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        List<BlockPos> positions = BlacklistUtils.filterBatch(TiziStorage.readPoints(file));
        if (positions.isEmpty()) {
            this.error("messages.collect.lockEnd.allBlacklisted");
            return null;
        }
        double px = this.mc.player.getX();
        double py = this.mc.player.getY();
        double pz = this.mc.player.getZ();
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (BlockPos candidate : positions) {
            double distance = candidate.distToLowCornerSqr(px, py, pz);
            if (distance < best) {
                best = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    private void writeBlacklist() {
        if (this.ladderPos == null || BlacklistUtils.worldKey() == null) {
            return;
        }
        BlacklistUtils.add(this.ladderPos.getX(), this.ladderPos.getZ());
        this.clearZuobiao();
    }

    private void clearZuobiao() {
        String world = TiziStorage.worldName();
        if (world == null) {
            return;
        }
        Path file = TiziStorage.file(world, "zuobiao.json");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "[]");
            this.info("messages.collect.lockEnd.cleared");
        } catch (IOException e) {
            this.error("messages.collect.lockEnd.clearFailed", e.getMessage());
        }
    }
}
