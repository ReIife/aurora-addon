/*
 * Behaviour ported from Aurora (GPL-3.0-only, author n_lzy) — `AutoCircleSlab.java`.
 * This file is distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.modules.production;

import com.aurora.addon.modules.AuroraModule;
import com.aurora.addon.utils.RenderMode;
import evernight.client.events.core.EventHandler;
import evernight.client.events.network.PacketEvent;
import evernight.client.events.render.WorldRenderEvent;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.mesh.WorldBoxRenderer;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.NamedEntry;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.ColorSetting;
import evernight.client.settings.types.EnumSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.settings.types.RegistrySetSetting;
import it.unimi.dsi.fastutil.longs.Long2IntMap.Entry;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 自动铺半砖：渲染球形区域，自动给区域内「能刷怪」的方块上方铺半砖/地毯/铁轨。
 *
 * <p>行为移植自 Aurora 的 {@code AutoCircleSlab}。逻辑（球形壳层重建、可刷怪方块判定、
 * 射线可达性检查、三种放置模式、发包队列与旋转还原）逐行保留，替换的只有框架层：
 * <ul>
 *     <li>{@code BlockPosSetting} → 三个 {@code NumberSetting}（OriginX/Y/Z）；</li>
 *     <li>{@code BlockListSetting} → {@code RegistrySetSetting<Block>}；</li>
 *     <li>{@code Renderer3D}/{@code ShapeMode} → {@link WorldBoxRenderer} + {@link RenderMode}；</li>
 *     <li>{@code PacketEvent.Send} → 本体同名事件（{@code getPacket()}）。</li>
 * </ul>
 *
 * <p>已知差异：上游渲染时按 {@code excludeDir} 位掩码跳过与相邻方块贴合的面，Evernight 的
 * {@link WorldBoxRenderer} 没有该参数，这里统一画整面（视觉上多一层内壁，不影响放置逻辑）。
 */
public final class AutoCircleSlab extends AuroraModule {
    private final Setting<Integer> originX = this.add(new NumberSetting<>("OriginX", 0, -30000000, 30000000));
    private final Setting<Integer> originY = this.add(new NumberSetting<>("OriginY", 64, -64, 320));
    private final Setting<Integer> originZ = this.add(new NumberSetting<>("OriginZ", 0, -30000000, 30000000));
    private final Setting<Integer> radius = this.add(new NumberSetting<>("Radius", 128, 1, 256));

    private final Setting<Boolean> render = this.add(new BooleanSetting("Render", true));
    private final Setting<Color> shellColor = this.add(new ColorSetting("ShellColor", new Color(0, 255, 200, 200)));
    private final Setting<Boolean> renderSpawnable = this.add(new BooleanSetting("RenderSpawnable", true));
    private final Setting<Color> spawnableColor = this.add(new ColorSetting("SpawnableColor", new Color(255, 200, 0, 255)));
    private final Setting<Double> spawnableThickness = this.add(new NumberSetting<>("SpawnableThickness", 0.05, 0.01, 0.5));

    private final Setting<Boolean> placeEnabled = this.add(new BooleanSetting("PlaceEnabled", false));
    private final Setting<PlaceMode> placeMode = this.add(new EnumSetting<>("PlaceMode", PlaceMode.MOVEMENT_POST));
    private final Setting<Set<Block>> blockWhitelist = this.add(new RegistrySetSetting<>("BlockWhitelist",
            BuiltInRegistries.BLOCK,
            Blocks.WHITE_CARPET,
            Blocks.ORANGE_CARPET,
            Blocks.MAGENTA_CARPET,
            Blocks.LIGHT_BLUE_CARPET,
            Blocks.YELLOW_CARPET,
            Blocks.LIME_CARPET,
            Blocks.PINK_CARPET,
            Blocks.GRAY_CARPET,
            Blocks.LIGHT_GRAY_CARPET,
            Blocks.CYAN_CARPET,
            Blocks.PURPLE_CARPET,
            Blocks.BLUE_CARPET,
            Blocks.BROWN_CARPET,
            Blocks.GREEN_CARPET,
            Blocks.RED_CARPET,
            Blocks.BLACK_CARPET,
            Blocks.RAIL,
            Blocks.POWERED_RAIL,
            Blocks.DETECTOR_RAIL,
            Blocks.ACTIVATOR_RAIL,
            Blocks.STONE_SLAB,
            Blocks.COBBLESTONE_SLAB,
            Blocks.SMOOTH_STONE_SLAB,
            Blocks.OAK_SLAB,
            Blocks.SPRUCE_SLAB,
            Blocks.BIRCH_SLAB,
            Blocks.DEEPSLATE_BRICK_SLAB,
            Blocks.SANDSTONE_SLAB,
            Blocks.MUD_BRICK_SLAB));
    private final Setting<Integer> placeDelay = this.add(new NumberSetting<>("PlaceDelay", 1, 1, 20));
    private final Setting<Integer> maxSpawnableRadius = this.add(new NumberSetting<>("MaxSpawnableRadius", 128, 16, 512));
    private final Setting<Integer> rebuildInterval = this.add(new NumberSetting<>("RebuildInterval", 0, 0, 200));
    private final Setting<Double> reachDistance = this.add(new NumberSetting<>("ReachDistance", 4.5, 3.0, 6.0));
    private final Setting<Boolean> swingHand = this.add(new BooleanSetting("SwingHand", true));

