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

    /** 骨架白名单（生产版改读 /data/local/tmp/takeover/whitelist.txt，root chmod 644 下发） */
    private val TARGET_WHITELIST = setOf(
        "com.tencent.mm",
        "com.ss.android.ugc.aweme",
        "com.android.settings"
    )

    @Volatile private var currentActivity: Activity? = null

    fun inWhitelist(pkg: String?): Boolean = pkg != null && pkg in TARGET_WHITELIST

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
                try { put("id", android.content.res.Resources.getResourceEntryName(v.id)) } catch (_: Throwable) {}
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
