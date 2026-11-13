package takeover.agent.gateway

import org.json.JSONObject

/**
 * 多 Agent 互斥锁：同一时刻一个 Agent 持有操作权（防两个智能体抢屏幕）。
 * - acquire/release 独立工具；
 * - 持有者 TTL（默认 10 分钟）到期自动释放（Agent 崩溃不卡死全局）；
 * - get_state / emergency_stop / 锁工具不受锁限制。
 */
object AgentLock {

    @Volatile var holder: String? = null
        private set
    @Volatile var since: Long = 0
        private set
    @Volatile var expiresAt: Long = 0
        private set

    private const val DEFAULT_TTL_SEC = 600

    fun acquire(agent: String, ttlSec: Int = DEFAULT_TTL_SEC): Pair<Boolean, String> {
        if (agent.isEmpty()) return false to "agent id required"
        val now = System.currentTimeMillis()
        synchronized(this) {
            val expired = holder != null && now > expiresAt
            if (holder == null || holder == agent || expired) {
                holder = agent; since = now; expiresAt = now + ttlSec.coerceIn(30, 3600) * 1000L
                return true to "acquired by $agent (ttl=${ttlSec}s)"
            }
            return false to "held by $holder until $expiresAt"
        }
    }

    fun release(agent: String): Boolean = synchronized(this) {
        if (holder == agent || holder == null) { holder = null; true } else false
    }

    /** 操作前检查：无主 / 自己是主 / 已过期 → 放行 */
    fun check(agent: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(this) {
            return holder == null || holder == agent || now > expiresAt
        }
    }

    fun snapshot(): JSONObject {
        val now = System.currentTimeMillis()
        synchronized(this) {
            return JSONObject()
                .put("holder", holder ?: "")
                .put("held", holder != null && now <= expiresAt)
                .put("ttl_sec_left", if (holder != null && now <= expiresAt) (expiresAt - now) / 1000 else 0)
        }
    }
}
