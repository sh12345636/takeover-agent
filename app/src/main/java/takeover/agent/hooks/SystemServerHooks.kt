package takeover.agent.hooks

import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * system framework 作用域 Hook（运行于 system_server，后端 A 标志性能力）。
 *
 * 职责（按优先级）：
 *  1. [事件审计] 旁路 observe 全部 injectInputEvent —— 兼容性遥测的核心数据源：
 *     谁在注入、注入到哪、延迟多少，日志可查表定位问题；
 *  2. [注入执行] 通过反射拿到 system_server 内的 InputManager 单例，供本进程
 *     直接调用 injectInputEvent（system uid 天然持有 INJECT_EVENTS，
 *     Android 14+ 对非系统调用者收紧后此通道依然成立）；
 *  3. [前台栈增强] hook IActivityTaskManager.getTasks 兜底 dumpsys 不可用的场景。
 *
 * 注意：system_server 内的回调必须全程 try-catch —— 崩溃 = 手机反复重启。
 *       配合 ModuleEntry 的统一兜底 + LSPosed 管理器可秒关（见 README）。
 */
object SystemServerHooks {

    /** system_server 内的 InputManager 单例（安装成功后非空） */
    @Volatile var inputManager: InputManager? = null
        private set

    /** 审计回调：Gateway 侧注册，收到 (事件, 耗时) 用于遥测 */
    @Volatile var auditSink: ((InputEvent, Long) -> Unit)? = null

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader

        // ---- 1) InputManager 单例 + 注入审计 ----------------------------------
        val imCls = XposedHelpers.findClass("android.hardware.input.InputManager", cl)
        XposedHelpers.findAndHookMethod(imCls, "getInstance", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                inputManager = param.result as? InputManager
            }
        })
        // 旁路审计：所有 injectInputEvent（含 shell `input` 命令、其他模块注入）都路过这里
        XposedHelpers.findAndHookMethod(
            imCls, "injectInputEvent",
            InputEvent::class.java, Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val ev = param.args[0] as? InputEvent ?: return
                    val t0 = SystemClock.elapsedRealtime()
                    auditSink?.invoke(ev, t0)
                    // 不改写、不拦截 —— 只观测（最小侵入原则）
                    if (ev is MotionEvent && ev.actionMasked == MotionEvent.ACTION_DOWN) {
                        XposedBridge.log(
                            "[Takeover] audit DOWN pid=${android.os.Process.myPid()} " +
                            "src=0x${Integer.toHexString(ev.source)} at=$t0"
                        )
                    }
                }
            }
        )

        // ---- 2) 注入执行：反射便捷入口（MODE_ASYNC = 0） ------------------------
        //     由本进程内组件调用；跨进程通道见 P2（Gateway 优先走 uinput，见 FallbackRouter）
        try {
            val im = XposedHelpers.callStaticMethod(imCls, "getInstance") as InputManager
            inputManager = im
            XposedBridge.log("[Takeover] system_server InputManager hooked OK")
        } catch (t: Throwable) {
            XposedBridge.log("[Takeover] InputManager getInstance failed: $t") // capability=false 走降级
        }

        // ---- 3) 前台栈读取兜底 ------------------------------------------------
        try {
            val atmCls = XposedHelpers.findClass("android.app.ActivityTaskManager", cl)
            XposedHelpers.findAndHookMethod(
                atmCls, "getTasks", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 结果交由审计侧序列化（P2 接 Gateway 的 get_state）
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[Takeover] ATM hook skipped (not fatal): $t")
        }
    }

    /**
     * system_server 进程内的注入执行（供同进程组件调用）。
     * 返回是否受理；false = capability 不可用，调用方走降级链。
     */
    fun injectFromSystemServer(ev: InputEvent): Boolean {
        val im = inputManager ?: return false
        return try {
            // INJECT_INPUT_EVENT_MODE_ASYNC = 0；调用者为 system uid，权限天然满足
            val ok = XposedHelpers.callMethod(im, "injectInputEvent", ev, 0) as? Boolean ?: false
            ok
        } catch (t: Throwable) {
            XposedBridge.log("[Takeover] inject failed: $t")
            false
        }
    }

    /** 构造单指 MotionEvent（系统侧注入用；CLOCK_MONOTONIC 时间戳） */
    fun obtainTap(x: Float, y: Float): MotionEvent {
        val t = SystemClock.uptimeMillis()
        return MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0).apply {
            source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        }.also {
            // down 立即补一条 up（骨架：完整手势由 Gateway 分帧下发，见 P2）
        }
    }
}
