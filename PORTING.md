# 移植说明（Meteor Aurora → Evernight Aurora）

本文件记录逐模块的移植状态、与原版的差异、以及剩余工作的具体阻塞点。
移植依据：`evernight-addon-template` 的 `meteor-to-evernight-port` skill
（查重 → 事件映射 → 设置语义 → 语言表 → 构建验收）。

- 上游：`aurora-1.21.11-1.4.1.jar`（Meteor Client addon，作者 n_lzy，GPL-3.0）
  （1.4.0 的移植已完成，1.4.1 的差异见 §6）
- 目标：Evernight 1.0.3 / MC 1.21.11
- 反编译与改写方式：jar → Vineflower 反编译 → intermediary 名批量翻译成 Mojang 官方名 →
  按 Evernight API 逐模块改写（脚本在工程外的 `tools/`，不随插件发布）

## 1. 状态总览

| 原类 | 新模块名 | 分类 | 状态 |
| --- | --- | --- | --- |
| `ElytraInfiniteFlight` | `ElytraPitch40` | Elytra | 已移植，逻辑等价 |
| `ElytraCollect` | `ElytraCollect` | Elytra | 已移植 |
| `elytracollect/AutoRestart` | `AutoRestart` | Elytra（ClickGUI 隐藏） | 已移植 |
| `elytracollect/Run` | `CollectRun` | Elytra（ClickGUI 隐藏） | 已移植 |
| `elytracollect/EndShipFinder` | `EndShipFinder` | Elytra（ClickGUI 隐藏） | 已移植（方块更新事件降级，见 §4.1） |
| `elytracollect/ItemFrameSearch` | `ItemFrameSearch` | Elytra（ClickGUI 隐藏） | 已移植 |
| `elytracollect/LockEnd` | `LockEnd` | Elytra（ClickGUI 隐藏） | 已移植 |
| `elytracollect/LockStart` | `LockStart` | Elytra（ClickGUI 隐藏） | 已移植 |
| `elytracollect/StoreResupply` | `StoreResupply` | Elytra（ClickGUI 隐藏） | 已移植（末影箱回退未移植） |
| `elytracollect/StowElytra` | `StowElytra` | Elytra（ClickGUI 隐藏） | 已移植（末影箱换盒未移植） |
| `elytracollect/WaypointLock` | `WaypointLock` | Elytra（ClickGUI 隐藏） | 已移植（路线模块联动未移植） |
| `ElytraTravel` | `ElytraTravel` | Elytra | 已移植 |
| `elytratravel/ContinueFly` | 助手类 | — | 已移植 |
| `elytratravel/EmergencyLanding` | 助手类 | — | 已移植 |
| `elytratravel/Resupply` | 助手类 | — | 已移植（末影箱补给链未移植） |
| `AFKMode` | `AfkAssist` | Production | 已移植，模块列表设置改为模块名列表 |
| `AttackHelper` | `RaidAssist` | Production | 已移植，`Utils.leftClick()` 改为按键模拟 |
| `BetterDirection` | `BetterDirection` | Other | 已移植，方位文字改走语言表 |
| `BetterChat` | `ChatGuard` | Other | 部分移植（去重后的子集） |
| `ElytraRouteCalculate` | — | Elytra | **未移植**，见 §4.1 |
| `AutoCircleSlab` | — | Production | **未移植**，见 §4.2 |
| `AutoRefreshEnchantmentBook` | — | Production | **未移植**，见 §4.3 |
| `AutoAssembleBox` 组 | — | Production | **未移植**，见 §4.4 |
| `EditableChatHistory` | — | Other | **未移植**，见 §4.5 |
| `FakeChatFor3C3U` 组 | — | Other | **未移植**，见 §4.6 |
| `modules/utils/*`（`AutoEat`/`EnderChestResupply`/`MineSingleBlock`/`WalkToPlace`） | 仅 `AutoEat` 作为助手类 | — | 上游未注册的遗留类，不单独成为模块 |

16 个模块已注册；其中 9 个自动收集子模块在 ClickGUI 里隐藏（见 §2.8），`;addons` 仍能看到全部。

## 2. 已移植模块的差异

### 2.1 `ElytraPitch40`（原 `ElytraInfiniteFlight`）
- 三个 `SettingGroup` 改为平铺设置；高度限制两项用 `dependsOn(EnableHeightLimit)` 表达可见性。
- `TickEvent.Pre` → `MinecraftTickEvent`，保留 `priority = 100`。
- `getInfoString()` → `getInfo()`，文本走语言表（俯冲 / 拉升）。

### 2.2 `AfkAssist`（原 `AFKMode`）
- `ModuleListSetting` → `StringSetting` 存逗号分隔的**模块名**，运行期在
  `ClientContext.moduleRegistry` 里解析（发行 jar 无 `ModuleListSetting`；
  `StringSetSetting` 会把条目小写化，不适合存模块名）。

