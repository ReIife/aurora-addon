/*
 * Port of EmergencyLanding from Aurora (Meteor Client addon, GPL-3.0-only, author n_lzy).
 * This file is distributed under GPL-3.0-only, like Evernight itself.
 */
package com.aurora.addon.modules.elytra.elytratravel;

import evernight.client.ClientAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * 紧急降落：在末地石平台上螺旋搜索落点，先锁定落点上空 40 格，再俯冲、最后垂直下降。
 *
 * <p>移植自 Meteor 插件的 {@code EmergencyLanding}，逻辑与搜索预算、冷却、判定半径保持一致。
 */
public final class EmergencyLanding {
    private static final int SEARCH_BUDGET_PER_TICK = 24;
    private static final int SEARCH_COOLDOWN_TICKS = 60;
    private static final int SEARCH_MIN_Y = 45;
    private static final int SEARCH_MAX_Y = 100;
    private static final int LOCK_ABOVE = 40;
    private static final int DESCEND_HEIGHT = 16;
    private static final int APPROACH_HORIZ = 24;
    private static final Minecraft mc = ClientAccess.evernightMinecraft;

    private State state = State.IDLE;
    private int searchRadius;
    private int searchAngle;
    private int searchCooldown;
    private int maxSearchRadius;
    private int arriveDistance;
    private BlockPos landingTarget;
    private BlockPos fixedTarget;
    private BlockPos lockTarget;
    private boolean descending;
    private TravelLog log;

    public boolean start(LocalPlayer player, int maxSearchRadius, int arriveDistance, TravelLog log) {
        if (player == null) {
            return false;
        }
        this.state = State.SEARCHING;
        this.maxSearchRadius = maxSearchRadius;
        this.arriveDistance = arriveDistance;
        this.searchRadius = 4;
        this.searchAngle = 0;
        this.searchCooldown = 0;
        this.landingTarget = null;
        this.fixedTarget = null;
        this.lockTarget = null;
        this.descending = false;
        this.log = log;
        return true;
    }

    public void reset() {
        this.state = State.IDLE;
        this.landingTarget = null;
        this.fixedTarget = null;
        this.lockTarget = null;
        this.descending = false;
        this.log = null;
    }

    public boolean isActive() {
        return this.state == State.SEARCHING || this.state == State.LANDING;
    }

    public State getState() {
        return this.state;
    }

    public BlockPos getLandingTarget() {
        return this.landingTarget;
    }

    public BlockPos getFixedTarget() {
        return this.fixedTarget;
    }

    public Result tick(LocalPlayer player) {
        if (player == null) {
            return Result.IDLE;
        }
        return switch (this.state) {
            case SEARCHING -> this.tickSearch(player) ? Result.LANDING : Result.SEARCHING;
            case LANDING -> {
                if (this.tickLanding(player)) {
                    this.state = State.ARRIVED;
                    yield Result.ARRIVED;
                }
                yield Result.LANDING;
            }
            default -> Result.IDLE;
        };
    }

    private boolean tickSearch(LocalPlayer player) {
        if (mc.level == null) {
            return false;
        }
        player.setXRot(0.0F);
        if (this.searchCooldown > 0) {
            this.searchCooldown--;
            return false;
        }

        int budget = SEARCH_BUDGET_PER_TICK;
        while (budget-- > 0) {
            if (this.searchAngle >= 360) {
                this.searchAngle = 0;
                this.searchRadius += 2;
                if (this.searchRadius > this.maxSearchRadius) {
                    this.searchCooldown = SEARCH_COOLDOWN_TICKS;
                    this.searchRadius = 4;
                    this.searchAngle = 0;
                    return false;
                }
            }

            int circumference = Math.max(8, (int) ((Math.PI * 2) * this.searchRadius));
            int angleStep = Math.max(1, 360 / circumference);
            double radians = Math.toRadians(this.searchAngle);
            int x = player.getBlockX() + (int) (Math.cos(radians) * this.searchRadius);
            int z = player.getBlockZ() + (int) (Math.sin(radians) * this.searchRadius);
            this.searchAngle += angleStep;

            if (!mc.level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }

            for (int y = SEARCH_MAX_Y; y >= SEARCH_MIN_Y; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                if (!mc.level.getBlockState(pos).is(Blocks.END_STONE)) {
                    continue;
                }
                BlockPos landing = pos.above();
                if (this.isValidLandingSpot(landing)) {
                    this.landingTarget = landing;
                    this.fixedTarget = landing.above(LOCK_ABOVE);
                    this.lockTarget = this.fixedTarget;
                    this.descending = false;
                    this.state = State.LANDING;
                    this.log.info("messages.landingSpotFound",
                            landing.getX(), landing.getY(), landing.getZ(),
                            this.fixedTarget.getX(), this.fixedTarget.getY(), this.fixedTarget.getZ());
                    return true;
                }
                break;
            }
        }

        return false;
    }

    private boolean isValidLandingSpot(BlockPos landing) {
        if (mc.level == null) {
            return false;
        }
        if (!mc.level.getBlockState(landing.below()).is(Blocks.END_STONE)) {
            return false;
        }
        for (int dy = 1; dy <= 3; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (!mc.level.getBlockState(landing.offset(dx, -dy, dz)).is(Blocks.END_STONE)) {
                        return false;
                    }
                }
            }
        }
        for (int dy = 0; dy <= 20; dy++) {
            if (!mc.level.getBlockState(landing.above(dy)).isAir()) {
                return false;
            }
        }
        return true;
    }

    private boolean tickLanding(LocalPlayer player) {
        if (this.landingTarget == null || this.fixedTarget == null || this.lockTarget == null) {
            this.state = State.IDLE;
            return false;
        }

        double adx = this.landingTarget.getX() + 0.5 - player.getX();
        double ady = this.landingTarget.getY() + 0.5 - player.getY();
        double adz = this.landingTarget.getZ() + 0.5 - player.getZ();
        double arriveDistanceSqr = Math.sqrt(adx * adx + ady * ady + adz * adz);
        if (!player.isFallFlying() && arriveDistanceSqr <= this.arriveDistance) {
            return true;
        }

        double dx = this.lockTarget.getX() + 0.5 - player.getX();
        double dy = this.lockTarget.getY() + 0.5 - (player.getY() + player.getEyeHeight(player.getPose()));
        double dz = this.lockTarget.getZ() + 0.5 - player.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        if (!this.descending && horizontalDistance <= APPROACH_HORIZ
                && Math.abs(this.fixedTarget.getY() - player.getY()) <= DESCEND_HEIGHT) {
            this.descending = true;
        }

        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        if (this.descending) {
            this.lockTarget = new BlockPos(this.lockTarget.getX(), (int) player.getY(), this.lockTarget.getZ());
            player.setYRot(yaw);
            player.setXRot(0.0F);
        } else {
            float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontalDistance)));
            player.setYRot(yaw);
            player.setXRot(pitch);
        }
        return false;
    }

    public enum Result {
        IDLE,
        SEARCHING,
        LANDING,
        ARRIVED
    }

    public enum State {
        IDLE,
        SEARCHING,
        LANDING,
        ARRIVED
    }
}
