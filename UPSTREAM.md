# 上游同步记录

> ## 🔴 动手前必读：同步上游必须用户明确要求（2026-10-07 用户立规）
>
> **未经用户明确点名"同步上游 / 合并 upstream"，不得 `git fetch upstream`、不得 merge 上游**
> （连"先 fetch 看看有没有更新"也不做）。
>
> ⚠️ **「同步仓库」默认指 `origin/dev` 快进，不等于"同步上游"** —— 两者**分开问、分开报**。
>
> 得到明确许可后，按本文下方流程 + `PATCHES.md` 顶部告警执行。

## 官方仓库

| 项 | 值 |
| --- | --- |
| 地址 | https://github.com/shilapi/xcertplay |
| 协议 | HTTPS |
| 默认分支 | `master` |
| 其他分支 | `iAP2-rewrite`（上游开发分支，未跟进） |
| 许可证 | GPL-3.0 |

## 当前同步状态

| 项 | 值 |
| --- | --- |
| 同步的 upstream commit | `17c9243`（⚠️ **上游仍未打 tag**；`1.3.2` / `1.3.3` 两个跨度都是直接推 `master`） |
| 上游版本号 | `1.3.3`（上游 `1.3.2` → `1.3.3`） |
| 同步方式 | `git fetch upstream` → `git merge 17c9243`（在 `dev` 上**生成 merge commit**） |
| 同步时间 | 2026-10-07 |
| 本地 `HEAD` | `dev` = `0e56317`（merge commit）；`master` = `17c9243`（= 上游镜像） |

> ⚠️ **`--ff-only` 在 `dev` 上用不了**：`dev` 有自己的二开提交 ⇒ 两边已分叉，
> **只能生成 merge commit**。`--ff-only` 只适用于 `master` 这条纯镜像分支。
>
> ⚠️ **上游可能不打 tag 就往前推 `master`**：`1.3.2` / `1.3.3` 两次都没有 tag，
> 所以「推进版本用 tag」这条惯例在这里**不适用**，必须直接跟 `upstream/master` 的 SHA。
>
> ⚠️ **`upstream` 是 https，走本机代理时可能连不上**（实测
> `Failed to connect to github.com:443 over proxy 127.0.0.1`）。
> 连不上时先 `git ls-remote upstream` 探一下；`origin` 走 ssh，不受影响。

### 本次同步（3753867 → 17c9243）的契约影响

上游 `master` 前进 7 个提交、10 个文件、+983 / −34。**1 处冲突**，已按车主决定解决。

| 上游改动 | 对二开的影响 |
| --- | --- |
| **`feat(audio): add audio buffer control`**（新增 `MainMediaAudioBuffer`） | 🔴 **与我们 PATCH-0014 的 `MediaAudioBuffer` 撞功能**。车主 2026-10-07 决定**保留我们自己这一套**（档位 300/500/1000 + `startBytesFor` 起播保护）。代价与细节见 `PATCHES.md` 的「2026-10-07」一节 |
| **`feat(debug): add fps&latency trace graph`** + `MediaMetricsMonitor` / `MediaMetricsOverlayView` | **免费拿到**（上游自带的 FPS/延迟追踪）。⚠️ 它依赖 `AndroidMediaSink.AudioRenderer.trackBytesPerFrame` —— 合并时**差点被当成上游缓冲逻辑的一部分删掉**，删了会除零。已保留 |
| **`fix(audio): add missing MainMediaAudioBuffer sources`** | 补上游自己漏提交的文件 |
| **改动 `armWirelessHandoffWatchdog`**（超时不再 `fail`，改"继续走蓝牙 iAP2"） | ⚠️ **紧邻 PATCH-0009 的 watchdog**。`PATCHES.md` 在 2026-10-06 就预告了这个风险；本次逐段核对：我们的 `armWirelessAttachWatchdog` 语义**完好未被污染** |
| `fix: Type-130 handshake as a optional requirement` | 对二开无直接动作 |
| `common/…/AirPlayPersistence.kt` +41、`CarPlayHostActivity.kt` +168（缓冲滑块 / 指标开关） | 二开不复用上游宿主（`CarPlayApp` 是自建）⇒ **那两个设置项在 DBPlay 里不渲染**。所以"两套缓冲设置"不会同时出现在车机上 |

**逐条复核**（合并**之后**在工作区 grep 锚点，不是看合并输出推断）：

