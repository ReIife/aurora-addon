/*
 * Behaviour ported from Aurora (GPL-3.0-only, author n_lzy) — `ElytraRouteCalculate.java`.
 * This file is distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.modules.elytra;

import com.aurora.addon.modules.AuroraModule;
import com.aurora.addon.modules.elytra.elytracollect.CalculateRoute;
import com.aurora.addon.utils.BlacklistUtils;
import com.aurora.addon.utils.PointUtils;
import com.aurora.addon.utils.RenderMode;
import com.seedfinding.mcbiome.source.BiomeSource;
import com.seedfinding.mccore.rand.ChunkRand;
import com.seedfinding.mccore.state.Dimension;
import com.seedfinding.mccore.util.data.Pair;
import com.seedfinding.mccore.util.pos.BPos;
import com.seedfinding.mccore.util.pos.CPos;
import com.seedfinding.mccore.version.MCVersion;
import com.seedfinding.mcfeature.structure.EndCity;
import com.seedfinding.mcfeature.structure.generator.Generator;
import com.seedfinding.mcfeature.structure.generator.structure.EndCityGenerator;
import com.seedfinding.mcterrain.TerrainGenerator;
import evernight.client.events.core.EventHandler;
import evernight.client.events.render.WorldRenderEvent;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.mesh.WorldBoxRenderer;
import evernight.client.settings.core.NamedEntry;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.ColorSetting;
import evernight.client.settings.types.EnumSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.settings.types.StringSetting;
import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.WorldData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 计算路线：按世界种子扫描末地城 / 末地船，算出一条尽量短的访问顺序并渲染出来。
 *
 * <p>行为移植自 Aurora 的 {@code ElytraRouteCalculate}，算法（扇形扫描 → 黑名单过滤 →
 * 最近邻 / 瓶颈优化 + ILS）逐行保留；只替换框架层：
 * <ul>
 *     <li>Meteor 的 {@code SettingGroup} → 平铺设置；{@code SettingColor} → {@link Color}；</li>
 *     <li>{@code Render3DEvent#renderer.box(...)} → {@link WorldBoxRenderer}；{@code ShapeMode} → {@link RenderMode}；</li>
 *     <li>{@code TickEvent.Post} → {@code MinecraftTickEvent}；</li>
 *     <li>seedfinding 的 {@code mc_*} 依赖随插件打包在 {@code META-INF/jars}。</li>
 * </ul>
 *
 * <p>已知差异：运行期提示文案仍为中文硬编码（22 处），尚未接入语言表，见 PORTING.md §10。
 */
public final class ElytraRouteCalculate extends AuroraModule {
    private static final Path LUJD_DIR = Paths.get("AutoElytra");
   private static final String[] AUX_JSON_FILES = new String[]{"zuobiao.json", "ItemFrame.json"};

    private final Setting<Algorithm> algorithm = this.add(new EnumSetting<>("Algorithm", Algorithm.NearestNeighbor));
    private final Setting<Boolean> clearCache = this.add(new BooleanSetting("ClearCache", false));
    private final Setting<Boolean> showRoute = this.add(new BooleanSetting("ShowRoute", true));
    private final Setting<Integer> renderChunks = this.add(new NumberSetting<>("RenderChunks", 10, 1, 32));
    private final Setting<Integer> arrivalRange = this.add(new NumberSetting<>("ArrivalRange", 60, 10, 256));
    private final Setting<Color> lineColor = this.add(new ColorSetting("LineColor", new Color(0, 255, 255, 180)));
    private final Setting<Boolean> showBeam = this.add(new BooleanSetting("ShowBeam", false));
    private final Setting<Color> beamColor = this.add(new ColorSetting("BeamColor", new Color(255, 200, 50, 120)));
    private final Setting<Double> beamWidth = this.add(new NumberSetting<>("BeamWidth", 0.3, 0.1, 2.0));
    private final Setting<Double> beamMinY = this.add(new NumberSetting<>("BeamMinY", -64.0, -128.0, 320.0));
    private final Setting<Double> beamMaxY = this.add(new NumberSetting<>("BeamMaxY", 320.0, -128.0, 512.0));
    private final Setting<Boolean> useBottleneck = this.add(new BooleanSetting("UseBottleneck", true));
    private final Setting<Double> bottleneckEpsilon = this.add(new NumberSetting<>("BottleneckEpsilon", 0.4, 0.0, 0.8));
    private final Setting<Integer> ilsIterations = this.add(new NumberSetting<>("IlsIterations", 10, 0, 100));
    private final Setting<Integer> neighborK = this.add(new NumberSetting<>("NeighborK", 50, 10, 200));
    private final Setting<String> worldSeedStr = this.add(new StringSetting("WorldSeed", "0"));
    private final Setting<String> mcVersion = this.add(new StringSetting("McVersion", "1.21"));
    private final Setting<Integer> centerX = this.add(new NumberSetting<>("CenterX", 0, -30000000, 30000000));
    private final Setting<Integer> centerZ = this.add(new NumberSetting<>("CenterZ", 0, -30000000, 30000000));
    private final Setting<Boolean> limitShipCount = this.add(new BooleanSetting("LimitShipCount", false));
    private final Setting<Integer> targetShipCount = this.add(new NumberSetting<>("TargetShipCount", 850, 1, 5000));
    private final Setting<Integer> searchRadius = this.add(new NumberSetting<>("SearchRadius", 20000, 1000, 100000));
    private final Setting<Boolean> snapToPlayer = this.add(new BooleanSetting("SnapToPlayer", false));
    private final Setting<Boolean> doSearch = this.add(new BooleanSetting("DoSearch", false));
    private final Setting<Double> sectorStartAngle = this.add(new NumberSetting<>("SectorStartAngle", 0.0, 0.0, Math.PI * 2));
    private final Setting<Integer> sectorDivisions = this.add(new NumberSetting<>("SectorDivisions", 1, 1, 64));
    private final Setting<Boolean> requireShip = this.add(new BooleanSetting("RequireShip", true));
    private final Setting<Boolean> addWaypoints = this.add(new BooleanSetting("AddWaypoints", true));
    private final Setting<Boolean> clearWaypoints = this.add(new BooleanSetting("ClearWaypoints", false));
    private final Setting<Color> cityColor = this.add(new ColorSetting("CityColor", new Color(255, 180, 0, 80)));
    private final Setting<Color> shipColor = this.add(new ColorSetting("ShipColor", new Color(255, 0, 255, 180)));
    private final Setting<Integer> renderBoxSize = this.add(new NumberSetting<>("RenderBoxSize", 3, 1, 16));
    private final Setting<Boolean> skipBlacklisted = this.add(new BooleanSetting("SkipBlacklisted", true));
    private final Setting<Integer> blacklistRadius = this.add(new NumberSetting<>("BlacklistRadius", 80, 10, 512));