### 2.3 `RaidAssist`（原 `AttackHelper`）
- `InvUtils.findInHotbar` → `InventoryUtils.findHotbarSlot`（返回槽位，`-1` = 没有）。
- `InvUtils.swap(slot, false)` → `InventoryUtils.selectHotbarSlot(slot)`。
- `Utils.leftClick()` → 按住 `keyAttack` 两个 tick 再松开（`handleKeybinds` 每 tick 只读一次）。
- `DrinkTrigger` 枚举实现 `NamedEntry`：配置里存英文稳定标识，显示名查语言表。

### 2.4 `BetterDirection`（原同名）
- `Render2DEvent` → `HudRenderEvent`，绘制走 `GuiGraphics`。
- 方位文字拆成 `addons.aurora.directions.*` + `messages.directionOffset` / `axis` / `axisOffset`。
- `SettingColor` → `java.awt.Color`，取色 `getRGB()`。

### 2.5 `ChatGuard`（原 `BetterChat`，去重后子集）
- 原版「按关键字屏蔽」与本体自带 `ChatFilter` 重复，按 skill 查重要求不重复实现；
  保留本体没有的两项：`#` 开头的 Baritone 指令拦截、按玩家名屏蔽（外加回显）。
- 原版靠两个 mixin 实现，这里改用本体 `ChatMessageEvent` 与
  `ClientPacketListenerSendChatEvent`，**插件不带 mixin**。

### 2.6 `ElytraCollect` 组（10 个模块）
- 原版在入口用 `Config.get().hiddenModules` 强制隐藏这 9 个子模块；Evernight 没有等价机制，
  这里直接注册为可见模块（玩家可单独开关，`ElytraCollect` 仍会在运行期拉起它们）。
- 子模块互调用从 `Modules.get().get(X.class)` 改为 `ClientContext.getModule(X.class)`，
  在事件里现取并判空（构造期可能为 null）。
- 坐标文件从写死工作目录改为 `AddonContext#dataDirectory()`（`evernight/addons/aurora/`），
  见 `utils/TiziStorage`、`utils/PointUtils`、`utils/BlacklistUtils`。
- `Render3DEvent` + `ShapeMode` → `WorldRenderEvent.Main` + `WorldBoxRenderer`。

### 2.7 `ElytraTravel` 组（1 个模块 + 3 个助手类）
- 原版给助手类传三个 `Consumer<String>` 并传拼好的中文；这里改成 `TravelLog` 接口传
  「键 + 参数」，由模块统一翻译打印（`elytratravel/TravelLog`）。
- 补给潜影盒默认名从「补给盒」改为 `supply`（Java 里不写中文），玩家可在设置里改。
- `AutoEat` 从静态单例改为普通实例（构造时显式订阅事件），避免类加载即永久订阅。

### 2.8 隐藏子模块（对应上游 `Config#hiddenModules`）
上游在 `@PostInit` 里把 9 个自动收集子模块塞进 `Config#hiddenModules`，让它们不出现在 ClickGUI，
但仍可被 `ElytraCollect` 拉起、也能用 `;<模块名>` 开关。Evernight 没有等价机制：

- `Module#setDrawn(false)` 只影响 ModuleList HUD 与通知（`ModuleCategoryPanel` 不看这个字段）；
- `Module#requiresPlatform(false)`（1.2.4+）会让模块彻底不可用，`enable()` 直接返回，语义不对。

因此本工程用两步实现同样的效果：

1. `HiddenModules.hide(module)`：`setDrawn(false)` + 把模块的 `Class` 记进隐藏表；
2. 插件自带的 `mixin/ModuleCategoryPanelMixin`：在 `ModuleCategoryPanel#populateModules` 返回后，
   把隐藏表里的模块从 `Panel#children` 摘掉——这一步才真正让 ClickGUI（含 HUD 编辑器）不显示。

用 `Class` 而不是 `drawn` 做判据，所以 `modules.json` 里旧的 `drawn: true` 不会把模块「反隐藏」，
`;drawn` 也改不回来（与上游每次启动强制隐藏一致）。隐藏模块仍然保留配置、键位与 `;模块名` 指令。

## 3. 结构性差异（全工程通用）