- 0001/0006、0004、0007 上游**未动同文件**；
- 0005、0010 上游动了 `CarPlayController.kt`，但**区域不重叠**，自动合并成功；
- 0008 上游动了 `AndroidMediaSink.kt`（同文件），但**只有 1 个文件真冲突**；
- 0009 上游动了**紧邻函数**（`armWirelessHandoffWatchdog`），逐段核对无误；
- 0010 补登记（`CarPlayHostActivity.kt`）自动合并，我们的 `SAME_LAN` label 仍在。

⚠️ **"零冲突"不等于"补丁还在"** —— 上表结论是合并后在新工作区 grep 锚点复核出来的。

⚠️ **验证时别用 `assembleDebug`**：它会撞 `car/app/build.gradle.kts` 的 `allowRelease`
闸门并**报 BUILD FAILED**，那是设计如此、不是构建失败。用
`compileDebugKotlin` / `testDebugUnitTest`。

### 本次同步（1.3.1 → 3753867）的契约影响

上游 `master` 前进 5 个提交、9 个文件、+546 / −7。**合并零冲突，6 个已落地补丁全部完好。**

| 上游改动 | 对二开的影响 |
| --- | --- |
| **新增 `MfiTarget.LOCAL_FILES` + `LocalMfiAuthenticationClient`（234 行）** | 上游自己实现了本地文件 MFi。⚠️ **不打断二开层**：我们用的是自有枚举 `CarMfiTarget`，映射只在 `CarPlayConfigBuilder.toUpstream()` 一处，`car/` 下没有对上游 `MfiTarget` 的穷尽式 `when`。⚠️ 但我们的 `CarMfiTarget.LOCAL_OFFLINE` 因此**变成冗余**（能力重叠，唯一不可替代的是"随包内置身份回落"）——迁移另开一轮，本轮不动，详见 `PATCHES.md` 的「2026-09-30」一节 |
| **`fix: request keyframe when video frames arrive without codec config (HEVC)`** | `AndroidMediaSink.VideoDecoder`：`lastConfig == null` 时原来是**静默丢帧**，现在改为请求关键帧。**免费拿到**。⚠️ 但与我们 2026-09-28 那条"HEVC 间歇绿屏"**不是同一个失败模式**（那条的现场是 codec config **正常到达**）⇒ 不紧急，合并后值得再试一轮 HEVC |
| **`fix: fall back to WPA2 when the P2P group security type is unreported`** | `WifiP2pGroupManager` 的 `else -> throw IOException` 改成记日志 + 假设 WPA2。与我们"别自造更严的判据"同源。⚠️ 我们**已把 `WIFI_P2P` 从 `CarHotspotMode` 删掉** ⇒ 对二开无直接收益 |
| `AirPlayPersistence` +24 行、`CarPlayHostActivity` +180 行（文档选择器 UI） | 二开不复用上游宿主（`CarPlayApp` 是自建），**无影响** |
| `README.md` / `README.zh-CN.md` | 纯文档 |

**逐条复核**（`git diff --stat 3753867 HEAD` 的结果就是 `PATCHES.md` 的条目清单，一条不多）：
0001/0006、0004、0007 上游**未动同文件**；0005（`CarPlayController.kt`，上游 +31）与
0008（`AndroidMediaSink.kt`，上游 +16）上游**动了同文件但区域不重叠**，自动合并成功。
⚠️ **"零冲突"不等于"补丁还在"** —— 上表结论是合并后在新工作区 grep 锚点复核出来的。


### 本次同步（1.2.4 → 1.3.1）的契约影响

上游跨了 `1.3.0` + `1.3.1` 两个 tag、13 个提交、42 个文件、+2735/−607 行。重头戏：

| 上游改动 | 对二开的影响 |
| --- | --- |
| **`WIFI_P2P` 改用 MFi 证书派生的固定 SSID/密码**（`mfiCertificateWifiP2pCredentials()`） | 与 PATCH-0004 想解决的问题重叠，但**不是替代品**：证书派生的名字用户改不了。PATCH-0004/0005 保留。⚠️ 二开侧**已不暴露 `WIFI_P2P` 这个选项**（`CarHotspotMode` 里已删除，理由见 `ADB-HOTSPOT.md` §3.5），所以这个上游改动对二开不可见 |
| `CarPlayController.startWirelessHotspot(generation)` → `(generation, mfi)` | ⚠️ 与 PATCH-0005 同文件 ⇒ 必然冲突。本次已手工重放（`LOCAL_ONLY_HOTSPOT` 分支那一行未变，可干净重放） |
| 新增 `Iap2FileTransferReceiver` / `CarPlayMediaSessionService` / `MicrophoneSupport` / `VideoDecodeFlow` / `VideoParameters` | 纯新增，二开不依赖 |
| `AndroidMediaSink` 大改（+230）、`MediaCodecSupport`、`OpusEncoder` 重写 | 二开只通过 `UpstreamMediaSinkBridge` 用 `MediaSink` 接口，需编译验证 |
| 新依赖 `androidx.media3`（session/container）1.11.1、`concentus` 1.0.0 | 声明在 `shared/build.gradle` 的 `implementation`，不进二开编译类路径，但会进 APK |
| `shared/AndroidManifest.xml` 新增 `CarPlayMediaSessionService`（`exported=true`，`mediaPlayback`）与 3 个权限 | 随库清单自动合并，二开 App 无需改 |
| `AirPlayPersistence` +14 行、`CarPlayHostActivity` +267 行 | 二开不复用上游宿主，无影响 |


