# 完整移植范例：MessageAura

选它的原因：规模小、有设置、有事件、有聊天输出，覆盖了移植里最常见的四类改写。照这个顺序做，其它模块同理。

## 1. 上游源码（Meteor）

```java
// meteordevelopment.meteorclient.systems.modules.misc.MessageAura
public class MessageAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> message = sgGeneral.add(new StringSetting.Builder()
        .name("message")
        .description("The specified message sent to the player.")
        .defaultValue("Meteor on Crack!")
        .build()
    );

    private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Will not send any messages to people friended.")
        .defaultValue(false)
        .build()
    );

    public MessageAura() {
        super(Categories.Misc, "message-aura", "Sends a specified message to any player that enters render distance.");
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (!(event.entity instanceof Player) || event.entity.getUUID().equals(mc.player.getUUID())) return;

        if (!ignoreFriends.get() || (ignoreFriends.get() && !Friends.get().isFriend((Player) event.entity))) {
            ChatUtils.sendPlayerMsg("/msg " + event.entity.getName().getString() + " " + message.get());
        }
    }
}
```

## 2. 移植前的判定

| 检查 | 结论 |
| --- | --- |
| Evernight 已有同功能模块？ | 没有（`;modules` / 本体源码都没有 message-aura） |
| 模块名冲突？ | `MessageAura` 未被占用 |
| 被上游 mixin 消费？ | 没有；`MessageAura` 只在 `Modules.initMisc()` 里注册，行为自包含 |
| 依赖的 Meteor 内部 | `Friends`（好友判定）、`ChatUtils.sendPlayerMsg` |

## 3. Evernight 代码

`src/main/java/com/example/exampleaddon/modules/MessageAuraModule.java`

```java
package com.example.exampleaddon.modules;

import evernight.addon.AddonModule;
import evernight.client.ClientAccess;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.world.ClientLevelAddEntityEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.StringSetting;
import evernight.client.utils.chat.ChatUtils;
import net.minecraft.world.entity.player.Player;

/**
 * Port of MessageAura from Meteor Client (GPL-3.0,
 * https://github.com/MeteorDevelopment/meteor-client).
 */
public final class MessageAuraModule extends AddonModule {
    private final Setting<String> messageSetting;
    private final Setting<Boolean> ignoreFriendsSetting;

    public MessageAuraModule(ModuleCategory category) {
        super("exampleaddon", "MessageAura",
                "Sends a message to any player that enters render distance.", category);
        this.messageSetting = this.add(new StringSetting("Message", "Evernight on top!"));
        this.ignoreFriendsSetting = this.add(new BooleanSetting("IgnoreFriends", false));
    }

    @EventHandler
    private void onEntityAdded(ClientLevelAddEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (ClientAccess.isLocalPlayer(player)) {
            return;
        }
        if (!this.ignoreFriendsSetting.getValue()
                && ClientContext.socialRegistry.isFriendPlayer(player)) {
            return;
        }
        ChatUtils.sendChatOrCommand("/msg " + player.getName().getString() + " "
                + this.messageSetting.getValue());
    }
}
```

逐项对照：

| Meteor | Evernight | 说明 |
| --- | --- | --- |
| `extends Module` | `extends AddonModule` | 需要语言表本地化就用 `AddonModule` |
| `super(Categories.Misc, "message-aura", "...")` | `super("exampleaddon", "MessageAura", "...", category)` | 名字转 CamelCase；分类由调用方注入 |
| `settings.getDefaultGroup()` | 直接 `this.add(...)` | 没有分组概念，一个默认列表 |
| `.name("message")` + `.defaultValue(...)` | `new StringSetting("Message", "...")` | 名字转 CamelCase，它同时是语言键后缀与配置键 |
| `.description(...)` | 删除 | 插件不注入设置 tooltip |
| `ignoreFriends.get()` | `ignoreFriendsSetting.getValue()` | 取值方法不同 |
| `EntityAddedEvent` + `event.entity` | `ClientLevelAddEntityEvent` + `getEntity()` | 见 `event-map.md` |
| `mc.player` / UUID 比较 | `ClientAccess.isLocalPlayer(player)` | 少一次判空 |
| `Friends.get().isFriend(player)` | `ClientContext.socialRegistry.isFriendPlayer(player)` | 好友系统对应物 |
| `ChatUtils.sendPlayerMsg("/msg ...")` | `ChatUtils.sendChatOrCommand("/msg ...")` | 走客户端连接发送 |

## 4. 语言表

`src/main/resources/assets/exampleaddon/lang/en_us.json`

```json
{
  "addons.exampleaddon.names.categories.Example": "Example",
  "addons.exampleaddon.names.modules.MessageAura": "Message Aura",
  "addons.exampleaddon.descriptions.modules.MessageAura": "Sends a message to any player that enters render distance.",
  "addons.exampleaddon.names.settings.Message": "Message",
  "addons.exampleaddon.names.settings.IgnoreFriends": "Ignore Friends"
}
```

