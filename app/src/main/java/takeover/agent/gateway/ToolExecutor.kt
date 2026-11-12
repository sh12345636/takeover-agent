package takeover.agent.gateway

import android.os.Build
import android.util.Xml
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import takeover.agent.root.DaemonClient
import takeover.agent.root.RootProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 工具执行器：统一 Result(channel) 回传实际通道，供 Agent 可见。
 *
 * 每个工具的降级链（兼容性核心）：
 *   ui_tree : HookBridge → uiautomator dump(root) → dumpsys window
 *   tap     : performClick(hook) → uinput(daemon) → su input
 *   swipe   : uinput 分帧 → su input swipe
 *   type    : setText(hook) → 剪贴板+PASTE(root) → input text(ascii)
 *   launch  : resolve-activity + am start(root) → monkey
 */
object ToolExecutor {

    data class Result(val ok: Boolean, val channel: String, val data: JSONObject = JSONObject(), val err: String? = null)

    // ------------------------------------------------------------ 状态读取

    fun frontPackage(): String {
        val (_, out) = RootProviderExec("dumpsys window 2>/dev/null | grep mCurrentFocus", 5000)
        // mCurrentFocus=Window{... u0 pkg/activity}
        val m = Regex("u0\\s+([\\w.]+)/").find(out)
        return m?.groupValues?.get(1) ?: ""
    }

    fun frontDetail(): String {
        val (_, out) = RootProviderExec("dumpsys window 2>/dev/null | grep mCurrentFocus", 5000)
        return Regex("Window\\{[^}]*u0\\s+([^}]+)\\}").find(out)?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun rotation(): Int {
        val (_, out) = RootProviderExec("dumpsys input 2>/dev/null | grep -m1 SurfaceOrientation", 4000)
        return Regex("(\\d)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    // -------------------------------------------------------------- ui_tree

    /** 最近一次树缓存（selector tap 3s 内复用，避免每次点按都 dump） */
    @Volatile private var lastTree: List<Node>? = null
    @Volatile private var lastTreeAt: Long = 0
    @Volatile private var lastTreeSrc: String = ""

    data class Node(
        val cls: String, val text: String, val desc: String, val rid: String,
        val pkg: String, val clickable: Boolean,
        val l: Int, val t: Int, val r: Int, val b: Int,
        val vid: Int   // hook 通道句柄；0 = 无
    ) {
        val cx: Int get() = (l + r) / 2
        val cy: Int get() = (t + b) / 2
    }

    fun uiTree(pkgArg: String?, force: Boolean = false): Result {
        val pkg = pkgArg?.takeIf { it.isNotEmpty() } ?: frontPackage()

        // L1：Hook 通道（目标 App 已配 LSPosed 作用域且桥在听）
        if (pkg.isNotEmpty() && (!force)) {
            HookBridge.dumpTree(pkg)?.let { arr ->
                val nodes = runCatching { parseHookNodes(arr) }.getOrNull()
                if (nodes != null && nodes.isNotEmpty()) {
                    cacheTree(nodes, "hook")
                    return Result(true, "hook", treeJson(nodes, "hook", pkg))
                }
            }
        }

        // L2：uiautomator dump（root；连续 2 次，容忍 idle 失败）
        val path = "/data/local/tmp/takeover/ui.xml"
        for (attempt in 1..2) {
            val (rc, out) = RootProviderExec("uiautomator dump $path", 12000)
            if (rc == 0 && !out.contains("ERROR")) {
                val (crc, xml) = RootProviderExec("cat $path", 6000)
                if (crc == 0 && xml.contains("<hierarchy")) {
                    val nodes = runCatching { parseUiaNodes(xml) }.getOrNull()
                    if (nodes != null && nodes.isNotEmpty()) {
                        cacheTree(nodes, "uiautomator")
                        return Result(true, "uiautomator", treeJson(nodes, "uiautomator", pkg))
                    }
                }
            }
            Thread.sleep(300)
        }

        // L3：dumpsys 窗口兜底（至少给出焦点信息）
        val focus = frontDetail()
        val fallback = JSONObject()
            .put("nodes", org.json.JSONArray())
            .put("source", "dumpsys_fallback")
            .put("focus", focus)
            .put("hint", "uiautomator dump 不可用（页面动画/无障碍占用）；已降级")
        return Result(focus.isNotEmpty(), "dumpsys", fallback,
            if (focus.isEmpty()) "ui_tree 全链失败" else null)
    }

    private fun cacheTree(nodes: List<Node>, src: String) {
        lastTree = nodes; lastTreeAt = System.currentTimeMillis(); lastTreeSrc = src
    }

    private fun treeJson(nodes: List<Node>, src: String, pkg: String): JSONObject {
        val arr = org.json.JSONArray()
        for (n in nodes) {
            arr.put(JSONObject()
                .put("cls", n.cls).put("text", n.text).put("desc", n.desc)
                .put("rid", n.rid).put("pkg", n.pkg).put("click", n.clickable)
                .put("bounds", org.json.JSONArray(listOf(n.l, n.t, n.r, n.b)))
                .also { if (n.vid != 0) it.put("vid", n.vid) })
        }
        return JSONObject().put("nodes", arr).put("source", src).put("pkg", pkg)
    }

    private fun parseHookNodes(arr: String): List<Node> {
        val a = org.json.JSONArray(arr)
        val out = ArrayList<Node>(a.length())
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val x = o.optInt("x"); val y = o.optInt("y")
            val w = o.optInt("w"); val h = o.optInt("h")
            out += Node(
                cls = o.optString("cls"), text = o.optString("text"),
                desc = o.optString("desc"), rid = o.optString("id"),
                pkg = "", clickable = o.optBoolean("clickable"),
                l = x, t = y, r = x + w, b = y + h,
                vid = o.optInt("vid", 0)
            )
        }
        return out
    }

    private fun parseUiaNodes(xml: String): List<Node> {
        val pp: XmlPullParser = Xml.newPullParser()
        pp.setInput(StringReader(xml))
        val out = ArrayList<Node>()
        var ev = pp.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && pp.name == "node") {
                val bounds = pp.getAttributeValue(null, "bounds") ?: ""
                val bm = Regex("\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]").find(bounds)
                if (bm != null) {
                    val g = bm.groupValues
                    out += Node(
                        cls = pp.getAttributeValue(null, "class") ?: "",
                        text = pp.getAttributeValue(null, "text") ?: "",
                        desc = pp.getAttributeValue(null, "content-desc") ?: "",
                        rid = pp.getAttributeValue(null, "resource-id") ?: "",
                        pkg = pp.getAttributeValue(null, "package") ?: "",
                        clickable = pp.getAttributeValue(null, "clickable") == "true",
                        l = g[1].toInt(), t = g[2].toInt(), r = g[3].toInt(), b = g[4].toInt(),
                        vid = 0
                    )
                }
            }
            ev = pp.next()
        }
        return out
    }

