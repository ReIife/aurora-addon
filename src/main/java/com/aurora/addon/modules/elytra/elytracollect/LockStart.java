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
import evernight.client.modules.movement.ElytraFly.ElytraFlyModule;
import evernight.client.render.mesh.WorldBoxRenderer;
import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * 锁定降落：取 {@code zuobiao.json} 里最近的梯子链坐标，在上方建锁定点飞过去；进入下降半径后
 * 保持水平、逐 tick 跟随 Y 轴下降，落到梯子链附近就交给 {@link ItemFrameSearch}。
 *
 * <p>移植自 Meteor 插件的 {@code LockStart}。原实现分别在 Pre / Post tick 各校正一次朝向，
 * 这里合并成每 tick 一次；{@code ElytraFly} 对应本体的 {@link ElytraFlyModule}。
 */
public final class LockStart extends AuroraModule {
    private static final int LANDED_TIMEOUT = 40;

    private BlockPos lockTarget;
    private BlockPos fixedTarget;
    private BlockPos ladderPos;
    private boolean descending;
    private int fireworkCooldown;
    private int scanTimer;
    private boolean hasFlown;
    private int landedTimer;

    public LockStart(ModuleCategory category) {
        super("LockStart", "Reads the nearest saved coordinate and glides down to the ladder chain.", category);
    }

    private static ElytraCollect cfg() {
        return ElytraCollect.cfg();
    }

    @Override
    public void onEnable() {
        this.lockTarget = null;
        this.fixedTarget = null;
        this.ladderPos = null;
        this.descending = false;
        this.fireworkCooldown = 0;
        this.scanTimer = 0;
        this.hasFlown = false;
        this.landedTimer = 0;
        this.tryLock();
    }

    @Override
    public void onDisable() {
        this.lockTarget = null;
        this.fixedTarget = null;
        this.ladderPos = null;
        this.descending = false;
        this.hasFlown = false;
        this.landedTimer = 0;
    }

    private boolean tryLock() {
        ElytraCollect config = cfg();
        if (config == null || this.mc.player == null) {
            return false;
        }
        BlockPos nearest = this.findNearest();
        if (nearest == null) {
            return false;
        }
        this.ladderPos = nearest;
        this.lockTarget = new BlockPos(nearest.getX(), nearest.getY() + config.lockStartAimAbove(), nearest.getZ());
        this.fixedTarget = this.lockTarget;
        this.descending = false;
        double distance = Math.sqrt(this.lockTarget.distToLowCornerSqr(
                this.mc.player.getX(), this.mc.player.getY(), this.mc.player.getZ()));
        this.info("messages.collect.lockStart.target",
                this.lockTarget.getX(), this.lockTarget.getY(), this.lockTarget.getZ(), (int) distance);

        disableIfActive(WaypointLock.class);
        disableIfActive(ElytraPitch40.class);
        disableIfActive(ElytraFlyModule.class);
        return true;
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        ElytraCollect config = cfg();
        if (this.mc.player == null || config == null) {
            return;
        }
        if (this.lockTarget == null) {
            this.scanTimer++;
            if (this.scanTimer >= 40) {
                this.scanTimer = 0;
                this.tryLock();
            }
            return;
        }

        if (this.ladderPos != null) {
            double dx = this.ladderPos.getX() - this.mc.player.getX();
            double dz = this.ladderPos.getZ() - this.mc.player.getZ();
            double dy = Math.abs(this.ladderPos.getY() - this.mc.player.getY());
            if (this.withinArriveRange(dx, dz, dy, config)) {
                this.info("messages.collect.lockStart.arrived");
                this.disable();
                enableIfInactive(ItemFrameSearch.class);
                return;
            }
        }

        if (this.mc.player.isFallFlying()) {
            this.hasFlown = true;
        }

        if (this.hasFlown && !this.mc.player.isFallFlying() && this.mc.player.onGround()) {
            this.landedTimer++;
            if (this.landedTimer >= LANDED_TIMEOUT) {
                boolean arrived = false;
                if (this.ladderPos != null) {
                    double dx = this.ladderPos.getX() - this.mc.player.getX();
                    double dz = this.ladderPos.getZ() - this.mc.player.getZ();
                    double dy = Math.abs(this.ladderPos.getY() - this.mc.player.getY());
                    arrived = this.withinArriveRange(dx, dz, dy, config);
                }
                if (!arrived) {
                    this.error("messages.collect.lockStart.landedTooEarly");
                    this.disable();
                    enableIfInactive(LockEnd.class);
                    return;
                }
            }
        } else {
            this.landedTimer = 0;
        }

        double dx = this.lockTarget.getX() + 0.5 - this.mc.player.getX();
        double dz = this.lockTarget.getZ() + 0.5 - this.mc.player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        if (!this.descending
                && horizontal <= config.lockStartDescendRadius()
                && Math.abs(this.fixedTarget.getY() - this.mc.player.getY()) <= config.lockStartDescendHeight()) {
            this.descending = true;
        }

        if (this.descending) {
            this.lockTarget = new BlockPos(this.lockTarget.getX(), (int) this.mc.player.getY(), this.lockTarget.getZ());
            this.mc.player.setYRot((float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F);
            this.mc.player.setXRot(0.0F);
        } else {
            this.lookAt(this.fixedTarget);
        }

        if (config.lockStartAutoFirework()
                && this.mc.player.isFallFlying()
                && this.mc.player.getY() < this.lockTarget.getY()) {
            if (this.fireworkCooldown > 0) {
                this.fireworkCooldown--;
            } else {
                config.useFirework(config.lockStartQuickSwap());
                this.fireworkCooldown = config.lockStartFireworkInterval();
            }
        }
    }

    private boolean withinArriveRange(double dx, double dz, double dy, ElytraCollect config) {
        double radius = config.lockStartArriveDistance();
        return dx * dx + dz * dz <= radius * radius && dy <= config.lockStartArriveHeight();
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

    @EventHandler
    private void onWorldRender(WorldRenderEvent.Main event) {
        if (this.lockTarget == null || this.mc.player == null) {
            return;
        }
        AABB box = new AABB(this.descending ? this.lockTarget : this.fixedTarget);
        Color color = new Color(255, 0, 0, 120);
        WorldBoxRenderer.fillBox(event.getPoseStack(), box, color);
        WorldBoxRenderer.outlineBox(event.getPoseStack(), box, color, 1.0F);
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
}
