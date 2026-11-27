package com.aurora.addon.modules.elytra.elytracollect;

import com.aurora.addon.modules.AuroraModule;
import com.aurora.addon.modules.elytra.ElytraCollect;
import com.aurora.addon.utils.TiziStorage;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.render.WorldRenderEvent;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.mesh.WorldBoxRenderer;
import evernight.client.utils.chat.ChatUtils;
import java.awt.Color;
import java.nio.file.Path;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.phys.AABB;

/**
 * 展示框查找：扫描视野内最近的展示框并写进 {@code ItemFrame.json}；展示框里还有物品时先
 * {@code #goto} 过去把物品打掉，再交给 {@link StoreResupply}。
 *
 * <p>移植自 Meteor 插件的 {@code ItemFrameSearch}。渲染从 {@code Render3DEvent} 换成
 * {@link WorldRenderEvent.Main}，{@code ShapeMode} 换成 {@link ElytraCollect.RenderMode}。
 */
public final class ItemFrameSearch extends AuroraModule {
    private BlockPos nearestFrame;
    private boolean navMode;
    private BlockPos navTarget;
    private double navFrameX;
    private double navFrameY;
    private double navFrameZ;
    private ItemFrame navFrameEntity;
    private int navTicks;
    private int frameCheckDelay;
    private boolean navDone;
    private int storeDelay;

    public ItemFrameSearch(ModuleCategory category) {
        super("ItemFrameSearch", "Finds the nearest item frame, saves its coordinates and clears it.", category);
    }

    private static ElytraCollect cfg() {
        return ElytraCollect.cfg();
    }

    @Override
    public void onEnable() {
        TiziStorage.ensureDirectory();
        this.nearestFrame = null;
        this.navMode = false;
        this.navTarget = null;
        this.navTicks = 0;
        this.navDone = false;
        if (this.mc.player == null) {
            this.disable();
            return;
        }
        this.frameCheckDelay = this.mc.player.tickCount < 80 ? 40 : 0;
        if (this.frameCheckDelay == 0) {
            this.checkFrameAndNavigate();
        }
    }

    @Override
    public void onDisable() {
        this.nearestFrame = null;
    }

    private void checkFrameAndNavigate() {
        this.navDone = true;
        ElytraCollect config = cfg();
        ItemFrame frame = this.findNearestFrameEntity();
        if (frame == null) {
            this.info("messages.collect.itemFrame.none");
            this.disable();
            this.enableLockEnd();
            return;
        }

        this.nearestFrame = frame.blockPosition();
        if (config != null && config.ifSaveToFile()) {
            this.saveToJson(this.nearestFrame);
        }

        if (frame.getItem().isEmpty()) {
            this.info("messages.collect.itemFrame.empty");
            this.disable();
            this.enableLockEnd();
            return;
        }

        this.navMode = true;
        this.navFrameEntity = frame;
        this.navFrameX = frame.getX();
        this.navFrameY = frame.getY();
        this.navFrameZ = frame.getZ();
        this.navTarget = frame.blockPosition().below();
        this.navTicks = 0;
        if (this.mc.player.connection != null) {
            ChatUtils.sendChatOrCommand("#goto " + this.navTarget.getX() + " "
                    + this.navTarget.getY() + " " + this.navTarget.getZ());
        }
        this.info("messages.collect.itemFrame.navigate",
                this.navTarget.getX(), this.navTarget.getY(), this.navTarget.getZ());
    }

    private void enableLockEnd() {
        LockEnd lockEnd = ClientContext.getModule(LockEnd.class);
        if (lockEnd != null && !lockEnd.isToggled()) {
            lockEnd.enable();
        }
    }

    private ItemFrame findNearestFrameEntity() {
        if (this.mc.player == null || this.mc.level == null) {
            return null;
        }
        int viewDist = this.mc.options.renderDistance().get();
        double range = viewDist * 16.0;
        double px = this.mc.player.getX();
        double py = this.mc.player.getY();
        double pz = this.mc.player.getZ();
        AABB box = new AABB(px - range, py - range, pz - range, px + range, py + range, pz + range);
        ItemFrame nearest = null;
        double best = Double.MAX_VALUE;
        for (ItemFrame frame : this.mc.level.getEntitiesOfClass(ItemFrame.class, box, entity -> true)) {
            double distance = frame.blockPosition().distToLowCornerSqr(px, py, pz);
            if (distance < best) {
                best = distance;
                nearest = frame;
            }
        }
        return nearest;
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.mc.player == null || this.mc.level == null) {
            return;
        }
        if (this.storeDelay > 0) {
            this.storeDelay--;
            if (this.storeDelay == 0) {
                this.disable();
                StoreResupply resupply = ClientContext.getModule(StoreResupply.class);
                if (resupply != null && !resupply.isToggled()) {
                    resupply.enable();
                }
            }
            return;
        }
        if (this.frameCheckDelay > 0) {
            this.frameCheckDelay--;
            if (this.frameCheckDelay == 0 && !this.navDone) {
                this.checkFrameAndNavigate();
            }
            return;
        }
        if (!this.navMode) {
            this.scanItemFrame();
            return;
        }

