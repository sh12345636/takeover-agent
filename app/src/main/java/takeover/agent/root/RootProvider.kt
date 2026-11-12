package takeover.agent.root

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 特权层：后端 A（KernelSU/Magisk+LSPosed 全功能）与后端 B（临时 root 会话）
 * 共用的 su 执行与 uinput daemon 管理。
 *
 * 核心原则：**root 是门票不是电源**
 *  - su 授权窗口内完成 daemon 拉起 + /dev/uinput open；
 *  - 此后写入只依赖已打开的 fd，root 会话失效不影响注入；
 *  - 看门狗只负责"重启后重建"与"失联降级通知"。
 */
object RootProvider {

    enum class Backend { FULL_A, TEMP_B, SHIZUKU_C, NONE_D }

    @Volatile var backend: Backend = Backend.NONE_D
        private set
    @Volatile var suVersion: String = ""
        private set

    data class ExecResult(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
    }

    private const val DAEMON_DIR = "/data/local/tmp/takeover"
    private const val DAEMON_BIN = "$DAEMON_DIR/takeoverd"
    private const val SOCK = "$DAEMON_DIR/ud.sock"

    // ------------------------------------------------------------- 探测

    /** Phase 0 探测：锁后端。A: KernelSU/Magisk+LSPosed；B: 仅会话 su；C/D: 无 su */
    fun probe(): Backend {
        val id = exec("id -u", timeoutMs = 4000)
        if (!id.ok || id.out.trim() != "0") {
            backend = Backend.NONE_D
            return backend
        }
        suVersion = exec("su -v", timeoutMs = 4000).out.trim()
        val persistent = listOf("KernelSU", "Magisk", "APatch").any { suVersion.contains(it) }
        val lsposedAlive = File("/data/adb/lspd").exists() &&
                exec("pidof lspd", timeoutMs = 3000).ok
        backend = when {
            persistent && lsposedAlive -> Backend.FULL_A
            persistent || !suVersion.isEmpty() -> Backend.TEMP_B
            else -> Backend.TEMP_B
        }
        return backend
    }

    // ------------------------------------------------------------- su 执行

    fun exec(cmd: String, timeoutMs: Long = 8000): ExecResult {
        return try {
            val p = ProcessBuilder("/system/bin/su", "-c", cmd)
                .redirectErrorStream(false)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly(); return ExecResult(-1, out, "timeout")
            }
            ExecResult(p.exitValue(), out, err)
        } catch (t: Throwable) {
            ExecResult(-1, "", t.message ?: "exec failed")
        }
    }

    // --------------------------------------------------- uinput daemon 管理

    /** root 窗口内拉起常驻 daemon（setsid 脱离 su 会话） */
    fun ensureDaemon(width: Int, height: Int): Boolean {
        if (daemonAlive()) return true
        exec("mkdir -p $DAEMON_DIR")
        // daemon 二进制由 APK 安装时从 jniLibs 拷出（见 GatewayService.installDaemon）
        val cmd = "chmod 700 $DAEMON_BIN && " +
                "setsid $DAEMON_BIN >$DAEMON_DIR/daemon.log 2>&1 < /dev/null &"
        exec(cmd, timeoutMs = 5000)
        Thread.sleep(300)
        if (!daemonAlive()) return false
        // 下发屏幕尺寸
        return send("i $width $height")
    }

    fun daemonAlive(): Boolean = File(SOCK).exists() && exec("pidof takeoverd", timeoutMs = 3000).ok

    /** 向 daemon 发一条指令（多指手势由 Gateway 侧按序多次调用） */
    fun send(line: String): Boolean {
        // 经 su 用 nc/自写客户端均可；骨架用 sh重定向（daemon 单连接串行）
        val r = exec("echo '$line' | nc -w 1 -U $SOCK 2>/dev/null || " +
                "sh -c 'exec 3<>/data/local/tmp/takeover/ud.sock; echo \"$line\" >&3; exec 3<&-'",
            timeoutMs = 3000)
        return r.ok
    }

    // ------------------------------------------------------------- 看门狗

    /** 失联判定：daemon 死 or su 断。返回 true = 需要降级/重建 */
    fun lost(): Boolean = !daemonAlive()

    /** 重建（重启后 / 任务前调用；依赖新的 root 窗口） */
    fun rebuild(width: Int, height: Int): Boolean = ensureDaemon(width, height)
}
