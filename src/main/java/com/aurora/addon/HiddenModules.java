/*
 * New file in this port (not present upstream): reproduces the Meteor addon's
 * "hiddenModules" behaviour for Evernight, which has no such concept built in.
 * Distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon;

import evernight.client.modules.Module;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 需要从 ClickGUI 里隐藏、但仍要保留功能的模块登记表。
 *
 * <p>上游 Meteor 版在 {@code @PostInit} 里把这些子模块塞进 {@code Config#hiddenModules}，
 * 于是它们不出现在 ClickGUI，但仍可被 {@code ElytraCollect} 拉起、也能用 {@code ;<模块名>} 开关。
 *
 * <p>Evernight 没有等价机制：{@code Module#setDrawn(false)} 只影响 ModuleList HUD 与通知，
 * {@code ModuleCategoryPanel} 依旧会把同分类的模块全部列出来；而
 * {@code Module#requiresPlatform(false)} 会让模块彻底不可用（{@code enable()} 直接返回），
 * 不符合「隐藏但能用」的语义。因此这里做两件事：
 *
 * <ol>
 *     <li>{@link #hide(Module)} 调 {@code setDrawn(false)}，让 ModuleList HUD / 通知也不再显示它；</li>
 *     <li>把模块的 {@code Class} 记进本表，由插件自带的 {@code ModuleCategoryPanelMixin}
 *     在分类面板构建完成后把对应条目摘掉——这一步才是真正让 ClickGUI 不显示。</li>
 * </ol>
 *
 * <p>用 {@code Class} 而不是实例做键：配置加载（{@code modules.json} 里的 {@code drawn} 字段）
 * 不会影响这里，隐藏状态因此不依赖玩家的存档，也不会被旧配置「反隐藏」。
 */
public final class HiddenModules {
    private static final Set<Class<?>> HIDDEN = Collections.synchronizedSet(new LinkedHashSet<>());

    private HiddenModules() {
    }

    public static void hide(Module module) {
        if (module == null) {
            return;
        }
        module.setDrawn(false);
        HIDDEN.add(module.getClass());
    }

    public static boolean isHidden(Module module) {
        return module != null && HIDDEN.contains(module.getClass());
    }

    public static int count() {
        return HIDDEN.size();
    }
}
