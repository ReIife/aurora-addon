/*
 * Port of AutoEat from Aurora (Meteor Client addon, GPL-3.0-only, author n_lzy).
 * This file is distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.modules.utils;

import com.aurora.addon.modules.elytra.ElytraPitch40;
import evernight.client.ClientAccess;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.utils.inventory.InventoryUtils;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 鞘翅巡航时的进食助手：只在 Pitch40 巡航模块启用、且饥饿值低于阈值时吃金胡萝卜。
 *
 * <p>移植自 Meteor 插件的 {@code AutoEat} 工具类（不是模块）。与原版的差异：
 * <ul>
 *     <li>Meteor 用静态事件总线在构造时订阅；Evernight 需要显式
 *     {@code ClientAccess.eventBus.subscribe(this)}。</li>
 *     <li>原版检查的是 {@code ElytraInfiniteFlight}，这里对应移植后的 {@link ElytraPitch40}。</li>
 *     <li>不再提供 {@code INSTANCE} 单例：单例会在类加载时永久订阅，改由使用方持有一个实例。</li>
 * </ul>
 */
public final class AutoEat {
    private static final Minecraft mc = ClientAccess.evernightMinecraft;

    private BooleanSupplier enabled = () -> false;
    private IntSupplier minHunger = () -> 18;
    private Runnable noFoodLogger = () -> {
    };

    private EatState eatState = EatState.IDLE;
    private int eatOriginalSlot = -1;
    private int eatSwappedFromInv = -1;
    private int stateTimer;
    private int eatStartTick;
    private int lastNoFoodTick = -100;
    private int releaseAtTick;

    public AutoEat() {
        ClientAccess.eventBus.subscribe(this);
    }

    public void configure(BooleanSupplier enabled, IntSupplier minHunger, Runnable noFoodLogger) {
        this.enabled = enabled;
        this.minHunger = minHunger;
        this.noFoodLogger = noFoodLogger;
    }

    public boolean isIdle() {
        return this.eatState == EatState.IDLE;
    }

    public boolean isEating() {
        return this.eatState != EatState.IDLE;
    }

    public void start() {
        if (this.eatState != EatState.IDLE || !this.enabled.getAsBoolean()) {
            return;
        }
        ElytraPitch40 flight = ClientContext.getModule(ElytraPitch40.class);
        if (flight != null && flight.isToggled() && mc.player != null && mc.level != null) {
            this.tryBeginEat();
        }
    }

    public void stop() {
        if (mc.options != null) {
            mc.options.keyUse.setDown(false);
        }
        if (this.eatOriginalSlot >= 0 && mc.player != null) {
            InventoryUtils.selectHotbarSlot(this.eatOriginalSlot);
        }
        if (this.eatSwappedFromInv >= 0 && this.eatOriginalSlot >= 0 && mc.player != null) {
            InventoryUtils.swapWithHotbarSlot(InventoryUtils.toMenuSlot(this.eatSwappedFromInv), this.eatOriginalSlot);
        }

        this.eatState = EatState.IDLE;
        this.eatOriginalSlot = -1;
        this.eatSwappedFromInv = -1;
        this.stateTimer = 0;
        this.releaseAtTick = 0;
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.eatState == EatState.IDLE) {
            return;
        }
        if (mc.player == null || mc.level == null) {
            this.stop();
            return;
        }
        switch (this.eatState) {
            case SWITCHING -> this.tickSwitching();
            case USING -> this.tickUsing();
            case RELEASING -> this.tickReleasing();
            default -> {
            }
        }
    }

    private void tryBeginEat() {
        int hunger = mc.player.getFoodData().getFoodLevel();
        if (hunger >= this.minHunger.getAsInt()) {
            return;
        }

        int selected = InventoryUtils.getSelectedSlot();
        if (mc.player.getInventory().getItem(selected).is(Items.GOLDEN_CARROT)) {
            this.beginEat(selected, -1, selected);
            return;
        }
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.GOLDEN_CARROT)) {
                this.beginEat(i, -1, selected);
                return;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).is(Items.GOLDEN_CARROT)) {
                InventoryUtils.swapWithHotbarSlot(InventoryUtils.toMenuSlot(i), selected);
                this.beginEat(selected, i, selected);
                return;
            }
        }

        if (mc.player.tickCount - this.lastNoFoodTick > 100) {
            this.lastNoFoodTick = mc.player.tickCount;
            this.noFoodLogger.run();
        }
    }

    private void beginEat(int hotbarSlot, int swappedFromInventory, int originalSlot) {
        this.eatOriginalSlot = originalSlot;
        this.eatSwappedFromInv = swappedFromInventory;
        if (InventoryUtils.getSelectedSlot() != hotbarSlot) {
            InventoryUtils.selectHotbarSlot(hotbarSlot);
            this.eatState = EatState.SWITCHING;
            this.stateTimer = 0;
        } else if (swappedFromInventory != -1) {
            this.eatState = EatState.SWITCHING;
            this.stateTimer = 0;
        } else {
            this.enterUsing();
        }
    }

    private void tickSwitching() {
        this.stateTimer++;
        if (this.stateTimer >= 1) {
            this.enterUsing();
        }
    }

    private void enterUsing() {
        ItemStack held = mc.player.getInventory().getItem(InventoryUtils.getSelectedSlot());
        if (!held.is(Items.GOLDEN_CARROT)) {
            this.stop();
            return;
        }
        mc.options.keyUse.setDown(true);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        this.eatState = EatState.USING;
        this.eatStartTick = mc.player.tickCount;
    }

    private void tickUsing() {
        if (!mc.player.isUsingItem()) {
            this.enterReleasing();
            return;
        }
        if (mc.player.tickCount - this.eatStartTick > 60) {
            this.enterReleasing();
        }
    }

    private void enterReleasing() {
        mc.options.keyUse.setDown(false);

        int ping = 0;
        if (mc.getConnection() != null && mc.player != null) {
            PlayerInfo entry = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (entry != null) {
                ping = entry.getLatency();
            }
        }

        int pingTicks = Math.min(ping / 50, 4);
        int jitter = ThreadLocalRandom.current().nextInt(1, 4);
        this.releaseAtTick = mc.player.tickCount + pingTicks + jitter;
        this.eatState = EatState.RELEASING;
        this.stateTimer = 0;
    }

    private void tickReleasing() {
        this.stateTimer++;
        if (mc.player.tickCount >= this.releaseAtTick || this.stateTimer >= 8) {
            this.stop();
        }
    }

    private enum EatState {
        IDLE,
        SWITCHING,
        USING,
        RELEASING
    }
}
