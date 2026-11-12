package takeover.agent.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import takeover.agent.root.DaemonClient
import takeover.agent.root.RootProvider

/**
 * Agent 接入层（P2 实装）：
 *  1. 前台服务常驻（specialUse）+ 紧急停止广播；
 *  2. Capability Probe → 拉起/复用 daemon（app 零 su 依赖架构）；
 *  3. **MCP HTTP Server**（127.0.0.1:17352，Bearer token 鉴权）；
 *  4. hidden API 豁免（HookBridge 反射前置）。
 */
class GatewayService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        startAsForeground()
        Thread { try { bootstrap() } catch (t: Throwable) { Log.e(TAG, "bootstrap failed", t) } }.start()
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
            .setContentTitle("TakeoverAgent · Gateway 运行中")
            .setContentText("MCP 127.0.0.1:$PORT_MCP · token 尾号 ${token.takeLast(6)}")
            .setStyle(Notification.BigTextStyle().bigText(
                "MCP endpoint: http://127.0.0.1:$PORT_MCP/mcp\nBearer token: $token\n紧急停止：adb 广播或调用 emergency_stop 工具"))
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    /** 引导：豁免 → daemon → MCP server → 状态快照 */
    private fun bootstrap() {
        HookBridge.exemptHiddenApi()

        // daemon：已活着则复用（root 是门票不是电源）；否则尝试 su 一次性拉起
        val dm = resources.displayMetrics
        val daemonOk = RootProvider.ensureDaemon(dm.widthPixels, dm.heightPixels)
        val backend = RootProvider.probe()
        Log.i(TAG, "backend=$backend daemon=$daemonOk su=${RootProvider.suVersion}")

        // 审计落盘
        AuditLog.init(filesDir)

        // capability 快照注入（get_state 可见）
        FallbackRouterCaps.snapshot = {
            JSONObject()
                .put("performClick", backend == RootProvider.Backend.FULL_A)
                .put("uinput", DaemonClient.alive())
                .put("su_input", true)
                .put("ui_tree_hook", false)     // 运行时按调用结果自然更新
                .put("ui_tree_uia", daemonOk)
        }

        // 紧急停止回调
        McpTools.onEmergencyStop = {
            RootProvider.killDaemon()
            val i = Intent(this, GatewayService::class.java).setAction(ACTION_EMERGENCY_STOP)
            startService(i)
        }

        // MCP HTTP server
        val srv = McpHttpServer(PORT_MCP, token)
        srv.onRequest = { _, raw -> McpTools.handleRpc(raw) }
        val up = srv.start()
        mcpServer = srv
        Log.i(TAG, "MCP server up=$up on 127.0.0.1:$PORT_MCP token=${token.takeLast(6)}…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EMERGENCY_STOP) {
            Log.w(TAG, "EMERGENCY_STOP received")
            try { mcpServer?.stop() } catch (_: Throwable) {}
            RootProvider.killDaemon()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { mcpServer?.stop() } catch (_: Throwable) {}
        super.onDestroy()
    }

    /** token：持久随机（files/gateway_token），重启复用；Agent 侧配置用 */
    private val token: String by lazy {
        try {
            val f = java.io.File(filesDir, "gateway_token")
            if (f.exists() && f.readText().trim().length >= 16) f.readText().trim()
            else java.util.UUID.randomUUID().toString().replace("-", "").also {
                f.writeText(it); f.setReadableByNobodyWorks()
            }
        } catch (_: Throwable) { java.util.UUID.randomUUID().toString().replace("-", "") }
    }

    private fun java.io.File.setReadableByNobodyWorks() { /* 保持 app 私有（root 可读） */ }

    companion object {
        private const val TAG = "TakeoverGateway"
        private const val CHANNEL = "takeover_gateway"
        const val PORT_MCP = 17352
        const val ACTION_EMERGENCY_STOP = "takeover.agent.EMERGENCY_STOP"

        @Volatile var appContext: Context? = null
        @Volatile private var mcpServer: McpHttpServer? = null
    }
}
