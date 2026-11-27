/*
 * New file in this port: account management for AutoLogin (upstream used an embedded Meteor GUI table).
 * Distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.commands;

import com.aurora.addon.modules.other.AutoLogin;
import com.aurora.addon.utils.LoginAccounts;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import evernight.client.ClientAccess;
import evernight.client.commands.Command;
import evernight.client.render.text.Translations;
import evernight.client.utils.chat.ChatUtils;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

/**
 * {@code ;autologin}（别名 {@code ;al}）：查看 / 添加 / 删除自动登录账号。
 *
 * <p>上游把账号表格做在模块自带的 Meteor GUI 控件里；Evernight 插件没有「模块自带控件」的接口，
 * 这里改成指令，功能对齐：列出、按当前服务器新增、按服务器删除、清空。
 */
public final class LoginCommand extends Command {
    private final LoginAccounts accounts;
    private final AutoLogin module;

    public LoginCommand(LoginAccounts accounts, AutoLogin module) {
        super("autologin");
        this.setAliases("al");
        this.accounts = accounts;
        this.module = module;
    }

    @Override
    public void exec(LiteralArgumentBuilder root) {
        root.executes(context -> {
            this.list();
            return 1;
        });

        root.then(Command.literal("list").executes(context -> {
            this.list();
            return 1;
        }));

        ArgumentBuilder addPassword = Command.argument("password", StringArgumentType.greedyString());
        addPassword.executes(context -> {
            String password = (String) context.getArgument("password", String.class);
            String server = currentServer();
            String username = currentUsername();
            if (server.isEmpty() || username.isEmpty()) {
                this.print("messages.loginNoServer");
                return 0;
            }
            boolean added = this.accounts.put(username, server, password);
            this.print(added ? "messages.loginAccountAdded" : "messages.loginAccountUpdated", server, username);
            return 1;
        });
        root.then(Command.literal("add").then(addPassword));

        ArgumentBuilder removeServer = Command.argument("server", StringArgumentType.word());
        removeServer.executes(context -> {
            String server = (String) context.getArgument("server", String.class);
            boolean removed = this.accounts.removeByServer(server);
            this.print(removed ? "messages.loginAccountRemoved" : "messages.loginAccountMissing", server);
            return removed ? 1 : 0;
        });
        root.then(Command.literal("remove").executes(context -> {
            String server = currentServer();
            if (server.isEmpty()) {
                this.print("messages.loginNoServer");
                return 0;
            }
            boolean removed = this.accounts.removeByServer(server);
            this.print(removed ? "messages.loginAccountRemoved" : "messages.loginAccountMissing", server);
            return removed ? 1 : 0;
        }).then(removeServer));

        root.then(Command.literal("clear").executes(context -> {
            int count = this.accounts.size();
            this.accounts.clear();
            this.print("messages.loginAccountsCleared", count);
            return 1;
        }));
    }

    private void list() {
        if (this.accounts.size() == 0) {
            this.print("messages.loginNoAccounts");
            return;
        }
        this.print("messages.loginAccountHeader", this.accounts.size());
        for (LoginAccounts.Account account : this.accounts.all()) {
            this.print("messages.loginAccountRow", account.username(), account.server(),
                    mask(account.password()));
        }
    }

    /** 列表里不回显明文密码，只显示长度。 */
    private static String mask(String password) {
        if (password == null || password.isEmpty()) {
            return "-";
        }
        return "*".repeat(Math.min(password.length(), 16));
    }

    private void print(String key, Object... args) {
        String text = args.length == 0
                ? Translations.get("addons.aurora." + key)
                : String.format(java.util.Locale.ROOT, Translations.get("addons.aurora." + key), args);
        ChatUtils.printChat(Component.literal(text), ChatUtils.moduleSignature(this.module));
    }

    private static String currentUsername() {
        return ClientAccess.evernightMinecraft.getUser() == null ? "" : ClientAccess.evernightMinecraft.getUser().getName();
    }

    private static String currentServer() {
        ServerData data = ClientAccess.evernightMinecraft.getCurrentServer();
        return data == null ? "" : LoginAccounts.normalizeServer(data.ip);
    }
}
