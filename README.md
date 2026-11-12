# TakeoverAgent · 系统级 Agent 接管框架（P1 + P2 完成）

> 路线：**root + LSPosed Hook + uinput 内核级注入**，不依赖无障碍/OCR。
> 形态：**单 APK**（LSPosed 模块 + MCP Gateway + native daemon）。
> 状态：**P2 全链路真机实测通过**（2026-11-13，KernelSU 3.2.5 + HyperOS + Android 15）。

## 架构（三层 + root 网关）

```
┌─────────────────────────────────────────────────────────────┐
│ ③ Agent 接入层：MCP HTTP Server（127.0.0.1:17352）           │
│    Bearer token 鉴权 · JSON-RPC · 7 个工具 · 审计日志         │
│    超级小爱/任意 MCP client 同一套 Tool Schema 接入           │
├─────────────────────────────────────────────────────────────┤
│ ② 能力抽象层：ToolExecutor + 降级链                          │
│    ui_tree : hook桥 → uiautomator dump(root) → dumpsys      │
│    tap     : performClick(hook) → uinput → su input          │
│    swipe   : uinput 分帧 → input swipe                       │
│    type    : setText(hook) → 剪贴板+PASTE → input text       │
│    launch  : resolve-activity + am start → monkey            │
├─────────────────────────────────────────────────────────────┤
│ ① 注入层 / 特权层                                            │
│    takeoverd（root daemon，TCP 127.0.0.1:17353）             │
│      · uinput 多指注入（<5ms）                               │
│      · **root 网关**：x 指令代执行 → app 进程零 su 依赖       │
│    LSPosed 模块（作用域：目标 App / system framework）        │
│      · AppHooks：控件树/performClick/setText + 桥服务端       │
│        （abstract socket tkov_<pkg>，HookBridge 连入）        │
└─────────────────────────────────────────────────────────────┘
```

**关键设计**：`root 是门票不是电源` —— daemon 由一次性 su 拉起后常驻，
此后 Gateway 的一切 root 需求走 daemon TCP（app **不弹 su 授权窗**）；
开机由 `/data/adb/service.d/takeoverd.sh` 自动恢复 daemon + Gateway。

## MCP 使用（接入超级小爱 / 任意 Agent）

```bash
# 端点
http://127.0.0.1:17352/mcp        # MCP Streamable HTTP（JSON-RPC 2.0）
http://127.0.0.1:17352/health     # 探活（免鉴权）

# token（三处可得）
#  1) 通知栏「TakeoverAgent · Gateway 运行中」全文
#  2) root 读取：cat /data/data/takeover.agent/files/gateway_token
#  3) 失效即重新生成，重装会轮换

# 调用示例
curl -X POST http://127.0.0.1:17352/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TOKEN>" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
       "params":{"name":"tap","arguments":{"selector":"text=登录"}}}'
```

**工具集（7）**：

| 工具 | 参数 | 说明 |
|---|---|---|
| `get_state` | — | 后端/daemon/前台/旋转/能力位/审计尾部 |
| `ui_tree` | package? | 结构化控件树（text/rid/cls/bounds/vid），零截图 |
| `tap` | selector 或 x/y，vid? | 语义点击或坐标；返回**实际通道** |
| `swipe` | x1,y1,x2,y2,duration_ms?,steps? | uinput 分帧轨迹 |
| `type_text` | text, selector?/vid? | 中文走 hook/剪贴板通道 |
| `launch_app` | package, activity? | root am start（绕后台启动限制） |
| `emergency_stop` | — | **后悔机制**：杀 daemon + 停 Gateway |

所有工具返回 `{"ok","channel","ms",...}` —— channel 即实际生效的降级层级，
审计落盘 `files/audit.jsonl`（`get_state.audit_tail` 可读）。

## 快速部署（真机验证过的完整序列）

