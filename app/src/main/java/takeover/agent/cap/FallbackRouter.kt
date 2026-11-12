package takeover.agent.cap

import android.os.SystemClock

/**
 * L1→L5 降级路由（兼容性核心）。
 *
 * 铁律：链上任一环抛异常/超时/空结果 → 自动下一环；
 *       全链失败才返回失败；每次结果回传实际通道，供 Agent 可见。
 *
 * 本骨架注册 click 一条完整链，其余能力（text/tree/swipe）同构扩展。
 */
object FallbackRouter {

    data class Result(
        val ok: Boolean,
        val channel: String,
        val latencyMs: Long,
        val error: String? = null,
        val degradedFrom: String? = null
    )

    /** 通道能力开关：由 Capability Probe 填充 */
    val caps = mutableMapOf(
        "performClick" to false,   // L1 需要 LSPosed 作用域（后端 A）
        "uinput"       to false,   // L2/L3 需要 daemon
        "suInput"      to false,   // L4 兜底（慢）
        "a11y"         to false    // L5 最后手段
    )

    /** click 的降级链（顺序即优先级） */
    private val clickChain = listOf("performClick", "uinput", "suInput", "a11y")

    private const val STEP_TIMEOUT_MS = 800L   // 单级超时：超了就降级

    fun tap(vid: Int? = null, x: Int? = null, y: Int? = null): Result {
        var firstTried: String? = null
        var lastErr: String? = null
        for (ch in clickChain) {
            if (caps[ch] != true) continue
            if (firstTried == null) firstTried = ch
            val t0 = SystemClock.elapsedRealtime()
            val r = runCatching {
                when (ch) {
                    "performClick" -> {
                        val id = vid ?: return Result(false, ch, 0, "missing vid")
                        takeover.agent.hooks.AppHooks.performClick(id)
                    }
                    "uinput" -> {
                        val px = x ?: return Result(false, ch, 0, "missing xy")
                        val py = y ?: return Result(false, ch, 0, "missing xy")
                        tapViaUinput(px, py)
                    }
                    "suInput" -> {
                        val px = x ?: return Result(false, ch, 0, "missing xy")
                        val py = y ?: return Result(false, ch, 0, "missing xy")
                        takeover.agent.root.RootProvider.exec("input tap $px $py", 5000).ok
                    }
                    else -> false   // a11y：P2 接入
                }
            }
            val cost = SystemClock.elapsedRealtime() - t0
            if (cost > STEP_TIMEOUT_MS) { lastErr = "timeout@${cost}ms"; continue }
            if (r.isSuccess && r.getOrDefault(false)) {
                return Result(true, ch, cost,
                    degradedFrom = if (ch != firstTried) firstTried else null)
            }
            lastErr = r.exceptionOrNull()?.message ?: "no-op"
        }
        return Result(false, "none", 0, lastErr)
    }

    /** uinput 通道：DaemonClient 单连接 down→up（P2 起 daemon 同时是 root 网关） */
    private fun tapViaUinput(x: Int, y: Int): Boolean =
        takeover.agent.root.DaemonClient.tap(x, y)

    /** 探测结果 → caps（Phase 0 结束时调用） */
    fun applyProfile(performClickOk: Boolean, uinputOk: Boolean, suOk: Boolean) {
        caps["performClick"] = performClickOk
        caps["uinput"] = uinputOk
        caps["suInput"] = suOk
    }
}
