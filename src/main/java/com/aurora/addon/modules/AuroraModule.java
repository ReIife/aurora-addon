package com.aurora.addon.modules;

import evernight.addon.AddonModule;
import evernight.client.ClientAccess;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.text.Translations;
import evernight.client.utils.chat.ChatUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Aurora 模块公共基类：只做三件在移植中反复出现的机械工作。
 *
 * <ul>
 *     <li>{@link #mc}：对应 Meteor 模块父类的 {@code mc} 字段，取值 {@link ClientAccess#evernightMinecraft}。</li>
 *     <li>{@link #info}/{@link #warning}/{@link #error}：对应 Meteor 的 {@code Module#info} 等，
 *     文本一律走语言表（{@code addons.aurora.*}），不在 Java 里写死。</li>
 *     <li>{@link #inGame()}：对应 Meteor 的 {@code Utils.canUpdate()}。</li>
 * </ul>
 *
 * <p>不是框架封装：设置、事件、生命周期全部直接用 Evernight API。
 */
public abstract class AuroraModule extends AddonModule {
    protected static final Minecraft mc = ClientAccess.evernightMinecraft;

    protected AuroraModule(String name, String description, ModuleCategory category, String... aliases) {
        super("aurora", name, description, category, aliases);
    }

    protected boolean inGame() {
        return !this.isWorldUnavailable();
    }

    protected void info(String key, Object... args) {
        this.print(key, args);
    }

    protected void warning(String key, Object... args) {
        this.print(key, args);
    }

    protected void error(String key, Object... args) {
        this.print(key, args);
    }

    /**
     * 查 {@code addons.aurora.<key>} 并按 {@link String#format} 填充占位符。
     *
     * <p>注意不要用 {@code Translations.format}：那个方法固定查本体命名空间
     * （{@code evernight.ui.*}），传插件键会查不到。
     */
    protected String translate(String key, Object... args) {
        String pattern = Translations.get(this.translationKey(key));
        return args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
    }

    private void print(String key, Object... args) {
        String text = this.translate(key, args);
        // 聊天栏不是线程安全的：后台线程（例如计算路线的搜索线程）打日志要切回主线程
        if (mc.isSameThread()) {
            ChatUtils.printChat(Component.literal(text), ChatUtils.moduleSignature(this));
        } else {
            mc.execute(() -> ChatUtils.printChat(Component.literal(text), ChatUtils.moduleSignature(this)));
        }
    }
}
