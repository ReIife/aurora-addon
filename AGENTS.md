# Aurora 插件仓库约定

1. 开发前先读 `.github/skills/evernight-addon-dev/SKILL.md`；要从 Meteor（彗星）移植模块时，
   先读 `.github/skills/meteor-to-evernight-port/SKILL.md`，并对照本仓库的 `PORTING.md`
   （逐模块状态、已做过的映射、剩余阻塞点）。
2. 玩家可见文本走语言表：`assets/aurora/lang/{en_us,zh_cn}.json` 键集一致，键用 `addons.aurora.*`，
   提交前 `python scripts/check-lang.py` 必须 0 error。
3. 模块名、指令名、配置文件是稳定标识，发布后不要改名；模块名不得与 Evernight 本体或其他插件重名。
4. 新模块继承 `modules/AuroraModule`（提供 `mc`、`inGame()`、走语言表的 `info/warning/error`），
   构造器签名统一 `(ModuleCategory category)`，并在 `Aurora#onInitialize` 里注册。
5. 移植来的文件保留 GPL-3.0 署名头（`Port of <类名> from Aurora (GPL-3.0-only, author n_lzy)`）。
6. 改完先 `gradlew.bat build`（需要 JDK 25）；`libs/*.jar` 与 `build/` 不入库。
7. 不在本环境启动游戏时，不要声称验证过游戏内行为；行为由人工验收。
