package com.aurora.addon.modules.elytra;

import com.aurora.addon.modules.AuroraModule;
import com.aurora.addon.modules.elytra.elytracollect.AutoRestart;
import com.aurora.addon.modules.elytra.elytracollect.CollectRun;
import com.aurora.addon.modules.elytra.elytracollect.EndShipFinder;
import com.aurora.addon.modules.elytra.elytracollect.ItemFrameSearch;
import com.aurora.addon.modules.elytra.elytracollect.LockEnd;
import com.aurora.addon.modules.elytra.elytracollect.LockStart;
import com.aurora.addon.modules.elytra.elytracollect.StoreResupply;
import com.aurora.addon.modules.elytra.elytracollect.StowElytra;
import com.aurora.addon.modules.elytra.elytracollect.WaypointLock;
import com.aurora.addon.modules.utils.AutoEat;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.Module;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.NamedEntry;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.ColorSetting;
import evernight.client.settings.types.EnumSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.settings.types.StringSetting;
import evernight.client.utils.chat.ChatUtils;
import evernight.client.utils.inventory.InventoryUtils;
import java.awt.Color;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 鞘翅自动收集总控：开关时拉起 / 收掉整条流水线（找梯子链 → 锁定起飞 → 展示框 → 补给 →
 * 存鞘翅 → 寻路 → 锁定降落），并提供子模块读取的全部配置项。
 *
 * <p>移植自 Meteor 插件的 {@code ElytraCollect}。Meteor 的 {@code SettingGroup} 改为平铺设置，
 * 组内可见性用 {@link Setting#dependsOn} 表达；所有玩家可见文案走
 * {@code addons.aurora.messages.collect.*}。{@code ElytraInfiniteFlight} 对应本工程的
 * {@link ElytraPitch40}。
 */
public final class ElytraCollect extends AuroraModule {
    // ---- 末地船查找 ----
    private final Setting<Color> ladderColor = this.add(new ColorSetting("LadderColor", new Color(255, 180, 0, 100)));
    private final Setting<Integer> lockPointOffset = this.add(new NumberSetting<>("LockPointOffset", 6, 0, 12));
    private final Setting<Integer> blacklistRadius = this.add(new NumberSetting<>("BlacklistRadius", 80, 10, 300));

    // ---- 锁定降落 ----
    private final Setting<Integer> aimAbove = this.add(new NumberSetting<>("LockStartAimAbove", 30, 5, 100));
    private final Setting<Integer> descendRadius = this.add(new NumberSetting<>("LockStartDescendRadius", 8, 2, 30));
    private final Setting<Integer> descendHeight = this.add(new NumberSetting<>("LockStartDescendHeight", 3, 2, 50));
    private final Setting<Integer> arriveDistance = this.add(new NumberSetting<>("LockStartArriveDistance", 3, 1, 20));
    private final Setting<Integer> arriveHeight = this.add(new NumberSetting<>("LockStartArriveHeight", 4, 1, 20));
    private final Setting<Boolean> autoFirework = this.add(new BooleanSetting("LockStartAutoFirework", true));
    private final Setting<Integer> fireworkInterval =
            this.add(new NumberSetting<>("LockStartFireworkInterval", 60, 10, 200).dependsOn(this.autoFirework));
    private final Setting<Boolean> quickSwap =
            this.add(new BooleanSetting("LockStartQuickSwap", true).dependsOn(this.autoFirework));

    // ---- 展示框查找 ----
    private final Setting<Boolean> showRender = this.add(new BooleanSetting("FrameRender", true));
    private final Setting<RenderMode> shapeMode =
            this.add(new EnumSetting<>("FrameRenderMode", RenderMode.Both).dependsOn(this.showRender));
    private final Setting<Color> frameColor =
            this.add(new ColorSetting("FrameColor", new Color(0, 255, 255, 100)).dependsOn(this.showRender));
    private final Setting<Boolean> saveToFile = this.add(new BooleanSetting("FrameSaveToFile", true));

    // ---- 补给 ----
    private final Setting<Boolean> enFW = this.add(new BooleanSetting("SupplyFirework", true));
    private final Setting<Boolean> enFD = this.add(new BooleanSetting("SupplyFood", true));
    private final Setting<Boolean> enTT = this.add(new BooleanSetting("SupplyTotem", true));
    private final Setting<Boolean> enRP = this.add(new BooleanSetting("SupplyElytraRepair", true));
    private final Setting<Integer> minFW =
            this.add(new NumberSetting<>("MinFirework", 32, 1, 64).dependsOn(this.enFW));
    private final Setting<Integer> minFD =
            this.add(new NumberSetting<>("MinFood", 32, 1, 64).dependsOn(this.enFD));
    private final Setting<Integer> minDur =
            this.add(new NumberSetting<>("MinElytraDurability", 40, 1, 100).dependsOn(this.enRP));
    private final Setting<Integer> minTotem =
            this.add(new NumberSetting<>("MinTotem", 3, 1, 64).dependsOn(this.enTT));
    private final Setting<Integer> fwSupply =
            this.add(new NumberSetting<>("FireworkSupplyBundles", 3, 1, 6).dependsOn(this.enFW));
    private final Setting<Integer> fdSupply =
            this.add(new NumberSetting<>("FoodSupplyBundles", 3, 1, 6).dependsOn(this.enFD));
    private final Setting<Integer> xpSupply =
            this.add(new NumberSetting<>("XpSupplyBundles", 3, 1, 6).dependsOn(this.enRP));
    private final Setting<Integer> totemSupply =
            this.add(new NumberSetting<>("TotemSupply", 3, 1, 64).dependsOn(this.enTT));
    private final Setting<String> name = this.add(new StringSetting("SupplyBoxName", ""));
    private final Setting<Integer> xpIV = this.add(new NumberSetting<>("XpThrowInterval", 10, 2, 40));
    private final Setting<Integer> placeRange = this.add(new NumberSetting<>("SupplyPlaceRange", 4, 1, 6));

    // ---- 储存鞘翅 ----
    private final Setting<String> boxName = this.add(new StringSetting("StowBoxName", ""));

    // ---- 寻路起飞 ----
    private final Setting<Integer> addY = this.add(new NumberSetting<>("FrameTargetOffsetY", 6, 1, 20));

    // ---- 锁定起飞 ----
    private final Setting<Integer> endAimAbove = this.add(new NumberSetting<>("LockEndAimAbove", 135, 10, 200));
    private final Setting<Integer> endArriveDistance = this.add(new NumberSetting<>("LockEndArriveDistance", 4, 1, 20));
    private final Setting<Integer> endFireworkInterval = this.add(new NumberSetting<>("LockEndFireworkInterval", 60, 10, 200));
    private final Setting<Boolean> endQuickSwap = this.add(new BooleanSetting("LockEndQuickSwap", true));

    // ---- 路径点锁定 ----
    private final Setting<Boolean> lockYaw = this.add(new BooleanSetting("WaypointLockYaw", true));
    private final Setting<Boolean> lockPitch = this.add(new BooleanSetting("WaypointLockPitch", false));

    // ---- 自动重启 ----
    private final Setting<Integer> restartStartupDelay = this.add(new NumberSetting<>("RestartStartupDelay", 60, 20, 200));
    private final Setting<Integer> restartMaxCount = this.add(new NumberSetting<>("RestartMaxCount", 5, 1, 20));
    private final Setting<Integer> restartFlyToY = this.add(new NumberSetting<>("RestartFlyToY", 280, 100, 320));
    private final Setting<Integer> restartFlyTimeout = this.add(new NumberSetting<>("RestartFlyTimeout", 600, 100, 1800));

    // ---- 低高度下线 ----
    private final Setting<Boolean> autoMinHeightLog = this.add(new BooleanSetting("AutoLogLowHeight", false));
    private final Setting<Integer> minYToLog =
            this.add(new NumberSetting<>("MinYToLog", 40, -64, 320).dependsOn(this.autoMinHeightLog));
    private final Setting<String> messageWhenLog =
            this.add(new StringSetting("LogMessage", "").dependsOn(this.autoMinHeightLog));

    // ---- 自动吃 ----
    private final Setting<Boolean> enableAutoEat = this.add(new BooleanSetting("EnableAutoEat", true));
    private final Setting<Integer> minHunger = this.add(new NumberSetting<>("MinHunger", 18, 1, 20));

    // ---- 日志 ----
    private final Setting<Boolean> printInfo = this.add(new BooleanSetting("PrintInfo", true));
    private final Setting<Boolean> printError = this.add(new BooleanSetting("PrintError", true));
    private final Setting<Boolean> logInfo = this.add(new BooleanSetting("LogInfo", true));
    private final Setting<Boolean> logError = this.add(new BooleanSetting("LogError", true));

    private boolean autoMinHeightLogFired;
    private ResourceKey<Level> lastDim;
    private BlockPos miningPos;
    private final AutoEat autoEat = new AutoEat();

    public ElytraCollect(ModuleCategory category) {
        super("ElytraCollect", "Collects shulker boxes of elytra in the End; run the route calculator first.", category);
        this.autoEat.configure(this.enableAutoEat::getValue, this.minHunger::getValue,
                () -> this.error("messages.collect.noGoldenCarrot"));
    }

    /** 供子模块取配置；模块未注册或未开启时返回 {@code null}。 */
    public static ElytraCollect cfg() {
        return ClientContext.getModule(ElytraCollect.class);
    }

    // ------------------------------------------------------------------ 配置读取

    public Color ladderColor() {
        return this.ladderColor.getValue();
    }

    public int lockPointOffset() {
        return this.lockPointOffset.getValue();
    }

    public int blacklistRadius() {
        return this.blacklistRadius.getValue();
    }

    public boolean ifShowRender() {
        return this.showRender.getValue();
    }

    public RenderMode ifShapeMode() {
        return this.shapeMode.getValue();
    }

    public Color ifFrameColor() {
        return this.frameColor.getValue();
    }

    public boolean ifSaveToFile() {
        return this.saveToFile.getValue();
    }

    public int lockStartAimAbove() {
        return this.aimAbove.getValue();
    }

    public int lockStartDescendRadius() {
        return this.descendRadius.getValue();
    }

    public int lockStartDescendHeight() {
        return this.descendHeight.getValue();
    }

    public int lockStartArriveDistance() {
        return this.arriveDistance.getValue();
    }

    public int lockStartArriveHeight() {
        return this.arriveHeight.getValue();
    }

    public boolean lockStartAutoFirework() {
        return this.autoFirework.getValue();
    }

    public int lockStartFireworkInterval() {
        return this.fireworkInterval.getValue();
    }

    public boolean lockStartQuickSwap() {
        return this.quickSwap.getValue();
    }

    public int lockEndAimAbove() {
        return this.endAimAbove.getValue();
    }

    public int lockEndArriveDistance() {
        return this.endArriveDistance.getValue();
    }

    public int lockEndFireworkInterval() {
        return this.endFireworkInterval.getValue();
    }

    public boolean lockEndQuickSwap() {
        return this.endQuickSwap.getValue();
    }

    public boolean storeEnFW() {
        return this.enFW.getValue();
    }

    public boolean storeEnFD() {
        return this.enFD.getValue();
    }

    public boolean storeEnTT() {
        return this.enTT.getValue();
    }

    public boolean storeEnRP() {
        return this.enRP.getValue();
    }

    public int storeMinFW() {
        return this.minFW.getValue();
    }

    public int storeMinFD() {
        return this.minFD.getValue();
    }

    public int storeMinDur() {
        return this.minDur.getValue();
    }

    public int storeMinTotem() {
        return this.minTotem.getValue();
    }

    public int storeFwSupply() {
        return this.fwSupply.getValue();
    }

    public int storeFdSupply() {
        return this.fdSupply.getValue();
    }

    public int storeXpSupply() {
        return this.xpSupply.getValue();
    }

    public int storeTotemSupply() {
        return this.totemSupply.getValue();
    }

    /** 补给潜影盒名称；留空时用语言表里的默认名。 */
    public String storeBoxName() {
        return this.resolveBoxName(this.name.getValue(), "messages.collect.defaultSupplyBoxName");
    }

    public int storeXpInterval() {
        return this.xpIV.getValue();
    }

    public int storePlaceRange() {
        return this.placeRange.getValue();
    }

    /** 存鞘翅用潜影盒名称；留空时用语言表里的默认名。 */
    public String stowBoxName() {
        return this.resolveBoxName(this.boxName.getValue(), "messages.collect.defaultStowBoxName");
    }

    public int runAddY() {
        return this.addY.getValue();
    }

    public boolean waypointLockYaw() {
        return this.lockYaw.getValue();
    }

    public boolean waypointLockPitch() {
        return this.lockPitch.getValue();
    }

    public int autoRestartStartupDelay() {
        return this.restartStartupDelay.getValue();
    }

    public int autoRestartMaxCount() {
        return this.restartMaxCount.getValue();
    }

    public int autoRestartFlyToY() {
        return this.restartFlyToY.getValue();
    }

    public int autoRestartFlyTimeout() {
        return this.restartFlyTimeout.getValue();
    }

    private String resolveBoxName(String configured, String defaultMessageKey) {
        return configured != null && !configured.isBlank()
                ? configured
                : Translations.get(this.translationKey(defaultMessageKey));
    }

    // ------------------------------------------------------------------ 日志

    /** 查表 + 填参；查不到时退回键名本身。 */
    private static String translateText(String key, Object... args) {
        String pattern = Translations.get(key);
        if (args == null || args.length == 0) {
            return pattern;
        }
        try {
            return String.format(java.util.Locale.ROOT, pattern, args);
        } catch (RuntimeException invalidPattern) {
            return pattern;
        }
    }

    @Override
    public void info(String key, Object... args) {
        // 不能用 Translations.format：它固定补 evernight.ui. 前缀，插件的 addons.aurora.* 查不到
        String message = translateText(this.translationKey(key), args);
        if (this.logInfo.getValue()) {
            System.out.println("[ElytraCollect][INFO] " + message);
        }
        if (this.printInfo.getValue() && mc.player != null) {
            ChatUtils.printChat(Component.literal(message), ChatUtils.moduleSignature(this));
        }
    }

    @Override
    public void error(String key, Object... args) {
        String message = translateText(this.translationKey(key), args);
        if (this.logError.getValue()) {
            System.out.println("[ElytraCollect][ERROR] " + message);
        }
        if (this.printError.getValue() && mc.player != null) {
            ChatUtils.printChat(Component.literal(message), ChatUtils.moduleSignature(this));
        }
    }

    // ------------------------------------------------------------------ 槽位同步

    public void setSlotAndSync(int target) {
        if (mc.player == null || target < 0 || target > 8) {
            return;
        }
        if (mc.player.getInventory().getSelectedSlot() == target) {
            return;
        }
        mc.player.getInventory().setSelectedSlot(target);
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(target));
        }
    }

    // ------------------------------------------------------------------ 烟花

    /**
     * 手持烟花并右键释放。
     *
     * @param allowQuickSwap 为 {@code true} 时允许从背包任意位置换到手上（等价 Meteor 的
     *                       {@code InvUtils.quickSwap()}），为 {@code false} 时只找快捷栏
     * @return 是否真的释放了烟花
     */
    public boolean useFirework(boolean allowQuickSwap) {
        if (mc.player == null || mc.gameMode == null) {
            return false;
        }
        int previous = mc.player.getInventory().getSelectedSlot();
        int hotbar = InventoryUtils.findHotbarSlot(Items.FIREWORK_ROCKET);
        if (hotbar >= 0) {
            this.setSlotAndSync(hotbar);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            this.setSlotAndSync(previous);
            return true;
        }
        if (!allowQuickSwap) {
            return false;
        }
        int inventorySlot = InventoryUtils.findInventorySlot(Items.FIREWORK_ROCKET);
        if (inventorySlot < 0) {
            return false;
        }
        InventoryUtils.swapIntoSelectedSlot(inventorySlot);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        InventoryUtils.swapIntoSelectedSlot(inventorySlot);
        return true;
    }

    // ------------------------------------------------------------------ 挖方块

    /**
     * 持续挖同一个方块：第一次调用开始挖掘，之后每 tick 调用推进进度。
     *
     * <p>Meteor 版借用了单独的「单方块挖掘」模块；本次移植没有把它带过来，这里用原版
     * {@code MultiPlayerGameMode} 的 start / continue / stop 三件套做等价实现。
     */
    public void mineBlock(BlockPos pos) {
        if (mc.player == null || mc.gameMode == null) {
            return;
        }
        Direction side = this.miningFace(pos);
        if (this.miningPos != null && this.miningPos.equals(pos) && mc.gameMode.isDestroying()) {
            mc.gameMode.continueDestroyBlock(pos, side);
            return;
        }
        this.miningPos = pos;
        mc.gameMode.startDestroyBlock(pos, side);
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    public void stopMining() {
        this.miningPos = null;
        if (mc.gameMode != null && mc.gameMode.isDestroying()) {
            mc.gameMode.stopDestroyBlock();
        }
    }

    private Direction miningFace(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = eye.x - (pos.getX() + 0.5);
        double dy = eye.y - (pos.getY() + 0.5);
        double dz = eye.z - (pos.getZ() + 0.5);
        return Direction.getNearest((int) Math.signum(dx), (int) Math.signum(dy), (int) Math.signum(dz),
                Direction.UP);
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void onEnable() {
        if (mc.level == null || mc.player == null) {
            this.disable();
            return;
        }
        this.autoMinHeightLogFired = false;
        this.lastDim = mc.level.dimension();
        this.autoEat.stop();
        if (mc.level.dimension() == Level.END) {
            this.enableIfInactive(AutoRestart.class);
        }
    }

    @Override
    public void onDisable() {
        this.autoEat.stop();
        this.stopMining();
        this.autoMinHeightLogFired = false;
        this.disableIfActive(StoreResupply.class);
        this.disableIfActive(StowElytra.class);
        this.disableIfActive(WaypointLock.class);
        this.disableIfActive(LockStart.class);
        this.disableIfActive(LockEnd.class);
        this.disableIfActive(ElytraPitch40.class);
        this.disableIfActive(CollectRun.class);
        this.disableIfActive(ItemFrameSearch.class);
        this.disableIfActive(AutoRestart.class);
        this.disableIfActive(EndShipFinder.class);
        if (mc.player != null) {
            mc.options.keyJump.setDown(false);
            mc.options.keyUse.setDown(false);
        }
    }

    private void disableIfActive(Class<? extends Module> type) {
        Module module = ClientContext.getModule(type);
        if (module != null && module.isToggled()) {
            module.disable();
        }
    }

    private void enableIfInactive(Class<? extends Module> type) {
        Module module = ClientContext.getModule(type);
        if (module != null && !module.isToggled()) {
            module.enable();
        }
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (!this.inGame() || this.autoMinHeightLogFired) {
            return;
        }

        this.enableIfInactive(EndShipFinder.class);

        ResourceKey<Level> current = mc.level.dimension();
        if (this.lastDim != current) {
            this.lastDim = current;
            if (current == Level.END) {
                this.enableIfInactive(AutoRestart.class);
            }
        }

        if (this.autoMinHeightLog.getValue() && mc.player.getY() < this.minYToLog.getValue()) {
            this.autoMinHeightLogFired = true;
            String message = this.messageWhenLog.getValue();
            if (message == null || message.isBlank()) {
                message = Translations.get(this.translationKey("messages.collect.lowHeightDefaultMessage"));
            }
            this.info("messages.collect.lowHeightLog", message);
            if (mc.getConnection() != null) {
                mc.getConnection().getConnection().disconnect(Component.literal(message));
                // 1.4.1：断开后退回多人游戏界面
                mc.disconnect(new ProgressScreen(true), false);
                mc.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
            }
            this.disable();
            return;
        }

        if (this.autoEat.isIdle() && this.needAutoEat()) {
            this.autoEat.start();
        }
    }

    private boolean needAutoEat() {
        if (!this.enableAutoEat.getValue() || mc.player == null) {
            return false;
        }
        ElytraPitch40 pitch40 = ClientContext.getModule(ElytraPitch40.class);
        return pitch40 != null && pitch40.isToggled()
                && mc.player.getFoodData().getFoodLevel() < this.minHunger.getValue();
    }

    /** 展示框 / 锁定点方框的绘制方式；对应 Meteor 的 {@code ShapeMode}。 */
    public enum RenderMode implements NamedEntry {
        Lines,
        Sides,
        Both;

        @Override
        public String getName() {
            return this.name();
        }

        @Override
        public String getDisplayName() {
            return Translations.get("addons.aurora.names.values." + this.getName());
        }
    }
}
