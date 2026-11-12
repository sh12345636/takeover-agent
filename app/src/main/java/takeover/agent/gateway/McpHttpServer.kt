package takeover.agent.gateway

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 极简 HTTP/1.1 server（零第三方依赖，仅 loopback）。
 *
 * 路由：
 *   GET  /health        → {ok:true}（免鉴权，探活）
 *   POST /mcp           → MCP Streamable HTTP：JSON-RPC（Bearer token 鉴权）
 *   OPTIONS *           → CORS 预检（本地工具兼容）
 *
 * 鉴权：Authorization: Bearer <token>；本地 Agent 首次可从通知栏/审计取 token。
 */
class McpHttpServer(
    private val port: Int,
    private val token: String,
) {
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "mcp-http").apply { isDaemon = true } }
    @Volatile private var running = false
    @Volatile var onRequest: (String, String) -> McpTools.Rpc = { _, _ -> McpTools.Rpc(404, "{}") }

    fun start(): Boolean = try {
        serverSocket = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        running = true
        Thread({
            while (running) {
                try {
                    val s = serverSocket?.accept() ?: break
                    pool.execute { handle(s) }
                } catch (_: Throwable) {
                    if (!running) break
                }
            }
        }, "mcp-accept").apply { isDaemon = true; start() }
        true
    } catch (_: Throwable) { false }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        pool.shutdownNow()
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = 30_000
                val inp = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val reqLine = inp.readLine() ?: return
                val parts = reqLine.split(" ")
                if (parts.size < 3) { write(s, 400, """{"error":"bad request"}"""); return }
                val method = parts[0]; val path = parts[1]

                // headers
                var contentLen = 0
                var auth: String? = null
                while (true) {
                    val line = inp.readLine() ?: break
                    if (line.isEmpty()) break
                    val ci = line.indexOf(':')
                    if (ci > 0) {
                        val k = line.substring(0, ci).trim().lowercase()
                        val v = line.substring(ci + 1).trim()
                        when (k) {
                            "content-length" -> contentLen = v.toIntOrNull() ?: 0
                            "authorization" -> auth = v
                        }
                    }
                }

                when {
                    method == "OPTIONS" -> {
                        write(s, 204, "", allow = true)
                    }
                    method == "GET" && path.startsWith("/health") -> {
                        write(s, 200, """{"ok":true,"service":"${McpTools.SERVER_NAME}","v":"${McpTools.SERVER_VERSION}"}""", allow = true)
                    }
                    method == "POST" && path.startsWith("/mcp") -> {
                        // 读 body（限制 1MB）
                        if (contentLen > 1_048_576) { write(s, 413, """{"error":"too large"}"""); return }
                        val body = CharArray(contentLen)
                        var got = 0
                        while (got < contentLen) {
                            val r = inp.read(body, got, contentLen - got)
                            if (r < 0) break
                            got += r
                        }
                        val raw = String(body, 0, got)

                        if (!authorized(auth)) {
                            write(s, 401, """{"jsonrpc":"2.0","id":null,"error":{"code":-32001,"message":"unauthorized: need Authorization Bearer token"}}""", allow = true)
                            return
                        }
                        val rpc = onRequest(method, raw)
                        when {
                            rpc.status == 202 || rpc.body == null -> write(s, 202, "", allow = true)
                            else -> write(s, rpc.status, rpc.body, allow = true)
                        }
                    }
                    else -> write(s, 404, """{"error":"not found"}""")
                }
            }
        } catch (_: Throwable) { /* 连接级异常静默 */ }
    }

    private fun authorized(auth: String?): Boolean {
        if (token.isEmpty()) return true                       // 未启用鉴权（调试）
        return auth != null && auth.startsWith("Bearer ") &&
                auth.removePrefix("Bearer ").trim() == token
    }

    private fun write(s: Socket, status: Int, body: String, allow: Boolean = false) {
        try {
            val os: OutputStream = s.getOutputStream()
            val reason = when (status) {
                200 -> "OK"; 202 -> "Accepted"; 204 -> "No Content"; 400 -> "Bad Request"
                401 -> "Unauthorized"; 404 -> "Not Found"; 413 -> "Payload Too Large"
                500 -> "Internal Server Error"; else -> "OK"
            }
            val sb = StringBuilder()
            sb.append("HTTP/1.1 $status $reason\r\n")
            sb.append("Content-Type: application/json; charset=utf-8\r\n")
            sb.append("Content-Length: ").append(body.toByteArray(Charsets.UTF_8).size).append("\r\n")
            if (allow) {
                sb.append("Access-Control-Allow-Origin: *\r\n")
                sb.append("Access-Control-Allow-Headers: Content-Type, Authorization, Mcp-Session-Id\r\n")
                sb.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            }
            sb.append("Connection: close\r\n\r\n")
            os.write(sb.toString().toByteArray(Charsets.UTF_8))
            if (body.isNotEmpty()) os.write(body.toByteArray(Charsets.UTF_8))
            os.flush()
        } catch (_: Throwable) { }
    }
}
