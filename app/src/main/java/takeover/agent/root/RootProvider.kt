package takeover.agent.root

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 特权层（P2 重构）：
 *  - **daemon 优先**：root 需求全部走 DaemonClient.execRoot（app 零 su 依赖）；
 *  - **su 只干一件事**：首次把 daemon 拉起来（一次性门票）。
 *
 * 后端判定：daemon 活着 = 特权就绪；su 是 KernelSU/Magisk + lspd 活着 = FULL_A。
 */
object RootProvider {

    enum class Backend { FULL_A, TEMP_B, NONE_D }

    @Volatile var backend: Backend = Backend.NONE_D
        private set
    @Volatile var suVersion: String = ""
        private set

    data class ExecResult(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
    }

    const val DAEMON_DIR = "/data/local/tmp/takeover"
    const val DAEMON_BIN = "$DAEMON_DIR/takeoverd"

    /** 探测：daemon / su / LSPosed 三态 */
    fun probe(): Backend {
        val suOk = try {
            val r = rawSu("id -u", 4000)
            r.first == 0 && r.second.trim() == "0"
        } catch (_: Throwable) { false }

        if (suOk) {
            suVersion = rawSu("su -v", 3000).second.trim()
        }
        val persistent = listOf("KernelSU", "Magisk", "APatch").any { suVersion.contains(it) }
        val lsposedAlive = File("/data/adb/lspd").exists() && rawSu("pidof lspd", 3000).first == 0

        backend = when {
            DaemonClient.alive() && persistent && lsposedAlive -> Backend.FULL_A
            DaemonClient.alive() || suOk -> Backend.TEMP_B
            else -> Backend.NONE_D
        }
        return backend
    }

    /**
     * root 执行：daemon 网关优先。
     * daemon 存活时不回落 su —— 命令本身挂起时回落只是双倍等待；
     * daemon 不在（alive=false）才走 su（此时 su 会触发一次性授权）。
     */
    fun exec(cmd: String, timeoutMs: Long = 8000): ExecResult {
        if (DaemonClient.alive()) {
            val (rc, out) = DaemonClient.execRoot(cmd, timeoutMs.toInt())
            return if (rc >= 0) ExecResult(rc, out, "")
                   else ExecResult(-1, out, "daemon exec rc=$rc")
        }
        val (rc, out) = rawSu(cmd, timeoutMs)
        return ExecResult(rc, out, if (rc == 0) "" else "su failed rc=$rc")
    }

    /** 纯 su 执行（不走 daemon；用于拉起 daemon 前） */
    private fun rawSu(cmd: String, timeoutMs: Long): Pair<Int, String> {
        return try {
            val p = ProcessBuilder("/system/bin/su", "-c", cmd)
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly(); return -1 to "timeout"
            }
            p.exitValue() to out
        } catch (t: Throwable) { -1 to (t.message ?: "exec failed") }
    }

    /**
     * 确保 daemon 运行（root 窗口内 setsid 拉起，随后脱离会话常驻）。
     * 已运行 → 直接 true；未运行且无 su → false（走 NONE_D 降级）。
     */
    fun ensureDaemon(width: Int, height: Int): Boolean {
        if (DaemonClient.alive()) {
            DaemonClient.sendBatch(listOf("i $width $height"))
            return true
        }
        // 二进制落地（jniLibs → nativeLibraryDir → /data/local/tmp）
        rawSu("mkdir -p $DAEMON_DIR", 4000)
        val (rc, _) = rawSu(
            "pkill -9 takeoverd 2>/dev/null; " +
            "setsid $DAEMON_DIR/takeoverd >/data/local/tmp/takeover/daemon.log 2>&1 < /dev/null &",
            5000
        )
        Thread.sleep(400)
        val up = DaemonClient.alive()
        if (up) DaemonClient.sendBatch(listOf("i $width $height"))
        return up
    }

    fun daemonAlive(): Boolean = DaemonClient.alive()

    /** 紧急停止：杀 daemon */
    fun killDaemon() { rawSu("pkill -9 takeoverd", 3000) }
}