        boolean arrived = this.navTarget != null
                && this.mc.player.getBlockX() == this.navTarget.getX()
                && this.mc.player.getBlockZ() == this.navTarget.getZ()
                && this.mc.player.getBlockY() == this.navTarget.getY();
        if (!arrived) {
            this.navTicks = 0;
            return;
        }

        this.navTicks++;
        double dx = this.navFrameX - this.mc.player.getX();
        double dy = this.navFrameY - (this.mc.player.getY() + this.mc.player.getEyeHeight(this.mc.player.getPose()));
        double dz = this.navFrameZ - this.mc.player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        this.mc.player.setYRot((float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F);
        this.mc.player.setXRot((float) (-Math.toDegrees(Math.atan2(dy, horizontal))));
        if (this.navTicks == 4 && this.navFrameEntity != null && !this.navFrameEntity.isRemoved()) {
            this.mc.gameMode.attack(this.mc.player, this.navFrameEntity);
            this.mc.player.swing(InteractionHand.MAIN_HAND);
        }
        if (this.navTicks >= 6) {
            this.info("messages.collect.itemFrame.attacked");
            this.navMode = false;
            this.storeDelay = 16;
        }
    }

    private void scanItemFrame() {
        int viewDist = this.mc.options.renderDistance().get();
        double range = viewDist * 16.0;
        double px = this.mc.player.getX();
        double py = this.mc.player.getY();
        double pz = this.mc.player.getZ();
        BlockPos nearest = null;
        double best = Double.MAX_VALUE;
        AABB box = new AABB(px - range, py - range, pz - range, px + range, py + range, pz + range);
        for (ItemFrame frame : this.mc.level.getEntitiesOfClass(ItemFrame.class, box, entity -> true)) {
            BlockPos pos = frame.blockPosition();
            double distance = pos.distToLowCornerSqr(px, py, pz);
            if (distance < best) {
                best = distance;
                nearest = pos;
            }
        }
        if (nearest != null && !nearest.equals(this.nearestFrame)) {
            this.nearestFrame = nearest;
            this.info("messages.collect.itemFrame.nearest",
                    nearest.getX(), nearest.getY(), nearest.getZ(), (int) Math.sqrt(best));
            ElytraCollect config = cfg();
            if (config != null && config.ifSaveToFile()) {
                this.saveToJson(nearest);
            }
        }
    }

    @EventHandler
    private void onWorldRender(WorldRenderEvent.Main event) {
        ElytraCollect config = cfg();
        if (config == null || !config.ifShowRender() || this.mc.player == null || this.nearestFrame == null) {
            return;
        }
        AABB box = new AABB(this.nearestFrame);
        Color color = config.ifFrameColor();
        switch (config.ifShapeMode()) {
            case Lines -> WorldBoxRenderer.outlineBox(event.getPoseStack(), box, color, 1.0F);
            case Sides -> WorldBoxRenderer.fillBox(event.getPoseStack(), box, color);
            case Both -> {
                WorldBoxRenderer.fillBox(event.getPoseStack(), box, color);
                WorldBoxRenderer.outlineBox(event.getPoseStack(), box, color, 1.0F);
            }
        }
    }

    private void saveToJson(BlockPos pos) {
        String world = TiziStorage.worldName();
        if (world == null) {
            return;
        }
        Path file = TiziStorage.file(world, "ItemFrame.json");
        Set<String> entries = TiziStorage.readEntries(file);
        if (entries.add(TiziStorage.entry(pos.getX(), pos.getY(), pos.getZ()))) {
            if (!TiziStorage.writeEntries(file, entries)) {
                this.error("messages.collect.itemFrame.saveFailed");
            }
        }
    }
}
