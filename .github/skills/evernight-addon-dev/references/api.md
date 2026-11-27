# Evernight 插件 API 参考

本模板链接的 API 版本是 Evernight 1.0.3，源码在上游仓库 `src/main/java/evernight/addon/`。行号随重构会变，以类/方法签名为准。

## 1. 公共类型

### `EvernightAddon`（abstract）

```java
public abstract class EvernightAddon {
    public abstract void onInitialize(AddonContext context);
    public void onShutdown() {}
}
```

- Fabric 实例化一次；`onInitialize` 在主线程、配置加载之前调用。
- 抛异常 → 整个插件记 `FAILED`，异常写日志；失败前已成功的注册保留。
- `onShutdown` 在配置保存之后、按加载逆序调用。

### `AddonMetadata`（record）

```java
public record AddonMetadata(String id, String name, String version,
                            String description, List<String> authors, String website) {
    public String displayName();   // name 为空时回退 id
    public String authorsText();   // "A, B"
}
```

由 Fabric mod 元数据投影：`id` = mod id，`version` = `getFriendlyString()`，`authors` = 作者名，`website` = contact 的 homepage，回退 sources。

### `AddonInfo`（record）

```java
public record AddonInfo(AddonMetadata metadata, Status status, String error,
                        int moduleCount, int commandCount) {
    public enum Status { LOADED, FAILED }
    public boolean isLoaded();
}
```

`moduleCount`/`commandCount` 只统计该插件成功注册的数量；失败插件的 `error` 是 `异常类: message`，成功时为 `null`。

### `AddonContext`（final）

| 签名 | 语义 |
| --- | --- |
| `AddonMetadata metadata()` | 元数据 |
| `String id()` | `metadata.id()` |
| `Logger logger()` | `Evernight/Addon/<id>` |
| `EventBus eventBus()` | `ClientAccess.eventBus` |
| `<T extends Module> T registerModule(T module)` | 注册进 `ModuleRegistry`，登记归属，生成模块别名指令；重名抛 `IllegalStateException` |
| `<T extends HudWidgetModule> T registerHudModule(T module)` | 与 `registerModule` 完全等价 |
| `ModuleCategory registerCategory(String name)` | 注册新的模块分类并返回 `ModuleCategory`，供 `AddonModule` 构造器使用；名字全局唯一（含内置、大小写不敏感），空白/重名抛 `IllegalStateException`；显示键为 `addons.<id>.names.categories.<name>`，中文缺键回退英文，两份都缺显示完整键 |
| `<T extends Command> T registerCommand(T command)` | 注册 Brigadier 指令；重复注册返回失败时抛 `IllegalStateException` |
| `void registerConfig(JsonConfigurable configurable)` | `ConfigurationManager` 未创建时暂存，初始化后自动注册并读盘 |
| `void subscribe(Object subscriber)` | 订阅 `@EventHandler` 并记录，关闭时自动退订 |
| `void unsubscribe(Object subscriber)` | 手动退订并移除记录 |
| `void registerLanguage(String languageCode, InputStream input)` | 解析并合并语言表；`input` 由调用方关闭 |
| `void loadLanguageFiles()` | 读取 `assets/<id>/lang/{en_us,zh_cn}.json`；`AddonManager` 初始化时已调用 |
| `Path dataDirectory()` | `evernight/addons/<id>`，自动 `createDirectories` |

常量：`AddonContext.SUPPORTED_LANGUAGES = ["en_us", "zh_cn"]`。

### `AddonManager`（final，单例）

```java
public static AddonManager getInstance();
public static final String ENTRYPOINT_KEY = "evernight-addon";
public boolean isInitialized();
public void initialize();
public List<AddonInfo> getAddons();          // 按 mod id 排序，含 FAILED
public Optional<AddonInfo> getAddon(String id);
public Optional<String> getOwningAddon(Module module);
public List<JsonConfigurable> getPendingConfigurables();
public void shutdown();
```

