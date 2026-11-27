/*
 * New file in this port (not present upstream): filters hidden modules out of the ClickGUI.
 * Distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.mixin;

import com.aurora.addon.HiddenModules;
import evernight.client.gui.components.ModuleComponent;
import evernight.client.gui.panels.ModuleCategoryPanel;
import evernight.client.gui.panels.Panel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让 {@link HiddenModules} 里登记的模块不出现在 ClickGUI 分类面板中。
 *
 * <p>注入点选在 {@code ModuleCategoryPanel#populateModules} 的返回处，而不是重写整个方法：
 * 本体怎么列模块（排序、搜索框过滤、以后新增的过滤条件）都保持原样，这里只做减法。
 * 面板与 HUD 编辑器共用同一个类（{@code HudModulePanel} 是它的子类），所以 HUD 编辑器里同样不显示。
 *
 * <p>依赖的是本体的内部结构（{@code Panel#children} 是 public 字段、{@code ModuleComponent#getModule()}）。
 * 升级 Evernight 后如果这些名字变了，本 mixin 会**静默失效**（{@code require = 0} + 配置里
 * {@code required: false}）：游戏照常启动，隐藏退化为「只在 ModuleList HUD 里不显示」，
 * 其余功能不受影响——不会因为一个纯 GUI 过滤把客户端拖崩。
 */
@Mixin(ModuleCategoryPanel.class)
public abstract class ModuleCategoryPanelMixin {
    @Inject(method = "populateModules", at = @At("RETURN"), require = 0)
    private void aurora$removeHiddenModules(CallbackInfo info) {
        Panel panel = (Panel) (Object) this;
        panel.children.removeIf(child -> child instanceof ModuleComponent component
                && HiddenModules.isHidden(component.getModule()));
    }
}
