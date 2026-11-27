/*
 * Port of ElytraInfiniteFlight from Aurora (Meteor Client addon, GPL-3.0-only, author n_lzy).
 * Original: https://github.com/  (Aurora addon jar 1.21.11-1.4.0)
 * This file is distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.modules.elytra;

import com.aurora.addon.modules.AuroraModule;
import evernight.client.events.core.EventHandler;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.NumberSetting;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 鞘翅 Pitch40 飞行：靠俯冲 / 拉升的俯仰角循环维持速度，不需要烟花。
 *
 * <p>移植自 Meteor 插件的 {@code ElytraInfiniteFlight}。逻辑逐行保留：
 * 水平速度达到「最大俯冲速度」后转拉升，掉到「拉升失速速度」后转俯冲；
 * 打开高度限制后，超过最高高度强制俯冲，降到目标高度以下且仍有速度时立即拉升。
 */
public final class ElytraPitch40 extends AuroraModule {
    private final Setting<Double> diveAngle = this.add(new NumberSetting<>("DiveAngle", 33.0, 10.0, 60.0));
    private final Setting<Double> diveTargetSpeed = this.add(new NumberSetting<>("DiveTargetSpeed", 40.0, 25.0, 60.0));
    private final Setting<Double> pullUpAngle = this.add(new NumberSetting<>("PullUpAngle", -49.0, -70.0, -20.0));
    private final Setting<Double> pullUpStallSpeed = this.add(new NumberSetting<>("PullUpStallSpeed", 18.0, 8.0, 30.0));
    private final Setting<Double> diveTransitionSpeed = this.add(new NumberSetting<>("DiveTransitionSpeed", 1.5, 0.3, 8.0));
    private final Setting<Double> pullUpTransitionSpeed = this.add(new NumberSetting<>("PullUpTransitionSpeed", 7.0, 1.0, 30.0));
    private final Setting<Boolean> enableHeightLimit = this.add(new BooleanSetting("EnableHeightLimit", false));
    private final Setting<Double> maxHeight =
            this.add(new NumberSetting<>("MaxHeight", 480.0, 320.0, 100000.0).dependsOn(this.enableHeightLimit));
    private final Setting<Double> targetHeight =
            this.add(new NumberSetting<>("TargetHeight", 240.0, 200.0, 2000.0).dependsOn(this.enableHeightLimit));

    private Phase phase = Phase.DIVE;
    private boolean forcedDive;
    private float currentPitch;

    public ElytraPitch40(ModuleCategory category) {
        super("ElytraPitch40", "Keeps elytra speed with a pitch-40 dive/pull-up cycle, no fireworks needed.", category);
    }

    @Override
    public void onEnable() {
        if (mc.player != null) {
            this.phase = Phase.DIVE;
            this.currentPitch = mc.player.getXRot();
        }
        if (this.enableHeightLimit.getValue() && this.maxHeight.getValue() <= this.targetHeight.getValue()) {
            this.warning("messages.heightLimitInvalid");
            this.enableHeightLimit.setValue(false);
        }
    }

    @Override
    public void onDisable() {
        this.phase = Phase.DIVE;
        this.forcedDive = false;
    }

    @Override
    public String getInfo() {
        return Translations.get(this.translationKey(this.phase == Phase.DIVE ? "info.dive" : "info.pullUp"));
    }

    @EventHandler(priority = 100)
    private void onTick(MinecraftTickEvent event) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        double horizontalSpeed = this.horizontalSpeed();
        double y = mc.player.getY();
        boolean heightMode = this.enableHeightLimit.getValue();

        if (heightMode) {
            if (y >= this.maxHeight.getValue()) {
                this.forcedDive = true;
            }
            if (this.forcedDive && y <= this.targetHeight.getValue()) {
                this.forcedDive = false;
            }
        } else {
            this.forcedDive = false;
        }

        if (this.forcedDive) {
            this.phase = Phase.DIVE;
            this.smoothPitch(this.diveAngle.getValue().floatValue(), this.diveTransitionSpeed.getValue().floatValue());
            return;
        }

        if (heightMode && y <= this.targetHeight.getValue() && horizontalSpeed >= this.diveTargetSpeed.getValue()) {
            this.phase = Phase.PULL_UP;
        }

        switch (this.phase) {
            case DIVE -> {
                this.smoothPitch(this.diveAngle.getValue().floatValue(), this.diveTransitionSpeed.getValue().floatValue());
                if (horizontalSpeed >= this.diveTargetSpeed.getValue()) {
                    this.phase = Phase.PULL_UP;
                }
            }
            case PULL_UP -> {
                this.smoothPitch(this.pullUpAngle.getValue().floatValue(), this.pullUpTransitionSpeed.getValue().floatValue());
                if (horizontalSpeed <= this.pullUpStallSpeed.getValue()) {
                    this.phase = Phase.DIVE;
                }
            }
        }
    }

    private double horizontalSpeed() {
        Vec3 velocity = mc.player.getDeltaMovement();
        return Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z) * 20.0;
    }

    private void smoothPitch(float target, float speed) {
        float difference = target - this.currentPitch;
        float step = Mth.clamp(difference, -speed, speed);
        this.currentPitch += step;
        mc.player.setXRot(this.currentPitch);
    }

    private enum Phase {
        DIVE,
        PULL_UP
    }
}