`initialize()` 幂等；由 `ClientInitializer.initialize()` 在所有管理器构造完成后调用。只有至少加载了一个插件时才注册 `AddonsCommand`（`;addons` / `;addon`）。

### `AddonModule`（abstract，extends Module）

```java
protected AddonModule(String addonId, String name, String description,
                      ModuleCategory category, String... aliases) {
public String getAddonId();
public String translationKey(String key);   // "addons." + addonId + "." + key
@Override public String getDisplayName();   // addons.<id>.names.modules.<Name>
@Override public String getDescription();   // addons.<id>.descriptions.modules.<Name>
@Override public <S extends Setting<?>> S add(S setting); // 自动设置标签键
```

- 显示文本查找链固定为「当前语言 → 英文 → 完整键」：中文缺键回退英文，两份都缺才把完整 key 显示到界面。构造器的 `description` 参数仅为源码兼容保留，不再作为显示兜底。
- 设置标签由 `add` 写入 `Setting#setDisplayNameKey`，解析 `addons.<id>.names.settings.<SettingName>`；设置 tooltip 仍走本体的 `evernight.descriptions.settings.*` 逻辑。
- `AddonContext.registerModule` 会校验模块声明的 addon id 与当前插件一致，不一致直接抛 `IllegalArgumentException`。

### `ModuleCategory`（final，implements NamedEntry）

`evernight/client/modules/ModuleCategory.java`。已从枚举改为 final class：既是内置分类常量，也是插件分类的全局登记表。

```java
public final class ModuleCategory implements NamedEntry {
    public static final ModuleCategory COMBAT, MISC, RENDER, MOVEMENT,
            PLAYER, AUTO, EXPLOIT, CLIENT, HUD;        // 顺序固定，ordinal 0-8
    public static List<ModuleCategory> values();       // 内置在前 + 插件按注册顺序，不可变
    public static ModuleCategory findByName(String name); // 大小写不敏感，找不到返回 null
    public static ModuleCategory register(String name, String translationKey); // 内部用，见下
    public String getName();                           // 稳定英文标识
    public String getDisplayName();                    // 当前语言 → 英文 → 完整键
    public int ordinal();                              // 显示顺序，供 ClickGUI 面板排序
}
```

- 对象只保存稳定名与语言键，Java 里没有英文显示名兜底；分类不进配置/预设，模块按对象身份（`==`）比较。
- 插件不要直接调用 `register`，用 `AddonContext#registerCategory(String)`；它自动拼出 `addons.<id>.names.categories.<Name>` 并做唯一性校验。
- 内置分类键是 `evernight.names.categories.<Name>`；插件分类键推荐同时写进 `en_us.json` 与 `zh_cn.json`。中文缺键回退英文，两份都缺时显示完整键。
- `values()` 覆盖内置与插件分类，ClickGUI 面板、`;drawn <category>`、`;preset ... specific <category>` 都走它；分类参数由 `ModuleCategoryArgumentType` 解析（大小写不敏感，错误信息复用 `commands.Arguments.UnknownEnum`）。

## 2. 加载与初始化顺序

```
ClientInitializer.initialize()
  ├─ 构造 ModuleRegistry / HudModuleRegistry / CommandRegistry / ...
  ├─ AddonManager.initialize()
  │    ├─ 读取 evernight-addon entrypoint，按 mod id 排序
  │    ├─ 每个插件：new AddonContext → loadLanguageFiles() → onInitialize(context)
  │    └─ 有插件时注册 ;addons
  └─ managersInitialized = true

ClientInitializer.initializeClient()
  ├─ ModuleDependencies.bind(...)
  ├─ ClientKeybinds.register()
  ├─ ClientInitializationEvent（HUD 锚点重建）
  ├─ new ConfigurationManager()
  ├─ 注册 AddonManager 暂存的 JsonConfigurable
  └─ configuration.load()   ← 插件的模块设置在这里恢复

Minecraft.destroy()
  ├─ configuration.save()
  └─ AddonManager.shutdown() → onShutdown() + 释放 context 订阅
```