    /** 统一的方框绘制。 */
    private static void box(WorldRenderEvent.Main event, double x1, double y1, double z1,
                            double x2, double y2, double z2, Color side, Color line, RenderMode mode) {
        AABB aabb = new AABB(x1, y1, z1, x2, y2, z2);
        if (mode != RenderMode.Lines) {
            WorldBoxRenderer.fillBox(event.getPoseStack(), aabb, side);
        }
        if (mode != RenderMode.Sides) {
            WorldBoxRenderer.outlineBox(event.getPoseStack(), aabb, line, 1.0F);
        }
    }

   private float lastYaw = 0.0F;
   private float lastPitch = 0.0F;
   private Vec2 pendingRotation = null;
   private boolean currentInQueue = false;
   private long queueTick = 0L;
   private final List<Packet<?>> queuedPackets = new ArrayList<>();
   private AutoCircleSlab.PendingPostPlace pendingPostPlace = null;
   private float savedPitch = 0.0F;
   private float savedYaw = 0.0F;
   private boolean rotationSaved = false;
   private final Map<BlockPos, Long> pendingPlaces = new HashMap<>();
   private static final int PENDING_TIMEOUT_TICKS = 10;
   private int placeTimer = 0;
   private boolean lastAdd = false;
   private final ArrayList<AutoCircleSlab.CachedBlock> cachedBlocks = new ArrayList<>();
   private final ArrayList<AutoCircleSlab.SpawnableBlock> cachedSpawnable = new ArrayList<>();
   private boolean cacheDirty = true;
   private long lastRebuildTick = 0L;
   private boolean lastPlayerInside = false;
   private double lastOx;
   private double lastOy;
   private double lastOz;
   private double lastR;
   private final double[] cx = new double[1];
   private final double[] cy = new double[1];
   private final double[] cz = new double[1];

    public AutoCircleSlab(ModuleCategory category) {
        super("AutoCircleSlab", "Renders a spherical area and places slabs under every block that can spawn mobs.", category);
    }

   public void onActivate() {
      this.cacheDirty = true;
      this.placeTimer = 0;
      this.pendingPlaces.clear();
      this.pendingRotation = null;
      this.pendingPostPlace = null;
      this.queuedPackets.clear();
      this.currentInQueue = false;
      this.queueTick = 0L;
      this.rotationSaved = false;
      this.savedPitch = 0.0F;
      this.savedYaw = 0.0F;
      if (mc.player != null) {
         this.lastYaw = mc.player.getYRot();
         this.lastPitch = mc.player.getXRot();
      }
   }

   @EventHandler(
      priority = -200
   )
   private void onPacketSend(PacketEvent.Send event) {
      if (!event.isCancelled()) {
         if (event.getPacket() instanceof ServerboundMovePlayerPacket packet) {
            if (packet.hasRotation()) {
               this.lastYaw = packet.getYRot(this.lastYaw);
               this.lastPitch = packet.getXRot(this.lastPitch);
            }
         } else {
            if (this.currentInQueue) {
               event.cancel();
               this.queuedPackets.add(event.getPacket());
            }
         }
      }
   }

