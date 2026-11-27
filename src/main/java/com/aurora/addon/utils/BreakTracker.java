/*
 * Behaviour ported from LeavesHack (AGPL-3.0, MrBZBZ) — `manager/BreakManager.java`.
 * This file is distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.utils;

import evernight.client.ClientAccess;
import evernight.client.events.core.EventHandler;
import evernight.client.events.network.ConnectScreenConnectEvent;
import evernight.client.events.network.PacketEvent;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.utils.inventory.EnchantmentUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 挖掘进度跟踪：把服务端发来的 {@code ClientboundBlockDestructionPacket} 按「挖掘者实体 id」归并成
 * 「主挖」与「副挖（双挖）」两条进度，供 {@code BreakESP} 与 {@code PacketMine} 共用。
 *
 * <p>行为移植自 LeavesHack 的 {@code BreakManager}。与原版一致的三条判定：
 * <ul>
 *     <li>只统计距离挖掘者眼睛 8 格以内的包，避免远处噪声；</li>
 *     <li>进度 0 视为「开始挖」，255 视为「挖断」，其余为中间进度（可据此识别双挖）；</li>
 *     <li>超过 {@code 破坏耗时 × breakTimeout} 还没断的记为 failed，方块变空气记为 complete。</li>
 * </ul>
 *
 * <p>破坏耗时用本体玩家当前手持物品以外的「最优工具」估算，与原版 {@code getBreakTime} 的取刀方式一致。
 */
public final class BreakTracker implements ClientAccess {
    private final Map<Integer, BreakData> mainBreaks = new ConcurrentHashMap<>();
    private final Map<Integer, BreakData> secondaryBreaks = new ConcurrentHashMap<>();

    private boolean detectDouble = true;
    private double doubleMineTimeout = 2.0;
    private double minTimeout = 2.0;
    private double breakTimeout = 1.5;

    public static final class BreakData {
        public BlockPos pos;
        public final int entityId;
        public final long startMs;
        public double breakTimeMs;
        public boolean failed;
        public boolean complete;

        BreakData(BlockPos pos, int entityId, double breakTimeMs) {
            this.pos = pos;
            this.entityId = entityId;
            this.startMs = System.currentTimeMillis();
            this.breakTimeMs = breakTimeMs;
        }

        public long elapsedMs() {
            return System.currentTimeMillis() - this.startMs;
        }

        /** 0..1 的挖掘进度。 */
        public double progress(double multiplier) {
            if (this.failed || this.complete) {
                return 1.0;
            }
            double total = Math.max(this.breakTimeMs * multiplier, 50.0);
            return Math.min(1.0, this.elapsedMs() / total);
        }

        public Player entity() {
            if (evernightMinecraft.level == null) {
                return null;
            }
            Entity entity = evernightMinecraft.level.getEntity(this.entityId);
            return entity instanceof Player player ? player : null;
        }
    }

    public void setDetectDouble(boolean detectDouble) {
        this.detectDouble = detectDouble;
    }

    public Map<Integer, BreakData> mainBreaks() {
        return this.mainBreaks;
    }

    public Map<Integer, BreakData> secondaryBreaks() {
        return this.secondaryBreaks;
    }

    /** 主挖中、且未失败未完成的方块坐标。 */
    public List<BlockPos> mainPositions() {
        List<BlockPos> positions = new ArrayList<>();
        for (BreakData data : this.mainBreaks.values()) {
            if (data.failed || data.complete || data.entity() == null) {
                continue;
            }
            positions.add(data.pos);
        }
        return positions;
    }

    public void clear() {
        this.mainBreaks.clear();
        this.secondaryBreaks.clear();
    }