## 二开版本

| 项 | 值 |
| --- | --- |
| 二开分支 | `dev`（已创建并推送；`master` 保持为上游镜像） |
| 分支模型 | `master` = 上游镜像；`dev` = 二开主线（见 ARCHITECTURE.md §11.1，已落地） |
| 二开 `applicationId` | `com.dbtool.carplay`（与上游 `com.shilapi.xcertplay` 不同，两者可共存安装） |
| 二开扩展模块版本 | 未单独打版本，随仓库走 |

> `applicationId` 的隔离已在 `car/app/build.gradle.kts` 落地：二开产品 App
> 与上游 APK 可以同时装在车机上，便于真机对比新旧 UI 行为。

## Git remote 现状（2026-09-28 更新）

```
origin    git@github.com:xbrooke/DBPlay.git      (fetch/push)   ← 二开私密仓库
upstream  https://github.com/shilapi/xcertplay.git (fetch/push)  ← 官方仓库
```

**分支模型（已落地）**：

| 分支 | 内容 | 说明 |
| --- | --- | --- |
| `master` | 纯上游镜像（当前 `17c9243`） | **不要在它上面提交二开改动**，否则 `merge --ff-only` 会失效 |
| `dev` | 二开主线（当前 `e49d2b5`） | 日常开发在这里；上游更新合进这里 |

```powershell
# 日常
git checkout dev
git push                       # → origin/dev
```

### ⚠️ 本机环境怪癖：`refs/remotes/*` 的写入会被静默丢弃

**已实测确认**（2026-09-28）：在这台机器上，git 对 `refs/remotes/**` 的写入
**报告成功但不会落盘**——

```powershell
git update-ref refs/remotes/origin/probe HEAD   # 返回 0，无报错
git rev-parse refs/remotes/origin/probe         # fatal: unknown revision
find .git/refs/remotes -type f                  # 空
```

后果：`git fetch` 打完 `* [new branch] dev -> origin/dev` 之后，
`origin/dev` 依然解析不了，`git branch -vv` 显示 `[origin/dev: gone]`，
`git branch -r` 为空。**这不是 git 配置错误，重试也没用。**
（`refs/heads/*` 与 `refs/tags/*` 正常，所以提交、打标签、推 tag 都没问题。）

**绕过办法（已配置好，无需再改）**：把 fetch 的 refspec 指到别的命名空间——

```powershell
git config remote.origin.fetch   "+refs/heads/*:refs/origin/*"
git config remote.upstream.fetch "+refs/heads/*:refs/upstream/*"
```

改完之后以下全部恢复正常（`refs/origin/*`、`refs/upstream/*` 都能落盘）：

```powershell
git log -1 upstream/master        # 17c9243
git log -1 origin/dev             # e49d2b5
git merge --ff-only upstream/master   # 现在也能用了
```

唯一代价：`git branch -r` 仍为空（它只认 `refs/remotes/`）。
要看远端分支列表用 `git ls-remote --heads origin`。

> 顺带说明：这也是之前记录「`git log upstream/master` 报 unknown revision，
> 所以推进版本只能用 tag」的**真正原因**。tag 能用的原因是 tags 走
> `refs/tags/*`，不受这个怪癖影响。现在 refspec 改过之后，
> tag 与 `upstream/master` 两条路都能走。
>
> ⚠️ **推荐用 `upstream/master` 而不是 tag**（2026-09-30 更正）：
> 上游可能**不打 tag 就直接往前推 `master`**，那时 `<tag>` 会把你带回旧位置；
> 而 `upstream/master` 永远是最新。tag 只适合用来确认"某个版本的边界在哪"。

## 本地环境准备（每台开发机一次）

`local.properties` 已被 `.gitignore` 忽略，**不会**进仓库，但必须存在，
否则 AGP 找不到 SDK。内容必须使用**转义后的反斜杠**：

