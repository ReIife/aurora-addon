package com.aurora.addon.modules.elytra.elytracollect;

import com.aurora.addon.modules.AuroraModule;
import com.aurora.addon.modules.elytra.ElytraCollect;
import com.aurora.addon.utils.BlacklistUtils;
import com.aurora.addon.utils.TiziStorage;
import evernight.client.events.core.EventHandler;
import evernight.client.events.network.ClientChunkCacheReplaceWithPacketDataEvent;
import evernight.client.events.render.WorldRenderEvent;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.events.world.BlockUpdateAppliedEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.mesh.WorldBoxRenderer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.awt.Color;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * 末地船查找：扫描已加载区块里的梯子，按相邻关系聚成「梯子链」，方框 ESP 显示并把链底端的
 * 锁定点写进 {@code tizi/<world>/zuobiao.json}（已进黑名单的跳过）。
 *
 * <p>移植自 Meteor 插件的 {@code EndShipFinder}，聚类、朝向投票、落盘格式逐行保留。三处适配：
 * <ul>
 *     <li>{@code Utils.chunks()} → 按渲染距离轮转扫描已加载区块（不触发加载，也不要求 FULL 状态）。</li>
 *     <li>{@code Render3DEvent} → {@link WorldRenderEvent.Main}。</li>
 *     <li>TODO(port): Meteor 的 {@code BlockUpdateEvent} 带 pos / oldState / newState，Evernight 的
 *     {@link BlockUpdateAppliedEvent} 没有任何字段，无法定位被改的方块。这里退化为「标记需要重扫」，
 *     每 20 tick 重扫玩家周围 3×3 区块，因此远处梯子被改动要等对应区块重新加载才会更新。</li>
 * </ul>
 */
public final class EndShipFinder extends AuroraModule {
    private static final int MIN_CHAIN_LENGTH = 14;

    private final Long2ObjectMap<Set<BlockPos>> chunkLadders = new Long2ObjectOpenHashMap<>();
    private final Set<BlockPos> allLadders = new LinkedHashSet<>();
    private final Map<BlockPos, Direction> ladderFacing = new HashMap<>();
    private List<List<BlockPos>> chains = new ArrayList<>();
    private int lastReportedCount = -1;
    private DimensionType lastDimension;
    private boolean needsChainUpdate;
    private boolean needsChunkRescan;
    private int ticksSinceLastReport;

    /** 轮转扫描游标：每 tick 只扫少量区块，保证新加载的区块迟早会被扫到。 */
    private static final int CHUNKS_PER_TICK = 4;
    private int cursorX;
    private int cursorZ;
    private int cursorRadius = -1;

    public EndShipFinder(ModuleCategory category) {
        super("EndShipFinder", "Scans loaded chunks for ladder chains and saves their lock points.", category);
    }

    private static ElytraCollect cfg() {
        return ElytraCollect.cfg();
    }

    @Override
    public void onEnable() {
        TiziStorage.ensureDirectory();
        this.resetState();
        if (this.mc.player == null || this.mc.level == null) {
            this.disable();
            return;
        }
        this.scanLoadedChunks();
        this.lastDimension = this.mc.level.dimensionType();
        this.recomputeChains();
    }

    @Override
    public void onDisable() {
        this.resetState();
    }

    private void resetState() {
        synchronized (this.chunkLadders) {
            this.chunkLadders.clear();
            this.allLadders.clear();
            this.ladderFacing.clear();
            this.chains.clear();
        }
        this.ticksSinceLastReport = 0;
        this.needsChainUpdate = false;
        this.needsChunkRescan = false;
        this.lastReportedCount = -1;
    }

    private void scanLoadedChunks() {
        int viewDistance = this.mc.options.renderDistance().get() + 1;
        int centerX = this.mc.player.getBlockX() >> 4;
        int centerZ = this.mc.player.getBlockZ() >> 4;
        for (int cx = centerX - viewDistance; cx <= centerX + viewDistance; cx++) {
            for (int cz = centerZ - viewDistance; cz <= centerZ + viewDistance; cz++) {
                LevelChunk chunk = this.chunkAt(cx, cz);
                if (chunk != null) {
                    this.searchChunk(chunk);
                }
            }
        }
    }

    /**
     * 取已加载区块，**不要求 FULL 状态**。
     *
     * <p>原实现只查 {@code ChunkStatus.FULL}：滑翔时周围区块常常只到 FEATURES/LIGHT，
     * 查询返回 null，于是「未完全加载的区块里明明有末地船却扫不到」。这里先取客户端区块缓存里
     * 任意状态的区块（{@code getChunkNow}），拿不到再退回 FULL 查询。
     */
    private LevelChunk chunkAt(int cx, int cz) {
        LevelChunk chunk = this.mc.level.getChunkSource().getChunkNow(cx, cz);
        if (chunk != null) {
            return chunk;
        }
        return this.mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
    }

