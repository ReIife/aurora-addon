package com.aurora.addon.utils;

import evernight.client.ClientAccess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.WorldData;

/**
 * {@code tizi/} 目录下坐标文件的公共读写。
 *
 * <p>Meteor 版把同一段「按世界分目录 + 解析 {@code [{"x":..,"y":..,"z":..}]}」的代码复制在
 * 五个鞘翅子模块里；移植时合并到这里，解析与写盘格式逐字保留，避免五份实现漂移。
 *
 * <p>注意：单机世界的目录名沿用原实现的「世界名」，<b>不带</b> {@code Singleplayer_} 前缀，
 * 与 {@link BlacklistUtils#worldKey()} 的键不同，两者不能混用。
 */
public final class TiziStorage {
    public static final Path TIZI_DIR = Paths.get("AutoElytra");

    private TiziStorage() {
    }

    /**
     * 当前世界在 {@code tizi/} 下的目录名；单机用存档名，多人用 {@code Multiplayer_<ip>}。
     *
     * @return 目录名，未知时为 {@code null}
     */
    public static String worldName() {
        try {
            if (ClientAccess.evernightMinecraft.getCurrentServer() != null) {
                return "Multiplayer_" + ClientAccess.evernightMinecraft.getCurrentServer().ip.replace(":", "_");
            }
        } catch (Exception ignored) {
        }
        try {
            if (ClientAccess.evernightMinecraft.getSingleplayerServer() != null) {
                WorldData data = ClientAccess.evernightMinecraft.getSingleplayerServer().getWorldData();
                if (data != null) {
                    return data.getLevelName();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static Path file(String world, String name) {
        return TIZI_DIR.resolve(world).resolve(name);
    }

    /** 确保 {@code tizi/} 存在；失败时静默忽略（与原实现一致）。 */
    public static void ensureDirectory() {
        try {
            Files.createDirectories(TIZI_DIR);
        } catch (IOException ignored) {
        }
    }

    /** 读取文件里的原始 {@code {"x":..,"y":..,"z":..}} 片段，保持出现顺序。 */
    public static Set<String> readEntries(Path file) {
        Set<String> entries = new LinkedHashSet<>();
        if (!Files.isRegularFile(file)) {
            return entries;
        }
        try {
            String raw = Files.readString(file).trim();
            if (!raw.startsWith("[") || !raw.endsWith("]")) {
                return entries;
            }
            String inner = raw.substring(1, raw.length() - 1).trim();
            if (inner.isEmpty()) {
                return entries;
            }
            for (String part : inner.split("},")) {
                String entry = part.trim();
                if (!entry.endsWith("}")) {
                    entry = entry + "}";
                }
                if (!entry.isBlank()) {
                    entries.add(entry);
                }
            }
        } catch (IOException ignored) {
        }
        return entries;
    }

    /** 把原始片段写成 JSON 数组；失败时返回 {@code false}。 */
    public static boolean writeEntries(Path file, Collection<String> entries) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "[\n  " + String.join(",\n  ", entries) + "\n]");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 解析文件里的所有坐标；解析失败的条目直接跳过。 */
    public static List<BlockPos> readPoints(Path file) {
        List<BlockPos> points = new ArrayList<>();
        for (String entry : readEntries(file)) {
            int xi = entry.indexOf("\"x\":");
            int yi = entry.indexOf("\"y\":");
            int zi = entry.indexOf("\"z\":");
            if (xi < 0 || yi < 0 || zi < 0) {
                continue;
            }
            try {
                int x = Integer.parseInt(entry.substring(xi + 4, entry.indexOf(",", xi)));
                int y = Integer.parseInt(entry.substring(yi + 4, entry.indexOf(",", yi)));
                int z = Integer.parseInt(entry.substring(zi + 4, entry.indexOf("}", zi)));
                points.add(new BlockPos(x, y, z));
            } catch (Exception ignored) {
            }
        }
        return points;
    }

    public static String entry(int x, int y, int z) {
        return "{\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + "}";
    }
}