    /** selector：`text=登录&cls=TextView` / `rid=...` / `idx=2` */
    private fun matchSelector(sel: String, tree: List<Node>): Node? {
        val conds = HashMap<String, String>()
        var idx = 0
        for (part in sel.split("&")) {
            val kv = part.split("=", limit = 2)
            if (kv.size != 2) continue
            val k = kv[0].trim(); val v = kv[1].trim()
            if (k == "idx") idx = v.toIntOrNull() ?: 0 else conds[k] = v
        }
        var hit = 0
        for (n in tree) {
            val ok = conds.all { (k, v) ->
                when (k) {
                    "text" -> n.text == v
                    "desc" -> n.desc == v
                    "rid", "id" -> n.rid == v || n.rid.endsWith("/$v")
                    "cls" -> n.cls == v || n.cls.endsWith(".$v")
                    "pkg" -> n.pkg == v
                    "click" -> n.clickable == (v != "false")
                    else -> false
                }
            }
            if (ok) { if (hit == idx) return n; hit++ }
        }
        return null
    }

    private fun freshTree(): List<Node>? {
        val cached = lastTree
        if (cached != null && System.currentTimeMillis() - lastTreeAt < 3000) return cached
        uiTree(null, force = true)
        return lastTree?.takeIf { System.currentTimeMillis() - lastTreeAt < 15000 }
    }

    // ---------------------------------------------------------------- tap

    fun tap(args: JSONObject): Result {
        val pkg = frontPackage()
        val sel = args.optString("selector").takeIf { it.isNotEmpty() }
        var vid = args.optInt("vid", 0)
        var x = args.optInt("x", Int.MIN_VALUE)
        var y = args.optInt("y", Int.MIN_VALUE)

        if (sel != null) {
            val tree = freshTree() ?: return Result(false, "none", err = "树不可用，改用 x/y 直接点击")
            val node = matchSelector(sel, tree)
                ?: return Result(false, "none", err = "selector 未命中: $sel")
            vid = node.vid; x = node.cx; y = node.cy
        }
        if (x == Int.MIN_VALUE || y == Int.MIN_VALUE)
            return Result(false, "none", err = "需要 selector 或 x/y")

        // L1 语义点击（hook）
        if (vid != 0 && pkg.isNotEmpty() && HookBridge.performClick(pkg, vid)) {
            return Result(true, "performClick")
        }
        // L2 uinput
        if (DaemonClient.tap(x, y)) return Result(true, "uinput", JSONObject().put("xy", "$x,$y"))
        // L3 su input
        val (rc, _) = RootProviderExec("input tap $x $y", 5000)
        return if (rc == 0) Result(true, "su_input", JSONObject().put("xy", "$x,$y"))
        else Result(false, "none", err = "tap 全链失败")
    }

    // -------------------------------------------------------------- swipe