| 上游写法 | 本工程写法 | 原因 |
| --- | --- | --- |
| `extends Module` + `super(Category, "中文名", "描述")` | `extends AuroraModule` + `super("EnglishName", "desc", category)` | 模块名是全局键，必须英文稳定 |
| `SettingGroup` 分组 | 平铺设置 + `dependsOn(...)` | 发行 jar 的分组是「组开关」，语义不同 |
| `setting.get()` | `setting.getValue()` | API 差异 |
| `SettingColor` / Meteor `Color` | `java.awt.Color` / ARGB `int` | 本体不提供 Meteor 颜色类 |
| `ModuleListSetting` | `StringSetting`（逗号分隔模块名） | 发行 jar 无该类型 |
| `BlockPosSetting` | 3 个 `NumberSetting`（待移植模块） | 发行 jar 无该类型 |
| Meteor `mixin/` | 优先用本体事件 | 本体不代管插件 mixin |
| `Translations.format(...)` | 插件自己的 `translate()`：`Translations.get` + `String.format` | 本体 `format` 固定加 `evernight.ui.` 前缀，查不到 `addons.aurora.*` |
| 中文硬编码文本 | 语言表 `addons.aurora.*`（292 个键） | 本体硬规则 |

## 4. 尚未移植的模块与阻塞点

### 4.1 `ElytraRouteCalculate`（1429 行）
- 依赖 seedfinding 的 `mc_*` 库（上游把 7 个 jar 塞在 `META-INF/jars/`）。
- 移植方式：把这 7 个 jar 放进本插件并在 `fabric.mod.json` 用 `jars` 声明；
  代码侧替换 `SettingGroup`/`BlockPosSetting`、把写死的工作目录改成 `context.dataDirectory()`。
- 连带影响：`WaypointLock` 依赖它读取路线（当前留 `TODO(port)`，锁定逻辑本身已移植）。

### 4.2 `AutoCircleSlab`（944 行）
- 阻塞点：`BlockPosSetting` → 3 个 `NumberSetting`；`BlockListSetting` → `RegistrySetSetting<Block>`；
  `Renderer3D` 的 `ShapeMode` → `WorldBoxRenderer.fillBox/outlineBox`；
  放置流程对 `PacketEvent.Send` 的改写与 `ServerboundMovePlayerPacket` 时序需要重新验证。

### 4.3 `AutoRefreshEnchantmentBook`（738 行）
- 依赖 `modules/utils/MineSingleBlock`（上游未注册的遗留类）与 `EnchantmentListSetting`
  （发行 jar 无该类型，需改 `RegistrySetSetting<Enchantment>` 或字符串设置）。

### 4.4 `AutoAssembleBox` 组（161 + 773 + 640 + 377 行）
- 套娃式子模块编排 + 大量容器交互，移植成本最高，建议最后做。

### 4.5 `EditableChatHistory` + `ChatHistoryEditorScreen`
- 原版界面继承 Meteor 的 `WidgetScreen`/`GuiTheme`，属于 Meteor GUI 框架，不能搬。
- 移植方式：改用原版 `Screen` 自绘（`ChatHudAccessor` 已能拿到消息列表），
  或做成 `;chat edit` 指令 + `EditBox` 的简单界面。

### 4.6 `FakeChatFor3C3U` 组（→ `FakeServerStats`）
- 依赖两个 mixin（`PlayerListHudMixin` 伪造玩家列表与分隔线、`PlayerListEntryMixin` 伪造延迟）
  与 `ChatApiManager`（HTTP 轮询 `https://3c3u.info/api/events`）。
- 移植方式：mixin 可直接搬（只涉及原版类）；HTTP 轮询改 `java.net.http.HttpClient` 并在
  `onDisable` 停线程；聊天注入改 `ChatMessageEvent`。

### 4.7 已知降级点（代码里带 `TODO(port)`）
| 位置 | 降级内容 |
| --- | --- |
| `EndShipFinder` | 本体的 `BlockUpdateAppliedEvent` 不带 pos/oldState/newState，只能整体标记重扫 |
| `StoreResupply` / `StowElytra` / `Resupply` | 上游的末影箱补给链依赖未注册的 `EnderChestResupply`，未移植（找不到盒子时直接放弃该轮） |
| `Resupply` | 上游用 `MineSingleBlock` 自动换最佳工具，这里直接用当前手持工具 |
| `ContinueFly` | 上游读取 `ElytraCollect` 的「锁定末地快捷切换」配置，该配置随路线模块一起未移植 |

### 4.8 `AutoMine` 组（1.4.1 新增，约 2500 行）
- 子类：`AutoMineConfig`、`InventoryHelper`、`RepairTask`、`PickupTask`、`ContainerInteraction`、
  `PlacementFinder`、`DeepDarkGuard`、`BaritoneDrive`。
- 阻塞点：`BaritoneDrive` 依赖 Baritone 桥（本体 `ClientContext.baritoneBridge`，未安装时是
  `DisabledBaritoneBridge`，要按 `isAvailable()` 分支）；`AutoMineConfig` 用 `BlockPosSetting` /
  `BlockListSetting` / `ItemListSetting`，需换成 `RegistrySetSetting` 与 3 个 `NumberSetting`。
  这是 1.4.1 里体量最大的一块，建议单独一轮移植。

