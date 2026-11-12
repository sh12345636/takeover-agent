# GitHub 云端打包 · Android Hook 项目调研与实施方案

> 调研日期：2026-11-12 · 数据来源：GitHub API 实时检索（星数/更新时间为实测值）
> 适用系统：小米澎湃 OS（HyperOS）· 设备基线：KernelSU 3.2.5 + Zygisk + LSPosed（本机实测）
> 打包对象：**TakeoverAgent（本仓库，自研 LSPosed 模块）**；Eta 等为参考/备选基座

---

## 一、推荐项目清单与对比

### A. 同赛道基座（系统级 Agent，可直接打包）

| 项目 | ★ | 框架 | HyperOS | 运行方式 | 适配性 |
|---|---|---|---|---|---|
| **Mangi-11/Eta** | 2.8k | 免 Root 基础 + Root/LSPosed 深层 | ✅ 官方标称支持（ColorOS & HyperOS） | APK 安装即用；Android 13+ ARM64；BYOK API Key / Codex / Grok 登录；Xposed 接管小布与超级小爱 | ⭐⭐⭐⭐⭐ 与本案同赛道（对标豆包手机），自带完整 release CI，**调研基准** |
| **ReChronoRain/HyperCeiler** | 5.7k | LSPosed（系统框架作用域） | ✅ 专为澎湃 OS（"Make HyperOS Great Again"） | LSPosed 勾选 system framework + 重启；改系统 UI/设置 | ⭐⭐⭐⭐⭐ **澎湃 OS 适配标杆**，CI（ci_build/release_build）可直接抄 |
| **ColdP/HyperChanger** | 377 | LSPosed | ✅ 专为 HyperOS 4 | 控制中心/锁屏/相机定制 | ⭐⭐⭐⭐ CI（android.yml）最简，含 NDK 安装范式 |
| **MonwF/customiuizer（米客）** | 1.5k | LSPosed | ✅ 小米系 | MIUI/HyperOS 增强 | ⭐⭐⭐ 参考 |

### B. 框架/隐藏类（接管方案的配套件）

| 项目 | ★ | 类型 | 说明与适配性 |
|---|---|---|---|
| **frknkrc44/HMA-OSS** | 4.1k | **Zygisk 模块**（Hide My Applist 去 LSPosed 化） | 隐藏应用列表/安装器——**反检测配套**；KernelSU 直接装；本机 `zygisk_lsposed` 环境兼容 |
| **snake-4/Zygisk-Assistant** | 2.7k | Zygisk 模块（MIT） | 隐藏 root（KSU/Magisk/APatch，Android 5+）；**反检测配套** |
| **topjohnwu/zygisk-module-sample** | 1.1k | C++ 样板 | Zygisk 模块打包范式（CI 参考，无自带 workflow） |
| **VarunS2002/Xposed-Disable-FLAG_SECURE** | 816 | Xposed 模块（Kotlin） | 通用 Hook 模块最小实现参考；**无自带 CI** |

### C. 模板类（起步骨架）

| 项目 | ★ | 说明 |
|---|---|---|
| XposedKit/template | 0（2026-11 活跃） | 新版 Xposed 模板（Kotlin/MIT） |
| organization/LSPosed-module-template | 2 | LSPosed 模块模板（fork 自 luolong47） |
| monkeylord/XposedTemplateForAS | 207 | 老牌 AS 模板（2019，可参考思想） |

### 对比结论（选型）

1. **打包对象 = 本仓库 TakeoverAgent（自研）**——能力矩阵（控件树/uinput/代打/多 Agent 接入）是现成开源项目都不具备的，且已完成 P1 骨架 + CI 配置；
2. **Eta 作为参考实现与备选基座**：若想"先跑通别人的"，fork Eta 即可——它自带 `android-release.yml`（签名 secrets、artifact、draft release 全套），且同样面向 HyperOS；
3. **HyperCeiler / HyperChanger 作为澎湃 OS 适配与 CI 范式参考**：其 `release_build.yml`（签名 properties 注入 + SHA256 摘要 + gh-release）与 `android.yml`（NDK 安装）被本仓库 CI 借鉴；
4. **HMA-OSS + Zygisk-Assistant 作为反检测配套件**，按需后装。

---

## 二、候选项目运行方式分析