```properties
sdk.dir=C\:\\Users\\<你的用户名>\\AppData\\Local\\Android\\Sdk
```

> ⚠️ 不要用正斜杠（`C:/Users/...`）。`local.properties` 位于工程根目录，
> 会被 Android Lint 的 `PropertyEscape` 检查扫到，
> 正斜杠会导致 `:common:lintDebug` **直接失败**（这是本地环境问题，不是代码问题）。

本项目构建所需的 SDK 组件（与 `.github/workflows/android.yml` 一致）：

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK（启动 Gradle） | 17+ | 实际编译用 toolchain Java 25，由 foojay 自动下载 |
| Android SDK Platform | `platforms;android-37.0` | 注意是 `37.0` 不是 `37` |
| Build-Tools | `build-tools;36.0.0` | CI 使用版本 |
| NDK | `ndk;28.2.13676358` | `:shared` 的 `linux_i2c_jni.c` 需要 |
| Gradle | `9.5.0` | 由 wrapper 自动下载 |

安装命令（用 SDK 自带的 `sdkmanager`）：

```powershell
$sm = "<AndroidSdk>\cmdline-tools\latest\bin\sdkmanager.bat"
& $sm --sdk_root="<AndroidSdk>" "platforms;android-37.0" "ndk;28.2.13676358" "build-tools;36.0.0"
```

首次构建会额外下载 Gradle 9.5.0 发行包与 JDK 25，耗时较长（本项目实测首次约 7 分钟）。

## 同步方法

### 一键脚本（推荐）

```powershell
# 在仓库根目录（⚠️ 是 scripts/ 不是 tools/，见下方注意事项第 7 条）
.\scripts\sync-upstream.ps1
```

脚本会依次执行：`fetch upstream` → `master` 快进到上游 → `dev` 合并 `master`
→ 提示需要人工检查的文件 → 提示运行构建验证。

### 手动等价命令

```powershell
git fetch upstream --tags
git checkout master
git merge --ff-only upstream/master   # ⚠️ 上游可能不打 tag，用 upstream/master 比 <tag> 稳
git checkout dev
git merge master                      # 这一步必然产生 merge commit，不能 --ff-only
```

> ⚠️ 若上游只推 `master` 而没打 tag（2026-09-30 就是），`--ff-only <tag>` 会快进到**旧位置**。
> 先 `git log --oneline -1 upstream/master` 确认拿到的是不是最新 SHA。

### 为什么用 merge 而不是 rebase

见 `ARCHITECTURE.md` §11.2。结论：merge 保留上游 commit hash、
冲突只需解决一次、无需 force push。rebase 只用于整理本地未推送的提交。