    /**
     * 轮转扫描：每 tick 扫 {@link #CHUNKS_PER_TICK} 个区块，游标在渲染距离内循环推进。
     *
     * <p>替代原来的「方块更新后只重扫玩家周围 3×3」：船在 3×3 之外的区块加载时永远扫不到。
     * 游标覆盖整个渲染距离，任意区块最迟在 {@code (2r+1)^2 / CHUNKS_PER_TICK} tick 内被扫到
     * （渲染距离 12 时约 40 秒），单 tick 成本恒定。
     */
    private void rotateScan() {
        int radius = this.mc.options.renderDistance().get() + 1;
        int centerX = this.mc.player.getBlockX() >> 4;
        int centerZ = this.mc.player.getBlockZ() >> 4;
        int span = radius * 2 + 1;

        if (this.cursorRadius != radius) {
            this.cursorRadius = radius;
            this.cursorX = centerX - radius;
            this.cursorZ = centerZ - radius;
        } else {
            int offsetX = Math.floorMod(this.cursorX - (centerX - radius), span);
            int offsetZ = Math.floorMod(this.cursorZ - (centerZ - radius), span);
            this.cursorX = centerX - radius + offsetX;
            this.cursorZ = centerZ - radius + offsetZ;
        }

        for (int i = 0; i < CHUNKS_PER_TICK; i++) {
            LevelChunk chunk = this.chunkAt(this.cursorX, this.cursorZ);
            if (chunk != null) {
                this.searchChunk(chunk);
            }
            this.cursorX++;
            if (this.cursorX > centerX + radius) {
                this.cursorX = centerX - radius;
                this.cursorZ++;
                if (this.cursorZ > centerZ + radius) {
                    this.cursorZ = centerZ - radius;
                }
            }
        }
    }

    private BlockPos findBottom(List<BlockPos> component) {
        BlockPos bottom = component.get(0);
        for (BlockPos pos : component) {
            if (pos.getY() < bottom.getY()
                    || pos.getY() == bottom.getY()
                    && (pos.getX() < bottom.getX() || pos.getX() == bottom.getX() && pos.getZ() < bottom.getZ())) {
                bottom = pos;
            }
        }
        return bottom;
    }

    private Direction findChainFacing(List<BlockPos> component, BlockPos bottom) {
        Direction direct = this.ladderFacing.get(bottom);
        if (direct != null) {
            return direct;
        }
        EnumMap<Direction, Integer> votes = new EnumMap<>(Direction.class);
        for (BlockPos pos : component) {
            Direction facing = this.ladderFacing.get(pos);
            if (facing != null) {
                votes.merge(facing, 1, Integer::sum);
            }
        }
        Direction best = null;
        int bestVotes = 0;
        for (Entry<Direction, Integer> entry : votes.entrySet()) {
            if (entry.getValue() > bestVotes) {
                bestVotes = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    private void searchChunk(ChunkAccess chunk) {
        long chunkKey = chunk.getPos().toLong();
        Set<BlockPos> found = new HashSet<>();
        Map<BlockPos, Direction> foundFacing = new HashMap<>();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();

        // 按「非空 section」逐层扫描：
        // 1) 末地绝大多数 section 是纯空气，hasOnlyAir() 直接跳过，单区块成本从约 10 万次
        //    方块查询降到几千次；
        // 2) 不依赖 WORLD_SURFACE 高度图——未完全加载的区块高度图可能还没生成，
        //    用它当上界会漏扫（「未加载区块扫不到船」也有这一层原因）。
        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = this.mc.level.getMinSectionY();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            int baseY = (minSectionY + index) << 4;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = 0; y < 16; y++) {
                        mutable.set(startX + x, baseY + y, startZ + z);
                        BlockState state = chunk.getBlockState(mutable);
                        if (state.getBlock() == Blocks.LADDER) {
                            BlockPos pos = mutable.immutable();
                            found.add(pos);
                            foundFacing.put(pos, state.getValue(LadderBlock.FACING));
                        }
                    }
                }
            }
        }