| 项目 | 依赖框架 | 安装与启用 | 所需权限 | 兼容 Android/系统 | 澎湃 OS | 已知限制与风险 |
|---|---|---|---|---|---|---|
| **Eta** | 基础免 Root；深层需 Root + LSPosed | 安装 APK → 授权 →（Root 环境）LSPosed 勾选作用域 | 通知、无障碍（GUI Agent）、Root/LSPosed（深层）、BYOK API Key | Android 13+（minSdk 33）、ARM64 | ✅ 官方适配 | 深层能力依赖 ROM 适配；模型费用自备（BYOK）；系统级 Hook 面大 |
| **HyperCeiler** | LSPosed（须 Zygisk 环境） | 装 APK → LSPosed 勾「system framework」→ 重启 | Root、系统框架作用域 | Android 12+（澎湃 OS 全系） | ✅ 本命 | **版本错配会卡开机**（改系统 UI）；AGPL-3.0 传染性 |
| **HyperChanger** | LSPosed | 同上（仅 HyperOS 4） | 同上 | **仅 HyperOS 4** | ✅（限 4） | 版本限定严格；出错需 LSPosed 秒关 |
| **HMA-OSS** | **Zygisk**（不再依赖 LSPosed） | KernelSU/Magisk 装模块 zip → 重启 → 配置管理 App | Root、Zygisk | Android 5+ | ✅（本机 zygisksu 兼容） | 与检测类 App 猫鼠游戏；模块失效不致开机故障 |
| **Zygisk-Assistant** | Zygisk | 装模块 → 重启 | Root | Android 5+，KSU/Magisk/APatch | ✅ | 同上 |
| **Disable-FLAG_SECURE** | Xposed/LSPosed | 装 APK → LSPosed 勾目标 App → 重启 | Root | 通用 | ✅（通用即支持） | 单一功能；无 CI 需自建 |

**共性启用链（所有 LSPosed 模块）**：
`解锁/Root 环境 → 装 LSPosed（Zygisk）→ 装模块 APK → LSPosed 管理器勾作用域 → 重启 → 模块生效`

---

## 三、选定项目与 GitHub 云端打包配置

### 3.1 选定

- **主打包对象：本仓库 TakeoverAgent**（后端 A 全功能路线，LSPosed 模块 + uinput daemon）
- 参考：Eta 的 `android-release.yml`、HyperCeiler 的 `release_build.yml`、HyperChanger 的 `android.yml`

### 3.2 工作流文件（已就位）

`.github/workflows/build.yml` —— 关键环节：

| 环节 | 实现 | 借鉴来源 |
|---|---|---|
| 触发 | `workflow_dispatch`（带输入参数）+ `push main` | Eta（dispatch + tag） |
| 环境 | JDK 17 + **Gradle 8.9（无 wrapper，setup-gradle 直装）** + Android SDK 35 + NDK 27.0.12077973 + CMake 3.22.1 | HyperChanger（NDK 安装范式） |
| 构建 | `gradle assembleDebug assembleRelease` | 通用 |
| 参数注入 | 生成 `app/src/main/assets/build_config.json`（随 APK 打包） | 本项目设计 |
| 产物 | `dist/TakeoverAgent-debug.apk`（可直接安装）+ `-release-unsigned.apk` + `SHA256SUMS.txt` → **Artifact 保留 30 天** | Eta（artifact + apksigner verify） |

### 3.3 使用步骤

```
1. fork/推送代码到你的 GitHub 仓库
2. Settings → Secrets and variables → Actions → New repository secret
     名称: API_KEY   值: （你的模型 API Key，构建时注入 APK）
3. Actions → "Build TakeoverAgent APK" → Run workflow
     api_base_url : 模型 API 地址
     api_model    : 模型名
     api_key_ref  : 留空（用内置 API_KEY secret）或填另一个 Secret 名
     build_flavor : both / debug / release
4. 构建完成（约 5–12 分钟）→ job 页面底部 Artifacts → 下载 TakeoverAgent-<sha>.zip
5. （可选）打 tag v* 后扩展 draft release 步骤即可发版（参考 Eta）
```

### 3.4 构建参数输入位置（第 4 点）

| 参数 | 输入位置 | 注入方式 | 安全性 |
|---|---|---|---|
| `api_base_url` / `api_model` | **Run workflow 表单 inputs** | 写入 `assets/build_config.json` | 明文（非敏感） |
| **API Key** | **仓库 Secrets**（`API_KEY` 或自定义名经 `api_key_ref` 引用） | 同上，仅从 `secrets[...]` 读取 | ✅ 不进 inputs、不进日志 |
| 签名 keystore（可选扩展） | Secrets：`KEYSTORE_BASE64/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD` | 参考 Eta：base64 解码到 runner 临时目录 | ✅ |
| 其他自定义 | 在 `build.yml` 的 `inputs:` 段**追加字段**，在"注入构建参数"步追加 JSON 键 | 同上 | 视敏感度选择 inputs 或 secrets |

> 注意：workflow_dispatch 的 inputs 会以明文出现在运行日志与 API 记录里——**API Key、密码一律走 Secrets，不要填 inputs**。

---

## 四、紧急停止 / 回滚操作指南（后悔机制）

> 原则：**分层设防，每层都能独立生效**；软件模块不可能物理损伤硬件，任何层级都可配合强制重启（长按电源键 10–15 秒，安全操作）。

### L0 预防层（已内建于本项目）

