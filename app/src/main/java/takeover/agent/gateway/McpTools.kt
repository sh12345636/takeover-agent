package takeover.agent.gateway

import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 工具面：统一 Tool Schema（超级小爱/任何 MCP client 同一套）
 * + JSON-RPC 分发（initialize / tools/list / tools/call / ping）。
 */
object McpTools {

    const val SERVER_NAME = "takeover-gateway"
    const val SERVER_VERSION = "0.2.0"
    const val PROTOCOL_VERSION = "2025-06-18"

    /** 紧急停止回调（由 GatewayService 注入：杀 daemon + 停服务） */
    var onEmergencyStop: () -> Unit = {}

    // ------------------------------------------------------------ schema

    private fun tool(name: String, desc: String, props: JSONObject, required: Array<String> = emptyArray()) =
        JSONObject()
            .put("name", name)
            .put("description", desc)
            .put("inputSchema", JSONObject()
                .put("type", "object")
                .put("properties", props)
                .put("required", JSONArray(required)))

    private fun str(desc: String, enum: JSONArray? = null) =
        JSONObject().put("type", "string").put("description", desc).also { if (enum != null) it.put("enum", enum) }
    private fun int(desc: String) = JSONObject().put("type", "integer").put("description", desc)

    fun toolsList(): JSONObject {
        val tools = JSONArray()

        tools += tool("get_state", "读取接管系统状态：后端/daemon/前台应用/旋转/能力位/审计尾部",
            JSONObject())

        tools += tool("ui_tree", "读取当前（或指定）应用的控件树（零截图，结构化节点含 text/rid/cls/bounds）",
            JSONObject().put("package", str("目标包名，缺省取前台应用")))

        tools += tool("tap", "点击：语义选择器（自动解析控件树取坐标/句柄）或直接坐标",
            JSONObject()
                .put("selector", str("选择器，如 text=登录 或 rid=com.x:id/btn&cls=Button（&组合，idx=n 第n个）"))
                .put("x", int("像素 x（无 selector 时必填）"))
                .put("y", int("像素 y"))
                .put("vid", int("控件句柄（ui_tree 返回的 vid，语义点击最快）")))

        tools += tool("swipe", "滑动（uinput 分帧轨迹，参数为屏幕像素坐标）",
            JSONObject()
                .put("x1", int("起点 x")).put("y1", int("起点 y"))
                .put("x2", int("终点 x")).put("y2", int("终点 y"))
                .put("duration_ms", int("总时长毫秒，默认 320"))
                .put("steps", int("插值步数，默认按时长推导")))

        tools += tool("type_text", "向输入框写文本（中文走 hook/剪贴板通道；先用 selector 定位输入框）",
            JSONObject()
                .put("text", str("要输入的文本"))
                .put("selector", str("输入框选择器（推荐）"))
                .put("vid", int("输入框句柄")))

        tools += tool("launch_app", "启动应用/Activity（root am start，可绕后台启动限制）",
            JSONObject()
                .put("package", str("包名"))
                .put("activity", str("可选 Activity（可为 pkg/Act 简写）")))

        tools += tool("emergency_stop", "紧急停止：杀注入 daemon 并停掉 Gateway（后悔机制 L1）",
            JSONObject())

        return JSONObject().put("tools", tools)
    }

    // ---------------------------------------------------------- tools/call

    /** 返回 MCP tools/call 的 result（或抛给上层做 error） */
    fun call(name: String, args: JSONObject): JSONObject {
        val t0 = System.currentTimeMillis()
        val r: ToolExecutor.Result = try {
            when (name) {
                "get_state" -> ToolExecutor.Result(true, "local", ToolExecutor.getState())
                "ui_tree" -> ToolExecutor.uiTree(args.optString("package").takeIf { it.isNotEmpty() })
                "tap" -> ToolExecutor.tap(args)
                "swipe" -> ToolExecutor.swipe(args)
                "type_text" -> ToolExecutor.typeText(args)
                "launch_app" -> ToolExecutor.launchApp(args)
                "emergency_stop" -> {
                    onEmergencyStop()
                    ToolExecutor.Result(true, "local", JSONObject().put("stopped", true))
                }
                else -> ToolExecutor.Result(false, "none", err = "未知工具: $name")
            }
        } catch (t: Throwable) {
            ToolExecutor.Result(false, "none", err = "异常: ${t.message}")
        }
        val cost = System.currentTimeMillis() - t0
        AuditLog.log(name, args.toString(), r.channel, cost, r.ok, r.err)

        val payload = JSONObject()
            .put("ok", r.ok)
            .put("channel", r.channel)
            .put("ms", cost)
            .also { if (r.err != null) it.put("error", r.err) }
        for (k in r.data.keys()) payload.put(k, r.data.get(k))

        return JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", payload.toString())))
            .put("isError", !r.ok)
    }

    // ------------------------------------------------------------- JSON-RPC

    data class Rpc(val status: Int, val body: String?)

    /** 处理一个 JSON-RPC 请求体；返回 HTTP 状态与响应体（null=无响应体，202） */
    fun handleRpc(raw: String): Rpc {
        val root = try { JSONObject(raw) } catch (_: Throwable) {
            return Rpc(400, errObj(null, -32700, "Parse error").toString())
        }
        val id = if (root.has("id") && !root.isNull("id")) root.get("id") else null
        val method = root.optString("method")

        if (id == null) {
            // notification（如 notifications/initialized）→ 无响应体
            return Rpc(202, null)
        }

        val result: JSONObject = try {
            when (method) {
                "initialize" -> JSONObject()
                    .put("protocolVersion", root.optJSONObject("params")?.optString("protocolVersion") ?: PROTOCOL_VERSION)
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                    .put("serverInfo", JSONObject().put("name", SERVER_NAME).put("version", SERVER_VERSION))
                "ping" -> JSONObject()
                "tools/list" -> toolsList()
                "tools/call" -> {
                    val p = root.optJSONObject("params")
                        ?: return Rpc(400, errObj(id, -32602, "missing params").toString())
                    val toolName = p.optString("name")
                    val a = p.optJSONObject("arguments") ?: JSONObject()
                    call(toolName, a)
                }
                else -> return Rpc(400, errObj(id, -32601, "Method not found: $method").toString())
            }
        } catch (t: Throwable) {
            return Rpc(500, errObj(id, -32603, "Internal error: ${t.message}").toString())
        }

        return Rpc(200, JSONObject()
            .put("jsonrpc", "2.0").put("id", id).put("result", result).toString())
    }

    private fun errObj(id: Any?, code: Int, msg: String) = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", msg))

    private operator fun JSONArray.plusAssign(o: JSONObject) { put(o) }
}