### 同步后必做的验证

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
.\gradlew.bat :shared:testDebugUnitTest :common:lintDebug :mobile:lintDebug :automotive:lintDebug :mobile:assembleDebug :automotive:assembleDebug
.\gradlew.bat :car:api:assembleDebug :car:core-bridge:assembleDebug :car:platform-android:assembleDebug :car:platform-flyme:assembleDebug :car:platform-ecarx:assembleDebug :car:steering:assembleDebug :car:ui:assembleDebug
```

如果 `:car:core-bridge` 编译失败，**大概率是好事**：
`CarPlayStatusMapper.kt` 的穷尽式 `when` 会因上游新增
`CarPlayStatus` 取值而报错，按提示把新状态归入合适的 `CarSessionPhase` 即可。
这是刻意设计，不要改成 `else`。

## 本地 Patch / 修改点

当前共 **6 项 APPLIED + 2 项 PROPOSED**，详见 `PATCHES.md`：

| ID | 文件 | 状态 |
| --- | --- | --- |
| PATCH-0001 | `settings.gradle.kts`（追加 8 行 `include`） | ✅ APPLIED |
| PATCH-0002 | `shared/.../CarPlayController.kt`（无线热点工厂） | 📋 PROPOSED（未应用） |
| PATCH-0003 | `shared/.../CarPlayController.kt`（VpnService 类注入） | 📋 PROPOSED（未应用） |
| PATCH-0004 | `shared/.../network/LocalOnlyHotspotManager.kt`（固定 SSID/密码 + 网卡识别） | ✅ APPLIED |
| PATCH-0005 | `shared/.../orchestration/CarPlayController.kt`（热点凭据透传） | ✅ APPLIED |
| PATCH-0006 | `settings.gradle.kts`（`rootProject.name` → `DBPlay`） | ✅ APPLIED |
| PATCH-0007 | `shared/.../media/MicrophoneSupport.kt`（音频模式读回校验降级） | ✅ APPLIED |
| PATCH-0008 | `shared/.../media/AndroidMediaSink.kt`（下行 PCM 增益钩子 + 新增 `AudioGain.kt`） | ✅ APPLIED |

除上面 6 个 APPLIED 条目外，**其余对上游的接触点为零**：
`car/` `configs/` `scripts/` `*.md` 全部是上游没有的新增路径，
`git merge upstream/master` 在这些路径上不可能产生冲突。

> ⚠️ **二开层此前长期未入库**（`git ls-files car/` = 0）。已于 **2026-09-28**
> 首次提交并推送到 `origin`（`dev` = `3c2c798`，145 个文件）。
> 合并上游前不再需要手工备份，但**推进上游前请确认 `git status` 干净**。


## 后续同步注意事项

1. **预期冲突点**（以 `PATCHES.md` 汇总表为准）—— 按「上游可能改到的文件」列，共 **5 个**：
   `settings.gradle.kts`（PATCH-0001 + 0006，+15/−1）、
   `shared/.../network/LocalOnlyHotspotManager.kt`（PATCH-0004，**+272/−19，改动最大**）、
   `shared/.../orchestration/CarPlayController.kt`（PATCH-0005，**上游活跃文件**）、
   `shared/.../media/MicrophoneSupport.kt`（PATCH-0007，+32/−3）、
   `shared/.../media/AndroidMediaSink.kt`（PATCH-0008，**上游活跃文件**，+37/−1）。

   ⚠️ **别只看"上游动了哪个文件"**：2026-09-30 那次上游**同时改了**
   `CarPlayController.kt` 与 `AndroidMediaSink.kt`（正是 PATCH-0005 / 0008 所在的文件），
   结果**零冲突**——因为改动**行区间不重叠**。
   ⇒ 合并前先用临时分支试跑（`git merge --no-commit --no-ff` 看冲突面后 `--abort`），
   **拿实测结果说话，别凭文件名推断风险**。
   ⚠️ 而且「零冲突」**不等于**「补丁还在」——合并后必须逐个 grep 锚点复核
   （`git diff --stat <上游 SHA> HEAD` 的结果应该正好是 `PATCHES.md` 的条目清单）。
2. **`:car:core-bridge` 是唯一需要跟上游 API 演进的模块**。
   重点看两处：
   - `CarPlayStatusMapper.kt` —— `CarPlayStatus` 取值变化；
   - `CarPlayConfigBuilder.kt` —— `CarPlayRuntimeConfig` / `AirPlayConfig` 字段变化。
3. **上游 App 层（`mobile` / `automotive`）无需跟随**。二开产品 App 会另建模块。
4. **不要跟进上游的 `iAP2-rewrite` 分支**，除非它合并进 `master`
   且你确认要承担协议层变更风险。
5. **验证硬件链路的行为不能只靠编译**。协议栈相关上游更新
   （`airplay/` `iap2/` `mfi/` `transport/`）必须在真实 MFi 硬件 + iPhone 上回归，
   模拟器只能验 UI。
6. **记录每次同步**：在本文件"当前同步状态"表格更新 commit 与时间。
7. **上游 1.2.4 起 `.gitignore` 新增了 `tools/` 排除**（同时新增 `research/`、
   `mobile/release/`、`mobile/debug/`，并把 `/agent_docs/` 改成 `agent_docs/`）。
   该模式**不带前导斜杠，因此匹配任意层级**（`car/tools/` 也一样中招）。

   **✅ 已解决（2026-09-28）：把目录从 `tools/` 改名为 `scripts/`。**
   三个脚本（`sync-upstream.ps1` / `footprint.ps1` / `install-mfi-identity.ps1`）
   现已可被正常跟踪，且**没有新增任何上游文件改动**。

   两个容易踩的 git 事实（当时据此排除掉另两个对策）：

   | 事实 | 后果 |
   | --- | --- |
   | 父目录被排除时**无法** re-include 其中文件 | `tools/.gitignore` 写 `!*` 无效——git 根本不进该目录 |
   | `tools/` 模式匹配任意层级 | 搬到 `car/tools/` 也无效，必须换名字 |

   | 已否决的对策 | 否决理由 |
   | --- | --- |
   | 改上游 `.gitignore` 追加 `!tools/` | 新增一处上游偏离；`.gitignore` 是上游活跃文件（1.2.4 刚改过） |
   | `git add -f tools/` 强制跟踪 | 依赖人工记得 `-f`；一次 `git reset` 就静默退回未跟踪 |

   ⚠️ **不要再把目录改回 `tools/`。**
