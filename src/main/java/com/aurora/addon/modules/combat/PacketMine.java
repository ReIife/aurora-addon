/*
 * Behaviour ported from LeavesHack (AGPL-3.0, MrBZBZ) — `modules/PacketMine.java`.
 * This file is distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.modules.combat;

import com.aurora.addon.modules.AuroraModule;
import com.aurora.addon.utils.BreakTracker;
import com.aurora.addon.utils.RenderMode;
import evernight.client.events.core.EventHandler;
import evernight.client.events.network.MultiPlayerGameModeStartDestroyBlockEvent;
import evernight.client.events.render.WorldRenderEvent;
import evernight.client.events.ui.MinecraftTickEvent;
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
import evernight.client.utils.inventory.InventoryUtils;
import evernight.client.utils.network.PacketSender;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;

/**
 * 发包挖掘（PacketMine+）：取消原版挖掘，改为自己按破坏耗时发 START/STOP_DESTROY_BLOCK 包。
 *
 * <p>行为移植自 LeavesHack 的 {@code PacketMine}，保留了它的核心流程：
 * <ol>
 *     <li>拦截 {@code startDestroyBlock}（{@link MultiPlayerGameModeStartDestroyBlockEvent} 可取消），
 *     接管目标方块；</li>
 *     <li>首 tick 发 {@code START_DESTROY_BLOCK}（含可选的「-999 绕过包」），随后按
 *     地面/滞空不同的累积速率累加进度；</li>
 *     <li>进度达到 {@code 破坏耗时 × Damage} 后发 {@code STOP_DESTROY_BLOCK}，并按
 *     {@code TryBreakTime} 重试；</li>
 *     <li>发 STOP 前静默切镐（{@code Silent} 只发包不改本地选中格），{@code SwitchTime} 后切回。</li>
 * </ol>
 *
 * <p>未移植的上游分支（代码内标 {@code TODO(port)}）：
 * <ul>
 *     <li>「优先重生锚 / 优先蜘蛛网」联动（依赖上游的 {@code AutoAnchor}/{@code AutoWeb} 模块）；</li>
 *     <li>双挖（第二个目标）；{@code BreakESP} 仍会显示其他玩家的双挖；</li>
 *     <li>进度条的动画指数 {@code AnimationExponent}。</li>
 * </ul>
 */
public final class PacketMine extends AuroraModule {
    private final Setting<Boolean> usingPause = this.add(new BooleanSetting("UsingPause", true));
    private final Setting<Boolean> onlyMain = this.add(new BooleanSetting("OnlyMain", true).dependsOn(this.usingPause));
    private final Setting<SwitchMode> autoSwitch = this.add(new EnumSetting<>("AutoSwitch", SwitchMode.Silent));
    private final Setting<Integer> range = this.add(new NumberSetting<>("Range", 6, 0, 12));
    private final Setting<Integer> tryBreakTime = this.add(new NumberSetting<>("TryBreakTime", 6, 0, 10));
    private final Setting<Boolean> fastBypass = this.add(new BooleanSetting("FastBypass", true));
    private final Setting<Boolean> checkGround = this.add(new BooleanSetting("CheckGround", true));
    private final Setting<Boolean> bypassGround = this.add(new BooleanSetting("BypassGround", false));
    private final Setting<Integer> mineDelay = this.add(new NumberSetting<>("MineDelay", 300, 0, 1000));
    private final Setting<Double> damage = this.add(new NumberSetting<>("Damage", 0.8, 0.0, 2.0));
    private final Setting<Boolean> instantMine = this.add(new BooleanSetting("InstantMine", false));
    private final Setting<Integer> instantDelay = this.add(new NumberSetting<>("InstantDelay", 10, 0, 1000).dependsOn(this.instantMine));
    private final Setting<Boolean> swingHand = this.add(new BooleanSetting("SwingHand", true));
    private final Setting<Integer> switchTime = this.add(new NumberSetting<>("SwitchTime", 100, 0, 1000));
    private final Setting<Boolean> renderProgress = this.add(new BooleanSetting("RenderProgress", true));
    private final Setting<RenderMode> renderMode = this.add(new EnumSetting<>("RenderMode", RenderMode.Both));
    private final Setting<Color> sideColor = this.add(new ColorSetting("Side", new Color(140, 142, 255, 50)));
    private final Setting<Color> lineColor = this.add(new ColorSetting("Line", new Color(140, 142, 255, 255)));
    private final Setting<Color> textColor = this.add(new ColorSetting("TextColor", new Color(140, 142, 255, 255)));

