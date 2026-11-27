/*
 * Port of BetterChat (subset) from Aurora (Meteor Client addon, GPL-3.0-only, author n_lzy).
 * Original: https://github.com/  (Aurora addon jar 1.21.11-1.4.0)
 * This file is distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.modules.other;

import com.aurora.addon.modules.AuroraModule;
import evernight.client.events.core.EventHandler;
import evernight.client.events.core.EventStage;
import evernight.client.events.network.ClientPacketListenerSendChatEvent;
import evernight.client.events.ui.ChatMessageEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.StringSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天守卫：拦截以 {@code #} 开头的 Baritone 指令，并按玩家名屏蔽其聊天消息。
 *
 * <p>移植自 Meteor 插件的 {@code BetterChat}。原版还有「按关键字屏蔽」与「回显提示」，
 * 关键字屏蔽与本体自带的 {@code ChatFilter} 功能重复（见交付说明），因此这里只保留
 * 本体没有的两项能力；回显提示保留。
 *
 * <p>原版靠 {@code ChatHudMixin} 与 {@code ClientPlayNetworkHandlerMixin} 实现，
 * 这里全部改用 Evernight 已有的 {@link ChatMessageEvent} 与
 * {@link ClientPacketListenerSendChatEvent}，插件不再需要 mixin。
 */
public final class ChatGuard extends AuroraModule {
    private static final Pattern PLAYER_NAME = Pattern.compile("^<([a-zA-Z0-9_]{1,16})>");

    private final Setting<Boolean> baritoneProtect = this.add(new BooleanSetting("BaritoneProtect", false));
    private final Setting<Boolean> echoBlocked = this.add(new BooleanSetting("EchoBlocked", true));
    private final Setting<Boolean> blockByPlayer = this.add(new BooleanSetting("BlockByPlayer", false));
    private final Setting<String> blockedPlayers =
            this.add(new StringSetting("BlockedPlayers", "").dependsOn(this.blockByPlayer));

    public ChatGuard(ModuleCategory category) {
        super("ChatGuard", "Blocks outgoing #-commands and hides chat from listed players.", category);
    }

    @EventHandler
    private void onSendChat(ClientPacketListenerSendChatEvent event) {
        if (!this.baritoneProtect.getValue()) {
            return;
        }
        String message = event.getMessage();
        if (message != null && message.startsWith("#")) {
            event.cancel();
            this.echo("messages.blockedBaritone", message);
        }
    }

    @EventHandler
    private void onReceiveChat(ChatMessageEvent event) {
        if (event.getStage() != EventStage.Pre || !this.blockByPlayer.getValue()) {
            return;
        }
        String text = event.getMessage() == null ? null : event.getMessage().getString();
        if (text == null) {
            return;
        }
        String sender = this.extractPlayerName(text);
        if (sender == null) {
            return;
        }
        for (String blocked : this.blockedPlayers()) {
            if (blocked.equalsIgnoreCase(sender)) {
                event.cancel();
                this.echo("messages.blockedPlayer", text);
                return;
            }
        }
    }

    private String extractPlayerName(String text) {
        Matcher matcher = PLAYER_NAME.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private List<String> blockedPlayers() {
        List<String> names = new ArrayList<>();
        String configured = this.blockedPlayers.getValue();
        if (configured == null || configured.isBlank()) {
            return names;
        }
        for (String raw : configured.split(",")) {
            String name = raw.trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    private void echo(String key, String content) {
        if (this.echoBlocked.getValue()) {
            this.info(key, content);
        }
    }
}
