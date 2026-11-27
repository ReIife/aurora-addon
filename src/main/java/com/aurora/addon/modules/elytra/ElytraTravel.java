/*
 * Port of ElytraTravel from Aurora (Meteor Client addon, GPL-3.0-only, author n_lzy).
 * This file is distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.modules.elytra;

import com.aurora.addon.modules.AuroraModule;
import com.aurora.addon.modules.elytra.elytratravel.ContinueFly;
import com.aurora.addon.modules.elytra.elytratravel.EmergencyLanding;
import com.aurora.addon.modules.elytra.elytratravel.Resupply;
import com.aurora.addon.modules.elytra.elytratravel.TravelLog;
import com.aurora.addon.modules.utils.AutoEat;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.ui.MinecraftRunTickEvent;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.settings.types.StringSetting;
import evernight.client.utils.chat.ChatUtils;
import evernight.client.utils.inventory.InventoryUtils;
import evernight.client.utils.network.PacketSender;
import java.util.Locale;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 末地自动鞘翅飞行：锁定目标坐标，起飞到 Y=320 后交给 Pitch40 巡航，途中自动换鞘翅、
 * 到点降落、落地补给。
 *
 * <p>移植自 Meteor 插件的 {@code ElytraTravel}，状态机与各阶段阈值逐项保留：
 * 低高度下线 → 补给 → 起飞 → 紧急降落 → 到达判定。
 *
 * <p>Meteor 的 {@code SettingGroup} 在这里改为平铺设置 + {@code dependsOn(...)}；
 * 原版引用的 {@code ElytraInfiniteFlight} 对应本工程已移植的 {@link ElytraPitch40}。
 */
public final class ElytraTravel extends AuroraModule implements TravelLog {
    private static final int CHEST_SLOT = 38;
    private static final int ARMOR_CHEST_MENU_SLOT = 6;
    private static final long REPLACE_COOLDOWN_MS = 2000L;

    // 目的地
    private final Setting<Integer> destinationX =
            this.add(new NumberSetting<>("DestinationX", 0, -30000000, 30000000));
    private final Setting<Integer> destinationZ =
            this.add(new NumberSetting<>("DestinationZ", 0, -30000000, 30000000));
    private final Setting<Integer> arrivalDistance =
            this.add(new NumberSetting<>("ArrivalDistance", 32, 1, 128));

    // 装备管理
    private final Setting<Boolean> autoEquip = this.add(new BooleanSetting("AutoEquip", true));
    private final Setting<Boolean> autoReplace = this.add(new BooleanSetting("AutoReplace", true));
    private final Setting<Integer> durabilityThreshold =
            this.add(new NumberSetting<>("DurabilityThreshold", 20, 5, 400).dependsOn(this.autoReplace));

    // 升空（1.4.1 新增）
    private final Setting<Integer> climbFireworkInterval =
            this.add(new NumberSetting<>("ClimbFireworkInterval", 60, 10, 200));
    private final Setting<Boolean> climbQuickSwap = this.add(new BooleanSetting("ClimbQuickSwap", true));

    // 降落
    private final Setting<Integer> landingSearchRadius =
            this.add(new NumberSetting<>("LandingSearchRadius", 64, 16, 128));
    private final Setting<Integer> landingArriveDistance =
            this.add(new NumberSetting<>("LandingArriveDistance", 3, 1, 16));

    // 补给
    private final Setting<Integer> minFireworks = this.add(new NumberSetting<>("MinFireworks", 32, 1, 64));
    private final Setting<Integer> minFood = this.add(new NumberSetting<>("MinFood", 32, 1, 64));
    private final Setting<Integer> minTotems = this.add(new NumberSetting<>("MinTotems", 3, 1, 64));
    private final Setting<Integer> supplyFireworks = this.add(new NumberSetting<>("SupplyFireworks", 1, 1, 10));
    private final Setting<Integer> supplyFood = this.add(new NumberSetting<>("SupplyFood", 1, 1, 10));
    private final Setting<Integer> supplyTotems = this.add(new NumberSetting<>("SupplyTotems", 1, 1, 16));
    private final Setting<Integer> supplyXp = this.add(new NumberSetting<>("SupplyXp", 3, 1, 6));
    private final Setting<String> boxName = this.add(new StringSetting("BoxName", "supply"));
    private final Setting<Integer> xpInterval = this.add(new NumberSetting<>("XpInterval", 5, 2, 40));
    private final Setting<Integer> placeRange = this.add(new NumberSetting<>("PlaceRange", 4, 1, 6));

    // 低高度下线
    private final Setting<Boolean> enableLowHeightLog = this.add(new BooleanSetting("EnableLowHeightLog", false));
    private final Setting<Integer> minYToLog =
            this.add(new NumberSetting<>("MinYToLog", 40, -64, 320).dependsOn(this.enableLowHeightLog));
    private final Setting<String> logMessage =
            this.add(new StringSetting("LogMessage", "").dependsOn(this.enableLowHeightLog));

