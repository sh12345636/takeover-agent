package takeover.agent.root

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.BufferedWriter
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
                val out = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
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
                val out = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
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
                val out = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
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

    /** 长按：同一连接 down → 保持 → up（uinput hold 通道） */
    fun hold(x: Int, y: Int, ms: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(HOST, PORT), 3000)
            s.soTimeout = ms + 3000
            val out = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
            val inp = BufferedReader(InputStreamReader(s.getInputStream()))
            fun send(l: String): Boolean {
                out.write(l); out.write("\n"); out.flush()
                return inp.readLine() == "O"
            }
            if (!send("d 0 $x $y")) return false
            Thread.sleep(ms.toLong().coerceIn(50, 5000))
            send("u 0")
        }
    } catch (_: Throwable) { false }

    /**
     * 分帧滑动（完全体：默认拟人化）
     *  human=true  → 二次贝塞尔弧线 + 缓入缓出变速 + ±2px 抖动（防机械轨迹风控）
     *  human=false → 直线匀速
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, steps: Int = 16, human: Boolean = true): Boolean {
        val rnd = java.util.Random()
        val dx = (x2 - x1).toDouble(); val dy = (y2 - y1).toDouble()
        val len = Math.hypot(dx, dy).coerceAtLeast(1.0)
        val nx = -dy / len; val ny = dx / len
        val bend = (rnd.nextDouble() * 0.3 - 0.15) * len
        val cx = (x1 + x2) / 2.0 + nx * bend
        val cy = (y1 + y2) / 2.0 + ny * bend
        val pts = ArrayList<Pair<Int, Int>>(steps)
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val mt = 1 - t
            var px = mt * mt * x1 + 2 * mt * t * cx + t * t * x2
            var py = mt * mt * y1 + 2 * mt * t * cy + t * t * y2
            if (human) { px += rnd.nextInt(5) - 2; py += rnd.nextInt(5) - 2 }
            pts += px.toInt().coerceIn(0, 32767) to py.toInt().coerceIn(0, 32767)
        }
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(HOST, PORT), 3000)
                s.soTimeout = 3000
                val out = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
                val inp = BufferedReader(InputStreamReader(s.getInputStream()))
                fun send(l: String): Boolean {
                    out.write(l); out.write("\n"); out.flush()
                    return inp.readLine() == "O"
                }
                if (!send("d 0 $x1 $y1")) return false
                for ((i, pt) in pts.withIndex()) {
                    if (!send("m 0 ${pt.first} ${pt.second}")) return false
                    if (human) {
                        val t = (i + 1).toDouble() / steps
                        val speed = Math.sin(Math.PI * t).coerceAtLeast(0.15)
                        Thread.sleep((14.0 / speed).toLong().coerceIn(6, 60))
                    } else Thread.sleep(12)
                }
                send("u 0")
            }
        } catch (_: Throwable) { false }
    }
}