- system_server / 目标进程 Hook 回调**全程 try-catch**，异常只记日志；
- **崩溃自锁**：目标进程连续崩溃 3 次 → 自动 disable 该作用域；
- **最小作用域**：模块默认只对白名单 App 生效，`system framework` 需手动勾选；
- 所有能力可单项开关（P2 实装）。

### L1 秒级（软件内，无需重启）

| 操作 | 步骤 |
|---|---|
| 关本模块（LSPosed） | LSPosed 管理器 → 模块 → TakeoverAgent → **开关关掉**（或清空作用域）→ 重启生效 |
| 杀注入进程 | 通知栏紧急停止按钮（P2）；或 adb 见 L2 |
| 关本模块服务 | `adb shell am force-stop takeover.agent` |

### L2 adb 一键命令（推荐的主力后悔手段）

```bash
# ① 停掉本项目全部活动（服务 + uinput daemon）
adb shell su -c "am force-stop takeover.agent; pkill -9 takeoverd"
# ② 总闸 A：禁用 LSPosed（本机实测路径），重启后所有 Xposed 模块全不加载
adb shell su -c "touch /data/adb/modules/zygisk_lsposed/disable && reboot"
#    恢复：删除该 disable 文件后重启
adb shell su -c "rm /data/adb/modules/zygisk_lsposed/disable && reboot"
# ③ 总闸 B（更彻底）：禁用 Zygisk 本体（zygisksu），一切 zygisk 模块停用
adb shell su -c "touch /data/adb/modules/zygisksu/disable && reboot"
```

> `disable` 标记文件是 **Magisk/KernelSU 模块体系的标准机制**（本机 `/data/adb/modules/` 实测存在），重启生效、删除即恢复——**这是最可靠的"后悔药"**。

### L3 安全模式（adb 都进不去时）

1. **KernelSU 管理器**（若可进）：模块页 → 全部禁用 → 重启；
2. **开机音量键组合**：重启时连续按/长按 **音量下**（Magisk/KernelSU safe mode 惯例，进入后禁用所有模块）；
3. **Android 安全模式**：长按电源 → 重启到安全模式（禁第三方自启；注意 Zygisk 模块可能仍加载，需配合 L2 的 disable 文件）。

### L4 recovery 层（最后手段）

进 TWRP/OrangeFox recovery → 挂载 data → 删除或改名：
```
/data/adb/modules/zygisk_lsposed      ← LSPosed 总闸
/data/adb/modules/zygisksu            ← Zygisk 总闸
/data/adb/lspd                        ← LSPosed 数据（连带清配置）
/data/local/tmp/takeover              ← 本项目 daemon 与日志
```
改名（如加 `.bak`）比删除更稳妥，可随时还原。

### L5 本项目自禁用（代码级，P1 已埋点）

- `GatewayService.ACTION_EMERGENCY_STOP` 广播 → 杀 daemon + 停服务；
- `RootProvider` 看门狗：daemon 失联自动降级并在 MCP `get_state()` 上报；
- 模块入口 `ModuleEntry` 顶层 catch —— 任何异常零外抛，**宁可能力失效，不可系统崩溃**。

### 操作速查（出问题按顺序）

```
能进系统 → L1 关模块 / L2 命令
能 adb   → L2 三条命令（总闸 A 最常用）
adb失灵  → L3 安全模式 + L3-2 音量键
全进不去 → L4 recovery 删 disable 标记 / 目录
任何时刻 → 长按电源 15s 强制重启（安全，无硬件风险）
```

---

## 五、风险提示

1. **系统稳定性**：Hook system_server 一旦崩溃会导致反复开机——本项目已做 try-catch 全包裹 + 崩溃自锁，但仍建议**首次启用先只勾目标 App、不勾 system framework**，观察一轮再放开；
2. **开机故障**：HyperCeiler/HyperChanger 类改系统 UI 的模块，版本错配可能卡开机动画——装前确认 ROM 版本，先备好 L2 总闸命令；
3. **反检测**：游戏/银行类 App 检测 root、uinput、frida——本项目走"不进游戏进程 + uinput 设备名伪装"路线，但**反检测是猫鼠游戏，无永久方案**；
4. **账号风险**：代打类行为可能违反游戏用户协议（封号），按单游戏开关使用；
5. **安全合规**：本能力等价于"本机任意 App 内任意操作"——Gateway 仅监听 localhost + token 鉴权 + 审计日志 + App 白名单，**不得用于他人设备或未授权目标**；
6. **构建安全**：API Key 只进 Secrets；fork 仓库后先检查 workflow 文件未被篡改再启用 Actions；release 签名私钥（keystore）同样只放 Secrets；
7. **硬件层面**：软件模块无物理损坏硬件的路径；发热/耗电异常时直接 L2 总闸 + 强制重启即可。

---

*文档版本 v1.0 · 配套仓库：TakeoverAgent · CI：.github/workflows/build.yml*
