# Meteor → Evernight 事件对照

Evernight 的事件不是 Meteor 那套「手写事件类 + 到处 post」，而是**从 mixin 注入点发布**的，所以很多 Meteor 事件在 Evernight 没有一字不差对应。按三档处理：

1. **有等价**：换类名 + 取值方法（表里已给出）。
2. **时机接近**：换事件后补判断（注意下表的「Pre/Post 语义」）。
3. **没有等价**：不要硬凑。用 tick 轮询 + 记录上一帧状态做 diff，或在你自己的插件里写 mixin（见文末）。

## 1. 客户端 / 玩家

| Meteor | Evernight | 备注 |
| --- | --- | --- |
| `TickEvent.Pre` | `MinecraftTickEvent` | 每客户端 tick 一次；同一处理器里做「tick 开头」的逻辑 |
| `TickEvent.Post` | `MinecraftTickEvent` / `MinecraftRunTickEvent` | 需要「本 tick 收尾」时用 `MinecraftRunTickEvent` |
| `PlayerTickMovementEvent` | `LocalPlayerPreTickEvent` / `LocalPlayerAiStepEvent` | 玩家 tick 前置 / AI 步进 |
| `PlayerMoveEvent` | `LocalPlayerMoveEvent` | `getVelocity()/setVelocity(Vec3)`、`setHorizontalVelocity(x,z)` |
| `SendMovementPacketsEvent` | `LocalPlayerSendPositionEvent` | 有 `getStage()`（发送前后）与 x/y/z/yaw/pitch 的 get/set |
| `AttackEntityEvent` | `PlayerAttackEvent` | `getStage()` 判断 Pre/Post，`getTarget()` |
| `DoAttackEvent` | `PlayerAttackEvent` | 用 Pre 阶段 |
| `DoItemUseEvent` / `InteractItemEvent` | `MultiPlayerGameModeUseItemEvent` | `getHand()` |
| `InteractBlockEvent` / `PlaceBlockEvent` | `MultiPlayerGameModeUseItemOnEvent` | `getHitResult()` / `getHand()`，放置与右键方块共用 |
| `InteractEntityEvent` | — | 无等价；用 `PlayerAttackEvent` 近似，或自己 mixin `MultiPlayerGameMode#interact` |
| `StartBreakingBlockEvent` | `MultiPlayerGameModeStartDestroyBlockEvent` | `getPos()` / `getDirection()` |
| `BreakBlockEvent` | `MultiPlayerGameModeDestroyBlockEvent` | `getPos()` |
| `FinishUsingItemEvent` | `ItemUseEvent.BeforeFinish` | |
| `StoppedUsingItemEvent` | `MultiPlayerGameModeReleaseUsingItemEvent` | |
| `BlockBreakingCooldownEvent` | — | 无；在 tick 里自己算 |
| `JumpVelocityMultiplierEvent` | `LivingEntityJumpFromGroundEvent` | 改 `jumpPower` |
| `CanWalkOnFluidEvent` / `ClipAtLedgeEvent` | — | 无 |
| `PickItemsEvent` / `DropItemsEvent` | — | 无（用 `PacketEvent` 或 tick 轮询） |
| `BoatMoveEvent` | `VehicleMoveEvent` | 只覆盖玩家载具 |
| `KeyInputEvent` / `MouseClickEvent` | `InputPressEvent` | `getCode()` / `isMouse()` |
| `MouseScrollEvent` | `MouseHandlerOnScrollEvent` | `getHorizontalOffset()` / `getVerticalOffset()` |
| `CharTypedEvent` | `KeyboardHandlerCharTypedEvent` | |

## 2. 世界 / 实体

| Meteor | Evernight | 备注 |
| --- | --- | --- |
| `EntityAddedEvent` | `ClientLevelAddEntityEvent` | `getEntity()` |
| `EntityRemovedEvent` | `ClientLevelRemoveEntityEvent` | `getId()` / `isEntityPresent()` |
| `EntityDestroyEvent` | — | 用 `ClientLevelRemoveEntityEvent` + 自己过滤 |
| `EntityMoveEvent` | — | 无；tick 里 diff 位置（见 §4） |
| `ChunkDataEvent` | `ClientChunkCacheReplaceWithPacketDataEvent` | `getChunk()` |
| `BlockUpdateEvent` | `BlockUpdateAppliedEvent` | 无字段，只知道「有一次方块更新」 |
| `CollisionShapeEvent` | `BlockCollisionShapeEvent` | `shape` / `pos` / `state` |
| `ParticleEvent` | `ParticleEngineAddEvent` | `getParticle()/setParticle(...)` |
| `PlaySoundEvent` | `SoundEnginePlayEvent` | `getSoundInstance()` |
| `BlockActivateEvent` | — | 无（用 `MultiPlayerGameModeUseItemOnEvent` 近似） |
| `AmbientOcclusionEvent` / `ChunkOcclusionEvent` | — | 无 |
| `ServerConnectBeginEvent` / `ServerConnectEndEvent` | `ConnectScreenConnectEvent` | 只在点「连接」时；`getServerAddress()` |

## 3. 网络 / 聊天 / 界面 / 模块状态