    /** 统一的方框绘制：{@code Both} 填充 + 描边，{@code Sides} 只填充，{@code Lines} 只描边。 */
    private static void box(WorldRenderEvent.Main event, double x1, double y1, double z1,
                            double x2, double y2, double z2, Color side, Color line, RenderMode mode) {
        AABB aabb = new AABB(x1, y1, z1, x2, y2, z2);
        if (mode != RenderMode.Lines) {
            WorldBoxRenderer.fillBox(event.getPoseStack(), aabb, side);
        }
        if (mode != RenderMode.Sides) {
            WorldBoxRenderer.outlineBox(event.getPoseStack(), aabb, line, 1.5F);
        }
    }

   private final Set<Long> visitedXZ = new HashSet<>();
   private List<Vec3> route = Collections.emptyList();
   private volatile String loadedWorld = null;
   private ElytraRouteCalculate.Algorithm activeAlgorithm = null;
   private boolean visitedFileExisted;
   /** 后台搜索线程写、渲染线程读，必须用写时复制列表，否则会 ConcurrentModificationException。 */
   private final List<ElytraRouteCalculate.FoundCity> foundCities = new java.util.concurrent.CopyOnWriteArrayList<>();
   private volatile int searchTotal = 0;
   private volatile int searchProgress = 0;
   private volatile boolean searching = false;
   private volatile boolean cancelSearch = false;
   private volatile String lastError;
   private final AtomicInteger searchSession = new AtomicInteger();

   public List<Vec3> getRoute() {
      return this.route;
   }

    public ElytraRouteCalculate(ModuleCategory category) {
        super("ElytraRouteCalculate", "Scans End cities and ships from the seed, orders them into a short route and renders it.", category);
    }

   @Override
   public void onEnable() {
      this.searching = false;
      this.cancelSearch = false;

      try {
         Files.createDirectories(LUJD_DIR);
      } catch (IOException var3) {
      }

      this.loadedWorld = this.detectWorld();
      if (this.loadedWorld != null) {
         this.loadVisited();
         this.loadRoute();
      }

      if (this.route == null) {
         this.route = Collections.emptyList();
      }

      if (this.getWorldSeedLong() == 0L) {
         long d = this.detectWorldSeed();
         if (d != 0L) {
            this.worldSeedStr.setValue(String.valueOf(d));
            this.info("自动检测种子: " + d);
         }
      }

      this.info(
         "已恢复 "
            + this.visitedXZ.size()
            + " 个已访问点, "
            + this.route.size()
            + " 个路径点路线"
            + (this.activeAlgorithm != null ? " (" + this.activeAlgorithm.name() + ")" : ""),
         new Object[0]
      );
   }

   @Override
   public void onDisable() {
      this.searchSession.incrementAndGet();
      if (this.searching) {
         this.cancelSearch = true;
      }

      this.saveVisited();
      this.saveRoute();
      this.searching = false;
      this.cancelSearch = false;
      this.visitedXZ.clear();
      this.route = Collections.emptyList();
      this.loadedWorld = null;
      this.activeAlgorithm = null;
      this.visitedFileExisted = false;
      this.foundCities.clear();
      this.searchTotal = 0;
      this.searchProgress = 0;
      this.lastError = null;
   }

   private void ensureStateLoaded() {
      if (this.loadedWorld == null) {
         this.loadedWorld = this.detectWorld();
         if (this.loadedWorld != null) {
            this.loadVisited();
            this.loadRoute();
            if (this.route == null) {
               this.route = Collections.emptyList();
            }
         }
      }
   }

   @EventHandler
   private void onTick(MinecraftTickEvent event) {
      if (mc.player != null) {
         if (this.snapToPlayer.getValue()) {
            this.snapToPlayer.setValue(false);
            this.centerX.setValue(mc.player.getBlockX());
            this.centerZ.setValue(mc.player.getBlockZ());
            this.info("中心 → 玩家: " + this.centerX.getValue() + ", " + this.centerZ.getValue());
         }

         if (this.clearWaypoints.getValue()) {
            this.clearWaypoints.setValue(false);
            int n = PointUtils.removeWaypoints("Elytra_") + PointUtils.removeWaypoints("EndCity_") + PointUtils.removeWaypoints("EndShip_");
            this.info("已清除 " + n + " 个路径点");
         }

         if (this.doSearch.getValue()) {
            this.doSearch.setValue(false);
            if (this.searching) {
               this.info("搜索进行中，请稍候");
            } else {
               this.performSeedSearch();
            }
         }

         if (this.clearCache.getValue()) {
            this.clearCache.setValue(false);
            if (this.searching) {
               this.cancelSearch = true;
               this.info("正在取消搜索，结果将被丢弃并清理");
            } else {
               this.foundCities.clear();
               this.route = Collections.emptyList();
               this.activeAlgorithm = null;
               this.searchTotal = 0;
               this.searchProgress = 0;
               this.lastError = null;
               this.saveRoute();
               this.info("已清除搜索结果与路线");
            }
         }

         this.markVisited();
      }
   }