### 4.9 `AutoUpgradeTools`（258 行）、`PearlBot`（738 行）（1.4.1 新增）
- `AutoUpgradeTools`：自动往锻造台放钻石装备 + 下界合金模板，阻塞点只有 `ItemListSetting`
  → `RegistrySetSetting<Item>`，移植成本低，建议优先做。
- `PearlBot`：末影珍珠投掷 + 落点计算 + 容器交互，依赖 `InvUtils.quickSwap` / `FindItemResult`
  与 `Rotations`，需要逐处换成 `InventoryUtils` 与 `rotationManager`。

## 5. 验收

| 项目 | 结果 |
| --- | --- |
| `gradlew build` | BUILD SUCCESSFUL，产物 `build/libs/aurora-addon-1.4.1.jar` |
| `python scripts/check-lang.py` | 0 error / 0 warning，292 个键中英一致 |
| 代码引用的语言键 | 与语言表逐项比对，无缺失（模块名/描述/设置/枚举值/运行时文案） |
| mixin 目标核对 | 用 `javap` 核对发行 jar：`ModuleCategoryPanel#populateModules`、`Panel#children`、`ModuleComponent#getModule` 均存在且同名（Evernight 自身类不混淆，因此不需要 refmap） |
| 游戏内行为 | **未验证**：按 skill 要求不启动游戏，行为需人工验收 |

验收建议顺序：

1. `;addons` 确认 Aurora 加载；ClickGUI 的 Elytra 分类里应只看到 `ElytraPitch40`、`ElytraTravel`、
   `ElytraCollect` 三个，9 个自动收集子模块**不出现**（`;autorestart`、`;lockend` 等指令仍可用）。
2. 检查显示名 / 设置标签 / 提示文本是否本地化（切中英文各看一遍）。
3. 创造模式单人世界验证 `ElytraPitch40` 的俯冲-拉升循环、`BetterDirection` 的方向条、
   `ElytraTravel` 的升空（含新增的升空烟花间隔 / 鬼手烟花两项）。

## 6. 1.4.1 同步内容

上游 1.4.1（文件名 1.4.1，`fabric.mod.json` 里仍写 1.21.11-1.4.0）相对 1.4.0 的变化与处理：

| 变化 | 处理 |
| --- | --- |
| 删除 `TitleScreenMixin`、`ScreenBackgroundMixin`、`FramePlayer`（视频背景） | 本来就没移植，无需处理 |
| `ElytraCollect` 切槽改用 `InvUtils.swap` | 等价：`InventoryUtils.selectHotbarSlot`（内部发 `ServerboundSetCarriedItemPacket`） |
| `ElytraCollect` / `ElytraTravel` 低高度下线后额外 `mc.disconnect(ProgressScreen)` + 退回多人界面 | 已同步 |
| `ElytraTravel` 新增「升空」组：`ClimbFireworkInterval`（默认 60）、`ClimbQuickSwap`（默认开） | 已同步，并接入 `ContinueFly` |
| `ContinueFly` 改为接收 `IntSupplier` / `BooleanSupplier`，不再读 `ElytraCollect` 静态配置 | 已同步，顺带消除了 1.4.0 移植时的 `TODO(port)` |
| `StowElytra` 移除自定义音效调用（`autoelytra:*`） | 已同步（本工程本来也没有这些音效资源） |
| 新增 `combat/KillAura`、`CrystalAura`、`LogPointNotifier` | **未移植**：三者是 10 行的空壳占位类（无任何逻辑），且 KillAura/CrystalAura 与本体 `Aura`/`AutoCrystal` 重名同功能 |
| 新增 `production/AutoMine`（923 行）+ `automine/*`（约 1600 行） | **未移植**，见 §4.8 |
| 新增 `production/AutoUpgradeTools`（258 行）、`PearlBot`（738 行） | **未移植**，见 §4.9 |

## 7. 从 LeavesHack 补充的三个功能（1.5.0）

