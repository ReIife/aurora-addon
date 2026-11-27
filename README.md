# Aurora — Evernight 插件

把 Meteor Client 版 **Aurora**（`aurora-1.21.11-1.4.1.jar`，作者 n_lzy）移植到
[Evernight](https://github.com/Evernight-Client/Evernight) 插件体系的工程。

- 目标：**Evernight 1.2.4** · Minecraft 1.21.11 · Fabric Loader 0.20.1 · Java 21
- 构建：Gradle 9.8 + **Loom 1.19.4**（Evernight 1.2.4 的 jar 要求 Loom ≥ 1.19.3），**运行 Gradle 需要 JDK 25**，产物 `build/libs/aurora-addon-<version>.jar`
- 上游许可：GPL-3.0-only（Meteor Client 与 Evernight 同为 GPL-3.0，本工程沿用）；
  部分文件的行为参考了 AGPL-3.0 的 LeavesHack，逐文件说明见 [`NOTICE`](NOTICE)

## 构建

```bash
# Windows
set JAVA_HOME=E:\path\to\jdk-25
gradlew.bat build

# 语言表校验（键集/前缀）
python scripts/check-lang.py
# 注册表校验（分类名/模块名/语言键），CI 也会跑
python scripts/check-registration.py
```

首次构建会把 Evernight 发行 jar 下载到 `libs/evernight-1.0.3.jar`（也可手动放入）。
离线/镜像环境可用 `-Pevernight_jar_url=<url>`，或在 `build.gradle` 里把
`repositories` 换成可访问的镜像（本工程默认已加 BMCLAPI 与阿里云镜像）。

## 安装

把 `build/libs/aurora-addon-1.4.1.jar` 放进 `.minecraft/mods/`（与 Evernight 同目录），
启动后在 ClickGUI 的三个分类里查看：**Elytra（鞘翅）/ Production（生电辅助）/ Other（其他功能）**。
`;addons` 可确认插件已加载，模块开关与设置写入 `evernight/modules.json`。

## 模块清单与移植状态

| 原模块（Meteor） | 本工程模块名 | 分类 | 状态 |
| --- | --- | --- | --- |
| 鞘翅Pitch40飞行 | `ElytraPitch40` | Elytra | 已移植 |
| 末地自动鞘翅飞行 | `ElytraTravel` | Elytra | 已移植（末影箱补给链除外） |
| 自动收集鞘翅 | `ElytraCollect` | Elytra | 已移植 |
| 自动重启 / 寻路起飞 / 末地船查找 / 展示框查找 / 锁定起飞 / 锁定降落 / 补给 / 储存鞘翅 / 路径点锁定 | `AutoRestart` / `CollectRun` / `EndShipFinder` / `ItemFrameSearch` / `LockStart` / `LockEnd` / `StoreResupply` / `StowElytra` / `WaypointLock` | Elytra | 已移植，**ClickGUI 隐藏**（与原版一致，指令仍可用） |
| 挂机辅助 | `AfkAssist` | Production | 已移植 |
| 袭击辅助 | `RaidAssist` | Production | 已移植 |
| 方向条显示 | `BetterDirection` | Other | 已移植 |
| 发包挖掘（LeavesHack `PacketMine`） | `PacketMine` | Combat | 已移植（行为重写） |
| 挖掘显示（LeavesHack `BreakESP`） | `BreakESP` | Combat | 已移植（行为重写） |
| 自动登录（LeavesHack `AutoLogin`） | `AutoLogin` | Other | 已移植（账号表改 JSON + 指令管理） |
| 自动宝库（Fuyi `AutoVault`） | `AutoVault` | Production | **第一阶段**：按种子定位试炼密室 + 标记 + Baritone 导航（下挖/开宝库/补给未移植，见 `PORTING.md` §9） |
| 聊天扩展设置 | `ChatGuard` | Other | 部分移植（去重后的子集） |
| 计算路线 / 自动铺半砖 / 自动刷附魔书 / 自动装配kit / 聊天栏编辑 / 3C3U伪造 | — | — | 未移植，见 `PORTING.md` §4 |
| 1.4.1 新增：自动挖矿 / 自动升级工具 / 珍珠机器人 / 战斗占位 | — | — | 未移植，见 `PORTING.md` §4.8、§4.9、§6 |

详细的逐模块说明、与原版的差异、以及未移植部分的缺口，见 **[`PORTING.md`](PORTING.md)**。

## 目录结构

```
src/main/java/com/aurora/addon/
├── Aurora.java                     入口：注册分类与模块
├── modules/AuroraModule.java       模块基类（mc / inGame / 走语言表的 info-warning-error）
├── modules/elytra/                 鞘翅相关模块
├── modules/production/             生电辅助模块
└── modules/other/                  其他功能模块
src/main/resources/
├── fabric.mod.json                 入口键 evernight-addon
└── assets/aurora/lang/{en_us,zh_cn}.json
```

## 约定（移植时遵守）

- 模块名是**全局稳定键**（`modules.json` 配置键、预设键、`;<Name>` 指令都由它生成），
  CamelCase 英文，发布后不改，也不能与本体或其他插件重名。
- 玩家可见文本只存在于语言表，键统一 `addons.aurora.*`，Java 里只写键。
- 设置名 CamelCase 英文，两份语言表都要有 `addons.aurora.names.settings.<Name>`。
- 不在 Java 里写死中文/英文界面文本；不复制 Meteor 的框架（ClickGUI、配置、包管理）。
- 移植来的行为保留 GPL-3.0 与上游署名：源文件头注明「Port of <类名> from Aurora (GPL-3.0)」。

## 命令

| 命令 | 作用 |
| --- | --- |
| `;autologin` / `;al` | 列出已保存的登录信息 |
| `;al add <密码>` | 为当前服务器 + 当前用户名添加/更新密码（密码在列表里打码显示） |
| `;al remove [服务器]` | 删除当前服务器（或指定服务器）的登录信息 |
| `;al clear` | 清空全部登录信息 |

## 鸣谢

- 上游插件：Aurora（Meteor Client addon），作者 n_lzy
- 发包挖掘 / 挖掘显示 / 自动登录的行为参考：[LeavesHack](https://github.com/MrBZBZ/LeavesHack)，作者 MrBZBZ（AGPL-3.0）
- 插件框架与模板：[Evernight](https://github.com/Evernight-Client/Evernight) /
  [evernight-addon-template](https://github.com/Evernight-Client/evernight-addon-template)
