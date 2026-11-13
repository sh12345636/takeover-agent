# TakeoverAgent · 系统级 Agent 接管完全体

> **一条路线**：root + LSPosed Hook + uinput 内核级注入，不依赖无障碍/OCR。
> **一个 APK**：LSPosed 模块 + MCP Gateway + native daemon 全部内置。
> **开箱即用**：安装 → 授权 root → 重启一次 → 完全体（作用域全自动写入，无需在 LSPosed 里手点）。

## 能力总览

| 层 | 能力 |
|---|---|
| Agent 接入 | MCP HTTP（`127.0.0.1:17352/mcp`，Bearer token）· **14 个工具** · 多 Agent 互斥锁 · 审计日志 |
| 语义操作 | 控件树（零截图）· selector 点击/长按/输入（中文安全）· WebView 穿透 |
| 系统注入 | uinput 多指（<5ms）· 拟人化滑动（贝塞尔+变速+抖动）· keyevent · root shell |
| 自动化 | `combo` 多步编排（代打核心）· 启动/切换应用 · 等待/延时 |
| 自动配置 | **LSPosed 启用 + 全量作用域自动写入**（第三方 App 全接管） |
| 可靠性 | 每个工具 L1→L3 自动降级 · 崩溃自锁 · 紧急停止五层 · 开机自启 |

## 架构

```
┌──────────────────────────────────────────────────────────────┐
│ ③ MCP Gateway  127.0.0.1:17352（token 鉴权 · JSON-RPC 2.0）   │
│    14 工具 · AgentLock 互斥 · 审计 audit.jsonl                │
├──────────────────────────────────────────────────────────────┤
│ ② 能力层 ToolExecutor（每个操作一条降级链）                    │
│    tap    : performClick(hook) → uinput → su input            │
│    ui_tree: hook 桥 → uiautomator dump(root) → dumpsys        │
│    type   : setText(hook) → 剪贴板+PASTE → input text         │
│    swipe  : 拟人化 uinput → input swipe                        │
├──────────────────────────────────────────────────────────────┤
│ ① 注入层                                                   │
│    takeoverd（root daemon · TCP 127.0.0.1:17353）             │
│      · uinput 多指注入 · root 网关（app 零 su 弹窗）            │
│    LSPosed 模块（作用域由 LsposedAutoConfig 自动写入）          │
│      · 第三方 App 全接管：控件树 / performClick / setText / 长按 │
│      · 语义桥 abstract socket `tkov_<pkg>`                     │
└──────────────────────────────────────────────────────────────┘
```

**root 是门票不是电源**：daemon 由一次性 su 拉起后常驻；开机由
`/data/adb/service.d/takeoverd.sh` 自动恢复 daemon + Gateway。

## 安装与启用（三步，完全体）

```bash
# 1) 安装（Release 已签名）
adb install -r TakeoverAgent-release.apk

# 2) 首次启动会自动完成（root 通道，无感）：
#    - 拉起 uinput daemon
#    - 写入 LSPosed 配置：启用模块 + scope = android/system + 全部第三方 App
#      （对应 LSPosed 管理器里"启用模块"开关与右侧作用域圆圈，全部自动勾好）

# 3) 重启手机一次 → LSPosed 按新配置加载 → 完全体生效
```

> 重启前 root 全通道（14 工具）已经全部可用；重启只是让 **Hook 语义通道**
> （更快的 performClick/setText/进程内控件树）生效。
> 若不重启：root 通道独立工作，仅 L1 语义增强待命。

### LSPosed 页面说明

装完你**不需要**打开 LSPosed 手动操作 —— 配置已由程序写入
（`/data/adb/lspd/config/modules_config.db`：`modules_state.enabled=1` +
`scope` 全量）。想手动调整时：LSPosed 管理器 → TakeoverAgent，
顶部开关与右侧圆圈即作用域，改动与自动配置互不冲突。

### 接管范围控制（可选）

默认 = **第三方 App 全部接管**，系统进程不注入（保稳定）。
覆盖文件 `/data/local/tmp/takeover/hook_scope.txt`（root 下发）：

```
com.tencent.mm          # 只写包名 = 白名单模式（仅接管列出的）
!com.xxx.game           # ! 前缀 = 排除
```

## MCP 接入（超级小爱 / 任意 Agent）

```bash
curl -X POST http://127.0.0.1:17352/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TOKEN>" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
       "params":{"name":"combo","arguments":{"agent":"xiaomi-ai","steps":[
         {"op":"launch","package":"com.xxx.game"},
         {"op":"wait","ms":3000},
         {"op":"tap","x":960,"y":1800,"delay_ms":200},
         {"op":"swipe","x1":800,"y1":1500,"x2":300,"y2":1500},
         {"op":"key","key":"back"}]}}}'
```

**token 获取**（三选一）：
1. 通知栏「TakeoverAgent · Gateway 运行中」全文；
2. `adb shell su -c "cat /data/data/takeover.agent/files/gateway_token"`；
3. 重装会轮换，旧 token 自动作废。

### 14 个工具