   private void doCalculate() {
      this.loadVisited();
      this.activeAlgorithm = (ElytraRouteCalculate.Algorithm)this.algorithm.getValue();
      if (this.foundCities.isEmpty()) {
         this.route = Collections.emptyList();
         this.saveRoute();
         this.info("未找到路径点，无法计算路线");
      } else {
         List<Vec3> pts = new ArrayList<>();

         for (ElytraRouteCalculate.FoundCity c : this.foundCities) {
            BlockPos p = c.hasShip && c.elytraPos != null ? c.elytraPos : c.position;
            pts.add(new Vec3(p.getX() + 0.5, 0.0, p.getZ() + 0.5));
         }

         Set<Long> seen = new HashSet<>();
         List<Vec3> dedup = new ArrayList<>(pts.size());

         for (Vec3 pt : pts) {
            if (seen.add(xzKey((int)pt.x, (int)pt.z))) {
               dedup.add(pt);
            }
         }

         List<Vec3> remaining = new ArrayList<>();

         for (Vec3 ptx : dedup) {
            if (!this.visitedXZ.contains(xzKey((int)ptx.x, (int)ptx.z))) {
               remaining.add(ptx);
            }
         }

         if (remaining.isEmpty()) {
            this.route = Collections.emptyList();
            this.saveRoute();
            this.info("未找到路径点，无法计算路线");
         } else {
            double px = mc.player != null ? mc.player.getX() : 0.0;
            double pz = mc.player != null ? mc.player.getZ() : 0.0;
            long t0 = System.currentTimeMillis();

            List<Vec3> result;
            try {
               result = this.useBottleneck.getValue()
                  ? CalculateRoute.calculateBottleneck(
                     remaining, px, pz, this.ilsIterations.getValue(), this.neighborK.getValue(), this.bottleneckEpsilon.getValue()
                  )
                  : CalculateRoute.calculateNearestNeighbor(remaining, px, pz);
            } catch (Exception var15) {
               this.error("路线计算出错: " + var15.getMessage());
               return;
            }

            long ms = System.currentTimeMillis() - t0;
            if (result.isEmpty()) {
               this.info("未找到路径点，无法计算路线");
            } else {
               this.route = result;
               double[] st = CalculateRoute.stats(this.route, px, pz);
               this.info(
                  String.format("已使用 %s 计算 %d 个路径点（瓶颈=%.0f 格，总长=%.0f 格，耗时 %d ms）", this.activeAlgorithm.name(), this.route.size(), st[0], st[1], ms),
                  new Object[0]
               );
               this.saveRoute();
            }
         }
      }
   }

   private void markVisited() {
      this.ensureStateLoaded();
      if (!this.route.isEmpty() && mc.player != null) {
         int range = this.arrivalRange.getValue();
         double rangeSq = (double)range * range;
         Vec3 nearest = this.route.getFirst();
         double dx = mc.player.getX() - nearest.x;
         double dz = mc.player.getZ() - nearest.z;
         if (dx * dx + dz * dz <= rangeSq) {
            long key = xzKey((int)nearest.x, (int)nearest.z);
            if (!this.visitedXZ.contains(key)) {
               this.visitedXZ.add(key);
               this.info(
                  "已到达路径点 " + (int)nearest.x + "," + (int)nearest.z + " 范围" + range + "内，加入黑名单（剩余 " + (this.route.size() - 1) + " 个点）",
                  new Object[0]
               );
               this.saveVisited();
               if (this.route.size() > 1) {
                  this.route = new ArrayList<>(this.route.subList(1, this.route.size()));
               } else {
                  this.route = Collections.emptyList();
                  this.playCompletionSound();
               }

               this.saveRoute();
            }
         }
      }
   }

   private static double[] sectorBoundingBox(double cx, double cz, double maxR, double startAngle, int div) {
      if (div <= 1) {
         return new double[]{cx - maxR, cx + maxR, cz - maxR, cz + maxR};
      } else {
         double arc = (Math.PI * 2) / div;
         double endAngle = startAngle + arc;
         double[] candidates = new double[]{
            cx + maxR * Math.cos(startAngle), cz + maxR * Math.sin(startAngle), cx + maxR * Math.cos(endAngle), cz + maxR * Math.sin(endAngle)
         };
         double minX = Math.min(cx, Math.min(candidates[0], candidates[2]));
         double maxX = Math.max(cx, Math.max(candidates[0], candidates[2]));
         double minZ = Math.min(cz, Math.min(candidates[1], candidates[3]));
         double maxZ = Math.max(cz, Math.max(candidates[1], candidates[3]));

         for (double axis = 0.0; axis < Math.PI * 2; axis++) {
            if (angleInSector(axis, startAngle, endAngle)) {
               double px = cx + maxR * Math.cos(axis);
               double pz = cz + maxR * Math.sin(axis);
               minX = Math.min(minX, px);
               maxX = Math.max(maxX, px);
               minZ = Math.min(minZ, pz);
               maxZ = Math.max(maxZ, pz);
            }
         }

         return new double[]{minX, maxX, minZ, maxZ};
      }
   }

   private static boolean angleInSector(double a, double start, double end) {
      a = (a % (Math.PI * 2) + (Math.PI * 2)) % (Math.PI * 2);
      start = (start % (Math.PI * 2) + (Math.PI * 2)) % (Math.PI * 2);
      return end <= Math.PI * 2 ? a >= start && a <= end : a >= start || a <= end - (Math.PI * 2);
   }

