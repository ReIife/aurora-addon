package com.aurora.addon;

import com.aurora.addon.modules.elytra.ElytraCollect;
import com.aurora.addon.modules.elytra.ElytraPitch40;
import com.aurora.addon.modules.elytra.ElytraTravel;
import com.aurora.addon.modules.elytra.elytracollect.AutoRestart;
import com.aurora.addon.modules.elytra.elytracollect.CollectRun;
import com.aurora.addon.modules.elytra.elytracollect.EndShipFinder;
import com.aurora.addon.modules.elytra.elytracollect.ItemFrameSearch;
import com.aurora.addon.modules.elytra.elytracollect.LockEnd;
import com.aurora.addon.modules.elytra.elytracollect.LockStart;
import com.aurora.addon.modules.elytra.elytracollect.StoreResupply;
import com.aurora.addon.modules.elytra.elytracollect.StowElytra;
import com.aurora.addon.modules.elytra.elytracollect.WaypointLock;
import com.aurora.addon.modules.other.BetterDirection;
import com.aurora.addon.modules.other.ChatGuard;
import com.aurora.addon.modules.production.AfkAssist;
import com.aurora.addon.modules.production.RaidAssist;
import evernight.addon.AddonContext;
import evernight.addon.EvernightAddon;
import evernight.client.modules.ModuleCategory;

/**
 * Aurora 插件入口。
 *
 * <p>{@code fabric.mod.json} 的 {@code evernight-addon} 入口键指向本类；所有注册都在
 * {@link #onInitialize(AddonContext)} 里一次性完成，运行期不支持动态增删。
 *
 * <p>原 Meteor 版的三个分类（Aurora 鞘翅 / Aurora 生电辅助 / Aurora 其他功能）在这里用
 * {@link AddonContext#registerCategory(String)} 重建，显示名来自
 * {@code addons.aurora.names.categories.*}。
 *
 * <p>「自动收集鞘翅」那一组子模块在原版里同样是独立模块（只是被入口强制隐藏），这里照原结构注册：
 * 它们自己出现在 Elytra 分类里，玩家可以直接开关，`ElytraCollect` 也会在运行期拉起它们。
 */
public final class Aurora extends EvernightAddon {
    public static final String ID = "aurora";

    @Override
    public void onInitialize(AddonContext context) {
        ModuleCategory elytra = context.registerCategory("Elytra");
        ModuleCategory production = context.registerCategory("Production");
        ModuleCategory other = context.registerCategory("Other");

        // Elytra：Pitch40 巡航、末地巡航、自动收集（含子模块）
        context.registerModule(new ElytraPitch40(elytra));
        context.registerModule(new ElytraTravel(elytra));
        context.registerModule(new ElytraCollect(elytra));

        // 自动收集的子模块：注册（保留功能与配置），但从 ClickGUI 隐藏，与上游
        // Config#hiddenModules 的行为一致。上游隐藏的正是这 9 个，ElytraCollect 本身不隐藏。
        HiddenModules.hide(context.registerModule(new AutoRestart(elytra)));
        HiddenModules.hide(context.registerModule(new CollectRun(elytra)));
        HiddenModules.hide(context.registerModule(new EndShipFinder(elytra)));
        HiddenModules.hide(context.registerModule(new ItemFrameSearch(elytra)));
        HiddenModules.hide(context.registerModule(new LockEnd(elytra)));
        HiddenModules.hide(context.registerModule(new LockStart(elytra)));
        HiddenModules.hide(context.registerModule(new StoreResupply(elytra)));
        HiddenModules.hide(context.registerModule(new StowElytra(elytra)));
        HiddenModules.hide(context.registerModule(new WaypointLock(elytra)));

        // Production
        context.registerModule(new AfkAssist(production));
        context.registerModule(new RaidAssist(production));

        // Other
        context.registerModule(new BetterDirection(other));
        context.registerModule(new ChatGuard(other));

        context.logger().info("Aurora {} initialized: {} modules registered, {} hidden",
                context.metadata().version(), 16, HiddenModules.count());
    }
}