    // 自动吃
    private final Setting<Boolean> enableAutoEat = this.add(new BooleanSetting("EnableAutoEat", true));
    private final Setting<Integer> minHunger = this.add(new NumberSetting<>("MinHunger", 18, 1, 20));

    private final EmergencyLanding emergencyLanding = new EmergencyLanding();
    private final AutoEat autoEat = new AutoEat();

    private boolean lowHeightLogFired;
    private long lastReplaceTime;
    private boolean landingForArrival;
    private Resupply resupply;
    private ContinueFly continueFly;

    public ElytraTravel(ModuleCategory category) {
        super("ElytraTravel", "Flies to a chosen End coordinate with elytra, resupplying on the way.", category);
    }

    @Override
    public void onEnable() {
        if (!this.inGame()) {
            this.toggle();
            return;
        }

        this.lowHeightLogFired = false;
        this.emergencyLanding.reset();
        this.lastReplaceTime = 0L;
        this.landingForArrival = false;
        this.resupply = null;
        this.continueFly = null;

        this.autoEat.configure(
                this.enableAutoEat::getValue,
                this.minHunger::getValue,
                () -> this.warning("messages.noFood"));

        if (this.autoEquip.getValue()) {
            this.equipElytra();
        }

        if (!mc.player.isFallFlying()) {
            this.info("messages.ascendStart");
            this.continueFly = new ContinueFly(this, this.climbFireworkInterval::getValue, this.climbQuickSwap::getValue);
            this.continueFly.start();
        }
    }

    @Override
    public void onDisable() {
        this.lowHeightLogFired = false;
        this.emergencyLanding.reset();
        this.landingForArrival = false;
        if (this.resupply != null) {
            this.resupply.reset();
            this.resupply = null;
        }
        if (this.continueFly != null) {
            this.continueFly.reset();
            this.continueFly = null;
        }
        this.autoEat.stop();
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (!this.inGame()) {
            return;
        }

        LocalPlayer player = mc.player;

        if (this.enableLowHeightLog.getValue() && !this.lowHeightLogFired && player.getY() < this.minYToLog.getValue()) {
            this.lowHeightLogFired = true;
            this.disconnectLowHeight();
            return;
        }

        if (this.resupply != null) {
            if (this.resupply.tick()) {
                this.info("messages.resupplyDone");
                this.resupply = null;
                this.continueFly = new ContinueFly(this, this.climbFireworkInterval::getValue, this.climbQuickSwap::getValue);
                this.continueFly.start();
            }
            return;
        }

        if (this.continueFly != null) {
            if (this.continueFly.preTick()) {
                this.info("messages.ascendDone");
                this.continueFly = null;
            }
            return;
        }

        if (this.emergencyLanding.isActive()) {
            this.checkEmergencyElytraReplace(player);
            EmergencyLanding.Result result = this.emergencyLanding.tick(player);
            if (result == EmergencyLanding.Result.ARRIVED) {
                if (this.landingForArrival) {
                    this.info("messages.arrivedAndLanded");
                    this.emergencyLanding.reset();
                    this.landingForArrival = false;
                    this.toggle();
                } else {
                    this.info("messages.emergencyLanded");
                    this.emergencyLanding.reset();
                    this.startResupply();
                }
            }
            return;
        }

        double dx = this.destinationX.getValue() + 0.5 - player.getX();
        double dz = this.destinationZ.getValue() + 0.5 - player.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        if (horizontalDistance <= this.arrivalDistance.getValue()) {
            this.info("messages.arrivedHorizontal");
            this.landingForArrival = true;
            this.stopFlightModule();
            this.emergencyLanding.start(
                    player,
                    this.landingSearchRadius.getValue(),
                    this.landingArriveDistance.getValue(),
                    this);
            return;
        }

        if (this.autoReplace.getValue()) {
            this.checkAndReplaceElytra(player);
        }

        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        player.setYRot(yaw);

        if (this.autoEat.isIdle() && this.needAutoEat()) {
            this.autoEat.start();
        }
    }

    @EventHandler
    private void onRunTick(MinecraftRunTickEvent event) {
        if (this.continueFly != null) {
            this.continueFly.postTick();
        }
    }

    private boolean needAutoEat() {
        if (!this.enableAutoEat.getValue() || mc.player == null) {
            return false;
        }
        ElytraPitch40 flight = ClientContext.getModule(ElytraPitch40.class);
        return flight != null && flight.isToggled()
                && mc.player.getFoodData().getFoodLevel() < this.minHunger.getValue();
    }

    private void disconnectLowHeight() {
        String message = this.logMessage.getValue();
        if (message == null || message.isBlank()) {
            message = this.text("messages.lowHeightDisconnect");
        }
        this.print(message);

        if (mc.getConnection() != null) {
            mc.getConnection().getConnection().disconnect(Component.literal(message));
            // 1.4.1：断开后把客户端退回多人游戏界面，否则会停在「连接已丢失」界面
            mc.disconnect(new ProgressScreen(true), false);
            mc.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
        }
    }