`zh_cn.json` 键集必须完全一致：

```json
{
  "addons.exampleaddon.names.categories.Example": "示例",
  "addons.exampleaddon.names.modules.MessageAura": "消息光环",
  "addons.exampleaddon.descriptions.modules.MessageAura": "对进入视距的玩家发送一条消息。",
  "addons.exampleaddon.names.settings.Message": "消息内容",
  "addons.exampleaddon.names.settings.IgnoreFriends": "忽略好友"
}
```

键名对应关系：`names.modules.<Name>` 用的是构造器第二参；`names.settings.<SettingName>` 用的是设置对象的名字（`AddonModule#add` 自动拼），**不是** `configName`。查找链是「当前语言 → 英文 → 完整键」：两份都缺时界面直接显示 `addons.exampleaddon.*` 这样的完整键——这能帮你发现漏翻，但也绝不能带进发布版。

## 5. 注册

```java
@Override
public void onInitialize(AddonContext context) {
    ModuleCategory exampleCategory = context.registerCategory("Example");   // 1.0.3+
    context.registerModule(new com.example.exampleaddon.modules.MessageAuraModule(exampleCategory));
}
```

注册成功后会多出一条 `;messageaura` 开关指令（由模块名生成），不需要另写指令。分类名是全局唯一标识，发布后不要改；显示名要写两份语言表的 `addons.<id>.names.categories.Example`。沿用内置分类时（例如 `ModuleCategory.MISC`）删掉 `registerCategory` 那行即可。

## 6. 验证

```bash
gradlew.bat build
python scripts/check-lang.py
```

到这里只证明「能编译、语言键齐全」。游戏内是否真的在玩家进视距时发消息、好友过滤是否符合预期，由人验收——不要在交付说明里写成已验证。

---

## 7. 附：HUD 元素移植骨架

Meteor 的 `HudElement` 在 Evernight 是模块：

```java
public final class YourHudModule extends HudWidgetModule {
    private final Setting<Boolean> shadowSetting;

    public YourHudModule() {
        super("YourHud");                    // 分类固定 HUD，不用传
        this.shadowSetting = this.add(new BooleanSetting("Shadow", true));
        this.setRenderer(new HudTextListRenderer(this,
                new HudTextRow(this::buildText, () -> !this.isWorldUnavailable())));
    }

    // HudWidgetModule 不是 AddonModule：默认显示名查的是本体的 evernight.names.modules.*，
    // 插件不能占用那个命名空间，所以这里必须覆写。
    @Override
    public String getDisplayName() {
        return Translations.get("addons.exampleaddon.names.modules.YourHud");
    }

    @Override
    public String getDescription() {
        return Translations.get("addons.exampleaddon.descriptions.modules.YourHud");
    }

    private Component buildText() {
        Player player = ClientAccess.evernightMinecraft.player;
        return player == null ? Component.empty() : Component.literal(player.getName().getString());
    }
}
```

- `HudTextRow(Supplier<Component>, Supplier<Boolean>)`：第二个参数是「是否显示」，用来代替 Meteor 里 `visible` 判断。
- 需要自绘（进度条、图表）时改用 `ResizableHudWidgetRenderer` 并覆写 `calculateDimensions()` / `renderContent(PoseStack)`。
- 注册用 `context.registerHudModule(new YourHudModule())`，走 `hud.json` 持久化。

## 8. 附：指令移植骨架

```java
public final class YourCommand extends Command {
    public YourCommand() {
        super("yourcommand");
        this.setAliases("yc");
    }

    @Override
    public void exec(LiteralArgumentBuilder builder) {
        builder.executes(context -> {
            ChatUtils.printChat(
                    Component.literal(Translations.get("addons.exampleaddon.command.done")),
                    ChatUtils.DEFAULT_SIGNATURE);
            return 1;
        });
    }
}
```

Meteor 的 `build(LiteralArgumentBuilder<ClientSuggestionProvider>)` 改成 `exec(LiteralArgumentBuilder)`；参数用 `Command.argument(...)` 构造，取值仍是 Brigadier 的 `context.getArgument(...)`。

## 9. 附：被 mixin 消费的模块怎么移植

以 Meteor `ItemHighlight` 为例：模块只暴露 `getColor(ItemStack)`，真正的染色在 Meteor 的 GUI mixin 里。移植步骤：

1. 在上游找到消费者：`grep -rn "ItemHighlight" src/main/java --include=*.java | grep -v systems/modules`。
2. 判断 Evernight 有没有对应事件：GUI 渲染有 `AbstractContainerScreenEvent`（`screen` / `graphics` / `poseStack` / `mouseX` / `mouseY`），能在事件里画高亮，不必写 mixin。
3. 没有对应事件才写插件自己的 mixin（`event-map.md` §6.2），并在 mixin 里现取模块、判空、判 `isToggled()`。
4. 在交付说明里写清楚「原来靠 mixin 的哪一段，现在靠事件/自己的 mixin」。漏了这一步，模块会「能开关但没效果」。
