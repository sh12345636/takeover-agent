package takeover.agent.hooks

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONArray
import org.json.JSONObject

/**
 * 目标 App 进程内语义 Hook（后端 A 的 L1 通道）。
 *
 * 能力：控件树 dump（零截图）/ 语义点击 / 中文输入。
 * 兼容性：任何一步失败都只降级、不外抛；树为空时由 FallbackRouter 转坐标链。
 */
object AppHooks {

    @Volatile private var currentActivity: Activity? = null

    /**
     * 完全体接管策略：
     *  - 默认：**第三方 App 全部接管**（系统进程不进，保系统稳定）；
     *  - 覆盖文件 /data/local/tmp/takeover/hook_scope.txt（root 下发）：
     *      每行一个包名 → 只接管列出的（白名单模式）
     *      `!包名` 前缀 → 排除（黑名单模式，可与默认全通混用）
     */
    fun inWhitelist(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        val pkg = lpparam.packageName ?: return false
        if (pkg == "android") return false
        try {
            val f = java.io.File("/data/local/tmp/takeover/hook_scope.txt")
            if (f.canRead()) {
                val lines = f.readText().lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.isNotEmpty()) {
                    val excludes = lines.filter { it.startsWith("!") }.map { it.removePrefix("!") }
                    val includes = lines.filter { !it.startsWith("!") }
                    if (pkg in excludes) return false
                    if (includes.isNotEmpty()) return pkg in includes
                }
            }
        } catch (_: Throwable) { }
        val ai = lpparam.appInfo ?: return false
        val isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        return !isSystem
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        exemptHiddenApi()   // 进程内第一行：hidden API 全量豁免（mViews 等反射可用）