    private void startResupply() {
        Resupply.Config config = new Resupply.Config(
                this.minFireworks.getValue(),
                this.minFood.getValue(),
                this.minTotems.getValue(),
                this.supplyFireworks.getValue(),
                this.supplyFood.getValue(),
                this.supplyXp.getValue(),
                this.supplyTotems.getValue(),
                this.boxName.getValue(),
                this.placeRange.getValue(),
                this.xpInterval.getValue(),
                this.durabilityThreshold.getValue());
        this.resupply = new Resupply(config, this);
        this.resupply.start();
    }

    private void equipElytra() {
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        if (player.getInventory().getItem(CHEST_SLOT).is(Items.ELYTRA)) {
            return;
        }
        for (int i = 0; i < 36; i++) {
            if (player.getInventory().getItem(i).is(Items.ELYTRA)) {
                this.equipFromSlot(i);
                return;
            }
        }
        this.warning("messages.noElytra");
    }

    private void checkEmergencyElytraReplace(LocalPlayer player) {
        long now = System.currentTimeMillis();
        if (now - this.lastReplaceTime < REPLACE_COOLDOWN_MS) {
            return;
        }
        ItemStack chest = player.getInventory().getItem(CHEST_SLOT);
        if (!chest.is(Items.ELYTRA)) {
            return;
        }
        int remaining = chest.getMaxDamage() - chest.getDamageValue();
        if (remaining > 2) {
            return;
        }
        int slot = this.findNewElytraAbove(2);
        if (slot != -1) {
            this.equipFromSlot(slot);
            this.lastReplaceTime = now;
            this.warning("messages.emergencyReplaced", remaining);
        }
    }

    private void checkAndReplaceElytra(LocalPlayer player) {
        long now = System.currentTimeMillis();
        if (now - this.lastReplaceTime < REPLACE_COOLDOWN_MS) {
            return;
        }
        ItemStack chest = player.getInventory().getItem(CHEST_SLOT);
        if (!chest.is(Items.ELYTRA)) {
            return;
        }

        int remaining = chest.getMaxDamage() - chest.getDamageValue();
        if (remaining > this.durabilityThreshold.getValue()) {
            return;
        }

        int slot = this.findNewElytra();
        if (slot == -1) {
            this.warning("messages.durabilityLowNoSpare", remaining);
            this.emergencyLanding.start(
                    player,
                    this.landingSearchRadius.getValue(),
                    this.landingArriveDistance.getValue(),
                    this);
            this.stopFlightModule();
            return;
        }

        boolean wasGliding = player.isFallFlying();
        this.equipFromSlot(slot);
        this.lastReplaceTime = now;
        this.warning("messages.durabilityLowReplaced", remaining);

        if (wasGliding && player.fallDistance > 0.2F) {
            PacketSender.send(new ServerboundPlayerCommandPacket(
                    player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
        }
    }

    private void equipFromSlot(int inventorySlot) {
        InventoryUtils.swapSlotsWithPickup(InventoryUtils.toMenuSlot(inventorySlot), ARMOR_CHEST_MENU_SLOT);
    }

    private int findNewElytra() {
        return this.findNewElytraAbove(this.durabilityThreshold.getValue());
    }

    private int findNewElytraAbove(int threshold) {
        LocalPlayer player = mc.player;
        if (player == null) {
            return -1;
        }
        for (int i = 0; i < 36; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.ELYTRA) && stack.getMaxDamage() - stack.getDamageValue() > threshold) {
                return i;
            }
        }
        return -1;
    }

    /** 原版在这里直接 toggle 巡航模块；这里同样只是关闭，由下一次起飞流程重新打开。 */
    private void stopFlightModule() {
        ElytraPitch40 flight = ClientContext.getModule(ElytraPitch40.class);
        if (flight != null && flight.isToggled()) {
            flight.disable();
        }
    }

    // ---------------------------------------------------------------- TravelLog

    @Override
    public void info(String key, Object... args) {
        this.print(this.text(key, args));
    }

    @Override
    public void warning(String key, Object... args) {
        this.print(this.text(key, args));
    }

    @Override
    public void error(String key, Object... args) {
        this.print(this.text(key, args));
    }

    /**
     * {@inheritDoc}
     *
     * <p>这里不用 {@code Translations.format}：它会补 {@code evernight.ui.} 前缀，addon 的键
     * （{@code addons.aurora.*}）经它查不到。改为直接查表后按 {@link String#format} 填参。
     */
    @Override
    public String text(String key, Object... args) {
        String pattern = Translations.get(this.translationKey(key));
        try {
            return String.format(Locale.ROOT, pattern, args);
        } catch (RuntimeException invalidPattern) {
            return pattern;
        }
    }

    private void print(String message) {
        ChatUtils.printChat(Component.literal(message), ChatUtils.moduleSignature(this));
    }
}
