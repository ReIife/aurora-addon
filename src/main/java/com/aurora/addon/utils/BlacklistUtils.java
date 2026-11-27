package com.aurora.addon.utils;

import evernight.client.ClientAccess;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.WorldData;

/**
 * 已处理落点黑名单：按世界分目录、按 10 万格一块的区域文件保存 (x, z) 坐标。
 *
 * <p>移植自 Meteor 版的同名工具类，逻辑未改；只把 {@code MeteorClient.mc} 换成
 * {@link ClientAccess#evernightMinecraft}，并把写盘失败的提示改成英文日志。
 */
public final class BlacklistUtils {
    private static final Path ROOT = Paths.get("AutoElytra", "BlackList");
    private static final int REGION = 100000;

    private BlacklistUtils() {
    }

    public static String worldKey() {
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
                    return "Singleplayer_" + sp.getLevelName();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static int regionOf(int c) {
        return Math.floorDiv(c, REGION) + 1;
    }

    private static Path regionFile(String world, int rx, int rz) {
        return ROOT.resolve(world).resolve(rx + "_" + rz + ".json");
    }

    public static boolean isBlacklisted(int x, int z) {
        String w = worldKey();
        return w != null && loadRegion(w, regionOf(x), regionOf(z)).contains(pack(x, z));
    }

    public static boolean isNearBlacklisted(String world, int x, int z, int radius) {
        if (world == null) {
            return false;
        }
        int rx = regionOf(x);
        int rz = regionOf(z);
        long r2 = (long) radius * radius;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (long k : loadRegion(world, rx + dx, rz + dz)) {
                    int bx = (int) (k >> 32);
                    int bz = (int) k;
                    long ddx = bx - x;
                    long ddz = bz - z;
                    if (ddx * ddx + ddz * ddz <= r2) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static void add(int x, int z) {
        String w = worldKey();
        if (w == null) {
            return;
        }
        int rx = regionOf(x);
        int rz = regionOf(z);
        Set<Long> set = loadRegion(w, rx, rz);
        if (set.add(pack(x, z))) {
            saveRegion(w, rx, rz, set);
        }
    }

    public static List<BlockPos> filterBatch(List<BlockPos> candidates) {
        String w = worldKey();
        if (w == null) {
            return new ArrayList<>(candidates);
        }
        Map<Long, List<BlockPos>> groups = new HashMap<>();
        for (BlockPos p : candidates) {
            long rk = pack(regionOf(p.getX()), regionOf(p.getZ()));
            groups.computeIfAbsent(rk, k -> new ArrayList<>()).add(p);
        }

        List<BlockPos> out = new ArrayList<>();
        for (Entry<Long, List<BlockPos>> e : groups.entrySet()) {
            long rk = e.getKey();
            int rx = (int) (rk >> 32);
            int rz = (int) rk;
            Set<Long> bl = loadRegion(w, rx, rz);
            for (BlockPos p : e.getValue()) {
                if (!bl.contains(pack(p.getX(), p.getZ()))) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    private static Set<Long> loadRegion(String w, int rx, int rz) {
        Path f = regionFile(w, rx, rz);
        Set<Long> set = new HashSet<>();
        if (!Files.isRegularFile(f)) {
            return set;
        }
        try {
            String raw = Files.readString(f).trim();
            if (!raw.startsWith("[") || !raw.endsWith("]")) {
                corrupt(f);
                return set;
            }
            String inner = raw.substring(1, raw.length() - 1).trim();
            if (inner.isEmpty()) {
                return set;
            }
            for (String part : inner.split("},")) {
                String t = part.trim();
                if (!t.endsWith("}")) {
                    t = t + "}";
                }
                int xi = t.indexOf("\"x\":");
                int zi = t.indexOf("\"z\":");
                if (xi >= 0 && zi >= 0) {
                    try {
                        int x = Integer.parseInt(t.substring(xi + 4, t.indexOf(",", xi)).trim());
                        int z = Integer.parseInt(t.substring(zi + 4, t.indexOf("}", zi)).trim());
                        set.add(pack(x, z));
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (IOException e) {
            corrupt(f);
        }
        return set;
    }

    private static void saveRegion(String w, int rx, int rz, Set<Long> set) {
        Path f = regionFile(w, rx, rz);
        try {
            Files.createDirectories(f.getParent());
            List<String> lines = new ArrayList<>(set.size());
            for (long k : set) {
                int x = (int) (k >> 32);
                int z = (int) k;
                lines.add("  {\"x\":" + x + ",\"z\":" + z + "}");
            }
            String json = "[\n" + String.join(",\n", lines) + "\n]";
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[Blacklist] write failed: " + e.getMessage());
        }
    }

    private static void corrupt(Path f) {
        try {
            Files.move(f, f.resolveSibling(f.getFileName() + ".corrupt." + System.currentTimeMillis()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    private static long pack(int a, int b) {
        return (long) a << 32 | b & 0xFFFFFFFFL;
    }
}