   @EventHandler
   private void onTickPre(MinecraftTickEvent event) {
      if (mc.level != null && mc.player != null) {
         long now = mc.level.getGameTime();
         if (this.currentInQueue && this.queueTick < now - 5L) {
            this.currentInQueue = false;
            this.queuedPackets.clear();
         }

         if (this.placeEnabled.getValue() && !this.cachedSpawnable.isEmpty() && ++this.placeTimer >= this.placeDelay.getValue()) {
            this.placeTimer = 0;
            this.tryPlaceOne();
         }
      }
   }

   @EventHandler
   private void onTickPost(MinecraftTickEvent event) {
      if (mc.level != null && mc.player != null) {
         if (this.pendingPostPlace != null) {
            AutoCircleSlab.PendingPostPlace p = this.pendingPostPlace;
            this.pendingPostPlace = null;
            this.interactBlock(p.hand, p.hit, p.swing);
         }

         if (this.currentInQueue) {
            this.flushOutBound();
         }

         this.pendingRotation = null;
         if (this.render.getValue() || this.placeEnabled.getValue()) {
            long now = mc.level.getGameTime();
            if (now % 20L == 0L) {
               this.pendingPlaces.entrySet().removeIf(e -> now - e.getValue() > 10L);
            }

            BlockPos op = new BlockPos(this.originX.getValue(), this.originY.getValue(), this.originZ.getValue());
            double ox = op.getX() + 0.5;
            double oy = op.getY() + 0.5;
            double oz = op.getZ() + 0.5;
            double r = this.radius.getValue();
            Vec3 eye = mc.player.getEyePosition();
            double dx = eye.x - ox;
            double dy = eye.y - oy;
            double dz = eye.z - oz;
            boolean nowInside = dx * dx + dy * dy + dz * dz < r * r;
            boolean needRebuild = this.cacheDirty
               || nowInside != this.lastPlayerInside
               || ox != this.lastOx
               || oy != this.lastOy
               || oz != this.lastOz
               || r != this.lastR;
            if (!needRebuild && this.rebuildInterval.getValue() > 0 && now - this.lastRebuildTick >= this.rebuildInterval.getValue()) {
               needRebuild = true;
            }

            if (needRebuild) {
               this.lastOx = ox;
               this.lastOy = oy;
               this.lastOz = oz;
               this.lastR = r;
               this.lastPlayerInside = nowInside;
               this.lastRebuildTick = now;
               this.cacheDirty = false;
               this.rebuildCache(ox, oy, oz, r, nowInside);
            }

            this.pendingRotation = null;
            if (this.rotationSaved) {
               this.rotationSaved = false;
               setEntityPitchSafe(mc.player, this.savedPitch);
               setPlayerYawSafe(mc.player, this.savedYaw);
            }
         }
      }
   }

   private void tryPlaceOne() {
      Vec3 eye = mc.player.getEyePosition();
      BlockPos bestTarget = null;
      BlockHitResult bestHit = null;
      double bestDistSq = Double.MAX_VALUE;
      Iterator<AutoCircleSlab.SpawnableBlock> iter = this.cachedSpawnable.iterator();

      while (iter.hasNext()) {
         AutoCircleSlab.SpawnableBlock sb = iter.next();
         BlockPos target = new BlockPos(sb.x, sb.y + 1, sb.z);
         BlockState cur = mc.level.getBlockState(target);
         if (!cur.isAir() && !cur.canBeReplaced()) {
            iter.remove();
         } else {
            Long pendingTime = this.pendingPlaces.get(target);
            if (pendingTime == null || mc.level.getGameTime() - pendingTime >= 10L) {
               BlockHitResult hit = this.findPlaceHit(target);
               if (hit != null && !(eye.distanceTo(hit.getLocation()) > this.reachDistance.getValue())) {
                  double d = eye.distanceToSqr(target.getCenter());
                  if (d < bestDistSq) {
                     bestDistSq = d;
                     bestTarget = target;
                     bestHit = hit;
                  }
               }
            }
         }
      }

      if (bestHit != null && bestTarget != null) {
         if (this.doPlace(bestTarget, bestHit)) {
            this.pendingPlaces.put(bestTarget, mc.level.getGameTime());
         }
      }
   }

