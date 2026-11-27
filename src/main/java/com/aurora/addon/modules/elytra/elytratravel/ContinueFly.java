/*
 * Port of ContinueFly from Aurora (Meteor Client addon, GPL-3.0-only, author n_lzy).
 * This file is distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.modules.elytra.elytratravel;

import com.aurora.addon.modules.elytra.ElytraPitch40;
import evernight.client.ClientAccess;
import evernight.client.ClientContext;
import evernight.client.utils.inventory.InventoryUtils;
import evernight.client.utils.network.PacketSender;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 起飞阶段：原地起跳到 Y=320，然后交给 Pitch40 巡航模块。
 *
 * <p>移植自 Meteor 插件的 {@code ContinueFly}。逐 tick 交替按下 / 松开跳跃键起跳，升空后放烟花加速，
 * 并用视线锁定 {@code (x, 320, z)}。
 */
public final class ContinueFly {
    private static final int TARGET_Y = 320;
    private static final Minecraft mc = ClientAccess.evernightMinecraft;

    private final TravelLog log;
    private final IntSupplier fireworkInterval;
    private final BooleanSupplier quickSwap;

    private BlockPos lockTarget;
    private int fireworkCooldown;
    private int jumpTimer;
    private boolean arrived;
    private boolean active;

    public ContinueFly(TravelLog log, IntSupplier fireworkInterval, BooleanSupplier quickSwap) {
        this.log = log;
        this.fireworkInterval = fireworkInterval;
        this.quickSwap = quickSwap;
    }

    public void start() {
        this.active = true;
        this.arrived = false;
        this.fireworkCooldown = 0;
        this.jumpTimer = 0;
        if (mc.player != null) {
            this.lockTarget = new BlockPos((int) mc.player.getX(), TARGET_Y, (int) mc.player.getZ());
            this.log.info("messages.ascendStart");
        }
    }

    public void reset() {
        this.active = false;
        this.arrived = false;
        this.lockTarget = null;
        if (mc.options != null) {
            mc.options.keyJump.setDown(false);
        }
    }

    public boolean isActive() {
        return this.active;
    }

    public boolean preTick() {
        if (!this.active) {
            return true;
        }
        if (mc.player == null || mc.level == null) {
            this.active = false;
            return true;
        }
        if (this.lockTarget == null) {
            return true;
        }

        this.lockTarget = new BlockPos((int) mc.player.getX(), TARGET_Y, (int) mc.player.getZ());

        if (mc.player.getY() >= TARGET_Y - 2 && !this.arrived) {
            this.arrived = true;
            mc.options.keyJump.setDown(false);
            this.log.info("messages.ascendReached", (int) mc.player.getY());
            ElytraPitch40 flight = ClientContext.getModule(ElytraPitch40.class);
            if (flight != null && !flight.isToggled()) {
                flight.enable();
            }
            this.active = false;
            return true;
        }

        if (!mc.player.isFallFlying() && this.jumpTimer <= 0) {
            mc.options.keyJump.setDown(true);
            this.jumpTimer = 4;
        }

        if (this.jumpTimer > 0) {
            if (this.jumpTimer == 3) {
                mc.options.keyJump.setDown(false);
            }
            if (this.jumpTimer == 2) {
                mc.options.keyJump.setDown(true);
            }
            if (this.jumpTimer == 1) {
                mc.options.keyJump.setDown(false);
                this.releaseFirework();
            }
            this.jumpTimer--;
        }

        if (mc.player.isFallFlying() && mc.player.getY() < this.lockTarget.getY()) {
            if (this.fireworkCooldown > 0) {
                this.fireworkCooldown--;
            } else {
                this.releaseFirework();
            }
        }

        this.aimAtLockTarget();
        return false;
    }

    public void postTick() {
        if (mc.player != null && this.lockTarget != null && !this.arrived && this.active) {
            this.aimAtLockTarget();
        }
    }

    private void aimAtLockTarget() {
        double dx = this.lockTarget.getX() + 0.5 - mc.player.getX();
        double dy = this.lockTarget.getY() + 0.5 - (mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()));
        double dz = this.lockTarget.getZ() + 0.5 - mc.player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
    }

    private void releaseFirework() {
        Predicate<ItemStack> isFirework = stack -> stack.is(Items.FIREWORK_ROCKET);

        if (this.quickSwap.getAsBoolean()) {
            // 鬼手烟花：背包任意位置都能换（1.4.1 由 ElytraTravel 的 ClimbQuickSwap 控制）
            int hotbarSlot = InventoryUtils.findHotbarSlot(isFirework);
            int inventorySlot = hotbarSlot == -1 ? InventoryUtils.findInventorySlot(isFirework, true) : -1;
            if (hotbarSlot == -1 && inventorySlot == -1) {
                this.fireworkCooldown = Math.max(1, this.fireworkInterval.getAsInt());
                return;
            }

            int previousSlot = InventoryUtils.getSelectedSlot();
            if (inventorySlot != -1) {
                InventoryUtils.swapIntoSelectedSlot(inventorySlot);
                PacketSender.sendUseItem(InteractionHand.MAIN_HAND);
                InventoryUtils.swapIntoSelectedSlot(inventorySlot);
            } else {
                InventoryUtils.selectHotbarSlot(hotbarSlot);
                PacketSender.sendUseItem(InteractionHand.MAIN_HAND);
                InventoryUtils.selectHotbarSlot(previousSlot);
            }
        } else {
            for (int slot = 0; slot < 9; slot++) {
                if (mc.player.getInventory().getItem(slot).is(Items.FIREWORK_ROCKET)) {
                    int previousSlot = InventoryUtils.getSelectedSlot();
                    InventoryUtils.selectHotbarSlot(slot);
                    PacketSender.sendUseItem(InteractionHand.MAIN_HAND);
                    InventoryUtils.selectHotbarSlot(previousSlot);
                    break;
                }
            }
        }

        this.fireworkCooldown = Math.max(1, this.fireworkInterval.getAsInt());
    }
}
