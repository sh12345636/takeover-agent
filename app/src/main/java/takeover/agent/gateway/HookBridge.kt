package takeover.agent.gateway

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * L1 快速通道客户端：连目标 App 进程内 LSPosed 桥（abstract socket `tkov_<pkg>`）。
 *
 * 全程反射 android.local.*（hidden API，Gateway 启动时已豁免）；
 * 连接失败/超时 → 返回 null，调用方自动降级 root 通道（不阻断可用性）。
 */
object HookBridge {

    /** 向目标进程桥发一条 JSON 命令，返回响应 JSON 文本；失败 null */
    fun request(pkg: String, req: JSONObject, timeoutMs: Int = 1200): String? {
        if (pkg.isEmpty()) return null
        return try {
            val socketCls = Class.forName("android.local.LocalSocket")
            val addrCls = Class.forName("android.local.LocalSocketAddress")
            val nsCls = Class.forName("android.local.LocalSocketAddress\$Namespace")
            val abstractNs = nsCls.getField("ABSTRACT").get(null)

            val socket = socketCls.getConstructor().newInstance()
            val addr = addrCls.getConstructor(String::class.java, nsCls)
                .newInstance("tkov_$pkg", abstractNs)

            socketCls.getMethod("connect", addrCls).invoke(socket, addr)
            socketCls.getMethod("setSoTimeout", Int::class.javaPrimitiveType).invoke(socket, timeoutMs)

            val os = socketCls.getMethod("getOutputStream").invoke(socket)
            val w = OutputStreamWriter(os.bufferedWriter())
            w.write(req.toString()); w.write("\n"); w.flush()

            val `is` = socketCls.getMethod("getInputStream").invoke(socket)
            val r = BufferedReader(InputStreamReader(`is` as java.io.InputStream))
            val line = r.readLine()
            socketCls.getMethod("close").invoke(socket)
            line
        } catch (_: Throwable) { null }
    }

    // ------------------------------------------------ 语义操作快捷封装

    fun ping(pkg: String): Boolean =
        request(pkg, JSONObject().put("op", "ping"))?.let {
            runCatching { JSONObject(it).optBoolean("ok") }.getOrDefault(false)
        } == true

    fun dumpTree(pkg: String): String? =
        request(pkg, JSONObject().put("op", "tree"))?.let {
            runCatching { JSONObject(it).optString("nodes").takeIf { s -> s.isNotEmpty() } }.getOrNull()
        }

    fun performClick(pkg: String, vid: Int): Boolean =
        request(pkg, JSONObject().put("op", "tap").put("vid", vid))?.let {
            runCatching { JSONObject(it).optBoolean("ok") }.getOrDefault(false)
        } == true

    fun setText(pkg: String, vid: Int, text: String): Boolean =
        request(pkg, JSONObject().put("op", "text").put("vid", vid).put("text", text))?.let {
            runCatching { JSONObject(it).optBoolean("ok") }.getOrDefault(false)
        } == true

    /** hidden API 豁免（Gateway 进程入口调用一次） */
    fun exemptHiddenApi() {
        try {
            val vm = Class.forName("dalvik.system.VMRuntime")
            val runtime = vm.getDeclaredMethod("getRuntime").invoke(null)
            vm.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                .invoke(runtime, arrayOf(""))
        } catch (_: Throwable) { }
    }
}
