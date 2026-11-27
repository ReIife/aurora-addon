/*
 * Port of BetterDirection from Aurora (Meteor Client addon, GPL-3.0-only, author n_lzy).
 * Original: https://github.com/  (Aurora addon jar 1.21.11-1.4.0)
 * This file is distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.modules.other;

import com.aurora.addon.modules.AuroraModule;
import evernight.client.events.core.EventHandler;
import evernight.client.events.render.HudRenderEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.ColorSetting;
import evernight.client.settings.types.NumberSetting;
import net.minecraft.client.gui.GuiGraphics;

import java.awt.Color;
import java.util.Locale;

/**
 * 方向条：在屏幕顶部画一条会随视角滚动的刻度条，并显示中文方位与坐标轴方位。
 *
 * <p>移植自 Meteor 插件的 {@code BetterDirection}。原版把方位文字写死在 Java 里，
 * 这里全部改走语言表（{@code addons.aurora.directions.*} 与 {@code addons.aurora.messages.*}）。
 */
public final class BetterDirection extends AuroraModule {
    private final Setting<Double> scale = this.add(new NumberSetting<>("Scale", 0.6, 0.5, 2.0));
    private final Setting<Boolean> background = this.add(new BooleanSetting("Background", false));
    private final Setting<Color> color = this.add(new ColorSetting("Color", new Color(255, 255, 255, 255)));

    public BetterDirection(ModuleCategory category) {
        super("BetterDirection", "Draws a compass strip with the current facing direction at the top of the screen.", category);
    }

    @EventHandler
    private void onRender(HudRenderEvent event) {
        if (mc.player == null) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        float scaleFactor = this.scale.getValue().floatValue();
        int textColor = this.color.getValue().getRGB();

        graphics.pose().pushMatrix();
        graphics.pose().scale(scaleFactor, scaleFactor);

        float center = screenWidth / 2.0F / scaleFactor;
        float top = 8.0F;
        float stripWidth = screenWidth * 0.33F / scaleFactor;
        float left = center - stripWidth / 2.0F;

        if (this.background.getValue()) {
            graphics.fill((int) left, (int) top, (int) (left + stripWidth), (int) (top + 22.0F), 1426063360);
        }

        float yaw = mc.player.getYRot();
        yaw = (yaw + 360.0F) % 360.0F;

        for (int tick = -25; tick <= 25; tick++) {
            float x = center + (tick * 12 - yaw % 12.0F) / scaleFactor;
            if (x < left || x > left + stripWidth) {
                continue;
            }
            int height = tick % 5 == 0 ? 12 : 6;
            int tickColor = tick == 0 ? 0xFFFFFFFF : textColor;
            graphics.fill((int) x, (int) top, (int) x + 1, (int) (top + height), tickColor);
        }

        graphics.fill((int) center, (int) top, (int) center + 2, (int) (top + 16.0F), 0xFFFFFFFF);

        String direction = this.directionText(yaw);
        int directionY = 25;
        graphics.drawString(mc.font, direction,
                (int) (center - mc.font.width(direction) / 2), directionY, textColor, true);

        String axis = this.axisText(yaw);
        int axisY = directionY + 10;
        graphics.drawString(mc.font, axis,
                (int) (center - mc.font.width(axis) / 2), axisY, textColor, true);

        graphics.pose().popMatrix();
    }

    private String directionText(float yaw) {
        yaw = (yaw + 360.0F) % 360.0F;

        String direction;
        float center;
        if (yaw >= 337.5 || yaw < 22.5) {
            direction = "S";
            center = 0.0F;
        } else if (yaw < 67.5) {
            direction = "SW";
            center = 45.0F;
        } else if (yaw < 112.5) {
            direction = "W";
            center = 90.0F;
        } else if (yaw < 157.5) {
            direction = "NW";
            center = 135.0F;
        } else if (yaw < 202.5) {
            direction = "N";
            center = 180.0F;
        } else if (yaw < 247.5) {
            direction = "NE";
            center = 225.0F;
        } else if (yaw < 292.5) {
            direction = "E";
            center = 270.0F;
        } else {
            direction = "SE";
            center = 315.0F;
        }

        float offset = yaw - center;
        if (offset > 180.0F) {
            offset -= 360.0F;
        }
        if (offset < -180.0F) {
            offset += 360.0F;
        }

        int degrees = Math.round(Math.abs(offset));
        String main = Translations.get(this.translationKey("directions." + direction));
        if (degrees <= 2) {
            return main;
        }

        String side = this.sideDirection(direction, offset > 0.0F);
        if (side == null) {
            return main;
        }
        return String.format(Locale.ROOT, Translations.get(this.translationKey("messages.directionOffset")),
                main, Translations.get(this.translationKey("directions." + side)), degrees);
    }

    private String sideDirection(String direction, boolean positive) {
        return switch (direction) {
            case "N" -> positive ? "E" : "W";
            case "E" -> positive ? "S" : "N";
            case "S" -> positive ? "W" : "E";
            case "W" -> positive ? "N" : "S";
            default -> null;
        };
    }

    private String axisText(float yaw) {
        double radians = Math.toRadians(yaw);
        double x = -Math.sin(radians);
        double z = Math.cos(radians);
        double absX = Math.abs(x);
        double absZ = Math.abs(z);

        String mainAxis;
        String mainSign;
        String subAxis;
        String subSign;
        double angle;
        if (absX >= absZ) {
            mainAxis = "X";
            mainSign = x >= 0.0 ? "+" : "-";
            subAxis = "Z";
            subSign = z >= 0.0 ? "+" : "-";
            angle = Math.toDegrees(Math.atan2(absZ, absX));
        } else {
            mainAxis = "Z";
            mainSign = z >= 0.0 ? "+" : "-";
            subAxis = "X";
            subSign = x >= 0.0 ? "+" : "-";
            angle = Math.toDegrees(Math.atan2(absX, absZ));
        }

        int degrees = (int) Math.round(angle);
        if (degrees == 0) {
            return String.format(Locale.ROOT, Translations.get(this.translationKey("messages.axis")), mainAxis, mainSign);
        }
        return String.format(Locale.ROOT, Translations.get(this.translationKey("messages.axisOffset")),
                mainAxis, mainSign, subAxis, subSign, degrees);
    }
}