   private boolean doPlace(BlockPos pos, BlockHitResult hit) {
      Item item = this.findAnyWhitelistedItem();
      if (item == null) {
         return false;
      } else {
         return !this.ensureItemInMainHand(item) ? false : this.handlePlaceMode(hit, InteractionHand.MAIN_HAND, this.swingHand.getValue());
      }
   }

   private boolean handlePlaceMode(BlockHitResult result, InteractionHand hand, boolean swing) {
      boolean directlyHit = this.canDirectHit(result);
      switch ((AutoCircleSlab.PlaceMode)this.placeMode.getValue()) {
         case NONE:
            this.interactBlock(hand, result, swing);
            return true;
         case DELAY_MOVEMENT:
            if (!directlyHit) {
               Vec2 rotation = this.computeBlockRotation(result.getBlockPos());
               if (rotation == null) {
                  return false;
               }

               this.pendingRotation = rotation;
               this.savedPitch = mc.player.getXRot();
               this.savedYaw = mc.player.getYRot();
               this.rotationSaved = true;
               setEntityPitchSafe(mc.player, rotation.x);
               setPlayerYawSafe(mc.player, rotation.y);
               this.startQueuePacket();
            }

            this.interactBlock(hand, result, swing);
            return true;
         case MOVEMENT_POST:
            if (!directlyHit) {
               Vec2 rotation = this.computeBlockRotation(result.getBlockPos());
               if (rotation == null) {
                  return false;
               }

               this.pendingRotation = rotation;
               this.savedPitch = mc.player.getXRot();
               this.savedYaw = mc.player.getYRot();
               this.rotationSaved = true;
               setEntityPitchSafe(mc.player, rotation.x);
               setPlayerYawSafe(mc.player, rotation.y);
               this.pendingPostPlace = new AutoCircleSlab.PendingPostPlace(result, hand, swing);
               return false;
            }

            this.interactBlock(hand, result, swing);
            return true;
         default:
            throw new IllegalStateException("Unhandled mode: " + this.placeMode.getValue());
      }
   }

   private InteractionResult interactBlock(InteractionHand hand, BlockHitResult result, boolean swing) {
      Vec2 storePY = new Vec2(mc.player.getXRot(), mc.player.getYRot());
      mc.player.setXRot(this.lastPitch);
      mc.player.setYRot(this.lastYaw);
      InteractionResult actionResult = mc.gameMode.useItemOn(mc.player, hand, result);
      mc.player.setXRot(storePY.x);
      mc.player.setYRot(storePY.y);
      if (swing && actionResult.consumesAction()) {
         mc.player.swing(hand);
      }

      return actionResult;
   }

   private void startQueuePacket() {
      this.queueTick = mc.level.getGameTime();
      this.currentInQueue = true;
   }

   private void flushOutBound() {
      this.currentInQueue = false;
      if (mc.getConnection() == null) {
         this.queuedPackets.clear();
      } else {
         List<Packet<?>> toSend = new ArrayList<>(this.queuedPackets);
         this.queuedPackets.clear();

         for (Packet<?> packet : toSend) {
            mc.getConnection().send(packet);
         }
      }
   }

   private boolean canDirectHit(BlockHitResult result) {
      return canRaycastHit(mc.player, this.lastPitch, this.lastYaw, result.getBlockPos(), this.getBlockReachDistance());
   }

   private static boolean canRaycastHit(Player player, float pitch, float yaw, BlockPos pos, double distance) {
      Vec3 vec3d = player.getEyePosition();
      Vec3 look = pitchYawToRotation(pitch, yaw);
      Vec3 raycast = look.normalize().scale(distance);
      AABB targetBox = new AABB(pos);
      return targetBox.clip(vec3d, vec3d.add(raycast)).isPresent();
   }

