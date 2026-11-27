/*
 * Behaviour ported from the Fuyi addon (AGPL-3.0, author fuyi) — `AutoVault.java` + `ChamberFinder.java`.
 * This file is distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.modules.production;

import com.aurora.addon.modules.AuroraModule;
import com.aurora.addon.utils.ChamberFinder;
import com.aurora.addon.utils.RenderMode;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.render.WorldRenderEvent;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.integrations.baritone.BaritoneBridge;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.font.FontRenderer;
import evernight.client.render.text.Translations;
import evernight.client.render.util.RenderUtils;
import evernight.client.render.mesh.WorldBoxRenderer;
import evernight.client.settings.core.NamedEntry;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.ColorSetting;
import evernight.client.settings.types.EnumSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.settings.types.StringSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 自动宝库（第一阶段：定位 + 导航）。
 *
 * <p>行为参考 Fuyi 的 {@code AutoVault}：按世界种子算出最近的试炼密室（{@link ChamberFinder}），
 * 标出目标、必要时交给 Baritone 走过去，到了就换下一个。上游那套「垂直下挖 → 清怪 → 开宝库 →
 * 就地补给 → 断点续跑」的完整流水线（约 5000 行的 {@code AutoVaultRunner} + 1800 行的
 * {@code SupplyPack}）**尚未移植**，见 {@code PORTING.md} §9。
 *
 * <p>为什么先做这一段：定位与导航是整条流水线的地基，而且完全依赖原版数学 + 本体已有的
 * Baritone 桥，行为可预测、风险最低；容器交互与补给链涉及大量点击时序，需要单独一轮验证。
 */
public final class AutoVault extends AuroraModule {
    private final Setting<String> seed = this.add(new StringSetting("Seed", ""));
    private final Setting<Integer> chamberCount = this.add(new NumberSetting<>("ChamberCount", 3, 1, 10));
    private final Setting<Integer> searchRadius = this.add(new NumberSetting<>("SearchRadius", 128, 16, 512));
    private final Setting<Integer> targetY = this.add(new NumberSetting<>("TargetY", -20, -64, 64));
    private final Setting<Boolean> autoPath = this.add(new BooleanSetting("AutoPath", false));
    private final Setting<PathMode> pathMode = this.add(new EnumSetting<>("PathMode", PathMode.Walk).dependsOn(this.autoPath));
    private final Setting<Integer> arriveDistance = this.add(new NumberSetting<>("ArriveDistance", 16, 4, 64));
    private final Setting<Boolean> render = this.add(new BooleanSetting("Render", true));
    private final Setting<RenderMode> renderMode = this.add(new EnumSetting<>("RenderMode", RenderMode.Both));
    private final Setting<Color> boxColor = this.add(new ColorSetting("Box", new Color(225, 25, 25, 255)));
    private final Setting<Color> fillColor = this.add(new ColorSetting("Fill", new Color(225, 25, 25, 50)));
    private final Setting<Color> textColor = this.add(new ColorSetting("TextColor", new Color(255, 255, 255, 255)));

    private final Set<BlockPos> visited = new HashSet<>();
    private final List<BlockPos> targets = new ArrayList<>();

    private BlockPos searchOrigin;
    /** 上一次搜索用的种子与位置：同样条件已搜过且没结果时不要每秒重试（否则聊天栏刷屏）。 */
    private long lastSearchedSeed;
    private BlockPos lastSearchedOrigin;
    private int index;
    private boolean seedWarned;
    private boolean pathRequested;
    private long lastSearchMs;

    public AutoVault(ModuleCategory category) {
        super("AutoVault", "Finds the nearest trial chambers from the world seed, marks them and optionally paths there with Baritone.", category);
    }

    @Override
    public void onEnable() {
        this.reset();
        this.visited.clear();
    }

    @Override
    public void onDisable() {
        this.cancelPath();
        this.reset();
    }

    @Override
    public String getInfo() {
        if (this.targets.isEmpty()) {
            return null;
        }
        return (this.index + 1) + "/" + this.targets.size();
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (!this.inGame()) {
            return;
        }

        long parsedSeed = ChamberFinder.parseSeed(this.seed.getValue());
        if (!ChamberFinder.isSeedUsable(parsedSeed)) {
            if (!this.seedWarned) {
                this.seedWarned = true;
                this.warning("messages.vaultNoSeed");
            }
            return;
        }
        this.seedWarned = false;

        BlockPos playerPos = mc.player.blockPosition();
        if (this.needsSearch(playerPos, parsedSeed)) {
            this.search(playerPos, parsedSeed);
        }

        if (this.targets.isEmpty()) {
            return;
        }

        BlockPos target = this.currentTarget();
        if (target == null) {
            return;
        }

        if (this.arrived(playerPos, target)) {
            this.visited.add(target);
            this.index++;
            this.cancelPath();
            if (this.index >= this.targets.size()) {
                this.info("messages.vaultAllDone");
                this.reset();
                return;
            }
            this.info("messages.vaultNext", String.valueOf(this.index + 1), String.valueOf(this.targets.size()));
            return;
        }

        if (this.autoPath.getValue() && !this.pathRequested) {
            this.startPath(target);
        }
    }

