/*
 * New file in this port: shared render-mode enum for box drawing.
 * Distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.utils;

import evernight.client.render.text.Translations;
import evernight.client.settings.core.NamedEntry;

/**
 * 方块 ESP 的绘制模式：只填充 / 只描边 / 两者都画。
 *
 * <p>对应 Meteor 的 {@code ShapeMode}。{@code getName()} 是稳定英文标识（进配置），
 * 显示名查 {@code addons.aurora.names.values.*}。
 */
public enum RenderMode implements NamedEntry {
    Both("Both"),
    Sides("Sides"),
    Lines("Lines");

    private final String name;

    RenderMode(String name) {
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
