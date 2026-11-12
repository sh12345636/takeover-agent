package takeover.agent.gateway

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.io.File
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 本地审计日志：JSONL 追加 + 内存环形缓冲（get_state 可读）。
 * 字段：时间 / tool / 参数摘要 / channel / 耗时 / ok / err
 */
object AuditLog {

    data class Entry(
        val ts: Long, val tool: String, val args: String,
        val channel: String, val costMs: Long, val ok: Boolean, val err: String?
    )

    private val recent = ConcurrentLinkedDeque<Entry>()
    private const val MAX_MEM = 200
    @Volatile private var file: File? = null

    fun init(dir: File) {
        file = File(dir, "audit.jsonl")
    }

    @Synchronized
    fun log(tool: String, args: String, channel: String, costMs: Long, ok: Boolean, err: String? = null) {
        val e = Entry(System.currentTimeMillis(), tool, args.take(300), channel, costMs, ok, err?.take(300))
        recent.addLast(e)
        while (recent.size > MAX_MEM) recent.pollFirst()
        val f = file ?: return
        try {
            val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            val line = JSONObject()
                .put("t", sdf.format(Date(e.ts)))
                .put("tool", e.tool).put("args", e.args)
                .put("ch", e.channel).put("ms", e.costMs)
                .put("ok", e.ok)
            if (e.err != null) line.put("err", e.err)
            f.appendText(line.toString() + "\n")
        } catch (_: Throwable) { /* 审计失败不阻断业务 */ }
    }

    fun tail(n: Int = 10): List<Entry> = recent.toList().takeLast(n)

    fun stats(): JSONObject {
        val total = recent.size
        val fails = recent.count { !it.ok }
        return JSONObject()
            .put("recent", total)
            .put("failed", fails)
            .put("log_file", file?.absolutePath ?: "")
    }
}