```bash
# 1) 安装 APK（Release 已签名，直接装）
adb install -r TakeoverAgent-release.apk
#   ⚠ 覆盖安装后若 am 报 "Not found; no service started" → 卸载重装即恢复

# 2) 首次部署 daemon（root，一次性）
mkdir -p /data/local/tmp/takeover
#   从 APK 解出 so（unzip lib/arm64-v8a/libtakeoverd.so）拷为：
#   /data/local/tmp/takeover/takeoverd  && chmod 700
setsid /data/local/tmp/takeover/takeoverd >/data/local/tmp/takeover/daemon.log 2>&1 &

# 3) 启动 Gateway（root 拉前台服务）
am start-foreground-service -n takeover.agent/.gateway.GatewayService

# 4) 开机自启（已随部署写入）
#    /data/adb/service.d/takeoverd.sh → 起 daemon + Gateway
```

## LSPosed 作用域（可选增强，勾选后 L1 通道生效）

LSPosed 管理器 → TakeoverAgent → 勾选：
- **目标 App**（按需，如微信/抖音）→ 进程内语义通道（`performClick`/`setText`/桥服务端 `tkov_<pkg>`）生效；
- **system framework** → system_server 事件审计 Hook 生效。
- 不勾也**完全可用**（root 全通道已实测），勾选后语义操作更快且中文输入无条件可用。

## 紧急停止（后悔机制）

| 层级 | 操作 |
|---|---|
| L1 工具 | `tools/call emergency_stop`（杀 daemon + 停 Gateway） |
| L1 广播 | `am startservice -a takeover.agent.EMERGENCY_STOP -n takeover.agent/.gateway.GatewayService` |
| L2 命令 | `pkill -9 takeoverd; am force-stop takeover.agent` |
| L3 总闸 | `touch /data/adb/modules/zygisk_lsposed/disable && reboot`（禁全部 Xposed；删标记恢复） |
| L4 recovery | 删 `/data/adb/modules/zygisk_lsposed`、`/data/local/tmp/takeover` |
| 任何时刻 | 长按电源 15s 强制重启（软件模块无硬件风险） |

开机自启脚本在 L3/L4 生效时会**自动随 disable 一起失效**（先禁总闸再重启即断根）。

## 构建（GitHub Actions 云端）

- Push / 手动 `Run workflow` 触发；`api_base_url`/`api_model` 走 inputs，
  **API Key 走 Secrets `API_KEY`**（注入 `assets/build_config.json`）；
- 签名：Secrets `KEYSTORE_P12_*`（Provision Signing 一次性生成）→ 自动签 release + apksigner verify；
- 产物：Release Actions Artifact（30 天）+ 自动发版 Release。
- 仓库：https://github.com/sh12345636/takeover-agent

## 真机实测记录（P2 验收，全部通过）

| 项 | 结果 |
|---|---|
| MCP initialize / tools/list / 401 鉴权 | ✅ |
| `launch_app`（am_start） | ✅ 144ms |
| `ui_tree`（uiautomator 通道） | ✅ 2.7s，6 节点 |
| `ui_tree` 降级（页面不 idle → dumpsys 兜底） | ✅ 自动降级如实记录 |
| `tap`（uinput） | ✅ **72ms** |
| `swipe`（uinput 分帧） | ✅ **168ms** |
| `type_text` 链（hook→剪贴板→ascii） | ✅ 链路就位（中文无条件可用需勾作用域） |
| selector 快速失败（超时修复） | ✅ **9s**（修复前挂 60s+） |
| `emergency_stop` + 恢复 | ✅ 三杀（服务/端口/daemon）+ 重启秒回 |
| 审计（jsonl + get_state 回显） | ✅ 成功失败全记录 |
| 开机自启（service.d） | ✅ daemon + Gateway |

## 路线图（P3）

- 代打专项：IL2CPP 状态读取（Il2CppDumper）、游戏多指编排、轨迹拟人化
- 后端 B（临时 root）frida 语义通道补全
- MCP 能力面扩展：`hook_subscribe`、任务队列与多 Agent 互斥锁
- selector DSL 升级（GKD 语法全集）