        synchronized (this.chunkLadders) {
            Set<BlockPos> previous = this.chunkLadders.get(chunkKey);
            if (previous != null) {
                this.allLadders.removeAll(previous);
                for (BlockPos pos : previous) {
                    this.ladderFacing.remove(pos);
                }
            }
            this.ladderFacing.putAll(foundFacing);
            if (found.isEmpty()) {
                this.chunkLadders.remove(chunkKey);
            } else {
                this.chunkLadders.put(chunkKey, found);
                this.allLadders.addAll(found);
            }
            this.needsChainUpdate = true;
        }
    }

    @EventHandler
    private void onChunkData(ClientChunkCacheReplaceWithPacketDataEvent event) {
        this.searchChunk(event.getChunk());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateAppliedEvent event) {
        // TODO(port): 事件无 pos / oldState / newState，只能整体标记重扫。
        this.needsChunkRescan = true;
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.mc.player == null || this.mc.level == null) {
            return;
        }
        DimensionType dimension = this.mc.level.dimensionType();
        if (this.lastDimension != dimension) {
            this.resetState();
            this.scanLoadedChunks();
            this.lastDimension = dimension;
            this.recomputeChains();
            return;
        }

        if (this.purgeUnloadedChunks()) {
            this.needsChainUpdate = true;
        }

        this.rotateScan();

        this.ticksSinceLastReport++;
        if (this.ticksSinceLastReport < 20) {
            return;
        }
        this.ticksSinceLastReport = 0;
        if (this.needsChunkRescan) {
            this.needsChunkRescan = false;
            this.needsChainUpdate = true;
        }
        if (this.needsChainUpdate) {
            this.needsChainUpdate = false;
            this.recomputeChains();
        }
    }

    private boolean purgeUnloadedChunks() {
        int playerChunkX = this.mc.player.getBlockX() >> 4;
        int playerChunkZ = this.mc.player.getBlockZ() >> 4;
        int viewDistance = this.mc.options.renderDistance().get() + 1;
        boolean changed = false;
        synchronized (this.chunkLadders) {
            ObjectIterator<Long2ObjectMap.Entry<Set<BlockPos>>> iterator =
                    this.chunkLadders.long2ObjectEntrySet().iterator();
            while (iterator.hasNext()) {
                Long2ObjectMap.Entry<Set<BlockPos>> entry = iterator.next();
                int chunkX = ChunkPos.getX(entry.getLongKey());
                int chunkZ = ChunkPos.getZ(entry.getLongKey());
                if (Math.abs(chunkX - playerChunkX) > viewDistance || Math.abs(chunkZ - playerChunkZ) > viewDistance) {
                    for (BlockPos pos : entry.getValue()) {
                        this.ladderFacing.remove(pos);
                    }
                    this.allLadders.removeAll((Collection<?>) entry.getValue());
                    iterator.remove();
                    changed = true;
                }
            }
        }
        return changed;
    }

    private void recomputeChains() {
        List<List<BlockPos>> newChains = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        for (BlockPos start : this.allLadders) {
            if (visited.contains(start)) {
                continue;
            }
            List<BlockPos> component = new ArrayList<>();
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start);
            while (!queue.isEmpty()) {
                BlockPos pos = queue.poll();
                component.add(pos);
                for (BlockPos neighbour : new BlockPos[]{
                        pos.above(), pos.below(), pos.north(), pos.south(), pos.east(), pos.west()}) {
                    if (this.allLadders.contains(neighbour) && !visited.contains(neighbour)) {
                        visited.add(neighbour);
                        queue.add(neighbour);
                    }
                }
            }
            if (component.size() >= MIN_CHAIN_LENGTH) {
                newChains.add(component);
            }
        }
        this.chains = newChains;

        if (this.chains.isEmpty()) {
            this.lastReportedCount = this.chains.size();
            return;
        }
        int added = this.saveToJson();
        if (added > 0) {
            this.info("messages.collect.endShipFinder.foundNew", added, MIN_CHAIN_LENGTH);
        } else if (this.chains.size() != this.lastReportedCount) {
            this.lastReportedCount = this.chains.size();
            this.info("messages.collect.endShipFinder.alreadySaved");
        }
    }

    @EventHandler
    private void onWorldRender(WorldRenderEvent.Main event) {
        ElytraCollect config = cfg();
        if (config == null) {
            return;
        }
        Color color = config.ladderColor();
        for (List<BlockPos> component : this.chains) {
            for (BlockPos pos : component) {
                AABB box = new AABB(pos);
                WorldBoxRenderer.fillBox(event.getPoseStack(), box, color);
                WorldBoxRenderer.outlineBox(event.getPoseStack(), box, color, 1.0F);
            }
        }
    }

    private int saveToJson() {
        ElytraCollect config = cfg();
        String world = TiziStorage.worldName();
        if (config == null || world == null) {
            return 0;
        }

        int blacklistRadius = config.blacklistRadius();
        String blacklistWorld = BlacklistUtils.worldKey();
        Path file = TiziStorage.file(world, "zuobiao.json");
        Set<String> entries = TiziStorage.readEntries(file);
        int before = entries.size();

        for (List<BlockPos> component : this.chains) {
            BlockPos bottom = this.findBottom(component);
            Direction facing = this.findChainFacing(component, bottom);
            if (facing == null) {
                this.error("messages.collect.endShipFinder.missingFacing",
                        bottom.getX(), bottom.getY(), bottom.getZ());
                continue;
            }
            BlockPos lock = bottom.relative(facing.getOpposite(), config.lockPointOffset());
            if (!BlacklistUtils.isNearBlacklisted(blacklistWorld, lock.getX(), lock.getZ(), blacklistRadius)) {
                entries.add(TiziStorage.entry(lock.getX(), bottom.getY(), lock.getZ()));
            }
        }

        int added = entries.size() - before;
        if (added > 0 && !TiziStorage.writeEntries(file, entries)) {
            this.error("messages.collect.endShipFinder.saveFailed");
            return 0;
        }
        return added;
    }
}
