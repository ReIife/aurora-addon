---
name: evernight-addon-dev
description: 在 Evernight 插件模板仓库中开发插件（Addon）的完整工作流：EvernightAddon 入口、AddonContext 注册模块/指令/配置/语言、依赖发行 jar 构建与联调、排错。触发词：Evernight 插件、Addon、evernight-addon、EvernightAddon、AddonContext、插件模板。
---

# Evernight 插件开发（模板仓库版）

本仓库是 [Evernight](https://github.com/Evernight-Client/Evernight) 的插件模板：`src/main/java/com/example/exampleaddon/` 是一份可以直接构建、加载的示例插件，把 `exampleaddon` / `com.example.exampleaddon` 换成自己的标识即可开工。

本 skill 记录**写插件时要遵守什么、调哪个 API、怎么验收**。Evernight 本体代码不在本仓库，API 定义以上游 [src/main/java/evernight/addon](https://github.com/Evernight-Client/Evernight/tree/main/src/main/java/evernight/addon) 与 [docs/addons.md](https://github.com/Evernight-Client/Evernight/blob/main/docs/addons.md) 为准。

## 目录

- [1. 先读什么](#1-先读什么)
- [2. 五条硬规则](#2-五条硬规则)
- [3. 工程结构与依赖](#3-工程结构与依赖)
- [4. AddonContext 速查](#4-addoncontext-速查)
- [5. 模块 / 设置 / 事件](#5-模块--设置--事件)
- [6. 指令 / 语言 / 配置](#6-指令--语言--配置)
- [7. 构建与联调](#7-构建与联调)
- [8. 排错](#8-排错)
- [9. 提交前检查](#9-提交前检查)

## 1. 先读什么

| 要做的事 | 读 |
| --- | --- |
| 从 Meteor（彗星）移植模块或插件 | `.github/skills/meteor-to-evernight-port/SKILL.md` |
| 示例文件怎么组织、每个类负责什么 | `references/example-addon.md`，以及仓库里真实的 `src/main/java/com/example/exampleaddon/` |
| AddonContext / EvernightAddon 精确签名 | `references/api.md` |
| 模块基类、设置类型、HUD 组件 | 上游 `docs/addons.md` 与上游 README |
| 语言表规则与校验 | 本 skill §6、`scripts/check-lang.py` |
| 依赖与构建 | 本 skill §3、`build.gradle` |

## 2. 五条硬规则

1. **入口键只能是 `evernight-addon`**，入口类是 `evernight.addon.EvernightAddon` 的子类，`fabric.mod.json` 的 `environment` 必须是 `client`。
2. **模块名全局唯一**：`new Module("Name", ...)` 的 `Name` 同时是配置键、预设键、指令别名来源，发布后不能改名。重名会让整个插件初始化失败。
3. **玩家可见文本走语言表，键只用 `addons.<mod id>.*`**：`assets/<mod id>/lang/en_us.json` 与 `zh_cn.json` 键集一致；**不要用 `evernight.` 开头**，那是本体保留命名空间。
4. **只注册、不硬塞**：模块、指令、配置、语言都通过 `AddonContext` 注册；不要直接操作 `ModuleRegistry.entries`、`CommandDispatcher` 内部结构。
5. **初始化后不改结构**：`onInitialize` 负责一次性注册；运行期动态增删模块/指令不受支持（配置与设置值可以随时改）。

## 3. 工程结构与依赖

```
src/main/java/com/example/exampleaddon/
├── ExampleAddon.java    入口：注册模块、配置、指令
├── ExampleModule.java   模块：开关 + 设置 + @EventHandler
├── ExampleCommand.java  指令：;exampleaddon / ;exaddon
└── ExampleConfig.java   独立配置：evernight/exampleaddon.json

src/main/resources/
├── fabric.mod.json
└── assets/exampleaddon/lang/{en_us,zh_cn}.json
```

`build.gradle` 用 `modImplementation files(libs/evernight-<evernight_version>.jar)` 链接 Evernight：首次构建会在配置阶段从 Evernight 的 GitHub Release 下载对应的发行 jar 到 `libs/`，然后由 Loom 把 intermediary 重映射成开发用的 named 命名空间。

**必须用发行 jar**：`build/devlibs/evernight-<version>-dev.jar` 的 access widener 头是 `named`，交给 `mod*` 配置会被 Loom 按 intermediary 处理，构建报 `Namespace mismatch, expected intermediary got named`。

Loom 1.18.4 要求 Gradle 跑在 **JDK 25** 上；`options.release = 21` 让产物仍是 Java 21 字节码。

## 4. AddonContext 速查

```java
AddonMetadata metadata()                 // id / name / version / authors / website
String id()
Logger logger()                          // 前缀 Evernight/Addon/<id>
EventBus eventBus()
<T extends Module> T registerModule(T)   // 同时生成 ;<alias> 指令
<T extends HudWidgetModule> T registerHudModule(T)
ModuleCategory registerCategory(String name) // 注册插件分类，传给 AddonModule 构造器
<T extends Command> T registerCommand(T)
void registerConfig(JsonConfigurable)    // 额外 evernight/<name>.json
void subscribe(Object) / unsubscribe(Object)
void registerLanguage(String code, InputStream)
void loadLanguageFiles()                 // 初始化时已自动调用
Path dataDirectory()                     // evernight/addons/<id>/
```

模块继承 `AddonModule` 时：`super(addonId, name, description, category, aliases...)`，语言键自动落到 `addons.<addonId>.*`。

注册失败抛 `IllegalStateException`；`AddonManager` 把该插件记为 `FAILED`，不会影响其他插件。

## 5. 模块 / 设置 / 事件

```java
public final class ExampleModule extends AddonModule {
    private final Setting<Boolean> announceSetting;

    public ExampleModule() {
        super("exampleaddon", "ExampleTicker", "Prints a chat message on an interval.",
                ModuleCategory.MISC);
        this.announceSetting = this.add(new BooleanSetting("Announce", true));
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) { /* 只在启用时触发 */ }
}
```

- `AddonModule` 自动解析 `addons.<id>.names.modules.*`、`addons.<id>.descriptions.modules.*` 与 `addons.<id>.names.settings.*`；查找链为「当前语言 → 英文 → 完整键」，中文缺键回退英文，两份都缺才显示完整键。构造器的 `description` 参数仅为源码兼容保留，不再作为显示兜底。不需要本地化时可直接继承 `Module`。
- 分类：内置分类（`ModuleCategory.COMBAT` … `HUD`）可直接复用；需要新分类时在 `onInitialize` 调用 `context.registerCategory("Example")`，把返回的 `ModuleCategory` 传给 `AddonModule` 构造器。分类只能在 `onInitialize` 注册，名字全局唯一（含内置分类、大小写不敏感），发布后不要改名；显示名写入两份语言表的 `addons.<id>.names.categories.<Name>`。
- 设置链式方法返回 `Setting<T>`；先建变量再 `this.add(...)`，不要声明成 `NumberSetting`。
- HUD 组件继承 `HudWidgetModule`、构造器里 `setRenderer(...)`，再用 `registerHudModule`。
- 辅助对象订阅事件用 `context.subscribe(obj)`，关闭时自动退订；模块不要重复订阅。

## 6. 指令 / 语言 / 配置

- 指令继承 `Command`，`exec(LiteralArgumentBuilder root)` 里 `root.executes(action)`；别名用 `setAliases(...)`。指令名不要和模块名/模块别名重复（模块会自动注册同名指令）。
- 语言：`assets/<id>/lang/en_us.json` + `zh_cn.json`，所有键放 `addons.<id>.*`（**禁止** `evernight.` 前缀）；`AddonModule` 自动读取模块名/描述/设置标签，私有文案用 `Translations.get("addons.<id>.key")`。查找链为当前语言 → 英文 → 完整键：可以只提供 `en_us.json` 让中文界面回退英文，但推荐两份都写；两份都缺会显示完整键。
- 改完语言表执行 `python scripts/check-lang.py`：校验 JSON、键集一致性、键前缀与占位符数量。
- 模块状态自动进 `modules.json`，HUD 进 `hud.json`；额外状态实现 `JsonConfigurable` 后 `context.registerConfig(...)`，文件名带插件 id（参考 `ExampleConfig`）。
- 关闭时不要自己反注册模块；只清理线程/连接/监听器。

## 7. 构建与联调

```bash
gradlew.bat build                       # 编译 + 打 jar（首次会下载 Evernight 发行 jar）
python scripts/check-lang.py            # 语言表校验，0 error 才算过
```

- 产物：`build/libs/<archives_base_name>-<version>.jar`。
- 联调：把插件 jar 放进 Evernight 开发目录的 `run/mods/`（或正式游戏 `.minecraft/mods/`），启动客户端；日志出现 `Loaded addon <name> <version> (<id>)`，游戏内 `;addons` 可查。
- 没有插件时 `;addons` 不注册。
- 不在本环境启动游戏时，不要声称验证过游戏内观感；构建与静态检查到此为止。

## 8. 排错

| 现象 | 检查 |
| --- | --- |
| 完全没有加载 | 入口键拼写、入口类是否 public 且无参构造、`environment` 是否 client |
| `Namespace mismatch, expected intermediary got named` | 把 `build/devlibs` 的 `-dev.jar` 交给了 `modImplementation`；改用发行 jar |
| `NoClassDefFoundError: evernight/...` | 运行环境没有装对应版本的 Evernight，或 `depends.evernight` 写错 |
| 插件 FAILED，日志有 `Module name is already registered` | 模块名重名；改名后重试 |
| ClickGUI 有模块但无法开关 | 模块构造器抛异常/未注册；看 `Addon <id> failed` 堆栈 |
| 语言不生效 | 资源路径的命名空间是否等于 `fabric.mod.json` 的 id；两份 JSON 键集是否一致 |
| 设置不落盘 | 设置是否走 `this.add(...)`；是否 `setExcludedFromConfig(true)` |

## 9. 提交前检查

1. `gradlew.bat build` 通过。
2. 动语言表：`python scripts/check-lang.py` 必须 0 error。
3. 改过 `fabric.mod.json` 的 id 或包名时，同步 `assets/<id>/` 目录与所有 `addons.<id>.*` 键。
4. 不把 `libs/*.jar` 或 `build/` 提交进仓库。
5. 任务功能完成后按功能拆分提交，不要一个大提交。