   private static ElytraRouteCalculate.FoundCity tryScanRegion(
      EndCity endCity,
      ChunkRand rand,
      BiomeSource biomeSrc,
      TerrainGenerator terrain,
      EndCityGenerator gen,
      long seed,
      int rx,
      int rz,
      int cx,
      int cz,
      int maxDist,
      boolean requireShip,
      double sectorStart,
      int sectorDiv
   ) {
      CPos cp = endCity.getInRegion(seed, rx, rz, rand);
      if (cp == null) {
         return null;
      } else {
         int bx = cp.getX() * 16 + 8;
         int bz = cp.getZ() * 16 + 8;
         double dist = Math.sqrt((double)(bx - cx) * (bx - cx) + (bz - cz) * (bz - cz));
         if (dist > maxDist) {
            return null;
         } else {
            if (sectorDiv > 1) {
               double angle = Math.atan2(bz - cz, bx - cx);
               if (angle < 0.0) {
                  angle += Math.PI * 2;
               }

               double arc = (Math.PI * 2) / sectorDiv;
               double end = sectorStart + arc;
               boolean inSector = end <= Math.PI * 2 ? angle >= sectorStart && angle < end : angle >= sectorStart || angle < end - (Math.PI * 2);
               if (!inSector) {
                  return null;
               }
            }

            if (!endCity.canSpawn(cp, biomeSrc)) {
               return null;
            } else if (!endCity.canGenerate(cp, terrain)) {
               return null;
            } else {
               BPos elytraPos = null;
               boolean hasShip = false;
               boolean generationFailed = false;

               try {
                  gen.generate(terrain, cp.getX(), cp.getZ(), rand);
                  hasShip = gen.hasShip();
                  if (hasShip) {
                     for (Pair<Generator.ILootType, BPos> e : gen.getChestsPos()) {
                        if (e.getFirst() == EndCityGenerator.LootType.SHIP_ELYTRA) {
                           elytraPos = e.getSecond();
                           break;
                        }
                     }
                  }

                  gen.reset();
               } catch (Exception generationError) {
                  // 结构生成失败（版本/地形数据异常）时不能当成「这里没船」直接丢弃，
                  // 否则一次失败就会让整片区域看起来「一艘船都没有」。保留城市，交给玩家自己看。
                  generationFailed = true;
                  gen.reset();
               }

               if (requireShip && !hasShip && !generationFailed) {
                  return null;
               } else {
                  int y = terrain.getHeightInGround(bx, bz);
                  if (hasShip && elytraPos != null) {
                     y = elytraPos.getY();
                  }

                  return new ElytraRouteCalculate.FoundCity(
                     new BlockPos(bx, y, bz),
                     elytraPos != null ? new BlockPos(elytraPos.getX(), elytraPos.getY(), elytraPos.getZ()) : null,
                     hasShip,
                     (int)dist
                  );
               }
            }
         }
      }
   }

