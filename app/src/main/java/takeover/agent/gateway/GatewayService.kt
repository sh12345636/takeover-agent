package takeover.agent.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import takeover.agent.root.RootProvider

/**
 * Agent 接入层占位（P2 实装 MCP Server：UDS + localhost SSE + token）。
 *
 * P1 职责：
 *  1. 前台服务常驻（specialUse 类型，Android 14+ 合规）；
 *  2. 安装 daemon：从 jniLibs 拷 libtakeoverd.so → files/takeoverd（chmod 700）；
 *  3. Capability Probe → 锁后端 → 拉起 uinput daemon；
 *  4. **紧急停止入口**（见 docs 紧急停止指南）：本服务可被通知栏按钮/adb 广播停用。
 */
class GatewayService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        Thread {
            try { bootstrap() } catch (t: Throwable) { Log.e(TAG, "bootstrap failed", t) }
        }.start()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Takeover Gateway",
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("TakeoverAgent 运行中")
            .setContentText("系统级接管服务 · 点按可紧急停止（P2）")
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    /** 引导：探测后端 → 装 daemon → 锁 capability */
    private fun bootstrap() {
        val backend = RootProvider.probe()
        Log.i(TAG, "backend=$backend su=${RootProvider.suVersion}")

        // 从 assets 拷出可执行（CMake 按 ABI 输出 takeoverd_arm64 / takeoverd_v7a）
        val out = java.io.File(filesDir, "takeoverd")
        try {
            val assetName =
                if (Build.SUPPORTED_ABIS.contains("arm64-v8a")) "takeoverd_arm64"
                else "takeoverd_v7a"
            assets.open(assetName).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            out.setExecutable(true, true)
        } catch (t: Throwable) {
            Log.e(TAG, "install daemon binary failed", t)
        }

        // uinput 探测 + 拉起（root 窗口内完成，此后不依赖会话）
        val dm = resources.displayMetrics
        val uinputOk = RootProvider.ensureDaemon(dm.widthPixels, dm.heightPixels)
        Log.i(TAG, "uinput daemon=$uinputOk sockAlive=${RootProvider.daemonAlive()}")

        takeover.agent.cap.FallbackRouter.applyProfile(
            performClickOk = backend == RootProvider.Backend.FULL_A,
            uinputOk = uinputOk,
            suOk = RootProvider.exec("id -u", 3000).let { it.ok && it.out.trim() == "0" }
        )
    }

    /** 紧急停止：adb 广播或通知栏触发 → 停服务 + 杀 daemon（模块仍可被 LSPosed 秒关） */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EMERGENCY_STOP) {
            RootProvider.exec("pkill -9 takeoverd", 3000)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    companion object {
        private const val TAG = "TakeoverGateway"
        private const val CHANNEL = "takeover_gateway"
        const val ACTION_EMERGENCY_STOP = "takeover.agent.EMERGENCY_STOP"
    }
}
