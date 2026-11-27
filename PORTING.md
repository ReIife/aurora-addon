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
