package takeover.agent

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import takeover.agent.hooks.AppHooks
import takeover.agent.hooks.SystemServerHooks

/**
 * LSPosed 入口（assets/xposed_init 指向本类）。
 *
 * 作用域分发 —— 对应 README 勾选：
 *   ☑ system framework  → lpparam.packageName == "android"（运行在 system_server 进程）
 *   ☑ 目标 App（白名单） → AppHooks 内再按包名二次过滤（最小侵入）
 *
 * 后端 A：LSPosed 存活，本类被框架调用；
 * 后端 B：LSPosed 不可用时本类不执行，语义通道改由 frida 侧加载同款 hooks（P2）。
 */
class ModuleEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            when (lpparam.packageName) {
                // system framework 作用域：system_server 进程
                "android" -> {
                    XposedBridge.log("[Takeover] system_server scope detected")
                    SystemServerHooks.install(lpparam)
                }
                // 目标 App 作用域：白名单内才装 Hook
                else -> {
                    if (AppHooks.inWhitelist(lpparam.packageName)) {
                        XposedBridge.log("[Takeover] app scope: ${lpparam.packageName}")
                        AppHooks.install(lpparam)
                    }
                }
            }
        } catch (t: Throwable) {
            // 兼容性铁律：Hook 回调绝不外抛，任何异常只记日志 —— 崩在这里等于重启手机
            XposedBridge.log("[Takeover] handleLoadPackage error @${lpparam.packageName}: $t")
        }
    }
}