   private void performSeedSearch() {
      if (this.searching) {
         this.info("搜索进行中，请稍候");
      } else {
         long seed = this.getWorldSeedLong();
         if (seed == 0L) {
            this.error("请先设置世界种子");
         } else {
            MCVersion ver = this.resolveVersion();
            if (ver == null) {
               this.error("无法解析MC版本: " + (String)this.mcVersion.getValue());
            } else {
               int fCx = this.centerX.getValue();
               int fCz = this.centerZ.getValue();
               boolean fLimitShip = this.limitShipCount.getValue();
               int fTargetShips = this.targetShipCount.getValue();
               int fRadius = fLimitShip ? Integer.MAX_VALUE : this.searchRadius.getValue();
               boolean fRequireShip = this.requireShip.getValue();
               boolean fSkipBL = this.skipBlacklisted.getValue();
               int fBLRadius = this.blacklistRadius.getValue();
               String fWorld = BlacklistUtils.worldKey();
               double fSectorStart = this.sectorStartAngle.getValue();
               int fSectorDiv = this.sectorDivisions.getValue();
               double arc = (Math.PI * 2) / fSectorDiv;
               String sectorInfo = fSectorDiv > 1 ? String.format(" sector=[%.2f, %.2f] %.0f°", fSectorStart, fSectorStart + arc, Math.toDegrees(arc)) : "";
               this.info(
                  String.format(
                     "种子搜索开始（后台）: seed=%d ver=%s center=(%d,%d) %s%s", seed, ver, fCx, fCz, fLimitShip ? "目标船数=" + fTargetShips : "r=" + fRadius, sectorInfo
                  ),
                  new Object[0]
               );
               this.clearAuxiliaryJson();
               this.searching = true;
               this.cancelSearch = false;
               this.searchProgress = 0;
               this.searchTotal = 0;
               this.lastError = null;
               int mySession = this.searchSession.incrementAndGet();
               Thread t = new Thread(
                  () -> {
                     List<ElytraRouteCalculate.FoundCity> result = new ArrayList<>();
                     String localErr = null;
                     int foundShips = 0;
                     boolean reachedTarget = false;

                     try {
                        EndCity endCity = new EndCity(ver);
                        ChunkRand rand = new ChunkRand();
                        BiomeSource biomeSrc = BiomeSource.of(Dimension.END, ver, seed);
                        TerrainGenerator terrain = TerrainGenerator.of(Dimension.END, biomeSrc);
                        EndCityGenerator gen = new EndCityGenerator(ver);
                        int spacing = endCity.getSpacing();
                        int crX = Math.floorDiv(fCx, 16 * spacing);
                        int crZ = Math.floorDiv(fCz, 16 * spacing);
                        int progress = 0;
                        if (!fLimitShip) {
                           int rr = Math.floorDiv(fRadius, 16 * spacing) + 1;
                           int rxMin = crX - rr;
                           int rxMax = crX + rr;
                           int rzMin = crZ - rr;
                           int rzMax = crZ + rr;
                           if (fSectorDiv > 1) {
                              double[] bb = sectorBoundingBox(fCx, fCz, fRadius, fSectorStart, fSectorDiv);
                              int bbRxMin = Math.floorDiv((int)Math.floor(bb[0] - 8.0), 16 * spacing);
                              int bbRxMax = Math.floorDiv((int)Math.ceil(bb[1] - 8.0), 16 * spacing);
                              int bbRzMin = Math.floorDiv((int)Math.floor(bb[2] - 8.0), 16 * spacing);
                              int bbRzMax = Math.floorDiv((int)Math.ceil(bb[3] - 8.0), 16 * spacing);
                              rxMin = Math.max(rxMin, bbRxMin);
                              rxMax = Math.min(rxMax, bbRxMax);
                              rzMin = Math.max(rzMin, bbRzMin);
                              rzMax = Math.min(rzMax, bbRzMax);
                              if (rxMin > rxMax || rzMin > rzMax) {
                                 this.searchTotal = 0;
                              }
                           }

                           this.searchTotal = (rxMax - rxMin + 1) * (rzMax - rzMin + 1);

                           label90:
                           for (int rx = rxMin; rx <= rxMax; rx++) {
                              for (int rz = rzMin; rz <= rzMax; rz++) {
                                 if (this.cancelSearch) {
                                    break label90;
                                 }

                                 this.searchProgress = ++progress;
                                 ElytraRouteCalculate.FoundCity c = tryScanRegion(
                                    endCity, rand, biomeSrc, terrain, gen, seed, rx, rz, fCx, fCz, fRadius, fRequireShip, fSectorStart, fSectorDiv
                                 );
                                 if (c != null) {
                                    result.add(c);
                                 }
                              }
                           }
                        } else {
                           int maxRing = Math.floorDiv(30000000, 16 * spacing) + 1;

                           label77:
                           for (int ring = 0; ring <= maxRing; ring++) {
                              for (int rx = crX - ring; rx <= crX + ring; rx++) {
                                 for (int rz = crZ - ring; rz <= crZ + ring; rz++) {
                                    if (Math.max(Math.abs(rx - crX), Math.abs(rz - crZ)) == ring) {
                                       if (this.cancelSearch) {
                                          break label77;
                                       }

                                       this.searchProgress = ++progress;
                                       ElytraRouteCalculate.FoundCity c = tryScanRegion(
                                          endCity,
                                          rand,
                                          biomeSrc,
                                          terrain,
                                          gen,
                                          seed,
                                          rx,
                                          rz,
                                          fCx,
                                          fCz,
                                          Integer.MAX_VALUE,
                                          fRequireShip,
                                          fSectorStart,
                                          fSectorDiv
                                       );
                                       if (c != null) {
                                          result.add(c);
                                          if (c.hasShip) {
                                             foundShips++;
                                          }

                                          if (foundShips >= fTargetShips) {
                                             reachedTarget = true;
                                             break label77;
                                          }
                                       }
                                    }
                                 }
                              }
                           }
                        }
                     } catch (Exception var40) {
                        localErr = var40.getMessage();
                     }

                     int skippedByBL = 0;
                     if (fSkipBL && fWorld != null && !result.isEmpty()) {
                        int before = result.size();
                        result.removeIf(cx -> {
                           BlockPos p = cx.hasShip && cx.elytraPos != null ? cx.elytraPos : cx.position;
                           return BlacklistUtils.isNearBlacklisted(fWorld, p.getX(), p.getZ(), fBLRadius);
                        });
                        skippedByBL = before - result.size();
                     }

                     String fErr = localErr;
                     boolean fCancelled = this.cancelSearch;
                     int fSkippedByBL = skippedByBL;
                     int fFoundShips = foundShips;
                     boolean fReachedTarget = reachedTarget;
                     this.mc
                        .execute(
                           () -> {
                              if (mySession == this.searchSession.get()) {
                                 this.searching = false;
                                 this.cancelSearch = false;
                                 if (this.isToggled()) {
                                    if (fCancelled) {
                                       this.foundCities.clear();
                                       this.route = Collections.emptyList();
                                       this.activeAlgorithm = null;
                                       this.searchTotal = 0;
                                       this.searchProgress = 0;
                                       this.lastError = null;
                                       this.saveRoute();
                                       this.info("已取消搜索并清除结果");
                                    } else if (fErr != null) {
                                       this.lastError = fErr;
                                       this.searchTotal = 0;
                                       this.searchProgress = 0;
                                       this.error("搜索出错: " + fErr);
                                    } else {
                                       this.foundCities.clear();
                                       this.foundCities.addAll(result);
                                       this.foundCities.sort(Comparator.comparingInt(a -> a.distance));
                                       if (fSkippedByBL > 0) {
                                          this.info("已跳过 " + fSkippedByBL + " 艘黑名单中的末地船");
                                       }

                                       if (fLimitShip) {
                                          this.info(
                                             "找到 "
                                                + fFoundShips
                                                + " 艘末地船"
                                                + (fReachedTarget ? "，达到目标 " + fTargetShips + "，停止搜索" : "，未达目标 " + fTargetShips + "（已扫到边界）"),
                                             new Object[0]
                                          );
                                       }

                                       this.reportResults();
                                       if (fSectorDiv > 1) {
                                          double arc2 = (Math.PI * 2) / fSectorDiv;
                                          double newStart = (fSectorStart + arc2) % (Math.PI * 2);
                                          if (newStart >= 6.283185306179586) {
                                             newStart = 0.0;
                                          }

                                          this.sectorStartAngle.setValue(newStart);
                                          this.info(
                                             String.format(
                                                "已推进始边: %.3f → %.3f  (%.0f° → %.0f°)",
                                                fSectorStart,
                                                newStart,
                                                Math.toDegrees(fSectorStart),
                                                Math.toDegrees(newStart)
                                             ),
                                             new Object[0]
                                          );
                                       }
                                    }
                                 }
                              }
                           }
                        );
                  },
                  "ElytraSeedSearch"
               );
               t.setDaemon(true);
               t.start();
            }
         }
      }
   }