    private BlockPos target;
    private boolean started;
    private boolean completed;
    private double progressTicks;
    private long lastUpdateMs;
    private long lastStopMs;
    private int breakAttempts;
    private int previousSlot = -1;
    private boolean switched;
    private long switchedMs;

    public PacketMine(ModuleCategory category) {
        super("PacketMine", "Cancels vanilla mining and drives it with START/STOP destroy packets, with silent tool switching.", category);
    }

    @Override
    public void onEnable() {
        this.reset();
    }

    @Override
    public void onDisable() {
        this.restoreSlot();
        this.reset();
    }

    @Override
    public String getInfo() {
        if (this.target == null) {
            return null;
        }
        return "[" + this.percent() + "%]";
    }

    @EventHandler
    private void onStartDestroy(MultiPlayerGameModeStartDestroyBlockEvent event) {
        if (!this.inGame() || event.getPos() == null) {
            return;
        }
        if (BreakTracker.unbreakable(event.getPos())) {
            return;
        }
        event.cancel();

        long now = System.currentTimeMillis();
        if (now - this.lastStopMs < this.mineDelay.getValue()) {
            return;
        }
        this.mine(event.getPos());
    }

    private void mine(BlockPos pos) {
        if (pos.equals(this.target)) {
            return;
        }
        this.restoreSlot();
        this.target = pos;
        this.started = false;
        this.completed = false;
        this.progressTicks = 0.0;
        this.lastUpdateMs = System.currentTimeMillis();
        this.breakAttempts = 0;
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (!this.inGame() || this.target == null) {
            return;
        }

        long now = System.currentTimeMillis();

        // 切镐时间到，切回原槽
        if (this.switched && now - this.switchedMs >= this.switchTime.getValue()) {
            this.restoreSlot();
        }

        if (this.isFar(this.target)) {
            this.target = null;
            return;
        }

        BlockState state = mc.level.getBlockState(this.target);
        if (state.isAir() || state.canBeReplaced()) {
            this.onBroken();
            return;
        }

        double maxTicks = Math.max(BreakTracker.breakTimeMs(this.target) / 50.0, 1.0);

        if (!this.started) {
            this.sendStart(this.target);
            return;
        }

        double deltaSeconds = Math.max(0.0, (now - this.lastUpdateMs) / 1000.0);
        this.lastUpdateMs = now;
        boolean onGround = mc.player.onGround();
        if (!this.checkGround.getValue() || onGround) {
            this.progressTicks += deltaSeconds * 20.0;
        } else {
            this.progressTicks += deltaSeconds * 4.0;
        }

        if (this.progressTicks < maxTicks * this.damage.getValue()) {
            return;
        }

        this.completed = true;
        if (this.instantMine.getValue()) {
            if (now - this.lastStopMs >= this.instantDelay.getValue()) {
                this.sendStop(this.target);
                this.lastStopMs = now;
            }
            return;
        }
        this.sendStop(this.target);
        this.lastStopMs = now;
    }

    private void onBroken() {
        this.breakAttempts = 0;
        this.target = null;
        this.started = false;
        this.completed = false;
        this.progressTicks = 0.0;
        this.restoreSlot();
    }

