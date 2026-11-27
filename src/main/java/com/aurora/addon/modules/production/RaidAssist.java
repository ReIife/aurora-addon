/*
 * Port of AttackHelper from Aurora (Meteor Client addon, GPL-3.0-only, author n_lzy).
 * Original: https://github.com/  (Aurora addon jar 1.21.11-1.4.0)
 * This file is distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.modules.production;

import com.aurora.addon.modules.AuroraModule;
import evernight.client.events.core.EventHandler;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.NamedEntry;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.EnumSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.utils.inventory.InventoryUtils;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 袭击辅助：自动喝不祥之瓶、定时左键、自动切剑，方便挂机袭击塔。
 *
 * <p>移植自 Meteor 插件的 {@code AttackHelper}。原版用 {@code InvUtils.findInHotbar} /
 * {@code InvUtils.swap} 与 {@code Utils.leftClick()}，这里换成
 * {@link InventoryUtils#findHotbarSlot(net.minecraft.world.item.Item)}、
 * {@link InventoryUtils#selectHotbarSlot(int)} 与「按下一 tick 左键」的等价实现。
 */
public final class RaidAssist extends AuroraModule {
    private final Setting<Boolean> drinkPotion = this.add(new BooleanSetting("DrinkPotion", true));
    private final Setting<DrinkTrigger> drinkTrigger = this.add(new EnumSetting<>("DrinkTrigger", DrinkTrigger.OnBadOmenLost));
    private final Setting<Integer> drinkDelay = this.add(new NumberSetting<>("DrinkDelay", 10, 0, 400));
    private final Setting<Boolean> autoAttack = this.add(new BooleanSetting("AutoAttack", true));
    private final Setting<Integer> attackInterval = this.add(new NumberSetting<>("AttackInterval", 16, 1, 60));
    private final Setting<Boolean> autoSwitchWeapon = this.add(new BooleanSetting("AutoSwitchWeapon", true));

    private int attackTimer;
    private int drinkDelayTimer;
    private boolean drinkDelaying;
    private DrinkState drinkState = DrinkState.IDLE;
    private int drinkStateTimer;
    private int attackHoldTicks;

    public RaidAssist(ModuleCategory category) {
        super("RaidAssist", "Drinks ominous bottles, attacks on an interval and switches to a sword while you idle at a raid farm.", category);
    }

    @Override
    public void onEnable() {
        this.attackTimer = 0;
        this.drinkDelayTimer = 0;
        this.drinkDelaying = false;
        this.resetDrinkState();
    }

    @Override
    public void onDisable() {
        this.attackHoldTicks = 0;
        if (mc.options != null) {
            mc.options.keyAttack.setDown(false);
            mc.options.keyUse.setDown(false);
        }
        this.resetDrinkState();
    }

    @Override
    public String getInfo() {
        return this.drinkState == DrinkState.IDLE ? null
                : Translations.get(this.translationKey("info." + this.drinkState.name().toLowerCase(java.util.Locale.ROOT)));
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (!this.inGame()) {
            return;
        }

        if (this.attackHoldTicks > 0 && --this.attackHoldTicks == 0) {
            mc.options.keyAttack.setDown(false);
        }

        this.tickDrinkState();

        if (this.drinkPotion.getValue() && this.drinkState == DrinkState.IDLE) {
            this.handleDrinkPotion();
        }

        if (!this.autoAttack.getValue() || this.drinkState != DrinkState.IDLE) {
            return;
        }

        if (++this.attackTimer >= this.attackInterval.getValue()) {
            this.attackTimer = 0;
            this.switchToSword();
            this.leftClick();
        }
    }

    private void handleDrinkPotion() {
        boolean shouldDrink = switch (this.drinkTrigger.getValue()) {
            case OnBadOmenLost -> !mc.player.hasEffect(MobEffects.BAD_OMEN);
            case OnRaidOmenLost -> !mc.player.hasEffect(MobEffects.RAID_OMEN);
        };

        if (!shouldDrink) {
            this.drinkDelaying = false;
            this.drinkDelayTimer = 0;
            return;
        }

        if (!this.drinkDelaying) {
            this.drinkDelaying = true;
            this.drinkDelayTimer = 0;
        }

        if (++this.drinkDelayTimer >= this.drinkDelay.getValue()) {
            this.drinkDelaying = false;
            this.drinkDelayTimer = 0;
            this.startDrink();
        }
    }

    private void startDrink() {
        if (this.drinkState != DrinkState.IDLE) {
            return;
        }
        int bottleSlot = InventoryUtils.findHotbarSlot(Items.OMINOUS_BOTTLE);
        if (bottleSlot < 0) {
            return;
        }

        int selectedSlot = mc.player.getInventory().getSelectedSlot();
        if (bottleSlot != selectedSlot) {
            InventoryUtils.selectHotbarSlot(bottleSlot);
            this.drinkState = DrinkState.SWITCHING;
            this.drinkStateTimer = 0;
        } else {
            this.startUsingItem();
        }
    }

    private void tickDrinkState() {
        switch (this.drinkState) {
            case IDLE -> {
            }
            case SWITCHING -> {
                if (++this.drinkStateTimer >= 1) {
                    this.startUsingItem();
                }
            }
            case USING -> {
                if (!mc.player.isUsingItem()) {
                    this.finishDrink();
                }
            }
            case RELEASING -> {
                if (++this.drinkStateTimer >= 5) {
                    this.resetDrinkState();
                }
            }
        }
    }

    private void startUsingItem() {
        ItemStack mainHand = mc.player.getInventory().getItem(mc.player.getInventory().getSelectedSlot());
        if (mainHand.getItem() != Items.OMINOUS_BOTTLE) {
            this.resetDrinkState();
            return;
        }
        mc.options.keyUse.setDown(true);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        this.drinkState = DrinkState.USING;
    }

    private void finishDrink() {
        mc.options.keyUse.setDown(false);
        this.drinkState = DrinkState.RELEASING;
        this.drinkStateTimer = 0;
    }

    private void resetDrinkState() {
        mc.options.keyUse.setDown(false);
        this.drinkState = DrinkState.IDLE;
        this.drinkStateTimer = 0;
    }

    private void switchToSword() {
        if (!this.autoSwitchWeapon.getValue()) {
            return;
        }
        int selected = mc.player.getInventory().getSelectedSlot();
        if (mc.player.getInventory().getItem(selected).is(ItemTags.SWORDS)) {
            return;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slot != selected && mc.player.getInventory().getItem(slot).is(ItemTags.SWORDS)) {
                InventoryUtils.selectHotbarSlot(slot);
                return;
            }
        }
    }

    /**
     * 等价于 Meteor 的 {@code Utils.leftClick()}：按住左键两个 tick 再松开。
     *
     * <p>按住时间不能是 0 tick —— {@code Minecraft#handleKeybinds} 每 tick 只读一次按下状态，
     * 而本模块的 tick 处理器与它谁先执行取决于事件发布点，留两个 tick 才能保证至少被读到一次。
     */
    private void leftClick() {
        mc.options.keyAttack.setDown(true);
        this.attackHoldTicks = 2;
    }

    private enum DrinkState {
        IDLE,
        SWITCHING,
        USING,
        RELEASING
    }

    /** 喝药时机；{@code getName()} 是稳定标识（进配置），显示名查语言表。 */
    public enum DrinkTrigger implements NamedEntry {
        OnBadOmenLost("OnBadOmenLost"),
        OnRaidOmenLost("OnRaidOmenLost");

        private final String name;

        DrinkTrigger(String name) {
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
    }
}