    private boolean needsSearch(BlockPos playerPos, long seed) {
        int radius = this.searchRadius.getValue();
        double radiusSq = (double) radius * radius;

        if (this.targets.isEmpty()) {
            // 同一颗种子、同一个位置已经搜过且没结果 → 不重复搜索，避免每秒一条「未找到」
            if (this.lastSearchedSeed == seed && this.lastSearchedOrigin != null
                    && this.lastSearchedOrigin.distSqr(playerPos) < radiusSq) {
                return false;
            }
            return true;
        }
        if (this.searchOrigin == null) {
            return true;
        }
        return this.searchOrigin.distSqr(playerPos) > radiusSq;
    }

    private void search(BlockPos origin, long seed) {
        long now = System.currentTimeMillis();
        if (now - this.lastSearchMs < 1000L) {
            return;
        }
        this.lastSearchMs = now;

        this.searchOrigin = origin;
        this.lastSearchedSeed = seed;
        this.lastSearchedOrigin = origin;
        this.index = 0;
        this.cancelPath();

        List<BlockPos> found = ChamberFinder.findNearestWithDepth(origin, this.chamberCount.getValue(),
                this.visited, seed, this.targetY.getValue());
        this.targets.clear();
        this.targets.addAll(found);

        if (found.isEmpty()) {
            this.warning("messages.vaultNotFound");
            return;
        }

        StringBuilder builder = new StringBuilder();
        for (BlockPos pos : found) {
            if (builder.length() > 0) {
                builder.append(" §7|§r ");
            }
            builder.append('(').append(pos.getX()).append(", ").append(pos.getZ()).append(')');
        }
        this.info("messages.vaultFound", String.valueOf(found.size()), builder.toString());
    }

    private BlockPos currentTarget() {
        return this.index >= 0 && this.index < this.targets.size() ? this.targets.get(this.index) : null;
    }

    private boolean arrived(BlockPos playerPos, BlockPos target) {
        double dx = playerPos.getX() - target.getX();
        double dz = playerPos.getZ() - target.getZ();
        return Math.sqrt(dx * dx + dz * dz) <= this.arriveDistance.getValue();
    }

    private void startPath(BlockPos target) {
        BaritoneBridge bridge = ClientContext.baritoneBridge;
        if (bridge == null || !bridge.isAvailable()) {
            this.warning("messages.vaultNoBaritone");
            this.autoPath.setValue(false);
            return;
        }
        if (bridge.isPathingOrCalculating()) {
            return;
        }
        if (this.pathMode.getValue() == PathMode.Elytra) {
            bridge.pathElytraTo(target);
        } else {
            bridge.pathToXZ(target.getX(), target.getZ());
        }
        this.pathRequested = true;
        this.info("messages.vaultPathing", String.valueOf(target.getX()), String.valueOf(target.getZ()));
    }

    private void cancelPath() {
        this.pathRequested = false;
        BaritoneBridge bridge = ClientContext.baritoneBridge;
        if (bridge != null && bridge.isAvailable() && bridge.isPathingOrCalculating()) {
            bridge.cancelPathing();
        }
    }

    private void reset() {
        this.targets.clear();
        this.index = 0;
        this.searchOrigin = null;
        this.pathRequested = false;
        this.lastSearchedSeed = 0L;
        this.lastSearchedOrigin = null;
    }

    @EventHandler
    private void onRender(WorldRenderEvent.Main event) {
        if (!this.render.getValue() || !this.inGame()) {
            return;
        }

        BlockPos target = this.currentTarget();
        if (target == null) {
            return;
        }

        AABB box = new AABB(target);
        if (this.renderMode.getValue() != RenderMode.Lines) {
            WorldBoxRenderer.fillBox(event.getPoseStack(), box, this.fillColor.getValue());
        }
        if (this.renderMode.getValue() != RenderMode.Sides) {
            WorldBoxRenderer.outlineBox(event.getPoseStack(), box, this.boxColor.getValue(), 2.0F);
        }

        double distance = Math.sqrt(mc.player.blockPosition().distSqr(target));
        String text = Translations.get(this.translationKey("messages.vaultLabel")) + " "
                + (this.index + 1) + "/" + this.targets.size()
                + " · " + (int) distance + "m";
        Vec3 position = new Vec3(target.getX() + 0.5, target.getY() + 1.4, target.getZ() + 0.5);
        float width = FontRenderer.INSTANCE.getWidth(text);
        RenderUtils.INSTANCE.drawWorldText(event.getPoseStack(), text, position,
                -width / 2.0F, 0.0F, 1.0F, this.textColor.getValue(), true);
    }

    /** 导航方式：{@code Walk} 走地面（Baritone 寻路），{@code Elytra} 用鞘翅滑翔。 */
    public enum PathMode implements NamedEntry {
        Walk("Walk"),
        Elytra("Elytra");

        private final String name;

        PathMode(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return this.name;
        }

        @Override
        public String getDisplayName() {
            return Translations.get("addons.aurora.names.values." + this.name);
        }
    }
}