        // 记录前台 Activity，作为控件树根
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    currentActivity = param.thisObject as? Activity
                }
            }
        )

        // 兼容性观测：目标 App 若自绘（树空），这里能第一时间从日志看出来
        XposedBridge.log("[Takeover] AppHooks installed @${lpparam.packageName}")

        // P2：启动 L1 桥服务端（abstract socket tkov_<pkg>，Gateway 经 HookBridge 连入）
        startBridgeServer(lpparam.packageName)
    }

    // ------------------------------------------------------ P2 桥服务端

    /**
     * 目标进程内命令服务：tree / tap / text / ping。
     * 全程反射 android.local.*（hidden API，exemptHiddenApi 已豁免）；
     * 起服务失败只记日志 —— Gateway 会自动降级 root 通道，不影响可用性。
     */
    private fun startBridgeServer(pkg: String) {
        Thread {
            try {
                val ssCls = Class.forName("android.local.LocalServerSocket")
                val ss = ssCls.getConstructor(String::class.java).newInstance("tkov_$pkg")
                val accept = ssCls.getMethod("accept")
                val sockCls = Class.forName("android.local.LocalSocket")
                val getIs = sockCls.getMethod("getInputStream")
                val getOs = sockCls.getMethod("getOutputStream")
                val closeM = sockCls.getMethod("close")
                XposedBridge.log("[Takeover] bridge server UP tkov_$pkg")
                while (true) {
                    val c = accept.invoke(ss)
                    try {
                        val ins = getIs.invoke(c) as java.io.InputStream
                        val outs = getOs.invoke(c) as java.io.OutputStream
                        handleBridge(ins, outs)
                    } catch (t: Throwable) {
                        XposedBridge.log("[Takeover] bridge handle: $t")
                    }
                    try { closeM.invoke(c) } catch (_: Throwable) { }
                }
            } catch (t: Throwable) {
                XposedBridge.log("[Takeover] bridge server FAILED: $t")
            }
        }.apply { isDaemon = true; start() }
    }

    private fun handleBridge(inp: java.io.InputStream, out: java.io.OutputStream) {
        val line = java.io.BufferedReader(java.io.InputStreamReader(inp)).readLine() ?: return
        val resp = JSONObject()
        try {
            val req = JSONObject(line)
            when (req.optString("op")) {
                "ping" -> resp.put("ok", true)
                "tree" -> {
                    val nodes = onUiSync<String> { dumpUiTree() } ?: "[]"
                    resp.put("ok", nodes.isNotEmpty()).put("nodes", nodes)
                }
                "tap" -> {
                    val ok = onUiSync<Boolean> { performClick(req.optInt("vid")) } ?: false
                    resp.put("ok", ok)
                }
                "long" -> {
                    val ok = onUiSync<Boolean> { performLongClick(req.optInt("vid")) } ?: false
                    resp.put("ok", ok)
                }
                "text" -> {
                    val ok = onUiSync<Boolean> { setText(req.optInt("vid"), req.optString("text")) } ?: false
                    resp.put("ok", ok)
                }
                else -> resp.put("ok", false).put("err", "unknown op")
            }
        } catch (t: Throwable) {
            resp.put("ok", false).put("err", t.message ?: "error")
        }
        try {
            out.write((resp.toString() + "\n").toByteArray())
            out.flush()
        } catch (_: Throwable) { }
    }

    /** UI 线程同步执行（Hook 操作必须在主线程）；超时/异常返回 null */
    private fun <T> onUiSync(timeoutMs: Long = 1500, block: () -> T): T? {
        var result: T? = null
        var done = false
        val latch = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { result = block() } catch (_: Throwable) { }
            done = true
            latch.countDown()
        }
        latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return if (done) result else null
    }

    /** hidden API 豁免：VMRuntime.setHiddenApiExemptions("") */
    private fun exemptHiddenApi() {
        try {
            val vm = Class.forName("dalvik.system.VMRuntime")
            val runtime = vm.getDeclaredMethod("getRuntime").invoke(null)
            val set = vm.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
            set.invoke(runtime, arrayOf(""))
        } catch (_: Throwable) { /* LSPosed 环境通常已豁免，失败不致命 */ }
    }

    private fun rootView(): View? =
        currentActivity?.window?.decorView

    // ---------------------------------------------------------------- 控件树

    /** L1 读控件树 → JSON 数组；空树 = 自绘 UI，调用方降级坐标链 */
    fun dumpUiTree(): String {
        val root = rootView() ?: return """[{"err":"no_root"}]"""
        val arr = JSONArray()
        try { dfs(root, arr) } catch (t: Throwable) {
            XposedBridge.log("[Takeover] dumpUiTree error: $t")
        }
        return arr.toString()
    }

    private fun dfs(v: View, out: JSONArray, depth: Int = 0) {
        if (depth > 60 || v.visibility != View.VISIBLE) return
        val o = JSONObject().apply {
            put("cls", v.javaClass.simpleName)
            put("d", depth)
            put("clickable", v.isClickable)
            (v as? TextView)?.let { put("text", it.text?.toString() ?: "") }
            v.contentDescription?.let { put("desc", it.toString()) }
            if (v.id != View.NO_ID) {
                try { put("id", v.resources.getResourceEntryName(v.id)) } catch (_: Throwable) {}
            }
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            put("x", loc[0]); put("y", loc[1]); put("w", v.width); put("h", v.height)
            put("vid", System.identityHashCode(v))   // 后续操作用句柄，避免重名歧义
        }
        out.put(o)
        if (v is ViewGroup) for (i in 0 until v.childCount) dfs(v.getChildAt(i), out, depth + 1)
    }

    // ------------------------------------------------------------ 语义点击

    /** L1 语义点击：按控件句柄（dumpUiTree 的 vid）直接 performClick，零坐标 */
    fun performClick(vid: Int): Boolean {
        val root = rootView() ?: return false
        val target = findViewByVid(root, vid) ?: return false
        return try {
            if (target.isClickable) target.performClick() else {
                // 不可点击时退而求其次：沿父链找可点击项（兼容列表项/容器包裹）
                var p = target.parent
                while (p is View) { if (p.isClickable) return p.performClick(); p = p.parent }
                false
            }
        } catch (t: Throwable) {
            XposedBridge.log("[Takeover] performClick error: $t"); false
        }
    }

    private fun findViewByVid(v: View, vid: Int): View? {
        if (System.identityHashCode(v) == vid) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) {
            findViewByVid(v.getChildAt(i), vid)?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------ 中文输入

    /** L1 长按（语义）；不可长按返回 false → 调用方走 uinput hold */
    fun performLongClick(vid: Int): Boolean {
        val root = rootView() ?: return false
        val target = findViewByVid(root, vid) ?: return false
        return onUiSync(2000) {
            try { target.isLongClickable && target.performLongClick() } catch (_: Throwable) { false }
        } ?: false
    }

    /** L1 文本写入：直接 setText，绕过软键盘与 ASCII 限制（中文友好） */
    fun setText(vid: Int, text: String): Boolean {
        val root = rootView() ?: return false
        val et = findViewByVid(root, vid) as? EditText ?: return false
        return try {
            et.setText(text); et.setSelection(text.length); true
        } catch (t: Throwable) {
            XposedBridge.log("[Takeover] setText error: $t"); false
        }
    }
}
