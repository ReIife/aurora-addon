package com.aurora.addon;

import com.aurora.addon.commands.LoginCommand;
import com.aurora.addon.modules.combat.BreakESP;
import com.aurora.addon.modules.combat.PacketMine;
import com.aurora.addon.modules.elytra.ElytraCollect;
import com.aurora.addon.modules.elytra.ElytraPitch40;
import com.aurora.addon.modules.elytra.ElytraRouteCalculate;
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
import com.aurora.addon.modules.other.AutoLogin;
import com.aurora.addon.modules.other.BetterDirection;
import com.aurora.addon.modules.other.ChatGuard;
import com.aurora.addon.modules.production.AfkAssist;
import com.aurora.addon.modules.production.AutoVault;
import com.aurora.addon.modules.production.RaidAssist;
import com.aurora.addon.utils.BreakTracker;
import com.aurora.addon.utils.LoginAccounts;
import evernight.addon.AddonContext;
import evernight.addon.EvernightAddon;
import evernight.client.modules.Module;
import evernight.client.modules.ModuleCategory;

import java.util.function.Supplier;

/**
 * Aurora 插件入口。
 *
 * <p>{@code fabric.mod.json} 的 {@code evernight-addon} 入口键指向本类；所有注册都在
 * {@link #onInitialize(AddonContext)} 里一次性完成，运行期不支持动态增删。
 *
 * <p>分类：{@code Elytra} / {@code Production} / {@code Other} 由插件自建，
 * 战斗相关模块直接用本体内置的 {@link ModuleCategory#COMBAT}——**不能自建同名分类**：
 * 1.2.4 起 {@code registerCategory} 遇到重名会抛 {@code IllegalStateException}，
 * 而 {@code onInitialize} 里任何未捕获异常都会让整个插件初始化中止（1.5.0 的真实事故）。
 *
 * <p>同理，模块名与指令名在 Evernight 里是**全局**的（跨插件唯一）。为了让「某个名字被别的插件占了」
 * 不再拖垮整个插件，这里统一走 {@link #registerSafely} 包一层：单个模块注册失败只记日志并跳过，
 * 其余模块照常加载。
 */
public final class Aurora extends EvernightAddon {
    public static final String ID = "aurora";

    private static final int EXPECTED_MODULES = 21;

    private int registered;

    @Override
    public void onInitialize(AddonContext context) {
        ModuleCategory elytra = registerCategory(context, "Elytra", ModuleCategory.MISC);
        ModuleCategory production = registerCategory(context, "Production", ModuleCategory.MISC);
        ModuleCategory other = registerCategory(context, "Other", ModuleCategory.MISC);

        // Combat：发包挖掘 + 挖掘显示（共用同一个挖掘进度跟踪器），用本体内置分类
        BreakTracker breakTracker = new BreakTracker();
        context.subscribe(breakTracker);
        registerSafely(context, () -> new PacketMine(ModuleCategory.COMBAT));
        registerSafely(context, () -> new BreakESP(ModuleCategory.COMBAT, breakTracker));

        // 自动登录：账号表单独存 evernight/aurora-autologin.json，指令管理
        LoginAccounts loginAccounts = new LoginAccounts();
        context.registerConfig(loginAccounts);
        AutoLogin autoLogin = registerSafely(context, () -> new AutoLogin(other, loginAccounts));
        if (autoLogin != null) {
            try {
                context.registerCommand(new LoginCommand(loginAccounts, autoLogin));
            } catch (RuntimeException failure) {
                context.logger().warn("Aurora: command 'autologin' unavailable: {}", failure.getMessage());
            }
        }

        // Elytra：Pitch40 巡航、末地巡航、自动收集（含子模块）
        registerSafely(context, () -> new ElytraPitch40(elytra));
        registerSafely(context, () -> new ElytraTravel(elytra));
        registerSafely(context, () -> new ElytraCollect(elytra));
        registerSafely(context, () -> new ElytraRouteCalculate(elytra));

        // 自动收集的子模块：注册（保留功能与配置），但从 ClickGUI 隐藏，与上游
        // Config#hiddenModules 的行为一致。上游隐藏的正是这 9 个，ElytraCollect 本身不隐藏。
        hide(registerSafely(context, () -> new AutoRestart(elytra)));
        hide(registerSafely(context, () -> new CollectRun(elytra)));
        hide(registerSafely(context, () -> new EndShipFinder(elytra)));
        hide(registerSafely(context, () -> new ItemFrameSearch(elytra)));
        hide(registerSafely(context, () -> new LockEnd(elytra)));
        hide(registerSafely(context, () -> new LockStart(elytra)));
        hide(registerSafely(context, () -> new StoreResupply(elytra)));
        hide(registerSafely(context, () -> new StowElytra(elytra)));
        hide(registerSafely(context, () -> new WaypointLock(elytra)));

        // Production
        registerSafely(context, () -> new AfkAssist(production));
        registerSafely(context, () -> new RaidAssist(production));
        registerSafely(context, () -> new AutoVault(production));

        // Other
        registerSafely(context, () -> new BetterDirection(other));
        registerSafely(context, () -> new ChatGuard(other));

        context.logger().info("Aurora {} initialized: {}/{} modules registered, {} hidden",
                context.metadata().version(), this.registered, EXPECTED_MODULES, HiddenModules.count());
    }

    /**
     * 注册模块；名字被占用或构造失败时只记警告并返回 {@code null}，不中断整个插件的初始化。
     *
     * <p>Evernight 的模块名是全局唯一的，插件生态里任何一次撞名都不应该让玩家「整个插件都看不到」。
     */
    private <T extends Module> T registerSafely(AddonContext context, Supplier<T> factory) {
        T module;
        try {
            module = factory.get();
        } catch (RuntimeException failure) {
            context.logger().error("Aurora: module construction failed", failure);
            return null;
        }
        try {
            context.registerModule(module);
            this.registered++;
            return module;
        } catch (RuntimeException failure) {
            context.logger().warn("Aurora: module '{}' skipped: {}", module.getName(), failure.getMessage());
            return null;
        }
    }

    private static void hide(Module module) {
        if (module != null) {
            HiddenModules.hide(module);
        }
    }

    /**
     * 注册插件分类；重名时退到备用分类并记警告。
     *
     * <p>1.2.4 起重名会抛异常，而分类名同样全局唯一（本体已有 Combat/Misc/Render/Movement/Player/
     * Auto/Exploit/Client/HUD，其他插件也会占用名字）。
     */
    private static ModuleCategory registerCategory(AddonContext context, String name, ModuleCategory fallback) {
        try {
            return context.registerCategory(name);
        } catch (RuntimeException failure) {
            context.logger().warn("Aurora: category '{}' unavailable ({}), falling back to {}",
                    name, failure.getMessage(), fallback.getName());
            return fallback;
        }
    }
}
