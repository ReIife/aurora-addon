# Meteor → Evernight API 映射表

以模板固定的 **Evernight 1.0.3** 为准，签名已用 `javap -classpath libs/evernight-1.0.3.jar` 核对；升级本体后请重新核对（见 `SKILL.md` §8）。
Meteor 一侧以 `MeteorDevelopment/meteor-client` 的当前 `master` 为准（`systems/modules`、`settings`、`systems/hud`、`commands`、`events`）。

## 目录

- [1. 入口与注册](#1-入口与注册)
- [2. 模块](#2-模块)
- [3. 设置](#3-设置)
- [4. 事件总线](#4-事件总线)
- [5. 文本、聊天、颜色](#5-文本聊天颜色)
- [6. HUD](#6-hud)
- [7. 指令](#7-指令)
- [8. 工具类](#8-工具类)
- [9. 渲染](#9-渲染)
- [10. 配置与持久化](#10-配置与持久化)
- [11. Meteor 内部 API 的替代](#11-meteor-内部-api-的替代)

## 1. 入口与注册

| Meteor | Evernight 插件 |
| --- | --- |
| `extends MeteorAddon` | `extends EvernightAddon` |
| `void onInitialize()` | `void onInitialize(AddonContext context)` |
| `void onRegisterCategories()` | `context.registerCategory("Name")`，返回的 `ModuleCategory` 传给模块构造器（1.0.3+；1.0.1 只能用内置分类） |
| `String getPackage()` | 不需要，入口类写在 `fabric.mod.json` |
| `void onRegisterModules()` / `onRegisterCommands()` | 全部合进 `onInitialize` |
| `MeteorClient.EVENT_BUS.subscribe(this)`（插件级辅助订阅） | `context.subscribe(this)` |
| `String getWebsite()` / `GithubRepo getRepo()` | 写进 `fabric.mod.json` 的 `contact` |

| 注册动作 | Meteor | Evernight |
| --- | --- | --- |
| 模块 | `Modules.get().add(new X())`（由 `Modules.init` 调用） | `context.registerModule(new X(category))` |
| HUD | `Hud.get().add(new HudElementInfo<>(...))` | `context.registerHudModule(new X())` |
| 指令 | `Commands.add(new X())` | `context.registerCommand(new X())` |
| 额外配置 | `Systems.add(...)` / 自定义 `System` | `context.registerConfig(JsonConfigurable)` |
| 数据目录 | `MeteorClient.FOLDER` | `context.dataDirectory()` |

失败语义：Evernight 的注册重名会抛 `IllegalStateException`，`AddonManager` 把该插件记 `FAILED`，其他插件不受影响；失败前已成功的注册**不回滚**。

## 2. 模块

| Meteor | Evernight |
| --- | --- |
| `public abstract class Module implements ISerializable<Module>` | `AddonModule extends Module`（需要本地化）或 `Module`（纯英文） |
| `Module(Category, String name, String description, String... aliases)` | `AddonModule(String addonId, String name, String description, ModuleCategory category, String... aliases)` |
| `name`：kebab-case（`item-highlight`） | `name`：CamelCase 稳定键（`ItemHighlight`），同时进配置、预设、`;<Name>` 指令 |
| `title`：`Utils.nameToTitle(name)` 自动生成 | 语言表 `addons.<id>.names.modules.<Name>` |
| `description` 字段 | 语言表 `addons.<id>.descriptions.modules.<Name>` |
| `category` 字段 | `getCategory()` |
| `isActive()` | `isToggled()` |
| `toggle()` / `enable()` / `disable()` | 同名（`enable()` 自动订阅事件，`disable()` 自动退订） |
| `onActivate()` / `onDeactivate()` | `onEnable()` / `onDisable()`；另有 `onToggle()` |
| `getInfoString()` | `getInfo()`（`null` = 不显示后缀） |
| `keybind` 字段（`Keybind`） | `getKeybind()` / `setKeybind(Keybind)`；`Keybind.UNBOUND`、`Keybind.toggle(code, mouse)` |
| `Module.color` | 无；颜色由主题/设置决定 |
| `chatFeedback` / `favorite` / `serialize` / `runInMainMenu` / `autoSubscribe` / `toggleOnBindRelease` | 无对应开关，见下方「注意事项」 |
| `settings`（`Settings`） | `getSettings()`（`List<Setting<?>>`），平铺无分组 |
| `protected final Minecraft mc` | `ClientAccess.evernightMinecraft`（或 `implements ClientAccess`） |
| `@meteordevelopment.orbit.EventHandler` | `@evernight.client.events.core.EventHandler` |

注意事项：

- **分类**：1.0.3 的 `ModuleCategory` 是 `final class`，内置 `COMBAT/MISC/RENDER/MOVEMENT/PLAYER/AUTO/EXPLOIT/CLIENT/HUD`；Meteor 的 `World` 分类在 Evernight 没有对应，按功能落到 `MISC` / `PLAYER` / `EXPLOIT`。需要独立分类时 `context.registerCategory("X")`（只能在 `onInitialize` 里、且在使用它的模块之前），名字全局唯一、发布后别改，显示名写两份语言表的 `addons.<id>.names.categories.X`。
- **描述**：1.0.3 的 `AddonModule.getDescription()` 只读语言表，构造器 `description` 不参与显示；缺键会在界面显示完整语言键。
- Meteor 的 `runInMainMenu=false` 表示「进世界才启动、离开世界自动停」。Evernight 没有这套 main-menu 托管，行为要自己在事件里判 `isWorldUnavailable()`（`ClientAccess` 的 default 方法）。
- `serialize=false` 没有等价：不想存就逐项 `setExcludedFromConfig(true)`。
- `autoSubscribe=false`（手动订阅的模块）在插件里对应 `context.subscribe(module)`，但**只有**在你确定不用 `enable()` 时；正常模块不要这么写。
- 模块名重复 → 整个插件 FAILED。移植前查重（`SKILL.md` §2）。

## 3. 设置

全部设置类在 `evernight.client.settings.types`，基类 `evernight.client.settings.core.Setting<T>`。

| Meteor | Evernight 1.0.3 | 取值 |
| --- | --- | --- |
| `BoolSetting.Builder().name("x").defaultValue(true)` | `new BooleanSetting("X", true)` | `getValue()` → `Boolean` |
| `IntSetting.Builder().name("x").defaultValue(4).range(0, 20)` | `new NumberSetting<>("X", 4, 0, 20)` | `Integer` |
| `DoubleSetting.Builder().name("x").defaultValue(1.0)` | `new NumberSetting<>("X", 1.0, 0.0, 10.0)` | `Double` |
| `LongSetting` | `new NumberSetting<>("X", 100L, 0L, 1000L)`（泛型接受 `Long`；界面按数值处理） | `Long` |
| `EnumSetting<T extends Enum<?>>` | `new EnumSetting<E extends Enum<E> & NamedEntry>("X", Mode.A)` | `E` |
| `StringSetting.Builder().name("x").defaultValue("hi")` | `new StringSetting("X", "hi")` | `String` |
| `ColorSetting`（`SettingColor`） | `new ColorSetting("X", new java.awt.Color(225, 25, 255, 50))` | `java.awt.Color` |
| `BlockListSetting` | `new RegistrySetSetting<>("X", BuiltInRegistries.BLOCK, Blocks.OBSIDIAN)` | `Set<Block>` |
| `ItemListSetting` | `new RegistrySetSetting<>("X", BuiltInRegistries.ITEM, Items.END_CRYSTAL)` | `Set<Item>` |
| `EntityTypeListSetting` | `new RegistrySetSetting<>("X", BuiltInRegistries.ENTITY_TYPE, EntityType.PLAYER)` | `Set<EntityType<?>>` |
| `StringListSetting` | 发行 jar 里没有（1.0.1 / 1.0.3 都没有）→ `new StringSetSetting("X", "a", "b")`（无序） | `Set<String>` |
| `KeybindSetting` | 无 | 见「键位」 |
| `ModuleListSetting` | 无 | 见「模块列表」 |
| `GenericSetting` / `ProvidedStringSetting` / `ProvidedStringSetting` 等 | 无 | 用 `StringSetting` + 自己的校验 |
| `BlockDataSetting` / `ColorListSetting` / `PotionSetting` / `StatusEffectListSetting` | 无 | 用 `RegistrySetSetting` 或字符串设置表达 |

常用构造器：

```java
BooleanSetting(String name, Boolean defaultValue)
BooleanSetting(String name, Boolean defaultValue, Predicate<? super Boolean> visibility)
NumberSetting(String name, N defaultValue, N min, N max)     // 有 Predicate 重载
EnumSetting(String name, E defaultValue)                     // 有 Predicate 重载
StringSetting(String name, String defaultValue)
ColorSetting(String name, java.awt.Color defaultValue)
StringSetSetting(String name, String... defaults)
RegistrySetSetting(String name, Registry<E> registry, E... defaults)
```

链式修饰：

| Meteor builder | Evernight `Setting` |
| --- | --- |
| `.visible(() -> flag.get())` | `.dependsOn(this.flagSetting)`（布尔依赖） / `.addVisibilityPredicate(v -> ...)` |
| `.onChanged(v -> handler(v))` | `.setChangeCallback(() -> handler())`（无参，值从 `getValue()` 取） |
| `.onModuleActivated(...)` | 无；在 `onEnable()` 里做 |
| `.description("...")` | 无；删掉，插件不注入 tooltip |
| `.defaultValue(...)`（builder 后置） | 构造器第二参 |
| `settings.createGroup("X")` | `BooleanSetting group = new BooleanSetting("X", false); group.markAsGroup(); this.add(group);` 子项 `.dependsOn(group)` |
| `.range(min, max)` | 构造器的 min/max 参数 |
| `.sliderMin/.sliderMax/.noSlider()` | 无（界面滑块范围由 min/max 决定） |

其他常用 API（1.0.3 已核对）：

```java
Setting<T>  getValue() / setValue(T) / getDefaultValue() / reset()
String      getName()        // 稳定标识，也是语言键后缀
String      getConfigName()  // 默认 = getName()，setConfigName 可改（发布后不可改）
Setting<T>  dependsOn(Setting<?>...)
Setting<T>  setBoundaryLabel(String, SettingLabelCondition)
Setting<T>  markAsGroup() / markVisibilityDependency()
void        setExcludedFromConfig(boolean)
void        setChangeCallback(Runnable)
```

「键位」：Meteor 的 `KeybindSetting` 让一个设置就是按键。Evernight 没有设置级键位，两种改写：

```java
// A. 模块级键位（玩家在 ClickGUI 里绑）
this.setKeybind(Keybind.toggle(GLFW.GLFW_KEY_R, false));

// B. 监听按键（需要组合键/按键瞬间行为时）
@EventHandler
private void onPress(InputPressEvent event) {
    if (event.isMouse() || event.getCode() != GLFW.GLFW_KEY_R) return;
    // ...
}
```

「模块列表」：用 `StringSetSetting` 存模块名，运行期按名字解析：

```java
ClientContext.moduleRegistry.getRegistry().stream()
        .filter(module -> module.getName().equals(name))
        .findFirst();
```

## 4. 事件总线

| Meteor | Evernight |
| --- | --- |
| `MeteorClient.EVENT_BUS` | `ClientAccess.eventBus` |
| `EVENT_BUS.post(event)` | `eventBus.post(event)`（只在主线程发） |
| `EVENT_BUS.subscribe(obj)`（普通模块由 `toggle()` 做） | 模块 `enable()` 自动；辅助对象 `context.subscribe(obj)` |
| `@EventHandler(priority = EventPriority.HIGH)` | `@EventHandler(priority = 100)`，**数值越大越先**（Meteor 的 `EventPriority` 是具名常量，别照抄） |
| `event.cancel()` | `event.cancel()`（`CancellableEvent`；是否真拦截取决于发布点是否检查） |
| `Cancellable` 接口 | `CancellableEvent` / `Cancellable` 都在 `events.core` |

派发是**按精确类型匹配**：监听 `WorldRenderEvent` 收不到 `WorldRenderEvent.Main`。父子类要各写一个处理器。

## 5. 文本、聊天、颜色

| Meteor | Evernight |
| --- | --- |
| `ChatUtils.sendMsg(...)` / `ChatUtils.info(...)` | `ChatUtils.printChat(Component, MessageSignature)`（自动加 `[Evernight]` 前缀并切主线程） |
| `ChatUtils.sendPlayerMsg("/msg ...")` | `ChatUtils.sendChatOrCommand("/msg ...")` |
| `mc.getConnection().sendChat(...)` | 同上，或 `PacketSender.send(...)` |
| `Formatting.RED` / `Text.literal` | `ChatFormatting.RED` / `Component.literal` |
| `Utils.nameToTitle("auto-eat")` | 无，显示名只来自语言表 |
| `Color(r,g,b,a)`（Meteor 自带类） | `java.awt.Color(r,g,b,a)` |
| `color.getPacked()` | `color.hashCode()`（ARGB）或 `ColorUtils.toArgb(color, alpha)` |
| `SettingColor` | `java.awt.Color`（`ColorSetting.getValue()`） |
| `Renderer2D` / `HudRenderer.text` | `GuiDrawUtils` + `FontRenderer.INSTANCE` |

常用：

```java
ChatUtils.printChat(
        Component.literal(Translations.get("addons.youraddon.somekey")),
        ChatUtils.moduleSignature(this));           // 模块签名；一次性消息用 ChatUtils.DEFAULT_SIGNATURE
```

插件可见文本一律 `Translations.get("addons.<id>.<key>")`，不要硬编码。

## 6. HUD

| Meteor | Evernight |
| --- | --- |
| `extends HudElement` | `extends HudWidgetModule`（`Module` 子类，分类固定 HUD） |
| `super(HudElementInfo)` | `super("Name")`（模块名）；`HudWidgetModule` 不是 `AddonModule`，默认显示名/描述查的是本体的 `evernight.names.*`，**必须覆写 `getDisplayName()` / `getDescription()`** 读自己的 `addons.<id>.*` |
| `Hud.get().add(info)` | `context.registerHudModule(module)` |
| `setSize(width, height)`（double） | `public float[] calculateDimensions()` 返回 `{width, height}` |
| `render(HudRenderer renderer)` | `public void renderContent(PoseStack poses)` |
| `tick(HudRenderer)` | `@EventHandler void onTick(...)` 或每帧在 `renderContent` 里算 |
| `renderer.text(...)` | `FontRenderer.INSTANCE.drawWithShadow(poses, Component/String, x, y, Color)` |
| `renderer.quad(...)` | `GuiDrawUtils.fillRect(poses, left, top, right, bottom, color)`（**边界**，不是宽高） |
| `box.xAnchor` / `autoAnchors` | `getRenderer().setAnchor(HudAnchor.X)`（真正换组用 `HudWidgetModule#setAnchor`） |
| `isInEditor()` | `isInHudEditor()` |
| `getWidth()/getHeight()` | 尺寸由渲染器缓存：`getRenderer().getDimensions()`；`getContentX/Y(width, height)` 给对齐起点 |

两种现成渲染器：

```java
// 文本型：一行或多行文字，自带宽度动画
this.setRenderer(new HudTextListRenderer(this,
        new HudTextRow(() -> Component.literal("text"), () -> true)));

// 自绘型：自己算尺寸、自己画
this.setRenderer(new ResizableHudWidgetRenderer(this));
```

颜色取 `this.getHudColor(position)`，不要写死 `Catppuccin.TEXT`。

## 7. 指令

| Meteor | Evernight |
| --- | --- |
| `extends Command` | `extends evernight.client.commands.Command` |
| `Command(String name, String description, String... aliases)` | `Command(String name)` + `setAliases(...)` |
| `build(LiteralArgumentBuilder<ClientSuggestionProvider>)` | `exec(LiteralArgumentBuilder builder)`（裸类型） |
| `argument(name, type)` / `literal(name)` | 同名静态方法（`Command.argument` / `Command.literal`） |
| `Commands.add(cmd)` | `context.registerCommand(cmd)` |
| `Config.get().prefix` | `CommandRegistry.getPrefix()` / `getPrefixCharacter()` |
| `info/infoPrefix/warning/error` | `ChatUtils.printChat(...)` |
| `REGISTRY_ACCESS`（`CommandBuildContext`） | 无；需要注册表直接用 `BuiltInRegistries` |

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
                    Component.literal(Translations.get("addons.youraddon.command.done")),
                    ChatUtils.DEFAULT_SIGNATURE);
            return 1;
        });
    }
}
```

## 8. 工具类

| Meteor | Evernight（1.0.3） |
| --- | --- |
| `mc` | `ClientAccess.evernightMinecraft` |
| `Utils.canUpdate()` | `!isWorldUnavailable()`（`ClientAccess` default） |
| `Modules.get().get(X.class)` | `ClientContext.getModule(X.class)`（构造期可能为 null，事件里现取） |
| `Modules.get().isActive(X.class)` | `ClientContext.getModule(X.class)` 判空后 `isToggled()` |
| `InvUtils.findInHotbar(item)` | `InventoryUtils.findHotbarSlot(item)` |
| `InvUtils.findInHotbar(predicate)` | `InventoryUtils.findHotbarSlot(Predicate<ItemStack>)` |
| `InvUtils.find(item)` | `InventoryUtils.findInventorySlot(...)` / `findMenuSlot(...)` |
| `InvUtils.swap(slot, false)` | `InventoryUtils.selectHotbarSlot(slot)` |
| `InvUtils.quickSwap(...)` | `InventoryUtils.swapIntoSelectedSlot(slot)` / `swapWithHotbarSlot(a, b)` / `runWithSwap(...)` |
| `InvUtils.clickSlot(...)` | `InventoryUtils.clickSlot(slotId, button, ClickType)` |
| `PlayerUtils.isInHole(true)` | `ClientContext.holeManager.isInHole()` |
| `PlayerUtils.getTotalHealth()` | `EntityUtils.getPlayerHealthWithAbsorption()` / `getHealthWithAbsorption(entity)` |
| `PlayerUtils.distanceTo(...)` | `mc.player.distanceTo(e)` / `WorldUtils.getEntityDistance(e, true)` |
| `BlockUtils.getBlock(pos)` | `WorldUtils.getBlock(pos)` 或 `mc.level.getBlockState(pos)` |
| `BlockUtils.hasCollision` / `isReplaceable` | `WorldUtils.hasCollision(pos)` / `WorldUtils.isReplaceable(pos)` |
| `BlockUtils.place(...)` | 移植到 `BlockInteractionUtils` / 本体 `PlacementModule`（插件通常直接发包，见下） |
| `Rotations.rotate(yaw, pitch, prio, action)` | `ClientContext.rotationManager.requestRotation(yaw, pitch, prio, true)` + `executeAction(action)` |
| `Rotations.getYaw(target)` | `RotationManager.calculateRotations(from, to)` / `getRotationsToBlock(pos)` |
| `mc.getConnection().send(packet)` | `PacketSender.send(packet)`（`sendDirect` 绕过排队） |
| `mc.gameMode.startPrediction(...)` | `PacketSender.nextPredictionSequence()` + `PacketSender.sendPlayerAction(action, pos, dir)` |
| `mc.player.connection.sendCommand(...)` | `ChatUtils.sendChatOrCommand("/...")` |
| `Friends.get().isFriend(player)` | `ClientContext.socialRegistry.isFriendPlayer(player)` |
| `Friends.get().isFriend(name)` | `ClientContext.socialRegistry.isFriendName(name)` |
| `Systems.get(...)` / `Config.get()` | 无插件等价；插件不该读本体配置 |

## 9. 渲染

| Meteor | Evernight |
| --- | --- |
| `Render3DEvent` | `WorldRenderEvent.Main`（另有 `.BeforeDepthClear` / `.Overlay` / `.BeforeBufferSource`，按需选） |
| `event.matrices` | `event.getPoseStack()` |
| `event.tickDelta` | `event.getPartialTick()` |
| `event.offsetX/Y/Z` | 无（坐标系直接用世界坐标，渲染器内部减相机） |
| `Renderer3D.box(...)` | `WorldBoxRenderer.fillBox(poses, AABB/BlockPos, Color)` |
| `Renderer3D.blockOutline(...)` | `WorldBoxRenderer.outlineBox(poses, AABB, Color, lineWidth)` |
| `Renderer3D.line(...)` | `LineMeshRenderer.begin(poses, width)` + `addVertex/addLine` + `draw()` |
| `RenderUtils.drawBox` 等 | 同上（`WorldBoxRenderer`） |
| 世界空间文字 | `RenderUtils.INSTANCE.drawWorldText(poses, text, Vec3, scale, Color, shadow)` |
| `Render2DEvent` | `HudRenderEvent`（`getPoseStack()` / `getGuiGraphics()` / `getPartialTick()`） |
| `Renderer2D.quad` | `GuiDrawUtils.fillRect(poses, left, top, right, bottom, argb)` |
| `Renderer2D.line` | `GuiDrawUtils.drawLineRect` / `LineMeshRenderer` |
| `RenderUtils.renderItem` | `GuiDrawUtils.drawItem(poses, stack, x, y, z)` |
| 字体宽度 | `FontRenderer.INSTANCE.getWidth(text)` / `getHeight()` |
| `Color.getPacked()` | `color.hashCode()` |

提交：`WorldBoxRenderer` 在 `WorldRenderEvent.Main` 末尾自动 flush；`GuiDrawUtils` 写的矩形/线段要 `GuiDrawUtils.flush()`（`FontRenderer` 也由它 flush）。

```java
@EventHandler
private void onWorldRender(WorldRenderEvent.Main event) {
    PoseStack poses = event.getPoseStack();
    WorldBoxRenderer.fillBox(poses, box, this.fillColor.getValue());
    WorldBoxRenderer.outlineBox(poses, box, this.outlineColor.getValue(), 1.0F);
}
```

## 10. 配置与持久化

| Meteor | Evernight |
| --- | --- |
| `Module.toTag()` / `fromTag()`（NBT，`modules.nbt`） | 自动 JSON（`evernight/modules.json`），不用写 |
| `HudElement.toTag()`（`hud.nbt`） | 自动（`evernight/hud.json`） |
| `System<T>`（自定义 NBT 文件） | `JsonConfigurable` + `context.registerConfig(...)`（`evernight/<getConfigName()>`） |
| `MeteorClient.FOLDER` | 配置根 `<gameDir>/evernight`；插件数据 `context.dataDirectory()` |
| 设置不想保存 | `setting.setExcludedFromConfig(true)` |

`JsonConfigurable` 契约：`JsonElement toJson()`、`void fromJson(JsonElement)`、`String getConfigName()`（必须覆写，建议 `<id>.json`）。

## 11. Meteor 内部 API 的替代

| Meteor 内部 | 为什么不能用 | 插件里的做法 |
| --- | --- | --- |
| `MeteorClient.mc` | Meteor 的类不在运行环境 | `ClientAccess.evernightMinecraft` |
| `MeteorClient.EVENT_BUS` | 同上 | `ClientAccess.eventBus` |
| `MeteorClient.ADDON` | 同上 | `AddonContext#metadata()` |
| `Modules.get()` / `Systems.get()` / `Hud.get()` | 注册表内部结构，本体不保证稳定 | `ClientContext.moduleRegistry`、`context.register*` |
| `meteordevelopment.orbit.*` | 不随 Evernight 分发 | `evernight.client.events.*` |
| Meteor 自己的 `mixin/` | 不在运行环境 | 插件自带 mixin（`fabric.mod.json` 声明 `mixins`） |
| `meteordevelopment.meteorclient.utils.render.color.Color` | 同上 | `java.awt.Color` / ARGB int |
| `meteordevelopment.meteorclient.utils.misc.Keybind` | 同上 | `evernight.client.modules.Keybind` |
| Baritone 集成（`BaritoneUtils`） | Evernight 有 `ClientContext.baritoneBridge`，未装时为 `DisabledBaritoneBridge` | 先 `isAvailable()`，设置项用 `hideWithoutBaritone()` |
