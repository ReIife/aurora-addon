/*
 * Port of Resupply/ContinueFly log facade (new in this port) from Aurora (Meteor Client addon, GPL-3.0-only, author n_lzy).
 * This file is distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.modules.elytra.elytratravel;

/**
 * 辅助类的日志出口。
 *
 * <p>原 Meteor 版本给每个助手传三个 {@code Consumer<String>}（info/warn/error）并传入已经拼好的中文
 * 字符串。Evernight 侧玩家可见文本必须走语言表，所以这里改成传「键 + 参数」，由模块自己翻译并打印，
 * 助手类因此不需要知道 addon id，也不在 Java 里写死文案。
 */
public interface TravelLog {
    void info(String key, Object... args);

    void warning(String key, Object... args);

    void error(String key, Object... args);

    /** 只取翻译文本用于拼接，不打印。 */
    String text(String key, Object... args);
}