| 类别 | 工具 | 说明 |
|---|---|---|
| 感知 | `get_state` | 后端/daemon/前台/旋转/能力位/锁/审计 |
| | `ui_tree` | 结构化控件树（text/rid/cls/bounds/vid） |
| 操作 | `tap` | selector 或坐标；返回实际通道 |
| | `long_click` | 长按（语义 / uinput hold） |
| | `swipe` | 拟人化滑动（`human:false` 关闭） |
| | `type_text` | 文本输入（中文安全） |
| | `key` | back/home/recents/enter/… 数字 keycode |
| | `launch_app` | 启动应用/Activity |
| 编排 | `combo` | **多步序列**：tap/swipe/key/long/wait/type/launch |
| | `wait` | 延时（≤10s） |
| 系统 | `shell` | **root 执行任意命令**（万能兜底） |
| 协作 | `acquire_lock` / `release_lock` | 多 Agent 互斥（TTL 自动过期） |
| 保障 | `emergency_stop` | 紧急停止（杀 daemon + 停服务） |

所有操作类工具支持 `agent` 参数；**锁被他人持有时返回 `locked by …`**，
防止两个智能体抢屏幕。免锁工具：`get_state` / `ui_tree` / 锁工具 / `emergency_stop`。

## 降级链与兼容性

| 能力 | L1 | L2 | L3 |
|---|---|---|---|
| 点击 | performClick（hook） | **uinput（<5ms）** | `input tap` |
| 控件树 | hook 桥（进程内） | **uiautomator dump（root）** | dumpsys 焦点 |
| 文本 | setText（hook，中文） | 剪贴板+PASTE 键 | `input text`（ASCII） |
| 滑动 | 拟人化 uinput | `input swipe` | — |
| 长按 | performLongClick | uinput hold | `input swipe` 原地 |

- 页面不 idle（视频/动画）→ ui_tree 自动降级并如实上报 `source`；
- daemon 不在 → 自动走 su；su 不可用 → 明确报错不装死；
- 每次调用返回 `channel` 字段 = 实际生效层级，审计全程留痕。

## 紧急停止（后悔机制五层）

| 层 | 操作 |
|---|---|
| 1 工具 | `tools/call emergency_stop` |
| 1 广播 | `am startservice -a takeover.agent.EMERGENCY_STOP -n takeover.agent/.gateway.GatewayService` |
| 2 命令 | `pkill -9 takeoverd; am force-stop takeover.agent` |
| 3 总闸 | `touch /data/adb/modules/zygisk_lsposed/disable && reboot`（禁全部 Xposed，删标记恢复） |
| 4 recovery | 删 `/data/adb/modules/zygisk_lsposed`、`/data/local/tmp/takeover` |
| 任何时刻 | 长按电源 15s 强制重启（软件模块无硬件风险） |

## 构建（GitHub Actions）

仓库：https://github.com/sh12345636/takeover-agent

- Push / 手动 Run workflow 触发；`api_base_url`/`api_model` 走 inputs，
  **API Key 走 Secrets `API_KEY`**（注入 `assets/build_config.json`）；
- 签名：Secrets `KEYSTORE_P12_*` 自动签 release + apksigner verify；
- 产物：Actions Artifact（30 天）+ 自动 Release。

## 部署与运维备忘

```bash
# daemon 手动拉起（root）
mkdir -p /data/local/tmp/takeover
#   从 APK 解 lib/arm64-v8a/libtakeoverd.so → /data/local/tmp/takeover/takeoverd && chmod 700
setsid /data/local/tmp/takeover/takeoverd >/data/local/tmp/takeover/daemon.log 2>&1 &

# Gateway 启动（root）
am start-foreground-service -n takeover.agent/.gateway.GatewayService

# 开机自启（已内置）
/data/adb/service.d/takeoverd.sh   # daemon + Gateway

# 覆盖安装后若 am 报 "Not found; no service started" → 卸载重装即恢复
```

## 真机验收记录（HyperOS / Android 15 / KernelSU 3.2.5）

| 项 | 结果 |
|---|---|
| MCP initialize / tools/list / 401 鉴权 | ✅ |
| `launch_app`（am_start） | ✅ 144ms |
| `ui_tree`（uiautomator） | ✅ 2.7s；不 idle 页面自动降级 dumpsys |
| `tap`（uinput） | ✅ **72ms** |
| `swipe`（拟人化 uinput） | ✅ 168ms |
| selector 快速失败 | ✅ 9s（挂起页面不再拖死调用） |
| `emergency_stop` + 恢复 | ✅ 三杀 + 重启秒回 |
| 审计 jsonl + get_state 回显 | ✅ |
| LSPosed 自动配置 | ✅ 写入 modules_state + 全量 scope（重启后 Hook 生效） |

## 路线扩展

- 代打深度：IL2CPP 状态读取（Il2CppDumper 配合 `shell` 工具）、游戏专用 combo 模板
- 后端 B（临时 root）frida 语义通道
- `hook_subscribe` 前台/事件订阅推送（MCP SSE）
- selector DSL 全语法（对齐 GKD）
