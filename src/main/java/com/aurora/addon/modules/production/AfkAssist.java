/*
 * Port of AFKMode from Aurora (Meteor Client addon, GPL-3.0-only, author n_lzy).
 * Original: https://github.com/  (Aurora addon jar 1.21.11-1.4.0)
 * This file is distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.modules.production;

import com.aurora.addon.modules.AuroraModule;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.Module;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.settings.types.StringSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 挂机辅助：一段时间没有主动操作后自动打开指定模块，有操作后自动退出 AFK 并关闭指定模块。
 *
 * <p>移植自 Meteor 插件的 {@code AFKMode}。原版用 {@code ModuleListSetting} 存模块对象；
 * Evernight 发行 jar 没有该设置类型，这里改用逗号分隔的模块名（模块名是全局稳定键，
 * 例如 {@code AutoTotem,AutoLog,AutoEat}），运行期按名字在模块注册表里解析。
 */
public final class AfkAssist extends AuroraModule {
    private final Setting<Integer> afkTimeout = this.add(new NumberSetting<>("AfkTimeout", 300, 10, 3600));
    private final Setting<String> modulesToEnable = this.add(new StringSetting("ModulesToEnable", "AutoTotem,AutoLog,AutoEat"));
    private final Setting<String> modulesOnExit = this.add(new StringSetting("ModulesOnExit", "AutoLog,AutoEat"));

    private final List<Module> enabledByAfk = new ArrayList<>();
    private long lastActiveTime;
    private boolean afk;

    public AfkAssist(ModuleCategory category) {
        super("AfkAssist", "Enables modules after a period without input and restores them when you act again.", category);
    }

    @Override
    public void onEnable() {
        this.lastActiveTime = System.currentTimeMillis();
        this.afk = false;
        this.enabledByAfk.clear();
    }

    @Override
    public void onDisable() {
        this.disableModulesOnExit();
        this.enabledByAfk.clear();
        this.afk = false;
    }

    @Override
    public String getInfo() {
        return this.afk ? Translations.get(this.translationKey("info.afk")) : null;
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (!this.inGame()) {
            return;
        }

        boolean active = mc.options.keyUp.isDown()
                || mc.options.keyDown.isDown()
                || mc.options.keyLeft.isDown()
                || mc.options.keyRight.isDown()
                || mc.options.keyJump.isDown()
                || mc.options.keyAttack.isDown()
                || mc.options.keyUse.isDown()
                || mc.player.isUsingItem()
                || mc.player.isFallFlying();

        long now = System.currentTimeMillis();
        if (active) {
            this.lastActiveTime = now;
            if (this.afk) {
                this.disableModulesOnExit();
                this.enabledByAfk.clear();
                this.afk = false;
            }
            return;
        }

        if (!this.afk && now - this.lastActiveTime >= this.afkTimeout.getValue() * 1000L) {
            this.enterAfk();
        }
    }

    private void enterAfk() {
        this.afk = true;
        this.lastActiveTime = System.currentTimeMillis();

        List<Module> targets = this.resolveModules(this.modulesToEnable.getValue());
        if (targets.isEmpty()) {
            this.error("messages.afkNoModules");
            return;
        }

        for (Module module : targets) {
            if (module == this) {
                continue;
            }
            if (!module.isToggled()) {
                module.enable();
            }
            this.enabledByAfk.add(module);
            this.info("messages.afkEnabled", module.getDisplayName());
        }
    }

    private void disableModulesOnExit() {
        for (Module module : this.resolveModules(this.modulesOnExit.getValue())) {
            if (module != this && module.isToggled()) {
                module.disable();
                this.info("messages.afkDisabled", module.getDisplayName());
            }
        }
    }

    private List<Module> resolveModules(String configuredNames) {
        List<Module> resolved = new ArrayList<>();
        if (configuredNames == null || configuredNames.isBlank()) {
            return resolved;
        }
        for (String rawName : configuredNames.split(",")) {
            String name = rawName.trim();
            if (name.isEmpty()) {
                continue;
            }
            String normalized = name.toLowerCase(Locale.ROOT);
            Module module = ClientContext.moduleRegistry.findFirstOrNull(
                    candidate -> candidate.getName().toLowerCase(Locale.ROOT).equals(normalized));
            if (module != null && !resolved.contains(module)) {
                resolved.add(module);
            }
        }
        return resolved;
    }
}