推论：

- 插件模块的开关/设置能像本体一样持久化（`modules.json` / `hud.json`）。
- `onInitialize` 里可以读 `ClientContext.moduleRegistry`/`commandRegistry`，但 `ClientContext.configurationManager` 还是 `null`。
- 插件在 `onInitialize` 之后才注册的模块不会读取已存在的存档状态。

## 3. 模块与指令的注册细节

- `registerModule` 使用 `ModuleRegistry.registerModule`，它会拒绝被 `ExperimentalFeatures` 隐藏的类、清理隐藏设置；插件注册的重名模块返回 `false`。
- 注册成功后调用 `CommandRegistry.registerModuleAliasCommands(module)`：为模块的每个别名注册 `;<alias>`，若已存在同名指令则跳过。
- 模块的 `@EventHandler` 由 `Module.enable()` 自动订阅；不要再用 `context.subscribe(module)`，否则会重复触发。
- 模块分类是 final class：内置 8 类可直接复用，插件可用 `context.registerCategory("X")` 新增并在 `onInitialize` 内传给模块构造器；分类名全局唯一（含内置、大小写不敏感），空白/重名抛 `IllegalStateException`，插件记为 `FAILED`。
- `CommandRegistry.registerCommand` 会同时注册 `getAliases()`；指令名保持小写英文。

## 4. 语言 overlay

- 文件：`assets/<addon id>/lang/en_us.json`、`zh_cn.json`，UTF-8 扁平 JSON。
- `AddonManager` 在 `onInitialize` 前自动调用 `loadLanguageFiles()`；缺失文件静默跳过。
- `LanguageTable` 以 `(sourceId, languageCode)` 为键保存 overlay；`load(stream, code)` 换语言后自动重新叠加。
- overlay 在基础表之后应用，因此插件键会覆盖同名本体键（不建议覆盖本体键）。
- 本体键统一在 `evernight.` 命名空间，**插件不得使用**；插件所有键（模块名、描述、设置标签、私有文案）都放 `addons.<id>.*`。
- `AddonModule` 自动读取 `addons.<id>.names.modules.*` / `addons.<id>.descriptions.modules.*` / `addons.<id>.names.settings.*`；其他文案用 `Translations.get("addons.<id>.xxx")`。
- 新增文案/新增分类的显示名：Java 只写键，不用自造英文名或空串兜底；`en_us.json` 与 `zh_cn.json` 同时补键。查找链为当前语言 → 英文 → 完整键，中文缺键回退英文，两份都缺显示完整键（方便发现漏翻）。
- 插件自定义枚举的显示名可覆写 `NamedEntry#getDisplayName()` 读取自己的命名空间；本体的 `Translations.value` 只查 `evernight.names.values.*`。

## 5. 配置

- `JsonConfigurable` 契约：`JsonElement toJson()`、`void fromJson(JsonElement)`、`String getConfigName()`（默认空串，必须覆写）。
- `registerConfig` 在 `ConfigurationManager` 已存在时立即注册；文件在下次 `save()` 写入，但**不会**在注册后自动读盘。
- 配置文件统一位于 `evernight/`，文件名必须唯一；建议 `<id>.json`。
- 插件自己的资源/缓存放 `dataDirectory()`，不要污染配置目录根部。

## 6. 兼容性与限制

- 插件 API 面向二进制兼容；新增方法优先用 `default`/重载，删除或改签名视为破坏性变更。
- 不支持热卸载/热重载插件；重载配置（`;reload`）只重读配置源。
- 插件可以使用本体公开 API，但不保证私有字段/内部实现稳定；不要用反射改本体状态。
- Fabric 的 mixin 属于插件自己：插件 `fabric.mod.json` 声明自己的 mixin 文件，本体不代管。