   @Override
   public String getInfo() {
      if (this.lastError != null) {
         return "err: " + this.lastError;
      } else if (this.searching) {
         if (this.limitShipCount.getValue()) {
            return "找船 " + this.searchProgress + " 区域";
         } else {
            return this.searchTotal > 0 ? "搜索中 " + this.searchProgress + "/" + this.searchTotal : "搜索中...";
         }
      } else if (this.searchTotal > 0 && this.searchProgress < this.searchTotal) {
         return this.searchProgress + "/" + this.searchTotal;
      } else if (this.foundCities.isEmpty()) {
         return this.getWorldSeedLong() != 0L ? "点击搜索" : "设置种子";
      } else {
         long ships = this.foundCities.stream().filter(c -> c.hasShip).count();
         return this.foundCities.size() + "城/" + ships + "船";
      }
   }

   private void reportResults() {
      long ships = this.foundCities.stream().filter(cx -> cx.hasShip).count();
      this.info(String.format("完成! %d 末地城 | %d 末地船(鞘翅) | 种子=%d", this.foundCities.size(), ships, this.getWorldSeedLong()));
      int show = Math.min(20, this.foundCities.size());

      for (int i = 0; i < show; i++) {
         ElytraRouteCalculate.FoundCity c = this.foundCities.get(i);
         BlockPos p = c.elytraPos != null ? c.elytraPos : c.position;
         String tag = c.hasShip ? "§a[鞘翅]" : "§e[末地城]";
         this.info(String.format("  #%d: %s §f%d,%d,%d §7距离:%dm", i + 1, tag, p.getX(), p.getY(), p.getZ(), c.distance));
      }

      if (this.addWaypoints.getValue()) {
         this.addAllWaypoints();
      }

      this.doCalculate();
   }

   private void addAllWaypoints() {
      int added = 0;

      for (ElytraRouteCalculate.FoundCity c : this.foundCities) {
         if (c.hasShip && c.elytraPos != null) {
            BlockPos p = c.elytraPos;
            if (PointUtils.addToWaypoints(p.getX(), p.getY(), p.getZ(), "Elytra_" + p.getX() + "_" + p.getZ())) {
               added++;
            }
         } else {
            BlockPos p = c.position;
            if (PointUtils.addToWaypoints(p.getX(), p.getY(), p.getZ(), "EndCity_" + p.getX() + "_" + p.getZ())) {
               added++;
            }
         }
      }

      if (added > 0) {
         this.info("已添加 " + added + " 个路径点到Xaero");
      }
   }

   private void playCompletionSound() {
      if (mc.player != null) {
         try {
            this.info("所有路径点已到达！");
         } catch (Exception var2) {
         }
      }
   }

   private long getWorldSeedLong() {
      try {
         return Long.parseLong(((String)this.worldSeedStr.getValue()).trim());
      } catch (NumberFormatException var2) {
         return 0L;
      }
   }

   private long detectWorldSeed() {
      try {
         if (mc.getSingleplayerServer() != null) {
            WorldData sp = mc.getSingleplayerServer().getWorldData();
            if (sp != null) {
               return sp.worldGenOptions().seed();
            }
         }
      } catch (Exception var2) {
      }

      return 0L;
   }

   private MCVersion resolveVersion() {
      String v = ((String)this.mcVersion.getValue()).trim();

      try {
         return MCVersion.fromString(v);
      } catch (Exception var5) {
         Map<String, String> m = new LinkedHashMap<>();
         m.put("1.21.4", "1.21.4");
         m.put("1.21.3", "1.21.3");
         m.put("1.21.1", "1.21");
         m.put("1.21", "1.21");
         m.put("1.20.6", "1.20.5");
         m.put("1.20.4", "1.20.3");
         m.put("1.20.2", "1.20");
         m.put("1.20.1", "1.20");
         m.put("1.20", "1.20");
         m.put("1.19.4", "1.19");
         m.put("1.19.3", "1.19");
         m.put("1.19.2", "1.19");
         m.put("1.19.1", "1.19");
         m.put("1.19", "1.19");
         m.put("1.18.2", "1.18");
         m.put("1.18.1", "1.18");
         m.put("1.18", "1.18");
         m.put("1.17.1", "1.17");
         m.put("1.17", "1.17");
         m.put("1.16.5", "1.16_5");
         m.put("1.16", "1.16");
         if (m.containsKey(v)) {
            try {
               return MCVersion.fromString(m.get(v));
            } catch (Exception var4) {
            }
         }

         return null;
      }
   }