上游来源：[LeavesHack](https://github.com/MrBZBZ/LeavesHack)（AGPL-3.0，作者 MrBZBZ），
按 Evernight API 重新实现，逐文件许可说明见 `NOTICE`。

| LeavesHack | 本工程 | 分类 | 说明 |
| --- | --- | --- | --- |
| `modules/PacketMine.java`（781 行） | `modules/combat/PacketMine` | Combat | 发包挖掘 |
| `modules/BreakESP.java` + `manager/BreakManager.java` | `modules/combat/BreakESP` + `utils/BreakTracker` | Combat | 挖掘显示 |
| `modules/AutoLogin.java` + `modules/autoLogin/*` | `modules/other/AutoLogin` + `utils/LoginAccounts` + `commands/LoginCommand` | Other | 自动登录 |

### 7.1 `PacketMine`（发包挖掘）
保留的核心流程：拦截 `startDestroyBlock`（`MultiPlayerGameModeStartDestroyBlockEvent` 可取消）→
首 tick 发 `START_DESTROY_BLOCK`（含 `FastBypass` 的 y=-999 绕过包）→ 按地面/滞空不同速率累积进度 →
达到 `破坏耗时 × Damage` 后发 `STOP_DESTROY_BLOCK` → 按 `TryBreakTime` 重试 → `SwitchTime` 后切回原槽。
破坏耗时复用 `BreakTracker.breakTimeMs`（最优工具 + 效率附魔）。

未移植（代码内 `TODO(port)`）：
- 「优先重生锚 / 优先蜘蛛网」联动：依赖上游的 `AutoAnchor` / `AutoWeb` 模块，本工程没有；
- 双挖（第二个目标）：`BreakESP` 仍会显示**其他玩家**的双挖；
- 进度动画指数 `AnimationExponent`。

### 7.2 `BreakESP`（挖掘显示）
`BreakTracker` 把服务端 `ClientboundBlockDestructionPacket` 按挖掘者实体归并成主挖 / 副挖两条进度，
只统计 8 格内的包，超时未断记 `Failed`、方块变空气记 `Broke`。渲染改用
`WorldRenderEvent.Main` + `WorldBoxRenderer` + `RenderUtils#drawWorldText`，好友配色走
`SocialRegistry`。原版的 `detectDouble` / 超时系数已内置（未暴露为设置）。

### 7.3 `AutoLogin`（自动登录）
账号表存 `evernight/aurora-autologin.json`（`LoginAccounts implements JsonConfigurable`）；
连服时按「当前用户名 + 规范化后的服务器地址」匹配，进世界后第一 tick 用
`ChatUtils.sendChatOrCommand` 发 `/<登录指令> <密码>`；`PacketEvent.Send` 里拦截
`ServerboundChatCommandPacket`，把 `/l`、`/reg`、`/cp` 里的密码自动存下。
上游用模块内嵌的 Meteor GUI 表格管理账号，Evernight 没有等价接口，改为 `;autologin`
（`;al`）指令：`list` / `add <密码>` / `remove [服务器]` / `clear`，列表里密码打码。

## 8. 事故复盘：1.5.0「进游戏一个功能都看不到」

### 现象
装到 Evernight 1.2.4 上，ClickGUI 里没有任何 Aurora 分类/模块，`;addons` 也看不到。

### 根因
`Aurora.onInitialize` 里自建了一个名为 **`Combat`** 的分类：

```java
ModuleCategory combat = context.registerCategory("Combat");   // ← 与本体内置分类重名
```

Evernight 1.0.3 的 `ModuleCategory.register` 不校验重名（会静默多出一个同名面板），
**1.2.4 起改为抛异常**：

```
java.lang.IllegalStateException: Module category is already registered: Combat
    at evernight.client.modules.ModuleCategory.register(ModuleCategory.java:116)
    at evernight.addon.AddonContext.registerCategory(AddonContext.java:142)
    at com.aurora.addon.Aurora.onInitialize(Aurora.java:48)
```

`onInitialize` 里未捕获的异常会让 `AddonManager` 直接中止该插件的初始化，
**异常点之后的所有注册（模块、指令、配置）全部不执行**——于是「整个插件都不见了」。

同一份日志里还有第二个独立问题：本工程按 Evernight **1.0.3** 编译，而玩家实际跑的是 **1.2.4**，
开发目标与运行环境不一致，导致这类版本相关行为在开发期完全暴露不出来。

### 修复
1. 战斗相关模块改用本体内置的 `ModuleCategory.COMBAT`，不再自建同名分类；
2. **开发目标改为 Evernight 1.2.4**（`evernight_version=1.2.4`、`fabric.mod.json` 的
   `depends.evernight >= 1.2.4`），并把 Loom 升到 **1.19.4**（1.2.4 的 jar manifest 声明
   `Fabric-Loom-Version: 1.19.3`，Loom 1.18.4 会拒绝加载）；
3. 注册流程改为**失败隔离**：`registerSafely` / `registerCategory(..., fallback)` 把单个模块或
   分类的失败降级为一条警告，任何一个名字被占用都不再拖垮整个插件；
4. 新增 `scripts/check-registration.py` 并接入 CI：静态拦截「分类名撞内置分类」「分类/模块名重复」
   「模块缺中英文语言键」三类问题。

### 排查过程中的另一个坑（与本次事故无关，但会让人误判）
`~/.gradle/gradle.properties` 里残留了一个已经失效的本机代理（`127.0.0.1:53106`），
Gradle 走它拉取 Loom 插件时全部失败，报错是 `Plugin ... was not found`（看不出是网络问题）。
删掉代理配置后 Loom 1.19.4 一次解析成功。**遇到「插件坐标明明存在却 not found」先查代理。**

## 9. 自动宝库（Fuyi `AutoVault`）——第一阶段：定位 + 导航

上游 Fuyi 的「自动宝库」是一条完整流水线，规模如下（反编译行数）：

| 上游文件 | 行数 | 职责 |
| --- | --- | --- |
| `AutoVault.java` | 358 | 模块与 24 项设置 |
| `AutoVaultRunner.java` | **5132** | 状态机：飞行→下挖→清怪→开宝库→补给→换密室 |
| `supply/SupplyPack.java` | **1858** | 潜影盒补给（图腾/钥匙/食物） |
| `ElytraFlight.java` | 735 | 鞘翅滑翔巡航 |
| `ChamberFinder.java` | 261 | 按种子算密室坐标 |
| `safety/SpawnerAvoider.java` | 241 | 刷怪笼规避 |
| `combat/KillHelper.java` | 105 | 发包攻击 |
| 其余（Store/Log/Api 等） | ~900 | 进度持久化、日志、版本适配 |

合计约 **9800 行**。一次性照搬不现实，也不可能在没有游戏内验证的情况下保证正确，
因此按阶段移植。**本版（1.6.0）交付第一阶段：定位 + 导航**。

### 9.1 已实现（`AutoVault`，Production 分类）
- **按种子定位**（`utils/ChamberFinder`）：用原版 `trial_chambers` 结构集参数
  （spacing=34 / separation=12 / salt=94251327 / LINEAR）逐个 region 反算结构起点区块，
  取区块中心，按距离排序取最近 N 个；
- **目标标记**：方框 + 世界文字（序号 / 总数 / 距离）；
- **自动导航**：`BaritoneBridge#pathToXZ`（步行）或 `#pathElytraTo`（鞘翅），
  到达判定按水平距离，到了自动换下一个，全部走完给提示；
- **可配置**：种子、数量、重新搜索距离、目标 Y、是否自动寻路、寻路方式、到达距离、渲染与配色。

数学正确性用一次性自检验证过（`devProbe`，验证后已删除）：种子 `12345` 得到
`(-216,72) (8,232) (136,-520) (-472,-296) (-296,584)`，坐标均为区块中心（`x % 16 == 8`），
距离递增、无重复；`RandomSpreadStructurePlacement` 改为懒加载（构造函数会碰注册表，
类加载期构造会抛 `Not bootstrapped`）。

### 9.2 未移植（下一阶段）
1. **垂直下挖进入**：上游按密室方块（陶瓦标记）找入口并逐层挖下去（`TreasureScanner` + `interaction/BlockBreaker`）；
2. **清怪与刷怪笼规避**：`KillHelper` + `SpawnerAvoider`（旋风人刷怪笼 14 格内必放弃）；
3. **开宝库**：试炼钥匙开宝库、验证是否开启（`VaultStore`）、失败重试；
4. **就地补给**：`SupplyPack`（潜影盒取图腾/钥匙/食物、放盒、收盒、经验瓶修鞘翅）；
5. **断点续跑**：`ProgressStore` / `VaultStore` / `SeedStore`（已访问密室、已开宝库）；
6. **执行日志**：`AutoVaultLogFile`（`游戏目录/fuyi/autovault_*.log`）。

这几块都依赖容器点击时序与 Baritone 过程控制，需要单独一轮实现 + 游戏内验证。

## 10. 计算路线（`ElytraRouteCalculate`）—— 已移植（1.7.0）

「找鞘翅必备」的那一环。上游 1433 行，本次**算法逐行保留**，只替换框架层：

| 上游 | 本工程 |
| --- | --- |
| `SettingGroup` 分组 + 36 项设置 | 平铺设置（`add(new XxxSetting(...))`），英文稳定键 |
| `SettingColor` / `ShapeMode` | `java.awt.Color` / `RenderMode` + `WorldBoxRenderer` |
| `Render3DEvent#renderer.box(...)` | 本地 `box(...)` 辅助方法（fill/outline 两个调用） |
| `TickEvent.Post` | `MinecraftTickEvent` |
| `Modules.get().get(ElytraRouteCalculate.class)`（在 `WaypointLock` 里） | `ClientContext.getModule(ElytraRouteCalculate.class)`，反射已删除 |
| `META-INF/jars` 里的 7 个 seedfinding jar | 同样打进本插件 jar 的 `META-INF/jars`，并在 `fabric.mod.json` 的 `jars` 里声明 |

**seedfinding 依赖**（`mc_biome / mc_core / mc_feature / mc_math / mc_noise / mc_seed / mc_terrain`，
约 1 MB）放在 `libs/seedfinding/`，构建时由 `processResources` 拷进 jar 的 `META-INF/jars`。
`.gitignore` 里对 `libs/*.jar` 做了例外，CI 也能直接构建。

**自检**（临时 `routeProbe`，验完已删）：`MCVersion.fromString("1.21")` → `1.21`；
种子 `12345` 在 5×5 个 region 内扫到 25 个末地城区块（如 `-37,-37` / `-36,-17` / `-33,5`）；
`EndBiomeSource` / `EndTerrainGenerator` / `EndCityGenerator` 均可实例化。

**已知差异**：该模块的 22 处运行期提示仍是中文硬编码（未接语言表）。
其余模块的提示都走 `addons.aurora.*`；这一块单独收尾时再统一抽键。

## 11. 自动铺半砖（`AutoCircleSlab`）—— 已移植（1.8.0）

上游 944 行。球壳重建、可刷怪方块判定、射线可达性、三种放置模式（`NONE` / `DELAY_MOVEMENT` /
`MOVEMENT_POST`）、发包队列与旋转还原全部保留。框架层替换：

| 上游 | 本工程 |
| --- | --- |
| `BlockPosSetting` | `OriginX` / `OriginY` / `OriginZ` 三个 `NumberSetting` |
| `BlockListSetting` | `RegistrySetSetting<Block>`（默认 29 种地毯/半砖/铁轨，与上游一致） |
| `Renderer3D` + `ShapeMode` | `WorldBoxRenderer` + `RenderMode` |
| `PacketEvent.Send` / `TickEvent.Pre|Post` | 本体同名事件 / `MinecraftTickEvent` |
| `excludeDir` 位掩码（跳过贴合面） | **无对应**，统一画整面（仅视觉差异，不影响放置） |

## 12. 修复：找不到「未加载区块」里的末地船（1.8.1）

### 现象
滑翔经过没加载/没完全加载的区域时，`EndShipFinder` 扫不到末地船；`计算路线` 在个别环境里也可能一艘船都算不出来。

### 根因（两处，都修了）

**1. `EndShipFinder` 只认 `ChunkStatus.FULL`**

```java
mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false)   // 未完全加载 → null
```

滑翔时周围区块经常只到 `FEATURES` / `LIGHT` 状态，`FULL` 查询直接返回 `null`，
于是「区块明明已经下来了，但里面的末地船扫不到」。现在先取客户端区块缓存里**任意状态**的区块：

```java
LevelChunk chunk = mc.level.getChunkSource().getChunkNow(cx, cz);
if (chunk == null) chunk = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
```

**2. 方块更新后只重扫玩家周围 3×3 区块**

Evernight 的 `BlockUpdateAppliedEvent` 不带坐标，移植时退化成「更新后重扫 3×3」——
船在 3×3 之外的区块加载时**永远扫不到**。现在改成**轮转扫描**：
每 tick 扫 8 个区块，游标在渲染距离内循环推进（渲染距离 12 时全图一轮约 40 秒，
单 tick 成本恒定），任意新加载区块都会被扫到。

**3. `ElytraRouteCalculate` 把「结构生成抛异常」当成「这里没有船」**

```java
try { gen.generate(...); hasShip = gen.hasShip(); }
catch (Exception e) { gen.reset(); }        // hasShip 保持 false
if (requireShip && !hasShip) return null;   // 整片区域被静默丢弃
```

一次生成异常就会让整片区域看起来「一艘船都没有」。现在生成失败时**保留城市**
（`generationFailed` 标记），只有确认「生成成功且确实没船」才丢弃。

### 自检（临时 probe，验完已删）
种子 `12345`、半径 20000：`cities=16129 spawned=7067 generated=1511 ships=818 elytra=818`，
耗时 6.5 秒 —— 说明种子数学这条路本身是通的，问题确实出在「区块状态过滤」和「异常吞掉」上。

## 13. 自查（1.8.2）：修掉的 8 个问题

逐模块过了一遍，按「会不会被玩家看见 / 会不会崩」排序：

| # | 位置 | 问题 | 后果 | 修复 |
| --- | --- | --- | --- | --- |
| 1 | `ElytraCollect#info/error` | 用了 `Translations.format`，它会固定补 `evernight.ui.` 前缀 | **所有**自动收集相关提示都显示成 `evernight.ui.addons.aurora.messages...` | 改 `Translations.get` + `String.format` |
| 2 | `ElytraRouteCalculate#foundCities` | 普通 `ArrayList`，搜索线程 add/clear、渲染线程遍历 | 搜索时 `ConcurrentModificationException`，渲染线程抛异常 | 改 `CopyOnWriteArrayList` |
| 3 | `AuroraModule#print` | 后台线程直接调 `ChatUtils.printChat` | 聊天栏非线程安全，搜索线程打日志可能崩 | 非主线程走 `mc.execute(...)` |
| 4 | `EndShipFinder#searchChunk` | 逐方块扫 `minY..高度图`（单区块约 10 万次查询），且依赖 `WORLD_SURFACE` 高度图 | 我上一版改成每 tick 扫区块后变成**持续掉帧**；未完全加载的区块高度图未生成 → 上界为 0 → 漏扫 | 改为按 section 扫描，`hasOnlyAir()` 直接跳过；不再依赖高度图；每 tick 预算 4 个区块 |
| 5 | `AutoVault` | 搜索失败后 `targets` 为空 → 每秒重搜一次 | 聊天栏每秒一条「未找到试炼密室」刷屏 | 记住「种子 + 位置」，同条件不重复搜 |
| 6 | `PacketMine` | 目标因过远/超次数被清掉时没恢复静默切换的槽位 | 客户端与服务端选中格不同步（本地显示 A，服务端认为 B） | 清目标时 `restoreSlot()` |
| 7 | `ChamberFinder` 懒加载 | `placement()` 非同步 | 极端情况下并发构造两份 placement | 加 `synchronized` |
| 8 | `ElytraRouteCalculate#loadedWorld` | 搜索线程里调 `detectWorld()`（读 `mc.getCurrentServer()`） | 后台线程碰主线程状态 | 字段改 `volatile`，后台只读缓存 |

### 检查方式
- 事件处理器签名扫描：全部 `void`、单参数（`EventBus` 只收这种，写错会静默不触发）；
- 残留物扫描：`class_*` 中介名、`new Object[0]`、`Setting#get()` 误用、`==` 比较字符串 —— 0 命中；
- `check-lang`（424 键）/ `check-registration`（3 分类 / 22 模块）双 0 error；
- 空 `catch` 26 处：均在机械移植的 `ElytraRouteCalculate` / `AutoCircleSlab` 里，与上游行为一致（结构生成那处已单独加了 `generationFailed` 标记）。

## 14. 「计算路线没修好」的真正根因（1.8.3）

### 结论先说：依赖没问题，是生命周期方法根本没被调用

**依赖核对**：`aurora-1.21.11-1.4.0(2).jar` 里 `META-INF/jars` 的 7 个 seedfinding 依赖，与
`libs/seedfinding/` 下的 7 个文件 **SHA-256 完全一致**（逐个比对过），且都已打进本插件 jar 的
`META-INF/jars` 并在 `fabric.mod.json` 的 `jars` 里声明。也就是说「依赖没移植对」这个方向可以排除：
它们不是 `fnseedc`，而是 `com.seedfinding` 的 `mc_core / mc_feature / mc_biome / mc_math /
mc_noise / mc_seed / mc_terrain`，与上游逐字节相同。

**真正的问题**：`ElytraRouteCalculate` 与 `AutoCircleSlab` 是机械转换过来的，仍然写着 Meteor 的
生命周期方法名：

```java
public void onActivate() { ... }    // Meteor 在 toggle 时调用
public void onDeactivate() { ... }
```

而 Evernight 的 `Module` 只有 `onEnable()` / `onDisable()` / `onToggle()`——**`onActivate` 不是重写，
是一个永远不会被调用的普通方法**。于是：

- `ElytraRouteCalculate` 开模块时不会执行 `ensureStateLoaded()`、不会 `detectWorld()`、
  不会读 `visited.json` / `route.json`，也不会自动探测种子——**表现就是「点了搜索也没反应/没结果」**；
- `AutoCircleSlab` 开模块时不会重置缓存、不会重建球壳——表现是「开了但什么都不画」。

因为方法签名不冲突，编译器不会报错，静态检查也发现不了，只有对照框架 API 才能看出来。

### 本次改动
| 位置 | 改动 |
| --- | --- |
| `ElytraRouteCalculate` | `onActivate/onDeactivate` → `onEnable/onDisable`（加 `@Override`） |
| `AutoCircleSlab` | `onActivate` → `onEnable` |
| `ElytraRouteCalculate` | `getInfoString()` → `getInfo()`（前者是本体的框架方法，覆写它会绕过 [xxx] 包装） |
| `ElytraRouteCalculate` | `LUJD_DIR` 由 `lujd` 改为 **`AutoElytra`**，与上游 1.4.0(2) 对齐 |
| `TiziStorage` | `TIZI_DIR` 由 `tizi` 改为 **`AutoElytra`**，与 `EndShipFinder`/`ItemFrameSearch`/`LockEnd` 一致 |
| `ElytraRouteCalculate` | 新增 `clearAuxiliaryJson()`：搜索前清空 `zuobiao.json` / `ItemFrame.json`（上游 1.4.0(2) 新增行为） |

### 目录一致性（这次顺带修掉）
上游所有坐标/进度文件都在 `AutoElytra/<世界名>/` 下：
`zuobiao.json`（末地船坐标）、`ItemFrame.json`（展示框）、`visited.json`、`route.json`。
之前本工程把 `TiziStorage` 写成 `tizi/`、计算路线写成 `lujd/`，两边对不上，等于「算出来的坐标
收集模块看不见」。现在统一到 `AutoElytra/`，也与玩家用原版插件时留下的数据兼容。