    fun swipe(args: JSONObject): Result {
        val x1 = args.optInt("x1"); val y1 = args.optInt("y1")
        val x2 = args.optInt("x2"); val y2 = args.optInt("y2")
        val dur = args.optInt("duration_ms", 320)
        val steps = args.optInt("steps", (dur / 20).coerceIn(4, 40))
        if (x1 == 0 && y1 == 0 && x2 == 0 && y2 == 0)
            return Result(false, "none", err = "需要 x1/y1/x2/y2")

        if (DaemonClient.swipe(x1, y1, x2, y2, steps))
            return Result(true, "uinput")
        val (rc, _) = RootProviderExec("input swipe $x1 $y1 $x2 $y2 $dur", 8000)
        return if (rc == 0) Result(true, "su_input") else Result(false, "none", err = "swipe 全链失败")
    }

    // ----------------------------------------------------------- type_text

    fun typeText(args: JSONObject): Result {
        val text = args.optString("text")
        if (text.isEmpty()) return Result(false, "none", err = "text 为空")
        val pkg = frontPackage()
        val sel = args.optString("selector").takeIf { it.isNotEmpty() }

        // 先定位焦点输入框（selector 或 vid）
        var vid = args.optInt("vid", 0)
        if (sel != null) {
            val node = freshTree()?.let { matchSelector(sel, it) }
                ?: return Result(false, "none", err = "selector 未命中: $sel")
            vid = node.vid
            // 点一下确保获得焦点
            DaemonClient.tap(node.cx, node.cy); Thread.sleep(250)
        }

        // L1 hook setText（中文安全）
        if (vid != 0 && pkg.isNotEmpty() && HookBridge.setText(pkg, vid, text))
            return Result(true, "hook")

        // L2 剪贴板 + 粘贴键（root；中文安全）
        val clipOk = try {
            val cm = GatewayService.appContext?.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("tk", text)); true
            } else false
        } catch (_: Throwable) { false }
        if (clipOk) {
            val (rc, _) = RootProviderExec("input keyevent 279", 4000)   // KEYCODE_PASTE
            if (rc == 0) return Result(true, "clipboard_paste")
            // 部分 ROM 无 PASTE 键语义 → KEYCODE_V + META 无门，直接 input text ASCII 兜底
        }

        // L3 input text（仅 ASCII）
        if (text.all { it.code in 32..126 }) {
            val esc = text.replace("'", "'\\''")
            val (rc, _) = RootProviderExec("input text '$esc'", 6000)
            if (rc == 0) return Result(true, "su_input_ascii")
        }
        return Result(false, "none", err = "非 ASCII 且 hook/剪贴板通道不可用（目标 App 未配 LSPosed 作用域？）")
    }

    // ---------------------------------------------------------- launch_app

    fun launchApp(args: JSONObject): Result {
        val pkg = args.optString("package").takeIf { it.isNotEmpty() }
            ?: return Result(false, "none", err = "需要 package")
        val act = args.optString("activity")

        if (act.isNotEmpty()) {
            val comp = if (act.contains("/")) act else "$pkg/$act"
            val (rc, _) = RootProviderExec("am start -n $comp", 8000)
            if (rc == 0) return Result(true, "am_start", JSONObject().put("component", comp))
        }
        // 解析默认 Activity
        val (rc1, out1) = RootProviderExec("cmd package resolve-activity --brief $pkg 2>/dev/null | tail -n1", 6000)
        val comp = out1.trim().substringAfterLast(": ").trim()
        if (rc1 == 0 && comp.contains("/") && !comp.contains("No activity")) {
            val (rc, _) = RootProviderExec("am start -n $comp", 8000)
            if (rc == 0) return Result(true, "am_start", JSONObject().put("component", comp))
        }
        // 兜底 monkey
        val (rc2, _) = RootProviderExec("monkey -p $pkg -c android.intent.category.LAUNCHER 1", 8000)
        return if (rc2 == 0) Result(true, "monkey")
        else Result(false, "none", err = "启动失败: $pkg")
    }

    // ------------------------------------------------------------- get_state

    fun getState(): JSONObject {
        val caps = FallbackRouterCaps.snapshot()
        return JSONObject()
            .put("backend", RootProvider.backend.name)
            .put("su", RootProvider.suVersion)
            .put("daemon", RootProvider.daemonAlive())
            .put("front", frontDetail())
            .put("front_pkg", frontPackage())
            .put("rotation", rotation())
            .put("sdk", Build.VERSION.SDK_INT)
            .put("capabilities", caps)
            .put("audit", AuditLog.stats())
            .put("audit_tail", org.json.JSONArray().also { arr ->
                for (e in AuditLog.tail(8)) arr.put(JSONObject()
                    .put("t", SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(e.ts)))
                    .put("tool", e.tool).put("ch", e.channel)
                    .put("ms", e.costMs).put("ok", e.ok))
            })
            .put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
    }

    // 工具内部统一执行入口（名字太长避免与 RootProvider.exec 混淆）
    private fun RootProviderExec(cmd: String, timeoutMs: Long): Pair<Int, String> {
        val r = RootProvider.exec(cmd, timeoutMs)
        return r.code to r.out
    }
}

/** 与 FallbackRouter 解耦的 capability 快照 */
object FallbackRouterCaps {
    @Volatile var snapshot: () -> JSONObject = { JSONObject() }
}