   @EventHandler
   private void onRender3D(WorldRenderEvent.Main event) {
      if (!this.foundCities.isEmpty() && mc.player != null) {
         Color cc = this.cityColor.getValue();
         Color sc = this.shipColor.getValue();
         double hw = (this.renderBoxSize.getValue()) / 2.0;
         double maxSq = 250000.0;
         double px = mc.player.getX();
         double py = mc.player.getY() + 1.0;
         double pz = mc.player.getZ();

         for (ElytraRouteCalculate.FoundCity c : this.foundCities) {
            BlockPos pos = c.hasShip && c.elytraPos != null ? c.elytraPos : c.position;
            double dx = pos.getX() + 0.5 - px;
            double dy = pos.getY() + 0.5 - py;
            double dz = pos.getZ() + 0.5 - pz;
            if (!(dx * dx + dy * dy + dz * dz > maxSq)) {
               Color col = c.hasShip ? sc : cc;
               box(event,
                     pos.getX() + 0.5 - hw,
                     pos.getY() + 0.5 - hw,
                     pos.getZ() + 0.5 - hw,
                     pos.getX() + 0.5 + hw,
                     pos.getY() + 0.5 + hw,
                     pos.getZ() + 0.5 + hw,
                     col, col, RenderMode.Both);
            }
         }
      }

      if (!this.route.isEmpty()) {
         double maxDist = (this.renderChunks.getValue()) * 16.0;
         double maxSq = maxDist * maxDist;
         double px = mc.player.getX();
         double pz = mc.player.getZ();
         if (this.showRoute.getValue()) {
            Color cx = this.lineColor.getValue();
            double y = mc.player.getY() + 1.0;
            List<Vec3> renderPts = new ArrayList<>();
            renderPts.add(new Vec3(px, y, pz));
            renderPts.add(new Vec3(this.route.getFirst().x, y, this.route.getFirst().z));

            for (int i = 1; i < this.route.size(); i++) {
               renderPts.add(new Vec3(this.route.get(i).x, 100.0, this.route.get(i).z));
            }

            for (int i = 0; i < renderPts.size() - 1; i++) {
               Vec3 a = renderPts.get(i);
               Vec3 b = renderPts.get(i + 1);
               double dax = a.x - px;
               double daz = a.z - pz;
               double dbx = b.x - px;
               double dbz = b.z - pz;
               if (!(dax * dax + daz * daz > maxSq) || !(dbx * dbx + dbz * dbz > maxSq)) {
                  double dx = b.x - a.x;
                  double dz = b.z - a.z;
                  double len = Math.sqrt(dx * dx + dz * dz);
                  if (!(len < 0.001)) {
                     double ux = dx / len;
                     double uz = dz / len;
                     double ex = -uz * 0.12;
                     double ez = ux * 0.12;
                     double segY = (a.y + b.y) * 0.5;
                     double toPlayerX = px - a.x;
                     double toPlayerZ = pz - a.z;
                     double tNear = toPlayerX * ux + toPlayerZ * uz;
                     double perpX = toPlayerX - ux * tNear;
                     double perpZ = toPlayerZ - uz * tNear;
                     double distToLineSq = perpX * perpX + perpZ * perpZ;
                     if (!(distToLineSq > maxSq)) {
                        double half = Math.sqrt(maxSq - distToLineSq);
                        double tStart = Math.max(0.0, tNear - half);
                        double tEnd = Math.min(len, tNear + half);
                        if (!(tEnd <= tStart)) {
                           double visibleLen = tEnd - tStart;
                           double step = Math.max(0.1, visibleLen / 200.0);
                           double halfLen = step * 0.5;

                           for (double t = tStart; t < tEnd; t += step) {
                              double cxx = a.x + ux * t;
                              double cz = a.z + uz * t;
                              box(event,
                                    cxx - ux * halfLen - ex,
                                    segY - 0.12,
                                    cz - uz * halfLen - ez,
                                    cxx + ux * halfLen + ex,
                                    segY + 0.12,
                                    cz + uz * halfLen + ez,
                                    cx, cx, RenderMode.Sides);
                           }
                        }
                     }
                  }
               }
            }
         }

         if (this.showBeam.getValue()) {
            Color bc = this.beamColor.getValue();
            double bw = this.beamWidth.getValue();
            double minY = this.beamMinY.getValue();
            double maxY = this.beamMaxY.getValue();

            for (Vec3 pt : this.route) {
               double ddx = pt.x - px;
               double ddz = pt.z - pz;
               if (!(ddx * ddx + ddz * ddz > maxSq)) {
                  box(event, pt.x - bw, minY, pt.z - bw, pt.x + bw, maxY, pt.z + bw, bc, bc, RenderMode.Sides);
               }
            }
         }
      }
   }

   private String detectWorld() {
      if (this.mc == null) {
         return null;
      } else {
         try {
            if (mc.getCurrentServer() != null) {
               return "Multiplayer_" + mc.getCurrentServer().ip.replace(":", "_");
            }
         } catch (Exception var3) {
         }

         try {
            if (mc.getSingleplayerServer() != null) {
               WorldData sp = mc.getSingleplayerServer().getWorldData();
               if (sp != null) {
                  return sp.getLevelName();
               }
            }
         } catch (Exception var2) {
         }

         return null;
      }
   }

