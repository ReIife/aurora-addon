package com.aurora.addon.modules.elytra.elytracollect;

import com.aurora.addon.modules.AuroraModule;
import com.aurora.addon.modules.elytra.ElytraRouteCalculate;
import com.aurora.addon.modules.elytra.ElytraCollect;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.Module;
import evernight.client.modules.ModuleCategory;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * 路径点锁定：把水平视角（可选垂直）锁到「计算路线」模块路线里的下一个路径点。
 *
 * <p>移植自 Meteor 插件的 {@code WaypointLock}。
 *
 * <p>路线来源是 {@link ElytraRouteCalculate#getRoute()}：与上游一致，直接按类型取模块，
 * 模块未开启或路线为空时保持视角不动。
 */
public final class WaypointLock extends AuroraModule {
    private Vec3 prevTarget;

    public WaypointLock(ModuleCategory category) {
        super("WaypointLock", "Locks the view to the next waypoint of the calculated route.", category);
    }

    private static ElytraCollect cfg() {
        return ElytraCollect.cfg();
    }

    @Override
    public void onEnable() {
        this.prevTarget = null;
    }

    @Override
    public void onDisable() {
        this.prevTarget = null;
    }

    private List<Vec3> currentRoute() {
        ElytraRouteCalculate route = ClientContext.getModule(ElytraRouteCalculate.class);
        if (route == null || !route.isToggled()) {
            return Collections.emptyList();
        }
        List<Vec3> points = route.getRoute();
        return points == null ? Collections.emptyList() : points;
    }

    private Float computeYaw() {
        ElytraCollect config = cfg();
        if (this.mc.player == null || config == null || !config.waypointLockYaw()) {
            return null;
        }
        List<Vec3> route = this.currentRoute();
        if (route.isEmpty()) {
            return null;
        }
        Vec3 target = route.get(0);
        double dx = target.x - this.mc.player.getX();
        double dz = target.z - this.mc.player.getZ();
        return (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        Float yaw = this.computeYaw();
        if (yaw == null) {
            return;
        }
        ElytraCollect config = cfg();
        this.mc.player.setYRot(yaw);
        if (config != null && config.waypointLockPitch()) {
            this.mc.player.setXRot(0.0F);
        }

        List<Vec3> route = this.currentRoute();
        if (route.isEmpty()) {
            return;
        }
        Vec3 target = route.get(0);
        if (this.prevTarget == null
                || (int) this.prevTarget.x != (int) target.x
                || (int) this.prevTarget.z != (int) target.z) {
            double dx = target.x - this.mc.player.getX();
            double dz = target.z - this.mc.player.getZ();
            this.info("messages.collect.waypointLock.locked",
                    (int) target.x, (int) target.z, (int) Math.sqrt(dx * dx + dz * dz));
            this.prevTarget = target;
        }
    }
}
