package com.aurora.addon.utils;

import evernight.client.ClientAccess;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.storage.WorldData;

/**
 * Xaero 小地图路径点写入 / 删除工具：优先走反射直接改运行中的 WaypointSet，同时兜底写
 * {@code waypoints.txt}，所以即使没装 Xaero 也能留下文件记录。
 *
 * <p>移植自 Meteor 版同名类，反射目标（{@code xaero.*}）保持不变；只把
 * {@code MeteorClient.mc} 换成 {@link ClientAccess#evernightMinecraft}。
 *
 * <p>本类的调用方是「计算路线」模块，不在本次鞘翅收集移植范围内；这里保持可用即可。
 */
public final class PointUtils {
    private static boolean initTried;
    private static Object cachedWaypointSet;
    private static Method wpGetName;
    private static Method wpGetX;
    private static Method wpGetZ;
    private static Method wpsGetWaypoints;
    private static Method wpsAdd;
    private static Method wpsRemove;
    private static Constructor<?> wpCtor;

    private PointUtils() {
    }

    private static void init() {
        if (initTried) {
            return;
        }
        initTried = true;
        try {
            Class<?> hudMods = Class.forName("xaero.hud.minimap.BuiltInHudModules");
            Object minimap = hudMods.getField("MINIMAP").get(null);
            Object session = minimap.getClass().getMethod("getCurrentSession").invoke(minimap);
            if (session == null) {
                return;
            }
            Object worldMgr = session.getClass().getMethod("getWorldManager").invoke(session);
            if (worldMgr == null) {
                return;
            }
            Object world = worldMgr.getClass().getMethod("getCurrentWorld").invoke(worldMgr);
            if (world == null) {
                return;
            }
            cachedWaypointSet = world.getClass().getMethod("getCurrentWaypointSet").invoke(world);
            Class<?> wpCls = Class.forName("xaero.common.minimap.waypoints.Waypoint");
            wpGetName = wpCls.getMethod("getName");
            wpGetX = wpCls.getMethod("getX");
            wpGetZ = wpCls.getMethod("getZ");
            wpCtor = wpCls.getConstructor(int.class, int.class, int.class, String.class, String.class, int.class);
            Class<?> wpsCls = Class.forName("xaero.hud.minimap.waypoint.set.WaypointSet");
            wpsGetWaypoints = wpsCls.getMethod("getWaypoints");
            wpsAdd = wpsCls.getMethod("add", wpCls);
            wpsRemove = findRemoveMethod(wpsCls, wpCls);
        } catch (Throwable ignored) {
        }
    }

    private static Method findRemoveMethod(Class<?> wpsCls, Class<?> wpCls) {
        for (Method m : wpsCls.getMethods()) {
            if (m.getName().equals("remove") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isAssignableFrom(wpCls)) {
                return m;
            }
        }
        return null;
    }

    private static Object getWaypointSet() {
        try {
            Class<?> hudMods = Class.forName("xaero.hud.minimap.BuiltInHudModules");
            Object minimap = hudMods.getField("MINIMAP").get(null);
            Object session = minimap.getClass().getMethod("getCurrentSession").invoke(minimap);
            if (session == null) {
                return null;
            }
            Object worldMgr = session.getClass().getMethod("getWorldManager").invoke(session);
            if (worldMgr == null) {
                return null;
            }
            Object world = worldMgr.getClass().getMethod("getCurrentWorld").invoke(worldMgr);
            return world == null ? null : world.getClass().getMethod("getCurrentWaypointSet").invoke(world);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean addToWaypoints(int x, int y, int z, String name) {
        boolean fileOk = appendToFile(x, y, z, name);
        init();
        if (wpCtor == null || wpsAdd == null) {
            return fileOk;
        }
        try {
            Object wps = getWaypointSet();
            if (wps == null) {
                return fileOk;
            }
            for (Object wp : (List<?>) wpsGetWaypoints.invoke(wps)) {
                if ((Integer) wpGetX.invoke(wp) == x && (Integer) wpGetZ.invoke(wp) == z
                        && wpGetName.invoke(wp).equals(name)) {
                    return fileOk;
                }
            }
            Object wpx = wpCtor.newInstance(x, y, z, name, "E", 6);
            wpsAdd.invoke(wps, wpx);
            return true;
        } catch (Throwable ignored) {
            return fileOk;
        }
    }

    private static Path getWpFile() {
        String world = detectXaeroWorld();
        Path gameDir = ClientAccess.evernightMinecraft.gameDirectory.toPath();
        return world == null
                ? gameDir.resolve("waypoints.txt")
                : gameDir.resolve("xaero/minimap").resolve(world).resolve("dim%1/waypoints.txt");
    }

    private static String detectXaeroWorld() {
        if (ClientAccess.evernightMinecraft == null) {
            return null;
        }
        try {
            if (ClientAccess.evernightMinecraft.getCurrentServer() != null) {
                return "Multiplayer_" + ClientAccess.evernightMinecraft.getCurrentServer().ip.replace(":", "_");
            }
        } catch (Exception ignored) {
        }
        try {
            if (ClientAccess.evernightMinecraft.getSingleplayerServer() != null) {
                WorldData sp = ClientAccess.evernightMinecraft.getSingleplayerServer().getWorldData();
                if (sp != null) {
                    return sp.getLevelName();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean appendToFile(int x, int y, int z, String name) {
        Path file = getWpFile();
        try {
            Files.createDirectories(file.getParent());
            String line = String.format("waypoint:%s:E:%d:%d:%d:6:false:0:gui.xaero_default:false:0",
                    sanitise(name), x, y, z);
            List<String> lines = new ArrayList<>();
            if (Files.isRegularFile(file)) {
                lines.addAll(Files.readAllLines(file));
            }
            for (String existing : lines) {
                if (existing.trim().equals(line)) {
                    return true;
                }
            }
            lines.add(line);
            Files.write(file, lines);
            return true;
        } catch (IOException e) {
            System.err.println("[PointUtils] write failed: " + file.toAbsolutePath() + ": " + e);
            return false;
        }
    }

    private static String sanitise(String s) {
        return s.replace(":", "_").replace("\\", "_");
    }

    public static int removeWaypoints(String namePrefix) {
        int removed = removeFromFile(namePrefix);
        init();
        if (wpGetName == null || wpsRemove == null) {
            return removed;
        }
        try {
            Object wps = getWaypointSet();
            if (wps == null) {
                return removed;
            }
            List<?> wpsList = (List<?>) wpsGetWaypoints.invoke(wps);
            List<Object> toRemove = new ArrayList<>();
            for (Object wp : wpsList) {
                String n = (String) wpGetName.invoke(wp);
                if (n.startsWith(namePrefix)) {
                    toRemove.add(wp);
                }
            }
            for (Object wpx : toRemove) {
                wpsRemove.invoke(wps, wpx);
            }
            return Math.max(removed, toRemove.size());
        } catch (Throwable ignored) {
            return removed;
        }
    }

    private static int removeFromFile(String namePrefix) {
        Path file = getWpFile();
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        try {
            List<String> lines = Files.readAllLines(file);
            List<String> keep = new ArrayList<>();
            String prefix = "waypoint:" + sanitise(namePrefix);
            for (String line : lines) {
                if (!line.startsWith(prefix)) {
                    keep.add(line);
                }
            }
            int removed = lines.size() - keep.size();
            if (removed > 0) {
                if (keep.isEmpty()) {
                    Files.deleteIfExists(file);
                } else {
                    Files.write(file, keep);
                }
            }
            return removed;
        } catch (IOException e) {
            return 0;
        }
    }
}
