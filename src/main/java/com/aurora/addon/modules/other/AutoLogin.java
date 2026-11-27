/*
 * Behaviour ported from LeavesHack (AGPL-3.0, MrBZBZ) — `modules/AutoLogin.java`.
 * This file is distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.modules.other;

import com.aurora.addon.modules.AuroraModule;
import com.aurora.addon.utils.LoginAccounts;
import evernight.client.ClientAccess;
import evernight.client.events.core.EventHandler;
import evernight.client.events.network.ConnectScreenConnectEvent;
import evernight.client.events.network.PacketEvent;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.StringSetting;
import evernight.client.utils.chat.ChatUtils;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;

import java.util.Optional;

/**
 * 自动登录：连过的服务器自动发 {@code /<登录指令> <密码>}，并把手动输入的登录/注册/改密指令里的密码记下来。
 *
 * <p>行为移植自 LeavesHack 的 {@code AutoLogin}，差异：
 * <ul>
 *     <li>账号表用 {@link LoginAccounts}（JSON）而不是 Meteor 的 NBT {@code System}；</li>
 *     <li>上游在模块里内嵌 Meteor GUI 表格来增删账号，Evernight 插件没有等价的「模块自带控件」接口，
 *     改为 {@code ;autologin} 指令管理（见 {@code commands/LoginCommand}）；</li>
 *     <li>服务器地址取自 {@link ConnectScreenConnectEvent}，取不到时回退到
 *     {@code Minecraft#getCurrentServer()}。</li>
 * </ul>
 */
public final class AutoLogin extends AuroraModule {
    private final LoginAccounts accounts;

    private final Setting<Boolean> autoSave = this.add(new BooleanSetting("AutoSave", true));
    private final Setting<String> loginCommand = this.add(new StringSetting("LoginCommand", "l"));
    private final Setting<String> registerCommand = this.add(new StringSetting("RegisterCommand", "reg"));
    private final Setting<String> changePasswordCommand = this.add(new StringSetting("ChangePasswordCommand", "cp"));

    private String server = "";
    private String pendingPassword;

    public AutoLogin(ModuleCategory category, LoginAccounts accounts) {
        super("AutoLogin", "Logs in automatically on servers you saved, and remembers passwords typed into /l, /reg or /cp.", category);
        this.accounts = accounts;
    }

    @Override
    public void onEnable() {
        this.server = currentServer();
        this.pendingPassword = null;
    }

    @Override
    public void onDisable() {
        this.pendingPassword = null;
    }

    @Override
    public String getInfo() {
        return this.accounts.size() == 0 ? null : String.valueOf(this.accounts.size());
    }

    @EventHandler
    private void onConnect(ConnectScreenConnectEvent event) {
        this.server = LoginAccounts.normalizeServer(event.getServerAddress());
        this.pendingPassword = null;

        String username = username();
        if (username.isEmpty()) {
            return;
        }
        Optional<LoginAccounts.Account> account = this.accounts.find(username, this.server);
        account.ifPresent(value -> this.pendingPassword = value.password());
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.pendingPassword == null || !this.inGame()) {
            return;
        }
        String password = this.pendingPassword;
        this.pendingPassword = null;
        if (password.isEmpty()) {
            return;
        }
        String command = this.loginCommand.getValue();
        if (command == null || command.isBlank()) {
            return;
        }
        ChatUtils.sendChatOrCommand("/" + command.trim() + " " + password);
        this.info("messages.loginSent", command.trim());
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (!this.autoSave.getValue() || !(event.getPacket() instanceof ServerboundChatCommandPacket packet)) {
            return;
        }
        String message = packet.command();
        if (message == null || message.isBlank()) {
            return;
        }

        String[] args = message.split(" ");
        if (args.length < 2) {
            return;
        }

        String head = args[0];
        String password;
        if (head.equalsIgnoreCase(this.changePasswordCommand.getValue())) {
            // /cp <旧密码> <新密码>：记新密码
            if (args.length < 3) {
                return;
            }
            password = args[2];
        } else if (head.equalsIgnoreCase(this.loginCommand.getValue())
                || head.equalsIgnoreCase(this.registerCommand.getValue())) {
            password = args[1];
        } else {
            return;
        }

        String username = username();
        String targetServer = this.server.isEmpty() ? currentServer() : this.server;
        if (username.isEmpty() || targetServer.isEmpty()) {
            return;
        }

        boolean added = this.accounts.put(username, targetServer, password);
        this.info(added ? "messages.accountAdded" : "messages.accountUpdated", targetServer);
    }

    private String username() {
        if (mc.getUser() == null) {
            return "";
        }
        return mc.getUser().getName();
    }

    private static String currentServer() {
        ServerData data = ClientAccess.evernightMinecraft.getCurrentServer();
        return data == null ? "" : LoginAccounts.normalizeServer(data.ip);
    }
}
