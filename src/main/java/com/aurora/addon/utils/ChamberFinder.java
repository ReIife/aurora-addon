/*
 * Behaviour ported from LeavesHack-style addon "Fuyi" (AGPL-3.0, author fuyi) — `ChamberFinder.java`.
 * This file is distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 按世界种子计算最近的试炼密室（Trial Chamber）坐标。
 *
 * <p>移植自 Fuyi 的 {@code ChamberFinder}。原理是原版结构集 {@code minecraft:trial_chambers} 的
 * 分布参数（spacing=34 / separation=12 / salt=94251327 / LINEAR），逐个 region 反算
 * 「该 region 内的结构起点区块」再取中心——纯数学，不需要区块数据，也不需要服务端配合。
 *
 * <p>与原版的差异：
 * <ul>
 *     <li>上游优先从服务端注册表读取 placement 参数，读不到才用内置常量；这里直接用内置常量
 *     （多人服拿不到真实注册表，常量与原版一致，结果相同）；</li>
 *     <li>上游会过滤冻洋/深冻洋（这些群系不会生成密室），本实现不做群系过滤——
 *     那需要区块生成，成本高且对「找最近的几个」影响很小；</li>
 *     <li>Y 坐标取结构起点区块的中心高度 0（真实密室在地下，具体入口要到了再挖）。</li>
 * </ul>
 */
public final class ChamberFinder {
    /** 原版 {@code trial_chambers} 结构集参数。 */
    private static final int SPACING = 34;
    private static final int SEPARATION = 12;
    private static final int SALT = 94251327;
    private static final RandomSpreadType SPREAD = RandomSpreadType.LINEAR;

    /** 搜索时向外扩多少个 region（1 region = 34 区块 ≈ 544 格）。 */
    private static final int REGION_RADIUS = 8;

    /**
     * 懒加载：{@link RandomSpreadStructurePlacement} 的构造函数会碰原版注册表，
     * 类加载期（注册表尚未 bootstrap）构造会抛 {@code Not bootstrapped}。
     */
    private static RandomSpreadStructurePlacement placement;

    private static RandomSpreadStructurePlacement placement() {
        if (placement == null) {
            placement = new RandomSpreadStructurePlacement(SPACING, SEPARATION, SPREAD, SALT);
        }
        return placement;
    }

    private ChamberFinder() {
    }

    /**
     * 以 {@code origin} 为中心，返回最近的 {@code count} 个密室中心（按距离升序）。
     *
     * @param origin  搜索中心（一般用玩家坐标）
     * @param count   需要几个
     * @param exclude 需要跳过的坐标（已访问过的密室）
     * @param seed    世界种子；{@code 0} 表示未设置，直接返回空列表
     */
    public static List<BlockPos> findNearest(BlockPos origin, int count, Set<BlockPos> exclude, long seed) {
        if (seed == 0L || count <= 0) {
            return List.of();
        }

        int originChunkX = origin.getX() >> 4;
        int originChunkZ = origin.getZ() >> 4;
        int originRegionX = Math.floorDiv(originChunkX, SPACING);
        int originRegionZ = Math.floorDiv(originChunkZ, SPACING);

        Set<ChunkPos> seen = new HashSet<>();
        List<BlockPos> centers = new ArrayList<>();

        for (int regionX = originRegionX - REGION_RADIUS; regionX <= originRegionX + REGION_RADIUS; regionX++) {
            for (int regionZ = originRegionZ - REGION_RADIUS; regionZ <= originRegionZ + REGION_RADIUS; regionZ++) {
                // getPotentialStructureChunk 收的是「区块坐标」，内部再除以 spacing 得到 region；
                // 上游同样传的是 region * spacing，这里保持一致。
                ChunkPos chunk = placement().getPotentialStructureChunk(seed, regionX * SPACING, regionZ * SPACING);
                if (chunk == null || !seen.add(chunk)) {
                    continue;
                }
                BlockPos center = chunk.getMiddleBlockPosition(0);
                if (exclude != null && exclude.contains(center)) {
                    continue;
                }
                centers.add(center);
            }
        }

        centers.sort(Comparator.comparingDouble(origin::distSqr));
        return centers.size() <= count ? centers : new ArrayList<>(centers.subList(0, count));
    }

    /** 与 {@link #findNearest} 相同，但把 Y 直接落到密室所在的深层高度。 */
    public static List<BlockPos> findNearestWithDepth(BlockPos origin, int count, Set<BlockPos> exclude, long seed, int y) {
        List<BlockPos> flat = findNearest(origin, count, exclude, seed);
        List<BlockPos> result = new ArrayList<>(flat.size());
        for (BlockPos pos : flat) {
            result.add(new BlockPos(pos.getX(), y, pos.getZ()));
        }
        return result;
    }

    /** 判断一个字符串是否是合法的世界种子（十进制或带符号的 64 位整数）。 */
    public static long parseSeed(String raw) {
        if (raw == null) {
            return 0L;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException notNumeric) {
            // 原版也允许用文本种子，这里按原版 String.hashCode 规则折算
            return value.hashCode();
        }
    }

    /** 该 seed 是否可用于计算（0 视为未设置）。 */
    public static boolean isSeedUsable(long seed) {
        return seed != 0L;
    }

    /**
     * 供调试/文档用：当前使用的分布参数。
     */
    public static String describePlacement() {
        return "spacing=" + SPACING + " separation=" + SEPARATION + " salt=" + SALT + " spread=" + SPREAD;
    }

    /** 结构集状态占位：仅为避免误用未初始化状态，保留给后续接入注册表版本。 */
    static ChunkGeneratorStructureState unusedStructureState() {
        return null;
    }
}