   private double getBlockReachDistance() {
      return mc.player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE) + (mc.player.isPassenger() ? 1.0 : 0.0);
   }

   private Vec2 computeBlockRotation(BlockPos pos) {
      Vec3 feet = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());
      AABB box = new AABB(pos);
      double range = this.getBlockReachDistance();
      Vec3 fallbackEye = feet.add(0.0, mc.player.getEyeHeight(mc.player.getPose()), 0.0);
      Vec3 ray = this.getPotentialEyeHeights(feet).filter(eyePos -> box.distanceToSqr(eyePos) <= range * range).map(eyePos -> {
         Vec3 r = raycastDirection(box, eyePos, range);
         return box.clip(eyePos, eyePos.add(r.scale(range))).isPresent() ? r : null;
      }).filter(Objects::nonNull).findFirst().orElseGet(() -> raycastDirection(box, fallbackEye, range));
      Vec2 direct = rotationToPitchYaw(ray.normalize());
      return this.fluctuateBlockRaycast(pos, feet, direct);
   }

   private Stream<Vec3> getPotentialEyeHeights(Vec3 pos) {
      return Stream.of(pos.add(0.0, mc.player.getEyeHeight(mc.player.getPose()), 0.0));
   }

   private static Vec3 raycastDirection(AABB box, Vec3 eyePos, double range) {
      Vec3 centerDirection = box.getCenter().subtract(eyePos).normalize();
      double raycastDistance = Math.max(0.0, range - 0.09178);
      if (box.clip(eyePos, eyePos.add(centerDirection.scale(raycastDistance))).isPresent()) {
         return centerDirection;
      } else {
         Vec3 point = magnitudePoint(box.inflate(-1.0E-7), eyePos);
         return point.subtract(eyePos).normalize();
      }
   }

   private static Vec3 magnitudePoint(AABB box, Vec3 point) {
      double x = Mth.clamp(point.x, box.minX, box.maxX);
      double y = Mth.clamp(point.y, box.minY, box.maxY);
      double z = Mth.clamp(point.z, box.minZ, box.maxZ);
      return new Vec3(x, y, z);
   }

   private Vec2 fluctuateBlockRaycast(BlockPos pos, Vec3 feet, Vec2 direct) {
      if (pos == null) {
         return direct;
      } else {
         float pitch = quantize(direct.x);
         float yaw = quantize(direct.y);
         Vec2 candidate = new Vec2(pitch, yaw);
         if (!this.canRayCast(feet, pos, candidate.x, candidate.y)) {
            candidate = direct;
         }

         yaw = candidate.y;
         int sign = this.lastAdd ? 1 : -1;
         this.lastAdd = !this.lastAdd;

         for (int i = 0; i < 8; i++) {
            Vec2 adjusted = new Vec2(pitch, quantize(yaw + sign * 0.1F));
            if (this.canRayCast(feet, pos, adjusted.x, adjusted.y)) {
               candidate = adjusted;
               yaw = adjusted.y;
            }
         }

         return candidate;
      }
   }

   private static float quantize(float angle) {
      return Math.round(angle * 10.0F) / 10.0F;
   }

   private boolean canRayCast(Vec3 pos, BlockPos target, float pitch, float yaw) {
      AABB box = new AABB(target);
      double range = this.getBlockReachDistance();
      Vec3 direction = pitchYawToRotation(pitch, yaw).normalize();
      return this.getPotentialEyeHeights(pos).anyMatch(eyePos -> box.clip(eyePos, eyePos.add(direction.scale(range))).isPresent());
   }

   private static Vec3 pitchYawToRotation(float pitch, float yaw) {
      float f = pitch * (float) (Math.PI / 180.0);
      float g = -yaw * (float) (Math.PI / 180.0);
      float h = Mth.cos(g);
      float i = Mth.sin(g);
      float j = Mth.cos(f);
      float k = Mth.sin(f);
      return new Vec3(i * j, -k, h * j);
   }

   private static Vec2 rotationToPitchYaw(Vec3 vec) {
      return new Vec2(rotationToPitch(vec), rotationToYaw(vec));
   }

   private static float rotationToYaw(Vec3 vec) {
      vec = vec.normalize();
      return (float)Math.toDegrees(Math.atan2(-vec.x, vec.z));
   }

   private static float rotationToPitch(Vec3 vec) {
      vec = vec.normalize();
      return (float)Math.toDegrees(Math.asin(-vec.y));
   }

   private static float getSafeYawDiff(float oldYaw, float newYaw) {
      float diff = newYaw - oldYaw;
      float normalizedDiff = (diff % 360.0F + 720.0F + 180.0F) % 360.0F - 180.0F;
      if (oldYaw > 1000.0F && normalizedDiff > 179.0F) {
         normalizedDiff -= 360.0F;
      } else if (oldYaw < -1000.0F && normalizedDiff < -179.0F) {
         normalizedDiff += 360.0F;
      }

      return normalizedDiff;
   }

   private static float getSafeYaw(float oldYaw, float newYaw) {
      return oldYaw + getSafeYawDiff(oldYaw, newYaw);
   }

   private static float getSafePitch(float newPitch) {
      return newPitch < 90.00001 && newPitch > -90.00001 ? newPitch : (newPitch % 180.0F + 720.0F + 90.0F) % 180.0F - 90.0F;
   }

   private static void setEntityPitchSafe(Entity entity, float newPitch) {
      entity.setXRot(getSafePitch(newPitch));
   }

   private static void setPlayerYawSafe(Player player, float newYaw) {
      player.setYRot(getSafeYaw(player.getYRot(), newYaw));
   }

   private void rebuildCache(double c0x, double c0y, double c0z, double r, boolean playerInside) {
      this.cx[0] = c0x;
      this.cy[0] = c0y;
      this.cz[0] = c0z;
      if (this.render.getValue()) {
         this.rebuildShell(c0x, c0y, c0z, r, playerInside);
      } else {
         this.cachedBlocks.clear();
      }

      this.cachedSpawnable.clear();
      boolean collectSpawnable = this.renderSpawnable.getValue() || this.placeEnabled.getValue();
      if (collectSpawnable) {
         int spawnR = (int)Math.min(Math.ceil(r), (double)this.maxSpawnableRadius.getValue());
         if (spawnR > 0) {
            double spawnRSq = (double)spawnR * spawnR;
            int sMinX = (int)Math.floor(c0x - spawnR);
            int sMaxX = (int)Math.ceil(c0x + spawnR);
            int sMinY = (int)Math.floor(c0y - spawnR);
            int sMaxY = (int)Math.ceil(c0y + spawnR);
            int sMinZ = (int)Math.floor(c0z - spawnR);
            int sMaxZ = (int)Math.ceil(c0z + spawnR);

            for (int x = sMinX; x <= sMaxX; x++) {
               for (int y = sMinY; y <= sMaxY; y++) {
                  for (int z = sMinZ; z <= sMaxZ; z++) {
                     double px = x + 0.5;
                     double py = y + 0.5;
                     double pz = z + 0.5;
                     double ddx = px - c0x;
                     double ddy = py - c0y;
                     double ddz = pz - c0z;
                     if (!(ddx * ddx + ddy * ddy + ddz * ddz > spawnRSq)) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = mc.level.getBlockState(pos);
                        if (state.canOcclude() && mc.level.getBlockState(pos.above()).isAir()) {
                           this.cachedSpawnable.add(new AutoCircleSlab.SpawnableBlock(x, y, z));
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void rebuildShell(double c0x, double c0y, double c0z, double r, boolean playerInside) {
      double outerRSq = r * r;
      double innerR = r - 1.0;
      double innerRSq = innerR * innerR;
      int minY = (int)Math.floor(c0y - r);
      int maxY = (int)Math.ceil(c0y + r);
      int minZ = (int)Math.floor(c0z - r);
      int maxZ = (int)Math.ceil(c0z + r);
      Long2IntOpenHashMap shellMap = new Long2IntOpenHashMap();

      for (int y = minY; y <= maxY; y++) {
         double dy = y + 0.5 - c0y;
         double dy2 = dy * dy;

         for (int z = minZ; z <= maxZ; z++) {
            double dz = z + 0.5 - c0z;
            double dz2 = dz * dz;
            double outerX2 = outerRSq - dy2 - dz2;
            if (!(outerX2 < 0.0)) {
               double outerXLen = Math.sqrt(outerX2);
               int minX = (int)Math.ceil(c0x - outerXLen);
               int maxX = (int)Math.floor(c0x + outerXLen);

               for (int x = minX; x <= maxX; x++) {
                  double dx = x + 0.5 - c0x;
                  double d2 = dx * dx + dy2 + dz2;
                  if (!(d2 > outerRSq) && !(d2 < innerRSq)) {
                     shellMap.put(BlockPos.asLong(x, y, z), 0);
                  }
               }
            }
         }
      }

      this.cachedBlocks.clear();
      ObjectIterator var42 = shellMap.long2IntEntrySet().iterator();

      while (var42.hasNext()) {
         Entry entry = (Entry)var42.next();
         long packed = entry.getLongKey();
         int xx = BlockPos.getX(packed);
         int y = BlockPos.getY(packed);
         int zx = BlockPos.getZ(packed);
         int excludeDir = this.computeExcludeDir(xx, y, zx, outerRSq, shellMap, playerInside);
         if (excludeDir != 63) {
            this.cachedBlocks.add(new AutoCircleSlab.CachedBlock(xx, y, zx, excludeDir));
         }
      }
   }

   private int computeExcludeDir(int x, int y, int z, double outerRSq, Long2IntOpenHashMap shellMap, boolean playerInside) {
      int mask = 0;

      for (Direction dir : Direction.values()) {
         int nx = x + dir.getStepX();
         int ny = y + dir.getStepY();
         int nz = z + dir.getStepZ();
         long neighborPacked = BlockPos.asLong(nx, ny, nz);
         if (shellMap.containsKey(neighborPacked)) {
            mask |= 1 << dir.ordinal();
         } else {
            boolean neighborInside = this.isInside(nx + 0.5, ny + 0.5, nz + 0.5, outerRSq);
            boolean visibleBySide = playerInside ? neighborInside : !neighborInside;
            if (!visibleBySide) {
               mask |= 1 << dir.ordinal();
            }
         }
      }

      return mask;
   }

   @EventHandler
   private void onRender3D(WorldRenderEvent.Main event) {
      if (this.render.getValue()) {
         if (!this.cachedBlocks.isEmpty()) {
            Color c = this.shellColor.getValue();
            Color fill = new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(10, (int)(c.getAlpha() * 0.2)));

            for (AutoCircleSlab.CachedBlock b : this.cachedBlocks) {
               box(event, b.x, b.y, b.z, b.x + 1, b.y + 1, b.z + 1, fill, c, RenderMode.Both);
            }
         }

         if (this.renderSpawnable.getValue() && !this.cachedSpawnable.isEmpty()) {
            Color c = this.spawnableColor.getValue();
            Color fill = new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(20, (int)(c.getAlpha() * 0.5)));
            double t = this.spawnableThickness.getValue();

            for (AutoCircleSlab.SpawnableBlock b : this.cachedSpawnable) {
               double y = b.y + 1.0;
               box(event, b.x, y, b.z, b.x + 1, y + t, b.z + 1, fill, c, RenderMode.Both);
            }
         }
      }
   }

   private Item findAnyWhitelistedItem() {
      List<Block> whitelist = (List<Block>)this.blockWhitelist.getValue();
      Inventory inv = mc.player.getInventory();

      for (int i = 0; i < 9; i++) {
         ItemStack stack = inv.getItem(i);
         if (!stack.isEmpty() && whitelist.contains(Block.byItem(stack.getItem()))) {
            return stack.getItem();
         }
      }

      for (int ix = 9; ix < 36; ix++) {
         ItemStack stack = inv.getItem(ix);
         if (!stack.isEmpty() && whitelist.contains(Block.byItem(stack.getItem()))) {
            return stack.getItem();
         }
      }

      return null;
   }

   private void sendPacketsForInventoryAction() {
      if (mc.player != null && mc.getConnection() != null) {
         if (mc.player.isSprinting()) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
            mc.getConnection().send(new ServerboundPlayerCommandPacket(mc.player, ServerboundPlayerCommandPacket.Action.START_SPRINTING));
         }
      }
   }

   private boolean ensureItemInMainHand(Item item) {
      Inventory inv = mc.player.getInventory();
      int currentSlot = inv.getSelectedSlot();
      if (inv.getItem(currentSlot).is(item)) {
         return true;
      } else {
         for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(item)) {
               inv.setSelectedSlot(i);
               if (mc.getConnection() != null) {
                  mc.getConnection().send(new ServerboundSetCarriedItemPacket(i));
               }

               return true;
            }
         }

         AbstractContainerMenu handler = mc.player.containerMenu;

         for (int ix = 9; ix < 36; ix++) {
            if (inv.getItem(ix).is(item)) {
               OptionalInt slotIndex = handler.findSlot(inv, ix);
               if (slotIndex.isPresent() && slotIndex.getAsInt() >= 0) {
                  this.sendPacketsForInventoryAction();
                  mc.gameMode.handleInventoryMouseClick(handler.containerId, slotIndex.getAsInt(), currentSlot, ClickType.SWAP, mc.player);
                  return true;
               }
            }
         }

         return false;
      }
   }

   private BlockHitResult findPlaceHit(BlockPos pos) {
      BlockPos down = pos.below();
      BlockState downState = mc.level.getBlockState(down);
      if (!downState.isAir() && downState.getFluidState().isEmpty() && downState.canOcclude()) {
         Vec3 hp = this.getSupportHitPos(down, downState, Direction.UP);
         if (hp != null) {
            return new BlockHitResult(hp, Direction.UP, down, false);
         }
      }

      for (Direction d : Direction.values()) {
         if (d != Direction.DOWN) {
            BlockPos nb = pos.relative(d);
            BlockState nbState = mc.level.getBlockState(nb);
            if (!nbState.isAir() && nbState.getFluidState().isEmpty() && nbState.canOcclude()) {
               Direction face = d.getOpposite();
               Vec3 hp = this.getSupportHitPos(nb, nbState, face);
               if (hp != null) {
                  return new BlockHitResult(hp, face, nb, false);
               }
            }
         }
      }

      return null;
   }

   private Vec3 getSupportHitPos(BlockPos supportPos, BlockState supportState, Direction face) {
      VoxelShape shape = supportState.getShape(mc.level, supportPos);
      if (shape.isEmpty()) {
         shape = supportState.getCollisionShape(mc.level, supportPos);
      }

      Direction.Axis axis = face.getAxis();
      boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
      double offset = 0.5;
      if (!shape.isEmpty()) {
         switch (axis) {
            case X:
               offset = positive ? shape.max(Direction.Axis.X) : shape.min(Direction.Axis.X);
               break;
            case Y:
               offset = positive ? shape.max(Direction.Axis.Y) : shape.min(Direction.Axis.Y);
               break;
            case Z:
               offset = positive ? shape.max(Direction.Axis.Z) : shape.min(Direction.Axis.Z);
         }
      }

      double x = supportPos.getX() + 0.5;
      double y = supportPos.getY() + 0.5;
      double z = supportPos.getZ() + 0.5;
      switch (axis) {
         case X:
            x = supportPos.getX() + offset;
            break;
         case Y:
            y = supportPos.getY() + offset;
            break;
         case Z:
            z = supportPos.getZ() + offset;
      }

      return new Vec3(x, y, z);
   }

   private boolean isInside(double px, double py, double pz, double rSq) {
      double dx = px - this.cx[0];
      double dy = py - this.cy[0];
      double dz = pz - this.cz[0];
      return dx * dx + dy * dy + dz * dz < rSq;
   }

   private static class CachedBlock {
      final int x;
      final int y;
      final int z;
      final int excludeDir;

      CachedBlock(int x, int y, int z, int m) {
         this.x = x;
         this.y = y;
         this.z = z;
         this.excludeDir = m;
      }
   }

   private static class PendingPostPlace {
      final BlockHitResult hit;
      final InteractionHand hand;
      final boolean swing;

      PendingPostPlace(BlockHitResult hit, InteractionHand hand, boolean swing) {
         this.hit = hit;
         this.hand = hand;
         this.swing = swing;
      }
   }

   public enum PlaceMode implements NamedEntry {
      NONE("None"),
      DELAY_MOVEMENT("DelayMovement"),
      MOVEMENT_POST("MovementPost");

      private final String name;

      PlaceMode(String name) {
         this.name = name;
      }

      @Override
      public String getName() {
         return this.name;
      }

      @Override
      public String getDisplayName() {
         return Translations.get("addons.aurora.names.values." + this.name);
      }

      public boolean canMultiRotPlace() {
         return this == NONE;
      }
   }

   private static class SpawnableBlock {
      final int x;
      final int y;
      final int z;

      SpawnableBlock(int x, int y, int z) {
         this.x = x;
         this.y = y;
         this.z = z;
      }
   }
}
