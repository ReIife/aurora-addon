/*
 * Behaviour ported from LeavesHack (AGPL-3.0, MrBZBZ) — `modules/BreakESP.java`.
 * This file is distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.modules.combat;

import com.aurora.addon.modules.AuroraModule;
import com.aurora.addon.utils.BreakTracker;
import com.aurora.addon.utils.RenderMode;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.render.WorldRenderEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.font.FontRenderer;
import evernight.client.render.util.RenderUtils;
import evernight.client.render.mesh.WorldBoxRenderer;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.ColorSetting;
import evernight.client.settings.types.EnumSetting;
import evernight.client.settings.types.NumberSetting;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.text.DecimalFormat;

/**
 * 挖掘显示：把周围玩家正在挖的方块画成带进度文字的方框，并区分「主挖」与「副挖（双挖）」。
 *
 * <p>行为移植自 LeavesHack 的 {@code BreakESP}。进度数据来自 {@link BreakTracker}（对应上游
 * {@code BreakManager}）。与原版的差异：
 * <ul>
 *     <li>Meteor 的 {@code Render3DEvent} + {@code ShapeMode} → {@code WorldRenderEvent.Main} +
 *     {@link WorldBoxRenderer} + {@link RenderMode}；</li>
 *     <li>Meteor 的 {@code NametagUtils}/{@code TextRenderer} → {@code RenderUtils#drawWorldText}；</li>
 *     <li>好友判定走本体的 {@code SocialRegistry}。</li>
 * </ul>
 */
public final class BreakESP extends AuroraModule {
    private final BreakTracker tracker;

    private final Setting<Boolean> progress = this.add(new BooleanSetting("Progress", true));
    private final Setting<Double> damage = this.add(new NumberSetting<>("Damage", 1.0, 0.0, 2.0));
    private final Setting<RenderMode> renderMode = this.add(new EnumSetting<>("RenderMode", RenderMode.Both));
    private final Setting<Color> boxColor = this.add(new ColorSetting("Box", new Color(140, 142, 255, 255)));
    private final Setting<Color> fillColor = this.add(new ColorSetting("Fill", new Color(140, 142, 255, 50)));
    private final Setting<Color> friendBoxColor = this.add(new ColorSetting("FriendBox", new Color(140, 142, 255, 255)));
    private final Setting<Color> friendFillColor = this.add(new ColorSetting("FriendFill", new Color(140, 142, 255, 50)));
    private final Setting<Boolean> showSecondary = this.add(new BooleanSetting("Secondary", true));
    private final Setting<Color> secondaryBoxColor = this.add(new ColorSetting("SecondaryBox", new Color(140, 142, 255, 255)));
    private final Setting<Color> secondaryFillColor = this.add(new ColorSetting("SecondaryFill", new Color(140, 142, 255, 50)));
    private final Setting<Color> nameColor = this.add(new ColorSetting("NameColor", new Color(140, 142, 255, 255)));
    private final Setting<Color> progressColor = this.add(new ColorSetting("ProgressColor", new Color(140, 142, 255, 255)));
    private final Setting<Color> secondaryNameColor = this.add(new ColorSetting("SecondaryNameColor", new Color(140, 142, 255, 255)));
    private final Setting<Color> secondaryTextColor = this.add(new ColorSetting("SecondaryTextColor", new Color(140, 142, 255, 255)));
    private final Setting<Double> textScale = this.add(new NumberSetting<>("TextScale", 1.0, 0.5, 3.0));

    private final DecimalFormat format = new DecimalFormat("0.0");

    public BreakESP(ModuleCategory category, BreakTracker tracker) {
        super("BreakESP", "Draws boxes, names and progress for blocks other players are breaking.", category);
        this.tracker = tracker;
    }

    @Override
    public String getInfo() {
        return this.tracker.mainBreaks().isEmpty() ? null : String.valueOf(this.tracker.mainBreaks().size());
    }

    @EventHandler
    private void onRender(WorldRenderEvent.Main event) {
        if (!this.inGame()) {
            return;
        }

        for (BreakTracker.BreakData data : this.tracker.mainBreaks().values()) {
            Player breaker = data.entity();
            if (breaker == null) {
                continue;
            }
            boolean friend = ClientContext.socialRegistry != null && ClientContext.socialRegistry.isFriendPlayer(breaker);
            this.drawBox(event, data.pos, friend ? this.friendFillColor.getValue() : this.fillColor.getValue(),
                    friend ? this.friendBoxColor.getValue() : this.boxColor.getValue());
            this.drawName(event, breaker.getGameProfile().name(), data.pos, 0.6, this.nameColor.getValue());
            if (this.progress.getValue()) {
                String text = data.failed ? "Failed" : (data.complete ? "Broke"
                        : this.format.format(data.progress(this.damage.getValue()) * 100.0));
                this.drawText(event, text, data.pos, -0.1, this.progressColor.getValue());
            }
        }

        if (!this.showSecondary.getValue()) {
            return;
        }

        for (BreakTracker.BreakData data : this.tracker.secondaryBreaks().values()) {
            Player breaker = data.entity();
            if (breaker == null || mc.level.isEmptyBlock(data.pos)) {
                continue;
            }
            BreakTracker.BreakData main = this.tracker.mainBreaks().get(breaker.getId());
            if (main != null && main.pos.equals(data.pos)) {
                continue;
            }
            this.drawBox(event, data.pos, this.secondaryFillColor.getValue(), this.secondaryBoxColor.getValue());
            this.drawName(event, breaker.getGameProfile().name(), data.pos, 0.6, this.secondaryNameColor.getValue());
            this.drawText(event, "Double", data.pos, -0.1, this.secondaryTextColor.getValue());
        }
    }

    private void drawBox(WorldRenderEvent.Main event, net.minecraft.core.BlockPos pos, Color fill, Color line) {
        AABB box = new AABB(pos);
        if (this.renderMode.getValue() != RenderMode.Lines) {
            WorldBoxRenderer.fillBox(event.getPoseStack(), box, fill);
        }
        if (this.renderMode.getValue() != RenderMode.Sides) {
            WorldBoxRenderer.outlineBox(event.getPoseStack(), box, line, 1.5F);
        }
    }

    private void drawName(WorldRenderEvent.Main event, String name, net.minecraft.core.BlockPos pos, double yOffset, Color color) {
        this.drawText(event, name, pos, yOffset, color);
    }

    /** 在方块中心 + yOffset 处画居中的世界文字。 */
    private void drawText(WorldRenderEvent.Main event, String text, net.minecraft.core.BlockPos pos, double yOffset, Color color) {
        Vec3 position = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5 + yOffset, pos.getZ() + 0.5);
        float width = FontRenderer.INSTANCE.getWidth(text);
        RenderUtils.INSTANCE.drawWorldText(event.getPoseStack(), text, position,
                -width / 2.0F, 0.0F, this.textScale.getValue().floatValue(), color, true);
    }
}
