package com.aurora.addon.modules.elytra.elytracollect;

import com.aurora.addon.modules.AuroraModule;
import com.aurora.addon.modules.elytra.ElytraCollect;
import com.aurora.addon.utils.TiziStorage;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.utils.chat.ChatUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * 寻路起飞：读 {@code tizi/<world>/ItemFrame.json} 里最近的展示框坐标，向上抬
 * {@code FrameTargetOffsetY} 格作为目标点，发 {@code #goto} 让服务端寻路过去，到点后交给
 * {@link LockEnd}。
 *
 * <p>移植自 Meteor 插件的 {@code Run}；模块名改成 {@code CollectRun}，避免与其它客户端的
 * 通用名冲突。
 */
public final class CollectRun extends AuroraModule {
    private State state;
    private int ticks;
    private BlockPos target;

    public CollectRun(ModuleCategory category) {
        super("CollectRun", "Pathfinds above the nearest item frame, then hands over to LockEnd.", category);
    }

    private static ElytraCollect cfg() {
        return ElytraCollect.cfg();
    }

    @Override
    public void onEnable() {
        if (this.mc.player == null) {
            this.disable();
            return;
        }
        this.state = State.FIND;
        this.ticks = 0;
        this.target = null;
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.mc.player == null) {
            return;
        }
        this.ticks++;
        switch (this.state) {
            case FIND -> this.find();
            case GOTO -> this.go();
            case WAIT -> this.waitArrive();
            case DONE -> {
            }
        }
    }

    private void find() {
        String world = TiziStorage.worldName();
        if (world == null) {
            this.disable();
            return;
        }
        Path file = TiziStorage.file(world, "ItemFrame.json");
        if (!Files.isRegularFile(file)) {
            this.info("messages.collect.run.noFrameFile");
            this.disable();
            return;
        }
        List<BlockPos> points = TiziStorage.readPoints(file);
        if (points.isEmpty()) {
            this.error("messages.collect.run.noCoordinates");
            this.disable();
            return;
        }

        double px = this.mc.player.getX();
        double py = this.mc.player.getY();
        double pz = this.mc.player.getZ();
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        for (BlockPos candidate : points) {
            double distance = candidate.distToLowCornerSqr(px, py, pz);
            if (distance < best) {
                best = distance;
                nearest = candidate;
            }
        }
        if (nearest == null) {
            this.error("messages.collect.run.noCoordinates");
            this.disable();
            return;
        }

        ElytraCollect config = cfg();
        int addY = config != null ? config.runAddY() : 6;
        this.target = new BlockPos(nearest.getX(), nearest.getY() + addY, nearest.getZ());
        this.state = State.GOTO;
        this.ticks = 0;
    }

    private void go() {
        if (this.mc.player.connection != null) {
            ChatUtils.sendChatOrCommand("#goto " + this.target.getX() + " " + this.target.getY() + " " + this.target.getZ());
        }
        this.info("messages.collect.run.goto", this.target.getX(), this.target.getY(), this.target.getZ());
        this.state = State.WAIT;
        this.ticks = 0;
    }

    private void waitArrive() {
        if (this.ticks < 20 || this.target == null) {
            return;
        }
        if (this.mc.player.getBlockX() == this.target.getX() && this.mc.player.getBlockZ() == this.target.getZ()) {
            LockEnd lockEnd = ClientContext.getModule(LockEnd.class);
            if (lockEnd != null && !lockEnd.isToggled()) {
                lockEnd.enable();
            }
            this.disable();
        }
    }

    private enum State {
        FIND,
        GOTO,
        WAIT,
        DONE
    }
}
