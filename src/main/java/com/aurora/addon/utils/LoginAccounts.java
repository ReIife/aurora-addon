/*
 * New file in this port: auto-login account storage.
 * Behaviour ported from LeavesHack (AGPL-3.0, MrBZBZ) — `modules/autoLogin/*`.
 * This file is distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import evernight.client.settings.core.JsonConfigurable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 自动登录账号表，落在 {@code evernight/aurora-autologin.json}。
 *
 * <p>上游用 Meteor 的 {@code System<AutoLoginAccounts>} + NBT 存盘；Evernight 的等价物是
 * {@link JsonConfigurable}，在入口 {@code context.registerConfig(...)} 注册后由本体读写。
 *
 * <p>一条记录 = 用户名 + 服务器地址 + 密码。服务器地址统一规范化（去掉端口、小写），
 * 这样 {@code mc.example.com:25565} 与 {@code MC.Example.com} 会命中同一条。
 */
public final class LoginAccounts implements JsonConfigurable {
    public record Account(String username, String server, String password) {
    }

    private final List<Account> accounts = new ArrayList<>();

    @Override
    public String getConfigName() {
        return "aurora-autologin.json";
    }

    @Override
    public JsonElement toJson() {
        JsonObject root = new JsonObject();
        JsonArray list = new JsonArray();
        for (Account account : this.accounts) {
            JsonObject entry = new JsonObject();
            entry.addProperty("username", account.username());
            entry.addProperty("server", account.server());
            entry.addProperty("password", account.password());
            list.add(entry);
        }
        root.add("accounts", list);
        return root;
    }

    @Override
    public void fromJson(JsonElement json) {
        this.accounts.clear();
        if (json == null || !json.isJsonObject()) {
            return;
        }
        JsonElement listElement = json.getAsJsonObject().get("accounts");
        if (listElement == null || !listElement.isJsonArray()) {
            return;
        }
        for (JsonElement element : listElement.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String username = readString(entry, "username");
            String server = readString(entry, "server");
            String password = readString(entry, "password");
            if (!username.isEmpty() && !server.isEmpty()) {
                this.accounts.add(new Account(username, server, password));
            }
        }
    }

    public List<Account> all() {
        return List.copyOf(this.accounts);
    }

    public int size() {
        return this.accounts.size();
    }

    public Optional<Account> find(String username, String server) {
        String normalizedServer = normalizeServer(server);
        for (Account account : this.accounts) {
            if (account.username().equalsIgnoreCase(username) && account.server().equals(normalizedServer)) {
                return Optional.of(account);
            }
        }
        return Optional.empty();
    }

    /** 有则改密码，无则新增。返回 {@code true} 表示新增。 */
    public boolean put(String username, String server, String password) {
        String normalizedServer = normalizeServer(server);
        for (int i = 0; i < this.accounts.size(); i++) {
            Account account = this.accounts.get(i);
            if (account.username().equalsIgnoreCase(username) && account.server().equals(normalizedServer)) {
                this.accounts.set(i, new Account(account.username(), normalizedServer, password));
                return false;
            }
        }
        this.accounts.add(new Account(username, normalizedServer, password));
        return true;
    }

    public boolean removeByServer(String server) {
        String normalizedServer = normalizeServer(server);
        return this.accounts.removeIf(account -> account.server().equals(normalizedServer));
    }

    public void clear() {
        this.accounts.clear();
    }

    /** 去掉端口、统一小写；空值原样返回空串。 */
    public static String normalizeServer(String server) {
        if (server == null) {
            return "";
        }
        String value = server.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return "";
        }
        int colon = value.lastIndexOf(':');
        if (colon > 0 && !value.contains("]")) {
            value = value.substring(0, colon);
        }
        return value;
    }

    private static String readString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }
}
