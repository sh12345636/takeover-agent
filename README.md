# TakeoverAgent · 系统级接管模块骨架（P1）

> 主线：**后端 A（KernelSU/Magisk + Zygisk + LSPosed 全功能）**；
> 兼容：**后端 B（临时 root 会话）同 APK 内可用**，探测自动切换；
> 路线：系统级 —— LSPosed 注入 + system_server Hook + uinput 内核级注入，不依赖无障碍/OCR。

## 目录

```
takeover-agent/
├── README.md                          ← 本文
├── settings.gradle.kts
├── build.gradle.kts
└── app/
    ├── build.gradle.kts               ← 双 ABI + 16KB 页对齐 + NDK r28
    └── src/main/
        ├── AndroidManifest.xml        ← LSPosed 模块声明 + specialUse 前台服务
        ├── assets/xposed_init         ← LSPosed 入口类
        ├── cpp/
        │   ├── CMakeLists.txt         ← 编出 libtakeoverd.so（实为可执行）
        │   └── takeoverd.c            ← uinput 多指注入 daemon（系统级核心）
        └── java/takeover/agent/
            ├── ModuleEntry.kt         ← LSPosed 入口：按作用域分发
            ├── hooks/SystemServerHooks.kt  ← system framework 作用域：事件审计+增强
            ├── hooks/AppHooks.kt      ← 目标 App 作用域：控件树/语义点击/文本
            ├── root/RootProvider.kt   ← 后端 A/B 统一特权层（su 探测/会话/降级）
            └── cap/FallbackRouter.kt  ← L1→L5 降级路由（兼容性核心）
```

## 构建与安装（后端 A）

```bash
# 0) 依赖：Android Studio (AGP 8.x) + NDK r28 + CMake 3.22+
gradle :app:assembleRelease          # 产出 TakeoverAgent APK

# 1) 安装
adb install -r app-release.apk

# 2) LSPosed 管理器里勾选作用域（缺一不可）：
#    ☑ system framework   ← system_server 注入/审计（后端 A 标志）
#    ☑ 目标 App（如微信/游戏包名，按白名单逐个勾）
#    然后重启生效

# 3) 初始化（APK 首次启动会自动执行）
#    - Capability Probe → capability_profile.json
#    - su 拉起 uinput daemon：/data/local/tmp/takeover/takeoverd
#    - 校验三通道：performClick / injectInputEvent / uinput
```

## 后端 A / B 的切换（同一 APK）

| | 后端 A（本机现状） | 后端 B（临时 root） |
|---|---|---|
| 判定 | `su` = KernelSU/Magisk **且** `pidof lspd` 活着 | `su -c id` 通，但无 LSPosed（或已挂） |
| 语义通道 | LSPosed 进程内 Hook（AppHooks） | frida-server attach（`Java.use()` 同款逻辑） |
| 注入通道 | **uinput daemon**（首选）+ system_server 审计 | **同一 uinput daemon**（root 窗口内 setsid 拉起，fd 持有后不依赖会话） |
| 状态读取 | Hook + `dumpsys`（root） | `dumpsys`（root） |
| 会话保障 | 常驻 | 看门狗 30s 探测 `su -c id`，失联自动降级 C 并通知 Agent |

**root 是门票不是电源**：daemon 在提权窗口内 `open("/dev/uinput")` 成功后，
后续写入不再校验 root —— 临时 root 断了，注入照样工作（重启除外）。

## 作用域与白名单

`AppHooks.kt` 顶部 `TARGET_WHITELIST` 为初始白名单（骨架写死），
生产版改为 `/data/local/tmp/takeover/whitelist.txt`（root `chmod 644` 下发），
模块侧读取后按包名匹配，未命中不装 Hook（最小侵入）。

## 下一步（P2+）

- Gateway MCP Service（`specialUse` FGS）：UDS + localhost SSE + token
- 选择器 DSL、`capability_profile` 驱动的完整降级链
- IL2CPP 状态读取（代打）、轨迹拟人化
- frida 后端实现（后端 B 的语义通道）