    private void sendStart(BlockPos pos) {
        Direction side = clickSide(pos);
        PacketSender.sendPlayerAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, side);
        if (this.fastBypass.getValue()) {
            // 上游的「快速绕过」：紧跟一个埋在 y=-999 的无效 START，扰乱服务端对挖掘序列的判定
            PacketSender.sendPlayerAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    new BlockPos(0, -999, 0), Direction.UP);
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
        this.started = true;
        this.progressTicks = 0.0;
        this.lastUpdateMs = System.currentTimeMillis();
    }

    private void sendStop(BlockPos pos) {
        if (this.usingPause.getValue() && this.isUsingItem()) {
            return;
        }

        if (this.autoSwitch.getValue() != SwitchMode.None) {
            int bestSlot = BreakTracker.bestToolSlot(pos);
            if (bestSlot >= 0 && bestSlot != InventoryUtils.getSelectedSlot()) {
                if (this.previousSlot < 0) {
                    this.previousSlot = InventoryUtils.getSelectedSlot();
                }
                if (this.autoSwitch.getValue() == SwitchMode.Silent) {
                    PacketSender.sendSelectedSlot(bestSlot);
                } else {
                    InventoryUtils.selectHotbarSlot(bestSlot);
                }
                this.switched = true;
                this.switchedMs = System.currentTimeMillis();
            }
        }

        if (this.bypassGround.getValue() && !mc.player.onGround() && !mc.player.isFallFlying()) {
            // 上游的「滞空绕过」：先把 onGround 置真再发 STOP
            PacketSender.send(new ServerboundMovePlayerPacket.PosRot(
                    mc.player.getX(), mc.player.getY() + 1.0E-9, mc.player.getZ(),
                    mc.player.getYRot(), mc.player.getXRot(), true, mc.player.horizontalCollision));
            mc.player.setOnGround(true);
        }

        if (this.swingHand.getValue()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        PacketSender.sendPlayerAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, clickSide(pos));

        this.breakAttempts++;
        if (this.breakAttempts > this.tryBreakTime.getValue() * 10) {
            this.breakAttempts = 0;
            this.target = null;
        } else if (!this.instantMine.getValue()) {
            // 还没断就再发一轮 START，等下一个进度周期
            this.started = false;
            this.completed = false;
            this.progressTicks = 0.0;
        }
    }

    private void restoreSlot() {
        if (!this.switched) {
            return;
        }
        this.switched = false;
        if (this.previousSlot >= 0) {
            if (this.autoSwitch.getValue() == SwitchMode.Silent) {
                PacketSender.sendSelectedSlot(this.previousSlot);
            } else if (this.autoSwitch.getValue() == SwitchMode.Delay) {
                InventoryUtils.selectHotbarSlot(this.previousSlot);
            }
            this.previousSlot = -1;
        }
    }

    private void reset() {
        this.target = null;
        this.started = false;
        this.completed = false;
        this.progressTicks = 0.0;
        this.breakAttempts = 0;
        this.lastStopMs = 0L;
    }

    private boolean isUsingItem() {
        if (mc.player == null) {
            return false;
        }
        return this.onlyMain.getValue() ? mc.player.isUsingItem() && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND
                : mc.player.isUsingItem();
    }

    private boolean isFar(BlockPos pos) {
        Vec3 center = pos.getCenter();
        return mc.player.getEyePosition().distanceTo(center) > this.range.getValue();
    }

    private int percent() {
        if (this.target == null) {
            return 0;
        }
        double maxTicks = Math.max(BreakTracker.breakTimeMs(this.target) / 50.0, 1.0);
        double total = Math.max(maxTicks * this.damage.getValue(), 1.0);
        if (this.completed || this.progressTicks >= total) {
            return 100;
        }
        return (int) Math.min(100.0, this.progressTicks / total * 100.0);
    }

    /** 从玩家眼睛指向方块中心，取主轴绝对值最大的那一面作为破坏面（等价于 {@code Direction#getNearest}）。 */
    private static Direction clickSide(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = eye.x - (pos.getX() + 0.5);
        double dy = eye.y - (pos.getY() + 0.5);
        double dz = eye.z - (pos.getZ() + 0.5);
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ay >= ax && ay >= az) {
            return dy > 0.0 ? Direction.UP : Direction.DOWN;
        }
        if (ax >= az) {
            return dx > 0.0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0.0 ? Direction.SOUTH : Direction.NORTH;
    }

    @EventHandler
    private void onRender(WorldRenderEvent.Main event) {
        if (!this.renderProgress.getValue() || this.target == null) {
            return;
        }

        AABB box = new AABB(this.target);
        if (this.renderMode.getValue() != RenderMode.Lines) {
            WorldBoxRenderer.fillBox(event.getPoseStack(), box, this.sideColor.getValue());
        }
        if (this.renderMode.getValue() != RenderMode.Sides) {
            WorldBoxRenderer.outlineBox(event.getPoseStack(), box, this.lineColor.getValue(), 1.5F);
        }

        String text = this.completed ? "100" : String.valueOf(this.percent());
        Vec3 position = new Vec3(this.target.getX() + 0.5, this.target.getY() - 0.1, this.target.getZ() + 0.5);
        float width = FontRenderer.INSTANCE.getWidth(text);
        RenderUtils.INSTANCE.drawWorldText(event.getPoseStack(), text, position,
                -width / 2.0F, 0.0F, 1.0F, this.textColor.getValue(), true);
    }

    /** 自动切镐模式；{@code Silent} 只发选中格包，{@code Delay} 连本地选中格一起改。 */
    public enum SwitchMode implements NamedEntry {
        None("None"),
        Delay("Delay"),
        Silent("Silent");

        private final String name;

        SwitchMode(String name) {
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
