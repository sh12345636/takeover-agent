package takeover.agent.root

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * daemon TCP 客户端（127.0.0.1:17353）。
 *
 * 三重身份：
 *  1. uinput 注入通道（d/m/u 多指手势）
 *  2. root 网关（x 指令代执行——app 进程**不需要 su 授权**）
 *  3. 存活探测（p 指令）
 */
object DaemonClient {
    private const val HOST = "127.0.0.1"
    private const val PORT = 17353

    /** 单次连接：发若干指令并逐条收 ack；返回全部 ack 是否为 O */
    fun sendBatch(lines: List<String>, timeoutMs: Int = 3000): Boolean {
        if (lines.isEmpty()) return true
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(HOST, PORT), timeoutMs)
                s.soTimeout = timeoutMs
                val out = OutputStreamWriter(s.getOutputStream().bufferedWriter())
                val inp = BufferedReader(InputStreamReader(s.getInputStream()))
                for (l in lines) {
                    out.write(l); out.write("\n"); out.flush()
                    val ack = inp.readLine() ?: return false
                    if (ack != "O" && ack != "P") return false
                }
                true
            }
        } catch (_: Throwable) { false }
    }

    /** 探测 daemon 存活 */
    fun alive(): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(HOST, PORT), 800)
                s.soTimeout = 800
                val out = OutputStreamWriter(s.getOutputStream().bufferedWriter())
                out.write("p\n"); out.flush()
                BufferedReader(InputStreamReader(s.getInputStream())).readLine() == "P"
            }
        } catch (_: Throwable) { false }
    }

    /**
     * root 代执行。返回 (returnCode, stdout)；失败 rc=-1。
     * 输出尾行是 `__RC__<code>`（daemon 包装），这里解析剥掉。
     */
    fun execRoot(cmd: String, timeoutMs: Int = 8000): Pair<Int, String> {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(HOST, PORT), 2000)
                s.soTimeout = timeoutMs
                val out = OutputStreamWriter(s.getOutputStream().bufferedWriter())
                out.write("x $cmd"); out.write("\n"); out.flush()
                val inp = BufferedReader(InputStreamReader(s.getInputStream()))
                val head = inp.readLine() ?: return -1 to ""
                if (!head.startsWith("X")) return -1 to ""
                val len = head.substring(1).toIntOrNull() ?: return -1 to ""
                val body = CharArray(len)
                var got = 0
                while (got < len) {
                    val r = inp.read(body, got, len - got)
                    if (r < 0) break
                    got += r
                }
                var text = String(body, 0, got)
                var rc = 0
                val m = Regex("__RC__(-?\\d+)\\s*$").find(text)
                if (m != null) { rc = m.groupValues[1].toInt(); text = text.substring(0, m.range.first) }
                rc to text
            }
        } catch (_: Throwable) { -1 to "" }
    }

    // ---------------------------------------------------------- 手势封装

    fun tap(x: Int, y: Int): Boolean =
        sendBatch(listOf("d 0 $x $y", "u 0"))

    /** 分帧滑动：一条连接内连发（行缓冲保证不错乱），步间 usleep 级节奏由对端自然完成 */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, steps: Int = 16, stepDelayMs: Int = 12): Boolean {
        val lines = ArrayList<String>(steps + 2)
        lines += "d 0 $x1 $y1"
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            lines += "m 0 ${x1 + ((x2 - x1) * t).toInt()} ${y1 + ((y2 - y1) * t).toInt()}"
        }
        lines += "u 0"
        // 逐条发送并在客户端控制节奏（TCP 小包 + 对端即发即生效）
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(HOST, PORT), 3000)
                s.soTimeout = 3000
                val out = OutputStreamWriter(s.getOutputStream().bufferedWriter())
                val inp = BufferedReader(InputStreamReader(s.getInputStream()))
                for (l in lines) {
                    out.write(l); out.write("\n"); out.flush()
                    val ack = inp.readLine() ?: return false
                    if (ack != "O") return false
                    if (l.startsWith("m 0")) Thread.sleep(stepDelayMs.toLong())
                }
                true
            }
        } catch (_: Throwable) { false }
    }
}
