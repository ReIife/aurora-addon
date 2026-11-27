---
name: meteor-to-evernight-port
description: 把 Meteor Client（彗星）的插件/模块移植成 Evernight 插件（Addon）的完整工作流：读上游源码 → 查重与命名 → 按 Meteor→Evernight API 映射改写 → 补语言表 → 构建验收。触发词：移植、搬运、port、Meteor、彗星、Meteor Client、meteor-client、MeteorAddon、把彗星模块搬过来、Evernight 插件移植、addon port。只要任务涉及「Meteor 的某个模块/插件要在 Evernight 里能跑」，先读本 skill。
---

# 从 Meteor（彗星）移植到 Evernight 插件

本仓库是 Evernight 的插件模板。本 skill 的产物是**本仓库里可构建的插件代码**：不搬 Meteor 的框架（ClickGUI、配置、包管理），只搬「行为」。

Meteor 与 Evernight 的模块体系同源（Evernight 的插件系统就是参考 Meteor 设计的），所以移植大多是机械改写；真正会翻车的是**事件映射、设置语义、命名冲突、语言表**这四块，本 skill 按这个顺序组织。

## 目录

- [1. 开工前先确认 3 件事](#1-开工前先确认-3-件事)
- [2. 先判定：本体是不是已经有了](#2-先判定本体是不是已经有了)
- [3. 判定行为是否被上游 mixin 消费](#3-判定行为是否被上游-mixin-消费)
- [4. 工程骨架与入口](#4-工程骨架与入口)
- [5. 移植一个模块（主流程）](#5-移植一个模块主流程)
- [6. 语言表（Meteor 没有，必须补）](#6-语言表meteor-没有必须补)
- [7. HUD 元素与指令](#7-hud-元素与指令)
- [8. 版本差异：1.0.1 到 1.0.3](#8-版本差异101-到-103)
- [9. 构建与验收](#9-构建与验收)
- [10. 排错表](#10-排错表)
- [11. 参考文件](#11-参考文件)

## 1. 开工前先确认 3 件事

1. **移植范围**：整个 Meteor 插件工程，还是其中某几个模块？工程级移植按 [§4](#4-工程骨架与入口) 建骨架，单模块移植直接从 [§5](#5-移植一个模块主流程) 开始。
2. **许可与署名**：Meteor 是 GPL-3.0，Evernight 也是 GPL-3.0，代码可以移植，但**必须保留 GPL-3.0 与上游作者署名**：源文件头写上 `Port of <类名> from Meteor Client (GPL-3.0, https://github.com/MeteorDevelopment/meteor-client)`，README 的鸣谢里列出上游作者。不要改成 MIT、也不要隐去来源。
3. **Evernight 版本**：本模板固定 `evernight_version=1.0.3`（`libs/evernight-1.0.3.jar`）。移植前先看 [§8](#8-版本差异101-到-103) 的版本差异；在旧模板（1.0.1）上工作时尤其要注意。需要核实某个 API 到底在不在，直接问 jar：

   ```bash
   javap -classpath libs/evernight-1.0.3.jar evernight.client.modules.ModuleCategory
   ```

## 2. 先判定：本体是不是已经有了

Evernight 本体已经覆盖了 Meteor 的绝大部分模块，而且**模块名是全局键**（`modules.json` 配置键、预设键、`;<Name>` 指令都由它生成）。同名会让 `AddonManager` 把**整个插件记成 FAILED**。

移植前做两步：

1. **查重名**：在 Evernight 本体源码里搜 `super("<Name>"`（`src/main/java/evernight/client/modules/**`），或游戏内 `;modules` / ClickGUI 里看。
2. **查同功能**：常见对应关系（不是全部）——

   | Meteor | Evernight 本体 |
   | --- | --- |
   | `KillAura` / `CrystalAura` / `Surround` | `Aura` / `AutoCrystal` / `Surround` |
   | `AutoEXP` / `AutoTotem` / `AutoArmor` | `AutoExp` / `AutoTotem`(HUD 之外) / `AutoArmor` |
   | `AntiPacketKick` / `BetterTab` / `Sprint` | `NoPacketKick` / `ExtraTab` / `Sprint` |
   | `Velocity` / `Step` / `NoSlow` / `NoFall` | 同名或近名模块都已存在 |
   | `HoleESP` / `Tracers` / `Trails` / `Xray` / `Zoom` | 同名模块已存在 |

   已有等价实现时不要重复移植：改用已有模块，或只把 Meteor 独有的那部分能力做成新模块，并在交付说明里写清「为什么不整模块搬」。

**命名规则**：Meteor 用 kebab-case（`item-highlight`、`message-aura`），Evernight 用 **CamelCase 稳定标识**（`ItemHighlight`、`MessageAura`）。确实是新模块且与本体重名时加插件前缀（`YourAddonKillAura`）。名字发布后不可改。

## 3. 判定行为是否被上游 mixin 消费

**最容易白干的一步**。Meteor 里很多模块只是「数据源」：模块类本身没有逻辑，行为写在 Meteor 自己的 `mixin/` 里，通过 `Modules.get().get(Xxx.class).shouldXxx()` 取值。**只搬模块类 = 移植了一个永远不生效的空壳。**

在 Meteor 源码里查谁引用了这个类：

```bash
# 排除模块目录自身，看还有谁在用
grep -rn "XxxModule\|Xxx.class" src/main/java --include=*.java | grep -v "systems/modules/"
```

- 命中 `mixin/`：移植时必须同时给出替代方案。优先找 Evernight 的等价事件（见 `references/event-map.md`）；没有就**在你自己的插件里写 mixin**（`fabric.mod.json` 声明 `mixins`，本体不会代管插件的 mixin），从 mixin 回调模块的静态/实例方法。
- 命中其它模块（如 `Sprint.rageSprint()` 被 `GUIMove` 调用）：说明它有跨模块契约。插件里不要复制这种耦合，改成模块内自洽逻辑，或在交付说明里明确「该联动未移植」。
- 命中工具类（`utils/`）：那是纯逻辑，按 `references/api-map.md` 换成本体工具即可。

## 4. 工程骨架与入口

```
src/main/java/com/example/your addon/
├── YourAddon.java            // extends EvernightAddon，只做一次性注册
├── modules/YourModule.java   // extends AddonModule
├── commands/YourCommand.java // extends Command
└── hud/YourHud.java          // extends HudWidgetModule
src/main/resources/
├── fabric.mod.json           // entrypoint 键必须是 evernight-addon
└── assets/<mod id>/lang/{en_us,zh_cn}.json
```

Meteor 插件入口 → Evernight 入口：

```java
// Meteor: 三个注册回调 + getPackage
public class MyAddon extends MeteorAddon {
    @Override public void onInitialize() { MeteorClient.EVENT_BUS.subscribe(this); }
    @Override public void onRegisterCategories() { Modules.registerCategory(MY_CATEGORY); }
    @Override public String getPackage() { return "com.example.myaddon"; }
}

// Evernight: 一个 onInitialize，全部走 AddonContext
public final class MyAddon extends EvernightAddon {
    @Override
    public void onInitialize(AddonContext context) {
        // 内置分类直接用：ModuleCategory.MISC / COMBAT / ... / HUD
        // 需要自己的分类时（1.0.3+）：注册一次，把返回的对象传给模块构造器
        ModuleCategory exampleCategory = context.registerCategory("Example");
        context.registerModule(new YourModule(exampleCategory));
        context.registerHudModule(new YourHud());
        context.registerCommand(new YourCommand());
        context.subscribe(this);   // 只给「非模块」的辅助对象用
    }
}
```

`fabric.mod.json` 里把 `meteor-client` 依赖换成本体：

```json
"entrypoints": { "evernight-addon": ["com.example.myaddon.MyAddon"] },
"depends": { "fabricloader": ">=0.20.1", "minecraft": "1.21.11", "java": ">=21", "evernight": ">=1.0.3" }
```

**不要**给模块调 `context.subscribe(module)`：模块 `enable()` 已经自动订阅，重复订阅会一次事件触发两遍。

## 5. 移植一个模块（主流程）

按顺序做，每步都能独立检查。

### 5.1 类与构造器

```java
// Meteor
public class MessageAura extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    public MessageAura() { super(Categories.Misc, "message-aura", "Sends a message ..."); }
}

// Evernight
public final class MessageAuraModule extends AddonModule {
    public MessageAuraModule(ModuleCategory category) {
        super("youraddon", "MessageAura", "Sends a message ...", category);
        // 设置在这里 this.add(...)
    }
}
```

- 第二参 `"MessageAura"` 是全局稳定键（Meteor 的 `name` 转 CamelCase）；Meteor 的 `title` 由 `Utils.nameToTitle` 生成，Evernight 没有这个机制，标题只来自语言表。
- 构造器的 `description` 在 1.0.3 只是**源码兼容参数**，不参与显示：描述以语言表为准，缺键时界面显示完整语言键。两份语言表都要写。

### 5.2 设置

| Meteor | Evernight 1.0.3 |
| --- | --- |
| `settings.getDefaultGroup()` / `settings.createGroup("X")` | 平铺 `this.add(...)`；组用 `markAsGroup()`，子项 `.dependsOn(group)` |
| `Setting<T>.get()` | `Setting<T>.getValue()` |
| `new BoolSetting.Builder().name("x").defaultValue(true).build()` | `new BooleanSetting("X", true)` |
| `new IntSetting.Builder().name("x").defaultValue(100).range(0,1000)` | `new NumberSetting<>("X", 100, 0, 1000)` |
| `DoubleSetting` | `new NumberSetting<>("X", 1.0, 0.0, 10.0).withUnit("s")` |
| `EnumSetting<Mode>` | `new EnumSetting<>("Mode", Mode.A)`，枚举必须 `implements NamedEntry`（见 5.3） |
| `StringSetting` | `new StringSetting("Message", "hi")` |
| `ColorSetting`（`SettingColor`） | `new ColorSetting("Color", new java.awt.Color(225, 25, 255, 50))`，alpha 0..255 |
| `BlockListSetting` / `ItemListSetting` / `EntityTypeListSetting` | `new RegistrySetSetting<>("Blocks", BuiltInRegistries.BLOCK, Blocks.OBSIDIAN)` 等 |
| `StringListSetting` | 发行 jar 里**没有**（1.0.1 / 1.0.3 都没有），用 `new StringSetSetting("Names", "a", "b")`（无序） |
| `KeybindSetting` | 无设置级键位：用模块级 `setKeybind(Keybind.toggle(code, false))`，或监听 `InputPressEvent` |
| `.visible(() -> a.get())` | `.dependsOn(this.aSetting)` 或 `.addVisibilityPredicate(v -> ...)` |
| `.onChanged(v -> ...)` | `.setChangeCallback(() -> ...)`（无参） |
| `.description("...")` | **删掉**：插件不能注入设置 tooltip，别硬塞 `evernight.descriptions.*` |

写的时候注意：`dependsOn` / `withUnit` 这些链式方法返回 `Setting<T>`，所以**先声明 `Setting<X>` 变量再 `this.add(...)`**，不要声明成 `NumberSetting`。

```java
Setting<Boolean> strict = this.add(new BooleanSetting("Strict", false));
Setting<Integer> range = this.add(new NumberSetting<>("Range", 32, 1, 128).dependsOn(strict));
```

### 5.3 枚举设置必须本地化

Meteor 的 `EnumSetting<T extends Enum<?>>` 直接拿 `toString()` 当显示名和序列化值；Evernight 要求 `Enum<T> & NamedEntry`，`getName()` 是**稳定英文标识**（写进配置），显示名单独查语言表：

```java
public enum SortMode implements NamedEntry {
    Distance("Distance"),
    Health("Health");

    private final String name;

    SortMode(String name) { this.name = name; }

    @Override public String getName() { return this.name; }

    // 插件不能落在 evernight.names.values.*，必须用自己的命名空间
    @Override public String getDisplayName() {
        return Translations.get("addons.youraddon.names.values." + this.name);
    }
}
```

`getName()` 发布后不可改（改了就丢玩家配置）。两份语言表都要有 `addons.<id>.names.values.Distance`。

### 5.4 生命周期、字段与文本

| Meteor | Evernight |
| --- | --- |
| `mc`（父类字段） | `ClientAccess.evernightMinecraft`，或 `implements ClientAccess` 直接用 |
| `isActive()` | `isToggled()` |
| `toggle()` / `enable()` / `disable()` | 同名 |
| `onActivate()` / `onDeactivate()` | `onEnable()` / `onDisable()`（`onToggle()` 在两者之间都会触发） |
| `getInfoString()` | `getInfo()`（返回 `null` 表示不显示） |
| `info(...)` / `warning(...)` / `error(...)` | `ChatUtils.printChat(component, ChatUtils.moduleSignature(this))` |
| `keybind`（`Keybind`） | `getKeybind()` / `setKeybind(Keybind.toggle(code, mouse))` |
| `serialize = false` | 没有等价开关；确实不想落盘就 `setExcludedFromConfig(true)` 逐项处理 |
| `runInMainMenu` / `autoSubscribe` / `toggleOnBindRelease` | 无等价：不要照抄，改成事件里判 `isWorldUnavailable()` |

`@EventHandler` 换包：`meteordevelopment.orbit.EventHandler` → `evernight.client.events.core.EventHandler`。签名规则相同（`void`、恰好一个非基本类型参数、`priority` 越大越先），模块启用期间才被调用。

### 5.5 事件

按 `references/event-map.md` 一一对照。三档处理：

1. **有等价事件** → 换类名与取值方法即可。
2. **语义接近但时机不同** → 换事件后在处理器里补判断（例如 Meteor `TickEvent.Pre` 是客户端 tick 开头，Evernight 用 `MinecraftTickEvent`；玩家相关逻辑用 `LocalPlayerTickEvent` / `LocalPlayerPreTickEvent`）。
3. **没有等价** → 不要硬找。在 tick 里轮询 + diff 上一帧状态；确实需要注入点的，在插件里自带 mixin（见 §3）。

### 5.6 工具与渲染

Meteor 的 `InvUtils` / `PlayerUtils` / `Rotations` / `BlockUtils` / `Renderer3D` 在 Evernight 都有对应物，但类名和方法名不同，逐条见 `references/api-map.md`。三个最容易写错的：

- `InvUtils.findInHotbar(x)` → `InventoryUtils.findHotbarSlot(x)`；`InvUtils.swap(slot, false)` → `InventoryUtils.selectHotbarSlot(slot)`。
- `Rotations.rotate(yaw, pitch, priority, action)` → `ClientContext.rotationManager.requestRotation(yaw, pitch, priority, true)` 再 `executeAction(action)`。
- `Renderer3D` 的方框/线段 → `WorldBoxRenderer.fillBox / outlineBox`、`LineMeshRenderer`，绘制入口是 `WorldRenderEvent.Main`。

### 5.7 配置

Meteor 的 `Module.toTag/fromTag`（NBT）在 Evernight 里是自动的：模块开关、键位、设置自动进 `evernight/modules.json`，HUD 进 `evernight/hud.json`。**不要**手写序列化。插件自己的状态才需要 `JsonConfigurable` + `context.registerConfig(...)`。

## 6. 语言表（Meteor 没有，必须补）

Evernight 的硬规则：**玩家可见文本只存在于语言表**，Java 里只写键。Meteor 把英文标题硬编码在构造器和 `.description(...)` 里，移植时要把它们搬进 `assets/<id>/lang/{en_us,zh_cn}.json`：

| 内容 | 键 |
| --- | --- |
| 模块显示名 | `addons.<id>.names.modules.<ModuleName>` |
| 模块描述 | `addons.<id>.descriptions.modules.<ModuleName>` |
| 设置标签 | `addons.<id>.names.settings.<SettingName>`（`AddonModule.add` 自动拼，取的是 `setting.getName()`） |
| 枚举值（5.3） | `addons.<id>.names.values.<EnumName>` |
| 指令/聊天/运行时文案 | `addons.<id>.<自定义键>`，代码里 `Translations.get("addons.<id>.<键>")` |
| 插件分类 | `addons.<id>.names.categories.<Name>` |

规则：

- **禁止**使用 `evernight.` 前缀（本体保留命名空间）；插件所有键都在 `addons.<id>.*`。
- `en_us.json` 与 `zh_cn.json` **键集必须一致**；查找链是「当前语言 → 英文 → 完整键」，两份都缺时界面会显示完整的 `addons.<id>.*` 键——能立刻看出漏翻，但绝不能带到发布版。
- 设置名从 Meteor 的 kebab 改成 CamelCase（`ignore-friends` → `IgnoreFriends`），语言键跟着改。
- 改完执行 `python scripts/check-lang.py`，必须 0 error。

## 7. HUD 元素与指令

### HUD

Meteor 的 `HudElement` 是「动态加进 `Hud` 的独立对象」，Evernight 的 HUD 组件就是**模块**：`extends HudWidgetModule`，构造器里 `setRenderer(...)`，用 `context.registerHudModule(...)` 注册。

| Meteor | Evernight |
| --- | --- |
| `HudElementInfo` + `Hud.get().add(info)` | `context.registerHudModule(new YourHud())` |
| `setSize(w, h)` | 覆写 `calculateDimensions()` 返回 `float[]{w, h}` |
| `render(HudRenderer)` + `renderer.text(...)` | 覆写 `renderContent(PoseStack)` + `FontRenderer.INSTANCE.drawWithShadow(...)` |
| `settings.getDefaultGroup()` | `this.add(...)` |
| `box.xAnchor` / `autoAnchors` | `getRenderer().setAnchor(HudAnchor.TOP_LEFT)` 或玩家在 HUD 编辑器里拖 |
| `isInEditor()` | `isInHudEditor()` |

纯文本组件最省事：`new HudTextListRenderer(this, new HudTextRow(() -> Component.literal(...), () -> true))`；自绘组件用 `ResizableHudWidgetRenderer`。颜色用 `this.getHudColor(...)`，不要写死。

注意：`HudWidgetModule` **不是** `AddonModule`，默认显示名查的是本体命名空间 `evernight.names.modules.*`（插件不能占用）。插件 HUD 组件必须覆写 `getDisplayName()` / `getDescription()`，改成读自己的 `addons.<id>.*` 键。

### 指令

| Meteor | Evernight |
| --- | --- |
| `super("name", "description", "alias")` | `super("name")` + `setAliases("alias")` |
| `build(LiteralArgumentBuilder<ClientSuggestionProvider>)` | `exec(LiteralArgumentBuilder)` |
| `argument(name, type)` / `literal(name)` | 同名静态辅助方法 |
| `Config.get().prefix` | `CommandRegistry.getPrefix()` |
| `info(...)` / `error(...)` | `ChatUtils.printChat(...)` |

指令名不要与模块名/别名重复——`registerModule` 会为模块自动注册同名指令。

## 8. 版本差异：1.0.1 到 1.0.3

模板当前编译的是 **1.0.3**。如果你在旧模板（`evernight_version=1.0.1`）或别的插件工程里移植，先确认目标版本再动代码：

| 关注点 | 1.0.1（旧模板） | 1.0.3（当前模板） |
| --- | --- | --- |
| `ModuleCategory` | `enum`，8 个常量 | `final class`：`COMBAT/MISC/RENDER/MOVEMENT/PLAYER/AUTO/EXPLOIT/CLIENT/HUD`，另有 `values()`、`findByName`、`ordinal()` |
| 插件分类 | 不支持，只能用内置常量 | `context.registerCategory("X")` 返回 `ModuleCategory`；显示名 `addons.<id>.names.categories.<Name>` |
| `AddonModule.getDescription()` | 语言表缺键时回退构造器 `description` | 缺键显示完整语言键；构造器 `description` 仅为源码兼容 |
| 语言查找 | 当前语言 → 英文 → 兜底 | 当前语言 → 英文 → 完整键 |
| `StringListSetting` | 发行 jar 没有 | 发行 jar 仍然没有（用 `StringSetSetting`） |

两张表都基于 **发行 jar** 核对，本体仓库源码可能比发行 jar 新：以 `libs/evernight-<version>.jar` 为准。**先查再写**：

```bash
javap -classpath libs/evernight-<version>.jar evernight.client.modules.ModuleCategory
javap -classpath libs/evernight-<version>.jar evernight.addon.AddonContext
```

不确定的 API 不要凭记忆写，也不要照抄本 skill 里带版本号的结论。

## 9. 构建与验收

```bash
gradlew.bat build                # 编译 + 打 jar
python scripts/check-lang.py     # 语言表键集/前缀校验，0 error 才算过
```

验收清单：

1. 模块出现在 ClickGUI 的对应分类（插件分类排在内置之后），开关能保持到 `modules.json`。
2. 每个可见文本都有中英两份键。
3. 交付说明里逐条列出：**移植了什么、重命名了什么、删掉了什么、哪些上游行为依赖 Meteor 内部 mixin 而没移植**。不要静默丢功能。
4. 在代码里给没移植的部分留 `// TODO(port): ...`，让下一个人知道缺口在哪。
5. **不要启动游戏、不要声称验证过游戏内观感**；构建与静态检查到此为止，行为由人验收（模板 `AGENTS.md` 第 6 条）。

## 10. 排错表

| 现象 | 原因 / 处理 |
| --- | --- |
| 插件整包 `FAILED`，日志 `Module name is already registered` | 模块名与本体/其他插件冲突；改名（§2） |
| 编译报 `cannot find symbol: get()` | 设置取值是 `getValue()`，不是 `get()` |
| 编译报 `EnumSetting<...> cannot be applied` | 枚举没 `implements NamedEntry`（§5.3） |
| 编译报 `incompatible types: Setting<X> vs NumberSetting` | 链式方法返回 `Setting<T>`，先声明 `Setting<X>` 再 `this.add(...)` |
| 模块能开但没反应 | 行为在被消费的 mixin 里，没移植（§3）；或事件选错（§5.5） |
| 设置改了不落盘 | 设置没走 `this.add(...)`，或 `setExcludedFromConfig(true)` |
| 中文界面显示英文/完整键 | 语言表缺键或键名与 `setting.getName()` 不一致（§6） |
| HUD 组件不显示 | 没 `setRenderer(...)`，或没走 `registerHudModule` |
| `NoSuchMethodError` / `NoClassDefFoundError` | 运行环境 Evernight 版本低于 `depends.evernight`，或按更高版本写了代码（§8） |

## 11. 参考文件

| 文件 | 内容 |
| --- | --- |
| `references/api-map.md` | Meteor → Evernight 逐项 API 映射：入口、模块、设置、HUD、指令、工具、渲染、颜色、配置 |
| `references/event-map.md` | 事件对照表 + 无等价事件时的替代策略 |
| `references/worked-example.md` | 完整移植范例（MessageAura）+ HUD/指令/自写 mixin 的改写骨架 |
| `.github/skills/evernight-addon-dev/SKILL.md` | 本仓库的插件开发约定（入口、注册、构建） |
| 上游 `docs/addons.md` | Evernight 官方插件文档（版本可能比模板新，注意 §8） |