    public boolean isMining(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        for (BreakData data : this.mainBreaks.values()) {
            if (data.failed || data.entity() == null) {
                continue;
            }
            if (data.pos.equals(pos)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    private void onConnect(ConnectScreenConnectEvent event) {
        this.clear();
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.isWorldUnavailable()) {
            return;
        }

        if (this.detectDouble) {
            this.secondaryBreaks.entrySet().removeIf(entry -> {
                BreakData data = entry.getValue();
                return data == null
                        || data.entity() == null
                        || evernightMinecraft.level.isEmptyBlock(data.pos)
                        || data.elapsedMs() > Math.max(this.minTimeout * 1000.0, data.breakTimeMs * this.doubleMineTimeout);
            });
        }

        for (BreakData data : this.mainBreaks.values()) {
            data.breakTimeMs = Math.max(breakTimeMs(data.pos), 50.0);
            if (unbreakable(data.pos)) {
                data.complete = false;
                data.failed = true;
            } else if (evernightMinecraft.level.isEmptyBlock(data.pos)) {
                data.complete = true;
                data.failed = false;
            } else if (!data.complete && data.elapsedMs() > data.breakTimeMs * this.breakTimeout) {
                data.failed = true;
            }
        }
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (this.isWorldUnavailable() || !(event.getPacket() instanceof ClientboundBlockDestructionPacket packet)) {
            return;
        }
        BlockPos pos = packet.getPos();
        if (pos == null) {
            return;
        }

        BreakData candidate = new BreakData(pos, packet.getId(), Math.max(breakTimeMs(pos), 50.0));
        Player breaker = candidate.entity();
        if (breaker == null) {
            return;
        }
        if (breaker.getEyePosition().distanceToSqr(pos.getCenter()) > 64.0) {
            return;
        }

        if (this.detectDouble) {
            int progress = packet.getProgress();
            if (progress != 255) {
                BreakData existing = this.secondaryBreaks.get(packet.getId());
                if (progress != 0) {
                    if (existing != null) {
                        existing.pos = pos;
                    } else if (!unbreakable(pos)) {
                        this.secondaryBreaks.put(packet.getId(), new BreakData(pos, packet.getId(), Math.max(breakTimeMs(pos), 50.0)));
                    }
                    return;
                }
                if (existing != null && existing.pos.equals(pos) && existing.elapsedMs() < 150_000L) {
                    return;
                }
            }
        }

        BreakData current = this.mainBreaks.get(packet.getId());
        if (current != null && !current.failed && current.pos.equals(pos)) {
            return;
        }

        this.mainBreaks.put(packet.getId(), candidate);

        if (this.detectDouble && !this.secondaryBreaks.containsKey(packet.getId()) && !unbreakable(pos)) {
            this.secondaryBreaks.put(packet.getId(), new BreakData(pos, packet.getId(), Math.max(breakTimeMs(pos), 50.0)));
        }
    }

    // ------------------------------------------------------------------ 破坏耗时

    public static boolean unbreakable(BlockPos pos) {
        if (evernightMinecraft.level == null) {
            return false;
        }
        BlockState state = evernightMinecraft.level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        float hardness = state.getDestroySpeed(evernightMinecraft.level, pos);
        return hardness < 0.0F || hardness == 100.0F;
    }

    /** 用背包里最快的工具估算破坏耗时（毫秒）。 */
    public static double breakTimeMs(BlockPos pos) {
        if (evernightMinecraft.level == null || evernightMinecraft.player == null) {
            return 50.0;
        }
        BlockState state = evernightMinecraft.level.getBlockState(pos);
        if (state.isAir()) {
            return 50.0;
        }
        float hardness = state.getDestroySpeed(evernightMinecraft.level, pos);
        if (hardness < 0.0F) {
            return Double.MAX_VALUE;
        }

        ItemStack tool = bestTool(pos, state);
        float speed = tool.getDestroySpeed(state);
        if (speed > 1.0F) {
            int efficiency = EnchantmentUtils.getLevel(Enchantments.EFFICIENCY, tool);
            if (efficiency > 0) {
                speed += (float) (efficiency * efficiency + 1);
            }
        }
        boolean correctTool = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        float damage = speed / hardness / (correctTool ? 30.0F : 100.0F);
        if (damage <= 0.0F) {
            return Double.MAX_VALUE;
        }
        return 1.0 / damage / 20.0 * 1000.0;
    }

    /** 快捷栏里对该方块破坏最快的一格；都不快于空手时返回当前手持。 */
    public static ItemStack bestTool(BlockPos pos, BlockState state) {
        if (evernightMinecraft.player == null) {
            return ItemStack.EMPTY;
        }
        ItemStack best = evernightMinecraft.player.getMainHandItem();
        float bestSpeed = best.getDestroySpeed(state);
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = evernightMinecraft.player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = stack;
            }
        }
        return best;
    }

    /** 快捷栏里对该方块破坏最快的一格槽位，找不到返回 {@code -1}。 */
    public static int bestToolSlot(BlockPos pos) {
        if (evernightMinecraft.level == null || evernightMinecraft.player == null) {
            return -1;
        }
        BlockState state = evernightMinecraft.level.getBlockState(pos);
        int bestSlot = -1;
        float bestSpeed = 1.0F;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = evernightMinecraft.player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }
}
