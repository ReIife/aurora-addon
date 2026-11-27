package com.aurora.addon.modules.elytra.elytracollect;

import com.aurora.addon.modules.AuroraModule;
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
 * <p>TODO(port): 原实现直接 {@code Modules.get().get(ElytraRouteCalculate.class)} 并调用
 * {@code getRoute()}；计算路线模块不在本次鞘翅收集的移植范围里，工程内没有对应类型。这里改为
 * 按模块名 {@code ElytraRouteCalculate} 在注册表里查找，再用反射读 {@code getRoute()}，
 * 这样模块本身可以编译，且等计算路线模块合并进来后无需改代码即可生效；找不到时保持不动。
 */
public final class WaypointLock extends AuroraModule {
    private static final String ROUTE_MODULE_NAME = "ElytraRouteCalculate";

    private Vec3 prevTarget;
    private Method getRouteMethod;

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

    private Module routeModule() {
        if (ClientContext.moduleRegistry == null) {
            return null;
        }
        Module module = ClientContext.moduleRegistry.findFirstOrNull(
                candidate -> candidate.getName().equalsIgnoreCase(ROUTE_MODULE_NAME));
        return module != null && module.isToggled() ? module : null;
    }

    @SuppressWarnings("unchecked")
    private List<Vec3> currentRoute() {
        Module module = this.routeModule();
        if (module == null) {
            return Collections.emptyList();
        }
        try {
            if (this.getRouteMethod == null) {
                this.getRouteMethod = module.getClass().getMethod("getRoute");
            }
            Object route = this.getRouteMethod.invoke(module);
            return route instanceof List<?> list ? (List<Vec3>) list : Collections.emptyList();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Collections.emptyList();
        }
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