   /** 新一轮搜索前把收集组用的坐标文件清空，避免旧坐标干扰（与上游一致）。 */
   private void clearAuxiliaryJson() {
      String world = this.loadedWorld != null ? this.loadedWorld : this.detectWorld();
      if (world == null) {
         this.info("未检测到世界，跳过清空辅助坐标");
         return;
      }
      int cleared = 0;
      for (String name : AUX_JSON_FILES) {
         Path file = LUJD_DIR.resolve(world).resolve(name);
         try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "[]");
            cleared++;
         } catch (IOException failure) {
            this.error("清空 " + name + " 失败: " + failure.getMessage());
         }
      }
      if (cleared > 0) {
         this.info("已清空 " + cleared + " 个辅助坐标文件（zuobiao.json / ItemFrame.json）");
      }
   }

   private void saveVisited() {
      if (!this.visitedXZ.isEmpty()) {
         String world = this.loadedWorld;   // 后台线程只读缓存，不直接碰 Minecraft 状态
         if (world != null) {
            Path f = LUJD_DIR.resolve(world).resolve("visited.json");
            if (this.visitedFileExisted && !Files.isRegularFile(f)) {
               this.visitedXZ.clear();
               this.visitedFileExisted = false;
            } else {
               try {
                  List<String> list = new ArrayList<>();

                  for (long key : this.visitedXZ) {
                     int x = (int)(key >> 32);
                     int z = (int)key;
                     list.add("  {\"x\":" + x + ",\"z\":" + z + "}");
                  }

                  Files.createDirectories(f.getParent());
                  Files.writeString(f, "[\n" + String.join(",\n", list) + "\n]");
                  this.visitedFileExisted = true;
               } catch (IOException var9) {
               }
            }
         }
      }
   }

   private void loadVisited() {
      if (this.loadedWorld != null) {
         Path f = LUJD_DIR.resolve(this.loadedWorld).resolve("visited.json");
         if (!Files.isRegularFile(f)) {
            if (this.visitedFileExisted) {
               this.visitedXZ.clear();
            }

            this.visitedFileExisted = false;
         } else {
            this.visitedFileExisted = true;
            this.visitedXZ.clear();

            try {
               for (String line : Files.readAllLines(f)) {
                  line = line.trim();
                  if (line.startsWith("{")) {
                     int xi = line.indexOf("\"x\":");
                     int zi = line.indexOf("\"z\":");
                     if (xi >= 0 && zi >= 0) {
                        int xe = line.indexOf(",", xi + 4);
                        int ze = line.indexOf("}", zi + 4);
                        if (xe < 0) {
                           xe = line.indexOf("}", xi + 4);
                        }

                        if (ze < 0) {
                           ze = line.length();
                        }

                        try {
                           int x = Integer.parseInt(line.substring(xi + 4, xe).trim());
                           int z = Integer.parseInt(line.substring(zi + 4, ze).trim());
                           this.visitedXZ.add(xzKey(x, z));
                        } catch (NumberFormatException var10) {
                        }
                     }
                  }
               }
            } catch (IOException var11) {
            }
         }
      }
   }

   private void saveRoute() {
      if (this.route != null) {
         String world = this.loadedWorld;   // 后台线程只读缓存，不直接碰 Minecraft 状态
         if (world != null) {
            Path f = LUJD_DIR.resolve(world).resolve("route.json");

            try {
               List<String> list = new ArrayList<>();

               for (Vec3 pt : this.route) {
                  list.add("  {\"x\":" + (int)pt.x + ",\"z\":" + (int)pt.z + "}");
               }

               Files.createDirectories(f.getParent());
               String algo = this.activeAlgorithm != null ? this.activeAlgorithm.name() : ((ElytraRouteCalculate.Algorithm)this.algorithm.getValue()).name();
               String json = "{\n  \"algorithm\":\"" + algo + "\",\n  \"points\":[\n" + String.join(",\n", list) + "\n  ]\n}";
               Files.writeString(f, json);
            } catch (IOException var6) {
            }
         }
      }
   }

   private void loadRoute() {
      if (this.loadedWorld != null) {
         Path f = LUJD_DIR.resolve(this.loadedWorld).resolve("route.json");
         if (!Files.isRegularFile(f)) {
            this.route = Collections.emptyList();
         } else {
            try {
               String raw = Files.readString(f).trim();
               int algoIdx = raw.indexOf("\"algorithm\":\"");
               if (algoIdx >= 0) {
                  int algoStart = algoIdx + 14;
                  int algoEnd = raw.indexOf("\"", algoStart);
                  if (algoEnd > algoStart) {
                     try {
                        this.activeAlgorithm = ElytraRouteCalculate.Algorithm.valueOf(raw.substring(algoStart, algoEnd));
                     } catch (IllegalArgumentException var16) {
                     }
                  }
               }

               List<Vec3> pts = new ArrayList<>();
               int arrStart = raw.indexOf("\"points\":[");
               if (arrStart < 0) {
                  this.route = Collections.emptyList();
                  return;
               }

               int brace = raw.indexOf("{", arrStart);

               while (brace >= 0) {
                  int endBrace = raw.indexOf("}", brace);
                  if (endBrace < 0) {
                     break;
                  }

                  String entry = raw.substring(brace, endBrace + 1);
                  int xi = entry.indexOf("\"x\":");
                  int zi = entry.indexOf("\"z\":");
                  if (xi >= 0 && zi >= 0) {
                     int xe = entry.indexOf(",", xi + 4);
                     if (xe < 0) {
                        xe = entry.indexOf("}", xi + 4);
                     }

                     int ze = entry.indexOf("}", zi + 4);
                     if (ze < 0) {
                        ze = entry.length();
                     }

                     try {
                        int x = Integer.parseInt(entry.substring(xi + 4, xe).trim());
                        int z = Integer.parseInt(entry.substring(zi + 4, ze).trim());
                        pts.add(new Vec3(x + 0.5, 0.0, z + 0.5));
                     } catch (NumberFormatException var15) {
                     }
                  }

                  brace = raw.indexOf("{", endBrace + 1);
               }

               List<Vec3> filtered = new ArrayList<>();

               for (Vec3 pt : pts) {
                  if (!this.visitedXZ.contains(xzKey((int)pt.x, (int)pt.z))) {
                     filtered.add(pt);
                  }
               }

               this.route = filtered;
            } catch (IOException var17) {
               this.route = Collections.emptyList();
            }
         }
      }
   }

   private static long xzKey(int x, int z) {
      return (long)x << 32 | z & 4294967295L;
   }

   public enum Algorithm implements NamedEntry {
      NearestNeighbor("NearestNeighbor");

      private final String name;

      Algorithm(String name) {
         this.name = name;
      }

      @Override
      public String getName() {
         return this.name;
      }

      @Override
      public String getDisplayName() {
         return evernight.client.render.text.Translations.get("addons.aurora.names.values." + this.name);
      }
   }

   private static class FoundCity {
      final BlockPos position;
      final BlockPos elytraPos;
      final boolean hasShip;
      final int distance;

      FoundCity(BlockPos p, BlockPos ep, boolean hs, int d) {
         this.position = p;
         this.elytraPos = ep;
         this.hasShip = hs;
         this.distance = d;
      }
   }
}