| Meteor | Evernight | 备注 |
| --- | --- | --- |
| `PacketEvent.Send` | `PacketEvent.Send` | `getPacket()`；`cancel()` 真正阻断发送 |
| `PacketEvent.Sent` | `PacketEvent.PostSend` | 发送后 |
| `PacketEvent.Receive` | `PacketEvent.Receive` | `cancel()` 真正阻断接收 |
| `ReceiveMessageEvent` | `ChatMessageEvent` | `getStage()`、`getMessage()/setMessage()` |
| `SendMessageEvent` | `ClientPacketListenerSendChatEvent` | |
| `InventoryEvent` / `ContainerSlotUpdateEvent` | `SlotSetEvent` / `InventoryActionEvent` / `SelectedSlotChangeEvent` | 按关注点选：槽位内容 / 点击阶段 / 选中槽位 |
| `PlaySoundPacketEvent` | — | `PacketEvent.Receive` + 判 `ClientboundSoundPacket` |
| `OpenScreenEvent` | `ScreenChangeEvent.Before` | `getPreviousScreen()` / `getNewScreen()` |
| `ItemStackTooltipEvent` | `AbstractContainerScreenRenderTooltipEvent` | `getTooltipComponents()/setTooltipComponents(...)` |
| `TooltipDataEvent` | — | 无 |
| `GameJoinedEvent` / `GameLeftEvent` | — / `MinecraftDisconnectEvent` | 进服无专用事件：用 `ConnectScreenConnectEvent` + 判 `isWorldUnavailable()` |
| `ActiveModulesChangedEvent` | `ModuleToggleEvent` | `module()` |
| `ModuleBindChangedEvent` | — | 无 |
| `ResolutionChangedEvent` / `ResourcePacksReloadedEvent` / `CustomFontChangedEvent` | — | 无 |
| `ChangePerspectiveEvent` | — | 无（`CameraSetupEvent` 只能改相机位置） |
| `GetFovEvent` | — | 无 |

## 4. 渲染

| Meteor | Evernight | 备注 |
| --- | --- | --- |
| `Render3DEvent` | `WorldRenderEvent.Main` | 世界方框/线段/文字，`getPoseStack()` + `getPartialTick()` |
| `RenderAfterWorldEvent` | `WorldRenderEvent.Overlay` | 世界之后的叠加层 |
| `Render2DEvent` | `HudRenderEvent` | `getPoseStack()` / `getGuiGraphics()` / `getPartialTick()` |
| `RenderBlockEntityEvent` / `RenderItemEntityEvent` | `EntityRenderEvent.Pre/Post` | 按 `getEntity()` 类型过滤 |
| `NametagEvent` | `NameTagRenderEvent` | `getEntity()` / `getPoseStack()` |
| `ArmRenderEvent` / `HeldItemRendererEvent` | — | 无（本体 `ViewModel` / `NoRender` 内部处理） |
| `ApplyTransformationEvent` / `RenderBossBarEvent` | — | 无 |

**Pre/Post 语义**：Meteor 的 `Render3DEvent` 是「世界渲染中一次」，Evernight 的 `WorldRenderEvent` 有 4 个阶段：

```
BeforeDepthClear → Main → Overlay          // 世界渲染主流程
BeforeBufferSource                          // 需要自己的 buffer 时
```

普通方框/线段画在 `.Main` 即可；要求被深度遮挡就自己在 `.BeforeBufferSource` 里用 `MeshBuffer`。

## 5. 三个必须知道的坑

1. **按精确类型派发**：监听 `WorldRenderEvent` 收不到 `WorldRenderEvent.Main`，父子类各写一个处理器。
2. **渲染事件是复用单例**：`WorldRenderEvent.Main.create(...)`、`HudRenderEvent.create(...)` 复用同一实例。**不要把 event 存进字段**，需要延迟用就把数据拷出来。
3. **`cancel()` 不一定真拦截**：是否生效取决于发布点是否检查 `isCancelled()`。改包/改输入用 `PacketEvent.Send/Receive` 比较可靠；其它事件先验证行为。

## 6. 无等价事件时的替代写法

### 6.1 tick 轮询 + diff（代替 `EntityMoveEvent`）

```java
private final Map<Integer, Vec3> lastPositions = new HashMap<>();

@EventHandler
private void onTick(MinecraftTickEvent event) {
    if (this.isWorldUnavailable()) return;
    for (Entity entity : ClientAccess.evernightMinecraft.level.entitiesForRendering()) {
        Vec3 previous = this.lastPositions.put(entity.getId(), entity.position());
        if (previous == null || previous.distanceToSqr(entity.position()) < 1.0E-4) continue;
        // 这里就是「实体移动了」的回调
    }
}
```

轮询要克制：只在模块启用时跑（模块的 `@EventHandler` 天然只在启用期间触发），别每帧做全实体遍历。

### 6.2 自带 mixin（代替只有注入点才能拿到的信息）

插件可以用自己的 mixin，本体不代管：

`src/main/resources/fabric.mod.json`

```json
"mixins": ["youraddon.mixins.json"]
```

`src/main/resources/youraddon.mixins.json`

```json
{
  "required": true,
  "package": "com.example.youraddon.mixin",
  "compatibilityLevel": "JAVA_21",
  "client": ["GameRendererMixin"],
  "injectors": { "defaultRequire": 1 }
}
```

```java
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void youraddon$afterRender(CallbackInfo info) {
        SomeModule module = ClientContext.getModule(SomeModule.class);
        if (module == null || !module.isToggled()) return;
        module.onFrame();
    }
}
```

要点：

- 映射与 Meteor 一致用 Mojang 官方映射（`net.minecraft.client.Minecraft` 等），Meteor 的 mixin 通常可以整体搬过来。
- mixin 里**不要缓存模块实例**（构造期可能为 null），每次现取并判空。
- 只改「自己看到的效果」时在渲染期判断，不要改服务器同步状态（参考本体 `AvatarMixin` 的写法）。
- `@Inject` 的方法名/描述符依赖 MC 版本，升级本体或 MC 后要重新核对。
