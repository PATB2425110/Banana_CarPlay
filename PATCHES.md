# 对上游的改动登记（Patch Ledger）

## 2026-11-21：按用户追问执行车辆读取安全版出包

- 用户问“为什么不出包”，本轮按出包请求执行；前阶段只验证未打包不是编译失败。不覆盖前阶段不可变证据或旧2244交付。
- Release前复查发现崩溃分类按Geely reader包前缀匹配，R8可改名；在产品proguard限定`-keepnames class com.dbtool.carplay.geely.vehicle.**`，既有源码接线guard补断言。不扩大保留全部业务、不改变协议，仍不保证每个优化栈都可归因。
- 实际8模块测试（前7模块＋App）全量强制执行：`BUILD SUCCESSFUL in 38s`，182tasks全执行、exit0；XML2468项0失败／错误／跳过。出包前冻结867构建输入、105个历史顶层交付文件，原工程1044项摘要一致。Release构建及最终交付结果据实补录至verification/apple-product/vehicle-read-safety-release-20261121.md。
- 签名Release实际`BUILD SUCCESSFUL in 1m 37s`、338tasks（97执行／241UP-TO-DATE）、exit0，原证书SDK28验签／zipalign／Manifest及DEX检查通过；新交付`AppleCarPlay-1.4.2-375-202611210125.apk`，SHA256 `42775B7FBF1664BDED474D4E532141F038957AEE9D67886E4E1FE2D43045C314`。48个存活原始reader类名保留；校验器首跑误判R8删除／synthetic条目，限定分类并补关键DEX描述符验证后exit0，非APK／编译失败。原工程1044项和历史105文件未变，新建独立清单及309份XML等证据；未加固／未安装／未实车验收／未提交推送。

## 2026-11-21：车辆读取默认关闭开关与崩溃回退

- 用户要求“增加开关，打开闪退自动关闭读取”。本次改动仅car模块的开关、平台读取安全封装及启动保护，不修改shared/协议；默认关闭时core-bridge不提供vehicleStatusProvider，故不新增A1声明。
- 独立安全prefs避免普通设置草稿／导入重新打开故障闸门；开需手动确认并重连，关立即关闭读取lease，不重启／停止整个CarPlay。
- 读取worker首次触及厂商代码前持久化活跃会话标记，最后正常清理后清除；进程不正常退出留下标记时，下次启动关闭。系统杀进程／断电也可能触发，属于保护性回退不是故障归因。读取相关Java未捕获异常先落盘关闭再交原crash handler，不吞掉全局异常，不承诺当次native崩溃自救。
- marker按活跃读取期间写一次／清一次，不逐秒每样本commit。未知权限／字段不可用保持原逻辑，不把普通无数据当崩溃。测试／编译及限制见verification/apple-product/vehicle-read-safety-switch-20261121.md。
- 复审补旧控制器generation隔离（关／开后必须手动重连）、严重反射Error穿透、组合清理错误不再假装成功、清理失败禁止同进程重开、快照／close不在source锁内调用，以及crash handler无论保护是否失败都在finally衔接原handler。
- 实跑7模块全量JUnit、Geely／App Kotlin及Manifest合并：`BUILD SUCCESSFUL in 1m 24s`，141tasks全执行，exit0；XML2445项0失败／错误／跳过（新增39项），专项138项／平台80项。未产新APK／未安装，不改已交付接口文档及其阶段快照。
- 最后复审补通知乱序：source按即时generation清理，UI有界合并通知后在界面协程重读最新状态，不让迟到off回调误停新lease／覆盖最新保护原因。补1项逆序用例后再次7模块及App全量强制重跑，`BUILD SUCCESSFUL in 45s`，141tasks全执行，exit0；最终XML2446项0失败／错误／跳过（共新增40项），专项139项／平台80项；上行2445为前一阶段。

## 2026-11-20：Apple CarPlay独立工程接入燃油车Vehicle Status

- 用户明确授权燃油车数据接入及补齐订阅；仅本独立工程，不同步／获取上游，不改原DBPlay-dev。
- `shared/.../transport/Iap2VehicleStatusClient.kt`新增A100 void字段选择、A101 u16/i16/u8编码、首次有效／未知快照、变化去重、A102及通道退出清理；未知不填0，不使用手机／主机数据冒充车况。
- `Iap2IdentificationClient.kt`按实际provider存在追加A101发送及A100/A102接收声明；`Iap2WiredControlClient.kt`、`Iap2WirelessControlClient.kt`在既有控制循环处理／发送，不增发送线程；`CarPlayController.kt`接线有线、RFCOMM、隧道及最终释放。`Iap2Endpoint.kt`补字段schema，不改原导航、电源、通信、HID能力及RGD字节声明。
- 二开层以可选只读平台API及独立lease适配；燃油续航不叠加EV、NightMode不取App主题，不执行任何车辆setter。新增状态机／编码／生命周期／接线测试，实车与iPhone互操作待验。
- 验证与边界随`verification/apple-product/vehicle-status-integration-20261120.md`补录；首次shared/core-bridge compile/test实际`BUILD SUCCESSFUL in 51s`，20项新增用例0失败；不是最终全模块验收，不出包。
- 台账流程踩坑：本条是在首轮实现／测试后补记，未遵守本文件“先登记”的顺序；如实保留，后续共享层改动须先登记，不将补记冒称预先登记。
- 后续预先登记：新声明可能使手机在identify之后、MFi完成前排队发送A100/A102。计划只在提供车辆provider时，在MFi循环有界暂存最后一条有效A1请求，AA05成功后才交给车辆状态处理；不提前读车况／发A101，不修改证书、challenge、AA04失败或认证超时语义，其它未知消息仍拒绝。该防御性时序处理不是已观察到手机早发的实车结论。
- 审查后预先登记：分离“已接受A100”和“lease已打开”；open暂时失败时保留原请求并30秒限速重试，A102／close取消待重试状态。补对应恢复／停止测试及open非阻塞接口合同，关闭失败记诊断，不以异常吞掉后谎报清理成功。
- 后续复审：同selector A100保留退避不再stop重置；MFi阶段hook在MFi类自身也限于A100/A102。以上各项不把“声明了A1”当车辆接口已有合法权限；平台完整质量路径和系统／签名vendor权限限制见接入记录。无需修改原导航／热点／媒体协议或其它AA认证语义。

## 2026-11-11：退出导航无后续消息时主动结束宽限期

- 修改 `shared/.../iap2/message/Iap2NavigationMessages.kt`、`shared/.../orchestration/CarPlayController.kt` 及 `Iap2ProtocolTest.kt`。
- 旧代码对 guidanceState=0 只设置 3 秒结束宽限，靠下一条帧检查时间；退出后无后续帧就无法发布停止。新增同步检查接口与仅在明确 pending-end 存在时的定时检查，保留 3 秒宽限，恢复有效路线时取消；controller close 移除任务。
- 二开配套：会话解绑前主动发桥接停止；转入道路与旧版第二行目的地分开封装，原厂 nextRoadName 不再被终点占位；新增重复快照诊断（5 秒限速）。
- 不把原始输入中连续不变的距离冒充丢包；不承诺手机只给“未知位置”时能够恢复真实终点。实车待测。

## 2026-11-11：CX11 首次无线连接延迟优化（桥接配套版）

- 来源：11 月 10 日诊断包，首次自建热点 ADB 看门狗持锁约 11～13 秒；自建热点又被按 MANUAL 复用路径等待 10 秒。原厂热点首次无客户端时同样在发送入网凭据前等待 10 秒，后续还有 AirPlay 回连失败，不能把这些失败都归因于等待。
- 上游修改：`shared/.../orchestration/ReuseClientSettle.kt`、`CarPlayController.kt`、`CarPlayRuntimeConfig.kt`、`ReuseClientSettleTest.kt`。新增独立的 `manualHotspotSkipClientSettle`，仅实际 ADB 自建 AP 启用，不能复用被 GEELY_ROUTED 借作配置校验开关的 `manualHotspotSelfHosted`。已就绪的自建 AP 不查询原厂客户端；复用 AP 搜寻客户端预算 2 秒，发现后仍保留完整 2 秒稳定时间。旧工具默认 10 秒策略保留，调用点显式选择新预算。
- 二开层：ADB 看门狗首次巡检延后 30 秒，热点启动不依赖看门狗预先拿 root；明确启动自建 AP 时按需提权。adb root 客户端请求预算 3 秒，首次重连等待 500ms，仍以实际 shell uid=0 判定成功。原厂客户端采样由 5 秒缩至 1 秒，重复日志单独限速。
- 验证：`:shared:testDebugUnitTest :car:core-bridge:testDebugUnitTest :car:platform-geely:testDebugUnitTest :car:app:compileDebugKotlin -PallowRelease=false --no-configuration-cache --offline`，BUILD SUCCESSFUL in 24s。实车待测，不承诺原厂 TCAM 路由/转发问题已解决。

> **规则**：任何对 `shared/` `common/` `mobile/` `automotive/` `gradle/` `build.gradle.kts`
> `.github/` `README*.md` `LICENSE` `asset/` 的修改，**必须先登记在本文件**。
>
> 详细流程见 `ARCHITECTURE.md` §13。禁止悄悄修改。

> ## 🔴 同步上游必须先获用户明确确认（2026-10-07 用户立规）
>
> **未经用户明确点名"同步上游 / 合并 upstream"，不得 `git fetch upstream`、不得 merge 上游。**
> 连"先 fetch 看看有没有更新"也不做 —— fetch 本身无害，但它是同步的第一步，
> 做了就容易顺手 merge 下去。
>
> ⚠️ **「同步仓库」默认 ≠ 同步上游**：用户说"同步最新 DBPlay 仓库"时，指的是
> **`origin/dev` 快进**。两者**分开问、分开报**。
>
> **为什么必须挡**：同步上游会改上游文件、产生冲突需要**产品决策**
> （2026-10-07 那次就撞上"两套媒体缓冲用哪套"）、并前移台账基线 ——
> 这三件事都不是"顺手做完"的量级。
>
> **正确姿势**：先问"要不要同步上游？上游现在到 `xxx`"，得到明确许可后再动。
> 动手前重读本文件顶部告警 + 按 `UPSTREAM.md` 的流程走。

> ## 本 fork 与上游的关系（2026-09-28 用户确认）
>
> - **这些 patch 只留在二开仓库里，不往上推。** 上游 `shilapi/xcertplay` 不会收到它们。
> - **上游更新仍会合并进来**，但 ⚠️ **每次合并前必须先获用户明确确认**
>   （见上方「🔴 同步上游必须先获用户明确确认」）；`scripts/sync-upstream.ps1` 保持可用。
> - 二开私密仓库：`origin = git@github.com:xbrooke/DBPlay.git`；
>   二开主线分支 `dev`，`master` 保持为上游镜像（详见 `UPSTREAM.md`）。
>
> ⇒ 本文件的定位**不变**：控制上游合并时的冲突范围。
> §13 第 5 条「同步上游后逐个复核 patch 是否仍然必要，能撤销就撤销」**依然有效**。
> 改动要尽量小、锚点要稳的理由都在。

## 汇总

| ID | 文件 | 状态 | 是否可用 Adapter 替代 | 对应上游 commit |
| --- | --- | --- | --- | --- |
| PATCH-0001 | `settings.gradle.kts` | ✅ **APPLIED** | 否（Gradle 硬性要求） | `3ac55e34` |
| PATCH-0002 | `shared/.../orchestration/CarPlayController.kt` 无线热点工厂 | 📋 PROPOSED | 否（Core 内 `when` 硬编码） | `3ac55e34` |
| PATCH-0003 | `shared/.../orchestration/CarPlayController.kt` VpnService 类注入 | 📋 PROPOSED | 否（Core 内硬编码 `::class.java`） | `3ac55e34` |
| PATCH-0004 | `shared/.../network/LocalOnlyHotspotManager.kt` 固定 SSID/密码 + 网卡识别 | ✅ **APPLIED** | 否（Core 内部实现，构造点在 `when` 里） | `1.3.1` `7b66322` |
| PATCH-0005 | `shared/.../orchestration/CarPlayController.kt` 热点凭据透传 | ✅ **APPLIED** | 否（同 PATCH-0002 的硬编码点） | `1.3.1` `7b66322` |
| PATCH-0006 | `settings.gradle.kts` 根项目名 `xcertplay` → `DBPlay` | ✅ **APPLIED** | 否（Gradle 硬性要求） | `1.3.1` `7b66322` |
| PATCH-0007 | `shared/.../media/MicrophoneSupport.kt` 音频模式读回校验降级 | ✅ **APPLIED** | 否（见下"为什么没有 Adapter 出路"） | `1.3.1` `7b66322` |
| PATCH-0008 | `shared/.../media/AndroidMediaSink.kt` 下行 PCM 增益钩子（+ 新增 `AudioGain.kt`） | ✅ **APPLIED** | 否（`AudioRenderer` 是 `private class`，见下） | `1.3.1` `7b66322` |
| PATCH-0009 | `shared/.../orchestration/CarPlayController.kt` 「等 iPhone 反向连接」的有界超时 | ✅ **APPLIED** | 否（阻塞点在 Core 内部，见下） | `3753867` |
| PATCH-0010 | `shared/.../orchestration/CarPlayController.kt` + `CarPlayRuntimeConfig.kt` **同一 Wi-Fi（SAME_LAN）模式** | ✅ **APPLIED**（含 2026-10-07 SSID/密码 修正） | 否（Core 内 `when` 穷举，见下） | `3753867` |
| PATCH-0011 | `shared/.../airplay/AirPlayInfoPlist.kt` 等 **仪表副屏声明**（吉利 HDMI2 移植） | ✅ **APPLIED**（2026-10-07） | 否（`cluster` 字段不经任何 Adapter，见下） | `17c9243` |
| PATCH-0012 ~ 0015 | `shared/.../media/*` **音频通道/缓冲/降级**（PATCH-0013/0014/0015 被代码引用） | ⚠️ 已应用但**台账条目缺失**（见下"缺口"段） | 否 | 早于 `3753867` |
| PATCH-0016 | `shared/.../iap2/message/Iap2NavigationMessages.kt`（新增）+ `orchestration/CarPlayController.kt` **iAP2 导航解码**（吉利 HUD 移植前置） | ✅ **APPLIED**（2026-10-08） | 否（`onIap2Incoming` 在 Core 内部，无夹层，见下） | `17c9243` |
| PATCH-0032 | `car/app/build.gradle.kts` **版本号末位只 +1、不再满 10 进位** | ✅ **APPLIED**（2026-10-08, `bc7cfa2`） | 否（构建脚本，非上游文件） | — |
| PATCH-0042 | `car/app/build.gradle.kts` **`applicationId` 改为 `io.dushu.car`**（占用帆书包名 ⇒ 点「帆书」媒体卡片即打开本应用） | ✅ **APPLIED**（2026-10-08） | 否（构建脚本，非上游文件） | — |
| PATCH-0059 | `shared/.../orchestration/CarPlayController.kt` + `CarPlayRuntimeConfig.kt` + `LegacyWifiP2pGroupManager.kt` **独立 Wi-Fi Direct 热点模式及 Android 9 legacy API** | ✅ APPLIED（2026-10-09） | 否（独立 runtime enum 分派） | `17c9243` |
| PATCH-0064 | `shared/.../network/LegacyWifiP2pGroupManager.kt` + `LegacyWifiP2pRecovery.kt`（新增）+ `orchestration/CarPlayController.kt` **Wi-Fi Direct 自己给车机 Wi-Fi 上电 + `createGroup` 有界重试 + 收尾归还** | ✅ APPLIED（2026-10-10） | 否（只动 PATCH-0059 新增的独立 manager 与它的超时常量） | `17c9243` |
| PATCH-0065 | `car/ui/…`（`SettingsScreen` / `CarDestination` / `CarUiContract`）+ `car/app/…/CarAppController.kt` + `car/api/…/CarPlayClusterProjection.kt` **设置页补回「仪表导航」分区（投屏开关 / 选屏 / 核心安全区）；副屏「未选一律不投」（删掉"唯一候选自动用"兜底）** | ✅ APPLIED（2026-10-10） | 否（`car/*` 自有模块） | `dev` |
| PATCH-0066 | 根 `build.gradle.kts` + `car/app/build.gradle.kts` **把 `NewApi` 提到 fatal（release 出包门禁）**；`car/platform-geely/…/GeelyClusterPresentation.kt` **仪表窗口用了 API 34 监听器 ⇒ Android 9 上 `NoClassDefFoundError`、窗口建不起来**（真车日志实证）；连带修 `platform-android/…/CarWindowInsetsHolder.kt`、`core-bridge/…/hotspot/LocalSoftApReader.kt`、`core-bridge/…/hotspot/CarSameLanReader.kt` 三处同类隐患 | ✅ APPLIED（2026-10-10） | 否（构建脚本 + `car/*`；**未改上游**） | `dev` |
| PATCH-0067 | `shared/…/network/LegacyWifiP2pGroupManager.kt` **Wi-Fi Direct 失败原因分档 + 建组前 400ms 落定等待 + 尝试 3→4**；`core-bridge/…/CarErrorText.kt` 两条新中文文案；`core-bridge/…/developer/CarDiagnosticPackager.kt` 新增 `system/wifi_direct.txt`（含 root 段） | ✅ APPLIED（2026-10-10，**未实机验证**） | 否（只动 PATCH-0059/0064 新增的独立 manager + 诊断/文案） | `dev` |
| PATCH-0071 | `car/api/…/hotspot/`（新增 `CarRootP2p{Contract,Output,Commands}.kt`）+ `car/core-bridge/…/hotspot/`（新增 `CarRootP2p{Tool,Sta,Manager}.kt`）+ `CarHotspotResolver` / `CarPlaySessionBridge` / `CarHotspotModeInit` / `CarAppServices` / 诊断包 **root 直驱 supplicant 建 P2P 组（绕开框架）** | ✅ APPLIED（2026-10-11，**未实机验证**） | 否（**未动上游**：成功后走上游既有的 `MANUAL` 出口） | `dev` |

> ⚠️ **汇总表只到 PATCH-0032 / PATCH-0042 两行是历史遗留**：PATCH-0033 ~ PATCH-0041
> 有正文章节、但未回填到本表。以**正文章节**为准。

> ⚠️ **PATCH-0016 的定位与风险**：它是**唯一一处凭协议推断写字段号的上游改动**。
> 上游注册了 `0x5201 / 0x5202 / 0x5204` 三个导航端点、也在 identification 里声明
> "会收到"，但**从未提供字段 schema、也没有任何解码器**——`onIap2Incoming` 把非
> NowPlaying 的帧**全部丢弃**。HUD 要出数据，这个缺口必须在 `:shared` 里补。
> **字段号依据**：iAP2 协议文档 + 反编译实证，**未经实车抓包确认**。
> 因此解码器**必须容忍字段缺失**（用 `optionalGroup` / `has` 门控），
> 缺字段时宁可降级为空快照，也**绝不可**抛异常打断 iAP2 通道。详见 PATCH-0016 一节。

> ⚠️ **PATCH-0011 的定位**：严格说它**不是一处对上游代码的改动**，而是
> "把上游**已有但从未被使用**的一条能力（第二块屏 / ALT 轨）真正接了起来"。
> 之所以仍然登记，是因为它落在 `:shared`（上游文件）上、且**同步上游时必须复核**
> ——上游若重构 `AirPlayConfig.cluster` / `STREAM_TYPE_ALT_SCREEN`，
> 仪表的声明与 ALT 轨绑定会一起失效。详见 PATCH-0011 一节。

> **当前同步基线**：上游 `master` = **`17c9243`**（`1.3.3`，2026-10-07 合并，
> **1 处冲突**——`AndroidMediaSink.createTrack()` 的缓冲策略，已按"保我们的"解决，
> 详见下面「2026-10-07」一节）。上游这几次**都没打 tag**，基线记的是 SHA；
> 最后一次带 tag 的同步是 `1.3.1` = `7b66322`，之后是 `1.3.2` / `1.3.3` 两个未打 tag 的跨度。
> 下次同步的起点就是 `17c9243`（`git merge-base dev 17c9243`）。

> ⚠️ **2026-09-28 补登记**：PATCH-0004 与 PATCH-0005 在落地时**未登记**，
> 违反了本文件开头的规则。现已补齐。同一次复核确认 PATCH-0002 / PATCH-0003
> 描述的两处改动（`hotspotFactory` / `vpnServiceClass` 构造参数）**确实仍未落地**——
> 因此那两条保持 `PROPOSED` 不变，不要与 PATCH-0005 混淆（两者落在同一区域，
> 但一个是"新增工厂参数"、一个是"透传凭据"，是不同的改动）。

### 2026-09-28：上游 1.2.4 → 1.3.1 同步复核

上游跨 `1.3.0` + `1.3.1` 两个 tag、13 个提交、42 个文件。**逐条复核本文件全部条目**：

| 条目 | 上游是否动了同文件 | 处理 |
| --- | --- | --- |
| PATCH-0001 / 0006（`settings.gradle.kts`） | 未动 | 快进后**整文件还原**（纯追加 + 改 1 行，无冲突） |
| PATCH-0004（`LocalOnlyHotspotManager.kt`） | 未动 | 快进后**整文件还原**（272 行改动原样保留） |
| PATCH-0005（`CarPlayController.kt`） | **动了 +148 行** | 手工重放：`startWirelessHotspot` 签名变为 `(generation, mfi)`，但 `LOCAL_ONLY_HOTSPOT` 那一行**未变**，可干净重放 |

> **上游 1.3.0 给 `WIFI_P2P` 加了 `mfiCertificateWifiP2pCredentials()`**
> （用配件证书的 SHA-1 派生固定 SSID 与密码）。它和 PATCH-0004 解决的是
> **同一类问题**，但**不是替代品**，两条理由：
>
> 1. 证书派生的 SSID 用户**改不了**，而本工程的产品要求是「热点名 = `DBPlay`」；
> 2. 它只覆盖 `WIFI_P2P`，而 `LOCAL_ONLY_HOTSPOT` 仍然每次随机生成。
>
> ⇒ PATCH-0004 / 0005 **继续保留**（已与用户确认）。二开侧的做法是把
> `CarHotspotMode.AUTO` 在 `CarHotspotResolver` 里解析成具体模式，
> `WIFI_P2P` 作为可选项照常可用。
>
> ⚠️ **2026-09-29 更正**：上面最后一句**已失效**。`WIFI_P2P` 已从
> `CarHotspotMode` **整个删除**（不是"可选但不用"）——Wi-Fi Direct 的 SSID/密码
> 由系统生成、用户改不了，与「热点名 = `DBPlay`」这个产品硬要求直接冲突，
> 留在选项里只会让人选到一个必然失败的方案。老安装里持久化的 `"WIFI_P2P"`
> 由 `enumOf` 的 `runCatching` 兜底静默回落到 `AUTO`，无需迁移。
> 上游的 `WirelessHotspotMode.WIFI_P2P` 本身照旧存在，只是不再映射。
> 详见 `ADB-HOTSPOT.md` §3.5。
>
> **代价提醒**：上游这次动了 `CarPlayController.kt`，说明那个文件的活跃度在上升；
> PATCH-0005 虽然只有 15 行，但它落在一个**正在被上游反复改**的函数里，
> 后续每次同步都要人工重放。这是当前最大的同步成本点。


### 2026-09-29：ADB 独立热点落地复核（结论：**不需要新 PATCH**）

`CarHotspotMode.ADB_HOSTAPD`（自建热点，完整实现说明见仓库根 `ADB-HOTSPOT.md`）
**全程零上游改动**。它借道上游已有的 `WirelessHotspotMode.MANUAL`：

- 上游 `ManualHotspotManager` 的语义是「attach 到本机**已经在跑**的那张热点」——
  只读 `SoftApConfiguration` 找接口，**从不**调用任何 `startSoftAp`。
  这正是我们需要的：热点由二开层用随包 adb + 系统 hostapd 先起好，再交给它接管；
- 映射点在二开层一处：`car/core-bridge/.../CarPlayConfigBuilder.kt` 的
  `CarHotspotMode.toUpstream()` 里 `ADB_HOSTAPD -> WirelessHotspotMode.MANUAL`；
- 上游对三方应用反射 `getSoftApConfiguration()` 会抛 `SecurityException`，
  其内部 `catch (_: Throwable) { null }` 让后续一致性校验整段跳过 ⇒ 不会因为我们
  把 `band`/`channel` 填成 5 GHz / 36 而在 `validateApConfiguration` 上失败。

⇒ **PATCH-0002（可注入 `hotspotFactory`）依然不需要。** 它想解决的"替换整个热点后端"
在本方案里是**绕开**而不是接入；代价是自建热点的生命周期由二开层自己管
（起在 `CarPlaySessionBridge.ensureAdbHotspot`，收在 `stop()` / `close()`）。

同批二开层改动（**全部在 `car/` 下，不属于上游文件，因此不需要登记**）：
`car/app/build.gradle.kts` 打开 `packaging.jniLibs.useLegacyPackaging`（两个随包二进制
必须被解压成可执行文件）、`CarPlaySessionBridge.start()` 把 `startInternal()` 投到
worker 线程（否则拉起热点会把主线程卡成 ANR）、`CarAppServices` / `CarUiContract` /
`SettingsScreen` / `DeveloperScreen` 的接线。

> ⚠️ 复核点：`ADB_HOSTAPD` 会把 `band`/`channel` 填成 5 GHz / 36。若将来上游给
> `MANUAL` 加上"严格校验频段/信道"的逻辑，这条通路就会失效——届时看
> `ManualHotspotManager` 与 `validateApConfiguration`。


### 2026-09-30：上游 master `3753867` 同步复核（结论：**零冲突，补丁全部完好**）

⚠️ 上游这次**没有打 tag**，直接把 `master` 往前推了：`1.3.1`（`7b66322`）→ `3753867`，
5 个提交、9 个文件、+546 / −7。**基线前移记录的是 SHA 而不是 tag**，
下次同步拿这个 SHA 当起点（`git merge-base dev 3753867` = `7b66322`）。

| 提交 | 内容 |
| --- | --- |
| `caf0e7f` + `0e1b871` | `WifiP2pGroupManager`：P2P 组的 `securityType` 未上报时不再抛 `IOException`，改为记日志并假设 WPA2-PSK |
| `cda5386` + `b0c498c` | `AndroidMediaSink.VideoDecoder`：收到没有 codec config 的视频帧时不再空转，改为置 `needsKeyFrame` 并请求关键帧（**HEVC 花屏/黑屏的修复**） |
| `3753867` | 新增 `MfiTarget.LOCAL_FILES` + `LocalMfiAuthenticationClient`（本地文件 MFi 身份，234 行，**上游一行测试都没配**）+ `AirPlayPersistence` / `CarPlayHostActivity` 的对应设置项 |

**合并试跑**（临时分支 + `git merge --no-commit --no-ff` 先看冲突面，随后 abort，零风险）：
**零冲突**。两个被我们改过的上游文件都自动合并成功——

- `AndroidMediaSink.kt`：PATCH-0008 落在构造函数 / `audioRenderer()` / `AudioRenderer` / `writePcm`，
  上游改的是 `VideoDecoder` ⇒ **区域不重叠**；
- `CarPlayController.kt`：PATCH-0005 落在 `startWirelessHotspot()` 内，
  上游改的是 `when (config.mfiTarget)` 分支与新增的 `openLocalMfi()` ⇒ **区域不重叠**。

合并后 `:car:app:compileDebugKotlin` + `:shared:testDebugUnitTest` +
`:car:core-bridge:testDebugUnitTest` **全部通过**。

**逐条复核**（`git diff --numstat 3753867 HEAD` 的结果就是本文件的条目清单，一条不多）：

| 条目 | 上游是否动了同文件 | 处理 |
| --- | --- | --- |
| PATCH-0001 / 0006（`settings.gradle.kts`） | 未动 | 自动保留（我们侧 `+15 / −1`） |
| PATCH-0004（`LocalOnlyHotspotManager.kt`） | 未动 | 自动保留（我们侧 `+272 / −19`） |
| PATCH-0005（`CarPlayController.kt`） | **动了**（上游 `+30 / −1`） | 区域不重叠，自动合并；锚点 `ssid = config.manualHotspotSsid` 仍在（第 1533 行）。我们侧 `+15 / −1` |
| PATCH-0007（`MicrophoneSupport.kt`） | 未动 | 自动保留（我们侧 `+32 / −3`，另加 `AudioModeLeaseManagerTest.kt` 175 行测试） |
| PATCH-0008（`AndroidMediaSink.kt` + 新增 `AudioGain.kt`） | **动了**（上游 `+15 / −1`） | 区域不重叠，自动合并；4 处钩子完好（`audioGainPercent` 在 49 / 151 / 524 行，`AudioGain.percentOf` 在 867 行）。我们侧 `+37 / −1` + `AudioGain.kt` 116 行 + `AudioGainTest.kt` 192 行 |

> **「零冲突」不等于「补丁还在」**：自动合并成功只说明文本不打架，**不代表补丁语义还成立**。
> 上表每一行都是合并**之后**在新工作区里 grep 锚点复核出来的，不是看合并输出推断的。
> 另外注意：**改动区域不重叠 ≠ 改了同一个文件**——只看"上游动了哪个文件"会把
> `AndroidMediaSink.kt` / `CarPlayController.kt` 误判成高风险，实际要看行区间。
> `--numstat` 给的是**增/删两列**；`--stat` 那根柱状图的数字是两者之和，别当成行数。

**上游给 `MfiTarget` 加了 `LOCAL_FILES` ⇒ 对二开层零影响**：二开层用的是自有枚举
`CarMfiTarget`，与上游的映射只集中在 `CarPlayConfigBuilder.toUpstream()` 一处（第 220 行起）；
`car/` 下**没有任何**对上游 `MfiTarget` 的穷尽式 `when`（已 grep 确认）。
这正是「自有枚举 + 一处映射」这套做法的收益——上游加枚举值不会连锁到我们的界面。

**`LOCAL_FILES` 与我们的 `LOCAL_OFFLINE` 能力重叠（迁移另开一轮，本轮不动）**：
两者同为 `.pk8` + `.p7b`、同用 `NONEwithECDSA` + AuthV3 + DER→raw 签名。
区别是上游把「本地文件」直接做成**进程内调用**，不再借道 HTTP 环回。
⇒ `CarMfiTarget.LOCAL_OFFLINE -> MfiTarget.REMOTE` 这一行**将来可以改成 `LOCAL_FILES`**，
届时可删的至少是 `CarMfiLoopbackServer`（362 行）、`CarMfiIdentityManager`（83 行）、
`CarMfiLoopbackServerTest`（275 行），以及被 `LocalMfiAuthenticationClient` 覆盖掉的
DER→raw 编码部分（`CarMfiDerSignature` 130 行）。
**唯一不可替代的是「随包内置身份回落」**（`assets/offline-mfi/` 里带一份出厂身份）——
上游要求 `content://`，可用已有的 FileProvider（`${applicationId}.fileprovider`）
把内置身份解到 `filesDir` 再喂给它。
⚠️ MFi 子系统当前总规模 **1865 行**（主代码 1142 + 测试 723），
**具体能删多少要等迁移落地后按实际 diff 统计**，上面列的是已知可替代的几块。
本轮**只做合并，`LOCAL_OFFLINE` 原样保留**（它照常工作），迁移是独立的一轮，
可以单独验证、单独回退。

> **补记一处历史遗留**：`asset/领克-iOS-512x512.png` 是二开基线提交（`3c2c798`）新增的，
> 落在本文件开头「必须先登记」的 `asset/` 目录里，但此前没有记录。它是**纯新增的独名文件**，
> 与上游不存在冲突面（上游 `asset/` 下两个文件从未被改过），此处补记，**不单开 PATCH 编号**。


### 2026-10-07：上游 master `17c9243` 同步复核（结论：**1 处冲突，已按"保我们的"解决**）

上游 `master`：`3753867` → **`17c9243`**，跨 `1.3.2` / `1.3.3` 两个 tag、
**7 个提交、10 个文件、+983 / −34**。

| 提交 | 内容 |
| --- | --- |
| `de9647f` | Type-130 握手改为可选要求 |
| `0c583ef` + `9b3f53b` | 新增 FPS/延迟追踪图（`MediaMetricsOverlayView`）+ 音频调试追踪开关 |
| `7eb08ff` + `8eaec37` + `17c9243` | **音频缓冲控制**：新增 `MainMediaAudioBuffer` / `MediaMetricsMonitor`，`AndroidMediaSink` 加缓冲时长参数与音频指标采集 |
| `b531b07` | 版本号 1.3.2 |

#### 冲突面

先跑 `git merge-tree --write-tree` 预演（零风险），再真合并：

- 我们改过的上游文件 ∩ 上游本次改的文件 = **3 个**：
  `common/…/CarPlayHostActivity.kt`、`shared/…/media/AndroidMediaSink.kt`、
  `shared/…/orchestration/CarPlayController.kt`；
- **实际冲突只有 1 个文件**：`AndroidMediaSink.kt`（6 处），另两个**自动合并成功**。

#### 逐条复核（合并**之后**在工作区 grep 锚点，不是看合并输出推断）

| 条目 | 上游是否动了同文件 | 处理 |
| --- | --- | --- |
| PATCH-0001 / 0006（`settings.gradle.kts`） | 未动 | 自动保留 |
| PATCH-0004（`LocalOnlyHotspotManager.kt`） | 未动 | 自动保留 |
| PATCH-0005（`CarPlayController.kt`） | 动了 | 区域不重叠；`LOCAL_ONLY_HOTSPOT` 传凭据分支仍在（第 1645 / 1674 行） |
| PATCH-0007（`MicrophoneSupport.kt`） | 未动 | 自动保留 |
| PATCH-0008（`AndroidMediaSink.kt` 下行增益钩子） | 动了 | ⚠️ 见下"历史遗留"——增益钩子已被 PATCH-0013 **有意取代** |
| PATCH-0009（`CarPlayController.kt` 有界超时 watchdog） | 动了（`armWirelessHandoffWatchdog`） | ✅ **本文件已预告过这个风险，本次逐段核对无误**：上游把 `armWirelessHandoffWatchdog` 超时后的 `closeWirelessStack() + fail()` 删掉改成"继续走蓝牙 iAP2"（第 1322 行）；我们的 `armWirelessAttachWatchdog`（第 1233~1294 行）在它**正上方**，自己的超时/重试语义（`WIRELESS_ATTACH_TIMEOUT_MILLIS = 40_000`、`WIRELESS_ATTACH_MAX_ATTEMPTS = 2`、`wireless attach timed out`）**完好未被污染** |
| PATCH-0010（`CarPlayController.kt` SAME_LAN） | 动了 | 区域不重叠；`sameLanHotspotInfo()`（第 1751 行）与两条 `SAME_LAN` 分支仍在 |
| PATCH-0010 补登记（`CarPlayHostActivity.kt` `hotspotModeLabel`） | 动了（上游 +168） | 自动合并；我们的 `SAME_LAN -> "局域网连接（不建热点）"`（第 2966 行）仍在 |

#### 🔴 本轮唯一的真冲突：`AndroidMediaSink.createTrack()` 的缓冲策略

上游 `7eb08ff` 新增了 `MainMediaAudioBuffer`（档位 100~1000 / 步进 100 / 默认 100，
只作用于 payloadType 100/102），**与我们 PATCH-0014 的 `MediaAudioBuffer` 是同一个功能**
（我们的档位 300/500/1000 / 默认 300，容量另留 200ms 余量，外加一条 `startBytesFor`
起播阈值保护）。两边各自重写了 `createTrack()` 的同一段，git 无法自动合并。

**决定：保留我们自己这一套**（车主 2026-10-07 拍板）。理由与代价：

- 我们的档位与车机上已验证的行为一致，不动它风险最小；
- 代价一：上游的 `mainMediaAudioBufferDurationMs` 参数在本 fork 里**收下但不参与建轨**
  （保留是为了让上游 `common/…/CarPlayHostActivity.createMediaSink` 的**具名调用**继续编译）；
- 代价二：`AudioRenderer` 里 `MIN_TRACK_BUFFER_BYTES` / `MIN_START_BUFFER_BYTES`
  两个常量在本 fork 变成**未使用**（`MediaAudioBuffer` 有自己的副本），
  但**刻意保留**——它们与上游逐字相同，删了只会让下次同步多一处冲突；
- 代价三：**每次同步都会在同一段再冲突一次**。若将来想彻底摆脱，
  就把 PATCH-0014 撤掉改用上游的 `MainMediaAudioBuffer`（届时需一并把
  `car/ui` 的 `MediaAudioBufferRow` 与 `CarSessionSettings.AUDIO_BUFFER_PRESETS`
  改成上游的 100~1000 档位）。

**⚠️ 冲突解决时最容易被顺手删掉的一行**：上游的
`trackBytesPerFrame = trackChannelCount * BYTES_PER_PCM_16_SAMPLE`。
它看着属于上游那套缓冲逻辑，其实**不属于**——上游的 `writePcm` 用
`totalBytesWritten / trackBytesPerFrame` 算已写帧数喂给 `recordAudioBuffer`，
**删了会除零**。合并时已保留，并顺手让我们的容量换算也改用它（不再各写一个 4 / 2）。

#### 验证

- `:shared:testDebugUnitTest` 通过；`:shared` / `:mobile` / `:automotive` /
  `:car:api` / `:car:core-bridge` / `:car:platform-android` / `:car:ui` / `:car:app`
  的 `compileDebugKotlin` 全部通过；
- ⚠️ **本轮没有出包**（按规矩，出包要单独许可）。用 `assembleDebug` 验证会撞上
  `car/app/build.gradle.kts` 的 `allowRelease` 闸门 —— **那是设计如此，不是构建失败**，
  验证请用 `compileDebugKotlin` / `testDebugUnitTest`。

#### ⚠️ 顺带发现：台账缺 PATCH-0011 ~ 0015 的条目（**待补，本轮未写**）

`git diff --numstat 3753867 dev -- shared/` 显示我们改动的上游文件远多于本文件的条目数。
代码里实际引用到 **PATCH-0013 / 0014 / 0015**（`AndroidMediaSink.kt`、`MediaAudioBuffer.kt`、
`AudioChannelMappingTest.kt`），但本文件**没有它们的条目**。同一批未登记的上游侧改动还包括
新增文件 `AudioChannelOverride.kt` / `LegacyAudioFallback.kt` / `SoftwareOpusDecoder.kt` /
`AudioBufferProgress.kt`，以及被改的 `AudioChannelMapping.kt` / `ManualHotspotManager.kt`。

**这不是本轮同步引入的**（都早于 `3753867`），但**违反了本文件开头的登记规则**，
且直接削弱了本文件"控制上游合并冲突面"的作用。补登记需要为每条写清
文件 / 锚点 / 上游 commit / 是否可用 Adapter 替代，是一次独立的小轮次，
**留待单独确认后再做**，本轮不猜编号、不臆造内容。


状态含义：
- ✅ **APPLIED** —— 已应用在仓库中，同步上游后必须复核。
- 📋 **PROPOSED** —— 已定位并写好改动方案，但**尚未应用**，等你确认。
- ❌ **REVERTED** —— 曾经需要，上游已提供接缝或已无需求，改回原样。

---

## PATCH-0001

| 项 | 内容 |
| --- | --- |
| **状态** | ✅ APPLIED |
| **文件** | `settings.gradle.kts` |
| **上游 commit** | `3ac55e34` |
| **修改原因** | Gradle 只认 `settings.gradle.kts` 里的 `include()` 声明，无法从外部注入新的子模块。要让 `car/*` 成为可编译的 Gradle 模块，必须在这里 include |
| **是否可用 Adapter 替代** | 否。这是 Gradle 的硬性机制，无替代方案 |
| **后续同步注意** | **本文件是唯一预期冲突点**。冲突时保留上游内容 + 保留我们的末尾追加块即可 |

### 改动内容

在文件**末尾追加**（上游内容一字未动）：

```kotlin
// ---------------------------------------------------------------------------
// Local extension layer (NOT part of upstream xcertplay).
// Keep this block contiguous and append-only so upstream merges stay trivial.
// See ARCHITECTURE.md, PATCHES.md (PATCH-0001).
// ---------------------------------------------------------------------------
include(":car:api")
include(":car:platform-android")
include(":car:platform-flyme")
include(":car:platform-ecarx")
include(":car:core-bridge")
include(":car:ui")
include(":car:steering")
include(":car:app")
```

### 为什么这个 patch 是安全的

- **纯追加**：没有删除、没有修改任何上游行，语义上等价于"上游文件 + 尾部新块"。
- **锚点稳定**：位于文件最末，上游通常在文件头部 `pluginManagement` /
  `dependencyResolutionManagement` 区域改动，碰撞概率低。
- **冲突易解**：即使冲突，`git` 显示的也是"两边都在末尾加内容"，保留两侧即可。

---

## PATCH-0002

| 项 | 内容 |
| --- | --- |
| **状态** | 📋 PROPOSED（**未应用**） |
| **文件** | `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt` |
| **锚点** | `private fun startWirelessHotspot(generation: Int)`，当前第 1394–1414 行 |
| **上游 commit** | `3ac55e34` |
| **修改原因** | 无线热点管理器是 `when (hotspotMode)` **硬编码 `new` 出来的**，无法注入车机自己的热点实现。若某车机需要第 4 种热点形态（例如 Flyme/OSN 的专有热点 API），当前只能改 Core |
| **是否可用 Adapter 替代** | 否。`WirelessHotspotMode` 是封闭枚举，`when` 在 Core 内部，外部无法参与选择 |
| **后续同步注意** | 改动是"**新增可选构造参数 + 一处 null 回退**"，与上游逻辑不重叠。上游若同一区域改动，冲突范围可控 |

### 现状代码（上游原样）

```kotlin
// CarPlayController.kt:1402-1414
val manager: WirelessHotspotManager = when (hotspotMode) {
    WirelessHotspotMode.WIFI_P2P -> WifiP2pGroupManager(appContext)
    WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> LocalOnlyHotspotManager(appContext)
    WirelessHotspotMode.MANUAL -> ManualHotspotManager(
        context = appContext,
        ssid = config.manualHotspotSsid
            ?: throw IOException("Manual hotspot SSID is not configured"),
        passphrase = config.manualHotspotPassphrase.orEmpty(),
        band = config.manualHotspotBand,
        channel = config.manualHotspotChannel,
        security = config.manualHotspotSecurity,
    )
}
```

### 建议改动（行为等价，最小 hunk）

```diff
 class CarPlayController(
     context: Context,
     private val config: CarPlayRuntimeConfig,
     private val airPlayConfig: AirPlayConfig,
     private val identity: AirPlayIdentity,
     private val pairings: PairingStore,
     listener: AirPlaySessionListener,
     private val media: AirPlayMediaHandler,
     reportStatus: (CarPlayStatus) -> Unit,
     private val loadPairRecord: () -> LockdownPairRecord? = { null },
     private val savePairRecord: (LockdownPairRecord) -> Unit = {},
     private val clearPairRecord: () -> Unit = {},
     private val locationProvider: Iap2LocationProvider? = null,
+    /**
+     * Optional per-platform override for the wireless hotspot backend.
+     * Returning null keeps the built-in selection below unchanged.
+     */
+    private val hotspotFactory: ((WirelessHotspotMode) -> WirelessHotspotManager?)? = null,
 ) : Closeable {
```

```diff
-        val manager: WirelessHotspotManager = when (hotspotMode) {
-            WirelessHotspotMode.WIFI_P2P -> WifiP2pGroupManager(appContext)
-            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> LocalOnlyHotspotManager(appContext)
-            WirelessHotspotMode.MANUAL -> ManualHotspotManager(
-                context = appContext,
-                ssid = config.manualHotspotSsid
-                    ?: throw IOException("Manual hotspot SSID is not configured"),
-                passphrase = config.manualHotspotPassphrase.orEmpty(),
-                band = config.manualHotspotBand,
-                channel = config.manualHotspotChannel,
-                security = config.manualHotspotSecurity,
-            )
-        }
+        val manager: WirelessHotspotManager = hotspotFactory?.invoke(hotspotMode)
+            ?: when (hotspotMode) {
+                WirelessHotspotMode.WIFI_P2P -> WifiP2pGroupManager(appContext)
+                WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> LocalOnlyHotspotManager(appContext)
+                WirelessHotspotMode.MANUAL -> ManualHotspotManager(
+                    context = appContext,
+                    ssid = config.manualHotspotSsid
+                        ?: throw IOException("Manual hotspot SSID is not configured"),
+                    passphrase = config.manualHotspotPassphrase.orEmpty(),
+                    band = config.manualHotspotBand,
+                    channel = config.manualHotspotChannel,
+                    security = config.manualHotspotSecurity,
+                )
+            }
```

### 改动性质

- **行为等价**：新增参数默认 `null`，短路回退到原有 `when`，
  默认路径逐字节不变量（无新分支、无新异常、无时序变化）。
- **hunk 数量**：2 处（构造函数尾参数、工厂调用点）。
- **何时才需要应用**：当你要接入第一个**非标准**热点形态的车机时。
  当前阶段（Phase 1）无此需求，因此**不应用**。

---

## PATCH-0003

| 项 | 内容 |
| --- | --- |
| **状态** | 📋 PROPOSED（**未应用**） |
| **文件** | `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt` |
| **锚点** | `private fun bindVpn()`，当前第 1683–1696 行（关键行 1687） |
| **上游 commit** | `3ac55e34` |
| **修改原因** | Core 直接 `Intent(appContext, CarPlayVpnService::class.java)` 绑定**上游自己的** VpnService。若车机需要替换 VpnService 实现（例如需要额外的权限、要复用系统已有 VPN 通道、或要把网络桥接换成厂商方案），当前必须改 Core |
| **是否可用 Adapter 替代** | 否。`::class.java` 在 Core 内部写死 |
| **后续同步注意** | 同样是"新增可选构造参数 + 默认回退"，上游若改 `bindVpn` 逻辑需重新核对锚点 |

### 现状代码（上游原样）

```kotlin
// CarPlayController.kt:1683-1696
private fun bindVpn() {
    if (vpnBound) return
    vpnBound = true
    try {
        val intent = Intent(appContext, CarPlayVpnService::class.java)
        if (!appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
            vpnBound = false
            vpnLatch.countDown()
        }
    } catch (_: Throwable) {
        vpnBound = false
        vpnLatch.countDown()
    }
}
```

### 建议改动（行为等价，最小 hunk）

```diff
+    /**
+     * Optional override for the VpnService implementation used by the wired path.
+     * Defaults to the upstream CarPlayVpnService.
+     */
+    private val vpnServiceClass: Class<out Service> = CarPlayVpnService::class.java,
 ) : Closeable {
```

```diff
-            val intent = Intent(appContext, CarPlayVpnService::class.java)
+            val intent = Intent(appContext, vpnServiceClass)
```

### 改动性质

- 需要额外 `import android.app.Service`。
- **行为等价**：默认值就是原来的类。
- **风险**：`serviceConnection` 里 `binder as CarPlayVpnService.LocalBinder` 的强转
  仍然存在。若替换成自定义 Service，必须复用 `CarPlayVpnService.LocalBinder`，
  否则会在 `onServiceConnected` 抛 `ClassCastException`。
  因此**本 patch 只在"继承 `CarPlayVpnService`"的场景下安全**；
  要支持完全不同的实现，还需要一并把 binder 契约抽成接口（改动会明显变大，
  届时应重新评估是否值得）。
- **何时才需要应用**：当车机确实需要替换 VPN/网络桥接实现时。
  当前阶段无此需求，因此**不应用**。

---

## PATCH-0004

| 项 | 内容 |
| --- | --- |
| **状态** | ✅ APPLIED（2026-09-28 补登记） |
| **文件** | `shared/src/main/java/com/shilapi/xcertplay/network/LocalOnlyHotspotManager.kt`（现 941 行） |
| **锚点** | 构造函数（48）；`startWithFixedCredentials`（279）；`useSystemGeneratedCredentials`（330）；`newSoftApConfigurationBuilder`（340）；`invokeSetSsid`（347）；`invokeSetPassphrase`（368）；`findStartMethod`（383）；`selectHotspotInterface`（约 590–660）；`NetworkInterface.hotspotAddress`（689）；`companion object`（915，`MAX_SSID_BYTES=32` / `EXCLUDED_INTERFACE_PREFIXES`） |
| **上游 commit** | `1.2.4`（`8885cba`）基线之上的工作区改动，尚未提交 |
| **改动规模** | `272 insertions(+), 19 deletions(-)` |
| **是否可用 Adapter 替代** | **否。** `LocalOnlyHotspotManager` 是 `WirelessHotspotManager` 的 Core 内部实现，构造点在 `CarPlayController.startWirelessHotspot()` 的 `when` 里（见 PATCH-0005）。二开侧没有替换入口——这正是 PATCH-0002 想解决、但至今未落地的问题 |

### 修改原因（两件独立的事，落在同一个文件）

**(a) 固定 SSID / 密码。** 上游不传 `ssid` 时走 Android 原生行为：**每次启动都随机生成
SSID 与密码**。车机场景下这会让 iPhone 每次重连都去学一张陌生网络，无线重连不可靠。
现在传入凭据后，尝试用 `SoftApConfiguration` 重载把名字固定为 `DBPlay` / `12345678`。

**(b) 热点网卡识别。** 真机 vivo PD2271（Y78+，Android 14）上，热点网卡 `wlan2` 的
`isUp()` 抛 `SocketException`（`/sys/class/net/wlan2/flags` 被 SELinux 拒绝读取）、
硬件地址也读不到。旧逻辑因此把整张网卡排除在候选之外，只能退而选中 `rmnet_data0`，
把 **iPhone 根本不可达的 link-local IPv6** 通告出去——表现为「iPhone 能连热点、能拿 DHCP，
却始终不连 AirPlay 7000 端口」。现在改用「必然持有 `192.168.x` 私网 IPv4」这个硬特征定位。

### 为什么必须走反射（(a) 部分）

`WifiManager.startLocalOnlyHotspot(SoftApConfiguration, Executor, Callback)` 与
`SoftApConfiguration.Builder` 在 Android 11~15 上是 **@SystemApi（即 @hide）**，
Android 16 才转为公开 API，Android 17 又改名成 `startLocalOnlyHotspotWithConfiguration`
（`Builder.setSsid(String)` 同时换成 `setWifiSsid(WifiSsid)`）。本工程 `compileSdk = 37`，
运行时要覆盖 Android 11 起的设备，两套名字各自只存在于一部分 SDK 里，编译期符号必然对不上
其中一端，只能按可用性探测。

### 安全设计（保证默认路径行为等价）

- 凭据为空 → `startWithFixedCredentials` 立即 `return false` → 回落到上游原生调用。
- 反射任一步探测失败 → `useSystemGeneratedCredentials(...)` 打一条 W 日志后 `return false`，
  **不抛异常**：拿不到固定 SSID 只是体验退化，不该把整条无线 CarPlay 链路搞挂。
- 只有**配置本身不合法**（SSID 超 32 字节、加密热点密码不在 8..63）才抛异常——
  那种情况静默变随机会把用户搞懵。
- 刻意**只设 SSID / 密码 / 加密方式三项，不碰频段与信道**：LocalOnlyHotspot 上同时设
  band 与 channel 会被框架直接拒绝，且现有实现本来就把信道上报为 0（auto）。

### ⚠️ 与 §13「最小化」要求的偏离（需要知晓）

本条 **不满足** §13 第 3 条的「最小化」硬要求——它是 272 行的实质性改动，而不是
「新增可选构造参数」那种小 hunk，并且改了 `selectHotspotInterface` 的选择逻辑本身。
这是**有意的偏离**：真机上网卡识别失败是硬阻塞（iPhone 拿不到正确地址就永远不出画面），
靠"新增参数"表达不了。

代价要认清：**上游只要动这个文件，冲突几乎必然**，且需要人工判断。
因此同步上游后应**优先评估能否撤销**（§13 第 5 条）——若上游自己修好了网卡识别或提供了
固定凭据入口，本 patch 应当整体回退。

### 后续同步注意

- 冲突点集中在 `selectHotspotInterface` 与 `NetworkInterface.hotspotAddress`；
  反射那一段（279–410）是**纯新增**，冲突概率低。
- `EXCLUDED_INTERFACE_PREFIXES` 是新增常量，上游若新增同名常量会编译冲突。
- 诊断日志（`interfaceProbeLogged` / `interfaceChoiceLogged`）各只打一次，不影响时序。

---

## PATCH-0005

| 项 | 内容 |
| --- | --- |
| **状态** | ✅ APPLIED（2026-09-28 补登记） |
| **文件** | `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt`（现 1867 行） |
| **锚点** | `private fun startWirelessHotspot(generation: Int)`（1376），`when` 的 `LOCAL_ONLY_HOTSPOT` 分支（1386） |
| **上游 commit** | `1.2.4`（`8885cba`）基线之上的工作区改动，尚未提交 |
| **改动规模** | `9 insertions(+), 1 deletion(-)` |
| **是否可用 Adapter 替代** | 否。与 PATCH-0002 是同一个硬编码点 |
| **后续同步注意** | 与 PATCH-0002 的落点**完全重叠**（同一个 `when`）。若将来真要应用 PATCH-0002，必须基于本 patch 之后的状态重写那段 diff |

### 改动内容

把配置里的热点凭据透传给 `LocalOnlyHotspotManager`（配合 PATCH-0004）：

```diff
         val manager: WirelessHotspotManager = when (hotspotMode) {
             WirelessHotspotMode.WIFI_P2P -> WifiP2pGroupManager(appContext)
-            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> LocalOnlyHotspotManager(appContext)
+            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> LocalOnlyHotspotManager(
+                context = appContext,
+                // 固定 SSID/密码：不传的话 Android 每次启动都会随机生成，
+                // iPhone 侧就永远在重学一张陌生网络，无线重连会变得不可靠。
+                // 凭据为空时 manager 会自动回落到系统随机行为。
+                ssid = config.manualHotspotSsid,
+                passphrase = config.manualHotspotPassphrase,
+                security = config.manualHotspotSecurity,
+            )
             WirelessHotspotMode.MANUAL -> ManualHotspotManager(
```

### 改动性质

- **行为等价**：`config.manualHotspotSsid` 为空时 `startWithFixedCredentials` 立即返回
  `false`，回落到上游原生 `startLocalOnlyHotspot(callback, handler)`。
- **hunk 数量**：1 处。
- 与 PATCH-0002 的**区别**：PATCH-0002 是「新增一个可注入的 `hotspotFactory` 参数，
  让车机替换整个热点后端」；本条只是「把已有配置项透传给上游实现」。前者通用但未落地，
  后者窄但已落地。**两者不是同一个改动，不要合并编号。**

---

## 已排除的改动（曾考虑但决定不做）

记录在这里，避免以后有人重复提议。

| 曾考虑 | 结论 | 理由 |
| --- | --- | --- |
| 把 `media/AudioChannelMapping.kt` 的两个档位抽成公开策略接口 | ❌ 不做 | 上游已用 `advancedAudioChannelMapping: Boolean` 表达了同一个二元选择；二开侧 `CarAudioAdapter.supportsAdvancedChannelMapping()` 已能表达，再加一层是过度抽象 |
| 把 `CarPlayController` 拆成多个小类 | ❌ 不做 | 属于"为了模块化而大规模重构核心逻辑"，直接违反 Phase 1 约束；且会让每次上游同步都变成人工合并 |
| 修改 `mobile/` `automotive/` 让它们依赖 `car/*` | ❌ 不做 | 上游应用壳不是二开产品；二开产品 App 会另建模块。改上游壳会引入无收益的耦合 |
| 在 `shared/` 内新增 `CarAdapter` 接口 | ❌ 不做 | 会让 Core 反向认识二开抽象，且使 `:shared` 无法独立于二开演进 |
| 改根 `.gitignore` 以解除上游对 `tools/` 的排除 | ❌ 不做 | 上游 1.2.4 刚动过 `.gitignore`，是活跃冲突点；为了"让自己的脚本入库"而新增一处上游偏离不划算。**改用改名方案**，见下 |

### `tools/` → `scripts/`：解除"上游 ignore 撞名"的正确做法（2026-09-28）

**问题**：上游根 `.gitignore` 第 21 行有 `tools/`（归类为「Local agent working notes」），
而本 fork 把 `tools/` 当正式脚本目录用。三个脚本
（`sync-upstream.ps1` / `footprint.ps1` / `install-mfi-identity.ps1`）
因此**不进版本控制**，`git status` 里看不到，换机器 clone 一份就没了。

**⚠️ 两个反直觉的 git 事实**（这才是关键）：

1. **嵌套 `.gitignore` 救不了。** 项目约定"新增 ignore 规则放 `car/.gitignore`"在这里无效：
   > It is not possible to re-include a file if a parent directory of that file is excluded.

   `tools/` 是**目录级**排除 → git 根本不会进入该目录 → `tools/.gitignore` 永远不会被读取。
   只能在**父级**解除，别无他法。
2. **`tools/` 这个模式匹配任意层级**（不带前导斜杠）。实测
   `git check-ignore -v car/tools/x.ps1` → 命中，所以搬到 `car/tools/` 也一样被忽略。

**采用方案**：把目录**改名为 `scripts/`**（`mv tools scripts`）。

| 为什么改名优于改 `.gitignore` | 说明 |
| --- | --- |
| **零上游足迹** | 不动任何上游文件，`git diff --name-only 1.2.4` 仍是 3 个文件，不新增冲突点 |
| **永久解决** | 不依赖"记得 `git add -f`"；`.gitignore` 里不再有让人困惑的"忽略了却已跟踪"状态 |
| **改名成本极低** | 目录名只出现在 `ARCHITECTURE.md`（已同步更新），无代码引用 |

**被否决的方案**：

| 方案 | 否决理由 |
| --- | --- |
| 改根 `.gitignore` 解除 `tools/` 排除 | 新增一处上游偏离，且 `.gitignore` 是上游活跃文件 |
| `git add -f tools/*.ps1` 强制入库 | 依赖人工记得 force-add；一次 `git reset` 就退回未跟踪 |
| 搬到 `car/tools/` | 无效——`tools/` 模式匹配任意层级，一样被忽略 |

---

## PATCH-0006

| 项 | 内容 |
| --- | --- |
| **状态** | ✅ APPLIED |
| **文件** | `settings.gradle.kts`（与 PATCH-0001 同文件） |
| **上游 commit** | `1.2.4` `8885cba` |
| **修改原因** | 产品更名为 DBPlay，Gradle 根项目名要跟着改，否则 IDE 项目名、构建日志、默认产物名里仍是 `xcertplay` |
| **是否可用 Adapter 替代** | 否。`rootProject.name` 只能写在 `settings.gradle.kts` |
| **后续同步注意** | 这是本仓库**第一处修改上游已有行**（PATCH-0001 是纯追加）。上游几乎不可能改这一行，冲突概率接近 0；万一冲突，保留 `DBPlay` 即可 |

### 改动内容

```diff
-rootProject.name = "xcertplay"
+rootProject.name = "DBPlay"
```

### 为什么这次破例修改上游已有行

PATCH-0001 的安全论证建立在"纯追加"上。本次改动破坏了这一点，因此需要说明：

- **收益明确**：用户要求"项目内的应用名称…保持一致"。`app_name`（`strings.xml`）、
  出厂设备标识（`CarSessionSettings`）、APK 输出名、Gradle 项目名四处统一为 DBPlay，
  才算真的统一。
- **代价极小**：改动点与 PATCH-0001 的追加块**在同一个文件、相隔 3 行**。
  即使上游大改 `settings.gradle.kts`，两个 patch 会落在同一段冲突里，
  一次解决即可，不额外增加冲突点数量。
- **不改的地方**：`applicationId` 仍为 `com.dbtool.carplay`、`namespace` 不变——
  包名一旦改动会与已装版本冲突，且与本 patch 无关。

### 与之配套、**不需要**登记的二开层改动

以下改动都在 `car/`（上游不存在的路径），不构成上游足迹：

- `car/app/src/main/res/values/strings.xml`：`app_name` → `DBPlay`
- `car/app/src/main/res/values/themes.xml` + `car/app/src/main/AndroidManifest.xml`：
  `Theme.CarPlay` → `Theme.DBPlay`
- `car/app/build.gradle.kts`：`versionName = "0.0.1"` / `versionCode = 1`，
  并在 `androidComponents.onVariants` 里把输出名改为
  `DBPlay-<版本号>-<yyyyMMddHHmm>.apk`
- `car/api/.../CarSessionSettings.kt`：出厂 `deviceName` / `manufacturer` / `model` → `DBPlay`
- `car/app/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml` +
  `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_{background,foreground}_dbplay.png`：
  启动图标换成 `asset/xcertplay.png`。

  源图是一张**已经画好圆角底板 + logo 的整图**，不能整张塞进某一层——
  自适应图标是 108dp 画布，系统遮罩只保证中间 **72dp** 可见（中心 **66dp** 圆是安全区），
  而源图里 logo 占画布 68.6%×77.4%，直接当 `background` 用会被上下切掉。
  因此拆成两层：

  - `background` = 从源图**重建**的底板：抠掉 logo 后用三次多项式拟合渐变，向四周铺满（不带圆角，遮罩交给系统）；
  - `foreground` = 从源图**抠出**的 logo（按亮度阈值 162 分离，底板 80~155 / logo ≥168），
    等比缩到画布 **52%** 后居中——与上游图标实测的 52.1% 同比例，落在安全区内。

  这是**零上游足迹**的覆盖手法：Android 资源合并优先级 app > library，`shared/` 里那个
  `@mipmap/ic_launcher` 会被本模块同名资源顶掉；minSdk 28 ≥ 26，`anydpi-v26` 必定命中。
  换图 = 替换 `asset/xcertplay.png` 后重跑 `_scratch/make-icon2.py`。

---

## PATCH-0007

| 项 | 内容 |
| --- | --- |
| **状态** | ✅ APPLIED |
| **文件** | `shared/src/main/java/com/shilapi/xcertplay/media/MicrophoneSupport.kt` |
| **锚点** | `AudioModeLeaseManager.acquire()` 里的 `check(readMode() == communicationMode) { ... }` |
| **上游 commit** | `1.3.1` `7b66322` |
| **修改原因** | 车机把音频 mode 钉死时，这条读回校验必然失败并抛 `IllegalStateException`，导致 CarPlay 上行麦克风**完全起不来**（Siri / 语音输入不可用） |
| **是否可用 Adapter 替代** | 否，见下节论证 |
| **后续同步注意** | 这是本仓库**第二处修改上游已有行**（第一处是 PATCH-0006）。改动只在 `acquire()` 内 4 行，`release()` 与其余逻辑一字未动 |

### 现场证据（2026-09-29 实车诊断包）

`1DBPlay_Diagnostic/logcat.txt:3150`（2 号包同样，每次语音请求都会重复一遍）：

```
09-29 14:12:55.476 airplay SETUP stream type=100 payload={type=100, input=true,
                 audioType=speechRecognition, dataPort=55531}
09-29 14:12:55.494 E xcertplay-usb: microphone start failed
09-29 14:12:55.494 E xcertplay-usb: java.lang.IllegalStateException:
                 Android communication audio mode was not enabled
    at AudioModeLeaseManager.acquire(MicrophoneSupport.kt:88)
    at MicrophoneAudioMode.acquire(MicrophoneSupport.kt:123)
    at MicrophoneUplink.startCapture(MicrophoneUplink.kt:51)
    at MicrophoneUplink.start(MicrophoneUplink.kt:41)
    at AndroidMediaSink.onMicrophoneStarted(AndroidMediaSink.kt:105)
    at com.dbtool.carplay.bridge.UpstreamMediaSinkBridge.onMicrophoneStarted
```

即：iPhone 一请求 `speechRecognition`（Siri），上行就挂。车机是 Android 9 / QUALCOMM CS11 / Flyme Auto。

### 改动内容

```diff
             if (changedMode) writeMode(communicationMode)
-            check(readMode() == communicationMode) {
-                "Android communication audio mode was not enabled"
-            }
+            // DBPlay PATCH-0007 —— 上游原文是 `check(readMode() == communicationMode)`，
+            // 读回失败直接抛 IllegalStateException，整个麦克风上行就没了。
+            // 车机的音频策略把 mode 钉死：setMode 既不报错也不生效。
+            // 通信模式只是"更好"（通话路由 + AEC），AudioRecord 本身并不要求它。
+            val activeMode = readMode()
+            if (activeMode != communicationMode) onCommunicationModeUnavailable(activeMode)
```

配套（同一 patch 的一部分）：

- `AudioModeLeaseManager` 新增构造参数 `onCommunicationModeUnavailable: (Int) -> Unit = {}`；
- `MicrophoneAudioMode.acquire` 传入一个打 `Log.w` 的实现
  （标签沿用 `xcertplay-usb`，与 `MicrophoneUplink` 一致，现场两行日志挨着看）。

**为什么做成回调而不是在 `acquire()` 里直接 `Log.w`**：`AudioModeLeaseManager` 刻意不依赖
任何 Android 运行时，单元测试才能用假 holder 把"设备接受 mode"和"车机钉死 mode"两侧都跑出来。
直接调 `Log` 会让 `:shared` 的单测撞上桩实现抛 `RuntimeException: Stub!`（已踩过一次）。

### 为什么没有 Adapter 出路（论证）

想在不碰 `shared/` 的前提下修，只有两条路，都不通：

1. **给上游塞一个"假的 AudioManager"**（`ContextWrapper` 覆写 `getSystemService(AUDIO_SERVICE)`）。
   ⇒ **不可行**：`android.media.AudioManager` 虽然 `public class`（非 final），
   但它的 `AudioManager(Context)` 构造器是 `@hide`，SDK 桩里根本没有公开构造器
   （`javap -classpath android.jar android.media.AudioManager` 只看到包内默认构造）。
   Java 要求 `super()` 必须是编译期可见的构造器 ⇒ **应用侧无法继承**，也就无法覆写 `getMode()`。
2. **二开层自己实现麦克风上行**（`MicrophoneUplink` 是 `internal`，拿不到）。
   ⇒ 要重写 RTP 打包 + Opus 编码 + `MicrophoneConfig` 解析，等于把上游协议代码抄一份，
   上游一改就烂。收益（省一个 4 行 patch）远小于代价。

补充：二开层确实握有 `AndroidMediaSink` 的 `Context`
（`CarPlayMediaSinkSlot` 里 `context = request.context`），
且该 Context **只被 `MicrophoneUplink` 使用**（`AndroidMediaSink.kt` 全文只有第 103 行用它），
所以第 1 条本来是最干净的落点 —— 卡在构造器不可见上。

### 验证

- 新增单测 `shared/src/test/java/com/shilapi/xcertplay/media/AudioModeLeaseManagerTest.kt`（**7 项**）：
  普通设备（写入 + 恢复 + 嵌套 + 二次 close 幂等 + 不误报）与车机（不抛异常 + 上报实际 mode +
  反复租约不崩）两侧都钉住。`AudioModeLeaseManager` 的 `readMode`/`writeMode` 本来就是 lambda 注入，
  假 holder 可以直接模拟"`setMode` 调用成功但模式不变"。
- ⚠️ **未在车机上验证**：车机不在手边，本地设备（vivo / Android 14）的 mode 是**能**设上的，
  走的是与上游原版完全相同的那条分支。车机侧要等下一次实车日志确认
  `communication audio mode unavailable (mode=...)` 是否出现、以及随后有没有声音。
- 兼容性：mode 能正常设置的设备上，本 patch **不改变任何行为**（只是把 `check` 换成等价的条件上报）。

### 可能的后续

若放开后 `AudioRecord` 仍然初始化失败，说明该车机的输入设备本身也依赖通信模式，
那时日志会变成 `microphone recorder failed to initialize` —— 属于另一条问题，另行处理。

---

## PATCH-0008

| 项 | 内容 |
| --- | --- |
| **状态** | ✅ APPLIED |
| **文件** | `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt`；**新增** `shared/src/main/java/com/shilapi/xcertplay/media/AudioGain.kt` |
| **锚点** | `AndroidMediaSink` 构造函数（30–39）；`audioRenderer(type, format)`（135–140）；`private class AudioRenderer` 构造函数（494–497）；`AudioRenderer.writePcm` 里 `applyFadeIn` 之后、`track.write` 之前（828–832） |
| **上游 commit** | `1.3.1` `7b66322` |
| **改动规模** | `AndroidMediaSink.kt` 约 +30 / -2 行；`AudioGain.kt` 新增 116 行 |
| **修改原因** | 实现**真正独立的分通道音量**（媒体 / 语音通话 / 语音助理 / 导航提示 / 兼容）。上游每条 CarPlay 音频流各有独立 `AudioTrack` + 独立 `AudioAttributes`，基础设施已经齐了，缺的只是**每条流的增益** |
| **是否可用 Adapter 替代** | 否，见下节论证 |
| **后续同步注意** | 改动是"**构造函数尾加一个带默认值的参数 + 一处调用 + 一处逐样本乘法**"，不改任何既有分支。上游若重构 `AudioRenderer` 需重新核对锚点 |

### 为什么没有 Adapter 出路（论证）

二开层已经有一个渲染接缝 `CarPlayMediaSink` / `CarPlayMediaSinkFactory`
（`car/core-bridge/.../CarPlayMediaSinkSlot.kt`），看上去"换掉媒体实现"就够了。但：

1. **`AudioRenderer` 是上游 `AndroidMediaSink.kt` 里的 `private class`** ⇒ 二开层
   **根本够不着**它，既不能继承也不能包装。（Kotlin 的 `internal` 是**模块级**，
   `private` 更是文件级——两种都跨不过 `:shared` → `:car:core-bridge` 的边界。）
2. **二开层自己实现音频渲染** = 重写 AAC-LC / Opus 解码、AudioTrack 缓冲、
   预缓冲阈值、fade-in、RTP 重组对接。等于把上游协议代码抄一份，上游一改就烂。
   收益（省 30 行 patch）远小于代价——与 PATCH-0007 第 2 条否决理由同源。
3. **`UpstreamMediaSinkBridge` 用 `by delegate` 委托**，看似可以"拦截 `onAudioRtp`
   改 PCM 再透传"，实际不行：接口方法一旦在类体里覆写就**不能用 `super`**
   （`MediaSink` 是接口），要"拦截 + 透传"只能引用 `delegate` 字段再手工转发
   ——那就等于把 11 个方法全抄一遍。而且这一层拿到的是**压缩后的 RTP**，
   不是 PCM，做增益还得自己解码，回到第 2 条。

### 改动内容

**(a) 新增 `AudioGain.kt`**（纯数学，无 Android 依赖，可单测）：

- `MIN/MAX/DEFAULT_PERCENT = 0/200/100`、`STEP_PERCENT = 5`、`NOT_APPLIED = -1`；
- `sanitize(percent)` 夹取；
- `percentOf(provider, audioType)`：`runCatching` 兜底 —— provider 跑在音频线程上，
  它抛异常必须退化成**原始音量**而不是静音；
- `internal fun applyPcm16InPlace(buffer, offset, count, percent, fromPercent)`：
  S16 小端逐样本缩放 + 饱和限幅 + 跨缓冲区线性斜坡，返回 peak。
  **`percent == fromPercent == 100` 时立即返回 `NOT_APPLIED`，一次乘法都不做。**

算法与上游**上行**麦克风的 `MicrophoneGain.applyPcm16InPlace` 同一套
（S16 小端、饱和），刻意如此：两端削顶行为一致，现场一句"声音破了"才只有一个含义。

**(b) `AndroidMediaSink` 加钩子**：

```diff
     private val microphoneGainPercent: Int = MicrophoneGain.DEFAULT_PERCENT,
+    private val audioGainPercent: (String) -> Int = { AudioGain.DEFAULT_PERCENT },
     onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
```

```diff
-        return AudioRenderer(format, advancedAudioChannelMapping).also { audioRenderers[type] = it }
+        return AudioRenderer(format, advancedAudioChannelMapping, audioGainPercent)
+            .also { audioRenderers[type] = it }
```

```diff
 private class AudioRenderer(
     val format: AudioFormat,
     private val advancedAudioChannelMapping: Boolean,
+    private val audioGainPercent: (String) -> Int,
 ) : Closeable {
```

```diff
         if (!fadeApplied) {
             applyFadeIn(data, offset, length)
             fadeApplied = true
         }
+        if (length > 0) {
+            val target = AudioGain.percentOf(audioGainPercent, format.audioType)
+            val peak = AudioGain.applyPcm16InPlace(data, offset, length, target, lastGainPercent)
+            if (peak != AudioGain.NOT_APPLIED && !gainAppliedLogged) { ... }
+            lastGainPercent = target
+        }
         var written = 0
```

### 为什么必须是"函数"而不是一张 `Map<String, Int>`

`sinkFactory.create(...)` 只在 `buildSession()` 里被调一次。传 Map 就等于把音量
**冻在建栈那一刻**，用户拖完滑杆必须重连才生效；传函数则是**每次写 PCM 现问一次**，
配合 `CarPlaySessionBridge` 的 `@Volatile var settings` 就能中途改。

代价：`CarPlaySinkRequest` 是 data class，函数字段会让 `equals`/`hashCode` 退化成
引用比较。已确认全仓没有任何地方比较两个 `CarPlaySinkRequest`，并在该字段的 KDoc 里写明。

### 为什么斜坡是必需的（不是装饰）

滑杆按 5% 一档跳变。跳变若落在缓冲区边界上——前一个缓冲区最后一个样本 100%、
后一个第一个样本 200%——波形上就是一个台阶，听感是"咔"的一声。
跨一个缓冲区插值把台阶摊到约 20 ms。`lastGainPercent` 记录**上一缓冲区结束时的实际档位**
并作为下一次的 `fromPercent`，否则每个缓冲区都从目标值起跳、斜坡形同虚设。

### 默认路径行为等价的保证

- 新参数默认 `{ AudioGain.DEFAULT_PERCENT }`（100）；
- `100 == 100` 时 `applyPcm16InPlace` **立即返回，不改一个字节**；
- 上游宿主 `common/.../CarPlayHostActivity.createMediaSink` 用的是**具名参数**，
  新参数有默认值 ⇒ **该文件一字未动**（这是本次唯一可能碰到 `common/` 的风险点，已排除）。

⇒ 不装 provider 的调用方（含上游自己的宿主）行为与改动前**逐字节一致**。

### 验证

- `shared/src/test/.../media/AudioGainTest.kt`（**15 项**）：区间夹取、0% 静音、
  衰减、饱和（不是截断）、越界夹到上限、斜坡插值与终点、反向斜坡、窗口边界、
  尾部奇数字节、越界窗口抛异常、provider 夹取/抛异常回落/收到正确的 audioType。
- `car/core-bridge/src/test/.../CarVolumePercentConsistencyTest.kt`（**8 项**）：
  跨模块常量一致性（`CarSessionSettings` ↔ `AudioGain`，见下）、步进整除、
  audioType→通道映射表（同时钉住那次枚举改名）、五条通道各取各的字段、
  关掉总开关后一律 100%、出厂状态、五条通道条条可达。
- `compileDebugKotlin` 通过；`:shared:testDebugUnitTest` 与
  `:car:core-bridge:testDebugUnitTest` 全绿；`assembleDebug` 出包。
- ⚠️ **未在车机上验证**：车机不在手边。实车需要确认的是
  `logcat` 里 `audio gain type=... audioType=... percent=...` 那一行是否出现、
  以及拖动滑杆后对应声音是否真的变化。

### ⚠️ 一处必须知晓的常量重复（不是疏漏）

界面侧的区间常量在 `CarSessionSettings`（`car:api`），DSP 侧的夹取在 `AudioGain`（`shared`）。
模块依赖方向是 `shared` ← `car:core-bridge` → `car:api`，**`car:api` 看不到 `shared`**，
所以**不可能靠共享常量解决**，两边各写一份是唯一选择。
`CarVolumePercentConsistencyTest` 专门断言它们相等——改了一边忘了另一边会红。
不一致的后果是**行为分叉而非崩溃**：界面能拖到 200%、DSP 只认到 150% 的话，
滑杆后半段静默失效，现场表现是"拖到底也没更响"。

### 与之配套、**不需要**登记的二开层改动

全部在 `car/` 下：

- `car/api/.../settings/CarSessionSettings.kt`：新增 `perChannelVolumeEnabled` +
  五条通道百分比字段 + `volumePercentFor(channel)`（**唯一取数入口**）+ 四个区间常量；
- `car/api/.../audio/CarAudioAdapter.kt`：**枚举改名** `NAVIGATION`→`ASSISTANT`、
  `ALERT`→`NAVIGATION`（原两名与其路由目标正好互换，见该文件 KDoc）；
- `car/core-bridge/.../SharedPreferencesCarSettingsStore.kt`：六个新 key + load/save；
- `car/core-bridge/.../CarPlayMediaSinkSlot.kt`：`CarPlaySinkRequest.audioGainPercent` + 透传；
- `car/core-bridge/.../CarPlaySessionBridge.kt`：provider 引用 `@Volatile settings` 字段；
- `car/api/.../developer/CarDiagnosticSettingsDump.kt`：六行白名单；
- `car/ui/.../SettingsScreen.kt`：把原来那张「暂不支持」只读卡换成开关 + 五条滑杆，
  并同步 `summaryAudio`。


## PATCH-0009

| 项 | 内容 |
| --- | --- |
| **状态** | ✅ APPLIED |
| **文件** | `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt` |
| **锚点** | 字段区（`wirelessGeneration` 之后）；`sessionListener.onSessionActive`（开头）；`start()`；`reconnectIphone()`；`close()`；`onStatus(CarPlayStatus.RunningWireless)` 之后；`armWirelessHandoffWatchdog`（开头）；Companion 常量区 |
| **上游 commit** | `3753867` |
| **改动规模** | 约 +145 行（**全部为新增**，不改任何既有分支） |
| **修改原因** | 「iPhone 始终不来**反向**连 AirPlay TCP」这一类卡死**在整个上游里没有任何 deadline 覆盖** ⇒ 界面永久停在「无线通道就绪」，整份诊断包里一句报错都没有 |
| **是否可用 Adapter 替代** | 否，见下节论证 |
| **后续同步注意** | 纯新增（字段 + 4 处调用 + 2 个新方法 + 2 个常量）。上游若重构 `runWireless()` 的 `onStatus(RunningWireless)` 位置或 `sessionListener`，需重新核对锚点。⚠️ **已知上游冲突风险（2026-10-06 发现）**：上游 `master` 已前进到 `17c9243`，其中**恰好改了紧邻的 `armWirelessHandoffWatchdog`**——把它超时后的 `closeWirelessStack() + fail(...)` 整段删掉，改成"继续走蓝牙 iAP2"。本 patch 新增的 watchdog 就在该函数**正上方**，同步时两段会贴在一起，**必须逐个确认谁是哪一段**，不要把上游"不再失败"的改动误加到本 watchdog 上（本 watchdog 的失败语义是**独立且必要的**：它管的是"iPhone 从不来连"，与上游那条"隧道没 ready"不是一回事） |

### 现场证据（为什么必须有它）

两个阻塞点**都不会自己退出**：

1. `CarPlayVpnService.kt:193` 的 `server.accept()` —— `ServerSocket()` 在 `:173` 构造，
   **从未调用 `setSoTimeout`** ⇒ 没有客户端来时**永久阻塞**；
2. `Iap2WirelessControlClient.kt:97` 的 `recv(60_000)` —— 超时后只 `continue`（`:111`），
   循环本身没有退出条件；`bringUpDeadlineNanos` 在 `onReady()`（`:85`）之后**不再被检查**。

而既有的 45 秒 `armWirelessHandoffWatchdog` **覆盖不了本场景**：
它只在收到 iPhone 的 `disableBluetooth` 时武装，而那条命令**只有在 iPhone
已经建立 AirPlay 之后**才可能下发 ⇒ "iPhone 从不连接"时它根本不会被武装。

### 与既有 watchdog 的分工（两个 watchdog 管两段，不重叠）

| | 本 watchdog | `armWirelessHandoffWatchdog` |
| --- | --- | --- |
| 起点 | 置 `RunningWireless`（蓝牙 CSM 刚通） | 收到 `disableBluetooth`（AirPlay 已建） |
| 等什么 | iPhone **来连** AirPlay TCP | 隧道 iAP2 **ready** |
| 解除 | `onSessionActive` / handoff 武装时 | `maybeCompleteWirelessHandoff` |

两段**串行**：本段成功（`onSessionActive`）之后才可能进入下一段。

### 为什么没有 Adapter 出路（论证）

阻塞点全在 `:shared` 的**私有实现**里：

1. `CarPlayVpnService` 的 `acceptLoop` 是 Core 内部的线程，二开层只能通过
   `attachWireless()` 进去，**够不到那个 socket**；
2. `Iap2WirelessControlClient` 同样是 Core 内部类，构造点在 Core 内；
3. 二开层能做的只有"在 `:car:core-bridge` 侧另外起一个定时器看状态"——
   但那需要 Core 把"当前处于无线等待中"这个内部状态暴露出来，
   **要暴露就得改 Core 的可见性，改动反而比本 patch 更大**，
   而且会引入"两个地方各有一个超时判据"这种迟早不一致的结构。

⇒ 直接在 `runWireless()` 的流程里加 deadline 是最小且最自洽的做法。

### 有界重试的边界（刻意不无限重试）

超时后重建一次无线栈（`restartWireless`），最多 `WIRELESS_ATTACH_MAX_ATTEMPTS = 2` 次。
不无限重试的理由：反复重启蓝牙 RFCOMM 会让车机侧配对列表抖动，而且
"iPhone 侧 Wi-Fi 没连上"这一类原因**重试救不回来**，该做的是尽快把可执行的提示交给用户。

### 刻意不做的事

- **不关热点**：`CarPlaySessionBridge` 只按 `keepHotspotAlive` 决定是否 `adbHotspot.stop()`，
  默认 true ⇒ 恢复时热点保留，下次连接秒复用；
- **不关 VPN / 不拆监听**：AirPlay 监听是下一次尝试要用的，拆了只会更慢。

### 验证

- `:shared:compileDebugKotlin` 通过；
- **未在车机上验证**：需要一台"热点已就绪但 iPhone 不连"的现场才能复现。
  验证方式：让 iPhone 不去连热点，等 40 秒，看状态栏是否出现
  「iPhone 没有连上这台车机的热点…」这句提示，且诊断里有
  `wireless attach timed out` 那一行。

## PATCH-0010

| 项 | 内容 |
| --- | --- |
| **状态** | ✅ APPLIED |
| **文件** | `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayRuntimeConfig.kt`（枚举 + 字段 + 校验）；`shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt`（`startWirelessHotspot` 分支 + 新增 `sameLanHotspotInfo()`）；`common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt`（`hotspotModeLabel()` 补 `SAME_LAN` 分支，见下方「补登记」） |
| **锚点** | `WirelessHotspotMode` 枚举；`CarPlayRuntimeConfig` 构造参数（`manualHotspotSecurity` 之后）；`init` 校验块末尾；`startWirelessHotspot()` 开头；`isStaleWirelessRun` 之前（新增方法）；`hotspotModeLabel()` 的 `when` 末尾（`MANUAL` 之后） |
| **上游 commit** | `3753867` |
| **改动规模** | `CarPlayRuntimeConfig.kt` 约 +55 行；`CarPlayController.kt` 约 +90 行；`CarPlayHostActivity.kt` +9 行 |
| **修改原因** | 实现**「同一 Wi-Fi」连接方式**：车机与 iPhone 连同一个路由器/便携热点，DBPlay 不建任何热点，直接用车机的局域网地址直连 AirPlay 7000 |
| **是否可用 Adapter 替代** | 否，见下节论证 |
| **后续同步注意** | ⚠️ 这是本仓库**第三处修改上游已有行**。`WirelessHotspotMode` 是新增枚举值（上游若也加值不会冲突，但若也加了同名值需人工合并）；`startWirelessHotspot()` 的改动是"函数开头插入一个提前返回"，不改既有三条分支。上游若重构该函数需重新核对。⚠️ 另有一处 `common/…/CarPlayHostActivity.kt` 的 `hotspotModeLabel()`（见下方补登记）——上游若重构该 `when` 需一并核对 |

### 补登记：`common/…/CarPlayHostActivity.kt` 的 `hotspotModeLabel()`（2026-10-07）

**背景**：`0ca304c`（热点开机自启内置）在提交时**未登记**本文件，违反本文件开头
「任何对 `common/` 的修改必须先登记」的规则。2026-10-07 同步时发现并补齐
（与 2026-09-28 补登记 PATCH-0004 / 0005 同类处理，**沿用原编号**而非新开 PATCH-0011
——它与 PATCH-0010 是**同一次改动的漏跟部分**，拆成两条会让同一个上游合并关注点散落两处）。

**改了什么**：给 `hotspotModeLabel()` 的 `when` 补一个分支：

```kotlin
WirelessHotspotMode.SAME_LAN -> "局域网连接（不建热点）"
```

**为什么必须改**：`WirelessHotspotMode.SAME_LAN` 由 PATCH-0010 引入，但这条 `when`
没跟着补 ⇒ **`:common` 编译失败、整包 `assembleRelease` 出不来**。
这不是"顺手优化"，是 PATCH-0010 的**编译依赖**——漏了它整个 release 出不来。

**文案口径**：与 `CarHotspotResolver` 里 `CarHotspotMode.SAME_LAN` 的中文标签
**逐字一致**。同一个概念挂在两个枚举上，必须同一口径，否则设置页与诊断页会各说各话。

**教训（值得记住）**：给上游枚举**加值**时，除了改 `when` 的**业务分支**（PATCH-0010 做了），
还要全仓 grep 该枚举的**展示型 `when`**（label / 文案 / 诊断输出）——它们同样会因非穷尽而编译失败。
本仓已有同类规则：MEMORY 第 19 条「给枚举加值 ⇒ 全仓 `when` 变非穷尽，加显式分支不要用 `else`」。

### 为什么需要它

前三种模式（P2P / LOH / MANUAL）的前提都是"**由车机提供那张网**"（自己起，
或从车机现成的热点接管）。但现场有第三类环境：车机本身就在一张 Wi-Fi 上
（4G 车机连手机热点、或车机连着车内路由器），而 iPhone 也连在同一张网上。
这时再自建一张热点是**纯负担**：多层 NAT 让 AirPlay 的 mDNS 与反向回连更容易断，
用户还要为"该连哪张网"做选择。

### 为什么没有 Adapter 出路（论证）

上游 `CarPlayController.startWirelessHotspot()` 里的 `when (hotspotMode)` 对三个值
**穷举**，且**三条分支都调用 `manager.start()`**：

```kotlin
val manager: WirelessHotspotManager = when (hotspotMode) {
    WirelessHotspotMode.WIFI_P2P -> WifiP2pGroupManager(...)
    WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> LocalOnlyHotspotManager(...)
    WirelessHotspotMode.MANUAL -> ManualHotspotManager(...)
}
hotspot = manager
return manager.start(timeoutMillis)
```

⇒ **上游根本不存在"跳过热点"这条路**。二开层能做的最多是：

1. **借道 `MANUAL`**（像 `ADB_HOSTAPD` 那样）—— 但 `ManualHotspotManager.start()`
   会去找**本机的 AP 网卡**（只认 ap / wlan / p2p 开头且有地址的网卡），
   而 SAME_LAN 要的是 **STA 侧地址**，两者判据相反 ⇒ 找到的就是我们自己
   或车机起的那张 AP，通告出去的地址 iPhone 到不了。**这条路走不通。**
2. **自己起一个假的 `WirelessHotspotManager`** —— 但 `startWirelessHotspot()`
   里的 `when` 是编译期穷举，二开层**无法往里插一个新分支**。

⇒ 必须 PATCH。这也是本仓第三次修改上游已有行（前两次是 PATCH-0006 / 0007）。

### 与 MANUAL 的关键区别（容易混淆，专门记一条）

| | `MANUAL` | `SAME_LAN` |
| --- | --- | --- |
| 车机角色 | **AP**（它给别人发网） | **STA**（它连着别人的网） |
| 读取器 | `ManualHotspotManager`（找 AP 网卡） | `CarSameLanReader`（读 activeNetwork） |
| 是否调 `startSoftAp` | 从不（只 attach） | 从不（也不 attach，本来就没有） |
| 上游改造 | 无（借道 MANUAL） | **有**（新增枚举值 + 分支） |

两者在网卡枚举上有交集（都是 `wlan*`），但判据相反 ⇒ 不能互相复用。

### 改动内容

**(a) `WirelessHotspotMode.SAME_LAN`**（新增枚举值，附完整 KDoc）；

**(b) `CarPlayRuntimeConfig.sameLanHostAddress: String? = null`**
+ `init` 里的校验：该模式下必填、不含 U+0000、**必须是 IPv4 字面量**
（主机名要 DNS、IPv6 与我们自己的监听口径不符，两者都属"能配但必然失败"，
所以早拦）。

**(c) `startWirelessHotspot()` 开头插入提前返回**：

```kotlin
if (hotspotMode == WirelessHotspotMode.SAME_LAN) {
    return sameLanHotspotInfo()
}
```

`when` 里另补一个 `SAME_LAN ->` 分支**显式抛异常**（而不是 `else`），
这样将来再加枚举值时编译器会提醒，而不是被 `else` 悄悄吞掉。

**(d) 新增 `sameLanHotspotInfo()`**：把配置里的地址包成 `WirelessHotspotInfo`，
**一个热点都不建**。地址缺失 / 非法 / 回环 / link-local 时**直接抛 `IOException`，
不回落到自建**——理由与"不再回落 LOH"一致：用户显式选了"同一 Wi-Fi"，
悄悄换成自建会让他拿着一台连着陌生热点的 iPhone 去猜"为什么密码不对"。

### 后续修正（2026-10-07，同一 PATCH 编号，不新开）

**现象**：真机（手机测试，诊断包 `1791342910937`）SAME_LAN 永远卡在「无线通道就绪」，
40 秒后 `wireless attach timed out: iPhone did not open the AirPlay connection`，
`handoffRequested=false tunnelReady=false`。

**根因**：`sameLanHotspotInfo()` 当初填的
`ssid = config.manualHotspotSsid`（= 热点名 `DBPlay`）、
`passphrase = config.manualHotspotPassphrase`（= `12345678`），
并在 KDoc 里假设"iPhone 已经在网上，会忽略它们"。

**这个假设是错的**：iPhone 是**照着 `0x5703` 的 SSID/密码去关联网络**的。
SAME_LAN **不建 `DBPlay` 这张热点**（车机连的是另一张 `192.168.10.x`）⇒
iPhone 拿着一个不存在的网络名去关联 ⇒ 永远不切 Wi-Fi ⇒ 全链路无报错地卡住。

**修法**（新增字段 + 全链路透传，仍然**不新增 PATCH 编号**——属同一次改动的缺陷修正）：

1. `CarPlayRuntimeConfig`：新增 `sameLanWifiSsid` / `sameLanWifiPassphrase`
   （可选；SSID ≤32 字节、密码空或 8..63，只在该模式下校验）。
2. `CarPlayController.sameLanHotspotInfo()`：`ssid`/`passphrase` 改取这两个新字段，
   **不再回落到热点名**；SSID 为空时 `Log.w` 明确提示"可能被 iOS 拒绝，请在设置里填"。
3. 二开层透传：`CarSessionSettings`（新字段）→ `SharedPreferencesCarSettingsStore`
   （新 key `same_lan_wifi_ssid` / `same_lan_wifi_passphrase`，老安装取默认空值）
   → `CarDiagnosticSettingsDump`（诊断输出）→ `CarHotspotResolver.sameLan()`
   （优先级：设置手填 > `CarSameLanReader` 读到的真实 SSID）
   → `CarHotspotPlan.sameLanWifiSsid/_Passphrase` → `CarPlayConfigBuilder` /
   `CarPlaySessionBridge` → 上游配置。
4. `CarSameLanReader`：读 STA 侧 `WifiInfo.getSSID()` 并 `sanitizeSsid()`
   （剥引号、滤 `<unknown ssid>` / `0x` / `unknown` 哨兵）。
5. 设置页 SAME_LAN 分支新增「Wi-Fi 名称」「Wi-Fi 密码」两个输入框。

**同一纪律**：Android 读不到已保存网络的**明文密码**（`preSharedKey` 自 A10 起对三方
不可读）⇒ 自动路径密码恒空，只能手填，**绝不拿热点密码顶替**。

### 后续修正 2（2026-10-07 晚，同一 PATCH 编号）：SSID 改为**必填**

**现象**：用户用 iPhone 个人热点实测（诊断包 `1791345066541`），第一次尝试
（还没填 Wi-Fi 名）**2.2 秒就失败**：

```
wireless bring-up failed
java.lang.IllegalArgumentException: Wireless SSID must not be blank
    at Iap2WirelessIdentification.<init>(Iap2IdentificationClient.kt:25)
```

**根因**：上一版把"SSID 为空"当成"握手可能被 iOS 拒绝"，于是只 `Log.w` 一句就放行。
**实测证明空 SSID 是崩溃级失败**——下游 `Iap2WirelessIdentification` 有硬校验
`require(ssid.isNotBlank())`，直接抛异常把整个 bring-up 打死。

**修法**（仍**不新增 PATCH 编号**）：
1. `CarPlayRuntimeConfig`：SAME_LAN 下 `sameLanWifiSsid` 由"可选"改为**必填**
   （`require`，与 `sameLanHostAddress` 同一处理），报错发生在配置构造层。
2. `CarPlayController.sameLanHotspotInfo()`：SSID 为空白时抛**中文 `IOException`**，
   明确告诉用户"去设置页填 Wi-Fi 名称（就是 iPhone 热点在别的设备上显示的名字）"。
   **不编造兜底值**——SAME_LAN 通告的是别人的网，编不出它的名字；随便填只会重演
   上午那个"关联一张不存在的网"的 bug。
3. 设置页「Wi-Fi 名称」提示改为**必填**，并加一句"填的是 Wi-Fi 名字，**不是**手机的蓝牙名"。

**⚠️ 诊断包同时暴露的未决问题（问题 B，非本次修复范围）**：
用户填的 `华为 Mete 90` 是 **iPhone 的蓝牙名/设备名**，`0x5703` 也确实下发了它，
但 iPhone 仍不来连（40s 超时）。已知车机侧 `wlan0` 拿到 `172.20.10.2/28`（iPhone 热点标准网段）
⇒ 物理链路通。**待确认**：iPhone 个人热点真正**广播的 SSID** 是否等于设备名
（iOS 对超长/特殊字符会截断改写），以及 iPhone 个人热点是否支持这套 iAP2 关联流程。

### 与之配套、**不需要**登记的二开层改动

全部在 `car/` 下：

- `car/api/.../settings/CarSessionSettings.kt`：新增 `CarHotspotMode.SAME_LAN`
  枚举值 + `sameLanHostAddress` 字段（默认空 = 自动读取）；
- `car/api/.../hotspot/CarSameLanInfo.kt`：**新增**，描述"车机作为 STA 所在的局域网"；
- `car/core-bridge/.../hotspot/CarSameLanReader.kt`：**新增**，读
  `ConnectivityManager` 里**类型为 Wi-Fi** 的在用网络（`findWifiNetwork` 用
  `TRANSPORT_WIFI` 过滤——4G 车机上 `activeNetwork` 往往给的是蜂窝口，
  无脑采信会把蜂窝地址通告给 iPhone）；退路是网卡枚举；
- `car/core-bridge/.../network/CarHotspotResolver.kt`：`explicit()` 开头分出
  `sameLan()` 一支 + 新增 `sameLanFromManual()`；
- `car/core-bridge/.../CarPlayConfigBuilder.kt`：`toUpstream()` 加一行映射 +
  `runtimeConfig()` 新增 `sameLanHostAddress` 参数（只在 SAME_LAN 模式下透传）；
- `car/core-bridge/.../CarPlaySessionBridge.kt`：把 `hotspotPlan.sameLan?.hostAddress`
  透传给 builder（**不重新读一次**——两次读取之间网络若变化，
  通告给 iPhone 的地址会与日志里记的不一致）；
- `car/core-bridge/.../SharedPreferencesCarSettingsStore.kt`：一个新 key
  `same_lan_host_address`（新 key ⇒ 老安装天然取默认值，不需要迁移）；
- `car/api/.../developer/CarDiagnosticSettingsDump.kt`：一行白名单；
- `car/ui/.../SettingsScreen.kt`：SAME_LAN 下把「热点身份」整块换成
  「同一 Wi-Fi」卡（本机地址 + 自动读取结果 + 三条使用前提）；
- `car/core-bridge/src/test/.../CarSameLanResolverTest.kt`：**新增 9 项单测**。

### 现场已知限制（如实写在这里，而不是假装支持）

1. **iPhone 是否在同一张网，我们无从验证** ⇒ 只能靠用户自己确认；
2. **路由器开了「客户端隔离」时必然失败**，而车机侧完全看不出来 ——
   设置页已把这条写进「使用前提」，这是本模式最主要的现场失败原因；
3. 自动读取依赖 `ACCESS_NETWORK_STATE` 与厂商对 `LinkProperties` 的实现，
   个别 ROM 给不出 IPv4 ⇒ 留了手填口子。

## 2026-10-06：车机 adbd 端口持久化到 5556（**不涉及上游，无需 PATCH 编号**）

用户要求：「DBPlay 的内置 ADB 端口持久化改成 5556，避免冲突」。

### 结论：`5556` 这个端口号**早已是** `DEVICE_ADB_PORT`（PATCH 时代就有）

真正缺的是**持久化**——旧代码只写瞬态的 `service.adb.tcp.port`，
车机一重启就被 init 按 `persist.adb.tcp.port=5555`（厂商值）恢复回 5555，
次次上车都要重新抢口。本次改的就是这一点。

### 改动（全部在 `car/` 二开层，**不碰上游文件**）

- `car/api/.../hotspot/CarAdbHotspotContract.kt`：
  新增常量 `ADB_PORT_PROPERTY_PERSIST = "persist.adb.tcp.port"`（附完整 KDoc：
  瞬态 vs 持久的区别、init 开机回填、为什么这不算"另抢一个端口"）；
- `car/core-bridge/.../hotspot/AdbShellRunner.kt`：`switchToDedicatedPort()`
  的 shell 脚本在写瞬态那条之后，**再无条件写 `persist.adb.tcp.port`**；
  **复检仍只读瞬态那条**（理由见 `ADB-HOTSPOT.md` §2.3.2）；
  顺带在 `connect()` / `resolveDevicePort()` 的注释里钉死"读的必须是瞬态那条"；
- `car/core-bridge/.../hotspot/AdbShellSession.kt`：第 6 步 `Step.command`
  这条**给人看的采集快照**补上持久那条 `setprop`，标题加「并持久化」，
  否则诊断包里读到的命令与真实脚本不符。

### 验证

`./gradlew :car:core-bridge:compileDebugKotlin :car:core-bridge:testDebugUnitTest
--no-configuration-cache` **全绿**。

### 未做（如实记录）

- 未在真机验证"这台 ROM 到底允不允许写 `persist.*`"——
  代码按"写失败不阻断"处理，真机首次运行看 `Steps` 里的复检口即可判定；
- **未出包**（等用户授权）。

## 2026-10-06（同日晚）：`DeviceAdbEnabler` 从 no-op 改为**真的开通 ADB**（**不涉及上游**）

用户要求：「这你跟 `FUCK-Lynk&Co-ADB` 对齐就可以，但可以要独占 5556 端口」。

### 背景：它此前一直是 no-op

`DeviceAdbEnabler.enable()` 过去只返回一句"暂未自动开通 ADB"——不是因为做不到，
而是因为**尚未实车验证** `SystemProperties.set` 会不会被 SELinux 拦。
参考实现（`FUCK-Lynk&Co-ADB.apk`，反编译见
`_decompile/fuck-lynkco-adb_smali/com/example/osnadber/OSNwifi.smali` .line 74~81）
在**同一台 OneOS 车机上跑通了**，所以这条路是通的。

### 改动（全部在 `car/`，**不碰上游文件**）

- `car/core-bridge/.../hotspot/SystemPropertiesBridge.kt`：**新增**，
  对 `android.os.SystemProperties` 的最小反射桥。`get` 失败返回默认值、
  `set` **不吞异常**（让调用方能记下真实失败类型）。
  与 `AndroidCarSystemAdapter.systemProperty()` 走同一条已验证可行的路。
- `car/core-bridge/.../hotspot/DeviceAdbEnabler.kt`：**重写**。
  判据与顺序**逐条对齐**参考实现 `OSNwifi.openAdb()`：
  读 `service.adb.tcp.port` + `init.svc.adbd` → 口不对就写口 + `ctl.stop/start adbd`；
  口对但 adbd 没 running 就只补 `ctl.start`。
  **两处故意不同**：① 端口 `5555` → **`5556`**；② 只写瞬态 → **瞬态 + `persist.*` 都写**。
- `car/core-bridge/src/test/.../DeviceAdbEnablerPlatformGateTest.kt`：**重写**。
  旧测试断言的是 no-op 行为，已失效。

### 两个容易踩的点（已在代码注释里钉死）

1. **不能走 `adb shell`** —— 鸡生蛋：要连上才能 shell，而连不上正是因为 ADB 没开。
   必须用 `SystemProperties` 直写（无需 root、无需已建立连接）。
2. **`ctl.stop adbd` 只能在"换口"分支里做** —— 若在"只补 start"时也 stop，
   会掐断**当下正在用**的那条连接（此刻通常还在厂商口上），后面 `adb connect` 就没了。

### 验证

`:car:core-bridge:compileDebugKotlin` + `testDebugUnitTest`（**315 项**）+ 
`:car:app:compileDebugKotlin` **全绿**。

> ⚠️ **仍未实车验证**三项：`set` 生效（读回 == 5556）/ `init.svc.adbd` 变 running /
> 无 `avc: denied`。`Outcome.detail` 里会写上读回值，装车跑一次看诊断包即知。
> **若 `persist.*` 被拒**，不影响本次连接——连接后的 `switchToDedicatedPort()`
> 还能靠 root 补上。

## 2026-10-06（同日晚）：「复用车机热点」手填 SSID 填错的静默失败（**不涉及上游**）

用户要求：「改成用户手动输入即可，你看看怎么修复？」

### 问题（测试这条链时发现）

上游 `ManualHotspotManager.start()` 的 `takeIf { it.ssid == expectedSsid }` 造成
**填错名字时反而不报错**：`apConfiguration = null` ⇒ 校验全跳过 ⇒ 上游把用户填的
字面量直接发进 `0x5703`，而那张网没人广播 ⇒ iPhone 连不上、**全链路无报错**、卡在
「无线通道就绪」。而 DBPlay 的 MANUAL 默认 SSID 是 `DBPlay`，**不是车机热点名**
（实车是 `Lynk`）⇒ 用户不改就必然踩。详见 `ADB-HOTSPOT.md` §2.3.3。

### 改动（全部在 `car/`，**不碰上游文件**）

- `car/api/.../hotspot/CarManualHotspotMatch.kt`：**新增**纯函数判据
  （比对 + 给一句带正确名字的人话；**判不准时一律不告警**）。
- `car/core-bridge/.../CarAppServices.kt`：`CarHotspotBriefing` 新增 `warning` 字段；
  新增 `manualSsidWarningOf()`；`phoneHintOf()` 在名字不匹配时**不再说"请连 X"**
  （那是谎话）改为"先把名字改成车机实际的热点名"。
- `car/ui/.../CarUiContract.kt`：`CarUiModel` 新增 `hotspotWarning`。
- `car/app/.../CarAppController.kt`：把 `warning` 投影进 model。
- `car/ui/.../SettingsScreen.kt`：警告排在 SSID 输入框 description 最前；
  MANUAL 的说明文字改为"必须是车机已开启热点的准确名字（区分大小写）"。

### 明确不做（用户要求）

**不自动填充、不硬拦**——判据只比对，不改任何输入值。

### 验证

- 新增 `CarManualHotspotMatchTest`（12 项，纯函数）+ 
  `HotspotManualReuseContractTest`（9 项，源码级契约）；
- `:car:core-bridge:testDebugUnitTest` **336 项全绿** + `:car:app:compileDebugKotlin` 通过。

## 2026-10-06（晚）：「默认无线 + 参考物理长度 350mm」补齐**老安装迁移**（**不涉及上游**）

用户要求：「**默认**这个配置，而不是**建议**别人这个配置」。

### 问题

这两项的出厂默认值在 0.10.1（`00eaac9`）就已经改成了目标值
（`transport = WIRELESS`、`DEFAULT_WIDTH_PHYSICAL_MM = 350`），
`CarSessionSettings` 的 KDoc 里也早就写明了「**已持久化的 `transport` 会原样读回，
只有全新安装才吃这个默认值**」。

也就是说：**新装用户拿到的是无线 + 350mm，老安装用户永远是旧值**。
那只是"对新人友好的默认"，不是"大家的默认"。用户要的是后者。

### 改动（全部在 `car/`，**不碰上游文件**）

`car/core-bridge/.../SharedPreferencesCarSettingsStore.kt`：新增两条**独立**的一次性迁移
（各自带标记，遵循既有纪律）：

- `migrateTransportToWireless()`：持久化值**恰好等于** `WIRED` → 改写成 `WIRELESS`；
  标记 `transport_wireless_migrated_dbplay`。
- `migrateWidthPhysicalMmTo350()`：持久化值**恰好等于** `200` → 改写成 `350`；
  标记 `width_mm_350_migrated_dbplay`。
  未存过时用哨兵 `NO_STORED_WIDTH_MM = Int.MIN_VALUE` 判定（不能拿 `0` 当哨兵）。

两条都只认「**恰好等于旧出厂值**」：
- 已把物理长度调到 250/300 的用户**不碰**（那是他自己量过的真值）；
- 判据用**裸字符串** `"WIRED"` / `200`，不用 `CarTransportMode.WIRED.name`——
  迁就的是"上一版写进偏好的文本"，将来重命名枚举成员也不会让迁移静默失效。

### 明确的取舍

判据**拦不住**「特意选有线 / 特意选 200mm」的用户（单靠持久化值无法区分
"从没改过"与"手动改成默认值"）。与既有 `migrateHideTopBarDefault` 同一取舍：
先修好绝大多数没碰过的用户，少数确有需要的重新选一次即可（设置项本身没变）。

### 验证

- 新增 `SettingsDefaultMigrationTest`（8 项：默认值常量 + 迁移写法契约）；
- `:car:core-bridge:testDebugUnitTest` 全量跑通。

## 2026-10-07：顶栏内容下移一档，设置按钮不再贴着车机状态栏下沿（**不涉及上游**）

**车主原话**：

> 左滑进蓝牙设备页，设置按钮被顶栏遮挡
> 顶栏是 72px，你把设置按钮再往下移，应该能解决

### 先厘清：车主说的「顶栏」是**车机状态栏**，不是我们的 `CarTopBar`

那 72px 是**车机 SystemUI 的独立窗口**（实机日志
`StatusBarWindowView{... 0,0-1920,72}`，SystemUI 进程 1767），不在我们进程里，
`insets.hide(statusBars())` 被 ROM 拒绝 ⇒ **抢不掉**。本应用走的是**避让**路线
（车主 2026-10-06 定案 A，见 `FullscreenInsetContractTest`）：`CarPlayApp` 根节点的
`windowInsetsPadding` 已经把内容整体推到那条栏之下，画面协商 1920x1008。

⇒ **躲状态栏不是 `CarTopBar` 的职责**。实测确认过：单看 `CarTopBar` 里的
`top = spaceM`（12dp）会以为它顶到屏幕边上了，其实它落在 inset 之内。
所以**不能**在顶栏里加"避开状态栏"的分支——`FullscreenInsetContractTest`
反向钉死，而且会变成双重偏移。

### 真正改的东西

顶栏内容**自己的垂直留白**：原来 `top` 和 `bottom` 都是 `spaceM`（12dp），
内容贴着那条栏的下沿，读起来就是「被压住了」。现在：

- 新增 `CarDimensions.topBarInsetTop = d(12f)`（基准 1280x720；1080p 车机
  `scale = 1.25` ⇒ 15dp），**随屏幕短边缩放**；
- `CarTopBar` 顶部内边距 `top = dimens.topBarInsetTop`；
  **底部维持 `spaceM` 不变**——那 12dp 同时承担顶栏与内容区之间的分界。

### 涉及文件（全在 `car/` 扩展层）

- `car/ui/…/theme/CarDimensions.kt`：新增 `topBarInsetTop` + KDoc
- `car/ui/…/components/CarScaffold.kt`：`CarTopBar` 顶部内边距
- `car/core-bridge/src/test/…/TopBarClearanceContractTest.kt`：新增（3 项）

### 验证

- `TopBarClearanceContractTest` 3 项：① 顶部必须用 `topBarInsetTop`、底部保持
  `spaceM`；② 该 token 必须定义在 `CarDimensions` 且走 `d(...)` 缩放换算；
  ③ `CarTopBar` 内不得出现 `windowInsetsPadding` / `WindowInsets` / `statusBars` /
  `hideTopBar`（防止顶栏自己再避让一次）；
- **反向验证**：把 `top` 改回 `spaceM`，①立刻变红（已实测）；
- `:car:core-bridge:testDebugUnitTest` 全量 + `:car:ui:compileDebugKotlin` 通过。

### 明确放弃的尝试（留给下次）

曾打算用 **Robolectric + Compose ui-test 真的 measure 一次**、直接断言设置按钮
的坐标（比静态断言强得多）。实测卡在：Robolectric 运行时要用
`MavenArtifactFetcher` 现抓 `android-all-instrumented:13-robolectric-9030017-i7`
（约 30MB），本机走代理时稳定 `CONNECT tunnel failed, response 502`；
手动置入 `~/.m2` 也没成功。
⇒ 为一个 4px 的留白改动新引入 robolectric / androidx.test / compose-ui-test
三组依赖、并解决镜像分发，**动作的分量远超改动的分量**，放弃。
将来真要测布局坐标，**先把镜像预置进 `~/.m2` 再引入依赖**。
另外记一条踩坑：`:car:core-bridge` 若为测试源码加 `alias(libs.plugins.kotlin.compose)`，
插件会**连带主源码**一起走 Compose 编译器，而本模块主源码没有 Compose Runtime，
会报 `IncompatibleComposeRuntimeVersionException`（需 `compileOnly` 补 runtime）。

---

## PATCH-0011

| 项 | 内容 |
| --- | --- |
| **状态** | ✅ APPLIED（2026-10-07） |
| **文件** | `shared/.../airplay/AirPlayConfig.kt` / `AirPlayInfoPlist.kt` / `AirPlaySession.kt`（**均未改**）；`shared/.../media/AndroidMediaSink.kt`（**未改**） |
| **上游 commit** | 基线 `17c9243` |
| **修改原因** | 把上游**已支持但从未被使用**的"第二块屏（ALT 轨）"接到车机仪表 HDMI2 上 |
| **是否可用 Adapter 替代** | 否。`AirPlayConfig.cluster` 是**协议数据**，在 `:shared` 里被 `/info` 消费，没有夹层可插 |
| **后续同步注意** | 上游若动 `cluster` / `STREAM_TYPE_ALT_SCREEN` / `setSurface(type,…)` 的签名，**仪表会静默失效**（不报错、只是黑屏）。同步后必须实机确认仪表出画面 |

### 改动内容

#### (a) `:shared` —— **零改动**（这是本 PATCH 的关键结论）

仪表副屏**不是**靠改上游实现的，而是**用尽上游已有的接口**：

| 上游已有能力 | 位置 | 我们如何使用 |
| --- | --- | --- |
| `AirPlayConfig.cluster: AirPlayDisplayConfig?` | `AirPlayConfig.kt` | 由 `:car:core-bridge` 的 `CarPlayConfigBuilder` 填非空值 |
| `/info` 按 `cluster != null` 追加第二块屏 | `AirPlayInfoPlist.kt` | 自动生效——`cluster` 一非空，iPhone 就会开 111 流 |
| `STREAM_TYPE_ALT_SCREEN = 111` 的会话分流 | `AirPlaySession.kt` | `MAIN_SCREEN, ALT_SCREEN -> media.onScreen(...)`，原本就分流 |
| `AndroidMediaSink.setSurface(type, surface)` / `clearSurface(type, …)` | `AndroidMediaSink.kt` | 按 **type 分别绑定**，本就是"类型键控"的 |

⇒ **上游一行未改**。PATCHES 里登记它，是为了"同步时知道有一块依赖在此"。

#### (b) `:car:core-bridge` —— 把 ALT 轨从"绑到主屏"改为"绑到仪表"

修改前的 `AndroidCarPlayMediaSink.attachSurface()`（照抄上游
`CarPlayHostActivity`）把 110 与 111 **绑到同一块 Surface**——上游不声明
`cluster`、iPhone 不开 111 流，所以绑了也无害。一旦我们开始声明副屏，
111 就**真的会有流**，再绑主屏就会"仪表内容 = 主屏内容 + 分辨率错乱"。

因此拆成两处绑定：

| Surface | 流类型 | 绑定方法 |
| --- | --- | --- |
| 主屏 | `SCREEN_TYPE_MAIN = 110` | `attachSurface(surface)` |
| 仪表 HDMI2 | `SCREEN_TYPE_ALT = 111` | `attachClusterSurface(surface)`（新增） |

其余改动（均为 `car/` 扩展层，**不登记**，因为不触碰上游文件）：

- `CarPlayConfigBuilder.airPlayConfig(..., cluster: CarClusterSpec?)`
- `CarPlaySessionBridge(clusterSpecResolver: () -> CarClusterSpec?)`
- `CarAppServices.createSession(..., clusterSpecResolver)`
- `:car:api` 新增 `CarClusterPresenter` / `CarClusterSpec`；`CarPlatform.cluster`
- `:car:platform-geely`（**新模块**）：`GeelyDisplaySelector` /
  `GeelyClusterPresentation` / `GeelyClusterPresenter` / `GeelyCarPlatform` /
  `GeelyCarSystemAdapter`
- `car/app`：`selectPlatform()` 先判 Geely，`CarAppController` 管副屏生命周期

### 为什么这个 patch 是安全的

- **零上游改动**：`git diff 17c9243 -- shared/` 应为空（可用作回归判据）。
- **默认关闭**：`CarPlatform.cluster` 默认 `null` ⇒ `clusterSpecResolver` 返回
  null ⇒ `AirPlayConfig.cluster = null` ⇒ `/info` 不声明第二块屏。
  **非吉利车机的行为与本 PATCH 之前逐字节相同。**
- **不新建虚拟屏**：HDMI2 是车机**本来就存在**的一块非主屏，DBPlay 只往它上面
  挂一个 `Presentation`（与反编译的原版做法一致），不碰 `createVirtualDisplay`。
- **判据从严**：只认"非主屏 + 恰好 1920x1080 + 唯一命中"。拿不到就按"无副屏"
  处理，绝不误选副驾屏 / 后排屏。

### 验证

- `:car:api` / `:car:platform-geely` / `:car:core-bridge` / `:car:app`
  四个模块 `compileDebugKotlin` 全绿；
- `:car:core-bridge:testDebugUnitTest` / `:car:platform-ecarx:testDebugUnitTest` 全绿；
- **待实机确认**（下一件必做的事）：吉利车机上接上仪表 HDMI2，
  看 `carplay-cluster` 标签的日志是否出现
  `Cluster: 仪表副屏已挂载 displayId=… 面板=1920x1080 可见高=720`，
  且仪表上方 720px 出画面、下方 360px 透出车机自己的仪表。

### 明确**不做**的部分（留给下次）

反编译的原版还有一整套仪表专属能力，本轮**刻意不移植**：

| 原版能力 | 为什么不做 |
| --- | --- |
| `GeelySelfDrawOverlayView`（车机自绘导航叠层 / 标线 / 路口图） | 依赖原版自己的导航数据源，DBPlay 侧没有对应物；画一个半吊子的叠层比不画更糟 |
| 工厂模式全屏地图（`modePoll` / `invalidateManualOpen`） | 那是它的"工程调试模式"，与"把画面放到仪表"无关 |
| `GeelyClusterSafeAreaStore`（安全区持久化 + 手动选屏） | 本轮是"有且只有一块 1920x1080 副屏"，自动选即可；等出现多副屏车型再接回 |

**HUD 桥**（`GeelyHudAdapter`，百度导航 HUD 服务）本轮**也未移植** ——
用户已明确把范围收敛为"先只做仪表 HDMI2 副屏"。要移植时按反编译台账走
（服务 `com.baidu.naviauto.hudbridge.CarPlayHudBridgeService`、
描述符 `com.juyou.carplay.hudbridge.ICarPlayHudBridge`、
transaction codes 1/2/3/10、magic `0x48445532`）。

> ↑ 该段写于 2026-10-07。**2026-10-08 用户已明确要求把 HUD 一并移植，
> 并授权为此修改上游 `:shared`** ⇒ 引入 PATCH-0016，见下。

---

## PATCH-0016

| 项 | 内容 |
| --- | --- |
| **状态** | ✅ APPLIED（2026-10-08） |
| **文件** | `shared/.../iap2/message/Iap2NavigationMessages.kt`（**新增**）；`shared/.../navigation/CarPlayNavigationBridge.kt`（**新增**）；`shared/.../orchestration/CarPlayController.kt`（`onIap2Incoming` 附近） |
| **上游 commit** | 基线 `17c9243` |
| **修改原因** | 上游注册了导航类 iAP2 端点却**不提供解码**：HUD 拿不到数据 |
| **是否可用 Adapter 替代** | 否。`onIap2Incoming` 是 `CarPlayController` 的 `private` 回调，且帧在 Core 内被丢弃，`:car` 层没有夹层可插 |
| **后续同步注意** | 上游若给这几个端点补上**官方解码器**，应**撤掉本 PATCH 改用上游的**，否则同一帧会被解两遍。同步后必须实机确认 HUD 仍出数据 |

### 问题（上游的缺口，不是我们的需求）

| 上游已有 | 位置 | 缺什么 |
| --- | --- | --- |
| 端点注册 `0x5201 RouteGuidanceUpdate` | `iap2/catalog/Iap2Endpoint.kt:638` | **无字段 schema** |
| 端点注册 `0x5202 RouteGuidanceManeuverUpdate` | `iap2/catalog/Iap2Endpoint.kt:639` | **无字段 schema** |
| 端点注册 `0x5204 LaneGuidanceInfoUpdate` | `iap2/catalog/Iap2Endpoint.kt:640` | **无字段 schema** |
| 声明"会从手机收到"这三条 | `transport/Iap2IdentificationClient.kt:285-286` | 收了**没有任何消费者** |
| iAP2 入站总入口 | `orchestration/CarPlayController.kt:391` | `if (frame.messageId != NOW_PLAYING_UPDATE) return` ⇒ **全丢** |

结论：**帧其实一直在来，只是被第 391 行挡在门外。** 这是"上游留了个半成品"，
不是"上游没这个能力"——但补完它必须动 `:shared`。

### 改动内容

#### (a) 新增 `Iap2NavigationMessages.kt`

对标既有 `Iap2NowPlayingAccumulator` 的**增量合并**范式（`Iap2Messages.reader(frame)`
→ `Iap2BodyReader` → `optionalGroup` / `has` 门控逐字段读），产出
`CarNavigationState`：

| 字段 | 含义 | HUD 用途 |
| --- | --- | --- |
| `routeMeters` / `routeSeconds` | 全程剩余距离 / 时间 | 仪表「剩余 xx km / xx min」 |
| `segmentMeters` | 当前路段剩余距离 | 转向点剩余距离 |
| `currentRoad` / `nextRoad` | 当前 / 下一路名 | HUD 路名行 |
| `maneuverType` | 转向类型码 | 经 `turnKind()` → `nativeTurnId()` 映射 |
| `revision` / `sessionId` | 路线版本 / 会话 | HUD 的 `generation` 判定（换路要 bump） |
| `lanes` | 车道 | **本版刻意不解码**（见下） |

**关键设计：字段全部可选。** 每个字段先 `has()` 再读，缺失即跳过、保留旧值；
整帧解不出也**只记日志、不上抛**。

##### 车道（`0x5204`）为什么不解码

反编译台账 §11 的车道格式是 **HUD 桥发给百度服务的 Parcel 布局**，与 iAP2
**接收端**的 `0x5204` body 是两回事。前者是「平铺 + 嵌套循环」的手写序列，
后者没有实证依据说明是 `group` 结构。**拿发送端格式去解接收端会解码成功但字段错位**，
比不解更危险 ⇒ 明确不做，`CarNavigationState.lanes` 恒空，等抓到真实 body 再补。

#### (b) `onIap2Incoming` 放行导航帧

第 391 行的早退改为**分发**：NowPlaying → 原路；导航三帧 → 累加器 →
下发快照。**`try/catch` 包的粒度不动**（异常只记日志），
保证 HUD 的 bug 不会打死 CarPlay 会话。

⚠️ **实现中发现的两处坑**（已修，写在这里供同步时复核）：
1. `Iap2ProtocolException` **extends `IOException`**，不是 `RuntimeException`。
   累加器最初 `catch (_: RuntimeException)` 兜不住字段宽度不符的帧 ⇒ 异常会逃进
   iAP2 控制循环。已改为 `catch (_: Exception)`。
2. `optionalGroup(id)` 遇到 payload 宽度不够时**会抛**（不是返回 null）。
   若把"组解析失败"和"整帧失败"混在一起，一个平铺帧会让整帧作废 ⇒ 已用
   `runCatching { optionalGroup(...) }.getOrNull() ?: body` 隔离为字段级降级。

#### (c) 新增 `CarPlayNavigationBridge.kt`（下发通道）

`object`（**public**，跨模块可见），风格与上游 `CarPlayMediaSessionBridge` 一致：
owner 校验 + `runCatching` 隔离订阅方异常 + `observe` 立即回调当前值。

### 为什么这次**不能**像「正在播放」那样绕开上游

`:car:core-bridge` 的 `CarPlayNowPlayingClient` 立过一条先例：**上游数据要出来，
优先走"已经公开的那扇门"**（Media3 的 `MediaBrowser`/`MediaController`），
而不是给上游开接缝 —— 这样能保住"对上游足迹为零"。

**导航数据没有这样的门，这是本 PATCH 存在的唯一理由：**

| 数据 | 公开出口 | 我们怎么拿 |
| --- | --- | --- |
| 正在播放 | `CarPlayMediaSessionService`（标准 `MediaSessionService`，`exported=true`） | `MediaBrowser` + `MediaController` ✅ 无需改上游 |
| **导航** | **不存在** —— 没有对应的 Service，`CarPlayMediaSessionBridge` 里也没有导航字段 | **只能开接缝** ⇒ PATCH-0016 |

换句话说：本 PATCH 不是"图省事直接改上游"，而是**核实过没有公开门之后**的选择。
若日后上游为导航也提供了 `MediaSessionService`（或别的公开出口），
应**撤掉本 PATCH 改走门**，与 `CarPlayNowPlayingClient` 的做法对齐。

### 为什么这个 patch 是**有风险**的（与 PATCH-0011 形成对比）

- PATCH-0011 是"接线"，**零上游改动、默认关闭、可回归判据为空 diff**。
- PATCH-0016 是**真的写上游逻辑**，且字段号**未经实车抓包确认**。
- 因此本 PATCH 的验收标准不是"编译通过"，而是**实机日志**：
  接上 iPhone 起导航，看 HUD 是否收到 `routeMeters/nextRoad/maneuverType`。
  若字段错位，表现是"HUD 显示荒谬数值"而非崩溃——**这是可接受的降级**。

### 兜底原则（写进代码，不靠记性）

1. 任何字段读不到 ⇒ 跳过，不发。
2. 整帧解码抛异常 ⇒ 捕获 + `debugLog`，**绝不冒泡**到 iAP2 通道。
3. 快照字段全空 ⇒ 不发（宁可不发，也不发一个"0 米 0 分钟"的假数据）。
4. 换路判定只用 `sessionId/revision`，**不猜**（契约层 `CarHudGuidance`
   直接带这两个值，不用时间戳/路名近似）。

### 验证

- 全模块 `compileDebugKotlin`；`:car:core-bridge` / `:car:platform-ecarx` 单测；
  新增 4 个 iAP2 导航解码用例 + 13 个 HUD 映射用例，**共 628 个测试 0 失败**；
- **待实机确认**（下一件必做的事）：起导航后 HUD 屏幕/日志出现真实里程与路名。


---

## PATCH-0017

| 项 | 内容 |
| --- | --- |
| **状态** | ✅ APPLIED（2026-10-07） |
| **文件** | **零上游改动**：全部新增/修改都在 `car/` 扩展层（详见下表） |
| **上游 commit** | 基线 `17c9243` |
| **修改原因** | 原版 GeelyCarPlay 有"仪表自绘导航"（我们自己用 Canvas 画卡片），DBPlay 只有视频流与 HUD 桥，缺这一层 |
| **是否可用 Adapter 替代** | **是** —— 这就是本 PATCH 与 PATCH-0016 的根本区别：全程只碰 `car/` 层，`:shared` 一行未动 |
| **后续同步注意** | 无。上游怎么改都不影响本 PATCH（新模块 + 新契约 + 新文件） |

### 与 PATCH-0011 / PATCH-0016 的关系

| | PATCH-0011（仪表副屏） | PATCH-0016（iAP2 导航解码） | **PATCH-0017（自绘）** |
| --- | --- | --- | --- |
| 动 `:shared`？ | ❌ 零改动 | ✅ **真的写上游** | ❌ **零改动** |
| 数据从哪来 | 复用上游 ALT 轨 | 新开解码器 | **复用 PATCH-0016 的门** |
| 风险 | 低（可回归为空 diff） | 高（字段号未实车确认） | 低（只在 `car/` 内） |

⇒ 本 PATCH **不新增**任何上游足迹，是三层里最安全的一层。

### 新增/修改清单（全部在 `car/`）

| 模块 | 文件 | 性质 |
| --- | --- | --- |
| `:car:api` | `selfdraw/CarSelfDrawModels.kt` | 新增：4 个区域开关+坐标、安全区、快照、机动、车道 |
| `:car:api` | `selfdraw/CarTurnClassifier.kt` | 新增：20 个转向枚举 + 中文正则/数字码两级分类 |
| `:car:api` | `selfdraw/CarSelfDrawBridge.kt` | 新增：桥契约 + 状态 + 通道枚举 |
| `:car:api` | `selfdraw/CarSelfDrawStore.kt` | 新增：持久化契约 |
| `:car:api` | `CarPlatform.kt` | 修改：加 `val selfDraw: CarSelfDrawBridge? get() = null` |
| `:car:core-bridge` | `selfdraw/SharedPreferencesCarSelfDrawStore.kt` | 新增：prefs 落盘（**键名沿用原版**） |
| `:car:core-bridge` | `selfdraw/CarPlaySelfDrawClient.kt` | 新增：订阅导航 → 快照 → 桥 |
| `:car:core-bridge` | `CarPlaySessionBridge.kt` | 修改：随会话 connect/disconnect |
| `:car:core-bridge` | `CarAppServices.kt` | 修改：暴露 `selfDrawStore` |
| `:car:platform-geely` | `selfdraw/CarSelfDrawPainter.kt` | 新增：绘制（箭头 50、间隔 10、两端 40） |
| `:car:platform-geely` | `selfdraw/CarSelfDrawOverlayView.kt` | 新增：叠层 View（1920×1080 缩放） |
| `:car:platform-geely` | `selfdraw/GeelySelfDrawBridge.kt` | 新增：桥实现（**复用副屏窗口**） |
| `:car:platform-geely` | `selfdraw/TextEllipsizer.kt` | 新增：二分截断 |
| `:car:platform-geely` | `GeelyClusterPresentation.kt` | 修改：挂叠层（面板尺寸，不裁到可见高） |
| `:car:platform-geely` | `GeelyClusterPresenter.kt` | 修改：暴露叠层 + `onAttachedCallback` |
| `:car:platform-geely` | `GeelyCarPlatform.kt` | 修改：override `selfDraw` |
| `:car:ui` | `CarUiContract.kt` | 修改：4 个模型字段 + 5 个动作 |
| `:car:ui` | `screens/ClusterSection.kt` | 新增：**原版两个设置页的 1:1 还原** |
| `:car:ui` | `screens/SettingsScreen.kt` | 修改：索引项（条件显示）+ 分区路由 |
| `:car:ui` | `navigation/CarDestination.kt` | 修改：加 `CLUSTER` 分区 |
| `:car:app` | `CarAppController.kt` | 修改：动作实现 + 启动时灌设置 |

### 与原版的**刻意偏离**（都记在这里，别当遗漏）

| # | 原版做法 | DBPlay 做法 | 为什么 |
| --- | --- | --- | --- |
| 1 | 两条输出通道：独立悬浮窗（需权限）优先，Presentation 回退 | **只用 Presentation**（复用副屏窗口） | 副屏窗口本来就因视频流存在；少一个权限 = 少一种"权限没给所以不显示"的故障；也避免两窗抢一屏 |
| 2 | 用户手填仪表输出 ID（`GeelyClusterSafeAreaStore.displayId`） | **自动识别**（非主屏 + 恰好 1920×1080 + 唯一命中） | 本车机那块屏有且只有一块。多屏时 `singleOrNull` 返 null ⇒ 明确报"不可用"，比选错一块安全 |
| 3 | 设置页是手搓 `LinearLayout`（两个 Activity，共 7287 行 smali） | `CarCard` / `CarSettingSwitch` / `CarSettingNumberGroup` 重写 | 用户定案「套用 DBPlay 的 Design Token」；功能与文案 1:1，视觉走 Design Token |
| 4 | 可视化拖拽矩形编辑器 | **精确数字输入**（原版也保留了「手动输入坐标」入口） | 拖拽编辑器属 P2；上车前数字输入已覆盖"挪开不挡仪表"的需求 |
| 5 | 车道条 `laneCardY` 未配置 = 不输出 | 同 | 语义一致，无偏离 |

### 未坐实项（**不要当事实用**，全文见 `.ai/SELFDRAW-REVERSE.md` §11）

1. `ARRIVAL` 触发区间、`UTURN_RIGHT` 的 `0xb5`、`0x00(TUNNEL)` 语义；
2. `GeelySelfDrawOutputWindow` 的 `2038` 含义（所以本 PATCH 不实现独立窗口通道）；
3. `Palette` 明暗色值（在调用点构造，未硬编码在 Palette 类里）；
4. **上游 `CarNavigationState` 没有中文描述字段** ⇒ 自绘 `classify()` 目前
   只走数字码那一支，中文正则那支（更可靠）**待补**。这是与 PATCH-0016
   对齐的下一步（台账 §11.8）。

### 验证

- `:car:api` / `:car:core-bridge` / `:car:platform-geely` / `:car:ui` / `:car:app`
  全模块 `compileDebugKotlin`；
- `:car:core-bridge` 单测：`CarSelfDrawTest`(29) + `CarSelfDrawStoreTest` 全绿；
- **待实机确认**：接上仪表 HDMI2 后，四块区域是否按设定坐标出现；
  转向箭头方向是否与手机一致（字段号未实车抓包）。

### 🔧 修正 1：入口可见性判据（2026-10-07，实车发现）

**实车现象**：设置页**根本没有「仪表导航」这一整行**，用户"进不去配置"。

**根因**：把「平台能力」与「副屏此刻在不在」绑成了一个布尔，形成死锁：

```
想配置副屏 → 得有设置入口
有设置入口 → 得先选中 GeelyCarPlatform
选中 GeelyCarPlatform → GeelyCarPlatform.isAvailable() 要求"当下真的存在那块 1920×1080 非主屏"
```

⇒ **用户正准备接副屏去配置时，入口恰恰不存在。**

| 文件 | 改动 |
| --- | --- |
| `car/api/.../selfdraw/CarSelfDrawBridge.kt` | 新增 `isSupported()`（**能力**，默认 false），与 `isAvailable()`（**状态**）分离 |
| `car/platform-geely/.../GeelySelfDrawBridge.kt` | `override isSupported() = true`（走到这里就是吉利平台） |
| `car/platform-geely/.../GeelyCarPlatform.kt` | `isAvailable()` 改为**只判 `isEcarx()`**，去掉"副屏存在"这条 |
| `car/app/.../CarAppController.kt` | `selfDrawAvailable()` 改判 `isSupported()` |
| `car/ui/.../screens/SettingsScreen.kt` | 入口注释更新（能力判据） |
| `car/ui/.../screens/ClusterSection.kt` | 页内状态一律改用 `status().displayAvailable`，不再误用 `selfDrawAvailable` |
| `car/ui/.../CarUiContract.kt` | `selfDrawAvailable` 的 KDoc 重写为"能力"语义 |

**安全复核**：`startClusterIfPossible()` 有 `if (!presenter.isAvailable()) return` 前置守卫
⇒ 副屏不在时运行时路径安全降级，不会因平台被选中而出错。

**新增防回归测试**：`CarSelfDrawAvailabilityContractTest`（4 例）——用"能力 true/状态 false"
与"能力 false/状态 true"两个 stub 钉死两个判据互不干扰。将来若有人再缠回去，立刻变红。

**教训（值得记）**：拿**易变的状态**去决定**稳定的 UI 可见性**，在某些时刻必然把入口藏起来——
而"用户要用它"的那些时刻往往正是状态为负的时候。能力与状态分开问，各答各的。

---

## PATCH-0020（仪表地图投屏：移植上游 cluster stream 111 通道）

**背景**：DBPlay 先前「仪表导航」只有两条不成立的路：① `GeelyHudBridge` 绑第三方
（高德/百度）`CarPlayHudBridgeService` 发 parcel——车机没装该服务，日志里
`HUD 桥可用性：false`，路本身就是断的；② 自绘箭头卡——车机侧画得出来，但没有真导航数据。

**诊断结论**：DBPlay 是上游 `carlito12345/DiPlay`（GPL-3.0，同 origin）的**阉割版**——
`AirPlaySession.kt` 里 cluster 相关方法被整段删除（上游 1011 行 → 本仓 745 行）。
上游走的是 **CarPlay 协议原生的第二路画面流（stream 111 = `ALT_SCREEN`）**：
iPhone 在 `/info` 里通过 `altScreenURLs` 声明愿意向第二块屏投的内容，
车机把这块流的 Surface 放到仪表屏上，再命令 iPhone「往第二屏投地图」。

**这条路纯用 Android 公开 API**（`TYPE_APPLICATION_OVERLAY` + `SurfaceTexture`），
只需悬浮窗权限，不依赖任何厂商 HUD 服务，也不需要 root/shell。

### 新增/修改清单

| 文件 | 改动 |
| --- | --- |
| `shared/.../airplay/CarPlayClusterDisplay.kt` | **新增**。cluster 流的能力模型（URL 常量、缩放档位、`AirPlayDisplayConfig` 构造）。骨架取自上游，**但把上游的比亚迪 DiLink 标定数据（安全区 16/25/35/36%、缩放档、车标位置）标为「待吉利实测」**，不照抄——那是别的车型的数字 |
| `shared/.../airplay/AirPlaySession.kt` | **补回 cluster 方法**（约 173 行）：`clusterStream` / `setClusterUrl` / `setClusterUiShown` / `clusterContentRoute` / `restoreClusterUrl` / `changeMapZoomLevel`，以及 `ZOOM_DIRECTION_IN/OUT` 常量。逻辑照着上游重写，非逐字复制 |
| `car/api/.../projection/CarPlayClusterProjection.kt` | **新增**。副屏投屏窗口：选副屏（`DisplayManager`）→ 建透明悬浮窗（`TYPE_APPLICATION_OVERLAY`）→ 挂 `SurfaceTexture` → 交给会话绑 stream 111。含「首帧未到 / 无新导航 30 秒」自动收起 |
| `car/api/.../session/CarProjectionState.kt` | **新增**。投屏状态快照（`streamReady/attached/firstFrame/displayId/shown`）与 `summary`——把「卡在哪一步」变成能显示给用户的一句话 |
| `car/api/.../session/CarSessionController.kt` | 契约新增 `projectionState` 属性 + `setProjectionEnabled/toggleProjectionUi/setProjectionContent/zoomProjectionMap`，以及 `attachClusterVideoSurface` |
| `car/core-bridge/.../projection/ClusterProjectionController.kt` | **新增**。把投影窗口与 `AirPlaySession` 串起来：建窗 → 绑 Surface → 发 `setClusterUiShown` |
| `car/core-bridge/.../CarPlaySessionBridge.kt` | 实现上述契约（复用**已有**的 `CarPlayMediaSinkSlot.attachClusterSurface()`，不重造） |
| `car/ui/.../CarTouchMapper.kt` | 新增 `CarThreeFingerGesture`：三指**上滑**切换显示 / **下滑**打开设置。含斜划判定（纵向须占优），避免误触 |
| `car/ui/.../CarUiContract.kt` | `CarUiActions` 加投屏动作；`CarUiModel` 加 `projectionState` |
| `car/ui/.../screens/CarPlayScreen.kt` | 触摸回调里接入三指手势识别 |
| `car/ui/.../screens/ClusterSection.kt` | 仪表页加「地图投屏」卡片：开关、显示/收起、选内容、放大缩小、状态一句话 |
| `car/app/.../CarAppController.kt` | 实现投屏动作 + `refreshModel` 带出 `projectionState` |
| `car/ui/build.gradle.kts` | 加 `testImplementation(libs.junit)`（手势判定是纯函数，必须可单测） |

### 与上游的**刻意偏离**（都记在这里，别当遗漏）

1. **类名与包名不同**：上游 `VehicleMapProjection` / `ClusterVideoTexture` 在 `com.shilapi`；
   本仓按自家分层放在 `car/api`（`com.dbtool.carplay.api.projection`）。**未抄上游代码**，
   只照协议与做法重写，避免 GPL 边界模糊。
2. **不照抄比亚迪标定值**：上游的 `SAFE_AREA_PERCENT(16,25,35,36)`、缩放档 `[100,83,67,125]`、
   车标位置都是**比亚迪 DiLink** 的实测值。吉利 CS11 的仪表布局不同，这些数字**必须实测重标**，
   代码里已标 `TODO(实测)`。
3. **保留旧 HUD 桥**：`GeelyHudBridge`（绑第三方服务）**没有删除**，作为降级路线保留。
   若某天车机真装了对应 HUD 服务，仍能走那条路。

### 未坐实项（**不要当事实用**）

1. **CS11 固件是否放行**往 `displayId=1` 开 `TYPE_APPLICATION_OVERLAY`。副屏本身已挂载
   （`displayId=1 1920×1080`），但能否在其上建窗口只有**上车实测**才知道。
   上游文档原话：「系统仍可拒绝创建投屏窗口」。
2. **iPhone 认不认 `maps:/car/instrumentcluster/map`** 这个第二显示流 URI。
   老 iOS 或地图 App 不认时会黑屏 / 15 秒超时。**只能真机试，模拟不出来**。
3. **三指手势会不会与车机自带手势冲突**（CS11 是否有自己的三指语义）——实车确认。

### 验证

- 编译：`:shared` / `:car:api` / `:car:core-bridge` / `:car:ui` / `:car:app` 全部 `BUILD SUCCESSFUL`
- 单测：**全量 703 用例、0 失败、0 错误**（本次新增 21 例：
  `CarThreeFingerGestureTest` 13 例钉死手势边界，`CarProjectionStateTest` 8 例钉死状态机优先级）
- 出包：1.0.8
- **待实车验证的三件事见上「未坐实项」**——本版**不含**对固件放行与否、iPhone 认 URI 与否的结论。
## PATCH-0022 — 仪表自绘：按反编译修正默认值与画布示意（2026-10-07）

### 为什么改

用户在设置页改了「仪表导航区域」的开关，感觉「不生效」。逐条比对反编译
`GeelySelfDrawStore` / `GeelyClusterSafeAreaEditorView` 后发现**三处默认值与
反编译不一致**，另有**两项原版机制完全没实现**。这些都会让「改了像没改」：

| 项 | 反编译原版（实测） | DBPlay 旧值 | 影响 |
| --- | --- | --- | --- |
| `self_draw_card_enabled` 默认 | **true** | false | 新装用户四个开关全关，开关抽屉打开却什么都不显示 |
| `self_draw_hud_enabled` 默认 | **true** | false | 同上 |
| `self_draw_fullscreen_card_enabled` 默认 | **true** | false | 同上 |
| hud 无存值回落矩形 | **(0, 720, 228, 186)** | (0, 720, 728, 186) | 宽度差 500px，卡片位置肉眼错位 |
| fullscreen_card 无存值回落矩形 | **(1165, 720, 364, 112)** | (0, 0, 728, 186)（代码注释自承认是「合理初值」） | 全屏卡片画在了错误位置 |
| `self_draw_hud_cleared` | 有（true ⇒ hud 置「无」） | **完全没实现** | 用户抹掉 HUD 位置后，下次读盘被默认值顶回来 |
| 画布示意图 | 四色分区 + 安全区描边 + 尺寸文字 | 单色底 + 「车机仪表区」文字 | 看不出各区域在哪 |

### 改了什么

**1. `car/api/.../selfdraw/CarSelfDrawModels.kt`**
- `HUD_DEFAULT`：`728` → `228`（反编译 `load$rect("hud")` 回落值）
- `DEFAULT_FULL_SCREEN_CARD`：`(0,0,728,186)` → `(1165,720,364,112)`（反编译真值）
- `CarSelfDrawSettings` 默认：`cardEnabled` / `hudEnabled` / `fullScreenCardEnabled` 三项
  `false` → `true`（照反编译 `getBoolean(..., true)`）
- 新增 `hudCleared: Boolean = false` 字段（反编译 `self_draw_hud_cleared` 语义）
- 新增画布示意常量：`CANVAS_BG_COLOR` / `CANVAS_VIDEO_COLOR` / `CANVAS_HUD_COLOR` /
  `CANVAS_EXTRA_COLOR` / `CANVAS_VIDEO_HEIGHT` / `CANVAS_HUD_REGION` / `CANVAS_EXTRA_REGION`
- 新增安全区最小尺寸常量 `SAFE_AREA_MIN_WIDTH=300` / `SAFE_AREA_MIN_HEIGHT=200`

**2. `car/core-bridge/.../selfdraw/SharedPreferencesCarSelfDrawStore.kt`**
- 三处 `getBoolean(key, false)` → `getBoolean(key, true)`
- 读/写 `KEY_HUD_CLEARED`

**3. `car/ui/.../screens/ClusterSection.kt`**
- `ClusterPreviewCanvas` 改为**四色分区**，逐条照反编译 `onDraw`：
  - 底 `rgb(25,31,40)`
  - 视频区 `(0,0,1920,720)` `rgb(38,91,55)`
  - HUD 区 `(0,720,728,186)` `rgb(125,42,48)`
  - 第三区 `(1160,720,364,112)` `rgb(39,96,143)`
- 「显示控制」区下方新增**落盘回执行**：改任何开关立刻显示
  `✓ 已保存 · HUD (0,720) 228×186`，未改动时显示 `○ 尚未修改（改动会实时保存）`

**4. `car/ui/.../CarUiContract.kt` + `car/app/.../CarAppController.kt`**
- `CarUiModel` 新增 `selfDrawSavedEcho: String`
- `applySelfDrawSettings` 每次落盘后刷新回执（`buildSelfDrawEcho`）

**5. 测试 `CarSelfDrawTest.kt`**：3 处断言按反编译新真值更新（旧断言断的正是错值）。

### 依据

- 反编译 `GeelySelfDrawStore.java`：键名、默认值、`hud_cleared` 语义、两处回落矩形
- 反编译 `GeelyClusterSafeAreaEditorView.java`：四色分区与绘制顺序
- 反编译 `GeelyClusterSafeArea.java`：`normalized()` 的最小尺寸 300×200

### 未做（等实测）

- 反编译的 `GeelyClusterCalibrationPresentation`（独立的校准浮层）未移植——
  DBPlay 用设置页内嵌预览 + 输入框替代，效果等价。
- 画布示意里第三区画在 `1160`，存的值是 `1165`（原版自身差 5px）。代码**照原版**
  保持两者不一致：示意 1160、存值 1165。

### 验证

- `:car:api` / `:car:core-bridge` / `:car:ui` / `:car:app` 编译全过
- 单测 **703 / 0 失败 / 0 错误 / 0 跳过 / 89 报告**

## PATCH-0023 仪表投屏改用 `Presentation` 通道（修 1.0.8「窗口建了但仪表不显示」）

**现象（1.0.8 实测，2026-10-07 21:19:55，吉利 CS11）**

```
21:19:55.090  addView()                    ← 加窗口
21:19:55.092  root.setView display=id 1    ← 绑到仪表屏
21:19:55.096  Added window                 ✅ WindowManager 说加成功了
21:19:55.097  建窗被拒（固件/权限）        ❌ 同一毫秒，同线程 8110
（之后）      —— 再无任何 Relayout / buffer 提交 ——
```

即：**窗口对象真的建起来了，但系统一个像素都不渲染**，全日志无 FATAL / Exception。

**真因**：仪表那块 HDMI 屏（`displayId=1`）的 `DisplayInfo` 带

```
FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, FLAG_PRESENTATION
```

—— 它被固件标为**受保护内容显示器**。第三方用 `TYPE_APPLICATION_OVERLAY` 悬浮窗
在这类屏上**允许 addView 但静默不渲染**（窗口进得去，图层不分配）。

**正解**：改用 `android.app.Presentation`（Android 官方副屏窗口 API）。

| | 旧（错） | 新（对） |
|---|---|---|
| 开窗 | `createDisplayContext` + `wm.addView(TYPE_APPLICATION_OVERLAY)` | `Presentation(context, display).show()` |
| 依据 | 自拼 WindowManager 参数 | 系统按 `FLAG_PRESENTATION` 屏分类放行 |

两侧权威实现一致：
- 反编译原厂 `io.dushu.car.geely.GeelyClusterPresentation extends Presentation`
  （`GeelyClusterSurfaceOwner.onDisplayChanged()` 里 `new ...().show()`）
- 上游 DiPlay `com.shilapi.xcertplay.ClusterMapPresentation : Presentation`
  （`CarPlayHostActivity` 里 `show()` / 屏失效时重建）

**改动**

- `car/api/.../projection/CarPlayClusterProjection.kt`：整体重写
  - 旧 `ClusterTexture` 内部类去掉，其职责并入新的私有 `ClusterPresentation : Presentation`
  - 窗口属性照上游：`setBackgroundDrawable(ColorDrawable(TRANSPARENT))` +
    `clearFlags(FLAG_DIM_BEHIND)`（透明窗口，不盖原厂仪表底图、不触发副屏调光）
  - `TextureView` 首帧前 `alpha=0`，`onSurfaceTextureUpdated` 首次回调时拉到 1
    （避免黑屏闪现，照上游做法）
  - `onStop()` 里释放 Surface 并回调 `onSurface(null)`，让上层清掉 stream 111
  - 找屏新增 `DISPLAY_CATEGORY_PRESENTATION` 优先（`ClusterDisplayFinder.best`），
    兜底「非主屏里面积最大者」——照上游 `findDisplay`
  - **对外 API 不变**：`attach/detach/close/firstFrame/attached/displayId` 签名保持，
    `ClusterProjectionController` 只改了日志文案
- `car/core-bridge/.../projection/ClusterProjectionController.kt`：
  「建窗被拒」日志改为「`Presentation.show()` 失败」并带上 `display.flags`（十六进制），
  便于下次直接看出是不是安全屏；不再把 `stream type=111 active=false`（iPhone 没开流）
  误报成建窗失败。

**未做 / 未验证**

- 未上车实测。**须实机验证**：① 仪表是否真的出现画面；② `stream type=111` 是否被
  打开（上一份日志里 `active=false`，说明 iPhone 侧没推第二路流，这是**独立**的下一步）。
- 坐标标定（比亚迪 `SAFE_AREA_PERCENT`、`markerPercent`）未移植——那是比亚迪专用，
  对吉利 CS11 无效，须上车标定。当前用「流等比居中」的通用做法。
- 上游 GPL-3.0 代码仅作思路参考，未复制其代码。

**测试**：`testDebugUnitTest` 全过（见当次构建记录）。

## PATCH-0024 修 CarPlay 主屏「左右划动卡顿 + 画质模糊」（照上游 DiPlay 补帧龄丢帧）

**现象**（1.0.9 实测，2026-10-07 21:18~21:22，吉利 CS11）

```
video stats ... maxOutputAgeMs=960                     ← 输出积压 960ms
video stats decodedFps=6.99 / 8.99 / 18.78 / 36.76 ... ← 帧率在 7~45 剧烈抖动
video size accepted codec=video/avc size=1920x1008@60  ← 但声明的是 60fps
```

**真因**

### 卡顿 —— 我们从不丢帧

`drainOutput()` 只**统计**积压，`render` 恒为 true：

```kotlin
maxOutputAgeUs = maxOf(maxOutputAgeUs, System.nanoTime()/1000 - info.presentationTimeUs)
codec.releaseOutputBuffer(index, render)   // 恒 true
```

旧帧一张张排队往屏上画，画面比手指动作晚近 1 秒。上游
`AndroidMediaSink` 有帧龄超时丢帧（`MAX_FRAME_AGE_NS = 250ms`），我们**缺这一层**。

### 模糊 —— 按 60fps 协商，实跑 7~45

按 60fps 告诉 iPhone，车机硬解实际只跑得动 7~45fps（划动时重编码大块区域，
瞬间掉到 7）。iPhone 按 60fps 恒定码率编码 ⇒ 每帧码率被摊薄 ⇒ 画面发糊。

**改动**（`shared/.../media/AndroidMediaSink.kt`，照上游）

1. **输出侧丢帧**：帧龄 > 250ms 的帧不渲染（`fresh` 判定）。丢弃不影响解码器
   参考帧链，代价少一帧、换来不积压。
2. **输入侧丢帧**：等到输入缓冲时帧已超龄（`hasPending` 且帧龄 > 250ms），
   丢当前帧 + 置 `needsKeyFrame` 让手机重发 I 帧。
   **刻意不用上游的 `recover()` 整链重启** —— 车机上重建解码器代价高
   （上一版踩过能力表查询阻塞 10.7 秒），改用既有 `needsKeyFrame` 机制。
3. 新增 `MAX_FRAME_AGE_NS = 250_000_000L` 常量（与上游同值）。
4. `video stats` 日志加 `droppedOut= / droppedIn=` 计数，便于下次日志验证。

**刻意没做**

- **没改握手帧率**（不动 `CarVideoSizeLimiter` / `CarPlayConfigBuilder`）。
  这台车的 `areSizeAndRateSupported(1920,1008,60)` 返回 true（能力表撒谎），
  改协商逻辑风险大且须上车验证。先靠丢帧治**延迟**；帧率等丢帧生效后看实测
  （丢帧后解码器负载下降，帧率可能自己稳下来）。
- **没移植**上游 `VideoDecoderSupport.candidates()` 的解码器按帧率排序（改动面大）。
- 未上车实测。

**测试**：`testDebugUnitTest` 703 通过 / 0 失败（与基线一致）。

## PATCH-0025 「仪表地图投屏」开关纳入设置草稿体系（修「点了不保存、无重连提示」）

**现象**（斌哥报告，2026-10-07）

设置 → 仪表导航 → 开启仪表地图投屏：
- 没有「有未保存的修改 / 保存并重连」提示；
- 点了开关、返回，**不保存**，重进页面开关弹回原状。

**真因（两层）**

### 1. UI 侧：该开关不在草稿体系里

`SettingsScreen` 的会话设置走「草稿 + 保存并重连」：

```kotlin
var draft by remember(model.settings) { mutableStateOf(model.settings) }
// dirty → 提示；onApply = actions.applySettings(draft, reconnect = true)
```

而 `CLUSTER` 是**唯一**没接 draft 的分区：

```kotlin
CarSettingsSection.CLUSTER -> ClusterSection(model = model, actions = actions)
```

投屏开关自己用裸 `remember { }`（无 key ⇒ 离开即丢），也从不通知父级 dirty
⇒ 提示条永不出现。

### 2. 逻辑侧：该设置无处落盘

`CarSessionSettings` **没有** projection 字段；store 也**不读写**它。
`setProjectionEnabled` 只当场建/拆窗口，不写设置 ⇒ 重启/重连无从恢复。

**旁证**：桥里 `projectionWanted` 字段的注释写着「会话重建后用它自动恢复」，
但 `reapplyProjection()` **定义了却从未被调用**（死代码）——机制本为这件事写的，
只是从来没接线。

**改动（6 个文件）**

1. `CarSessionSettings`：新增 `projectionEnabled: Boolean = false`。
2. `SharedPreferencesCarSettingsStore`：`KEY_PROJECTION_ENABLED` + `save()` 写 + `load()` 读。
3. `CarDiagnosticSettingsDump`：dump 里加一行（白名单测试会守这个字段）。
4. `SettingsScreen`：`CLUSTER` 分区传 `draft` / `update`。
5. `ClusterSection`：`ClusterMapProjectionCard` 读 `draft.projectionEnabled`，
   开关回调只 `update(draft.copy(...))` —— 与其它会话设置一致。
6. `CarPlaySessionBridge`：
   - `updateSettings()` 把 `next.projectionEnabled` 同步进 `projectionWanted`（接上死代码）；
   - `onSessionActive()` 调 `scheduleReapplyProjection()`；
   - 新增 `scheduleReapplyProjection()`：按 1 秒间隔试到 20 秒，等 iPhone
     第二路流（111）就绪后自动开窗。因为 `setProjectionEnabled` 在
     `clusterStream <= 0` 时**故意**返回 false（不让透明空窗盖住仪表），
     而第二路流常在会话 active 之后才到，所以必须重试等流。

**刻意没做**

- 不动 `setProjectionEnabled` 作为「即时开/关」的能力（三指上滑等仍用它）。
- 不改自绘设置通道（走「落盘 + 立刻生效，不重连」，语义不同）。

**测试**：`testDebugUnitTest` 703 通过 / 0 失败。
（中途白名单测试红了 1 个 —— 新增存储字段没进 dump，已补；这正是该测试的用途。）


---

## PATCH-0027 — 设置页新增「启动性能优化」（`pm compile -m speed -f`）

**日期**：2026-10-07
**背景（斌哥口径，verbatim）**：
> 「而且感觉都是画面被压缩了，性能不知道，你在设置里面加入一个启动性能优化，
>   启动后执行这条 `pm compile -m speed -f`」

前半句「画面被压缩」= 已定案的模糊根因（面板 1080，握手画布 1008，
纵向压 6.7%）——**本 PATCH 不动它**（那需要推翻车主 10-06 的避让定案，
且仓库里有测试明确要求"改前先问车主"，故另案处理）。
本 PATCH 只做后半句：**加开关 + 执行 `pm compile`**。

**语义**

- `pm compile -m speed -f <本 App 包名>`：让系统把本 App 的字节码**完整
  AOT 预编译**成机器码（speed 模式，全 AOT、不留 JIT/解释器），启动更快、
  运行期更少抖动。`-f` = 强制重编，避免"系统以为编好了"。
- **永久开关**（与「热点常开」同语义）：判据读**落盘值**，开启后**每次
  App 启动**都跑一次。首次实测几十秒，之后系统已编译会很快返回。
- 另有「立即执行一次」按钮，当场跑，便于立即验证（不必重启等下次启动）。
- **需要 root**：`pm compile` 操作 PackageManager 的 dexopt 服务，普通 App
  uid 会被 `Permission Denial` 顶回。复用既有提权通道
  （`AdbShellSession` 的 `adb root` / `su 0`），**不新增任何提权手段**。

**改动（6 个文件）**

1. `CarSessionSettings`：新增 `startupPerfCompile: Boolean = false`
   （默认 false —— 不该在用户没点之前就占掉一次几十秒的开机后台开销）。
2. `CarDiagnosticSettingsDump`：dump 加一行（白名单测试会守这个字段）。
3. `CarAppServices`：新增 `runStartupPerfCompile(packageName)`，
   返回 `(是否成功, 中文说明)`；新增 `PERF_COMPILE_TIMEOUT_MILLIS = 120_000L`。
4. `CarUiContract`：`CarUiModel.perfCompileResult: String` +
   `CarUiActions.runStartupPerfCompile()`。
5. `CarAppController`：`perfCompileResult` 字段 + `perfExecutor`（单线程 daemon）
   + 实现 action + `init {}` 里按落盘开关自动执行。
6. `SettingsScreen`：`CarSettingSwitch("启动性能优化")` +
   `CarPerfCompileRow`（立即执行按钮 + 结果行）。

**踩坑记录**

- `AdbShellRunner.shell(command, timeout)` **只有两个参数**，不做自动提权
  ——按其 KDoc 契约，提权前缀必须由**调用方**按 `probePrivilege()` 的结果
  自己拼（`""` 或 `su 0 `）。首版误写成 `privileged = true` 编译不过，
  已修正为调用方拼前缀。

**刻意没做**

- 不动模糊（画布 1008）那件事：须先获车主批准（有测试守着）。
- 不自动跑全部 App（`pm list packages` 逐个编译）——斌哥选的是"只编 DBPlay 自己"。

**测试**：见本轮 `testDebugUnitTest` 结果。


## PATCH-0028 队列侧帧龄丢弃（修「滑动卡顿」）

**日期**：2026-10-07
**触发**：车主实测反馈「滑动还是好卡」（1.0.10 丢帧补丁未能改善）。
**结论**：PATCH-0024 的丢帧判定写错位置 + 门槛过严，等于从未生效。

### 现场证据（1.0.9 实测日志，吉利 CS11）

```
video stats inputFps=6.79 decodedFps=6.99 queued=8 maxOutputAgeMs=960
video stats inputFps=12.4 decodedFps=12.5 queued=8 maxOutputAgeMs=936
```

- **解码器不慢**：`decodedFps ≈ inputFps`，解码端跟得上。
- **积压在队列**：`queued=8` 是队列容量上限，`maxOutputAgeMs` 长期 900+ms
  ——帧在**队列里**排了近 1 秒才被取走解码，手指划完画面才跟上 = 卡顿。
- **丢帧从未触发**：日志里 `droppedOut` / `droppedIn` 恒为 0。

### 根因

PATCH-0024 把超龄判定放在 `feed()` 里，并带两个几乎无法同时成立的门槛：

```kotlin
val waitingFrames = if (activePump.hasPending) 1 else 0
if (frame.receivedUs < lastPtsUs && waitingFrames > 0) { ... }
```

但**真正积压的是 `VideoWorkQueue`（容量 8），不是 `activePump`**。
帧在队列里排队时 `hasPending` 通常为 false ⇒ 整个分支基本进不去。

### 上游 DiPlay 的做法（同语义，可对照）

```kotlin
is VideoJob.Frame ->
    if (System.nanoTime() - job.receivedNs > MAX_FRAME_AGE_NS) {
        queue.discardFrames()
        recover("video backlog exceeded 250 ms")
    } else feed(job)
```

判据是「**这个 job 在队列里等了多久**」，超 250ms 就整队清掉 + 重建解码链。

### 本包改动

1. `VideoDecodeFlow.kt`：`VideoWorkQueue.Entry` 增加 `enqueuedNs`（入队时刻）；
   `frame()` / `control()` 写入时间戳；新增 `pollStamped()` 带出 `Stamped(value, enqueuedNs, frame)`；
   `poll()` 改为 `pollStamped()?.value`，**保持原契约不变**。
2. `AndroidMediaSink.kt` `run()`：取出帧那一刻判
   `System.nanoTime() - enqueuedNs > MAX_FRAME_AGE_NS`（250ms）：
   超龄 ⇒ 丢弃该帧 + `droppedOutputFrames++` + `needsKeyFrame = true`（让手机补 I 帧，
   否则参考链断掉会长期花屏）；附一次性日志
   `video stale queued frame dropped (waited Nms > 250ms)`。
3. 保留 `feed()` 里原检查作为第二道防线，**未动既有行为**。

**为什么丢帧后要 `needsKeyFrame`**：H.264 帧间有参考依赖，丢掉中间帧后
后续 P 帧解出来是残帧。上游 `recover()` 同理。这是「宁可短暂跳一下，
也不要拖着 1 秒前的旧画面」的取舍。

### 新增测试

`shared/src/test/java/com/shilapi/xcertplay/media/VideoWorkQueueStampTest.kt`
（5 个用例）：钉死「帧必须带入队时刻」「控制项不得当帧算」「旧 poll 兼容」
「空队列返 null」「容量背压不静默丢帧」——这个能力一旦被改掉，
丢帧判定会**静默失效**（表现＝卡但无报错），所以必须用测试守住。

### 刻意没做

- 未调 `MAX_FRAME_AGE_NS`（250ms）本身：上游同值，先按上游走。
- 未动模糊（画布 1008）：仍待车主批准。
- 未改 `VideoWorkQueue` 容量 8：容量是背压设计，不是 bug。

**测试**：`testDebugUnitTest` 708 通过 / 0 失败 / 0 错误（基线 703 + 新增 5）。


## PATCH-0029 在线更新优先走 ADB 静默安装

**日期**：2026-10-07
**触发**：车主需求（原话）：「**DBPlay更新改成优先用ADB安装，失败就退到系统安装器**」。

### 改动前的顺序（问题）

```
① 有 INSTALL_PACKAGES → PackageInstaller 静默装
② 否则               → 系统安装器（弹窗，用户手点）
```

`INSTALL_PACKAGES` 是**系统级权限**，DBPlay 是普通应用**永远拿不到**
⇒ 实际永远走 ②，每次更新都要在车机上手动点一次。

### 改动后的顺序

```
① 有 INSTALL_PACKAGES → PackageInstaller 静默（保留，将来进 priv-app 自动生效）
② ADB 静默安装        ← 新增
③ ADB 失败 → 系统安装器（原行为，兜底不变）
```

### ② 的实现要点

- **复用 DBPlay 自带 adb 客户端**：`AdbShellRunner` 连的是
  `127.0.0.1:<devicePort>`，即**本机 adbd**（不是别的设备）。
- 命令：`<adb> -P 5038 -s 127.0.0.1:<port> shell "su 0 pm install -r -d <apk>"`。
- 前置链：`prepare()` → `startServer()` → `connect()` → `probePrivilege()` 拿
  提权前缀（`""` 或 `su 0 `）；任一环失败即静默退到 ③。
- 吉利 CS11 是 userdebug（`ro.debuggable=1`，`adb root` 可成）⇒ 这条路成立。

### ⚠️ 为什么用 `shellDetached` 而不是 `shell`

装的是**自己**，提交那一刻本进程会被系统杀掉。同步等结果永远等不到，
还会把界面锁在"安装中"。`shellDetached` = 把命令交给远端 adbd 后立刻返回；
成败由**下次启动比对版本号**判定（与 ① 同机制，`prefs.pendingInstallVersionCode`）。

### 文件改动

1. `CarUpdateContract.kt`：`CarInstallOutcome` 加 `ADB_COMMITTED`；
   新增 `CarInstallChannel` 枚举（**声明次序 = 尝试次序**，`PREFERRED = ADB_SILENT`）
   + `CarInstallOutcome.isDelegated()`。
2. `CarApkInstaller.kt`：构造函数注入 `adbRunner: AdbShellRunner?`（可空，单测传 null）；
   `install()` 改为**按 `CarInstallChannel` 顺序驱动**；新增 `installViaAdb()`
   （含 shell 注入防护：APK 路径单引号包裹 + POSIX 转义）。
3. `CarUpdateManager.kt`：注入 `adbRunner` 并传给 installer；`when` 补
   `ADB_COMMITTED -> "正在通过 ADB 静默安装…"`。
4. `CarAppController.kt`：穷尽 `when` 补 `ADB_COMMITTED -> appendLog("已通过 ADB 静默提交安装")`。

### 新增测试

`car/core-bridge/src/test/java/com/dbtool/carplay/bridge/CarInstallChannelOrderTest.kt`
（7 个用例）：钉死「首选 = ADB」「系统安装器是最后一步」「ADB 排在系统安装器之前」
「next() 链不断」「FAILED 不算已委托（否则失败不会退到系统安装器）」。

⚠️ 测试放在 `:car:core-bridge`（已有 junit），**不放进 `:car:api`** ——
后者是零依赖契约模块，见其 build.gradle.kts 注释。

### 刻意没做

- 未删 ① 平台通道：将来若 DBPlay 进了 priv-app 它就自动生效。
- 未加"记住上次用了哪条通道"的缓存：三条路径都便宜，省这点不值得引入状态。
- 未改单测可见性：`AdbShellRunner` 是 `internal`，与 `CarApkInstaller` 同模块，无需放宽。

**测试**：`testDebugUnitTest` 715 通过 / 0 失败 / 0 错误（基线 703 + 0028 的 5 + 本包 7）。


## PATCH-0030 修复仪表投屏「连接成功即黑屏」

**现象**：启动投屏仪表 → 连接成功 → 仪表屏**立刻全黑**。

**根因**（三层叠加，全部实证）：

1. **旧版走了悬浮窗**（1.0.9）：车机日志 `ViewRootImpl[DBPlay Cluster Map]
   Added window` 后 **1ms** 就 `ClusterProjection: 在 displayId=1 上建窗被拒
   （固件/权限）` → 主动放弃。该实现已被 PATCH-0024 前的 `097c122` 换成
   `Presentation`，但**未上车**。
2. **窗口未做整窗透明**：副屏 `Presentation` 默认底色不透明（黑）。上游
   `ClusterMapPresentation.setMapVisible()` 会调 `window.attributes.alpha`，
   DBPlay 两份 Presentation **都没有**（`grep attributes` = 0 命中）。
3. **首帧前的空窗无占位**：`TextureView.alpha=0f` 起步，露出的就是窗口黑底。
   上游用 `waitingLabel`（"等待地图"）填这个空窗，DBPlay 没有。

**补充致命 bug（本补丁一并修）**：

`GeelyClusterPresentation.setVideoVisible()` —— 即**车机实际走的那条链** ——
`grep` 实证**全仓无任何调用方**。`TextureView` 起步 `alpha=0f` 且永不打开
⇒ **画面永远不显示**。另一条链（`CarPlayClusterProjection`）有调，两条不一致。

**改法**：

- `CarPlayClusterProjection.ClusterPresentation`：
  - `window.attributes.alpha` 默认 0（整窗透明，首帧才显形）
  - 新增 `setWindowVisible()`（窗口级 alpha）
  - 新增占位 `TextView`（"正在接收仪表画面…"）
  - `setVideoVisible()` 联动：视频层 alpha + 占位可见性 + 整窗 alpha
- `GeelyClusterPresentation`：同上，**并新增首帧自感知**
  （`onSurfaceTextureUpdated` → 一次性 `setVideoVisible(true)`），
  不再依赖上层记得调。

**依据**：上游 GPL-3.0 `ClusterMapPresentation.kt`
（`setMapVisible` / `setStreamActive` / `waitingLabel`），只照语义重写，
未抄码；上游标识隔离于 `com.dbtool.carplay.*`。

**验证**：`--rerun-tasks` 编译全模块 BUILD SUCCESSFUL；
单测 715 通过 / 0 失败 / 0 错误（703 基线 + 7 安装顺序 + 5 队列帧龄）。
**未实机验证** —— 车机当前不可达（NAS 在有线网段，车机 ADB 在热点网段）。

---

## PATCH-0031 自建热点改为「仅 5 GHz」，并让开机自启脚本同步频段

**背景**：2026-10-07 车主口径 —— **自建热点默认且仅走 5 GHz，频段自动选择与
2.4 GHz 一并隐藏**。此前（2026-09-29）曾按「车机射频只有 2.4 GHz」把候选池
锁在 2.4 GHz，该判定已被推翻：参考实现（`FUCK-Lynk&Co-ADB.apk`）在目标车机上
确实以 `hw_mode=a` + `channel=36` 成功起过热点。

**改法**：

- `CarAdbHotspotContract.CarAdbHotspotDefaults`：
  - `CHANNEL_CANDIDATES` → `CHANNEL_CANDIDATES_5G`（`36 → 40 → 44 → 48`，UNII-1）
  - `DEFAULT_CHANNEL`：`1` → `36`（兜底也留在 5 GHz，否则会写出
    `hw_mode=g` + `channel=1` 这种与口径矛盾的自启配置）
  - `CHANNEL_CANDIDATES_2G` 标 `@Deprecated`：保留常量供回退，不再参与选择
  - 已删除「2.4 GHz 必须始终留在候选池」的旧论述，改为「若要恢复 2.4 GHz
    兜底，把 `CHANNEL_CANDIDATES_2G` 拼到候选表后面即可」
- `CarAdbHotspotBootInstaller.BOOT_SCRIPT`（开机自启脚本）：
  - `hw_mode=g` → `hwModeOfChannel(DEFAULT_CHANNEL)`（由信道推导）
  - 5 GHz 时补 `ieee80211ac=1`（与 `CarAdbHotspotConfig.softap` 同源）
- `CarHotspotResolver`：`selfBuilt -> CarHotspotBand.GHZ_2_4` → `GHZ_5`
  （**这是写死的频段断言**，不改会导致 UI/配置显示 2.4 GHz）
- `CarAdbHotspotPlanner` / `CarAdbHotspotManager`：注释与口径同步
- `CarAdbHotspotChannelTest`：**整份反向重写**（原 9 个断言全在钉 2.4 GHz，
  现改为钉 5 GHz：候选全落 ≥32、两两间隔 ≥4、兜底在 5 GHz 且等于首项、
  2.4 GHz 不得进实际尝试表、VHT 必须与实际尝试信道同源）
- `CarAdbHotspotConfigTest`：注释同步（不断言值，无需改断言）

**已知取舍**（车主要求的）：5 GHz 若在这台车上起不来，**不再有 2.4 GHz 退路**，
热点直接失败。`CarAdbHotspotManager` 会按 4 个信道逐个试，最坏 4 次尝试。

**开机自启**：`CarAdbHotspotBootInstaller` 早已实现完整自启方案
（写脚本 + `init.rc` 服务 + `on property:sys.boot_completed=1` 触发 +
base64 传输 + 实测 touch 判可写），本补丁只改其配置段的频段，机制未动。
⚠️ 该自启**从未在真机成功安装过**（车友实测 17 次 FAIL，根因是可写性判据
正则永不命中——已于 2026-10-07 修为「实测 touch」），**待实机验证**。

**验证**：`testDebugUnitTest` 全模块 BUILD SUCCESSFUL；
单测 **715 通过 / 0 失败 / 0 错误**（与基线一致，无新增/删除测试）。
**未实机验证** —— 车机当前不可达（NAS 在有线网段，车机 ADB 在热点网段）。

---

## PATCH-0032 版本号末位只 +1、不再满 10 进位（`car/app/build.gradle.kts`）

**日期**：2026-10-08　**状态**：✅ 已应用（commit `bc7cfa2`）

**用户口径**（verbatim）：

> 正式版本为1.0.0，以后出包不需要满10递增，举例：1.0.22 1.0.23

**改动**：

- `bumpVersionName()`：删除满 10 进位的 `while` 循环，末位**永远只 +1**。
  - 旧行为：`1.0.9` → `1.1.0`、`1.0.10` → `1.1.0`（末位到 10 就向前进位）
  - 新行为：`1.0.9` → `1.0.10`、`1.0.10` → `1.0.11`、`1.0.99` → `1.0.100`
  - 前两位 `1.0` **只在功能有实质变化时手工改**，不随构建自动跳。
- 删除常量 `MAX_VERSION_PART`（只被进位循环使用）。
- 文件头「四条必须知道的规则」第 4 条注释同步改写（原文写「允许因为进位而
  看起来变短（0.0.29 的下一版是 0.2.10）」，已改为「末位同样只 +1、不进位」）。
- `car/app/version.properties` 设回正式版基线：`versionName=1.0.0` / `versionCode=133`。

**版本号语义不变**：`versionCode` 始终是纯计数器（只 +1、单调递增），是覆盖安装与
新旧比较的唯一依据；`versionName` 只影响展示名与 APK 文件名。

**验证**：

- 逻辑等价测试 7 例全通过：`1.0.0→1.0.1`、`1.0.21→1.0.22`、`1.0.22→1.0.23`、
  `1.0.99→1.0.100`、`1.0.9→1.0.10`（不再进位）、`1.0.10→1.0.11`（不再进位）、
  `1.0.0-beta→1.0.0-beta.1`（非数字段退化路径保持）。
- Gradle 配置阶段（`tasks --all --no-configuration-cache`）exit 0，未消耗版本号。

**未实机验证** —— 规则改动，需下次真实出包时确认产物名。

**同步口径**：本 patch 只改构建脚本，`PATCH-0016` 所记的 `:shared` 上游改动范围不受影响。

---

## PATCH-0033 — 仪表副屏「用户选屏 + 逐块试屏（5 秒自动关）」

**日期**：2026-10-08　**状态**：✅ 已应用（未 commit）

**用户口径**（verbatim）：

> 你选错了屏幕，你先把车机屏幕都列举出来让用户自己选择
>
> 我也不清楚 让用户自己测试 然后5s自动退出

### 为什么要改（现场根因）

用未加固原包 `dbplay_1.1.0_202610081119.apk` 反编译 + 设备
`e3de67df72a0`（VIN `L6TTEST1111111112`）12:02 日志定位：

- 反编译 `ClusterDisplayFinder.best()`：候选 = 系统
  `DISPLAY_CATEGORY_PRESENTATION` 的屏，**从中挑面积最大的那块**
  （`size = 物理宽 × 物理高`）。
- 车上有 3 块屏（HUD / HDMI / 主屏），HDMI 1920×1080 面积最大 → **抓走 HDMI**。
- 日志链：`窗口已建于 displayId=1 name=HDMI 屏幕 1920x1080`
  → `首帧超时（15000ms），关闭投屏窗口` → 主屏 1920×720 白块。
- 旧注释「这块屏有且只有一块」的假设被现场推翻。

### 改动

1. **选屏判据：自动挑 → 用户指定优先**
   - `ClusterDisplayFinder.best(context, requestedId)`：`requestedId >= 0` 时
     **只认用户选的那块**；选不到就返回 `null`（宁可不开窗，也不把白块盖到别的屏上）。
   - 抽出**纯函数** `ClusterDisplayFinder.pickDisplayId(requestedId, available, presentation)`
     + 数据类 `ClusterDisplayInfo`，让判据可在 JVM 单测里跑（不必起 Robolectric）。
   - `all(context)`：列出**所有**有效显示（含主屏），供 UI 原样呈现。
2. **首帧超时 15s → 5s**（`ClusterProjectionController.firstFrameTimeoutMs`）：
   选错屏或手机不推流时，别让空窗挂 15 秒像死机；5 秒收窗让用户立刻换屏重试。
3. **设置里「用户选屏」**：
   - `CarSessionSettings.clusterDisplayId`（-1 = 未选）持久化；
   - 设置页「仪表输出」新增「选择仪表副屏」卡片：一个分段控件列出**全部**屏，
     另加「逐块试屏（5 秒自动关）」按钮弹出 `CarChoiceDialog`；
   - `CarChoiceDialog` 新增可选 `autoDismissMillis`，到点自动 `onDismiss`（默认 null = 旧行为）。
4. **桥接**：`CarPlaySessionBridge.updateSettings` 把 `clusterDisplayId` 同步给
   `ClusterProjectionController`；`CarAppController` 用 `ClusterDisplayFinder.all()` 供候选清单。

### 验证

- `:car:api / :car:core-bridge / :car:ui / :car:platform-geely / :car:app`
  `compileDebugKotlin` **BUILD SUCCESSFUL**（仅 2 条 `width/height` deprecation 警告）。
- 新增 `ClusterDisplayPickTest` **6 例全通过**：用户选定 id 原样返回、选定屏消失返回 -1
  （不退回自动挑）、未选时退回面积最大演示屏、无演示屏退回最大有效屏、
  空列表返回 -1、面积按宽×高。
- 全量 `testDebugUnitTest`：**57 个测试类 / 564 用例 / 0 失败 / 0 错误**（基线 558 + 6）。

### 未做 / 未验证

- **未实机验证** —— 需上车确认「用户选对屏后 5 秒内出画面」。
- **1.1.x 分支未同步**该修法（两分支不同步）；本 patch 只落在 `dev`。
- HUD 屏走 ecarx 私有桥（`HUD 桥可用性：false`），不在 display 体系里，
  所以候选清单**列不到 HUD**，只列系统级屏。

---

## PATCH-0034 — 仪表副屏「关键帧恢复回调缺失 + 首帧被误丢」死锁（`sent=false` → 白块）

**日期**：2026-10-08　**状态**：✅ 已应用（未 commit）

**用户口径**（verbatim）：

> 你结合日志和反编译文件，查看下代码哪里还有问题

**补充口径**：就修 #2+#3+#4（用户选「1」）。

### 为什么改（1.1.0 现场根因，两条独立 bug 叠成死锁）

证据来源：设备 `e3de67df72a0`（VIN `L6TTEST1111111112`）12:01:58~12:02:14 日志
+ 未加固原包 `dbplay_1.1.0_202610081119.apk` 反编译（`/tmp/j110o/sources`）。

**症状链（逐字）**

```
12:01:58.563  仪表屏(altScreen) SETUP stream type=111 dataPort=46179
12:01:59.677  video decoder configured ... waiting for random access
12:01:59.678  video stale queued frame dropped (waited 396680ms > 250ms); queued=8; requesting keyframe
12:01:59.678  video recovery key frame requested sent=false     ← 第 1 次
...           每 2 秒重试，连续 8 次全部 sent=false
12:02:14.531  首帧超时（15000ms），关闭投屏窗口
12:02:14.570  video decoder detached from surface
```

**根因 A：仪表流（111）从未注册关键帧恢复回调**

- 反编译 `CarPlayMediaEngine.java:393`：`if (type == 110) { sink.onVideoRecoveryHandler(...) }`
  —— 只有主屏注册，111 被跳过。
- 反编译 `AndroidMediaSink.java:368-374`：
  `videoRecoveryHandlers[type]` 取不到 → 返回 `false` → 就是日志的 `sent=false`。
- 反编译 `CarPlayMediaEngineKt.java:76`：`mainScreenKeyFrameCommand()` 的
  `params` 为**空 map** —— 不带 uuid 的 `forceKeyFrame` 只作用于主屏，
  所以即便注册上，仪表也拿不到 I 帧。

**根因 B：攥在手里的首帧被「帧龄」判据无限期丢弃**

- `VideoDecoder.run()` 里 `heldFrame` 构造 `VideoWorkQueue.Stamped(it, 0L, true)`
  —— `enqueuedNs` **硬编码 0**。
- 于是 `ageNs = System.nanoTime() - 0` = **开机至今的时长**。
  实机 396680ms ≈ 6.6 分钟，正好是车机 uptime —— 数字对得上。
- `ageNs > MAX_FRAME_AGE_NS(250ms)` **必然成立** → 唯一的随机访问点被反复丢弃
  → 解码器一帧都吃不到 → `waiting for random access` 死等 → 副屏白块。

**两条 bug 的耦合**：丢帧后 `needsKeyFrame = true` 想补一个 I 帧，
而根因 A 让请求恒为 `sent=false` —— 补帧永远不来，形成闭环死锁。

> 注：帧龄丢弃逻辑（PATCH-0024 / 0028）在 1.1.0 出包时**已带上去**
> （反编译 `VideoDecoder.java:183/185/511/516` 逐行可查），
> 所以这是**自家补丁在 altScreen 上的误伤**，不是上游 bug。

### 改动（6 处）

1. **`CarPlayMediaEngine.kt:74`** —— 恢复回调按流类型注册：
   `STREAM_TYPE_MAIN_SCREEN` → `mainScreenKeyFrameCommand()`（不带 uuid，语义不变）；
   `STREAM_TYPE_ALT_SCREEN` → `altScreenKeyFrameCommand()`（带 `ALT_UUID`）；
   其它类型不注册。先取命令、再注册 lambda（`when` 分支里的 `{}` 会被 Kotlin 解析成
   block 而非 lambda，直接写成 `-> { session.sendCommand(...) }` 会编译失败）。
2. **`CarPlayMediaEngine.kt`** —— 新增顶层 `internal fun altScreenKeyFrameCommand()`：
   `params = mapOf("uuid" to AirPlayInfoPlist.ALT_UUID)`。
3. **`AndroidMediaSink.kt`** —— `heldFrame` 的 `Stamped` 改用 `System.nanoTime()`
   作 `enqueuedNs`（原先硬编码 `0L`）。
4. **`AndroidMediaSink.kt`** —— 新增 `internal fun shouldDropStaleFrame(submittedFirstFrame, ageNs, maxAgeNs)`：
   **首帧提交前一律不丢帧**；运行处改为调用它。
5. **`AirPlaySession.kt`** —— 新增 public `requestClusterKeyFrame()`：
   `clusterStream > 0 && sendCommand(altScreenKeyFrameCommand())`，跨模块供车机侧催帧。
6. **`ClusterProjectionController.kt` + `CarPlaySessionBridge.kt`** —— 兜底：
   开窗后**半程仍无首帧**时主动催一次仪表关键帧（只催一次），
   `firstFrameTimeoutMs/2` 触发；轮询周期 1000ms → 500ms；
   桥注入 `requestKeyFrame = { airPlaySession?.requestClusterKeyFrame() ?: false }`。

### 验证

- `:shared:testDebugUnitTest` → **BUILD SUCCESSFUL**。
- `testDebugUnitTest`（全量，7 个模块）→ **BUILD SUCCESSFUL**，
  **97 个测试类 / 759 用例 / 0 失败 / 0 错误**。
- 新增 2 个测试类 **11 例全通过**：
  - `ClusterKeyFrameCommandTest`（5 例）：主屏命令不带 uuid（回归保护）、
    仪表命令带 `ALT_UUID`、仪表命令绝不带 `MAIN_UUID`、两者 opcode 均为
    `forceKeyFrame`、仪表命令无多余键。
  - `StaleFrameDropGuardTest`（6 例）：首帧前超龄不丢、**复现 396680ms 的开机时长
    不再触发丢弃**、首帧后超龄照丢、首帧后未超龄不丢、边界值 `== maxAge` 不丢、
    heldFrame 修复后 ageNs≈0 不丢。

### 未做 / 未验证

- **未实机验证** —— 需上车确认「用户选对屏后 5 秒内真出画面」。
- **#5 HUD 通道未做** —— HUD 走 ecarx 私有桥（日志 `HUD 桥可用性：false`），
  不在 Android display 体系内，本 patch 不涉及。
- 1.1.x 分支未同步本修法。

---

## PATCH-0035 — 失败页热点按钮就地化 + 版式收窄 + 性能优化版块移位

**日期**：2026-10-08　**状态**：✅ 已应用（commit `a4f8b86`）

**用户口径**（verbatim）：

> 按钮尺寸过大，通栏铺满屏幕，页面比例不协调

（另两条调整同期提出：ADB 族失败页要能就地建热点 / 开 ADB；「启动性能优化」版块位置不对。）

### 为什么改

1. **失败页找不到入口** —— ADB 相关失败页原先只提示「去设置-连接-热点服务」，
   要用户自己翻页找。改为：失败页**就地**摆「立即建立热点 + ADB（永久）」
   （复用 `CarHotspotAdbSetupButton`）。
2. **按钮通栏铺满** —— 该按钮自带 `fillMaxWidth()` 且不受上限，摆在状态页之后吃满整屏。
   改为：宽度由**调用方容器**约束 —— 设置页用 `widthIn(max)`，
   失败页槽位收窄到 `contentMaxWidth * 0.42f`。
3. **版块位置** —— 「启动性能优化」从「设置-连接-热点服务」整块移出，独立成
   「设置-启动」分区；热点「建立」与「自愈」两按钮由各占一行通栏改为并排一行（权重 3:2）。

### 改动（4 文件 +212/−47）

- `car/ui/.../components/HotspotAdbSetupButton.kt` —— 去掉自带 `fillMaxWidth()`，宽度交给调用方
- `car/ui/.../components/StatusView.kt` —— 新增 `extraContent` 槽位
- `car/ui/.../screens/HomeScreen.kt` —— 失败页槽位收窄接入
- `car/ui/.../screens/SettingsScreen.kt` —— 设置页 `widthIn(max)` + 性能优化独立分区 + 按钮并排

### 验证

- 全量 `testDebugUnitTest`（7 个模块）→ BUILD SUCCESSFUL，
  **97 个测试类 / 759 用例 / 0 失败 / 0 错误**。（本 patch 为 UI 版式改动，未新增单测。）

### 未做 / 未验证

- **未实机验证** —— 需上车看失败页按钮是否真落位、页面比例是否协调。
- 1.1.x 分支未同步本修法。

---

## PATCH-0036 — APK 输出名补 versionCode

**日期**：2026-10-08　**状态**：✅ 已应用（commit `3853643`）

**用户口径**（verbatim）：

> 构建版本以后 DBPlay+版本号+versionCode+时间戳

### 为什么改

`versionName` 末位是「只 +1、不进位」的人工计数，前两位仅功能大改时才动，
单看版本号分不出两个包哪个新。`versionCode` 是纯计数器、永不回退，
是「覆盖安装 / 新旧比较」的唯一硬依据。

### 改动（1 文件 +19/−3）

`car/app/build.gradle.kts` —— 输出名由 `DBPlay-<versionName>-<yyyyMMddHHmm>.apk`
改为 **`DBPlay-<versionName>-<versionCode>-<yyyyMMddHHmm>.apk`**。
分隔符统一用 `-`（URL 里 `+` 会被解析成空格）。

### 验证

- 产物实测：`DBPlay-1.0.4-137-202610081244.apk` ✅

### 未做 / 未验证

- 无。

---

## PATCH-0037 — 仪表副屏「选屏失效 + 两套实现抢同一块屏」（按反编译原版收敛为单窗口 owner）

**日期**：2026-10-08　**状态**：✅ 已应用（编译 + 单测通过；**未实车验证**）

**用户口径**（verbatim）：

> 目前状态无法选择屏幕和正常投屏，你根据反编译文件帮我修复：C:\Users\Santali\Desktop\CarPlay\GeelyCarPlay-反编译

**定案**（用户回答"用反编译的方案"）：反编译原版只有**一个**窗口 owner
（`io.dushu.car.geely.GeelyClusterSurfaceOwner` + `GeelyClusterPresentation`），
因此**平台层 `GeelyClusterPresenter` 独占副屏窗口**，`:car:core-bridge` 的
`ClusterProjectionController` 不再建窗。

### 根因（四条，全部有实证）

诊断包（appVersion 1.0.4，deviceId `e3de67df72a0`，2026-10-08 14:04 / 14:11 两包）：

1. **主屏混进候选清单** —— `CarAppController` 用 `ClusterDisplayFinder.all()`（**含主屏**）
   当设置页候选源；用户一旦选中主屏，副屏窗口就开在**主屏**上（白块盖住 CarPlay 主界面）。
   反编译原版 `GeelyDisplaySelector.select` 里 `!isDefault` 是**硬条件**。
2. **尺寸硬条件把真副屏挡在候选之外** —— 原判据要求"恰好 1920×1080"，而实车那块
   副屏是 **1024×274**（`Display id 3: HDMI 屏幕 1024x274`）⇒ 用户想选也选不到。
3. **未选屏时自动挑一块** —— 原版是 `singleOrNull`（唯一命中），我们曾"挑面积最大的"，
   是"抓错屏 / 白块"的病根。
4. **两套实现各建一个窗口、抢同一条 ALT(111) 轨** —— 同一次会话里：

   ```
   14:09:17.408 ClusterProjection: projection: 窗口已建于 displayId=3   ← core-bridge
   14:10:25.456 carplay-cluster: 仪表副屏已挂载 displayId=3            ← platform-geely
   14:10:25.490 connecting to surface 0x75142c9010                    ← Surface 被换绑
   14:10:25.494 disconnecting from surface 0x7513ac9010
   ```

   先建的窗从此收不到帧，又因 `firstFrame` 已置位不会触发 5 s 超时，会一直留在屏上。

另有一条**冷启动**缺陷：`settings.clusterDisplayId` 只在 `applySettings()` 里灌给
presenter，`startSession()`（开机/杀进程后重进）没灌 ⇒ 用户上次选好的屏丢掉。

### 改动（9 文件 +386/−172）

| 文件 | 改了什么 |
| --- | --- |
| `car/api/.../projection/CarPlayClusterProjection.kt` | `ClusterDisplayFinder`：新增 `selectable()`（非主屏候选）、`all()` 明确降级为"仅诊断用"；`pickDisplayId(requestedId, availableIds)` 重写为对齐原版的纯函数（**先剔主屏** → 指定则认指定、未指定则仅当**恰好一块**才用，否则 -1）；删掉旧 `presentationDisplays()` / `size()` / `area` / 重复的 `all()` |
| `car/api/.../display/CarClusterPresenter.kt` | 契约新增两个只读状态（带默认实现）：`isWindowAttached()` / `isRendering()` |
| `car/core-bridge/.../CarPlaySessionBridge.kt` | 新增 `platformOwnsClusterWindow`；`projectionState` 在平台独占时改问 `CarClusterPresenter`；`setProjectionEnabled` 平台独占时只汇报不建窗；`toggleProjectionUi` 用桥内 `clusterUiShown`；`scheduleReapplyProjection` 平台独占时**不再空转重试**（消掉那 20 条日志噪声） |
| `car/core-bridge/.../projection/ClusterProjectionController.kt` | 新增 `platformOwnsWindow` 闸门：为 true 时 `start()` 直接拒绝建窗（**结构性封堵**，不依赖调用方自觉）；过时文案修正 |
| `car/platform-geely/.../GeelyDisplaySelector.kt` | `select` 由 `firstOrNull` 收紧为原版的 `singleOrNull`（**唯一命中**语义） |
| `car/platform-geely/.../GeelyClusterPresenter.kt` | 实现 `isWindowAttached`/`isRendering`；接上从未被赋值的 `GeelyClusterPresentation.onFirstFrame`；`start()` 补"声明屏 ≠ 当前选屏 ⇒ 不挂窗"闸门（防"声明了 A 屏、窗口在 B 屏"）；`removePresentation()` 复位首帧态；类注释更新 |
| `car/app/.../CarAppController.kt` | 候选清单 `all()` → `selectable()`；标签去掉"主屏"分支；**`startSession()` 补灌 `clusterDisplayId`**（修冷启动丢选屏） |
| `car/ui/.../screens/ClusterSection.kt` | 选屏从**横向等宽分段控件**改为**纵向整宽可点列表**（候选可达 3~4 块且标签长，等分会截断）；删掉两条错误的"仅支持 1920×1080"约束文案；状态文案不再写死 1920×1080/720 |
| `car/core-bridge/src/test/.../ClusterDisplayPickTest.kt` | 整份重写为 8 例，钉住新判据：主屏必剔除（含"id 与主屏同值也不认"）、指定屏不在 ⇒ -1、多候选未选 ⇒ -1、唯一候选自动用、空表 ⇒ -1 |

### 依据

反编译 `GeelyCarPlay-0.3.2-cs11-routed.apk`（baksmali 逐行读）：

- `GeelyDisplaySelector.select`：`!isEcarx → null`；`singleOrNull { id == p3 && !isDefault && width == 0x780 && height == 0x438 }`。
- `GeelyDisplaySelection`：`canvasWidth=0x780 / canvasHeight=0x438 / videoHeight=0x2d0`（固定源坐标系，与副屏物理分辨率无关）。
- `GeelyClusterSurfaceOwner.onDisplayChanged`：`removePresentation()` → `displayManager.getDisplay(id)` → `new GeelyClusterPresentation(...).show()`；`ALT_TYPE = 0x6f`(111)。
- `GeelyClusterSafeAreaStore.displayId`：缺省 `0x2`；保存侧拒绝负值。

**刻意偏离原版的一处**：去掉 `width == 0x780 && height == 0x438` 尺寸硬条件。
理由是实车副屏是 1024×274（日志实证），照抄会让用户**永远选不到那块屏**。
保留 `!isDefault` 与 `singleOrNull` 两条语义。

### 验证

- `:car:{api,core-bridge,ui,platform-geely,app}:compileDebugKotlin` → BUILD SUCCESSFUL
- `:car:core-bridge:testDebugUnitTest` → **506 例 / 0 失败 / 0 错误**（含新判据 8 例全绿）

### 未做 / 未验证

- **未实车验证**。需要在车上确认：设置页候选只剩非主屏、选中即生效、不再出现两块窗口。
- **未做**：`CarPlayClusterProjection`（core-bridge 那条链的 Presentation）在平台无
  `CarClusterPresenter` 的车机上仍然生效，本机（吉利）已不再走它，但代码保留。
- **未做**：`projectionEnabled` 开关在平台独占窗口时不再决定"要不要建窗"
  （窗口只要选过屏就存在，与原版一致）；它仍通过 `setClusterUrl(url, send = projectionWanted)`
  控制 iAP2 侧是否推地图。UI 文案未改，若实车觉得困惑再调。

---

## PATCH-0038 — 版式与设置项三连修：失败页按钮收窄（真根因）+ 移除「立即执行一次性能优化」+「立即自愈 ADB」改成按钮

- **日期**：2026-10-08
- **状态**：已完成（编译 + 单测通过）；**未出包、未实车验证**
- **用户原话**：
  1. 「**蓝色按钮比例不多，你看看怎么调整**」（附失败页截图）
  2. 「**移除设置-启动-立即执行一次性性能优化**」
  3. 「**设置-连接-无线连接-立即治愈 ADB 这个也改成按钮形式**」（附设置页截图）

### 改动 1 —— 失败页「立即建立热点 + ADB（永久）」通栏（真根因：`widthIn` 根本没生效）

**症状**：按钮 `x=26..1100`（宽 **1075px**），内容列 `contentMaxWidth` ≈ 1840px，
旁边「重试」按钮仅 69px。1.0.4 里已含 PATCH-0035 的收窄改动（`0.30f`），**完全没起作用**。

**根因**（`javap -p -c` 反汇编 `foundation-layout` 的 `SizeNode.measure-3p2s80s` 实证）：
`Modifier.fillMaxWidth().widthIn(max = X)` 写在**同一个节点**上时，
`incomingConstraints.constrain(targetConstraints)` 会把 `X` 反向
`coerceIn(父宽, 父宽)` 拉回父宽 ⇒ **同轴上 `widthIn` 等于没写**。
（`Constraints.constrain(other)` ≈ `other.minWidth.coerceIn(this.minWidth, this.maxWidth)`，
而 `fillMaxWidth()` 已把传入约束锁成 `min = max = 父宽`。）

**修法**：**外层负责铺满、里层负责收窄**（`Box` 下发的约束会把 `minWidth` 放松回 0）：

```kotlin
Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Box(Modifier.widthIn(max = dimens.contentMaxWidth * 0.22f)) { extraContent() }
}
```

上限由 `0.30f` 调为 `0.22f`：那排 `wrapContent` 按钮实测约 225dp，
而「立即建立热点 + ADB（永久）」15 字在 `titleSmall` 下单行约 220dp + 左右 `spaceL` 内边距已超 260dp，
照那排宽度写会逼出省略号；0.22f 本车约 310dp ≈ 那排的 1.35 倍。

**全仓排查**：`fillMax(Width|Size)\(\)[\s\S]{0,40}?\.\s*(widthIn|heightIn)\(` 只命中 3 处，
另两处（`CarScaffold.kt`、`UpdateDialog.kt`）都是**跨轴** `heightIn`，安全，未改。

| 文件 | 改动 |
| --- | --- |
| `car/ui/.../components/StatusView.kt` | `extraContent` 槽位改**双层 Box** + 上限 `0.30f` → `0.22f`；注释整段重写，记录 `constrain` 机制 |
| `car/ui/.../components/HotspotAdbSetupButton.kt` | KDoc 同步（设置页走 `Row` 的 `weight(3f)`；失败页 `0.22f`；补"收窄容器必须与 `fillMaxWidth()` 分属两层"警告） |
| `car/ui/.../screens/HomeScreen.kt` | 调用处注释同步 `0.42f` → `0.22f` |

### 改动 2 —— 移除设置 → 启动 的「立即执行一次性能优化」按钮

移除的只是**手动立刻跑一次**这个入口；**「启动性能优化」开关保留**（它管"每次启动自动执行
`pm compile -m speed -f`"）。只服务这颗按钮的链路一并清理：

| 文件 | 改动 |
| --- | --- |
| `car/ui/.../screens/SettingsScreen.kt` | 删 `CarPerfCompileRow`（组件 + KDoc）；调用处连同 `CarRowDivider()` 删除；`StartupSection` 去掉已无用的 `actions`/`model` 两个形参（调用处同步）；删掉因此失效的 `widthIn` import；顺带**修好一处历史遗留**：`CarAdbHealRow` 的 KDoc 曾被挤成悬空注释，已归位到该函数上方 |
| `car/ui/.../CarUiContract.kt` | 删 `CarUiModel.perfCompileResult`、`CarUiActions.runStartupPerfCompile()`（全仓唯一调用方就是该组件） |
| `car/app/.../CarAppController.kt` | 删 `perfCompileResult` 字段与回填；`runStartupPerfCompile()` 由 `override` 改 **`private`**（仍被 `startSession()` 的"开机自动执行"调用，**实现保留**） |

⚠️ 用户可见反馈**没丢**：自动执行结束时仍弹一次 `notice` 气泡
（"启动性能优化完成 / 未完成：原因"），不会变成静默动作。

### 改动 3 —— 设置 → 连接 → 热点服务：「立即自愈 ADB」改成按钮形式

原先 `CarButtonVariant.Ghost`。查 `ActionButton.kt`：`Ghost -> TextButton`
（无底色、无描边，只有 `primaryText` 颜色）⇒ 渲染出来**就是一行纯文字**，
摆在旁边那颗 `Primary` 实底按钮左边，读起来像"说明文字"而不是可点控件。

⇒ 改 `Secondary`（`OutlinedButton` + `colors.surface` **实底** + 极淡描边），
**不改成 `Primary`**（同屏只允许一个主操作）；
同批尺寸 `Regular`(56dp) → `Large`(68dp)，与左侧 `Primary` 齐平
（`Row` 默认 `verticalAlignment = Top`，两颗**实心**按钮差 12dp 就是两条参差色块）。

| 文件 | 改动 |
| --- | --- |
| `car/ui/.../screens/SettingsScreen.kt` | `CarAdbHealRow` 的 `CarButton`：`Ghost` → `Secondary` + `size = Large`；`CarHotspotActionRow` 的两处 "Ghost" 注释同步 |

### 改动 4 —— 顺带修正 `CarPlayCoreInfo` 上游常量（PATCH-0037 出包时发现）

`car/core-bridge/.../CarPlayVpnConsent.kt` 的上游常量停在 `1.3.1 / 1301 / 3753867`，
而 2026-10-07 同步到的上游基线是 `17c9243`（= 1.3.3 / 1303）。
核实 `17c9243:mobile/build.gradle.kts` 后改为 `1.3.3 / 1303 / 17c9243`，
并扫描全仓确认无其他残留。（正是 §7 明令要挡的坑：反一次 ⇒ APK 嵌旧 SHA 作废。）

### 验证

- `:car:{ui,app,core-bridge}:compileDebugKotlin` → **BUILD SUCCESSFUL**
- `:car:core-bridge:testDebugUnitTest` → **506 例 / 0 失败 / 0 错误**（`--rerun-tasks` 全量重跑）

### 未做 / 未验证

- **未实车验证**：失败页按钮比例、设置页自愈按钮观感、启动页只剩一个开关。
- **未出包**：`car/app/version.properties` 已回退为 `1.0.4 / 137`（版本号属发布提交，
  不在本次代码提交里）。上次出的是 `DBPlay-1.0.5-138-202610081532.apk`（**不含本次改动**）。
- **未做**：`CarAppServices.runStartupPerfCompile()`（真正跑 `pm compile` 的那条）保留不动。

## PATCH-0039 — 仪表自绘导航「按反编译原版逐行对齐」（`0x5201/0x5202/0x5204` 解码 + 绘制层 + 生命周期）

起因：用户要求对已移植的仪表导航做**深度排查**，以
`GeelyCarPlay-反编译/geelycarplay_smali` 为唯一事实来源，逐项对齐，不重做、不凭空加功能，
并把 DBPlay 自造且无保留必要的逻辑清掉。

方法：jadx/CFR 在这套 APK 上不可用，全部结论来自 baksmali 产物**逐寄存器读**。
（`GeelySelfDrawContent$Companion.classify` 的 5 段决策树、`Iap2RouteState.parseRouteUpdate`
的 5 步执行顺序、`GeelySelfDrawStore.load` 的默认值、`Iap2LaneStore` 全文都已逐条核实。）

### 一、解码层（`shared/.../iap2/message/Iap2NavigationMessages.kt`）

| # | 原版事实 | DBPlay 旧实现 | 修法 |
| --- | --- | --- | --- |
| 1 | `0x5202` id：`1` index / `2` **description** / `3` type / `4` **afterRoad** / `8` drivingSide / `9` junctionType / `0xa` angles / `0xb` exitAngle / `0xd` exitInfo | 表是**推断**的，description 与 afterRoad 错位 | 按 `smali:560-700 / 1028-1210` 重填；`maneuverDescription` 从此不再恒为 null |
| 2 | `0x5204` id `1` = **event id** | 读成"选中车道下标"⇒ 整组只留一条 | 保留**整组**（`distinctBy` 拒绝重复 position） |
| 3 | `0x5204` id `2` 组内：`0` position / `1` status / `2` angles / `3` highlight | 大致对，但 highlight 未前置合并 | `(listOfNotNull(highlight) + angles).distinct()`（highlight **前置**，渲染顺序可见） |
| 4 | `Iap2LaneStore.current()` = `if (showing) events[selected] else null` | **没有这道闸门**，收到 `0x5204` 就画 | 移植车道存储：8 组 LRU（不淘汰 selected）+ `selected`/`showing`；`0x5201` id `0x10` 选组（`0xffff` = 无）、id `0x12` 控制显示 |
| 5 | `0x5201` `parseRouteUpdate` **5 步且 3 步 early-return**：source 变更清路→`0x14` 清路→guidanceState（`2` 清/`0` 起宽限/`>0` 取消）→ `ROUTE_REFRESH_IDS` 刷新戳 → 字段遍历 | 无生命周期概念 | 按原版顺序重写 |
| 6 | `END_GRACE_NS = 3 s`、`STALE_ROUTE_NS = 120 s` | 无 | 补上 |
| 7 | `routeActive` 只由 guidanceState / 机动索引驱动 | 语义过宽 | 收紧 |
| 8 | 机动列表字段存在但宽度不足 ⇒ 本帧其余字段丢弃 | 无 | 补上 |
| 9 | `sourceKey` 把「高德/Amap/AutoNavi」「苹果/Apple Maps」折叠 | 无 | 补上（避免改名被当成换路线） |
| 10 | `Iap2IdentificationClient` 声明接收 `0x5204` | 漏声明 | 补 |
| 11 | `routeSource` 是 `0x5201` id `0x13` | — | 新增字段 |

顺带**删掉** DBPlay 自造、原版没有且无人消费的 `CarNavigationState.selectedLaneIndex` /
`lanesVisible`（原版这两个值只活在 `Iap2LaneStore` 里，不对外暴露）。

### 二、快照 TTL（`car/core-bridge/.../CarPlaySelfDrawClient.kt`）

原版 `GeelyNavigationOutput.publish`：`expiresAtMs = routeFreshAtMs + 0x1d4c0`（= **120 s**）。
DBPlay 原为自造的 **8 s** —— 直路上手机一段不发更新就会把还在导航的卡片清掉。
已改 `120_000L`；"导航结束"交给解码层的 guidanceState / 清路标志。

### 三、契约层（`car/api/.../selfdraw/`）

| 项 | 原版 | 旧 DBPlay | 现在 |
| --- | --- | --- | --- |
| `CarSourceRect.HUD_REGION` | `GeelySourceRect.HUD` = `(0,720,728,186)`（**允许范围**） | 缺；误拿 228 宽的存盘回落值当校验边界 | 新增并四处改用 |
| `CarSelfDrawSettings.DEFAULT_CARD` | `(1165,720,364,112)` | `(0,0,728,186)` | 改 |
| `DEFAULT_FULL_SCREEN_CARD` | `(1435,273,364,166)` | `(1165,720,364,112)` | 改 |
| `lanesEnabled` 默认 | `true` | `false` | 改 |
| `CarLane.angles` | 一束角度（`anglesDegrees`） | 压成单个 `Int` | 改 `List<Int>` |
| `CarManeuver.exitAngle` | 有（掉头细分用） | 缺 | 新增 |

**新增 `CarSelfDrawFormat.kt`**（纯 Kotlin，可单测）：把 `distance` / `duration` / `remaining` /
`road` 四个纯文本规则从绘制层下沉。原版口径逐条实测：
`<1000 ⇒ "$m 米"`、否则 `%.1f 公里`；时长**向上取整到分钟**（`0s`/`59s` 都得 `1 分钟`）；
剩余信息 `"剩余" + join`（去全部空白、`分钟`→`分`）；路名回退
`afterRoad.ifBlank { description.ifBlank { currentRoad } }`。

**`CarTurnClassifier` 整 file 重写**（`classify` 五段顺序 + 数字码表逐寄存器实证）：
- `PRE_TYPES = {0,3,5}`、`WAYPOINT_TYPES = {0,5,10,12,24,25,27}`；
- 数字码表：`1/0x14→左`、`2/0x15→右`、`0x16/0x31→左前`、`0x17/0x32→右前`、
  `0x2f→急左`、`0x30→急右`、`0xd/0x34→靠左`、`0xe/0x35→靠右`、`3→直行`、`5→沿路`、
  `0xb→起点`、`4/0x12/0x1a→掉头细分`、`6→环岛`、`7` 或 `0x1c..0x2e→出环岛`、
  `0x13→环岛掉头`、`0xa/0xc/0x18/0x19/0x1b→到达`、else `UNKNOWN`；
- 掉头细分：`exitAngle in 1 until 181 ⇒ UTURN_RIGHT`，无角度才看 `drivingSide == 1`；
- **文本兜底放到最后**（旧实现提到数字码表之前，且对任意 type 都跑 ⇒
  `type=1` 配"向右前方"会被判成 `SLIGHT_RIGHT`）；
- 两处归一化**不同**、不可合并：第 1/2 段 `\s+` 全去（且描述+路名都试），第 3/5 段 `trim()`。

### 四、绘制层（`car/platform-geely/.../selfdraw/`）

| 项 | 原版 | 旧 DBPlay | 现在 |
| --- | --- | --- | --- |
| 圆角 | 唯一来源 `10f` | `16f`（自造） | 改 `10f` |
| 区域顺序 | 卡片 → HUD → 全屏卡片 → extension → 全屏车道条 | 相反，且自造「全屏时不画仪表卡片」互斥 | 按原版重排，去掉互斥 |
| 车道条定位 | `laneCardRect(count, y)` 居中框 + `bottom <= 720` 守卫 | 直接铺满 | 移植 `laneCardRect` |
| 车道条卡片 | `region{}` 裁切平移 + 背景 `alpha 0x4D` | 无 | 补 |
| 车道箭头 | 每条角度一支、`-180..180` 过滤 + distinct、仅 `highlightAngle` 命中者不透明 | 每车道一支、用 `status == 2` 自造高亮 | 改为原版口径，删自造常量 |
| HUD 内紧凑车道行 | `separators=true` + 分隔线（参考空间 228×186） | 无 | 补 `drawHudLaneRow` |
| HUD 区域校验 | `inside(HUD_REGION)` | 缺 | 补（overlay + store + 设置页） |

**设置页真 bug**：`ClusterSection.hudRegionHint` 拿 **228 宽**的 `HUD_DEFAULT` 当校验边界，
而设置页文案写的是「必须位于源矩形 (0,720) **728×186** 内」⇒ 用 `HUD_REGION`。

### 五、明确**不**移植（有据）

- `Iap2LaneStore` 的 `unknownFields` 保留、`validRouteFieldWidths` 全表校验：不影响显示。
- `GeelyFactoryArrows` 是**预置 drawable**（`autosdk_hud_sou*` 按转向 + 左右舵选图），
  DBPlay 是自绘矢量箭头 —— 渲染方式不同，属**视觉保真差异**而非功能缺失；
  `leftHandTraffic` 由 `mirrorForDrivingSide` 等价覆盖，`roundaboutExit` 只用于选图变体。
- `roundaboutExit` 未加进契约：原版只喂给 drawable 选择，DBPlay 无对应字形，加了就是死字段。

### 验证

- `:shared:compileDebugKotlin` / `:car:{api,core-bridge,platform-geely,ui,app}:compileDebugKotlin`
  → **BUILD SUCCESSFUL**
- `:shared:testDebugUnitTest` → **200 例 / 0 失败 / 0 错误**
- `:car:core-bridge:testDebugUnitTest` → **513 例 / 0 失败 / 0 错误**
- 新增/重写用例：`0x5202` id 表、`0x5204` 整组解码、车道闸门（指向/`0xffff`/隐藏/清路）、
  生命周期（source 变更 / `guidanceState` 2 与 0 / `0x14` / 120 s 过期 / 机动索引截断）、
  `CarSelfDrawFormat` 四则、分类器数字码表与文本兜底。

### 未做 / 未验证

- **未实车验证**：车道组是否真按 `0x10`/`0x12` 指示显示、HUD 紧凑车道行观感、
  仪表卡片与全屏卡片的默认位置。
- **未出包**：版本号属发布提交，本次不动。

## PATCH-0040 — 移植 DiPlay v0.2.15：USB 链路加固 +「复用车机热点」前置快速校验

**来源**：`https://github.com/shihabal3amri/DiPlay` @ **v0.2.15**（本地只读 worktree
`../_diplay-0215`，detached `b940efe`）。两工程同源（都出自 `com.shilapi.xcertplay`），
所以研究方式是**逐文件 diff 同名文件**，不是按名字猜。

**范围纪律（用户明确限定）**：只动「复用车机热点」这一模式、USB 连接相关功能、以及
二者必需的底层连接代码。**自建热点 / 局域网连接 / 方控 / 音乐 / 电话 / 仪表导航 /
仪表台封面一律不碰**。

### 一、先说清楚「复用车机热点」到底对应 DiPlay 的什么

用户明确提醒过：**DiPlay 里没有"复用车机热点"这个名字**，不许按名字去套。实际对应关系：

| DBPlay | DiPlay | 机制 |
| --- | --- | --- |
| 「复用车机热点」（`CarHotspotMode.MANUAL`） | `WirelessHotspotMode.MANUAL`（UI 上叫 *Built-in car hotspot*） | **只 attach 已在跑的车机热点，从不 `startSoftAp`** |
| 「局域网连接」（`SAME_LAN`） | `WirelessHotspotMode.EXISTING_WIFI` | 不建任何热点，只通告地址 |
| 「自建热点」（`ADB_HOSTAPD`） | 无直接对应（DiPlay 走 `CarHotspotTethering` 反射 / `CarHotspotAdbFallback`） | 自己把热点拉起来 |

⇒ **Wi-Fi Direct（`WIFI_P2P`）不是「复用车机热点」**，DBPlay 也刻意不用这一形态（上游枚举里
有，二开层的 `CarHotspotMode` 四态里没有）。本次**不移植** `WifiP2p*` / `LocalOnlyHotspot*`。

### 二、🔴 改动面隔离（决定了为什么"不能整段照搬"）

`CarPlayConfigBuilder.toUpstream()` 里 **`MANUAL`、`ADB_HOSTAPD`、`AUTO` 三者全部映射到上游
`WirelessHotspotMode.MANUAL`** ⇒ **「自建热点」与「复用车机热点」共用同一个上游
`ManualHotspotManager`**。所以 DiPlay 对 `ManualHotspotManager` 的那次大重构
（`ManualHotspotInterfaces` / `ManualHotspotReadiness` / `HotspotAddressPolicy` 三拆）
**一旦照搬就会直接动到自建热点**，违反约束 ⇒ **本次不移植**，改为「只加不改、只放宽不收紧」。

### 三、移植了什么

#### 3.1 `CarPlayRuntimeConfig`：热点设置只约束无线会话（DiPlay #124 / #130）

`shared/.../orchestration/CarPlayRuntimeConfig.kt`：`MANUAL` 与 `SAME_LAN` 两个 `require` 块
各加一道 `transport == CarPlayTransport.WIRELESS` 门。DiPlay 原文（commit `732b101`）：

> *"With 'Manual hotspot' selected for wireless but no hotspot name entered, connecting by
> cable crashed at start: the runtime config required manualHotspotSsid whatever the transport,
> although only a wireless session starts the hotspot."*

**DBPlay 同样中招，且更严重**：`hotspotPlan` 在 `CarPlaySessionBridge.buildSession()` 里是
`if (current.isWireless) … else null` ⇒ 有线会话拿到的 `sameLanHostAddress` / `sameLanWifiSsid`
**恒为 null**。于是"设置页停在局域网连接 + 插线连 CarPlay"是**必然失败**的组合：
配置构造即抛 `IllegalArgumentException`，有线会话在碰到 USB 之前就崩了。
现场表现是"以为有线坏了"，实际是一条与有线无关的无线设置挡住了它。

DiPlay v0.2.15 对**三个**块都加了门（`EXISTING_WIFI` / `WIFI_P2P` / `MANUAL`）；DBPlay 只有
`MANUAL` 与 `SAME_LAN` 两个块，两处都加了。

**测试夹具要跟着修**：`SameLanRuntimeConfigTest` 原先用默认 `transport = WIRED` 去断言
"无线侧校验"，加门后必然全挂——那是**夹具失真**（真机上 SAME_LAN 只可能出现在无线会话），
所以把夹具显式改成 `WIRELESS`；"有线会话不该被这些设置挡住"这条不变量另立到
`ManualHotspotConfigTest`。

#### 3.2 USBMUX 分帧器 `UsbMuxFrameBuffer`（新文件，DiPlay 同名）

把原先内联在 `Iap2UsbMuxHost` 里的"攒够 16 字节 → 读长度 → 够长出一帧"抽成纯状态机。
旧实现有两个**只表现为"莫名超时/断流"**的缺陷：

1. iPhone 某些回复（iOS 27 的 VERSION、以及带 payload 的合法 TCP 回复）帧尾**多 4 字节**，
   旧判据 `length < 16 || length > 65536` 撞上后读到荒唐长度 ⇒ 抛
   `Invalid USBMUX frame length`，**整条 USBMUX 链路就地死掉**（DiPlay #100/#114/#298、v0.2.14 #362）；
2. 抛之前不做保留 ⇒ 把**已经读到、本来完好的**后续帧一起吞掉。

新实现**刻意保守**：不扫描、不模糊匹配，只在"上一条回复本身可能有尾随 padding"时才允许
在偏移 0 处跳过**恰好 4 字节**，且跳之前必须确认跳过后的头是合法 MUX/TCP 头（协议号、长度、
TCP 头长、两端端口全合规）或形状完全符合协议 1 诊断载荷的帧；`MAX_PADDING_REPORTS = 4` 防循环。
"拆分到达"的正常帧其头必然在偏移 0，会被第一条判据直接放行，不会被误跳。

⚠️ 相对 DiPlay **加了一个字段** `sequence`（从偏移 12 读 u16）：DBPlay 原先用
`readU16(receiveBuffer, 12)` 取 `nextMuxAcknowledgement`，DiPlay 是 `frame.sequence`，语义相同，
带上字段后调用点才能等价替换。

#### 3.3 USB 请求排队 `UsbRequestQueue` + `UsbReadQueuePolicy`（新文件）

- `UsbRequestQueue`：把"谁是这条连接上唯一的 `requestWait` 使用者"收进一个类；
  API 26+ 走 `connection.requestWait(timeoutMillis)`，API < 26 走 helper 线程 +
  `UsbCompletionReaper`。⚠️ 本工程 `minSdk = 28`，**API < 26 分支当前不会走到**，
  保留是为了把版本差异封在一处，而不是散在两个调用点各写一遍。
- `UsbReadQueuePolicy`：只在 `submit()` **明确返回 false**（而非抛异常、而非改动 buffer 状态）
  时才缩到 `COMPATIBILITY_BYTES = 16 KiB` 重试**一次**，并记住 `successfulLimit`
  （所以每个 pipe 上只发生一次）；还带一条断言 `Rejected USB queue changed its buffer state`。

#### 3.4 `NcmUsbBridge`：Bulk IN 改常驻异步请求（性能修复）

原文注释（DiPlay）：

> *"bulkTransfer() pins its byte[] in a JNI critical section for the whole wait, which blocks
> ART's GC thread flip and, with it, every other USB transfer (**seen as ~0.8 s stalls of video
> and audio**). A timed-out request stays queued, so no data is lost between calls."*

⇒ 改成 `directReadBuffer` + 常驻 `readRequest`（超时后请求仍在队列里，不丢数据）；
`STATUS_READ_TIMEOUT_MILLIS = 1_000` 拆成 `STATUS_POLL_TIMEOUT_MILLIS = 20` +
`STATUS_POLL_INTERVAL_MILLIS = 500L`；`close()` 按 DiPlay 顺序重写
（`cancel` → `interrupt` → `join(20+250ms)` → release → `readRequests.close()` → `connection.close()`）。

#### 3.5 诊断出口接线（USB）

`Iap2UsbMuxHost` / `IphoneUsbHost` / `Iap2UsbSession` / `NcmUsbBridge` 各加一个
`onDiagnostic: (String) -> Unit = {}` 参数（**默认空实现 ⇒ 现有调用方零改动**），
在 `CarPlayController` 三处接到 `debugLog`。补的日志都是"只发生一次、事后无法复现"的类型：
大包 TX（`>512B`）、TCP 纯控制帧、排队退到 16 KiB、排队失败时的 `api=`/`endpoint=`/`timeoutMs=`。

#### 3.6 「复用车机热点」前置快速校验（新文件 2 个）

**补的缺口**：这一支原先**完全不用探测结果**——`CarHotspotResolver.resolve()` 在 2026-10-05
「斌哥口径」后只剩 `explicit()` / `fallback()` 两支，`resolveLegacyProbeAndReuse()` 被标
`@Suppress("unused")` 作废；`buildSession()` 里 `resolve(appContext, current, nativeInfo = null)`
**故意传 null**。所以用户选「复用车机热点」而车机热点关着时，整条链路会走到上游
`ManualHotspotManager` 里空转 `HOTSPOT_START_TIMEOUT_MILLIS`（数十秒），最后抛一句
`Timed out … local hotspot interface was not found`——**用户读不出下一步该做什么**。

- `car/api/.../hotspot/CarManualHotspotPrecheck.kt`（纯逻辑，无 `Context`，可脱机单测）：
  `CarHotspotEnabledState { ON, OFF, UNKNOWN }` + `fromWifiApState(state)`（区间 `10..14`，
  **只有 `13` 是 ON**；`12`=ENABLING 也算 OFF，因为它还没起来）+ `blocker(mode, state, ssid)`
  （**只对 `MANUAL` 生效**；只有 `OFF` 才拦）。
- `car/core-bridge/.../hotspot/CarHotspotEnabledReader.kt`：三个信号从强到弱
  ① 反射 `getWifiApState()` ② 反射 `isWifiApEnabled()` ③ 粘性广播 `WIFI_AP_STATE_CHANGED`
  （`wifi_ap_mode != 1` ⇒ 不表态）。**读不到一律 UNKNOWN 并放行**——DiPlay 原文
  *"null when the firmware hides it (then callers must not block the connection)"*。
  `fromSticky` 抽成纯函数就是为了能脱机测（`Intent` 在纯 JVM 单测里是 stub）。
- `CarPlaySessionBridge.ensureManualHotspotReady()`：插在 `ensureAdbHotspot` **之前**。
  额外一道**旁证**：若 `LocalSoftApReader.read().actuallyRunning` 明确看到一张在跑的热点，
  则不拦——**旁证只用来放行，从不用来拦截**（`LocalSoftApReader.enabled` 用的是
  `enabledByReflection || interfaceName != null` 的宽松判据，不能做拦截；
  这正是 DiPlay 警告的假阳性来源）。

判据用的是二开层自己的 `CarHotspotPlan.mode`，**不是**上游那个被压扁成 `MANUAL` 的枚举——
否则自建热点会被这条检查直接打死。

### 四、明确**不**移植（有据）

| DiPlay 的东西 | 为什么不搬 |
| --- | --- |
| `ManualHotspotManager` 的 TetheringManager 重构（`ManualHotspotInterfaces` / `ManualHotspotReadiness` / `HotspotAddressPolicy`） | **共用上游类**，一动就伤「自建热点」；违反范围约束 |
| `CarHotspotTethering` / `CarHotspotAdbFallback` / `CarHotspotSettings` | 依赖 DiPlay `:shared` 里的 `LocalAdb` + `AdbKeys`，而 DBPlay 的 ADB 栈在 `:car:core-bridge` ⇒ 移植等于跨模块搬迁，且属"起热点"而非"复用热点" |
| `CarPlayBonjour` 的 interface-bound JmDNS 路径（+660 行） | 需新加 `jmdns` 依赖，且**所有无线模式共用**；DiPlay 自己在 v0.2.13 才写了 *"Avoid unused Android NSD and USB-service dependencies"*，方向相反 ⇒ 风险/收益不划算 |
| `HotspotJoin*` 系列 | v0.2.13 实验功能，Android 13+ only，与 DBPlay 的 ADB 路线正交 |
| `WifiP2p*` / `LocalOnlyHotspot*` / `P2p*` | DBPlay 刻意不用这些形态（见 §一） |
| `Iap2FileTransferReceiver` | DBPlay 已有自己的 FileTransfer 实现 |
| `WirelessConnectionProof` / `WirelessStartupPolicy` | 依赖上游 `CarPlayController` 的启动重试环，属"无线整体调度"，不在本次范围 |
| `HotspotInterfaceBssid`（从 link-local 反解 EUI-64） | 纯诊断增益，无行为改变；且 DBPlay 的 ADB 探测已能拿到 BSSID |

### 五、验证

- `:shared` + `:car:{api,core-bridge,platform-android,platform-flyme,platform-ecarx,platform-geely,ui,steering,app}`
  全部 `compileDebugKotlin` → **BUILD SUCCESSFUL**（10 个模块）
- `:shared:testDebugUnitTest` → **210 例 / 0 失败 / 0 错误**（基线 200，+10）
- `:car:core-bridge:testDebugUnitTest` → **525 例 / 0 失败 / 0 错误**（基线 513，+12）
- 新增用例：`UsbMuxFrameBufferTest`（5 条：padding 恢复、正常帧不被误跳、跨读拆帧重组、
  非法长度无资格 padding 时抛、padding 只跳一次防循环）、
  `CarManualHotspotPrecheckTest`（12 条：`10..14` 边界、区间外/`null` ⇒ UNKNOWN、
  `blocker` 只对 `MANUAL`、SSID 指名道姓、逐级降级与"第一个读通的信号胜出"）、
  `ManualHotspotConfigTest` 重写（+5 条：有线忽略未填完的热点/局域网设置、无线仍必须拦）。

### 六、未做 / 未验证

- **未实车验证**（用户要求的两张清单）：复用车机热点 10 项（热点正常启动 / 启动延迟 /
  状态检测 / 接口识别 / 自动建连 / 失败 / 超时 / 断开重连 / 冷启动 / 网络状态变化）、
  USB 8 项（插入 / 权限 / 识别 / 重新枚举 / CarPlay 建链 / 拔出 / 重插 / 异常重连）。
- **未出包**：版本号属发布提交，本次不动。

## PATCH-0041 — 设置页与错误页文案精简（副标题只说"这个开关做什么"）

**车主口径**：「设置页中，现在很多副标题写的太长了……把必须的信息保留，其他不需要的删除。
比如不需要告诉用户某个功能是什么。**要一眼就能读懂这个开关是干什么的**。」「还有错误连接页面。」

### 为什么这件事不只是"好看"

`CarSettingRow` 的 `description` 是 **`maxLines = 3`**（`SettingItem.kt:115`），
而 `CarSettingSwitch` / `CarSettingAction` / `CarSettingInfo` 全都转发到它
⇒ **所有开关副标题超过 3 行会被静默截断**，且截断处不补省略号——
现场观感就是"话没说完"。所以这次精简同时修掉了一批**已经被截断**的文案。

### 删什么 / 留什么

| 删 | 留 |
| --- | --- |
| "这个功能是什么"的定义句 | 开关打开/关闭**分别会发生什么** |
| 实现细节（`Presentation 通道`、`BootReceiver 触发`、`由本机 hostapd 执行`、`DBPlay 会把它发给 iPhone`） | 必填/可选、格式与取值范围、出厂默认值 |
| "本版已不再…"、"后续版本提供"这类版本沿革 | **会导致失败的常见填错**（如"别填成自建热点的密码"） |
| 重复的"永久开关"前缀（后半句已经说了） | 去哪里改（"可在「开发者选项 → ADB 热点自检」查看"） |

### 改动清单

- **`SettingsScreen.kt`**（19 处）：局域网地址 / Wi-Fi 名 / Wi-Fi 密码 / 热点常开 / 允许内置 ADB /
  网卡名 / 自定义分辨率 / HEVC / HEVC 软解 / 当前安全区 / 试听通道 / 媒体缓冲 / 开机自启 /
  选择图片 / 调试日志 / 触摸转发 / 方向盘按键 / USB 主机三档 hint / 隐藏顶栏 / 隐藏底栏 /
  `hotspotModeDescription` 四档（模式选择器的正文）。
- **`ClusterSection.kt`**（11 处）：仪表输出状态、未检测到屏幕、显示通道、源坐标系、
  全屏导航卡片、车道提示、车道条顶 Y、自定义剩余信息、输出权限、仪表地图投屏 subtitle 与状态。
- **`CarErrorText.kt`**（3 条）：`MANUAL` 兜底、LOH 超时、LOH 残留请求。
- **`CarPlaySessionBridge.kt`**（2 条，错误页副标题）：网卡被 foreign AP 占用（原 ~230 字 → ~140 字，
  5 句 → 2 段）、自建热点未建立。
- **`CarManualHotspotPrecheck.kt`**（PATCH-0040 刚加的文案）：去掉与前半句重复的"无法复用它"。

### ⚠️ 改文案时的两条硬约束（都已核对）

1. **`CarErrorTextTest` 钉住子串**，删词就会挂：LOH 超时那条必须含 `系统热点` / `不会回调` /
   `ADB 独立热点` 且**不含** `蓝牙`；LOH 残留那条必须含 `残留` / `本进程`；
   `MANUAL` 兜底必须含 `无法接管车机自带热点`。本次全部保留。
2. 🔴 **`NEED_ADB_MARKERS` 依赖失败文案**（`CarPlayScreenState.kt`）：首页失败页那颗
   「立即建立热点 + ADB」按钮的显隐，靠的是对 `errorMessage` 做字面匹配
   （`提权探测` / `adb root` / `连不上本机 adb` / `ADB 没开` / `ADB 调试` / `既不是 root 也没有 su`）。
   实际命中的是**外层**那句 `自建热点未建立（第 N 步「提权探测（adb root → shell → root）」失败）`，
   而步骤标题在 `AdbShellSession.kt:225`——**本次一个字都没动**，标记位完好。

### 验证

- `:shared` + `:car:{api,core-bridge,ui,app}` `compileDebugKotlin` → **BUILD SUCCESSFUL**
- `:shared:testDebugUnitTest` **210 例 / 0 失败**；`:car:core-bridge:testDebugUnitTest` **525 例 / 0 失败**
  （其中 `CarErrorTextTest` 是这次改动最直接的回归保护）
- 净变化：**−35 行**（删 108 / 加 73），即文案整体变短。

### 未做

- **未实车看版式**：3 行截断是否彻底消失、错误页长文案在 1920x1080 上的折行观感。
- **未出包**：版本号属发布提交，本次不动。

---

## PATCH-0042 — `applicationId` 改为 `io.dushu.car`（占用帆书车机版包名）

**车主口径**：「**包名改成帆书，里面的不该**」（2026-10-08）。
目标：在车机上点「帆书」媒体卡片 / 桌面帆书图标，**直接打开 DBPlay 的 CarPlay 画面**。

### 为什么"改包名"就够了（零代码）

车机把「帆书」这个媒体源**固定映射到包名 `io.dushu.car`**：桌面图标、媒体卡片、
仪表 DimMenu 上点「帆书」时，系统都按**包名**去解析要拉起的组件。

- DBPlay 的媒体卡片本身是靠**伪装帆书**的广播通道喂出来的
  （`:car:platform-ecarx` 的 `FdBookBroadcaster`，action
  `ecarx.intent.broadcast.action.MEDIA_CONTROL_RECEIVER`，宿主
  `com.geely.online.service/com.media.control.MediaControlReceiver`），
  所以卡片上显示的就是「帆书」；点它时车机找的包名是 `io.dushu.car`。
- 原版 `GeelyCarPlay` 之所以"点帆书卡片就打开自己"，**靠的正是它的包名整体就叫
  `io.dushu.car`**（实证：`GeelyCarPlay-反编译/geelycarplay/_manifest.txt` 第 1 行
  `package=io.dushu.car`，launcher 是 `io.dushu.car.CarPlayActivity`），
  **而不是任何代码**——全树 `getLaunchIntentForPackage` 零命中，不存在"拦截点击"的实现。
- ⇒ Android 组件身份 = `(applicationId, 类名)`，包名由 `applicationId` 决定，
  与 Java 包 / `namespace` **无关**；`activity-alias` / `sharedUserId` / split APK
  都**无法**改变包名。所以唯一手段就是改 `applicationId`。

### 改动清单

- **`car/app/build.gradle.kts`**（唯一实质改动）：`applicationId = "com.dbtool.carplay"`
  → **`"io.dushu.car"`**，并在其上方补 26 行注释（映射原理 / 为什么 namespace 不动 /
  两条代价 / 回退办法）。
- **`car/app/README.md`**：`applicationId` 说明同步为 `io.dushu.car`。
- **`car/core-bridge/.../SharedPreferencesCarSettingsStore.kt`**：类注释里的
  `applicationId` 字面量同步（纯注释，不影响 prefs 文件名 `car_ui_settings`）。

### ✅ 为什么"里面的不改"是安全的（已逐项核实）

全工程**没有任何一处写死包名字面量用于运行时**，`applicationId` 只出现在
`car/app/build.gradle.kts` 一行；其余 `com.dbtool.carplay.*` **全部是 `namespace`**
（`api` / `app` / `core-bridge` / `platform-android|ecarx|flyme|geely` / `steering` / `ui`
共 8 个模块，逐一 grep 确认）。运行时需要包名的地方全走动态取值：

- `context.packageName`（更新器、自更新、拉起自己）
- Manifest 占位符 `${applicationId}`：FileProvider authority 自动变
  `io.dushu.car.fileprovider`、AndroidX Startup 自动变 `io.dushu.car.androidx-startup`
- `:car:platform-ecarx` 的 `OneOsKeyInputBridge.DEFAULT_CLIENT_ID = "io.dushu.car"`
  —— 改包名后**反而与真实包名一致**（此前是刻意对齐原版 clientId，不是包名依赖）

> **唯一一处"写死的包名字面量"已复核，无需修改**：
> `car/core-bridge/.../update/CarApkInstaller.kt:245`
> `const val ACTION_INSTALL_RESULT = "com.dbtool.carplay.UPDATE_INSTALL_RESULT"`。
> 但第 138 行发送时是 `Intent(ACTION_INSTALL_RESULT).setPackage(appContext.packageName)`
> —— **收件人限定为本应用自身**（运行时动态取包名），是**应用内私有广播**，
> action 字符串叫什么都能正常自收自发，**不构成包名依赖**。为减小改动面，**保持原样**。
> 其余 `com.dbtool.carplay.*` 命中全部是源码包声明 / KDoc 链接 / `build/` 中间产物，
> 以及 `ADB-HOTSPOT.md` 里的历史日志证据（历史记录，**不该改**）。

### 验证

- `./gradlew :car:app:compileDebugKotlin :car:app:processDebugMainManifest --no-configuration-cache`
  → **BUILD SUCCESSFUL**
- `./gradlew :car:core-bridge:testDebugUnitTest :shared:testDebugUnitTest --rerun-tasks --no-configuration-cache`
  → **BUILD SUCCESSFUL**（`--rerun-tasks` 强制真跑，41 个任务全部 executed，**未受 UP-TO-DATE 假绿影响**）
  - `:car:core-bridge` **531 例 / 0 失败**（54 个测试类）
  - `:shared` **210 例 / 0 失败**（40 个测试类）
  - 合计 **741 例 / 0 失败**
- 核对合并后的清单：
  ```
  <manifest ... package="io.dushu.car" android:versionCode="139" android:versionName="1.0.6">
  ...
  authorities="io.dushu.car.fileprovider"
  authorities="io.dushu.car.androidx-startup"
  ```

### ⚠️ 两条代价（必须知道，已写进 `build.gradle.kts` 注释）

1. **与真帆书车机版同包名** ⇒ 两者**不能共存**：装了本应用就装不了帆书（反之亦然）。
   本车机上**只有 DBPlay、没装真帆书**（车主确认），故当前无冲突。
2. **从旧 `com.dbtool.carplay` 升级必须卸载重装**：包名变了 = 新应用 = 新数据目录，
   **设置会全部丢失** —— 与换签名是同一类代价。回退：改回 `com.dbtool.carplay` 再重装。

### 未做

- **未实车验证**：点「帆书」卡片是否真的打开 DBPlay（需真机 + 卸载重装本包）。
- **未出包**：版本号属发布提交，本次不动。
- **曾考虑并撤销的方案**：`car/fanshu-entry` 独立小 APK（`io.dushu.car` + 一个透传
  Activity 拉起 DBPlay）。因车主明确要"**单包**"（"能直接放在 DBPlay 包里？"）而删除，
  `settings.gradle.kts` 已还原。
- 无。

---

## PATCH-0043 — 仪表选屏「可辨识」+ 无线 CarPlay 蓝牙 iAP2「轮次重试 + 退避」

**日期**：2026-10-08　**状态**：✅ 已应用

**用户口径**（verbatim）：

> 还有它的仪表导航无法使用
> 1和2一起修

（1 = 选屏列表补全；2 = 无线 CarPlay 掉线。）

### 现场证据（VIN `L6TTEST1111111112`，设备 `e3de67df72a0`，App 1.0.6）

同一天 5 次采集，仪表投屏结果：

| 时间 | 选的屏 | 结果 |
|---|---|---|
| 12:02 | `displayId=1` HDMI 1920×1080 | ❌ 首帧超时（15000ms），关闭投屏窗口 |
| 14:04 | 未选屏 | ❌ 等 iPhone 第二路流 20s 放弃（`clusterStream=0`） |
| **14:11** | **`displayId=3` 1024×274** | ✅ **首帧到达，仪表地图可见** |
| 18:16 | `displayId=3` | ✅ 副屏已挂载 |
| 18:36 | — | ❌ 蓝牙 iAP2 先断，无线 CarPlay 整个没起来 |

无线那次的完整链：

```
18:31:15.927 I xcertplay-usb: IAP2 RX [wireless-rfcomm] 0x5702 RequestAccessoryWiFiConfigurationInformation
18:31:15.928 I xcertplay-usb: IAP2 TX [wireless-rfcomm] 0x5703 AccessoryWiFiConfigurationInformation
                              [1] SSID: "Lynk&Co"       ← 18:31 蓝牙是通的
18:35:42.151 W xcertplay-usb: IAP2 CLOSE [wireless-rfcomm]      ← 旧连接被关
18:35:42.500 I xcertplay-usb: wireless RFCOMM attempt=1/1 address=C0:6C:0C:6D:1B:6A
18:35:42.904 W xcertplay-usb: read failed, socket might closed or timeout, read ret: -1
                at BluetoothSocket.readAll(BluetoothSocket.java:767)
                at BluetoothSocket.connect(BluetoothSocket.java:400)
                at CarPlayController.connectBluetoothSocket(CarPlayController.kt:1965)
18:35:42.907 W xcertplay-usb: wireless bring-up failed
```

关键：`attempt=1/1` → 失败**仅 404ms**，远小于 15s 连接超时 ⇒
**不是超时，是 iPhone 把 RFCOMM 拒掉**（旧连接尚未释放）。

### 改动 A — 仪表选屏「可辨识」（`GeelyDisplaySelector.kt`）

现场两块副屏在 `Display.getName()` 里**都叫「HDMI 屏幕」**，旧标签只吐
`副屏 · displayId=N · W×H` ⇒ 用户无从分辨，选错屏。

- `GeelyDisplayDescriptor` 增 `name` / `displayType` / `xdpi` / `ydpi` / `refreshRate`。
  （`getType`/`getXdpi`/`getYdpi` 在本模块 compileSdk 下 Kotlin 解析不到 ⇒
  改走反射 `readIntProperty` / `readFloatProperty`。）
- `labelOf()` 补三类提示：面板性质、物理尺寸 mm、屏名。
- 新增 `panelHint()` / `clusterLikeness()` / `physicalSizeLabel()`。
- `candidates()` **按「像仪表面板」程度排序**（细长矮屏排前）——
  ⚠️ **只排序、不自动选**，`select()` 仍只认用户明确点中的 id。

### 改动 B — 蓝牙 iAP2「轮次重试 + 递增退避」（`CarPlayController.kt`）

- `connectWirelessBluetoothDevices()` 由「每设备跑一遍就放弃」改为
  **`RFCOMM_MAX_ROUNDS = 3` 轮 × 每轮遍历全部候选设备**，
  轮间**指数退避**（`RFCOMM_RETRY_BACKOFF_BASE_MILLIS = 2500L` << round ⇒ 2.5s、5s）。
- 新增 `sleepQuietly()`（中断只恢复标记，不抛）。
- 退避前仍走 `isStaleWirelessRun`，会话取消时不白等。
- 失败信息补 `after N rounds`，日志加 `round=x/3`。

### 改动 C — 副屏可见高不超过面板（同文件）

`specOf()` 由写死 `visibleHeightPixels = 720` 改为 `min(面板高, 720)`。
现场仪表面板是 1024×274，旧实现等于告诉 iPhone「这块屏有 720 高」，比屏本身还高。

### 验证

- `:car:platform-geely:` / `:shared:` / `:car:core-bridge:` / `:car:app:` compileDebugKotlin
  → **BUILD SUCCESSFUL**。
- 单测真值（解析 `build/test-results` XML，非 UP-TO-DATE 假绿）：
  `car/platform-geely` **29 例** · `car/core-bridge` **531 例** ·
  `shared` **210 例** · `car/ui` **13 例** ⇒ **783 例 / 0 失败 / 0 错误**。
- `GeelyDisplaySelectorTest` 新增 5 例：细长矮屏标成「像仪表面板」、dpi 折算物理尺寸、
  `panelHint` 四档、`clusterLikeness` 排序且**不参与自动选**、`specOf` 矮屏可见高=面板高。

### 未做 / 未验证

- **未实机验证** —— 需上车确认：① 选屏列表能一眼认出仪表面板；
  ② 蓝牙被拒后 2.5s/5s 退避重试真能接上；③ `displayId=3` 出图。
- **热点信道读不到**（`Could not read the active hotspot channel from Android public APIs;
  reporting iAP2 channel 0 (auto)`）**本次未改** —— 18:31 那次 `channel=0` 也握手成功了，
  判定**不是掉线主因**，留作后续观察点。
- `HUD 桥可用性：false` 未动（HUD 走车机私有桥，不在 Android display 体系）。
- **未出包**。

---

## PATCH-0044 — 日志降噪：Opus 后端记忆 + 音频模式提示 + 蓝牙 adapter 惰性化

**日期**：2026-10-08　**状态**：✅ 已应用

**用户口径**（verbatim）：

> 你再看看日志还有什么问题
> 1

**背景**：对同一辆车（VIN `L6TTEST1111111112`）的 5 份诊断包做全量 E/W 去重，
筛出 DBPlay 自己的三处「已知且已被正确处理」的反复告警。**三处都不是硬故障**，
但都在淹没真信号 —— 本轮排查时靠人眼过滤了这些行才看到蓝牙 iAP2 断开。

### A. Opus 系统编码器「进程内记忆」（`OpusEncoder.kt`）

```
W xcertplay-usb: Opus encoder fallback: system encoder unavailable:
                 Failed to initialize audio/opus, error 0xfffffffe
```

`0xfffffffe` = ENOENT：该车机（Android 9 / QUALCOMM CS11）的
`MIMETYPE_AUDIO_OPUS` 系统编码器**根本不存在**。上游实现每次建链都重试一遍，
白花一次 `MediaCodec.createEncoderByType` → `configure` → `start`（必失败）+ 刷一行 W。

改：新增 `@Volatile systemBackendKnownUnavailable`。**首次仍真实尝试并如实告警**
（保留「这台车没有系统 Opus」的可观测性），之后本进程内直接走软件后端（Concentus）。
另加 `resetSystemBackendAvailabilityForTest()` 供单测复位，避免用例互相污染。

⚠️ 只在「系统编码器不存在/配置失败」时记忆；**暂时性**失败仍每次重试（`useSoftware()` 路径未动）。

### B. `communication audio mode` 仅提示一次（`MicrophoneSupport.kt`）

```
W xcertplay-usb: communication audio mode unavailable (mode=0); continuing
```

自 PATCH-0007 起已被**有意容忍**（车机钉死 `AudioManager.mode`，拿不到通话模式也继续开录音，
保证 Siri 可用）。但每次麦克风启动都打一行；本轮排查人眼过滤了 10 行。
改为 `AtomicBoolean` 控制，每进程只如实提示一次，文案补「本机音频策略钉死 mode，仅提示一次」。

### C. 蓝牙 adapter 惰性化（`CarPlayController.kt`）

```
W BluetoothAdapter: getBluetoothService() called with no BluetoothManagerCallback   ×19
```

AOSP 在**蓝牙服务未就绪**时打此警告 —— 此时 `BluetoothManager.adapter` 内部 binder 为空，
后续 `bondedDevices` / `isEnabled` 可能返空或再次触发同一警告。旧代码在 `CarPlayController`
**构造时**取值，而控制器每次会话新建：若撞上车机蓝牙栈重启/开机早期，会把「半成品 adapter」
固化进整个会话 → 无线 CarPlay 蓝牙段莫名失败，日志里只剩那行不起眼的警告。

改：`by lazy`（真正用到蓝牙时才取值）+ 进程级只告警一次（`bluetoothAdapterWarningIssued`）。

### 验证

- `:shared:compileDebugKotlin` / `:car:platform-geely:` / `:car:app:` → **BUILD SUCCESSFUL**
- 单测真值（`--rerun-tasks` 全量重跑，解析 `build/test-results` XML）：

  | 模块 | 用例 | 失败 | 错误 |
  |---|---|---|---|
  | `shared` | **214** | 0 | 0 |
  | `car/platform-geely` | **29** | 0 | 0 |
  | `car/core-bridge` | **531** | 0 | 0 |
  | `car/ui` | **13** | 0 | 0 |
  | **合计** | **787** | **0** | **0** |

- 新增 `OpusEncoderTest` 4 例：系统后端可用不告警 / 不可用回退并带原始原因 /
  **记住后不再重试**（核心）/ 复位钩子有效。

### 未做 / 未验证

- **未实机验证**。
- **这三处不是硬故障修复** —— 是降噪 + 省一次无用 MediaCodec 创建。
  现场 Siri/免提通话是否真有断续，日志里**没有证据**，需上车实测。
- 判定为**非 DBPlay 来源**的高噪声项，未动：`BluetoothA2dpSink close`(1154)、
  `BD>PrivateMicrophoneInputStream read data timeout`(~250，百度语音 PID 2857)、
  `Invalid logical cluster id 1`(189，车机性能框架)、`ANDR-PERF-*`、`libipcp`/`gnss`。
- `NAVI_SERVICE_STARTED pkg=ecarx.oem.naviservice not found`(12) 属车机侧，未动。
- **未出包**。

---

## PATCH-0045 — 仪表选屏「点选即时高亮」（读错数据源：`model` → `draft`）

**日期**：2026-10-08　**状态**：✅ 已应用

**用户口径**（verbatim）：

> 我无法选中2，你看看是不是哪里出问题，你看看反编译代码
> 你查看最新日志，能选择屏幕但当时屏幕不会显示选中状态，保存以后再进去才能选中屏幕，地图启动的时候那里黑屏

**背景**：对车 `L6TTEST1111111111`（设备 `67b3c24af6b5`）的诊断包，
`effective_settings.txt` 显示 `clusterDisplayId` **有值（先 2 后 3）** ——
即设置**确实写进去了**，但用户主观感受是「选不中」。用户第二个口径把现象说全了：
**点选当下界面不显示选中态，保存后重进才看到选中**。

**根因（读错数据源）**：设置页是 `draft` / `model` 双层结构 ——
`model` = 已保存的设置，`draft` = 用户正在编辑的草稿。
`ClusterSection.kt` 里渲染「当前选中哪块屏」时读的是 **`model.clusterDisplayId`**，
而点击 chip 只写 `draft`：

```kotlin
onClick = { update(draft.copy(clusterDisplayId = c.displayId)) }   // 只改 draft
...
val selectedId = model.clusterDisplayId   // ❌ 还是老值 → 高亮不动
```

于是「点了没反应」，一保存 `model` 才同步，重进页面才看到选中 —— 与用户描述**逐字吻合**。

**改**：同一段里的 `selectedId` 改读 `draft.clusterDisplayId`（`value` / `description` /
`selected` 三处由它派生，一处改全跟着对）。

⚠️ 这是**纯 UI 状态刷新**问题，与「能不能投屏」无关 —— 别和 PATCH-0046 混为一谈。

---

## PATCH-0046 — 仪表「抢屏 + 被收回自动重开」（DimMenu 反射 + logcat 监听）

**日期**：2026-10-08　**状态**：✅ 已应用（**未实机验证**）

**用户口径**（verbatim）：

> 你查看最新日志，能选择屏幕但当时屏幕不会显示选中状态，保存以后再进去才能选中屏幕，地图启动的时候那里黑屏
> 2

**背景（真车日志的固定组合）**：投屏链路软件侧是通的 —— 挂载成功、解码器配好、
首帧渲染、`decodedFps≈22`。但**画面留不住**：

```
21:18:24  Cluster: 仪表副屏已挂载 displayId=2 面板=1920x1080 可见高=720
21:18:24  video decoder rendered first frame bytes=8
21:20:37  Cluster: 仪表副屏已挂载 displayId=3 面板=1024x274 可见高=274
21:20:42  BufferQueueProducer: cancelBuffer: BufferQueue has been abandoned
21:20:42  SurfaceUtils: disconnecting from surface 0x71c1266010 …disconnectFromSurface
```

同一模式在 21:19:17 / 21:19:44 / 21:20:42 出现**三次**：挂上 → 几十秒内被废。
车机侧证据：`DimMenu[1.0.20251121L]: com.geely.dimmenu.MainActivity: UI_DETECTION
result : MediaControlFragment` —— **车机自己的 DimMenu 占着仪表屏**。

**反编译结论（原版凭什么不黑）**：原版 `GeelyCarPlay` 除了挂 `Presentation`，还有两步
DBPlay 完全没有的环节：

1. **抢屏** —— `GeelyDimModeController` 反射调车机私有 API：
   `Class.forName("com.ecarx.xui.adaptapi.diminteraction.DimMenuInteraction")`
   → `getConstructor(Context)` → `getMethod("switchNaviMode", int).invoke(obj, 1)`
   （`0` = 还回去），另有 `getNaviMode()` 读当前模式。
2. **监听 + 自动重开** —— `GeelyDimModeObserver` 起 logcat 子进程盯
   `DimMenuInteraction:D`，见到 `onModeOff` 就回调上层重开
   （原版日志串：`Cluster: manual reopen sent; waiting for a new video frame`）。
   另有 `markOwnModeOff()` 去抖：我方主动退出后一小段时间内的 `onModeOff` 一律忽略。

**改**（按思路重写，GPL-3.0 上游**不抄码**）：

- 新增 **`GeelyDimModeController.kt`** —— 反射抢屏。⚠️ 原版硬编码的
  `com.ecarx.xui.adaptapi.diminteraction.DimMenuInteraction` 是**亿咖通体系**类名，
  而本车日志里是 `com.geely.dimmenu.*` —— **体系不同，类名不能照抄**。
  故实现为**候选类名逐个探测**（6 个候选，含 ECARX 与 geely 两套），
  探测过程与最终用上的类名**全部写进诊断日志**，便于真车核对；
  全不中则静默失效（退化为「只挂窗口」，**不比起加之前更差**）。
- 新增 **`GeelyDimModeObserver.kt`** —— logcat 子进程（守护线程，tag
  `DimMenuInteraction:D` + `DimMenu:D` 双盯），命中 `onModeOff` → 回调；
  `markOwnModeOff()` + 2.5s 静默窗口去抖。**不写文件、不外发数据**。
- 改 **`GeelyClusterPresenter.kt`** —— `attach()` 挂窗后 `modeObserver.start()` +
  `dim.enterFullNavi()`（日志 `Cluster: 仪表抢屏 entered=…（类=…）`）；
  收到 `onModeOff` → `onClusterTakenBack()` 重开（节流 1.5s，
  日志 `Cluster: 画面被收回 → 重开 entered=…`）；
  `stop()` 里**先** `markOwnModeOff()` 再 `exitFullNavi()` 再 `stop()` 监听
  （顺序错了会把自己退出误判成被抢）。

### 验证

- `:car:ui:compileDebugKotlin` / `:car:platform-geely:compileDebugKotlin` → **BUILD SUCCESSFUL**
- 单测：`shared` **214** + `car/platform-geely` **29** + `car/core-bridge` **531** +
  `car/ui` **13** = **787 例 / 0 失败 / 0 错误**（与基线一致）

### 未做 / 未验证

- **未实机验证** —— 车机私有 API 的**真实类名与签名未知**，本车能否抢到屏要看
  真车日志里 `Cluster: DimMenu 探测 · …` 那几行。
  若 6 个候选全不中，需要按真车日志里 `com.geely.dimmenu.*` 的实际类再补候选。
- 原版的 `forceKeyFrame` / `showUI` / `stopUI`（往 AirPlay 副屏发 `changeMapZoomLevel`）、
  以及 `GeelyClusterModeState` 的完整状态机（`Enter/Exit/None` + `canManualOpen`）**未复刻**，
  只做了「进入 / 退出 / 被收回重开」这条最小闭环。
- **未出包**。

> ⚠️ **本 PATCH 已修正（2026-10-09，见 PATCH-0050）**：本文档上面写的
> `switchNaviMode … invoke(obj, 1)`（`0`=还回去）**是错的**。逐寄存器读原版 smali
> 实证 `enterFullNavi=3` / `exitFullNavi=1`；`onModeOff` 也不是 logcat 匹配串
> （原版匹配 `"switchNaviMode naviMode:1"`）。本 PATCH 的初版实现因此把"抢屏"
> 写成了"关屏" ⇒ 仪表白屏。勘误见 **PATCH-0050**。

---

## PATCH-0047 — 「复用车机热点」无法使用：复用分支透传了「自建用的」5 GHz 频段，被上游硬校验拒绝

**日期**：2026-10-08　**状态**：✅ 已应用（**未实机验证**）

**用户口径**（verbatim）：

> 你根据日志来帮我解决：复用车机热点无法使用的问题

**日志来源**：日志机上 21:24 / 21:26 / 21:32 上传的 3 个包（设备 `67b3c24af6b5`，
车 `L6TTEST1111111111`）—— 这是用户**切到「复用」后实测**的记录，本地此前没有。

**现场证据（决定性，逐行可复现）**：

```
21:22:52  hotspot plan: 复用车机热点「Lynk」（手动指定）
21:22:52  manual hotspot precheck: state=ON effective=ON ssid=Lynk     ← 车机热点确实开着
21:22:52  wireless bring-up generation=1 starting
21:22:52  W wireless bring-up failed
          java.io.IOException: Could not establish MANUAL hotspot:
            Manual hotspot band 2.4 GHz does not match configured band 5 GHz
            at ManualHotspotManager.validateApConfiguration(ManualHotspotManager.kt:197)
            at ManualHotspotManager.start(ManualHotspotManager.kt:110)
            at CarPlayController.startWirelessHotspot(CarPlayController.kt:1771)
```

同一形状在 21:22:52 / 21:22:54 / 21:22:55 / 21:23:40 **反复出现**——
不是偶发，是「复用车机热点」这条路**每一次都死在同一行**。

**根因**：`CarHotspotResolver.explicit()` 里，复用分支把**为自建热点准备的**
频段设置直接透传给了上游：

```kotlin
band = when {
    network != null -> bandOfChannel(network.channel)
    selfBuilt -> CarHotspotBand.GHZ_5
    else -> settings.manualHotspotBand      // ❌ 复用也走到这里 = 出厂默认 GHZ_5
}
```

`manualHotspotBand` 是设置页里给**自建热点**用的（自建走 5 GHz，见
`CarAdbHotspotDefaults.CHANNEL_CANDIDATES`）。而车机自带热点（`Lynk`）跑在
**2.4 GHz** —— 上游 `validateApConfiguration` 拿这两者硬校验，必然不等 ⇒ 抛异常。

⚠️ 设置页那一项在复用模式下**根本没有意义**，却决定了能不能连上。

**改**（`CarHotspotResolver.kt` 的 `explicit()`）：

- `band`：新增 `reuses -> CarHotspotBand.AUTO`。上游判据是
  `expectedBand != ManualHotspotBand.AUTO` 才校验频段
  （`ManualHotspotManager.kt` 的 `validateApConfiguration`）⇒ 传 AUTO 即放行，
  改由**车机热点自己的频段**说了算。
- `channel`：新增 `reuses -> 0`。同理，上游对 `expectedChannel > 0` 的信道
  **同样硬校验**（`channel X does not match configured channel Y`），
  0 = 不指定。

只动 `reuses` 分支，`selfBuilt`（自建走 5 GHz）与 `network != null` 两支**未动**。

### 验证

- `:car:core-bridge:compileDebugKotlin` / `:car:app:compileDebugKotlin` → **BUILD SUCCESSFUL**
- 新增 2 例契约测试（`HotspotManualReuseContractTest`）钉住这两条：
  `复用分支的频段必须是 AUTO 而不是设置里的自建值` / `复用分支的信道为零而不是设置里的值`；
  该类 **11 例 / 0 失败**（原 9 例）。
- **RED/GREEN 交叉验证**（防假绿灯）：把 `HEAD` 版的 `CarHotspotResolver.kt` 与工作区版
  分别按测试同款「剥注释」逻辑解析，断言串在 HEAD **不出现**、在工作区**出现** ⇒
  该测试确实能抓到这次回归，不是永远为真。

### 未做 / 未验证

- **未实机验证** —— 需上车再选一次「复用车机热点」确认不再报 band 不匹配。
- **上游 `security` 硬校验无 AUTO 例外**（`configuration.security != expectedSecurity`
  直接抛）。本次实车两边都是 `WPA2` 恰好相等故能过；若车机热点是 WPA3/OPEN，
  复用仍会被拒 —— **未处理**，待现场确认后再决定。
- **`SAME_LAN` 另有一处独立问题**（本次日志同样暴露）：它通告给 iPhone 的地址是
  `198.18.34.15`（`eth0.11` 有线口的地址，见
  `same LAN mode: no hotspot will be created, advertising 198.18.34.15`），
  走 Wi-Fi 的 iPhone 到不了这个地址 ⇒ 也无法投屏。**本次未动**。
- **未出包**。

## PATCH-0048 — 「局域网连接」无法使用：网卡枚举回落到车机内部保留地址 `198.18.34.15`，iPhone 走 Wi-Fi 永远到不了

**日期**：2026-10-08　**状态**：✅ 已应用（**未实机验证**）

**用户口径**（verbatim）：

> 还有局域网连接也是有一样的问题，你根据我上传的日志去查找潜在的BUG

**日志来源**：日志机上 21:24 / 21:26 / 21:32 上传的 3 个包（设备 `67b3c24af6b5`，
车 `L6TTEST1111111111`）。其中 `1791466003802`（21:26）与 `1791466352235`（21:32）
两包的 `hotspotMode` 先后为 `SAME_LAN`，是用户实测「局域网连接」的记录。

**现场证据（决定性）**：

```
21:25:46  XCExt.session: hotspot plan: 车机在 eth0.11 上拿到地址 198.18.34.15
            （读不到系统在用网络，按网卡枚举推断）；不建热点，iPhone 连同一张网即可直连。
            iPhone 将按 SSID「Lynk」+ 密码关联该网
21:25:46  xcertplay-usb: wireless hotspot backend=Manual hotspot iface=unknown
            host=198.18.34.15 bssid=unavailable band=同一 Wi-Fi channel=0
21:25:46  W wireless bring-up failed
          java.io.IOException: No bonded PHONE_SMART devices found; pair an iPhone and retry
            at CarPlayController.selectWirelessBluetoothDevices(CarPlayController.kt:1927)

（用户随后在车机上配对了 iPhone，21:28:53 起蓝牙侧全通：）
21:28:53  wireless Bluetooth bonded name=华为Mete 90 address=1C:8E:2A:49:29:F6
21:28:53  wireless RFCOMM connected address=1C:8E:2A:49:29:F6 round=1
21:28:53  wireless iAP2 CSM channel opened
21:28:53  iap2 mfi rx=0xaa05 authentication-succeeded
21:28:53  iap2 tx=0x5703 accessory-wifi-configuration
21:28:53  iap2 tx=0x4301 carplay-start-session      ← 会话都发起了
21:28:53  wireless advertise SSID="Lynk" passphraseLength=8 security=WPA_WPA2
            ip=198.18.34.15                          ← 通告的还是这个地址
（然后：）
21:28:53  wireless attach timed out: iPhone did not open the AirPlay
            connection within 40000ms (attempt=1)    ← 40 秒超时，反复重试
```

**关键读法**：蓝牙 / RFCOMM / iAP2 / MFi 认证 / `carplay-start-session` **全部成功**，
**唯独 AirPlay 数据通道永远不开** —— 因为通告给 iPhone 的 `198.18.34.15` 是
车机内部地址，iPhone 走 Wi-Fi **物理上到不了**。除这条超时外全链路无任何报错。

**根因**：`CarSameLanReader.readFromInterfaces()`（**网卡枚举回落**路径）有两个缺陷：

```kotlin
// 修复前
val candidate = Collections.list(interfaces)
    .asSequence()
    .filter { !it.isLoopback && it.isUp }
    .filter { EXCLUDED_PREFIXES.none { p -> it.name.startsWith(p, true) } }
    .mapNotNull { ni -> ... ; if (looksLikeApInterface(ni.name)) null ... }
    .firstOrNull()          // ❌ ① 纯枚举顺序：eth0.11 排在 wlan0 前面就被选中
```

② 全程没有排除 **RFC 2544 保留网段 `198.18.0.0/15`**（IANA 网络互连设备基准测试专用，
车机内部拿它做 VLAN 子接口 / 内部网桥）—— 它**不可能是**用户局域网，通告必死。

首发路径 `readFromActiveNetwork()` 有 `TRANSPORT_WIFI` 过滤（严谨），但本次实测
车机侧**根本没有 Wi-Fi STA**，`findWifiNetwork` 返回 null ⇒ 落到枚举回落 ⇒ 中招。

⚠️ **本次是 2026-10-03 那次坑的重演**：当时代码注释记录的是「上游退回按名字挑网卡，
抓到有线口 `eth0.3`（`host=198.18.34.15`）」—— 同一个地址。那次修的是
**AUTO 复用分支**（加 `info.actuallyRunning`）；2026-10-05 复用分支按用户口径作废时，
这道防护一并被丢掉，于是从「网卡枚举回落」这条路又漏了出来。

**改**（`CarSameLanReader.kt`）：

- 新增 `isBenchmarkReserved(Inet4Address)`：判 `198.18.0.0/15`。
  在**两条路径**上都用：枚举回落里过滤候选；`readFromActiveNetwork()` 也
  `takeUnless` 一次（Wi-Fi 侧若真拿到保留地址，通告出去一样连不上）。
- 新增 `pickSameLanCandidate(candidates)`：**`wlan*`（Wi-Fi STA 侧）优先**，
  没有才退回原顺序。抽成纯函数以便单测。
  ⚠️ 刻意**不一刀切排除 `eth*`**：个别 ROM 的 Wi-Fi 侧网卡就叫 `eth0.x`，
  硬排会把本来可用的配置判死。
- 候选全被过滤后返回 null ⇒ 如实报「车机当前没有连上任何一张可用的局域网」，
  由设置页引导用户在 `sameLanHostAddress` 手填 —— 与本文件既有纪律一致
  （「读不到地址时不静默滑回自建」）。

### 验证

- `:car:core-bridge:testDebugUnitTest` → **BUILD SUCCESSFUL**
- 新增 `CarSameLanReaderContractTest` **6 例 / 0 失败**：
  - `198_18 网段判为保留（含实测到的那一个地址）`（含 `198.18.0.0` / `198.18.34.15` /
    `198.18.255.255` / `198.19.0.0` / `198.19.255.255` 五个边界）
  - `198_18 之外的地址绝不误判`（`198.17.255.255` / `198.20.0.0` / `192.168.1.100` /
    `10.0.0.5` / `172.16.0.1` / `169.254.1.1` / `1.1.1.1`）
  - `有 wlan 时优先选 wlan，即便 eth 排在前面`　← **本次回归的核心**
  - `网卡名大小写不敏感`
  - `没有 wlan 时退回第一个 —— 不误伤 Wi-Fi 侧不叫 wlan 的机型`
  - `候选为空时返回 null（如实报读不到，不编一个地址）`
- RED/GREEN：两个被测函数都是本次**新增**的，修复前的源码里不存在 ⇒ 这些断言在
  修复前**不可能通过**，不存在「永远为真」的假绿灯。

### 未做 / 未验证

- **未实机验证** —— 需上车再选一次「局域网连接」。
- ⚠️ **SAME_LAN 的适用场景与用户当前用法可能不符**：本类的语义是「车机**作为客户端**
  连着别人 AP 的那张网」（见文件头注释），需要车机与 iPhone **连同一张第三方 Wi-Fi**。
  而实测中 iPhone 是被要求去连 `Lynk`，`Lynk` 又是**车机自己起的**热点
  （21:22 复用模式探测：`manual hotspot precheck: state=ON effective=ON ssid=Lynk`）
  —— 那属于「**复用车机热点**」（`MANUAL`）的场景，走 SAME_LAN 结构上就不对。
  **2026-10-08 用户已确认**：`Lynk` 就是**车机自己起的热点**，且要求**三条路都能走**
  （自建 / 复用 / 局域网）。⇒ SAME_LAN 必须扩展成「**通告本机 AP 侧网关地址**」：
  这种场景下车机不是任何 Wi-Fi 的客户端，`readFromActiveNetwork` 必然返回 null，
  只能落到 `readFromInterfaces()` —— 本次修复的 **`wlan*` 优先**恰好让这条路走通
  （车机开热点时 `wlan0` 承载 AP，实测日志：`wlan0 … inet addr:192.168.175.1`）。
- ⚠️ 剩余未知：车机开 **`Lynk`** 时 `wlan0` 的地址是否也落在 RFC2544 保留段。
  若落在，会被本次的排除规则挡掉 ⇒ 读出「读不到」。为此在 `summary` 里加了
  **网卡账本**（形如 `eth0.11/198.18.34.15=RFC2544保留跳过, wlan0/192.168.x.1=候选`），
  下一次实车日志一眼即可定位，不必再猜。
- **未出包**。

---

## PATCH-0049 — 仪表黑屏：窗口短路判据只看「窗口对象还在」，不查通道是否已失效（重连把死通道再交一次）

**用户报障（2026-10-08，VIN `L6TTEST1111111113`）**：「刚才刚传了日志，仪表投屏还是黑色屏。」

**日志实证（设备 `bbc40f4ddd25`，22:52–22:56）**：

| 时刻 | 事实 |
|---|---|
| 22:55:05 | `Cluster: 仪表副屏已挂载 displayId=2 面板=1920x1080 可见高=720` |
| 22:55:05 | `Cluster: DimMenu 探测 · com.ecarx.xui.adaptapi.diminteraction.DimMenuInteraction → 可用（将用它抢屏）` |
| 22:55:05 | `Cluster: 仪表抢屏 entered=true` ← **抢屏成功** |
| 22:55:08 | 副屏解码器（tid 17393）`decodedFps=38.0` ← **画面真的出来了** |
| 22:55:27 | `BufferQueueProducer: [SurfaceTexture-0-17041-0] cancelBuffer: BufferQueue has been abandoned` |
| 22:55:28 | 副屏解码器**最后一次**出现，此后只剩主屏那一路 |
| 22:56:22 | 重连 —— 日志里**再没有任何 `Cluster:` 行**：没挂载、没重建、没报错 |

⇒ **不是「没挂上」，是「断掉之后再也没接回来」**。

**根因（代码）**：`GeelyClusterPresenter.start()` 的短路分支

```kotlin
// 修复前
if (selected.id == currentDisplayId && presentation != null) {
    presentation?.let { onSurfaceAvailable(it.currentSurfaceOrNull()) }
    return   // ← 只认「窗口对象还在」，不认通道死没死
}
```

通道被系统回收后 `GeelyClusterPresentation` 里的 `TextureView` 壳子仍在，
`presentation` 引用也就还在 ⇒ 短路成立 ⇒ 重连时把一个**已失效的 Surface**
又交给 ALT(111) ⇒ 手机往死通道推流 ⇒ **仪表长期黑屏**。

`CarAppController.startClusterIfPossible()` 的注释本身就把这个假设写明了：
「幂等：重复调用只会重建同一个窗口（**presenter 内部对比 displayId 后短路**）」。

**修法（两道保险）**：

1. **短路判据加「通道有效」**（`GeelyClusterPresenter.start()`）：
   `surface != null && surface.isValid` 才复用，否则落到 `attach()` 重建。
   并把两种结果都打日志（`复用现有副屏窗口 displayId=…（通道有效）` /
   `现有副屏窗口通道已失效（…）—— 丢弃重建`）—— 修复前这里是**静默**的，
   真车日志连一行都看不到，这正是本次排查绕远路的原因。

2. **通道销毁即丢窗**：`GeelyClusterPresentation` 新增 `onSurfaceInvalidated`
   回调（在 `onSurfaceTextureDestroyed` 里除 `publish(null)` 外再发一次「窗口废了」），
   `GeelyClusterPresenter.onWindowInvalidated()` 收到后
   `exitFullNavi()` + `modeObserver.stop()` + `removePresentation()`（内部 `release()`
   会 `dismiss`，不留残窗），让下一次 `start()` 走完整重建。
   - 守卫 `if (stopped || currentDisplayId != displayId) return`：主动 `stop()` 拆窗时
     `dismiss()` 也会触发同一回调，避免重复走一遍；也防上一块屏的迟到回调拆掉新窗。

**未做 / 未验证**：
- **未实机验证** —— 需上车再走一次「启动地图」路径（用户口径：一启动地图就黑）。
  下次日志应看到 `Cluster: 现有副屏窗口通道已失效…—— 丢弃重建` 或
  `Cluster: 复用现有副屏窗口…（通道有效）`，即可确认判据生效。
- ⚠️ **另一处未修**：`/info` 里 type=111 声明尺寸写死 `1920x1080` +
  `safeArea=1865x660`（真车日志同现），与副屏实际可见高 720 不一致；
  与本次黑屏无关，但会让手机画面偏小/留边，另行处理。
- **未出包**（本轮仅改代码 + 单测）。

---

## PATCH-0050 — 仪表**白屏**：`switchNaviMode` 的值域搞反（把「抢屏」写成了「关屏」）+ logcat 匹配串错 + 缺 `modePoll`

**日期**：2026-10-09　**状态**：✅ 已应用（**未实机验证**）

**用户报障**：「这是最新的日志，仪表盘变成白屏了。」（`日志/1791510147443_.../`）

**修正对象**：**PATCH-0046**（那个 PATCH 的初版实现基于**猜测**）。本 PATCH 是
**逐寄存器读原版 smali 后的勘误**，不是新增功能。

### 一、三个根因（全部有 smali 依据，不是推断）

| # | 症状 | 我们的实现（错） | 原版 smali 实证（对） |
|---|---|---|---|
| 1 | 一"抢屏"画面反而**消失**、仪表变白 | `enterFullNavi()` = `switchNaviMode(0x1)`；注释还写 `0=还回去` | `GeelyDimModeController.smali`：**enter = `0x3`**，exit = `0x1` |
| 2 | 听不到车机收回 | 匹配 `"onModeOff"`（那是**我们自己 Kotlin 里的字段名**，logcat 里根本不会有） | `GeelyDimModeObserver.smali` line 264：匹配 **`"switchNaviMode naviMode:1"`** |
| 3 | 被收回后**不自动重开** | 没实现轮询 | `GeelyClusterSurfaceOwner$modePoll$1.smali`：**1500ms（`0x5dc`）** 定时反射读 `dim.currentNaviMode()`；若 `全屏地图激活 && currentNaviMode()==1` ⇒ 判定被车机收回 |

### 二、真车日志锁死根因 1

```
09:40:51.276  DimMenuInteraction: switchNaviMode naviMode:1
09:40:51.450  DimMenuInteraction: onChangeNaviMode:1     ← 车机回显：当前=1
09:40:57.820  DimMenuInteraction: onChangeNaviMode:3     ← 车机自己切回全屏地图
```

我们**以为**在抢屏，实际发出的 `naviMode:1` 在车机侧语义 = **NAVI_MODE_OFF（关掉地图）**
⇒ 车机把仪表让给仪表原生界面 ⇒ **白屏**。7 秒后车机看用户在看地图，又自己切回 `3`。
**每一次"抢屏"都是一次"关屏"** ⇒ 观感上就是画面反复丢。

### 三、值域定案（反编译 `GeelyDimModeController.smali`）

| 常量 | 值 | 语义 |
|---|---|---|
| `NAVI_MODE_OFF` | **1** | 关掉全屏地图（还回仪表原生界面） |
| `NAVI_MODE_FULL_MAP` | **3** | 全屏地图（**这才是"抢屏"**） |

> ⚠️ 这两个常量**必须同时钉住**：只改匹配串（第一轮报告的建议）会变成
> **自己发的 `naviMode:1` 触发自己的监听 ⇒ 无限重开风暴**，比原状更糟。
> 三处必须一起改。

### 四、改（三文件 + 一测试）

1. **`GeelyDimModeController.kt`**
   - `enterFullNavi()` = `switchNaviMode(NAVI_MODE_FULL_MAP)`（**3**）
   - `exitFullNavi()` = `switchNaviMode(NAVI_MODE_OFF)`（**1**）
   - 新增 `internal companion object { NAVI_MODE_OFF = 1; NAVI_MODE_FULL_MAP = 3 }`（**可单测**）
   - `readNaviMode()` 改 `(invoke(obj) as? Number)?.toInt()`（同时容忍 `int`/`Integer`/`Number`）
   - 类 KDoc 补 smali 依据

2. **`GeelyDimModeObserver.kt`**
   - 匹配串 → `"switchNaviMode naviMode:1"`（提取为 `MATCH_NAVI_MODE_OFF` 常量）
   - logcat 参数对齐原版：`logcat -T 1 -v raw DimMenuInteraction:D *:S`（`*:S` **必须最后**）
   - 去抖 `OWN_OFF_QUIET_MS`：`2500L` → **`1500L`**（对齐原版 `0x5dc`）
   - 诊断文案：`Cluster: 监听到车机收回仪表（switchNaviMode naviMode:1），准备重开画面`

3. **`GeelyClusterPresenter.kt`**（补 **根因 3**）
   - 新增 `modePoll` Runnable（**1500ms**）+ `mainHandler`（主线程 Looper）
   - 新增 `seizeCluster(source)`：带 `suppressReopenUntil` 抑制窗口；
     **抢屏失败则抑制 10s 重开**（`REOPEN_SUPPRESS_MS = 10_000L`）—— 防止失败时每 1.5s 打一次
   - 新增 `pollNaviModeOnce()`：`if (stopped || presentation == null || !rendering) return`；
     读到的 mode == `NAVI_MODE_OFF` ⇒ `onClusterTakenBack("poll")`
   - `onClusterTakenBack(source: String)` 加 source（`"logcat"` / `"poll"`，日志可区分谁先动的手）
   - `attach()` 起轮询；`stop()` / `onWindowInvalidated()` 停轮询；`stop()` 复位抑制计数

4. **新增 `GeelyDimModeValuesTest.kt`** —— 钉死值域（**回归防线**）：
   - `进入全屏导航 = 3`
   - `退出全屏导航 = 1`
   - `进入 与 退出 不能相等`

### 五、验证

- `:car:platform-geely:compileDebugKotlin` → **BUILD SUCCESSFUL**
- `:car:platform-geely:testDebugUnitTest` → **BUILD SUCCESSFUL**
- `:car:core-bridge:testDebugUnitTest` → **BUILD SUCCESSFUL**（1m27s）

> 过程中踩到 **KDoc 禁 `*/`**（pitfall §18）：KDoc 里写了
> `` 日志/1791510147443_*/logcat_app.txt `` ⇒ `*/` 提前闭合块注释 ⇒
> `Expecting a top level declaration`。已改为 `...`。

### 六、未做 / 未验证

- **未实机验证** —— 下次车上日志应出现：
  - `Cluster: 仪表抢屏 entered=true`（**且**随后 `DimMenuInteraction: switchNaviMode naviMode:3`）
  - `Cluster: 监听到车机收回仪表（switchNaviMode naviMode:1），准备重开画面`（被收回时）
  - `Cluster: …（来源=poll）`（轮询先发现时）
- **未复刻**：原版 `forceKeyFrame` / `showUI` / `stopUI`（往 AirPlay 副屏发
  `changeMapZoomLevel`）、`GeelyClusterModeState` 完整状态机（`Enter/Exit/None` +
  `canManualOpen`）。本轮只保证「抢屏值域对 + 能听见被收回 + 会重开」。
- **未出包**（本轮仅改代码 + 单测）。

---

## PATCH-0051 — 热点双模式解耦（第一步）：模式切换初始化器 `CarHotspotModeInit` + 残留判据 `CarHotspotResidue`

**日期**：2026-10-09　**状态**：✅ 已应用（**未实机验证**）　**上游改动**：**零**

**用户诉求**（原话）：

> 「复用车机热点和自建热点应作为两个独立模块分别实现，确保二者能够独立运行、
> 互不干扰。是否可以增加一个热点初始化机制，在启动或切换热点模式时进行必要的
> 初始化处理，减少不同热点模式之间的相互影响，提高连接的稳定性？」

**设计文档**：`CarPlay/热点双模式解耦-设计方案-20261009.md`（本 PATCH 是其 §6 的
**第一步**：§4 的 1–5）。

### 一、要修的 bug（真车日志，设备 `e3de67df72a0`，2026-10-09 09:33）

```
hotspot plan: 复用车机热点「Lynk&Co」（手动指定）
wireless hotspot backend=Manual hotspot iface=wlan0 host=192.168.176.1 …
wireless attach timed out: iPhone did not open the AirPlay connection within 40000ms
```

链条：

```
① 先用「自建热点」      → ifconfig wlan0 = 192.168.176.1（我们 planner 配的）
② 切到「复用车机热点」  → 那个地址**不会自动消失**
③ 复用分支 / probe 的「我们的热点在跑吗」判据 = 拿 planner 的网关地址去网卡上找
                        → 命中残留 → 以为车机热点就是这张网卡
④ 把 SSID + 192.168.176.1 通告给 iPhone
⑤ 车机热点其实在 192.168.175.x ⇒ iPhone 到不了 ⇒ 40s attach timed out
```

**根因不是频率**（那是 PATCH-0047 已修的），是**两种模式共用同一张 `wlan0`
且切换时不清理上一个模式的残留**。

### 二、改（4 文件 + 2 测试，**零上游改动**）

1. **新增 `car/api/.../hotspot/CarHotspotResidue.kt`**（纯函数，可单测）
   - `detect(interfaceName, addressesOnInterface, plannedGateway): Result`
   - ⚠️ **只认精确匹配的规划网关地址，不做网段匹配** —— 网段匹配会把
     "车机自己正好用了同段"误判成我们的残留 ⇒ 去清理一张不是我们建的网。
     纪律：**误清车机地址 比 多等一次超时 糟糕得多**。
   - 网卡名/规划地址任一读不到 ⇒ 返回 `NONE`（不凭空下结论）。

2. **新增 `car/core-bridge/.../hotspot/CarHotspotModeInit.kt`**（public，见下）
   - `onModeChanged(from: CarHotspotMode?, to: CarHotspotMode): Result`
   - **同模式 = no-op**（改分辨率/音量都会调到 `applySettings`，不能每次重启热点）
   - **`from == ADB_HOSTAPD` ⇒ `stopSelfBuiltAp()`**：`host.stop()` 后
     **轮询确认**残留地址释放（`RESIDUE_WAIT_MS=3000` / `RESIDUE_POLL_MS=100`）
     —— `stop()` 是**非阻塞**契约，不确认就会被紧接着的 resolve 误导
   - 其余切换 ⇒ **只 `detectResidue()`**，不擅自清理（清理是实体操作）
   - `plannedGatewayAddress()` / `plannedInterfaceName()` **复用
     `CarAdbHotspotPlanner.plan`** —— 判据与"实际会配上去的地址"**同源**
   - 等不到**不抛异常**，如实记进 `Result.residueCleared`（与
     `CarHotspotEnabledState.UNKNOWN` 一律放行同一条纪律）
   - ⚠️ 可见性 = **public**（不是 internal）：`Result` 要出现在
     `CarAppServices.onHotspotModeChanged` 的返回类型上，而 Kotlin 不允许
     public 函数暴露 internal 类型。

3. **`CarAppServices.kt`** 新增 `onHotspotModeChanged(from, to)`：
   构造 `CarHotspotModeInit`（注入 `adbHotspotManager`）并转发；
   诊断出口接 `Log.i("XCExt.hotspot.mode", …)`。

4. **`CarAppController.kt` → `applySettings()`** 接线：
   - **覆盖 `this.settings` 之前**取旧值 `previousHotspotMode`（之后只剩新值）
   - `if (hotspotModeChanged)` ⇒ 丢到 `briefingExecutor`（worker）执行，
     **不阻塞调用方**（会停热点 + 最长 3 秒轮询，主线程跑 = 界面假死）
   - **排在 `session.reconnect()` 之前**（先扫干净再重连）

5. **`CarPlaySessionBridge.kt`** —— 只加注释与警示（**无逻辑改动**）：
   - `buildSession()` 的 `nativeInfo = null` 处补一条：这条也是"复用不被
     自建残留误导"的落点；**若将来把 `probeNativeHotspot()` 接回来**，
     必须先给 `hotspotAlreadyRunningLocally()` 加"当前不是复用模式"的前置闸门
   - `hotspotAlreadyRunningLocally()` KDoc 补 🔴 段：该判据**只对自建成立**，
     在复用语境下会把残留误认成车机热点（附那条 40s 超时链）

### 三、为什么这样接线是安全的（现状复核）

- `hotspotAlreadyRunningLocally()` 的唯一调用方是 `probeNativeHotspot()`
  （`CarPlaySessionBridge.kt:1236`），而 `probeNativeHotspot()` 在链路里
  **已不被调用**（`buildSession()` 传 `nativeInfo = null`；全文件仅注释提到它）。
  ⇒ 复用路径当前**不会**把残留地址通告出去。本 PATCH 把这个**隐式依赖**
  用契约测试钉成**显式约定**（防回归）。

### 四、验证

- `:car:core-bridge:compileDebugKotlin` → **BUILD SUCCESSFUL**
- `:car:app:compileDebugKotlin` → **BUILD SUCCESSFUL**
- 新增 `CarHotspotResidueTest`（api 判据边界，含"必须闭嘴"的负例）
- 新增 `CarHotspotModeInitContractTest`（静态读源码钉接线与清理形态）

### 五、未做（需用户明确授权，见设计文档 §4.6）

- **让复用分支的 `expectedSsid` 用 `iw dev` 读到的真实广播 SSID**，
  使上游 `ManualHotspotManager.kt:106` 的 `takeIf { it.ssid == expectedSsid }` 为真
  ⇒ **必须动上游 `:shared`**，本轮**零上游改动**故未做。
  在此之前，复用模式**仍需用户手填的 SSID 与车机实际广播名一致**才能连上。
- **未出包**（§0：仅改代码 + 编译 + 单测）。
- **未实机验证**：下次车上应从「自建」切到「复用」，观察 logcat
  `XCExt.hotspot.mode` 是否出现"已拆掉自建热点并确认残留地址 … 已释放"，
  且 AirPlay 通告的 host 不再是上一个自建网段。

---


## PATCH-0052 — 仪表**白屏**根因：跨会话复用副屏窗口，把上一会话作废的 Surface 又交给 ALT(111)（方案 A：改为每次重建）

**日期**：2026-10-09　**状态**：✅ 已应用（**未实机验证**）　**上游改动**：**零**（只动 `:car:platform-geely`）

**用户报障**（原话）：「这是最新1.0.10的日志，仪表盘还是白屏」+「我又上传了一份日志」。

**诊断报告**：`CarPlay/仪表白屏-第三轮诊断-20261009.md`（本 PATCH 是其 §4 的**方案 A**）。

**修正对象**：**PATCH-0049** 引入的「复用副屏窗口」短路分支。PATCH-0049 治的是
「纹理被系统回收」（黑屏）；本 PATCH 治的是它没挡住的另一姿势：「对端断流、
纹理还在但没帧了」。

### 一、根因（真车日志，设备 `e3de67df72a0`，日志 `1791519459715_...`）

日志里有**三次** CarPlay 连接（12:12:49 / 12:15:39 / 12:16:00），但：

| 事实 | 证据 |
|---|---|
| 窗口**全程只建了一次** | 全日志只有一处 `WindowLayout in setView … ty=PRESENTATION` + `Added window`（12:12:49） |
| 会话 2、3 都走复用 | `12:15:39.922` / `12:16:00.722  Cluster: 复用现有副屏窗口 displayId=2（通道有效）` |
| **PATCH-0049 的失效回调从未触发** | 全日志 `Cluster:` 只有 6 条，**没有一条** "丢弃窗口" |
| 副屏纹理全程存活 | `SurfaceTexture-0-15001-N`：只有 `-0`/`-2`/`-3`（**都是主屏**，宿主 `ViewRootImpl$W@ef55d67`）；**副屏索引 `-1` 从不出现** |
| 副屏轨 Surface 每次连接都新建 | `0x744dd49010`(1) → `0x745f345010`(2) → `0x744dddb010`(3)，**而窗口还是那一个旧窗口** |

**代码根因**：`GeelyClusterPresenter.start()` 的复用分支只 `onSurfaceAvailable(existingSurface)`
就 `return` —— **不建窗、不 `show()`、不抢屏、不重挂**，把旧 `surface` 对象又喂回 ALT(111)；
且 `GeelyClusterPresentation.onSurfaceTextureUpdated` 的 `if (!firstFrameShown)` 闸门使
会话 2/3 **永远不会再调 `setVideoVisible(true)`** ⇒ 白屏。

**为什么 `stopCluster()` 没救场**：它的唯一触发点是 `CarAppController.stop()` ← MainActivity 退出；
`onVideoSurfaceDestroyed()` 只挂 Compose 主屏画面层（`CarPlayScreen.kt:149`）。
本日志 `MainActivity`/`DecorView@a8ef350` 全程同一个 ⇒ **App 从未退出**；
断/重连的是 iPhone ⇒ 窗口被留着、被复用。

### 二、⭐ 反编译原版对照（`GeelyClusterSurfaceOwner.smali`，决定性证据）

- `onDisplayChanged`：**同屏 ⇒ 直接 `return`（什么都不做）**；换屏 ⇒ `removePresentation()`
  （dismiss + `presentation`/`surface`/`displayId` 统统置空）→ **新建** → **每次 `show()`**。
- `removePresentation`：`presentation.dismiss()` → `iput-object null`；`sink.clearSurface(0x6f=111, old)`；`surface=null`；`displayId=null`。
- `onSurfaceAvailable$common`：**无条件先解旧（`clearSurface`）再挂新（`setSurface`）**。

⇒ 我们把原版的「同屏就什么都没做」误实现成了「同屏就复用并 resurfaces」，
**多出来的那一步就是本次白屏**。

### 三、修法（方案 A）

`GeelyClusterPresenter.start()`：**删掉复用短路分支**，一律落到 `attach()`
（内部先 `removePresentation()` 清场 → 新建 → `show()` → 抢屏）。
同时删除纯函数 `shouldReuseClusterWindow` 与其单测 `ClusterWindowReuseTest.kt`
（它钉的是被删除的旧语义）。

保留 `onSurfaceInvalidated` → `onWindowInvalidated()`（PATCH-0049 引入）：不再是
"防复用死通道"的唯一防线，但仍用于"通道真被回收时把窗口与抢屏状态收干净"。

### 四、验证

- `:car:platform-geely:compileDebugKotlin` → **BUILD SUCCESSFUL**
- 删除 `ClusterWindowReuseTest.kt`（6 个用例随判据一起下线）

### 五、未做

- **未实机验证**：下次车上应从**第一次连接起**每次都在日志看到
  `Cluster: 仪表副屏已挂载 displayId=2 …`（不再出现"复用现有副屏窗口"），
  且第二/三次连接仪表**仍出画**。
- **未出包**（§0：仅改代码 + 编译验证）。
- ⚠️ **未动抢屏参数**（`switchNaviMode` 3/1）—— 那条已验证正确（PATCH-0050）。
## PATCH-0053 — Android 9 解码已出首帧但仪表仍白屏：移除 TextureView 首帧回调前的透明度闸门

**日期**：2026-10-09　**状态**：✅ 已改代码，待 Android 9 实车复测　**范围**：`:car:platform-geely`

### 新诊断证据（DBPlay 1.0.11，Android 9 / API 28，QUALCOMM CS11）

- `15:21:21.458`：仪表窗口挂载到 `displayId=2`。
- `15:21:21.465`：DimMenu 接受 `switchNaviMode naviMode:3`；后续轮询仍为 `3`。
- `15:21:21.532`：CarPlay `screen stream type=111 active=true`。
- `15:21:21.899`：ALT(111) 解码器输出格式为 `1920x1088`，并记录首帧已提交到 Surface。
- 本版本没有记录 `TextureView.onSurfaceTextureUpdated`，所以无法从旧日志确认它是否触发。

### 修正

旧实现把整个 Presentation 窗口和 TextureView 初始 alpha 都设为 `0`，只在
`onSurfaceTextureUpdated` 后切到可见。该回调若在 Android 9 车机上迟发，虽然 MediaCodec
已把首帧提交给 ALT(111)，整窗仍保持透明，仪表就只显示底层空白。

现在窗口和 TextureView 从创建起保持可见；root、TextureView 与 SurfaceTexture 均为透明，
空窗仍可透出仪表底图。TextureView 首帧回调继续隐藏占位文字，并记录 surface 创建、转交、
首帧更新和销毁事件，便于下一份日志验证回调时序。

### 验证状态

- 未运行测试或构建。
- 待在同一 Android 9 车机安装新包复测。新日志应同时出现“仪表 Surface 转交 ALT(111)”
  和“仪表 TextureView 首帧更新”；如果首帧更新仍缺失，窗口现在也不会因 alpha=0 而隐藏解码画面。

## PATCH-0054 — 仪表白屏**诊断探针加厚**：把窗口/视频层/回调的每个状态都记进日志（**不改行为**）

**日期**：2026-10-09　**状态**：✅ 已改代码，待实车采集　**范围**：`:car:platform-geely`

### 为什么要加

第四轮诊断（`1791530535275_2aa2aed1_e3de67df72a0`，包版本 1.0.11）拿到了一条**决定性新证据**：
会话 2 里**两路解码器同时在跑且都出帧**（副屏 7675：SPS 1080 → 输出 1920x1088 → `rendered first frame`；
主屏 7680：SPS 1008 → 输出 1920x1008），副屏 `decodedFps` 维持 11~27 共 30 秒，
**期间该 Surface 从未被 abandon** —— 而用户看到的仍是白屏。

⇒ **解码侧正常，问题在"交出去的 Surface ≠ 窗口展示的纹理"。**

但**本包缺探针**：既看不到 `onSurfaceTextureUpdated` 到底触发没有，
也看不到 `publish` 交出去的 Surface 地址/有效性，更看不到窗口与视频层的实际 alpha。
**结论悬空，必须先把日志补厚才能切下一刀。**

> ⚠️ 注意：PATCH-0053 的分支**已经**加过 4 条日志（`SurfaceTexture available/destroyed`、
> `仪表 Surface 转交 ALT(111)`、`仪表 TextureView 首帧更新`）。本 PATCH 在它之上**再加厚**，
> 并补上**首帧回调的逐次计数**（PATCH-0053 只在首帧打一条，缺失时无法区分
> 「回调从没触发」与「触发了但没被记录」）。

### 改了什么（**只加日志与只读探针，不改任何可见性/时序行为**）

`GeelyClusterPresentation.kt`：

| 位置 | 新增内容 |
|---|---|
| `onCreate` | 窗口创建时的 `displayId / 面板 / 可见高`；窗口属性**回读**（`format / alpha / flags`）——"设了 alpha=1"≠"真的生效" |
| `onSurfaceTextureAvailable` | 追加 `textureAlpha / windowAlpha` |
| `onSurfaceTextureSizeChanged` | 由 `= Unit` 改为**打日志**（原来完全静默） |
| `onSurfaceTextureDestroyed` | 追加 `textureAlpha / windowAlpha` |
| `onSurfaceTextureUpdated` | **每帧都打**（含 `#计数`、`firstFrameShown`、`textureAlpha/windowAlpha`、`surface`）← **判定"回调到没到"的关键** |
| `publish` | 追加 `isValid / released` |
| `setVideoVisible` | 记录切换前后 `textureAlpha` + `windowAlpha` + `firstFrameShown` |
| `onStop` | 记录 `updated回调累计 / firstFrameShown` |
| `release` | 记录 `updated回调累计` |
| 新增只读 API | `updatedCallbackCountOrZero()`、`diagnosticSnapshot()`（一次性快照，含 `surfaceValid / textureAlpha / windowAlpha / textureVisible / textureAttached / firstFrameShown / updated回调累计 / isShowing / dpi`） |

`GeelyClusterPresenter.kt`：

| 位置 | 新增内容 |
|---|---|
| `start()` | **显示器快照**：把本次枚举到的**全部**屏（`id/name/type/尺寸/dpi/refresh/default`）打一行 |
| `attach()` | 挂载瞬间打一次 `diagnosticSnapshot()`（基线）；抢屏后 **+2s** 再打一次（此时 `updated回调累计` 才有判别力） |
| 三处拆除路径 | 同步 `removeCallbacks(diagnosticSnapshot)`（`stop()` / `onWindowInvalidated()` / `start()` 重挂） |
| 常量 | `DIAGNOSTIC_SNAPSHOT_DELAY_MS = 2_000L` |

### 验证状态

- `:car:platform-geely:compileDebugKotlin :car:app:compileDebugKotlin` → **BUILD SUCCESSFUL**
- `:car:platform-geely:testDebugUnitTest` → **BUILD SUCCESSFUL**
- **未出包**（§0）；**未改行为**（这不是修复，是"让下一份日志能自证"）。
- **下次实车判据**：`updated回调累计` 若为 **0** 而 `仪表 Surface 转交 ALT(111)` 有值
  ⇒ 坐实"交出去的 Surface 不是窗口那条纹理"，下一刀切 `publish` 幂等短路 + `onSurfaceTextureAvailable` 时序。

---

## PATCH-0055 · 全量采集补齐框架层环境快照 + dumpsys 通道（2026-10-09）

**动机**：仪表副屏白屏排查到第四轮，卡在"日志里根本看不到**当时车机的显示体系**"——
有哪几块屏、副屏 `displayId` 是几、分辨率/刷新率多少、是不是 `ON`。
`getprop` 与 `logcat` 都读不到这些（副屏信息只在 `DisplayManager` / `dumpsys display` 里），
于是"日志缺采集项"本身成了断案的瓶颈。用户要求：**把开发者设置里的「全量采集」补全**。

### 改了什么

**新增 `CarDiagnosticEnvironment.kt`**（`:car:core-bridge/developer`，纯框架 API、不需任何权限）：

| 段 | 内容 | 为什么 |
|---|---|---|
| 显示体系 | `DisplayManager` 全部屏：`id/name/状态/尺寸/密度/刷新率/旋转/类型(FLAG_PRESENTATION)/flags/valid` + 每块 `supportedModes` | **副屏排查第一手资料**；`getprop` 读不到 |
| 运行环境 | `ActivityManager.MemoryInfo` + `Runtime` 堆 + `StatFs(/data)` + 电量/温度/电压 + `SystemClock` 开机时长 + `Build.*` + ABI | 区分"车机状态异常"与"我们代码有 bug" |
| 系统设置 | `settings` 三表：`global`(provisioned/airplane/stay_on/三个动画倍率)、`secure`(user_rotation/screen_brightness/install_non_market_apps)、`system`(screen_brightness/screen_off_timeout/sound_effects/time_12_24) | "只影响表现层、出问题最容易被漏掉"的项 |
| 网络 | `ConnectivityManager` 当前传输/能力/带宽 + `WifiManager` 开关/状态/IP | 判断"走的是哪种传输、通不通"；**刻意不采 SSID/BSSID**（脱敏） |
| 音频 | `AudioManager` 模式 + 音乐/导航/系统音量 + 静音/扬声器 | 排查"没声音"类问题 |

全部读取 `runCatching` 兜底，任一格读不到只降级该格、不影响整份采集。

**扩展 `CarDiagnosticPackager`**（`collectAndPack`，仅 `FULL` 档）：

- 新落盘 `system/environment.txt`（上面那份快照）。
- 走 **adb 通道**采集 `dumpsys`（应用 uid 没 `DUMP` 权限，必须走 shell）：
  `dumpsys/window.txt`(`window windows`)、`display.txt`(`display`)、`activity.txt`(`activity activities`)、
  `media_codec.txt`(`media.codec`)、`projection.txt`(`media_projection`)。
- `buildManifest` 新增 `dumpsysSections` 行，如实报每段字节数；拿不到通道时写明"未采集"。
- **`dumpsys SurfaceFlinger` 刻意不采**：MB 级输出，会拖垮采集预算（要 SF 视角走 logcat 更划算）。
- 每段 `dumpsys` 前查整体预算（`COLLECTION_BUDGET_MILLIS`），超时则跳过剩余命令。

### 验证状态

- `:car:core-bridge:compileDebugKotlin` → **BUILD SUCCESSFUL**
- `:car:app:compileDebugKotlin` → **BUILD SUCCESSFUL**
- `:car:core-bridge:testDebugUnitTest` → **BUILD SUCCESSFUL**
- **未出包**（§0）。
- 服务端按**路径后缀**匹配、忽略未知路径 ⇒ `environment.txt` 与 `dumpsys/*` 是**纯增量**，无需服务端配合。

### 踩到的坑

- `AudioManager.STREAM_NAVIGATION` 是 **API 33** 才有的公开常量；本车 Android 9（sdkInt 28）
  引用它**编译不过**（`Unresolved reference`）。改用字面量 `12`（自 API 21 起固定）并注明。

---

## PATCH-0057 · HUD 转向映射逐支对照 smali 修正（2026-10-09）

**位置**：`car/platform-geely/.../geely/GeelyHudTurnMapping.kt` + `GeelyHudTurnMappingTest.kt`
（**二开层文件，不涉上游**；登记在册是因为它与 PATCH-0016 的 iAP2 导航解码同属一条链路，
同步上游复核 PATCH-0016 时必须一并复核）。

### 改了什么

| 项 | 改前 | 改后（依据 smali） |
|---|---|---|
| `turnKind()` 类型分组 | `2/21/23/53 → 2`、`4/18/26 → 2`、`7/10/12/24/25/27/28..46 → 10`、`13 → 7`、`14 → 3` | `2/21/23/53 → 3`、`4/18/26 → 5`、`7/28..46 → 10`、`10/12/24/25/27 → 24`、`13 → 11`、`14 → 12`、`47 → 3`、`51 → 0` |
| 未知类型默认值 | `else -> 3`（"直行"） | `else -> 0` —— `payload()` 会把 kind 0 转成原版 `0x9c` **无转向哨兵** |
| `nativeTurnId()` 专用编号 | 只有 `type == 5 → 0x78` | 补 `13 → 0x95`、`14 → 0x96`、`19 → 0x75` |

**依据**：`GeelyCarPlay-反编译/geelycarplay_smali/io/dushu/car/geely/GeelyHudAdapter$Companion.smali`
逐寄存器核对 `turnKind()` 与 `nativeTurnId()` 的控制流（含跳转目标），不靠 jadx 渲染
（见 skill §二点六：涉及"哪条分支可达"必须回 smali）。

### 验证状态

- `:car:platform-geely:testDebugUnitTest`（31 tests，0 failures）+ `:car:app:compileDebugKotlin` → **BUILD SUCCESSFUL**
- **未出包**（§0）；**未改上游**。
- **待实车确认**：起导航后 HUD 转向箭头方向是否与手机一致（PATCH-0016 的字段号本身仍未实车抓包）。

---

## PATCH-0058 · 仪表副屏窗口生命周期对齐 AirPlay 会话 + 旧会话迟到回调防护（2026-10-09）

**位置**：`car/app/.../CarAppController.kt`、`car/core-bridge/.../CarPlaySessionBridge.kt`、
`car/platform-geely/.../GeelyClusterPresenter.kt`（新增测试 `GeelyClusterPresentationCallbackTest.kt`）。
**全部落在二开层，未动上游。**

### 病根（承 PATCH-0052 / REF-pitfalls §94、§95）

副屏 `Presentation` 是**跨会话存活的长壳子**，而它的启动时机原来挂在**主屏 View 的
`onVideoSurfaceAvailable()`** 上 —— 主屏 View/Surface 可能跨"远端断开→重连"一直存活，
于是新会话未必再触发一次该回调，旧窗口/旧 Surface 继续被复用。
PATCH-0052 只能保证"**下次 `start()` 被调用时**重建"，救不了"上层压根没按会话生命周期 start/stop"。
远端主动断开（`AirPlay session ended`）**不经过** `CarUiActions.stop()`，此前无人拆窗。

### 改了什么（最小生命周期修复）

1. `CarAppController`：仪表 Presenter 的启动时机从主屏 `onVideoSurfaceAvailable()`
   移到 **`CarSessionEvent.SessionStarted`**；`SessionEnded` 时调 `stopCluster()`
   （停 Presenter + 停自绘桥 + `attachClusterVideoSurface(null)` 显式解绑 ALT(111)）。
   `startClusterIfPossible()` 去掉多余的 `mainHandler.post`（会话事件本就由桥投递到主线程）。
2. `CarPlaySessionBridge.onSessionEnded()`：只有 `releaseAirPlaySession()` 的
   `compareAndSet` 确认该 session **仍是当前会话**时才更新状态并 `emit(SessionEnded)`；
   过期回调只记诊断日志后返回 —— 避免旧会话的迟到结束事件误拆**新会话**的窗口。
3. `GeelyClusterPresenter`：新增 `presentationGeneration`，`attach()` 显式接收本次
   Surface 回调（不再长期持有 `onSurface` 字段）；attach 开头先向上层发 `null` 解绑旧 Surface；
   Surface / 首帧 / invalidation 三个回调都过 generation 闸门
   （纯函数 `shouldAcceptClusterPresentationCallback()`），旧 Presentation 的迟到回调被丢弃。

### 验证状态

- `:car:platform-geely:testDebugUnitTest` + `:car:core-bridge:testDebugUnitTest` +
  `:car:app:compileDebugKotlin --no-configuration-cache` → **BUILD SUCCESSFUL**
- 新增 `GeelyClusterPresentationCallbackTest`（3 tests）：当前代接受 / 旧代拒绝 / stop 后拒绝。
- **未出包**（§0）；**实车未验证**。
- **下次实车判据**（需带 PATCH-0053/0054 探针的版本）：连续"断开→重连"后核对
  ① `SessionStarted/SessionEnded` 与建窗/拆窗日志是否一一对应（建窗条数 == 会话数）；
  ② `仪表 Surface 转交 ALT(111)` 的 `surface=` 每次会话都换新；
  ③ 窗口快照里 `updated回调累计` 是否 > 0。若 ③ 仍为 0 而 ② 有值，
  下一刀切 `GeelyClusterPresentation.publish()` 的对象身份短路 + `onSurfaceTextureAvailable` 时序。

---

## PATCH-0060 · 仪表窗口 `onStop` 不再解绑 ALT(111)（对齐原版：原版没有 `onStop`）（2026-10-09）

**位置**：`car/platform-geely/.../geely/GeelyClusterPresentation.kt`（二开层，未动上游）。

### 病根（自查出来的自我引入偏差）

反编译原版 `GeelyClusterPresentation.smali` 的方法表**只有** `onCreate` + `refreshSelfDrawSettings` /
`setFullScreen` / `setLocalNavigationVisible` / `setNavigation` / `setNightMode` / `setVideoVisible`
—— **没有 `onStop`，也没有 `onStart`**。原版在窗口被暂停（车机把仪表收回 / 切回原厂界面）时
**不解绑** ALT(111) 上的 Surface，画面回来时无需任何人再喂一次。

我们额外写了 `onStop { publish(null) }`，而**没有任何回补路径**：

| 触发 `publish(...)` 的地方 | 窗口重新可见时会不会再来一次 |
|---|---|
| `onSurfaceTextureAvailable` | ❌ 不会 —— 窗口重新可见**不重建** `SurfaceTexture` |
| `release()` | 只在拆窗时 |
| ~~`onStop`~~ | 就是它解绑的 |

⇒ **窗口只要停过一次，ALT(111) 就永久空着** ⇒ 白屏。与 PATCH-0052 同一族病根
（"通道还在，但已被我们自己解绑/喂错"）。

### 改了什么

`onStop()` 保留诊断日志（`updated回调累计` / `firstFrameShown` 仍是排查关键量），
**去掉 `publish(null)`**。解绑职责收归两处，各自语义清晰：
- 会话结束 / 拆窗 → `release()`（先 `publish(null)` 再 `dismiss()`）；
- 纹理真被系统回收 → `onSurfaceTextureDestroyed`（`publish(null)` + `onSurfaceInvalidated`）。

### 同时核到、但**本轮刻意不改**的两处原版差异（留档，等实车证据）

1. **原版把"视频可见"绑定在抢屏成功上**：`GeelyClusterSurfaceOwner.applyMode()` 里，
   只有 `dim.enterFullNavi()` 返回 **true** 才 `setVideoVisible(true)`；退出全屏导航或
   `onGlobalEnabled(false)` 时 `setVideoVisible(false)`。而**原版从不用 `TextureView` 的
   `onSurfaceTextureUpdated` 控制可见性**（那个回调在原版里只调 `onFirstFrame`）。
   我们这边是 PATCH-0030 用首帧回调显示、PATCH-0053 又从创建起 `alpha=1f`。
   ⚠️ 空 `TextureView`（`isOpaque=false`、无帧）本来就是透明的，所以 `alpha=1f` 本身
   不改变观感；**暂不动**，因为原版那套需要移植 `GeelyClusterModeState` 状态机。
2. **原版 `onSurfaceTextureDestroyed` 不拆窗口**：只 `clearSurface(111, surface)` + 置空
   （`onSurfaceGone$common`）。我们的 `onSurfaceInvalidated` → 丢窗重建是 PATCH-0049 加的，
   在"纹理真被回收"时有用，但**分不清"纹理真回收"与"我们自己 dismiss 导致的正常销毁"**。
   本轮靠 PATCH-0058 的 generation 闸门把后一种挡掉了（我们自己拆窗时 generation 已 +1）。

### 验证状态

- `:car:platform-geely:testDebugUnitTest` + `:car:app:compileDebugKotlin` → **BUILD SUCCESSFUL**
- **未出包**（§0）；**实车未验证**。

---

## PATCH-0059 · 增加独立 Wi-Fi Direct 热点方案与 Android 9 legacy group 创建

**位置**：`shared/.../orchestration/CarPlayRuntimeConfig.kt`、
`shared/.../orchestration/CarPlayController.kt`、新增
`shared/.../network/LegacyWifiP2pGroupManager.kt` 与 `LegacyWifiP2pGroupOwnership.kt`。
同时在 `:car/api` / `:car:core-bridge` / `:car:ui` 接入独立的 `CarHotspotMode.P2P_DIRECT`。

### 改了什么

- 新增独立 runtime mode `WIFI_P2P_LEGACY`，Android 9 旧 `WIFI_P2P -> LOCAL_ONLY_HOTSPOT`
  降级分支原样保留；P2P 模式不映射 MANUAL、ADB 或 SAME_LAN。
- Android 9 使用系统凭据的 `createGroup(channel, ActionListener)` legacy overload，
  经 `requestGroupInfo` / `requestConnectionInfo` 等待组名、密码、接口与地址；Android 10+
  使用既有 `WifiP2pGroupManager` 和证书派生凭据。
- legacy manager 只会移除与本 manager 持久化组名相符且仍是组主的组；检测到其他应用的组时
  明确失败，不移除、不回落到其他热点方案。组信息、地址等待、超时、启动失败与 close 均走独立路径。
- 设置页新增「Wi-Fi Direct」选项；隐藏对该模式无意义的手动 SSID / 密码 / 频段 / 信道 / 加密字段，
  并提示 Android 9 凭据与信道由系统决定、兼容性需逐车型验证。
- 默认值仍是 `ADB_HOSTAPD`；`keepHotspotAlive` / ADB prewarm 只对 ADB_HOSTAPD 有效，
  P2P 不触发 ADB 热点 ensure / stop。

### 验证状态

- `:shared:testDebugUnitTest` + `:car:core-bridge:testDebugUnitTest` +
  `:car:api:compileDebugKotlin` + `:car:ui:compileDebugKotlin` + `:car:app:compileDebugKotlin`
  → **BUILD SUCCESSFUL**（串行 + `--no-configuration-cache --no-parallel -Pkotlin.incremental=false`，
  避开并行 Gradle daemon 互踩 `build/intermediates` 与增量缓存损坏）。
- 新增/相关单测实跑（均 0 failures / 0 errors）：`LegacyWifiP2pGroupOwnershipTest`（7 条断言）、
  `ManualHotspotConfigTest`（9）、`CarHotspotP2pModeTest`（3）、`CarErrorTextTest`（12）。
- ⚠️ **顺带修掉 HEAD 上一处真实回归**：并行会话的 `e179c11` 因 `git add -- <file>` 整文件暂存，
  把本补丁**中途已回滚**的 `sessionStartedAdbHotspot` 版本扫进了 `CarPlaySessionBridge.kt`，
  而该字段在 HEAD 里**从未被置 true** ⇒ 两处 `adbHotspot?.stop()` 永不执行（会话结束/`close()`
  时 ADB 热点不再回收）。已改回**模式闸门** `settings.hotspotMode == ADB_HOSTAPD`。
  （坑见 REF-pitfalls §99。）
- Android 9 legacy 路径尚未在目标车机实测；特别是系统生成凭据、组接口地址及频段行为，不能据编译通过推定所有车机可用。
- **未构建 APK / 未出包**。

---

## PATCH-0064 · Wi-Fi Direct 自己给车机 Wi-Fi 上电（Android 9 legacy 路径修不起来的根因）

**位置**：`shared/.../network/LegacyWifiP2pGroupManager.kt`（改）、
新增 `shared/.../network/LegacyWifiP2pRecovery.kt`、
`shared/.../orchestration/CarPlayController.kt`（只改 `WIFI_P2P_LEGACY` 的超时常量）。
配套：`car/core-bridge/.../CarErrorText.kt`、`car/ui/.../SettingsScreen.kt` 文案。

### 现场（诊断包 `1791546694008_e2f499c9_67b3c24af6b5`，VIN `L6TTEST1111111111`）

`hotspotMode: P2P_DIRECT`，19:50:48 启动，**7 次会话尝试（19:50:50 → 19:51:01）全部**
停在同一条栈：

```
W xcertplay-usb: java.io.IOException: Could not establish WIFI_P2P_LEGACY hotspot:
        Turn on Wi-Fi in the head unit's settings, then reconnect
  at LegacyWifiP2pGroupManager.checkPrerequisites(LegacyWifiP2pGroupManager.kt:146)
  at LegacyWifiP2pGroupManager.start(LegacyWifiP2pGroupManager.kt:59)
  at CarPlayController.startWirelessHotspot(CarPlayController.kt:1792)
```

即 **P2P 方案连组网阶段都没走到**（`createGroup` 从未被调用），根本谈不上"发现 / 识别热点"。

### 根因

同一份诊断包的三处证据指向同一件事——**车机框架 Wi-Fi（STA）是关着的**：

| 证据 | 内容 |
|---|---|
| `system/logcat_system.txt`（system_server pid 1350） | `WifiControllerIPCP: current state: WIFI_STATE_DISABLED`（整包 4 次全是 DISABLED） |
| `system/environment.txt` | `WiFi：启用=false 状态=1 IP=0.0.0.0`；`当前网络：传输=以太网` |
| app 日志失败前一行 | `D WifiManager: call tcam getWifiState callpkg: io.dushu.car` |

而 **Wi-Fi Direct 走的是 Android 框架**：框架只在 `WIFI_STATE_ENABLED` 之后才把
`WifiP2pServiceImpl` / P2P 状态机拉起来。旧实现把"Wi-Fi 关着"当成**不可修复的前置条件**
（只读一次 `isWifiEnabled`，读到 `false` 就抛"请去车机设置里打开 Wi-Fi"），于是：

- 这台车机的上行是以太网，**平时就不开 Wi-Fi**（`WifiControllerIPCP` 全程 DISABLED）；
- 车机 UI 上**没有**"打开 Wi-Fi"的入口 ⇒ 用户拿着那句报错什么也做不了，只能反复重试；
- 而 DBPlay 其实**已经声明了 `CHANGE_WIFI_STATE`**（`shared/src/main/AndroidManifest.xml`，
  普通权限、安装即授予）——这件事它自己做得了。

> 同族对照：`ADB_HOSTAPD`（自建热点）走 root shell + hostapd 直控 `wlan0`，**不需要**框架
> Wi-Fi，所以同一台车在 ADB 模式下一切正常（包 `1791550379832` 实录 21 步全 OK）。
> 这也解释了为什么"只有 Wi-Fi Direct 这一支起不来"。

### 改了什么

1. **自己给车机 Wi-Fi 上电**（`ensureWifiStationPowered`）：
   - 读 `getWifiState()`；已是 `ENABLED` ⇒ 只记诊断，**永不接管**；
   - 不是 ⇒ `setWifiEnabled(true)`，然后**轮询等到状态同步到 `ENABLED`**
     （`setWifiEnabled` 只是"受理"，状态要经 `ENABLING(2)` 才到位）；
   - 上电前先 `awaitWifiStationSettled()` 等在途状态落定（上一次会话收尾的
     `setWifiEnabled(false)` 可能还停在 `DISABLING(0)`——此时再发开启请求，
     厂商 ROM 有的丢掉、有的卡在 ENABLING，表现是"连续重试时第二、三次反而开不起来"）。
2. **`createGroup` 有界重试**（`createGroupWithRetry`，最多 3 次）：
   只对 `BUSY` / `ERROR` 重试（Wi-Fi 刚上电时 P2P 状态机还没就绪的竞态），
   `P2P_UNSUPPORTED` / `NO_PERMISSION` 立即失败。
   `StartAttempt` 里 `channelFailure`（致命）与 `createFailureReason`（可重试）**分开记**——
   重试前要清掉后者，而清掉一个致命失败会把"通道已断开"当成"还没出结果"空转到超时。
3. **收尾归还**（`restoreWifiStation`，在 `close()` 里拆完组之后）：
   只在"**是我们打开的**且现在确实还开着"时关回去（`legacyWifiStaShouldBeRestored`）。
   车机 Wi-Fi 一直开着会让 `CarAdbHotspotPlanner.autoInterface` 把 `wlan0` 判成上行网卡
   而排除它（见该文件第 198 行），反过来影响「自建热点」。
4. **超时预算**：`WIFI_P2P_LEGACY_START_TIMEOUT_MILLIS = 45_000L`（只给 legacy 这一支；
   上游 `WIFI_P2P` 保持 20s 不变）。20s 装不下"上电（最长 20s）+ 建组（含 3 次重试）"。
5. **文案**：`CarErrorText` 新增 3 条带完整包装层的 PREFIX（上电被拒 / 上电超时 /
   建组连续失败），并改掉 `WIFI_P2P_LEGACY` 的兜底——不再让用户"先去打开 Wi-Fi"。
   ⚠️ 新内层文案刻意**不用** `Timed out after …`：那与蓝牙 RFCOMM 超时**前缀相同**，
   而 `PREFIXES` 是 `startsWith` 匹配、先命中者胜（该文件里已有一段同族说明）。
6. **设置页**：P2P 专属卡新增一行「车机 Wi-Fi：由 DBPlay 自动开启」，说明"只关自己打开的那一次"。

### 未动的部分（`避免影响其他热点连接模式`）

- `WirelessHotspotMode.WIFI_P2P` 在 Android 9 的 `LOCAL_ONLY_HOTSPOT` 降级、`WIFI_P2P`
  的 20s 超时、`WifiP2pGroupManager`（Android 10+ 证书凭据路径）**逐字未动**；
- `MANUAL` / `ADB_HOSTAPD` / `AUTO` / `SAME_LAN` 四种模式的代码与默认值（仍是 `ADB_HOSTAPD`）未动；
- 新增逻辑全部落在 PATCH-0059 新建的独立 manager 与它自己的超时常量里。

### 验证状态

- `:shared:testDebugUnitTest` + `:car:core-bridge:testDebugUnitTest` +
  `:car:api:compileDebugKotlin` + `:car:ui:compileDebugKotlin` + `:car:app:compileDebugKotlin`
  → **BUILD SUCCESSFUL**（串行 + `--no-configuration-cache --no-parallel -Pkotlin.incremental=false`，
  避开并行 Gradle daemon 互踩 `build/intermediates` 与增量缓存损坏）。
- 新增/相关单测实跑（均 0 failures / 0 errors）：
  `LegacyWifiP2pRecoveryTest`（**5**，新增）、`CarErrorTextTest`（**14**，+2）、
  `LegacyWifiP2pGroupOwnershipTest`（1）、`ManualHotspotConfigTest`（9）、`CarHotspotP2pModeTest`（3）。
  `LegacyWifiP2pRecoveryTest` 里有一条**常量对齐**断言（`DISABLING=0/DISABLED=1/ENABLING=2/ENABLED=3`、
  `ERROR=0/BUSY=2`）——本文件用字面量而不是引用 `WifiManager.*`（纯 JVM 单测的 `android.jar`
  是 stub，静态字段取值不可靠，同族坑 §57）；那条断言就是"抄错了会红"的闸。
- ⚠️ **Android 9 legacy 路径仍未在目标车机实测**：`setWifiEnabled(true)` 在这台车机的
  `persist.service.wifi.ipcp=true` 厂商实现下能否成功、以及上电后 P2P 组能否真正建起来，
  都**只能上车验证**。本次修复能保证的是：**失败时一定有一句说得出原因的日志与中文提示**，
  不再出现"7 次全是同一句、且用户无从下手"。
- **未构建 APK / 未出包**（§0）。


---

## PATCH-0065 · 设置页补回「仪表导航」分区（副屏「未选一律不投」）

**日期**：2026-10-10　**状态**：✅ 已应用（**未实机验证**）　**影响上游**：否

**用户口径（verbatim）**：

> 设置里面并没有配置页面，需要你解决
> 检测仪表副屏能让用户自行选择，而非自动选择
> 我github已经移除原有的版库，你查看后再继续

**背景（代码实证，非推测）**：数据通路本来是通的 —— `CarSessionSettings.projectionEnabled`
/ `clusterDisplayId` / `safeArea` 三个字段都有存储与消费方，但设置页 `when (current)`
里**没有仪表分区**，`CarSettingsSection` 也没有对应枚举项；`CarUiActions.setProjectionEnabled`
存在却**没有任何界面调用点**。这一段是 commit `7e4c2ff`「整体移除仪表导航（自绘）能力」
时连同 `ClusterSection.kt`（1007 行）一起删掉的（当时连带影响写着
「CarClusterSafeArea 随 selfdraw 包消失，仪表副屏不再有自定义安全区」）。

**改动**：

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `car/ui/…/navigation/CarDestination.kt` | `CarSettingsSection` 增加 `CLUSTER("仪表导航")` |
| 2 | `car/ui/…/screens/SettingsScreen.kt` | 索引页入口 + `ClusterSection`（投屏开关 / **纵向整宽可点列表**选屏 / 核心安全区四边）+ `summaryCluster`；`attached && !firstFrame` 时给「换一块屏试」的可行动提示 |
| 3 | `car/ui/…/CarUiContract.kt` | 新动作 `refreshClusterDisplays()`（进页主动重算 + 「重新检测副屏」按钮） |
| 4 | `car/app/…/CarAppController.kt` | 候选来源**优先平台清单** `CarPresenter.listCandidates()`、通用枚举兜底，逐块 `runCatching`（一块屏读失败不再让整张清单变空）；`labelFor` 前置 `displayId`；`resolveClusterSpec` 补 `safeArea` 映射（全 0 保持 null，与改前逐字节一致） |
| 5 | `car/api/…/projection/CarPlayClusterProjection.kt` | **删掉「未选 + 恰好一块候选 ⇒ 自动用它」兜底**：`pickDisplayId` 未选恒 -1 |
| 6 | `car/core-bridge/…/projection/ClusterProjectionController.kt` | 未选屏诊断文案改为「检测到 N 块候选，等用户去设置里选」 |
| 7 | `car/core-bridge/src/test/…/ClusterDisplayPickTest.kt` | 反转「唯一候选自动用」用例 → 「未选时即使只有一块候选也不自动用」 |
| 8 | `car/core-bridge/src/test/…/ClusterSafeAreaContractTest.kt`（新增） | 5 例：spec 给什么就报什么、不给就不报、主副屏互不串味、可用高度按各自画布算、`drawUIOutsideSafeArea` 一起透传 |

**⚠️ 有意偏离原版**：反编译原版 `GeelyDisplaySelector.select` 是 `singleOrNull`
（唯一命中时自动用）；本产品**改为未选一律不投**，因为原版前提「车上仪表屏有且只有一块」
在实车上不成立，而"自动挑错"的代价是画面跑到别的屏上。车主要求"由用户自行选择"。

**验证**：`:car:core-bridge:testDebugUnitTest` 522 用例 / 0 失败（含新增 5 例 + 反转 1 例）；
`:car:ui` / `:car:app` 编译通过；`lintVitalRelease` 通过。

**边界**：**未实机验证**；自绘卡片/HUD（原版 `GeelyClusterContentStore`）本工程未实现，
本轮未新增；「关闭投屏开关时仍按 spec 声明第二块屏」行为未改。

---

## PATCH-0066 · API 级别防线：真车"投屏完全不工作"的根因（API 34 跑在 Android 9）

**日期**：2026-10-10　**状态**：✅ 已应用（**出包门禁已实测有效**）　**影响上游**：否

**现象**：真车（CS11，VIN `L6TTEST1111111112`）上 CarPlay 主界面正常，**仪表永远不出画**。

**证据（诊断包 `1791624952142`，app 1.0.28）**：

```
Rejecting re-init on previously-failed class
  com.dbtool.carplay.geely.GeelyClusterPresentation$onCreate$5:
  java.lang.NoClassDefFoundError: Failed resolution of:
  Landroid/view/ViewTreeObserver$OnWindowVisibilityChangeListener;
Cluster: 创建仪表窗口失败 NoClassDefFoundError: …
```

同一包里协议侧是好的：`airplay /info displays=[{type=110…},{type=111,…}]` 已声明第二块屏，
iPhone 也回了 `SETUP stream type=111` + `screen stream type=111 active=true`。

**根因**：`ViewTreeObserver.OnWindowVisibilityChangeListener` 是 **API 34（Android 14）**
才有的接口（SDK 自带 `data/api-versions.xml` 里 `since="34"`），而车机是 **Android 9（SDK 28）**。
Kotlin `object : …` 编成匿名类，"实现接口"在**类加载阶段**就要解析 ⇒ 整个 `onCreate` 抛异常 ⇒
窗口一次都建不起来，111 流没有承载 Surface。`assembleRelease` 只卡 fatal 级检查，而 `NewApi`
是 error 级 ⇒ 编译/打包一路绿灯、上车才炸。

**改动**：

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `car/platform-geely/…/GeelyClusterPresentation.kt` | 改用 `OnGlobalLayoutListener`（API 1，回读 `decorView.windowVisibility`，**状态变化**才回调）+ `OnWindowFocusChangeListener`（API 18），语义等价（"窗口重新可见 ⇒ 补建被回收的纹理"），两者都只调幂等的 `reattachIfTextureLost()` |
| 2 | 根 `build.gradle.kts` | 对 `:car:*` 模块统一 `lint { fatal += "NewApi" }`（不碰上游 `:shared`/`:common` 的历史 lint 债） |
| 3 | `car/app/build.gradle.kts` | `lint { checkDependencies = true }` —— 不打开这一项时**库模块里的 NewApi 进不了 release 报告**（实测：只加根规则照样能出包） |
| 4 | `car/platform-android/…/CarWindowInsetsHolder.kt` | `WindowInsets.Type.*` / `getInsets(int)` / `getInsetsIgnoringVisibility(int)` 都是 **API 30** ⇒ 按 `SDK_INT >= R` 分支，28/29 走同语义的 `systemWindowInsetTop/Bottom` |
| 5 | `car/core-bridge/…/hotspot/LocalSoftApReader.kt` | `SoftApConfiguration` 是 **API 30** 才有的类 ⇒ 判据挪到调用点（显式版本分支）+ 两个方法标 `@RequiresApi(R)` |
| 6 | `car/core-bridge/…/hotspot/CarSameLanReader.kt` | `NetworkCapabilities.getTransportInfo()` 是 **API 29** —— SAME_LAN 模式读 SSID 必崩 ⇒ 28/29 改用 `WifiManager.getConnectionInfo()`，拿不到回落设置页手填 |

**验证**：故意在 `platform-geely` 留一个 API 34 调用 ⇒ `:car:app:lintVitalRelease`
**直接失败并定位到 `ApiLevelProbe.kt:6`**（探针用完即删）；修完三处隐患后
`lintVitalRelease` 全绿（`No issues found`）；`:car:core-bridge` 522 用例 / 0 失败。

**边界**：**仪表窗口能否真出画仍需上车验证**（本次只保证"不再因这个异常建不起窗"）。
上游 `:shared` 仍有既存 lint 债（`LocalOnlyHotspotManager` 2 处 NewApi 等），本轮未动。

---

## PATCH-0067 · Wi-Fi Direct：失败原因分档 + 建组前落定 + root 取证

**日期**：2026-10-10　**状态**：✅ 已应用（**未实机验证**）　**影响上游**：是（`shared/…/LegacyWifiP2pGroupManager.kt`）

**用户口径（verbatim）**：

> 同时修下WiFi Direct 临时组网失败的问题
> Wi-Fi Direct能否通过ADB提权后进去呢？

**证据（诊断包 `1791626784246`，app 1.0.29，`hotspotMode=P2P_DIRECT`）**：

```
Wi-Fi P2P legacy preflight wifiState=3 featureWifiDirect=true locationEnabled=true
Wi-Fi P2P legacy Wi-Fi station already on state=3; leaving it untouched
createGroup sdk=28 attempt=1 → "Wi-Fi P2P is busy"（5ms 内即回）
createGroup sdk=28 attempt=2 → 同上
createGroup sdk=28 attempt=3 → startup failed → wireless bring-up failed
```

⇒ **三条前提全部满足**（本包新增的 `system/wifi_direct.txt` 直接给出：`FEATURE_WIFI_DIRECT:true`、
`isLocationEnabled:true`、`isWifiEnabled:true / wifiState:3`、`wlan0 UP`），框架仍一直回 `BUSY`；
同包 `p2p0 state DOWN`、`init.svc.wpa_supplicant` 为空、`persist.service.wifi.ipcp: true`（厂商 IPCP 代管 Wi-Fi）。
同一份包里**完全没有 CarPlay 起栈痕迹**（无 `/info`、无 `SETUP stream`、无 `Cluster:` 行）
⇒ 那一次仪表黑屏与选屏/副屏窗口无关，是**会话根本没起来**。

**改动**：

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `shared/…/LegacyWifiP2pGroupManager.kt` | 第一次 `createGroup` 之前加 **400ms 落定等待**（`CREATE_GROUP_SETTLE_MILLIS`）；尝试次数 **3 → 4**；`createGroupFailure()` 失败原因**分档**：缺固件特性 / 定位没开 / **前提全满足却仍 BUSY（"服务不可用"）** / 其它；`preflightFacts()` 三条事实写进日志 |
| 2 | `car/core-bridge/…/CarErrorText.kt` | 新增三条中文文案：固件不支持、定位没开、**服务不可用**（前提全满足 + BUSY）——都不再落进"请确认没有其他应用占用 Wi-Fi Direct"那个查不出结果的方向 |
| 3 | `car/core-bridge/…/developer/CarDiagnosticPackager.kt` | 新增 `system/wifi_direct.txt`：应用侧能力（FEATURE / wifiState / P2P manager / 定位）+ 内核侧（**全部网卡**、`p2p0`/`wlan0`、supplicant/p2p 进程）+ `dumpsys wifi` 的 P2P 段；**新增 root 段**（`ROOT_P2P_PROBES`，全只读）：厂商 supplicant 配置、socket 目录、`service list`、`wpa_cli help` 的 `P2P_*` 命令表、厂商属性、P2P/hostapd 二进制 |
| 4 | `car/core-bridge/…/CarAppServices.kt` + `…/developer/CarDeveloperMode.kt` | 把**已提权**的 adb shell 通道接进诊断包（`rootShell`；同 `systemShell` 的纪律：只 `peek()`、**绝不** `ensure()`） |
| 5 | `car/core-bridge/src/test/…/CarErrorTextTest.kt` | +3（共 17）：固件不支持 / 定位没开 / 前提全满足却仍忙（并断言**不**出现"占用"） |

**为什么要 root 段**：应用 uid / shell uid **查不下去**（厂商 supplicant 配置 `Permission denied`、
`dumpsys wifi` 看不到 P2P 段）。root 后才能判定三件事 —— 配置层 P2P 是否被关（能改 ⇒ 有救）、
框架 P2P 服务是否在跑、`wpa_cli` 里有没有 `P2P_*`（有 ⇒ 可**绕过框架**用 `P2P_GROUP_ADD` 建组）。
真正会改状态的动作（改配置、重启 Wi-Fi、建组）**本轮没有做**，等看过证据再定。

**验证**：`:shared` 222 用例 / `:car:core-bridge` 523 用例，0 失败；`lintVitalRelease` 通过；
1.0.32 签名包已出（见 `投屏修复与API防线-20261010.md`）。

**边界**：**未实机验证**。当前结论是"这台车机的 Wi-Fi Direct 服务不可用（前提全满足仍 BUSY）"，
能不能靠 root 救回来要看下一次包里 root 段的证据。

---

## PATCH-0071 · root 直驱 supplicant 建 P2P 组（绕开框架）

**日期**：2026-10-11　**状态**：✅ 已应用（**未实机验证**）　**影响上游**：**否**（全部落在 `car/`）

**车主口径（verbatim）**：

> 我车无法正常使用p2p，你可以给它adb权限去操作

**证据（诊断包 `1791680035426`，app 1.0.33，`hotspotMode=P2P_DIRECT`；全为 root 段/框架侧实测）**：

| 事实 | 出处 |
| --- | --- |
| `createGroup` 4 次尝试全部 **5ms 内回 BUSY**（Wi-Fi 已 `state=3`） | `logcat_app.txt` 08:58:53～08:58:57 |
| `p2p0` 网卡存在但**一直 DOWN**；`wlan0` UP | `system/wifi_direct.txt` |
| `init.svc.wpa_supplicant` 为空、`persist.service.wifi.ipcp: true`（厂商 IPCP 代管 Wi-Fi） | 同上 |
| 框架 `wifip2p` 服务**已注册**，而 `dumpsys wifi` 的 P2P 段**为空** | 同上（root 段） |
| `/vendor/bin/hw` 里**只有** `hostapd` 与 `wpa_supplicant` ⇒ **没有 `wpa_cli`** | 同上（root 段） |
| `/data/vendor/wifi/wpa/sockets/wlan0` 存在（`srwxrwx--- wifi wifi`），夹具配置里有 `p2p_add_cli_chan=1` | 同上（root 段） |
| 随包 busybox 是 **1.28.1**（其 `nc` **不支持 `-U`**，够不到 unix socket） | `strings libdbplaybusybox.so` |

⇒ 结论：框架那层在这台车机上**用不了**（P2P 服务没把 `p2p0` 拉起来）；而 root 能读到
supplicant 的 socket 与 P2P 夹具线索。**绕开框架、直接跟 supplicant 说话**是唯一没试过的路。

**改动**（新增 6 个主源码文件 + 4 个测试文件 / 改 7 个文件，**全部在 `car/`，未动上游**）：

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `car/api/…/hotspot/CarRootP2pContract.kt`（新增） | `CarRootP2pHost` 接口：`start(timeoutMillis)` / `stop()` / `lastFailure()` / `steps()` / `releaseLeftover()`（默认实现返回 null，与 `CarAdbHotspotHost.releaseLeftoverAddress` 同风格） |
| 2 | `car/api/…/hotspot/CarRootP2pOutput.kt`（新增） | 输出解析（纯函数）：`p2pCapable` / `commandOk` / `replyFailed` / `parseGroup`（口令优先 `passphrase`，缺了才用 `psk`；**要求 `role=GO`**）/ `groupInterfaceFromGroupOutput` / `pickGroupInterface` / `extractReply` + `parseToolExit`（工具输出标记）/ 频率计划与 socket 候选 |
| 3 | `car/api/…/hotspot/CarRootP2pCommands.kt`（新增） | 设备侧命令构造（纯函数）：`app_process` + `CLASSPATH=` 的 `sh -c` 形状、`pidof` / `iw` / `ip addr` / `udhcpd`、以及 `shellToken`（**需要才加引号**，诊断包里的命令才读得懂） |
| 4 | `car/core-bridge/…/hotspot/CarRootP2pTool.kt`（新增） | **跑在车机上的那个工具**：`@JvmStatic main` + `LocalSocket`（FILESYSTEM 命名空间）连 supplicant 控制 socket，`PING`/`HELP`/`P2P_GROUP_ADD freq=`/`P2P_GROUP`/`P2P_GROUP_REMOVE`/`P2P_FLUSH`；输出用 `reply-begin`/`reply-end`/`exit=` 标记框住 |
| 5 | `car/core-bridge/…/hotspot/CarRootP2pSta.kt`（新增） | 车机 Wi-Fi（STA）开关的**可替换接口** + `WifiManager` 实现：`enableAndWait`（轮询到 ENABLED）；**只归还"我们打开的"那张**（`staEnabledByUs`） |
| 6 | `car/core-bridge/…/hotspot/CarRootP2pManager.kt`（新增） | 主流程：Wi-Fi 上电 → ADB/root → `pidof wpa_supplicant` → 控制 socket 试探（候选目录×名字，`PING`/`PONG`，带 deadline）→ `HELP` 查 `P2P_*` → 收编已有组或按频率计划建组 → 认 GO 网卡（`ifname=` 优先，`iw dev`+ssid 兜底）→ 配 `192.168.49.1/24` + busybox `udhcpd` → 三项收尾校验；`stop()` 幂等；`releaseLeftover()` 清"上次进程留下的组"；`probeForDiagnostics()` **只读**自证 |
| 7 | `car/core-bridge/…/network/CarHotspotResolver.kt` | 新增纯函数 `p2pGroupPlan(...)`（P2P 组 → `MANUAL` 形状 plan；band/channel = AUTO/0；`reused=false`；**口令必须 8~63 位**这道硬闸门）+ `p2pDirect()` 的文案更新 |
| 8 | `car/core-bridge/…/CarPlaySessionBridge.kt` | 新增 `rootP2p` 构造参数（默认 null ⇒ 行为与改动前逐字一致）与 `ensureRootP2pGroup`：只在 `P2P_DIRECT` 上跑；成功 ⇒ 改写成 MANUAL 形状交给上游（**零上游改动**）；失败 ⇒ 记原因并**回落框架那一路**；`stop()`/`close()` 里收组 |
| 9 | `car/core-bridge/…/hotspot/CarHotspotModeInit.kt` | 切模式时（`to != P2P_DIRECT`）调 `releaseLeftover()`：**不扫的话**上游 `ManualHotspotManager` 会按网卡名打分优先选中那张 `p2p*` 残留（80+30 > 70+30），把 iPhone 指向 `192.168.49.1`（它到不了）⇒ 40 秒 attach 超时 |
| 10 | `car/core-bridge/…/CarAppServices.kt` | `rootP2pManager` **字段级单例**（与 `adbSession` 共用同一份 adb server —— 两份实例会互相 `kill-server`），同时交给会话桥、模式切换器与诊断包 |
| 11 | `car/core-bridge/…/developer/CarDiagnosticPackager.kt` + `…/CarDeveloperMode.kt` | 诊断包 root 段新增「DBPlay 自己的那条路实测」（`rootP2pProbe`，**只读**：`PING`/`HELP`/`P2P_GROUP`）——上一版的 `wpa_cli` 那一格在本机永远是空的，等于问了没答 |
| 12 | `car/core-bridge/src/test/…/CarRootP2p{Output,Commands,Plan,Contract}Test.kt`（新增） | +56 用例：解析（含"缺标记必须判不可信"）、命令形状（含"`su 0` 后第一个词必须是 `sh`"）、plan 翻译（含口令长度闸门）、结构契约（入口类可反射、三段顺序、不杀别人的 hostapd、只用 `peek`） |
| — | `car/api/…/hotspot/CarAdbHotspotContract.kt` | 新增常量 `LOCAL_P2P_UDHCPD_CONF`：P2P 组的 `udhcpd` 配置**不与自建热点共用文件**（两张网的 `interface=` 不同，而两个 DHCP 进程可能同时活着） |

**为什么不碰上游**：拿到凭据后走的是**上游 `MANUAL`** —— 它的语义本来就是
"attach 一张已经在跑的网"（`ManualHotspotManager` 只读配置、从不 `startSoftAp`），
而此刻那张组确实在跑（由我们建的）。于是出口与「复用车机热点」共用同一条下游链路。

**为什么用 `app_process` 而不是 `wpa_cli`**：设备上没有 `wpa_cli`（见上表），
随包 busybox 1.28.1 的 `nc` 又不支持 `-U`。`app_process` 起的是**我们自己 APK 里的类**
（与本仓 `LynkCapabilityProbe.APP_PROCESS` 同一手法）⇒ 不新增原生二进制、不依赖设备工具。

**验证**：`:shared` 226 / `:car:core-bridge` 605 / `:car:platform-geely` 44 用例，**0 失败**；
`:car:app:lintVitalRelease`（`checkDependencies=true`）通过。**未实机验证**。

**如实标注的边界**：

1. **"这台车的 supplicant 有没有编 P2P"仍未实测** —— 它决定这条路成不成立。
   下一次装车采诊断包时，`system/wifi_direct.txt` 的 root 段会直接给出答案
   （`P2P_* 有 N 条` / `没编 P2P 支持，root 也救不回来`）。
2. **P2P 与自建热点共用同一台射频**：检测到 `hostapd` 还在跑时**只告警不处置**
   （各模式互不影响是车主要求，见 PATCH-0068），建组可能因此被驱动拒。
3. 打开车机 Wi-Fi（STA）可能与车机自带热点互斥（驱动并发能力决定）；本轮按
   "车机本来就开着就不碰、是我们打开的就关回去"处理，**未实机验证**。

**待补登记（既有缺口）**：代码里引用到的 `PATCH-0068`（热点模式隔离/停止修复）、
`PATCH-0069`（仪表 Surface owner 对齐）、`PATCH-0070`（`LynkCh341I2c` lint 修复）
截至目前**在本文件里没有条目** —— 它们来自上一轮未提交的改动。本次不动它们，
只如实记下这个缺口（改 `shared/` 或根 `build.gradle.kts` 前必须先补上）。

---

## PATCH-0072 · P2P 建组前先关掉车机自带热点（照抄原厂应用的必经步骤）+ 打开框架侧 Wi-Fi/P2P 日志

**日期**：2026-10-11　**状态**：✅ 已应用（**未实机验证**）　**影响上游**：**否**（全部落在 `car/`）

**车主口径（verbatim）**：

> 这个能在车机上实现p2p连接：`C:\Users\Santali\Desktop\CarPlay\ick\ICCOA Carlink_1.0.20251121L(d2).apk`

**依据（对那份 APK 的反编译；sha256 `0a6ed6b08ebfb40062528ce00883c76ab5986b6520332266bb86c34f5e8cc1e2`，
`com.geely.carlink` v1.0.20251121L(d2)，`sharedUserId=android.uid.system`，3 056 类，jadx 1.5.5 全量导出）**：

| 观察 | 出处（反编译原文） |
| --- | --- |
| **车机声明"只走 P2P、不开 SoftAP"** | `CarLinkService.java:158` `new UCarConfig.Builder()…setSupportP2P(true)…setSupportSoftAP(false)` |
| **每次开始 P2P 之前先关共享热点** | `WifiP2pService.r()`（由 `open()` 调用）：`getWifiApState()` ∈ {12,13,14} → 日志 `"wifi ap state = " + n + ", stopTethering"` → `TetheringManager.stopTethering(0)`（API≥30）或反射 `IConnectivityManager.stopTethering(0, pkg)`（走 `ConnectivityManager.mService`） |
| Wi-Fi 上电方式 | `m()`：`setWifiEnabled(true)` 后**等 `WIFI_STATE_CHANGED` 广播**再继续 |
| 建组方式（Android 9 = 本机） | 反射隐藏 `setWifiP2pChannels(channel, listenChannel=**0**, operatingChannel=ch, listener)`，然后在它的 **onSuccess 与 onFailure 两处**都调 `createGroup(channel, listener)`（2 参重载、系统生成凭据）；日志 `Method selected: Classic` |
| 建组方式（API≥29） | 反射 `WifiP2pConfig$Builder`（`setNetworkName` / `setPassphrase` / `setGroupOperatingFrequency`）→ `createGroup(channel, config, listener)`；SSID `DIRECT-vs-XXXXXX`、口令 8 位 |
| 建组后的取凭据 | `requestGroupInfo` → `WifiP2pGroup.getNetworkName()/getPassphrase()/getInterface()` → `NetworkInterface.getByName(iface)`，再把 SSID/口令/GO MAC/频点交给手机 |
| **它不用**厂商私有接口 | 全量源码里 `tcam`/`ipcp`/`ecarx`/`setprop`/`Runtime.exec` **零命中**；只有 `android.os.SystemProperties` 一处（读 QTI 解码属性，与 P2P 无关） |

**推论（标为推断，等下一份包验证）**：车机自带热点（本机常态开着、随车自启）与 P2P 组主
在框架/驱动层面互斥 ⇒ 热点开着时框架对 `createGroup` 一律回 `BUSY` —— 这正是我们实测
"4 次尝试全部 5 ms 内 BUSY、换 5 个信道无效"的唯一合理解释（PATCH-0066 记录的那组现象）。

**改动**：

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `car/api/…/hotspot/CarRootP2pCommands.kt` | 新增两个动作常量 `Verb.AP_STATE`（只读）/ `Verb.STOP_TETHERING`（写）；把命令外壳抽成共用的 `appProcessCommand(...)`，新增 `tetheringCommand(prefix, apkPath, verb)`（这两个动作**不需要** supplicant socket） |
| 2 | `car/core-bridge/…/hotspot/CarRootP2pTool.kt` | 新增 `ap-state` / `stop-tethering` 两条入口：`getWifiApState()` 读状态；开着则**按最可能命中的顺序逐个试** `TetheringManager.stopTethering(0)` → `IConnectivityManager.stopTethering(0)` → `(0, pkg)` → `WifiManager.setWifiApEnabled(null, false)`，**关完再读一次**并把 before/after 与"试过哪些、谁成了"原样回报 |
| 3 | `car/core-bridge/…/hotspot/CarRootP2pManager.kt` | `start()` 在**建组之前**加一步 `recordSoftApHandling(prefix)`（条件与厂方应用逐字一致：只有热点在 {12,13,14} 才动手）；诊断报告新增**第一行**"车机自带热点状态"（只读） |
| 4 | `car/core-bridge/…/developer/CarDiagnosticPackager.kt` | 🔴 **补诊断盲区**：`logcat_system.txt` 里 `WifiP2p`/`p2p`/`softap`/`tether`/`wlan0` 命中数**连采两包全是 0**（白名单是按"仪表/投屏"建的）⇒ `KEEP_TAGS` 加 `WifiP2p`/`WifiControllerIPCP`/`WifiApState`/`SoftAp`/`ClientModeImpl`/`wificond`，`KEEP_KEYWORDS_LOWER` 加 `wifip2p`/`softap`/`tether`/`wpa_supplicant`/`wifi_ap_state`/`wifi_state_changed` |
| 5 | `car/core-bridge/src/test/…/CarRootP2p{Commands,Contract}Test.kt` | +4 用例：两个新动作的命令形状（不带 socket、`su 0` 后第一个词仍是 `sh`）、"建组前必须先处置热点"、"多种签名逐个试 + 关不掉也要如实回报"、"诊断包必须能看见框架侧日志" |

**为什么我们做得到（普通应用做不到）**：`stopTethering` / `setWifiApEnabled` 需要
`TETHER_PRIVILEGED` / `NETWORK_SETTINGS`；而这个类是以 **root（adb `su 0`）** 起的
`app_process` 进程，框架的权限检查对 **uid 0** 放行 —— 于是我们能做厂方应用
（凭 `android.uid.system`）做的同一件事。**没有**去改 SELinux 标签、**没有**新增原生二进制。

**副作用（必须知道）**：P2P 模式下**车机自带热点会被关掉**（互斥是物理属性，厂方应用也是这么做的）；
切回「复用车机热点」模式不受影响（那一步只在 `P2P_DIRECT` 里跑）。

**验证**：`:shared` 226 / `:car:core-bridge` **609**（+4）/ `:car:platform-geely` 44 用例，**0 失败**；
`:car:app:lintVitalRelease`（`checkDependencies=true`）通过。**未实机验证**。

**下一次上车怎么读**：P2P 模式连接时的日志里会出现
`root p2p step OK  车机自带热点（SoftAP）处置｜wifiApState(before)=13（ENABLED(开着)）⇒ 尝试关闭… ⇒ wifiApState(after)=11`；
诊断包的 `system/wifi_direct.txt` 第一行也会直接写出热点状态，且 `logcat_system.txt` 这次**会带上**
`WifiP2pServiceImpl` 的拒绝原因 —— 那才是"为什么 BUSY"的最终答案。

---

## PATCH-0073 · 按厂商方案建 P2P：厂商框架路升为一等公民（root 直驱 supplicant 退居兜底）

**日期**：2026-10-11　**状态**：✅ 已应用（**未实机验证**）　**影响上游**：**否**（全部落在 `car/`）

**车主口径（verbatim）**：

> 开始按照厂商的方案执行

**改动**：

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `car/api/…/CarRootP2pContract.kt` | 接口新增 `prepareEnvironment()`（默认 null）：P2P 之前的**必要前置**（本机 = 关掉车机自带热点）。由 `CarRootP2pManager` 实现（它才拿得到 root 工具） |
| 2 | `car/core-bridge/…/hotspot/CarVendorP2pGroup.kt`（新增） | **厂商框架路**，逐字照抄厂方四步：① STA 上电 → ② `WifiP2pManager.initialize` → ③ 反射隐藏 `setWifiP2pChannels(channel, **listen=0**, operating=ch, listener)`，**在它的成功与失败两处**都接着 `createGroup(channel, listener)`（2 参重载，Android 9 唯一可用）→ ④ 轮询 `requestGroupInfo` 读回 `getNetworkName()/getPassphrase()/getInterface()`；`isGroupOwner` 为假就拆掉重来 |
| 3 | `car/core-bridge/…/hotspot/CarVendorP2pChannelPlan.kt`（新增） | 信道计划的**纯函数**：厂方规则是"STA 当前信道（频段相符时）优先，否则用手机给的回退信道"（反编译 `a.b.a.j.e.a`）；我们侧没有那套手机协议 ⇒ 退化成候选表 `[5180, 5745, 2437, 2412, 2462]` + STA 同频段信道排第一；另含**信道↔频点**互推（`WifiP2pGroup.getFrequency()` 是 API 29 才有的，本机读不了，厂方在 9 上也是带着请求频点走） |
| 4 | `car/core-bridge/…/hotspot/CarRootP2pManager.kt` | 实现 `prepareEnvironment()`（`peek()` 通道 → 跑 `stop-tethering` → 把 before/after 报告返回并记录） |
| 5 | `car/core-bridge/…/CarPlaySessionBridge.kt` | `ensureRootP2pGroup` → **`ensureP2pGroup`**：① 统一做前置（关热点）→ ② **厂商框架路** → ③ **root 直驱 supplicant**（PATCH-0071）→ ④ 都不成则原样返回 plan（上游 legacy）。新增 `vendorP2p` 构造参数（默认 null ⇒ 与改动前逐字一致）；`stop()`/`close()` 两条路都收 |
| 6 | `car/core-bridge/…/CarAppServices.kt` | 新增 `vendorP2pGroup = CarVendorP2pGroup(...)` 并注入会话桥（单例，理由同 `rootP2pManager`） |
| 7 | `car/core-bridge/src/test/…/CarVendorP2pChannelPlanTest.kt`（新增）+ `…/CarRootP2pContractTest.kt` | +11 用例：信道/频点映射与非法值、候选表顺序与去重、无 DFS、SDK28 只能 classic、信道频点互推自洽；契约侧钉住"厂商路排在 root 路之前""前置在两条路之前统一做""四步逐条存在（`setWifiP2pChannels`/`listen=0`/2 参 `createGroup`/`requestGroupInfo`/`getNetworkName`+`getPassphrase`+`getInterface`/`isGroupOwner`）" |

**与厂方的有意偏离（写在代码注释里，避免后人误读）**：

1. **工作信道**：厂方由**手机**通过 `createGroupForClient(band, mac, fallbackChannel)` 指定
   （`ShareLinkService` 的 `this.t` 就是那个回退值）；我们没有那套协议 ⇒ 退化成候选表逐个试。
2. **API 29+ 的 `WifiP2pConfig$Builder`**（厂方用它设 SSID/口令/频率）：本机 28 不存在 ⇒ 只走 classic
   （厂方在本机的日志同样是 `Method selected: Classic`）。
3. **收尾**：厂方把组生命周期交给系统；我们显式 `removeGroup`（"各模式互不影响"的要求）。

**lint 又抓到一处 API 级别问题**：`WifiP2pGroup.getFrequency()` 是 **API 29** 才有的，
`:car:app:lintVitalRelease` 直接拦下（PATCH-0066 的防线生效）⇒ 改为按"请求的信道"反推频点
（厂方在 9 上也是这么做的）。

**验证**：`:shared` 226 / `:car:core-bridge` **620**（+11）/ `:car:platform-geely` 44，**0 失败**；
`:car:app:lintVitalRelease` 通过。**未实机验证**。

**下一次上车怎么看**：P2P 模式日志会按顺序出现 `车机自带热点（SoftAP）处置…` →
`p2p step OK  setWifiP2pChannels(listen=0, operating=36)` → `p2p step OK  P2P 组主已就绪（厂商框架路）…`；
若走了 `ADB 直驱 supplicant`，说明厂商那条在本机确实不成立 —— 那时诊断包里的
`WifiP2pServiceImpl` 日志会说明原因（这一轮刚把它的日志白名单补上）。

---

## PATCH-0075 · 诊断包新增「厂商 init 脚本」（只读）—— 车机热点由谁拉起、能不能 `ctl.stop`

**日期**：2026-10-11　**状态**：✅ 已应用（**未实机验证**）　**影响上游**：**否**（只动 `car/core-bridge` 诊断层）

**车主口径（verbatim）**：

> 了解下这个：`ls -l /vendor/etc/init/udhcp*.rc hostapd*.rc wlan*.rc 文件`
> …（车机实测清单）… `/vendor/etc/init/hostapd.android.rc`、`/vendor/etc/init/hostapdwlan.rc`、`/vendor/etc/init/udhcpdwlan.rc`
> 你直接在诊断包加入吧

**这一批证据说明什么**：车机自带热点**确实是 init 体系里的服务**（推翻了 PATCH-0072 里
"从 `init.svc.*` 看不到 ⇒ 不由 init 管"的推断）——那三份脚本就在 `/vendor/etc/init/` 下。
而两次采集里 `init.svc.hostapdwlan` / `init.svc.udhcpdwlan` **都不存在**（属性只在服务状态
变化时才写）⇒ 那两个服务当时**没在跑**，或者跑的是框架那份（`hostapd.android.rc`）。
**这两种情况的处置方式完全不同，必须看脚本内容才能定**：有服务名就能 `setprop ctl.stop <名字>`
干净停掉（init 不会当崩溃再拉一次）；有 `on property:` 触发就得连触发属性一起关。

**改动**：

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `car/core-bridge/…/developer/CarDiagnosticPackager.kt` | `system/wifi_direct.txt` 的 root 段新增 `# 厂商 init 脚本（只读）`：按**名字**逐个 `cat` 车主点名的三份（`hostapd.android.rc` / `hostapdwlan.rc` / `udhcpdwlan.rc`），再带上厂商侧另外四份（`vendor.ecarx.xma.connectivity@1.0-service.rc` / `android.hardware.wifi@1.0-service.rc` / `com.qualcomm.qti.wifidisplayhal@1.0-service.rc` / `manifest_sku.rc`）、`/vendor/etc/init/` 全量清单与关键字过滤清单、glob 兜底 cat、`ps -A` 里的 hostapd/udhcpd/dnsmasq、相关 `init.svc.*`、以及框架侧 `dumpsys wifi|tethering` 的 AP/tethering 段 |
| 2 | `car/core-bridge/src/test/…/CarDiagnosticVendorInitContractTest.kt`（新增） | +4 用例钉住：三份按名字 cat（不靠通配符）、厂商侧四份也在、必须同时给目录/进程/`init.svc`/tethering 状态、**不许 cat 热点配置文件**（`softap.conf`/`wpa_supplicant.conf`/`hostapd.conf` 里有 SSID/口令，包要上传服务器） |

**纪律**：这一批全**只读**（`ls`/`cat` 脚本本身 + `ps`/`getprop`/`dumpsys` 的只读转储），
**不 cat 热点配置文件**。用 root 跑（没有 root 退回 system shell）——`/vendor` 下的文件普通应用读不到。

**验证**：`:car:core-bridge` **624** 用例（+4），0 失败。**未实机验证**（下一次采包即可看到这一段）。

**下一步（等这批内容到手）**：把「关车机热点」从"框架 API 尽力而为"升级成**确定性动作**
（`setprop ctl.stop <服务名>` + 关掉它的 `on property:` 触发），P2P 的前置才算闭环。

---

## PATCH-0076 · 调试反连隧道：端点与口令**三处一起脱敏**（界面打码 / 日志 / 诊断包）

**日期**：2026-10-11　**状态**：✅ 已应用（**未实机验证**）　**影响上游**：**否**（`car/api` + `car/ui` + `car/app` + `car/platform-geely`）

**车主口径（verbatim）**：

> 这些都是明文，我需要你默认值隐藏，不能别人看见

（附开发者页截图：`服务器地址 39.108.105.65`、`服务器端口 7000`、`本机 adbd 端口 5555`、
`连接口令 dbplay-2026` 全是明文；本文不复述具体值。）

**问题不止一处**：这些值会出现在**三个**地方，而原来只有一处做了脱敏。

| 出现的地方 | 原来 | 谁看得到 |
| --- | --- | --- |
| 开发者页输入框 | 🔴 明文 + **placeholder 也写着真实值** | 站在车机前的人（截图即为证） |
| 应用日志（`appendLog` / `LynkDebugTunnel` 的 `Log.i`） | 🔴 明文 `host:port` | 车机屏幕的日志面板 + **诊断包** |
| `system/effective_settings.txt`（诊断包） | 🔴 `debugTunnelHost` 明文（密钥原本已打码） | **上传到服务器** ⇒ 等于谁都能看 |

**改动**：

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `car/api/…/developer/CarSensitiveText.kt`（新增） | **脱敏口径收口成一处**：`label(v)` / `hostLabel(v)` ⇒ 只报"有没有 + 多长"（`(未设置)` 与 `CarDiagnosticSettingsDump.NOT_SET` **逐字一致**，含半角括号） |
| 2 | `car/api/…/developer/CarDiagnosticSettingsDump.kt` | `debugTunnelHost` 改为 `CarSensitiveText.hostLabel(...)`（**包要上传，不落原值**）；`maskedSecret` 转发到同一实现 |
| 3 | `car/ui/…/screens/DeveloperScreen.kt` | 服务器地址 / 连接口令两个框加 `isPassword = true`（与热点密码同一手法）；**placeholder 不再写真实值**（原来那行提示填的就是真实跳板地址）；描述里说明"默认打码显示，点进输入框可查看/修改" |
| 4 | `car/app/…/CarAppController.kt` | 两条隧道日志改用 `CarSensitiveText.hostLabel(...)`（日志会进屏幕日志面板与诊断包） |
| 5 | `car/platform-geely/…/lynk/LynkDebugTunnel.kt` | 两条 `Log.i`（"正在反连服务器 …" / "已连上服务器 …"）同样脱敏 |
| 6 | 测试 | `CarSensitiveTextTest`（+4：只报长度不回原值 / 空值报未设置 / 地址同口径 / 默认口令长度口径）+ `DeveloperTunnelUiMaskingContractTest`（+2：两个框必须 `isPassword = true`、**界面源码里不许出现默认地址与默认口令**）+ `CarDiagnosticSettingsDumpTest` 加两条断言（默认地址不得出现在包里） |

**没动的两处（刻意，附理由）**：`服务器端口` / `本机 adbd 端口` 仍显示原值 ——
它们不是凭据，**单看端口没有意义**（地址已打码），而把数字打码后就没法核对与编辑了。
若车主仍要打码，改 `DeveloperScreen` 两行即可（说一声就做）。

**⚠️ 仍未堵住的那一层（需要车主决定）**：`CarSessionSettings.debugTunnelHost` 与
`debugTunnelSecret` 的**默认值是源码里的字面量** ⇒ 拿到 APK 的人**反编译就能读出来**，
只是不再"印在屏幕上"。要连这一层也堵住，需要二选一（或都做）：

1. **轮换口令**：截图里已出现过，且它在仓库历史里 —— 换一个新的，并同步服务器端；
2. **默认值改成构建期注入**（本地不入库的 properties → `BuildConfig`，缺省为空）⇒
   分发给别人的包**根本不含**你的跳板地址与口令，你自己的构建照旧。

**验证**：`:car:core-bridge` **630** 用例（+6）/ `:shared` 226 / `:car:platform-geely` 44，0 失败；
`:car:app:lintVitalRelease` 通过。**未实机验证**。

---

## PATCH-0077 · `shared/`：Android 9 的 legacy P2P 改走「信道计划」（移植 DiPlay 那条路）

**日期**：2026-10-11　**状态**：✅ 已应用（**未实机验证**）　**影响上游**：**是**（`shared/`，本条目即台账登记）

> ⚠️ 本条是**补登记**：这批 `shared/` 改动与此前几批 `car/` 改动一起躺在工作区里，
> 直到要提交时才发现 `shared/` 侧还没登记（仓库规矩：改 `shared/` **必须先登记**）。
> 内容按**代码现状**回溯描述，不是事后编故事。

**改动**：

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `shared/…/network/LegacyWifiP2pChannels.kt`（新增） | Android 9 legacy 建组的**信道计划**（纯函数、有单测）：`planChannels()` = `[5180, 5745, 2437, 2412, 2462, null]`（5 GHz 非 DFS 优先，`null` = 系统默认重载）、`operatingChannelFor(freq)`、`LISTEN_CHANNEL = 6`。来源与逐条对照见该文件 KDoc（用户指定参考 `carlito12345/DiPlay`） |
| 2 | `shared/…/network/LegacyWifiP2pGroupManager.kt` | `createGroupWithRetry` 从"同一信道重试 4 次"改为**沿信道计划走**：每一步先 **pin 工作信道**（反射隐藏 `setWifiP2pChannels`，2s 有界）→ 经典重载 `createGroup` → 同信道 `BUSY` 只再试一次 → 换下一信道；`P2P_UNSUPPORTED`/`NO_PERMISSION` 立即失败；pin 结果未知（超时/`InvocationTargetException`）**中止整轮**；最后一步"系统默认重载"前先 `clearLegacyOperatingChannel`；删除已无用的 `CREATE_GROUP_MAX_ATTEMPTS`；新增 `pinLegacyOperatingChannel` / `clearLegacyOperatingChannel` / `invokeSetWifiP2pChannels` / `p2pInterfaceState`（`p2p0` 状态的旁证） |
| 3 | `shared/src/test/…/network/LegacyWifiP2pChannelsTest.kt`（新增） | 4 例：计划顺序与 `null` 尾项、频率↔信道映射、`LISTEN_CHANNEL` 取值、非 DFS |

**为什么现在归到台账**：这三份文件是"Android 9 框架 P2P 走不通"那一轮的**唯一上游改动**，
而后续 PATCH-0072/0073 的结论是"真因更可能是车机热点挡着，而不是框架不能用"——
⇒ 这批改动**仍然必要**（它让失败原因可分辨、并在真支持 P2P 的车机上沿信道计划重试），
但**优先级下降**：现在 P2P 的首选是厂商框架路（`car/` 侧），这批只在它失败后兜底时才会跑到。

**验证**：`:shared` 226 用例（含新增 4 例），0 失败。**未实机验证**。

---

## PATCH-0078 · 修「有没有 root」的判据：前缀为空 = **adbd 自己就是 root**，不是"没有 root"

**日期**：2026-10-11　**状态**：✅ 已应用（**真车日志已证实**）　**影响上游**：**否**（只动 `car/core-bridge` 的 P2P 实现与它的契约测试）

**证据（车主给的真车日志：CX11，设备 `b9a987caa019`，app 1.0.37）**：

```
adb hotspot step 5 OK  提权探测（adb root → shell → root）｜adb shell 本身就是 root：
  uid=0(root) gid=0(root) … context=u:r:su:s0
```

而同一份包里，P2P 专项快照的那一格却写着：

```
=== DBPlay 自己的那条路实测（PATCH-0071；只读，不建组不拆组） ===
（有 ADB 通道但没有 root：这条路的每一步都要 root）
```

⇒ 同一时刻，日志证明**有 root**，我的自检**说没有 root**。原因：`AdbShellSession.Access.prefix`
在"adbd 本身就是 root"时是**空串**（不需要 `su 0` 前缀），而我把"前缀为空"当成了"没有 root"。

**影响（不只是那条误报）**：

| 位置 | 后果 |
| --- | --- |
| `releaseLeftover()` | 直接 return ⇒ **残留清理永远不跑**（而它是"各热点模式互不影响"的兜底，PATCH-0068） |
| `stopInternal()` | 整段收尾跳过 ⇒ P2P 组 / 网卡地址 / DHCP **全留在车机上**（正是"模式互相污染"的成因） |
| `buildDiagnosticsReport()` | 诊断包误报"没有 root"，把下一次排障引向错误方向 |

**改动**：三处判据改看 `AdbShellSession.Access.rooted`（= `prefix != null`）；`prefixOrEmpty` 只用于
**拼命令**（那才是它的本意）。`:car:core-bridge` 新增 1 条契约测试钉住"不许再用 `prefix.isEmpty()`
判 root"（只扫代码行、跳过注释）。**验证**：`:car:core-bridge` **631** 用例，0 失败。**未提交**。

---

## PATCH-0079 · 🔴 安全区"矩形数当内边距用" ⇒ 报出去的 `safeArea` 高度为负 ⇒ iPhone 拆会话

**日期**：2026-10-11　**状态**：⚠️ **兜底已应用**（真正按原版改成矩形模型**待车主确认**）　**影响上游**：**否**

**车主口径（verbatim）**：*"会话被拆是因为在核心安全调整了参数，你看看这个怎么调整可以参看这个，使用画框调整：…GeelyCarPlay-吉利集成-还原"*

**证据（CX11 真车包，app 1.0.37）**：

```
airplay /info displays=[… safeArea={widthPixels=1912, heightPixels=-1560,
                                   originXPixels=1, originYPixels=1920} …]   ← 两块屏都是这组
# 同一次连接 15ms 后：
AirPlay iAP tunnel peer EOF → airplay rx TEARDOWN activeBefore=[130]（视频流一条都没建）
```

上游 `AirPlayInfoPlist.areaDict` 按 insets 现算（`height = 面板高 − top − bottom`、`originY = top`），
反解这组值 = **`top=1920`（> 1080 面板高）**、`bottom=720`、`left=1`、`right=7` ⇒ **高度为负**。

**原版为什么不会这样**（`GeelyClusterSafeArea`，即"画框"）：画框存的是**矩形** `(x,y,w,h)`；
上报前 `normalized(canvasW,canvasH)`（先把 w/h 夹进画布、再把 x/y 夹进 `0..canvas−w/h`），
最后 `toInsets()` 才转成四边 ⇒ **四边恒 ≥0 且自洽**。我们这边缺这一步，外部把矩形数
**直接当 insets** 塞进 `CarClusterSafeAreaInsets`，`CarPlayConfigBuilder` 又原样转给上游。

**本次改动（兜底防线）**：

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `car/api/…/display/CarClusterPresenter.kt` | `CarClusterSafeAreaInsets.clampedTo(面板宽, 面板高)`：把四边夹进面板（保证 `top+bottom ≤ H−1`、`left+right ≤ W−1`） |
| 2 | `car/core-bridge/…/CarPlayConfigBuilder.kt` | **主屏与副屏两条路**在转 `AirPlayInsets` 前都先 `clampedTo(...)` |
| 3 | `car/core-bridge/src/test/…/ClusterSafeAreaClampTest.kt`（新增） | +4 用例：真车那组脏值必须被夹成合法区域、正常值原样保留、**任意乱填穷举**不产出负宽高、面板尺寸为 0/负数不崩 |

**验证**：`:car:core-bridge` **635** 用例，0 失败。**未提交、未出包**。

**待车主决定（真正的修法）**：把「核心安全区」改成**矩形模型** —— 设置页做成**画框**
（照 `GeelyClusterSafeAreaEditorView`），`CarClusterSafeAreaInsets` 只允许由
`rect.normalized().toInsets()` 产出（外部直塞从类型上写不出来），并迁移存量四边值。

---

## PATCH-0080 · 「核心安全区」改成**矩形模型**（+ 画框 UI，口径照原版 `GeelyClusterSafeArea`）

**日期**：2026-10-11　**状态**：✅ 已应用（**未实机验证**）　**影响上游**：**否**
**分工**：计算层（本条目列出的文件）由我改；**画框 UI（`car:ui`）由另一个模型完成**，
两边约定已对齐（见下"坐标系"）。

**车主口径（verbatim）**：*"会话被拆是因为在核心安全调整了参数，你看看这个怎么调整可以参看这个，使用画框调整"* + *"按矩形模型 + 画框改"*

### 坐标系（**两边必须一致**，这是本次最容易再错的一处）

**rect 的坐标空间 = 主屏上报给 iPhone 的那块画布**（不是物理面板尺寸）。
依据：画框按 `CarUiModel.safeAreaCanvasWidthPx/HeightPx` 预览；报给副屏时再
`scaledTo(主画布 → 副屏画布)`。

### 改动（计算层）

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `car/api/…/settings/CarSessionSettings.kt` | `CarSettingsSafeArea` 由**四边内边距**改成**矩形** `(x, y, width, height)`；`isEmpty = width<=0 || height<=0`；新增 `normalized(canvasW, canvasH)`（照原版：先夹 w/h、再夹 x/y，最小 `300×200`）、`scaledTo(fromW,fromH,toW,toH)`、`toInsets(canvasW,canvasH)`（`top=y / bottom=(H−y)−h / left=x / right=(W−x)−w` ⇒ **四边恒 ≥0**） |
| 2 | `car/core-bridge/…/SharedPreferencesCarSettingsStore.kt` | 读写改成新键 `safe_area_x/y/w/h`；老的四个内边距键保留**一次性换算**（`x=left,y=top,w=1920−left−right,h=1080−top−bottom`，且**只在真的填过时**才换算 ⇒ 没动过这项的老安装行为不变） |
| 3 | `car/core-bridge/…/CarPlayConfigBuilder.kt`（主屏） | 用户画过框 ⇒ `rect.toInsets(画布)`；没画过 ⇒ 保持 2026-10-06 定案 A 的"沉浸模式自动避让顶部"；两者末尾都过 `clampedTo()` 兜底（PATCH-0079） |
| 4 | `car/api/…/display/CarClusterPresenter.kt` | `CarClusterSafeAreaInsets.clampedTo(面板宽, 面板高)`（PATCH-0079 的安全网，保留） |
| 5 | 测试 | `SafeAreaRectModelTest`（+5：未设置判据 / 四边恒非负且自洽 / 坐标越界被收进画布 / 缩放后仍在目标画布内 / 最小尺寸取 `min(300,canvas)`）+ `ClusterSafeAreaClampTest`（+4，PATCH-0079） |

**为什么这样就不会再出事故**：矩形 → `normalized()` → `toInsets()` 这条链保证
`top+bottom+h == H` 且四边 ≥0；上游按 insets 现算的 `height = H−top−bottom` **恒为正**。
（CX11 那次是 `top=1920`（> 1080）⇒ 高度 −1560 ⇒ iPhone 拆会话；现在这种输入会被收进画布。）

**验证**：`:car:core-bridge` **640** 用例（+9），0 失败；`:car:ui` / `:car:app` 编译通过
（与另一模型的画框 UI 联编成功）。**未提交、未出包。**

---

## PATCH-0081 · 修「关车机热点」工具 **abort**（裸 `app_process` 缺主 Looper）+ 加不依赖 Context 的兜底

**日期**：2026-10-11　**状态**：✅ 已应用（**真车日志已证实问题**）　**影响上游**：**否**

**证据（车主给的真车包：CS11，app 1.0.44）**：

```
FAIL XCExt.p2p: 车机自带热点（SoftAP）处置｜工具没有回报（原始输出：Aborted）
  ↑ 同一轮里出现两次（厂商框架路之前一次、root 路之前一次）
FAIL XCExt.p2p: 厂商框架路未建出组主｜框架建组失败：超出总预算（已试到信道 149）
FAIL XCExt.p2p: wpa_supplicant 进程｜没有在跑（Wi-Fi 已 ENABLED）
W    xcertplay-usb: wireless bring-up failed      ← 因此整轮没有 CarPlay 会话
```

`Aborted` = 进程收到 **SIGABRT**：那两条动作（`ap-state` / `stop-tethering`）走
`ActivityThread.systemMain()` 取 Context，而**裸 `app_process` 没有主 Looper** ⇒ 直接 abort。
后果：**车机热点根本没被关**，而"热点还开着"正是 P2P 建组被回 `BUSY` 的前提（PATCH-0072 的推导）。

**改动**（`car/core-bridge/…/hotspot/CarRootP2pTool.kt`）：

| # | 改动 |
| --- | --- |
| 1 | `systemContext()` 里**先 `Looper.prepareMainLooper()`**（重复调用由 `runCatching` 吃掉）再 `ActivityThread.systemMain()` |
| 2 | `softApStoppers()` 改为"有 Context 就用那几条；**无论如何都附上**不带 Context 的兜底" |
| 3 | 新增 `stopSoftApViaBinder()`：`ServiceManager.getService("wifi")` + `IWifiManager$Stub.asInterface(...)`（**序号由框架 stub 决定，一个数都不猜**）→ `setWifiApEnabled(null, false)` |
| 4 | `CarRootP2pContractTest` +1 条契约测试：`systemContext()` 必须先 `prepareMainLooper`、必须有不依赖 Context 的 `ServiceManager` 兜底、兜底必须走 `asInterface`（不许写死 transaction code） |

**验证**：`:car:core-bridge` **641** 用例，0 失败。**未提交、未出包。**

**下一次上车怎么读**：那一行应该从 `FAIL …（原始输出：Aborted）` 变成
`… wifiApState(before)=13（ENABLED(开着)）⇒ 尝试关闭… ⇒ wifiApState(after)=11（DISABLED(关着)）`；
若仍是 13，说明这台车的 AP 由厂商 IPCP 代管、框架 `setWifiApEnabled` 改不动它
（那时下一步是"厂商 init 服务名 + `ctl.stop`"，见 PATCH-0075 采集到的那三份脚本）。

---

## PATCH-0082 · P2P 建组：**pin 结果未知就别掐整轮**；每个信道的时间预算各自有界

**日期**：2026-10-11　**状态**：✅ 已应用（**真车日志已证实问题**）　**影响上游**：**是**（`shared/…/LegacyWifiP2pGroupManager.kt`）

**证据（车主给的真车包：CS11，app 1.0.44）**：

```
FAIL XCExt.p2p: setWifiP2pChannels(listen=0, operating=36)｜没确认成功
FAIL XCExt.p2p: createGroup 回调｜onFailure(2)                     ← 2 = BUSY，5ms 级就回
FAIL XCExt.p2p: 厂商框架路未建出组主｜超出总预算（已试到信道 149）    ← 头一个信道就吃掉 20 秒
W    xcertplay-usb: Wi-Fi P2P legacy channel pin callback timeout channel=36
W    xcertplay-usb: startup failed type=IOException: channel pin outcome unknown at channel 36
                   ↑ 上游 legacy 路**第一个信道就把整轮掐了**，createGroup 一次都没发
```

**两条改动**：

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `car/core-bridge/…/hotspot/CarVendorP2pGroup.kt` | `createGroup` **回调**等待 15s → **4s**；并给**每个信道**各自一个 3s 的"组是不是其实建出来了"观察窗（不再用全局 deadline）⇒ `BUSY` 5ms 回来时立刻换下一个信道，**5 个信道都试得到**（旧逻辑只试到 2 个） |
| 2 | `shared/…/network/LegacyWifiP2pGroupManager.kt` | pin **超时/异常**不再 `throw`（旧行为="中止整轮"）；pin 返回 false 也不再 `continue` 跳过该信道 ⇒ 一律**记一条诊断后照常 `createGroup`**。依据：车机厂方应用 `WifiP2pService.i.b` 的 pin 回调**成功与失败都接着 createGroup**（pin 只是"希望用这个信道"，不是前提） |

**顺带记录一台车的差异（PATCH-0075 采集到的实证）**：

| 车 | `/vendor/etc/init/` 里的热点脚本 | 含义 |
| --- | --- | --- |
| **CS11**（本包） | 只有 `hostapd.android.rc`（`service hostapd /vendor/bin/hw/hostapd`、`class main`、`disabled`、`oneshot`、HIDL `IHostapd`） | 车机热点 = **AOSP 框架 SoftAP** ⇒ "关热点"走框架 `setWifiApEnabled` 是**对症**的（PATCH-0081 修的就是它） |
| **CX11**（车主早先贴的清单） | 另有 `hostapdwlan.rc` / `udhcpdwlan.rc`（厂商自建 AP + DHCP） | 那台车的热点可能由**厂商 init 服务**拉起 ⇒ 需要"服务名 + `ctl.stop`"（等 CX11 的采集包） |

**验证**：`:shared` 226 / `:car:core-bridge` **641** 用例，0 失败。**未提交、未出包。**

---

## PATCH-0083 · 让 P2P 失败**可归因**：先读框架的 `wifi_p2p_state`（粘性广播，只读）

**日期**：2026-10-11　**状态**：✅ 已应用　**影响上游**：**否**

**要解决的问题（连续几份真车日志的共性）**：厂商框架路失败时只有一行
`createGroup 回调｜onFailure(2)`（`2 = BUSY`），**看不出下一步该干什么**：
是"框架的 P2P 服务根本没起来"（该去把 P2P 弄起来），还是"命令一时排不上队"（该换信道/重试）。

**改动**：

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `car/core-bridge/…/hotspot/CarWifiP2pStateReader.kt`（新增） | 读 `android.net.wifi.p2p.STATE_CHANGED` **粘性广播**里的 `wifi_p2p_state`（1=启用 / 0=未启用）⇒ 三态 `ENABLED / DISABLED / UNKNOWN`；`describe()` 把"该做什么"直接写进日志（未启用 ⇒ 指出"只会回 BUSY，该把 P2P 弄起来，而不是换信道"）；顺带记 `FEATURE_WIFI_DIRECT`。**只读**：不留监听器、不动 Wi-Fi 开关 |
| 2 | `car/core-bridge/…/hotspot/CarVendorP2pGroup.kt` | `start()` 在建组之前先记一条"框架 P2P 状态（粘性广播，只读）"，与后面的 `onFailure(2)` 互为旁证 |
| 3 | `car/core-bridge/src/test/…/CarWifiP2pStateReaderTest.kt`（新增） | +3 用例：1/0/其它值的三态映射（**读不到必须 UNKNOWN 且放行**，不许当"不可用"——本工程反复栽过的坑）、未启用时的文案要指出下一步、未知时的文案要明说放行 |

**验证**：`:car:core-bridge` **644** 用例，0 失败。**未提交、未出包。**

**下一份包会多出这一行**：
`OK/FAIL 框架 P2P 状态（粘性广播，只读）｜框架报 P2P 未启用（wifi_p2p_state=0）→ 这时 createGroup 只会回 BUSY…`
—— 有了它，"这台车该不该继续折腾 P2P"就不再靠猜。

---

## PATCH-0084 · 关完热点要**等到它真的 DISABLED** 再建组（异步落地）

**日期**：2026-10-11　**状态**：✅ 已应用　**影响上游**：**否**

**车主口径（verbatim）**：*"P2P明确可以用"* ⇒ 不回落自建热点，继续把 P2P 抠通；
而"建组被回 BUSY"就不能停在"热点还开着"这个前提上。

**为什么要等**：`stopTethering` / `setWifiApEnabled(false)` 都是**异步**的 ——
AP 要经过 `DISABLING(10)` 才落到 `DISABLED(11)`。厂方应用是"关掉共享热点 → 开 Wi-Fi → 建组"，
但它**没有等 AP 落地**；这中间去建组，等于又撞回"热点占着射频"那堵墙。

**改动**（`car/core-bridge/…/hotspot/CarRootP2pManager.kt`）：

| # | 改动 |
| --- | --- |
| 1 | `handleSoftAp()`：发完"关热点"之后，若报告里 `after` 仍是 `10/12/13`，就**轮询 `ap-state` 直到 `wifiApState=11`（DISABLED）**，总预算 `SOFT_AP_DISABLE_WAIT_MILLIS = 6s`、间隔 `SOFT_AP_POLL_MILLIS = 400ms` |
| 2 | 报告里追加结果：`；等待确认：已到 DISABLED(11)` 或 `；⚠️ 等待 6000ms 仍未到 DISABLED：…（这台车的 AP 可能由厂商 IPCP 代管，框架关不掉它）` —— **等不到也如实写** |
| 3 | `CarRootP2pContractTest` +1 条：必须轮询等到 `wifiApState=11`、等待必须有界、等不到必须如实写 |

**验证**：`:car:core-bridge` **645** 用例（+1），0 失败。**未提交、未出包。**

**下一份包会多出这一段**：
`车机自带热点（SoftAP）处置｜wifiApState(before)=13 … ⇒ 尝试关闭… ⇒ wifiApState(after)=11；等待确认：已到 DISABLED(11)`
然后才轮到 `setWifiP2pChannels` / `createGroup` —— 这才是"热点已让位"之后的干净对照。

---

## PATCH-0085 · 「安全区画框拉满整屏」=「没画过」（别再顶掉沉浸模式的顶部避让）

**日期**：2026-10-11　**状态**：✅ 已应用　**影响上游**：**否**

**真车实证（CS11，1.0.44，2026-10-11 15:11）**：设置里存的矩形是 `x=0 y=0 w=1920 h=1080`
（整屏），于是同一份日志里出现**自相矛盾**的两处：

| 位置 | 内容 |
| --- | --- |
| `buildSession` 行 | `声明 safeArea.top=72px`（沉浸模式的顶部避让生效过） |
| `/info` 里主屏 | `safeArea.originYPixels=0`（全 0 ⇒ 等于什么都没声明） |

根因是分支顺序：**"画过框"优先于"沉浸模式自动避让"**，而"拉满整屏"这条框算出来的四边
恰好全 0 ⇒ 它把 2026-10-06 定案 A 的"主屏顶部避让 72px"**静默顶掉**。

**改动**（`car/core-bridge/…/CarPlayConfigBuilder.kt`）：算出来的四边**全为 0** 就按
"**未设置**"处理（与"没画过"完全等价）⇒ 走沉浸分支；`clampedTo()` 兜底（PATCH-0079）保留。

**验证**：`:shared` 226 / `:car:core-bridge` 用例、`:car:app:lintVitalRelease` 全绿。**未提交、未出包。**

---

## PATCH-0086 · 复用模式：诊断不再**冒认我方自建地址**为车机热点；识别不到就**当场拦下**

**日期**：2026-10-11　**状态**：✅ 已应用　**影响上游**：**否**

**要解决的问题（车主口径：*"复用车机热点呢"* → *"改"*）**：最新包
`日志\1791702691839_...`（1.0.44）的 `system/wifi_direct.txt` 里写着

```
车机热点网口: wlan0 192.168.176.1 192.168.176.0/24（车机固定网段 192.168.15.0/24）
```

`192.168.176.1` 是**我们自己**自建热点配上去的地址（我方候选池之一），却被当成"车机热点网口"
写进诊断 —— 那是**骗人的结论**。根因：老判据只排除「`CarAdbHotspotState.hostAddress` 记着的
那一个地址」，而进程重启后那份运行时状态就没了（`selfAddresses` 为空）⇒ 判据整个塌掉，
上游 `ManualHotspotManager` 便按网卡名挑到了残留地址通告给 iPhone。

**改动**：

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `car/api/…/hotspot/CarHotspotApInterface.kt` | 新增判据 `isSelfPoolOnly()`：**落在「我方候选池」（`SUBNET_CANDIDATES`：175~179 / 172.20 / 10.42）里、且不落在车机固定网段（`CAR_AP_SUBNET`）里的地址一律不算车机热点**。排除表因此变成两道并列：② 足迹地址（最准）、③ 候选池；**直判（落在车机网段里）永远压过 ③** ⇒ 万一车机真用了池里的网段，照样认得出来 |
| 2 | 同上（`debugLine`） | 结论为空时不再只写"识别不到"，而是写清**为什么**：`识别不到（读到的只有我方自建/候选网段地址：wlan0 192.168.176.1）` |
| 3 | `car/core-bridge/…/CarPlaySessionBridge.kt`（`ensureManualHotspotReady`） | 复用模式的**硬拦截**：`identify()` 识别不到车机热点网口 **且** 本地反射（`LocalSoftApReader.actuallyRunning`）也没看到一张在跑的热点 ⇒ `throw CarHotspotUnavailableException`，文案直接给出下一步"去车机设置里打开个人热点（本车固定在 192.168.15.x，随车自启）"+ 该行的诊断结论。**旁证只用来放行，从不用来拦截**（别的 ROM 形态不同但热点确实在跑时不打扰用户） |
| 4 | 同上（注释） | 修掉两处**陈旧的相反注释**：残留判据"不得按候选网段猜"（派生出池子兜底的由来）、`CarHotspotModeInit` / `CarHotspotResidue` 里同一句话 —— 现在按池判是**有据可依**的（车机网段已知且与池不相交） |

**测试**（`car/core-bridge/src/test/…/hotspot/`）：

| # | 用例 |
| --- | --- |
| 1 | `CarHotspotApInterfaceTest`：**只有** `wlan0 = 192.168.176.1`（不传足迹）⇒ 必须 **null**，且诊断行要写明"我方自建/候选网段"（1.0.44 冒认的回归测试） |
| 2 | `CarHotspotApInterfaceTest`：`wlan0 = 192.168.15.1` ⇒ 必须命中（含与 175/176 同时在场时取 15 那个） |
| 3 | `CarHotspotApInterfaceTest`：两条排除**互为双保险**（足迹挡不在池里的、池子挡足迹丢失的）+ **直判优先**（故意把车机网段与候选池设成同一个，15.1 仍被认出） |
| 4 | `CarHotspotModeIsolationContractTest`：复用分支必须先认足迹地址、池子只作兜底；识别不到必须**当场拦下**、判据与诊断**同源**、本地反射在跑时**放行** |

**验证**：`:shared` **226** / `:car:core-bridge` **648**（+3）/ `:car:platform-geely` **44** 用例，
0 失败；`:car:app:lintVitalRelease`（含 NewApi fatal 门禁）通过。**未提交、未出包。**

**下一份包会多出这一行**（复用模式下网卡上只有我方地址时）：
`车机热点网口: 识别不到（读到的只有我方自建/候选网段地址：wlan0 192.168.176.1）（车机固定网段 192.168.15.0/24）｜候选：…｜我方自建地址=…`
并且**不再**白等 40 秒：首页直接报
`复用车机热点：识别不到车机热点的网口与地址 …… 请先在车机设置里打开个人热点（本车固定在 192.168.15.x，随车自启），再重新连接。`

---

## PATCH-0087 · 复用车机热点：地址**由用户填**，我们不再"认网口"（同时撤回 PATCH-0086 的自动识别拦截）

**日期**：2026-10-11　**状态**：✅ 已应用　**影响上游**：**是**（`shared/`：加一个可选参数，向后兼容）

**车主口径（verbatim）**：*"复用车机热点是要用户自己手动设置，而不是自动识别"*

### 为什么掉头

PATCH-0086 的方向是"我们认车机热点网口 + 认不出就拦"。那是**错的方向**：两次认错都是我们
**猜到了自己自建热点的残留地址**——

| 包 | 我们把谁当成车机热点 | 后果 |
| --- | --- | --- |
| `1791624952142`（复用） | 自建残留 `192.168.176.1` 通告给 iPhone | 40 秒 `attach timed out` |
| `1791702691839`（诊断） | 把 `192.168.176.1` 写成"车机热点网口" | 结论骗人，排查被带偏 |

根因是**信息不足**：`wlan0` 上可能同时挂着"车机热点的地址"与"我方自建残留的地址"，
两者都是一张 UP 的 Wi-Fi 网卡上的 `192.168.x.1`。知道现场的人只有一个 —— 用户。
⇒ 复用改成"**用户填，我们照用**"：填了就以它为准，没填就**拦下并说清填什么**。

### 改动

| # | 层 | 文件 | 改动 |
| --- | --- | --- | --- |
| 1 | 设置 | `car/api/…/settings/CarSessionSettings.kt` | 新增 `manualHotspotHostAddress: String = ""`（**留空 = 未填**，不复用任何默认值）；只在「复用」这一支被读取 |
| 2 | 存储 | `car/core-bridge/…/SharedPreferencesCarSettingsStore.kt` | 新键 `hotspot_host_address` 的读写 |
| 3 | 诊断 | `car/api/…/developer/CarDiagnosticSettingsDump.kt` | `effective_settings.txt` 里报出它（空 = `(未设置)`）；白名单完整性测试因此要求它必须出现 |
| 4 | 方案 | `car/core-bridge/…/network/CarHotspotResolver.kt` | `CarHotspotPlan.manualHostAddress`（新字段）；**只有显式「复用」**才从设置带入（自建/P2P/SAME_LAN 都是 null）；summary 里写清"通告给 iPhone 的地址 X"或"⚠️ 还没填" |
| 5 | 拦截 | `car/api/…/hotspot/CarManualHotspotPrecheck.kt` | `blocker(...)` 加 `hostAddress` / `reuseRequested`：**复用且地址空 ⇒ 拦下**，文案给出本车值 `192.168.15.1` 与"在「热点身份」里填"。热点关着那句仍原样在开头（两句话可同时出现，一次说清）；`reuseRequested=false`（P2P 被翻译成 MANUAL 形状）不受管辖 |
| 6 | 出口 | `car/core-bridge/…/CarPlayConfigBuilder.kt` | 只有 `hotspot.reused` 时才把地址交给上游；其余形态一律不传 |
| 7 | 上游配置 | `shared/…/orchestration/CarPlayRuntimeConfig.kt` | 新增可选 `manualHotspotHostAddress: String? = null`；形态校验收紧为**严格点分四段 IPv4**（`isStrictIpv4Literal`：`InetAddress.getByName` 会把 `192.168.15` 解析成 `192.168.0.15` —— "能过校验却永远匹配不上"）。`sameLanHostAddress` 同步改用这条更严的判据 |
| 8 | 上游 | `shared/…/network/ManualHotspotManager.kt` | 新增可选 `hostAddressOverride`；`start()` 里先走 `resolveLocalHotspotInterface()` —— **有 override 就按地址找网卡**（绕开 `interfaceScore`）；找不到**回落**到老判据（二开层已核过一遍并给中文文案，这里不判死连接） |
| 9 | 上游 | `shared/…/orchestration/CarPlayController.kt` | `MANUAL` 分支把 `config.manualHotspotHostAddress` 透传给 manager |
| 10 | 桥接 | `car/core-bridge/…/CarPlaySessionBridge.kt` | **撤回** 0086 的"识别不到 ⇒ 拦"；改为：① 残留（我方自建地址还在网上）**降级为告警**（地址已由用户指定，残留不再改变我们通告什么）；② 用户填的地址**不在任何网卡上 ⇒ 拦下**，文案给出可执行结论（车机热点可能没开 / 地址填错，本车 192.168.15.1）+ 诊断行 |
| 11 | 界面 | `car/ui/…/screens/SettingsScreen.kt` | 「热点身份」里新增 **车机热点地址** 一栏（仅「复用」时出现，占位符 `192.168.15.1`，数字键盘）；页面顶部的拦截提示复用同一份文案（`reuseRequested = 热点模式==复用车机热点`） |

### 测试（新增 12 条）

| 文件 | 用例 |
| --- | --- |
| `CarManualHotspotPrecheckTest`（+4） | 复用没填地址 ⇒ 拦下且文案含"复用 / 192.168.15.1 / 热点身份"；填了（含带空格）⇒ 放行；热点关着且地址没填 ⇒ **两句话一起给、"关着"那句仍在开头**；`reuseRequested=false` ⇒ 地址缺失不拦 |
| `CarManualHotspotAddressTest`（新，5 条） | 方案带上用户地址；空白等同没填；自建不读它；**只有复用才透传给上游**（自建不带）；P2P 翻译成 MANUAL 形状后不带用户地址 |
| `ManualHotspotConfigTest`（shared，+3） | 非空地址透传；非 IPv4（主机名 / IPv6 / **`192.168.15` 三段写法**）一律拒；留空合法（护着 P2P 那条路） |
| `CarHotspotModeIsolationContractTest`（改判） | 残留**只告警、不再 throw**；复用的地址判据与设置页同源；P2P 那条路不被波及 |

**验证**：`:shared` **229**（+3）/ `:car:core-bridge` **657**（+9）/ `:car:platform-geely` **44** 用例，
0 失败；`:car:app:lintVitalRelease`（含 NewApi fatal 门禁）通过；`:car:ui` / `:car:app` 编译通过。
**未提交、未出包。**

**顺带记录一处与本次无关的既有编译问题**：`:common`（上游演示用宿主 Activity，
本 App 不依赖它）在被 `WIFI_P2P_LEGACY` 加入枚举后 `CarPlayHostActivity.kt:2954` 的 `when`
不再穷尽 ⇒ `:common:compileDebugKotlin` 编译不过（源自 PATCH-0059）。未修（不在本次范围）。

**用户要做的两件事**：① 在「复用车机热点」下把**车机热点地址**填成 `192.168.15.1`；
② 密码别再留着出厂默认 `12345678`，填车机热点「Lynk」的真实密码。

---

## PATCH-0088 · 副屏倒装：画面 180° 翻转（**原地**翻转，不重连）

### 背景（2026-10-11 真车）

车机 CS11（`cs11_high`）的仪表副屏倒装。诊断包 `1791702691839_f70d0ce5` 里 5 块屏
`rotation` **全是 0** —— 这是**物理**翻转，系统与 iPhone 都不知道，只能在呈现层转。

⚠️ **车主补充的关键事实**：面板**报 1920×1080，实际只显示 1920×720**。
内容本来就摆在 720 高的带子里 ⇒ 翻转必须**在这条带子内部原地做**。
曾按"转整块 1080 高的根视图"实现，那样带子会被绕面板中心移到 `360..1080` 行，
一半落到不显示的区里、画面被裁掉一半。最终转的是 `clip`（可见块，绕自身中心）。

### 改动

| # | 层 | 文件 | 改动 |
| --- | --- | --- | --- |
| 1 | 契约 | `car/api/…/display/CarClusterPresenter.kt` | 新增可选能力接口 `CarClusterFlipControl.setFlip180(Boolean)`（同 `CarClusterDisplaySelection` 思路，`as?` 探测） |
| 2 | 设置 | `car/api/…/settings/CarSessionSettings.kt` | 新增 `clusterFlip180: Boolean = false` |
| 3 | 存储 | `car/core-bridge/…/SharedPreferencesCarSettingsStore.kt` | 新键 `cluster_flip_180` 的读写 |
| 4 | 呈现 | `car/platform-geely/…/GeelyClusterPresentation.kt` | 新增 `flip180` 入参 + `setFlip180()`；`applyFlip180()` 对**可见块**与占位文字设 `rotation`；诊断快照带 `flip180/blockRotation` |
| 5 | 装配 | `car/platform-geely/…/GeelyClusterPresenter.kt` | 实现 `CarClusterFlipControl`；窗口已在时**就地下发**（不重建窗口、不重连） |
| 6 | 灌入 | `car/app/…/CarAppController.kt` | `startSession` 与 `applySettings` 两处透传（与选屏并列，但**不触发重连**） |
| 7 | 界面 | `car/ui/…/screens/SettingsScreen.kt` | 「仪表地图投屏」下新增「画面旋转 180°」开关 |

### 测试（新增 3 条）

`GeelyClusterFlip180ContractTest`（源码级，同既有 `…NoReattachTest` 理由：Presentation 需真 Context）：
按 `flip180` 决定角度、转的是**可见块**而非整窗根、占位文字跟着转、presenter 就地下发、
建窗带上当前值、设置项读写两侧齐全。

**验证**：`:car:platform-geely` **47** 用例 0 失败（+3）；`:shared` **234** 0 失败；
`:car:api` / `:car:core-bridge` / `:car:ui` / `:car:app` 编译通过。**未提交、未出包。**

---

## PATCH-0089 · 「仪表地图」内容板块同步（导航卡片 / 仅地图布局 / 内容对齐）

### 背景

原版 `GeelyClusterContent.url()` 用三个字段决定副屏 URL（`when` 顺序即优先级）：
导航卡片 > 仅地图布局 > 纯地图。DBPlay 此前把内容**写死**成地图 URL，
于是原版那三个设置项在 DBPlay 里根本没有落点。

### 改动

| # | 层 | 文件 | 改动 |
| --- | --- | --- | --- |
| 1 | 协议 | `shared/…/airplay/CarPlayClusterDisplay.kt` | 新增 `MANEUVER_LAYOUTS` / `MANEUVER_LAYOUT_LABELS` / `contentUrl(卡片, 对齐, 仅地图)`（原版 `url()` 的等价实现） |
| 2 | 设置 | `car/api/…/settings/CarSessionSettings.kt` | 新增 `clusterNavigationCard` / `clusterMapLayoutTrial` / `clusterAlignment`（**下标**存，顺序绑定协议） |
| 3 | 存储 | `car/core-bridge/…/SharedPreferencesCarSettingsStore.kt` | 三个新键（沿用原版字面量 `iphone_navigation_card` / `iphone_map_layout_trial` / `iphone_navigation_alignment`） |
| 4 | 声明 | `car/core-bridge/…/CarPlayConfigBuilder.kt` | `toAirPlayDisplay(initialUrl)`：`/info` 的副屏 `initialUrl` 不再写死 |
| 5 | 下发 | `car/core-bridge/…/CarPlaySessionBridge.kt` | `showUI` 的 URL 与声明**同源**；判据由"只看流号"改为"**流号 + URL 都相同才跳过**"，并在 `updateSettings` 里检测内容变更后**立即重推**（原实现改设置要重连才生效） |
| 6 | 界面 | `car/ui/…/screens/SettingsScreen.kt` + `CarClusterContentLabels.kt` | 设置页新增三项；对齐项在两内容开关都关时置灰（与原版一致）。标签按 `CarMfiTargetLabels` 惯例留在 UI 层（`car:ui` 不依赖 `:shared`） |

### 测试（新增 5 条）

`ClusterContentUrlTest`（`:shared`）：纯地图 / 仅地图带对齐 / 卡片优先且三组参数齐 / 越界夹住 / 档位顺序与协议一致。

**验证**：`:shared` **234** 用例 0 失败（+5）；其余同上。**未提交、未出包。**

---

## PATCH-0090 · 安全区**离线可设**（没检测到设备也能画框）

### 背景

原实现的门槛是 `car/ui/…/SettingsScreen.kt` 的 `SafeAreaFrame`：
`model.safeAreaCanvasWidthPx <= 0` 就只显示"还不能画框 / 先去连一次"，
而那个值**只有连过一次 CarPlay 才会上报** ⇒ 想"上车前先把框画好"做不到。

原版（`GeelyClusterSafeAreaStore` / 安全区编辑器）存的是**面板空间矩形**，与连没连无关，
所以本来就能离线编辑 —— 本补丁对齐这一口径。

### 改动

`car/ui/…/screens/SettingsScreen.kt` 的 `SafeAreaFrame` 取**参考画布**：
① 已上报画布（精确）→ ② 用户声明的自定义分辨率 → ③ `1920×1080`（原版缺省口径、本机面板尺寸）。
界面上明示"还没连过 CarPlay，按参考画布预览；连上后按比例换算"。

**验证**：`:car:ui` / `:car:app` 编译通过。**未提交、未出包。**

### 未做（需车主决策）

**手动巡航**（原版「允许无导航时手动开启仪表地图」）。原版语义是
"无导航时四指左滑手动开、开始导航自动开、明确结束导航自动关"，
需要引入**按路线状态门控投屏**的状态机。DBPlay 现在是"开关开了就一直投"，
忠实移植会**改变现有行为**（无导航时不再投屏），属行为级取舍，未擅自改。

### 补登记：`car/api/…/developer/CarDiagnosticSettingsDump.kt` 补 4 行（2026-10-11 出包前）

**背景**：PATCH-0088/0089 往 `CarSessionSettings` 加了 4 个存储字段
（`clusterFlip180` / `clusterNavigationCard` / `clusterMapLayoutTrial` / `clusterAlignment`），
但**没同步** `CarDiagnosticSettingsDump` 的白名单。出包前跑全量单测时被
`CarDiagnosticSettingsDumpTest` 的**反射完整性用例**抓住：

```
以下设置字段没有出现在 effective_settings.txt 里：
[clusterFlip180, clusterNavigationCard, clusterMapLayoutTrial, clusterAlignment]
```

**改了什么**：在 `[显示]` 段 `clusterDisplayId` 之后补 4 行 `appendLine`。

**为什么必须改**：本文件是**白名单、不是反射**（刻意如此，免得敏感字段被自动全量导出）。
代价就是"新增字段忘了加"**不会有任何提示**，现场只表现为"这个字段根本不在包里"，
而没人会想到是这个原因 —— 那条反射测试就是专门把这个代价找回来的（见坑 §5）。

**口径说明**：`clusterAlignment` 按**下标**落原始值（0=左/1=右/2=顶），**没**翻译成中文 ——
`:car:api` 不能 import `:shared` 的 `CarPlayClusterDisplay.MANEUVER_LAYOUTS`
（依赖方向 `:shared` → `:car:api`，反向引用编译不过）。

---

## PATCH-0091 · ADB 静默安装通道**修复**（实车装不上：路径权限墙 + 无降级 + 假阳性）

### 背景

实车反馈"ADB 静默安装不成功"。**诊断包日志推翻了第一直觉**：
`adb shell` 打出来的是 `uid=0(root) … context=u:r:su:s0` —— **提权本来就是成功的**。
真正卡住的是另外三件事，叠加在一起：

| # | 根因 | 后果 |
| --- | --- | --- |
| 1 | 🔴 **路径权限墙**：APK 在 `/data/user/0/<pkg>/files/update/…`（mode 0700），而 `pm install` 是 adbd 起的 **shell 进程**去读它 | shell 读不到文件，命令必然失败 |
| 2 | 🔴 **无降级**：只有一条 `pm install -r -d` | 车机 ROM 的任一条限制（多用户 / 来源校验 / verifier / OEM 白名单）打不通就永远打不通 |
| 3 | 🔴 **假阳性判定**：`shellDetached` 里 `if (process.isAlive) 0` ⇒ `exitCode` 恒 0（`pm install` 要跑几十秒，1.5s 观察窗里进程必然还活着） | 调用方据此报"已提交安装"，**失败被完全吞掉** |

参考实现：DBtool3.0 安卓 9 方案（`DBXM/DBTOOL3.0` 的 `LocalAdbBridge.installWithRetry` / `pushApk`）——
**照思路不照抄**。

### 改动

| # | 层 | 文件 | 改动 |
| --- | --- | --- | --- |
| 1 | adb | `car/core-bridge/…/hotspot/AdbShellRunner.kt` | **新增 `push(local, remotePath)`** —— 修复的根。`adb push` 的**读方是本应用**（对自己 `filesDir` 有权限）、**写方是 adbd**（落 `/data/local/tmp`），一读一写正好绕开权限墙，且**不需要 root** |
| 2 | 脚本 | `car/core-bridge/…/update/CarInstallScript.kt` | **新增**。设备端**六级降级**脚本：`pm install -r` → `--user U` → `-i com.android.vending` → `-w` → 临时关 verifier → Flyme 属性 / `su` 兜底。每步改过的系统状态**装完必恢复**；结果只认日志里**整行等于** `RESULT=OK` |
| 3 | 安装 | `car/core-bridge/…/update/CarApkInstaller.kt` | `installViaAdb` 重写：`ensure → push → rm log → shellDetached(脚本)`；`install(file)` 改名 `installWithoutAdb(file)`；新增 `canUseAdb()` / `readInstallLog()` / `currentUserId()` |
| 4 | 编排 | `car/core-bridge/…/update/CarUpdateManager.kt` | ADB 分支改**异步**（worker 跑安装，非 `ADB_COMMITTED` 才回主线程退系统安装器）；失败时读回设备日志提炼 `INSTALL_FAILED_XXX` 写进提示 |

**为什么脚本跑在设备端**：装的是**自己**，成功那一刻本进程会被系统杀掉；
脚本是 **adbd 的子进程**，不受本进程死活影响，能一路跑完并把结论写进 log。

⚠️ 脚本里**所有 shell `$` 写成 `${'$'}`**，Kotlin 插值只用 `$常量名`——
混在一起时"shell 变量拼错"的后果是**脚本静默不干活**（未定义变量展开成空串），极难发现。

### 测试（新增 8 条）

`CarInstallScriptTest`（`:car:core-bridge`）：占位符替换、六级全覆盖、verifier 恢复、
结果标记、`parseSucceeded` 精确匹配、`parseFailureReason` 取**最后一个** Failure 码。

**验证**：`CarInstallScriptTest` **8** 用例 0 失败；`CarInstallChannelOrderTest` **7** 0 失败。
**未实车验证**（需上车确认六级降级是否真走通）、**未提交、未出包。**

---

## PATCH-0092 · 后台静默更新（P0：状态机 + 空闲判据 + 通道复用 + 熔断 + 开关）

### 背景

需求原话「给在线更新加入后台静默 ADB 更新方案」。

**先纠一个认知**：ADB 静默通道 2026-10-07 就已存在（`CarInstallChannel.ADB_SILENT` 是
`PREFERRED`）⇒ 本需求缺的**不是"通道"，而是"触发方式"**。现有链路三个触发点
（检查 / 下载 / 安装）后两个全要用户点。

⇒ 本补丁 = 把"下载"与"ADB 安装"两步从**用户点击**改成**条件满足自动触发**，全程零弹窗。

设计全文：`CarPlay/DBPlay-后台静默ADB更新-设计方案-20261011.md`。

### 改动

| # | 层 | 文件 | 改动 |
| --- | --- | --- | --- |
| 1 | 判据 | `car/core-bridge/…/update/CarSilentUpdatePolicy.kt` | **新增**。纯函数：`allowsSilentInstall()` / `advanceIdleClock()` / `idleWindowSettled()` / `nextSilentAttempt()` / `silentAttemptsFor()` / `silentAttemptLimitReached()` / `silentBlocked()`。**时间由参数传入**，边界可精确单测 |
| 2 | 持久化 | `car/core-bridge/…/update/CarUpdatePrefs.kt` | 新增 `silentUpdateEnabled`（**默认 false**）/ `silentAttemptVersionCode` / `silentAttemptCount` / `silentBlockedVersionCode` + `recordSilentAttempt()` / `clearSilentBreaker()` |
| 3 | 契约 | `car/api/…/update/CarUpdateContract.kt` | ① 新增 `CarUpdatePhase.PENDING_INSTALL`；② `CarUpdateState.silentUpdateEnabled`；③ `CarUpdateHost` 新增 `notifyIdleWindow()` / `setSilentUpdateEnabled()`；④ `download(onState, silent)` |
| 4 | 编排 | `car/core-bridge/…/update/CarUpdateManager.kt` | 检查成功且静默开 ⇒ 自动 `download(silent=true)`；下完进 `PENDING_INSTALL`；`notifyIdleWindow()` 在 worker 发起安装；熔断计数**发起前落盘**；静默失败**不退弹窗** |
| 5 | 通道 | `car/core-bridge/…/CarAppServices.kt` | `updateManager` 注入**共享** `adbSession`（修既存缺陷）；`adbSession` 声明**前移**到 `updateManager` 之前（Kotlin 属性按文本顺序初始化） |
| 6 | 计时 | `car/app/…/CarAppController.kt` | `onStateChanged` 推进空闲计时 + `postDelayed` 到满 30s；`refreshModel()` 里判"该不该动手"；新增 `onToggleSilentUpdate` |
| 7 | 界面 | `car/ui/…/components/UpdateDialog.kt` + `screens/AboutScreen.kt` + `CarUiContract.kt` + `navigation/CarPlayApp.kt` | 五处穷尽 `when` 补 `PENDING_INSTALL`；「关于」页新增「后台自动更新」`CarSettingSwitch` |

### 三条硬设计（本补丁的关键不在"怎么装"）

1. **只在会话空闲 ≥30 秒才动手** —— 装自己必然杀进程重建，会话活跃时装会打断投屏。
   只判"当前空闲"不够：`WAITING_FOR_DEVICE → ACTIVE` 可能只差几百毫秒
   （用户刚拔线又插线），所以要求**连续**空闲。
2. **复用共享 adb 通道** —— `CarUpdateManager` 此前自己 `new AdbShellRunner(appContext)`，
   与热点/探测各持一个 adb server 却**共用 5038 端口**，而 `killServer()` **不幂等**。
   前台手动装时两条链路很少同时活跃所以没爆；**后台静默装会在"会话刚结束"的瞬间动手**，
   正好撞上热点收尾 ⇒ 必踩。顺带收益：`ensure()` 内部尊重 `enableBuiltInAdb` 开关。
3. **坏包熔断** —— 同一版本自动失败 **2** 次 ⇒ 永久关闭该版本的静默，只在「关于」页
   显示"自动更新失败，请手动安装"。**没有它，一个坏包就能让车队反复自杀重启，
   用户连设置页都进不去关开关**。计数**必须在发起安装前落盘**（装自己会杀进程，
   写在"成功之后"的代码可能永远执行不到）。

### 三个产品决策（按设计文档建议执行，可改）

| 问题 | 取值 | 理由 |
| --- | --- | --- |
| 开关默认值 | **默认关** | 这是唯一一个"能让 App 把自己装死"的功能，默认开=把车队可用性押在发布纪律上 |
| 安装时机 | **会话空闲 ≥30s** | 没连手机时杀 App 重启用户几乎无感，收益/代价最平衡 |
| 静默失败是否退系统安装器 | **不退**（只记状态 + 提示手动安装） | 静默只在**没连手机**时发生，那一刻车里基本没人 —— 拉起一个没人点的系统框比不装更糟。用户**手动**点「立即安装」时才走完整兜底 |

### 测试（新增 17 条）

`CarSilentUpdatePolicyTest`：9 个 `CarSessionPhase` 全覆盖 + 穷尽性守门、
稳定窗口 29s/30s 边界与"中途转 ACTIVE 重新计时"、
熔断时序表（0→允许、1→允许、2→熔断）、换版本号重置、熔断标记边界。

**验证**：`:car:core-bridge` 编译通过；`CarSilentUpdatePolicyTest` **17** 用例 0 失败；
`:car:app` / `:car:ui` 编译通过。**未提交、未出包。**

⚠️ 收紧可见性：`CarUpdateManager` 由 `public` 改 **`internal`** —— 它要收一个
`AdbShellSession`（internal 类型），Kotlin 不允许 internal 类型出现在 public 签名上
（**`private val` 构造参数也算**，坑 §58）。外部只需 `CarUpdateHost` 接口。

### 未做（列 P1/P2）

- **实车验证**（设计 §7 真机 5 项）：静默关时行为与现状逐帧一致、静默开的完整闭环、
  坏包熔断生效且**仍能进设置页**、安装期间开热点无 `kill-server` 互踢。
- **P1**：服务端 `check_update` 增加 `sha256`（现在只能校验 `Content-Length`）+ `auto_install`。
- **P2**：装完轻提示、静默成功率上报、灰度联动文档（坑 §50：开静默的车型发布**必须先走灰度**）。

---

## PATCH-0093 · 副屏「核心安全区」默认 1920×720（**只影响副屏**）

### 背景

车主原话：「**仪表核心安全区改成 1920*720，现在的太大了**」。

「核心安全区」在设置页里是**主屏与副屏共用**的一项（`CarSessionSettings.safeArea`，
矩形模型）。共用带来的问题是：**用户没画过框时**，主屏有既定回退（沉浸模式避让
顶部 72px，见 `CarPlayConfigBuilder`），而**副屏什么都没有** ——
`CarClusterSpec.safeArea` 恒为 `null` ⇒ 报给 iPhone 的声明里没有安全区 ⇒
iPhone 认为整块 1920×1080 面板都可用 ⇒ 导航铺满、压到下方车机自己的油量/车速表
（原版把这种现象叫「画面绘制到安全区外」）。

### 定案：默认只给副屏，**不动**共用设置项本身

三条路里选了 ①（车主经选项确认「只副屏」）：

| # | 方案 | 结论 |
| --- | --- | --- |
| ① | **副屏单独一条默认回退**（本补丁） | ✅ 采用。主屏行为逐字节不变 |
| ② | 把 `CarSettingsSafeArea` 的出厂默认改成 1920×720 | ❌ 会连带把**主屏**可用区收到上方 720px、底部空出 360px（`CarPlayConfigBuilder` 的主屏分支读同一份矩形）——"改一处坏另一屏" |
| ③ | 只改本车上的值 | ❌ 下次换车/重装又变回去 |

### 改动

| # | 层 | 文件 | 改动 |
| --- | --- | --- | --- |
| 1 | 策略 | `car/api/…/display/CarClusterSafeAreaPolicy.kt` | **新增**。纯对象（无 Android 依赖、可单测）：`defaultInsetsFor(面板宽, 面板高)` / `resolve(drawn, 主屏画布宽, 主屏画布高, 面板宽, 面板高)`。**只产出副屏面板空间的 insets** |
| 2 | 接线 | `car/app/…/CarAppController.kt` | `resolveClusterSpec()` 的 `safeArea` 从"未画过就回落到 `spec.safeArea`（= `null` = 不声明）"改成 `CarClusterSafeAreaPolicy.resolve(...) ?: spec.safeArea` |

### 默认值的含义与五条边界

- `1920 × 720`，坐标空间 = **副屏面板**：宽取整面板宽（不左右留边）、高取 720px
  ⇒ 1920×1080 面板上正好是上方 2/3，`bottom = 360` 留给车机自己的油量/车速表。
- **面板本来就 ≤ 1920×720 时不声明**（返回 `null`）——例如本车 HDMI 的另一档模式
  1024×274。硬套会算出 `bottom = 274 − 720 = −446`，而报出去的
  `safeArea.heightPixels` 为负时 **iPhone 直接拆会话**（真车 CX11 实测，PATCH-0079）。
  此时行为与改动前**逐字节一致**。
- **用户画过框就一律以用户的为准**（含"画得比默认还小"），本策略只在
  "用户没表达意见"时兜底。
- **"画框拉满整屏" ≡ "没画过"** ⇒ 也走默认（与主屏同口径，PATCH-0085）。
  副作用：想回到"整块面板"没法靠拉满画框实现 —— 这是**刻意的**，副屏的整块面板
  本来就包含车机自己的仪表区。
- **画布未知**（`CarVideoCanvas` 为 `null`，还没进过画面页）⇒ 走默认且**不抛**。
  少了这道守卫，`toInsets(0, 0)` 会撞 `require(canvas > 0)` 抛
  `IllegalArgumentException`，而 `resolveClusterSpec()` 每次建栈都被调、**必须不抛**
  （抛出去就是"有副屏的车机上会话建不起来"）。

### 为什么不是照抄原版

原版 `GeelyClusterSafeArea.DEFAULT` 是画布 1920×1080 上的 `(x=0, y=0, w=1865, h=660)`
⇒ insets `(top=0, bottom=420, left=0, right=55)`。没照抄两个理由：
`1865` 会让副屏左右各留一条谁也用不上的边（仪表没有左右饰板）；
`660` 比车主口径矮 60px。

### 测试（新增 11 条 + 改 2 条）

`CarClusterSafeAreaPolicyTest`（**新增，11 条**）：车主口径常量、未设 ⇒ `bottom=360`、
可用高 720 而非整块面板、默认宽夹到面板宽、面板正好等于默认区 / 比默认区小 / 尺寸非法
⇒ 不声明、画框拉满整屏 ≡ 没画过、画过框按用户的并**换算**（主屏画布上 72px ⇒
副屏面板上 **108px**，不是 72）、用户画得比默认还小也生效、画布未知走默认且不抛。

`ClusterSafeAreaContractTest`（**改 2 条**）：
`未设安全区时副屏不声明` → 改名 `翻译层不替 spec 补安全区`（分工不变：默认值属策略层，
翻译层只做翻译）；新增 `未设安全区时副屏默认声明 1920x720`（策略 + 翻译**串起来**之后
iPhone 真正收到的东西）与 `副屏默认策略必须接在 resolveClusterSpec 上`
（**静态读源码**——`resolveClusterSpec` 在 `:car:app`，本工程没有它的 test source set，
而"策略写好了但没接上"是本项目反复出现过的失败形态；按项目纪律 §29 先剥注释再断言）。

**验证**：`:car:api` / `:car:core-bridge` / `:car:app` 编译通过；
`:car:core-bridge:testDebugUnitTest` **706** 用例 0 失败；
`:car:platform-geely:testDebugUnitTest` **47** 用例 0 失败。

### 未做

- **实车验证**：接上仪表后看导航是否只占上方 720px、下方 360px 是否真的让出油水表。
- **主屏不改**（车主选「只副屏」）——主屏仍走沉浸避让顶部 72px 的既有回退。


## PATCH-0094 · 「核心安全区」方框**只作用于副屏**，主屏与它彻底解耦

### 背景

车主原话：「**仪表核心安全区方框只能调整副屏，不调整主屏**」。

PATCH-0093 只处理了**默认值**（用户没画框时副屏回落到 1920×720），
**没有**处理"用户真的画了框"那条路：那个矩形（`CarSessionSettings.safeArea`）
当时仍然被**两边同时读**——

- 副屏：`CarAppController.resolveClusterSpec()` → `CarClusterSafeAreaPolicy.resolve`
  → `CarClusterSpec.safeArea` → `/info`；
- 主屏：`CarPlayConfigBuilder.airPlayConfig()` 里 `drawnRect.toInsets(主画布)`
  **优先于**沉浸模式的 72px 避让。

后果很具体：用户在「仪表与HUD → 核心安全区」里画一个"只用上方 720px"的框
（这正是 PATCH-0093 想要的画法），`toInsets` 算出的 `bottom = 360` 会**直接作用到
主屏画布** ⇒ 主屏的 CarPlay 交互 UI 被挤到上方一条。现场表现是
**"我只调了仪表，主屏怎么变小了"** —— 一个挂在「仪表与HUD」下的设置去改了中控屏。

### 定案：矩形**只**喂副屏；主屏只剩"沉浸避让"一个来源

车主经选项确认：「**仪表与HUD 的安全区只限副屏**」。

| # | 方案 | 结论 |
| --- | --- | --- |
| ① | **方框彻底与主屏解耦**（本补丁） | ✅ 采用。主屏回到 2026-10-06 定案 A 的单一来源 |
| ② | 主屏只保留"顶部高度"手动覆盖 | ❌ 车主没选。保留半条耦合仍需解释"为什么只有 top 能调" |
| ③ | 让主屏也吃到这个框 | ❌ 与「仪表与HUD」这个归属矛盾，正是本次要修的现象 |

### 改动

| # | 层 | 文件 | 改动 |
| --- | --- | --- | --- |
| 1 | 翻译层 | `car/core-bridge/…/CarPlayConfigBuilder.kt` | 删掉主屏那条 `drawnRect → toInsets` 分支，`mainSafeArea` 只由 `immersiveSafeAreaTopPx`（沉浸实测状态栏高）决定。**主屏不再引用 `settings.safeArea`** |
| 2 | 契约 | `car/api/…/settings/CarSessionSettings.kt` | `CarSettingsSafeArea` 类注释加"只作用于副屏"；`safeArea` 字段补 KDoc；`immersiveVideo` 里"手动值优先"那条规则改成"本字段是主屏唯一来源" |
| 3 | 策略 | `car/api/…/display/CarClusterSafeAreaPolicy.kt` | 注释同步（"主副共用"的说法已过时） |
| 4 | 界面 | `car/ui/…/screens/SettingsScreen.kt` | 「核心安全区」分区副标题、画框提示、`CarSettingInfo` 说明、以及「显示与视频」里那句引用，全部从"与主屏共用"改成"只影响仪表" |
| 5 | 诊断 | `car/api/…/developer/CarDiagnosticSettingsDump.kt` | `safeArea` 行注明"矩形·主画布空间（只作用于仪表副屏）"；`immersiveVideo` 那行加"排查主屏位置看这里" |
| 6 | 接线注释 | `car/app/…/CarAppController.kt` | 标注"这里是 `settings.safeArea` 在全工程**唯一**的消费点" |

### 边界与刻意的取舍

- **`safeAreaDrawOutside` 不动**：它是「显示与视频 → 画面绘制到安全区外」，
  决定"画面怎么铺"而不是"安全区数值"，主屏副屏**共用**（主屏那份正是沉浸特性的组成部分）。
  负向静态断言因此必须用正则 `settings\.safeArea(?!DrawOutside)`，用 `contains` 会误判。
- **随之作废的两条旧规则**（PATCH-0080 / PATCH-0085 的主屏侧）："画框拉满整屏 ≡ 没画过"
  与"用户画过框就顶掉 72px 避让"都不再存在 —— 它们存在的唯一理由就是"主屏也读这个框"。
  副屏侧那条"拉满整屏 ≡ 没画过"**保留**（在 `CarClusterSafeAreaPolicy` 里）。
- **坐标空间不变**：矩形仍存**主画布空间**（画框 UI 是在那块画布上预览的），
  副屏侧继续 `scaledTo(主画布 → 副屏面板)`。借它的宽高做比例基准 ≠ 作用于主屏。
- **主屏失去的能力**：不能再靠画框手动微调顶部避让高度（只剩沉浸自动 72px）。
  车主明确选了这条。

### 测试（改 3 条 + 新增 2 条）

`ImmersiveVideoSafeAreaTest`（**改 2 条**）：原来那两条钉的是"画过框就以手动值为准 /
沉浸不再补顶部"，现在**反过来钉**——`画过框也不影响主屏，仍按实测状态栏高度避让`
与 `画框的四边不落到主屏，drawUIOutsideSafeArea 原样透传`。判别点是"框里那组数
（40 / 10 / 5 / 6）一个都不许出现在 `config.main.safeArea` 上"。

`ClusterSafeAreaContractTest`（**改 1 条 + 新增 2 条**）：`副屏安全区与主屏安全区互不串味`
改为显式传 `immersiveSafeAreaTopPx = 72`（主屏的值不再能来自 settings）；新增
`主屏不吃核心安全区那个矩形`（行为）与 `翻译层不许再把核心安全区接到主屏`
（**静态读源码 + 正则负向断言**，按项目纪律 §29 先剥注释）。

为什么行为测试不够、还要静态断言：矩形恰好等于整屏、或沉浸关掉时，把
`settings.safeArea` 接回主屏**不会**让行为测试变红 —— 真车 1.0.44 那次
"画框悄悄顶掉 72px 避让"就是这么漏过去的。

**验证**：`:car:api` / `:car:core-bridge` / `:car:app` / `:car:ui` 编译通过；
`:car:core-bridge:testDebugUnitTest` **0 失败**；`:car:platform-geely:testDebugUnitTest` **0 失败**。

### 未做

- **实车验证**：画一个明显小于整屏的框，确认**主屏画面纹丝不动**、副屏跟着变。
- **出包**（等车主明确说"出包"）。

## PATCH-0095 · 1.0.51 诊断包问题全修（A–G）

### 背景

车主把 **1.0.51 实车诊断包**（`日志/1791709700416_9939403b_241c10985291`）丢过来排查
「静默更新是否生效 + 投屏中偶发断联」。诊断包本身没问题，但它**暴露了诊断包自己
和周边一圈的可观测性缺陷** —— 想查的东西查不到，或查到的是假象。车主确认修复范围：
**「全部可修项，含行为改动（A–G）」**（H 属 ROM 限制，见文末）。

| # | 现象 | 根因 |
| --- | --- | --- |
| A | `logcat_system.txt` 只覆盖最后 5 秒 | `logcat -d` 只 dump 环形缓冲区尾部、**不落盘**；车机 `VehicleHal` 高频刷屏几秒就冲光 |
| B | 会话日志 `logs/xcertplay.log` 采不到，提示语还误导 | `CarPlayHostActivity` 在本产品**从未被实例化**（launcher 是 `car/app` 的 `MainActivity`）⇒ 上游那个路径**从不存在**；提示语却写"说明 App 没起过会话" |
| C | 副屏 `/info` 的 `widthPhysical/heightPhysical` 吃 **200** 兜底 | ① `GeelyDisplaySelector.specOf()` **完全不传**物理尺寸；② `toAirPlayDisplay()` 落到上游 `DEFAULT_WIDTH_PHYSICAL_MM = 200`，而本产品出厂值是 **350** ⇒ 副屏与主屏差 1.75 倍 |
| D | `SessionLogFile` 超 10MB **从头覆盖** | 上游 `output = file.outputStream()` 写满即截断 ⇒ 把"出事前后那一段"直接抹掉 |
| E | 静默更新零日志、开关不进诊断包 | `CarUpdateManager` 全链路无埋点，诊断包里看不到"为什么没静默" |
| F | 蓝牙释放未生效 | 默认档 `DISCONNECT_LINK` 在真车不生效 ⇒ 车机蓝牙仍占着链路 |
| G | 会话结束无自动重连 | 偶发断联后停在断连状态，要车主手动点「连接」 |

### 定案（车主经选项确认）

- 修复范围：**A–G 全做**，含两处行为改动。
- **F**：默认档改 `DISABLE_ADAPTER`（真关适配器，而不是只断链路）。
- **G**：会话结束**自动重连**，用**有界退避**（不是无限重试）。

### 改动

#### A/B/D · 滚动落盘的应用日志 + 全系统日志（新 `CarLogcatRecorder`）

| 文件 | 改动 |
| --- | --- |
| `car/core-bridge/…/developer/CarLogcatRecorder.kt`（**新增**，约 370 行，含两个类） | `RollingLogFileWriter`（纯 `java.io`、可 JVM 单测）：`name` → `name.1` → `name.2`…，每份是**完整时间片**，最老的才丢；`familyOf()` 返回整族（最老在前）。`CarLogcatRecorder`：起 `logcat` 子进程、按行读、滚动落盘 |
| `car/core-bridge/…/CarAppServices.kt` | 持两个实例；API 分三档（见下）；`logcatRecorderStatus()` 暴露状态给诊断包 |

**两份日志的运行时长期限刻意不同**（这是 A/B 的关键设计）：

| 记录器 | 内容 | 落盘名 | 跑多久 | 为什么 |
| --- | --- | --- | --- | --- |
| `appLogRecorder` | `--pid=<自己>`（稀疏，几 KB/s） | `logs/xcertplay.log`（滚动族） | **随进程常驻** | 空闲期的**后台静默更新**永远采不到，若只在会话期跑 |
| `systemLogRecorder` | 不过滤全系统（约 100KB/s，写 eMMC） | `logs/logcat_system_rolling.txt`（滚动族） | **仅会话期间** | 全量刷屏开销大，会话结束就停 |

`CarAppServices` 的日志 API：

```kotlin
fun startAppLogging()     // 随进程常驻（CarPlayApplication.onCreate 调）
fun startSessionLogging() // = startAppLogging() + 全系统
fun stopSessionLogging()  // 只停全系统那一份（会话结束调）
fun stopAllLogging()      // 两份都停（预留，正常路径不用）
```

> ⚠️ **`CarAppServices` 属性按文本顺序初始化**：两个 recorder 声明在 `developerMode`
> 之后 ⇒ `recorderStatusProvider` **必须是 lambda**（同 `systemShell` 的既有坑）。

#### C · 副屏物理尺寸

| 文件 | 改动 |
| --- | --- |
| `car/platform-geely/…/GeelyDisplaySelector.kt` | 抽出 `physicalSizeMmOrNull()`（`xdpi/ydpi <= 0` 或像素 `<= 0` 或算出来 `<= 0` 一律 **null**）；`specOf()` 补 `widthPhysicalMm/heightPhysicalMm`（此前完全不传） |
| `car/core-bridge/…/CarPlayConfigBuilder.kt` | `toAirPlayDisplay()` 兜底 `AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM`(200) → **`CLUSTER_DEFAULT_WIDTH_PHYSICAL_MM`**（= `CarSessionSettings.DEFAULT_WIDTH_PHYSICAL_MM` = 350，**引用**而非再写一个数） |

> ⚠️ 本机实测 `GeelyDisplayDescriptor.xdpi/ydpi` = **0.0** ⇒ 副屏物理尺寸只能落到产品
> 出厂值 350（**如实**，不编数）。`physicalSizeMmOrNull()` 返回 **null 而非 (0,0)**：
> 0 会被 `sanitizeReportedPhysicalMm` 夹成 1mm。

#### E · 静默更新埋点

`CarUpdateManager.kt` 全链路埋点：初始化（当前版本/开关/上次检查/待安装/熔断版本）、
`checkAsync`（**发现新版时逐项打出静默三条件**）、`maybeAutoCheck` 节流分支、
`download`（4 条被拒 + 缓存命中 + 开始/完成/失败）、`install`/`fallbackToSystemInstaller`、
`notifyIdleWindow`（**每个早退分支都留日志**）、`settlePendingInstall`、`restoreDownloaded`。

新增 `diagnosticDump(): String`（只读）→ `system/update_state.txt` + 清单一行 + 记录器状态行。
`shouldSilent` 抽成参数化 `silentAllowed(...)`，诊断与判定**同一份逻辑**。

#### F · 蓝牙释放改 `DISABLE_ADAPTER`

| 文件 | 改动 |
| --- | --- |
| `car/api/…/settings/CarSessionSettings.kt` | 默认值 `DISCONNECT_LINK` → **`DISABLE_ADAPTER`**；两个成员的 KDoc 重写 |
| `car/core-bridge/…/SharedPreferencesCarSettingsStore.kt` | 新增一次性迁移 `migrateBluetoothHandoffToDisableAdapter()`：**只改写恰好等于旧出厂值**的持久化项，标记 `bt_handoff_adapter_migrated_dbplay` |
| `car/core-bridge/…/bluetooth/CarBluetoothHandoffPrefs.kt`（**新增**） | 独立 prefs 落盘 `adapterDisabledByUs`（**不进** `CarSessionSettings`，恢复出厂不该清它） |
| `car/core-bridge/…/bluetooth/CarBluetoothHandoffReleaser.kt` | `adapterDisabled` 内存字段 → 落盘 prefs；**成功才记**；新增 `restoreIfLeftDisabled()` |
| `car/app/…/CarPlayApplication.kt` | `onCreate` 调 `restoreBluetoothAdapterIfLeftDisabled()`（兜异常） |
| `car/ui/…/SettingsScreen.kt` | 摘要比对默认档改 `DISABLE_ADAPTER`（否则每次念默认档，撞 `maxLines=3` 静默截断） |

**`DISABLE_ADAPTER` 的恢复有两条路径，缺一不可**：① 会话结束（`onSessionEnded`）；
② **下次进程启动**（`restoreIfLeftDisabled`）—— 覆盖静默装自己 / OOM / 强停 / 重启。
只记「**我们关的**」（`adapterDisabledByUs`），启动时**不会**去打开用户自己关的蓝牙。

#### G · 会话结束自动重连（有界退避）

| 文件 | 改动 |
| --- | --- |
| `car/core-bridge/…/session/CarSessionAutoReconnectPolicy.kt`（**新增**，纯函数） | `autoReconnectDelayMillis(attempt)`：`2s → 4s → 8s`（封顶 16s）；`shouldAutoReconnect(attempts, endedByUser)`：最多 3 次 |
| `car/app/…/CarAppController.kt` | 四个私有方法 `scheduleAutoReconnect` / `performAutoReconnect`（走 `permissionGate`）/ `cancelAutoReconnectCheck` / `resetAutoReconnect`；`stopRequestedByUser` 区分「用户停止」与「远端断开」 |

**归零挂在"稳定性"上**（会话连续存活 **30s**）而非 `SessionStarted` —— 否则
"连上就掉"会让退避变成**无限循环**。

### 边界与刻意的取舍

- **H 不修**：热点 step 17 FAIL（`/system` dm-verity 不可 rw）是 **ROM 限制**，非代码问题。
- **应用日志常驻**是有意的：只在会话期跑 ⇒ 空闲期的后台静默更新永远采不到（E 就查不到）。
- **`SimpleDateFormat` 每次新建**：`stamp()` / `formatStamp()` 会被**诊断采集线程**调用，
  而 `now()` / `describe()` 在主线程 ⇒ 不复用静态实例（线程安全）。
- **只记"我们关的"蓝牙**：启动补恢复**绝不**去打开用户自己关的适配器。
- **诊断包提示语改写**：`logs/` 段改成「找不到说明采集时它没在跑 —— **不是**「App 从未起过会话」」。

### 测试（新增 6 个测试类，共 42 条）

| 测试类 | 条数 | 说明 |
| --- | --- | --- |
| `RollingLogFileWriterTest` | 6 | 滚动族命名/最老在前/写满滚动/行数统计 |
| `CarSessionAutoReconnectPolicyTest` | 10 | 退避 2/4/8/16 封顶、次数上限、用户停止不重连、整轮 14s < 稳定阈值 30s |
| `CarAppControllerAutoReconnectWiringTest` | 9 | **静态读源码**：SessionEnded 排重连 / SessionStarted 重排稳定计时 / 用户停止置位 / 走权限闸门 / 复用策略函数 |
| `DiagnosticWiringContractTest` | 6 | **静态读源码**：启动开应用日志 + 补恢复蓝牙（两处 `runCatching`）+ 会话开全系统日志 + 结束只停全系统 |
| `BluetoothHandoffDefaultMigrationTest` | 9 | 出厂默认 DISABLE_ADAPTER / 迁移只改写旧出厂值 / 标记不重名 |
| `ClusterPhysicalSizeContractTest` | 7 | **行为**：副屏兜底与主屏出厂值同源 / 不再走 200 / 1024×274 ⇒ 350 / 94 |

> `car/app` / `car/ui` / `car/api` **无 test source set** ⇒ 接线点必须写静态读源码断言，
> 且读之前**必须剥注释**（项目纪律 §29）。`ClusterPhysicalSizeContractTest` 首跑挂 1 条，
> 根因是 `toAirPlayDisplay` 是**扩展函数**、`indexOf("fun toAirPlayDisplay(")` 找不到 ——
> 静态断言查**扩展函数**必须带上接收者（`private fun CarClusterSpec.toAirPlayDisplay(`）。

**验证**：`:car:api` / `:car:core-bridge` / `:car:platform-geely` / `:car:app` / `:car:ui`
全部 `BUILD SUCCESSFUL`；`:car:core-bridge:testDebugUnitTest` **756 / 0 失败**；
`:car:platform-geely:testDebugUnitTest` **51 / 0 失败**（`--rerun-tasks` 强制真跑）。

### 未做

- **实车验证**：静默更新是否生效（看 `system/update_state.txt`）、投屏断联是否自愈、
  蓝牙适配器会话后是否恢复。
- **出包**（等车主明确说"出包"）。
- （上一轮遗留）服务端 `check_update` 加 `sha256` + `auto_install`。


## PATCH-0096 · 「仪表与HUD」版块重排为**左右两列**（并把重复的「画面绘制到安全区外」收成一处）

### 背景

车主原话：「**我需要将仪表与HUD版块的布局重新设计过，目前看起比较乱**」。

「乱」不是元素多，而是**一张卡里混了三种语义、状态被压在最后、同族按钮被拆散**：

| # | 问题 | 具体表现 |
| --- | --- | --- |
| P1 | **语义混杂** | 卡①里「仪表地图投屏」（**会话级，要重连**）与「画面旋转 / 导航卡片」（**立即生效**）连排，用户分不清改哪个要重连 |
| P2 | **状态在最后** | 最该第一眼看到的「投屏中 / 未投屏」压在卡①**最后一行**，还和卡②的「当前选用」分散在两处 |
| P3 | **同族按钮被拆散** | 「逐块试屏」在卡②中段、「重新检测副屏」在卡②末尾，中间隔着候选列表与告警 |
| P4 | **标题重复** | `SectionHeader`「仪表地图投屏」与卡内首行开关「仪表地图投屏」同名 |
| P5 | **三段割裂** | 「画什么」（卡①）与「怎么摆」（卡③安全区）被整张「选屏」卡隔开 |
| P6 | **单列浪费横屏** | `CarScreen` 把内容卡在 `contentMaxWidth`(1120dp) 内居中 ⇒ 1920 宽屏左右各空出约 260px，纵向却要滚两三屏 |

**另发现一处真正的重复项**：`CarSessionSettings.safeAreaDrawOutside`
（「画面绘制到安全区外」）在「显示 → 系统栏与画面」与「仪表与HUD → 核心安全区」
**各渲染一次、绑同一个字段**（`SettingsScreen.kt` 改前 1325 与 2095 行）。
同一个开关出现在两个分区，用户改了这边那边也变，只会以为见鬼。

### 定案（车主经选项确认）

| 问题 | 结论 |
| --- | --- |
| 布局方向 | **左右两列**（车主主动提出「能否左右布局」——本车 1920×1080 横屏正该这么用） |
| 铺开范围 | **只改「仪表与HUD」**，其余分区保持单列（项目有「统一性以页为单位」的纪律，但本分区内容最多、问题最集中，先只改它） |
| 重复项归属 | **只留「仪表与HUD」**；「显示」里那条删掉 |

### 改动

| # | 层 | 文件 | 改动 |
| --- | --- | --- | --- |
| 1 | 界面 | `car/ui/…/screens/SettingsScreen.kt` | `ClusterSection` 拆成 **4 个私有分组 composable**，用 `Row` + 两个 `Modifier.weight(1f)` 的 `Column` 排成两列；删掉「显示」里重复的那条开关 |
| 2 | 台账 | `PATCHES.md` | 本条目 |

**新结构**（按车主的决策顺序，每列只回答一类问题）：

| 列 | 分组（`SectionHeader` + `CarCard`） | 回答的问题 |
| --- | --- | --- |
| 左 | `ClusterStatusGroup` · 投屏状态（**只读，置顶**） | 现在是什么情况 |
| 左 | `ClusterTargetGroup` · 投屏与副屏 | 投不投、投到哪块屏 |
| 右 | `ClusterContentGroup` · 仪表内容 | 让 iPhone 画什么 |
| 右 | `ClusterFrameGroup` · 画面与安全区 | 画面怎么摆 |

**顺带修掉的四处细节**：

- 「重新检测」与「逐块试屏」**并排一行**（同尺寸档、间距 `spaceL`，见 `REF-ui` §B）；
  后者在候选为空时**置灰而不隐藏**（项目纪律：前置条件不满足要常驻 + 置灰）。
- 黑屏告警里的指引文案由「到下面**选择仪表副屏**」改为「到下面**投屏与副屏**」（分组改名）。
- 「画面旋转 180°」描述里两个**字面 `**`**（Markdown 加粗）去掉 —— `Text` 不渲染 Markdown，
  原来会把星号原样显示出来。
- 「仪表内容」分组补了 subtitle「三项都是立即生效，不需要重连」，与左列的会话级总闸区分开。

### 边界与刻意的取舍

- **只动 `car/ui`，不碰任何逻辑/契约**：字段、默认值、`CarPlayConfigBuilder` 映射一律未改；
  单测基线不变。
- **两个对话框留在函数末尾**：`CarChoiceDialog` 走 `AlertDialog`（独立窗口），
  挂在组合树的哪一层都不参与两列布局。
- **`verticalAlignment = Top` 是必须的**：两列高度天然不等（左列有候选列表），
  非 `Top` 的对齐会把短的那一列整体拉开。
- **不改 `contentMaxWidth`**：那是全局 token，为一个分区单独放宽会破坏「页面宽度一致」。
  两列在 1120dp 内等分（每列约 560dp）已足够，且不需要滚动。
- **「显示」里不留跳转提示行**：本分区管的是**主屏**系统栏避让，在这里挂一条
  「去仪表与HUD改」的只读行，反而把两个不相干的概念又粘回去；代码里留注释说明去向。

### 测试

**无新增单测**：`car/ui` **没有 test source set**（只有 `main/`），
且本补丁纯属布局重排、不改任何可断言的逻辑。已确认 `car/core-bridge` 的两条
静态读源码断言（`ClusterSafeAreaContractTest` / `ImmersiveVideoSafeAreaTest`）
只在**注释**里提到相关文案，不读 `SettingsScreen.kt`，不会因本次重排而红。

**验证**：`:car:ui` / `:car:app` `compileDebugKotlin` **BUILD SUCCESSFUL**
（已确认两个任务真执行、非 `UP-TO-DATE`）；
`:car:core-bridge:testDebugUnitTest` + `:car:platform-geely:testDebugUnitTest` **0 失败**；
`grep -rn "draft.safeAreaDrawOutside" car/ui/src/main` 由 **2 处降到 1 处**。

### 未做

- **实车验证**：确认两列在 1024×600 这类**小屏车机**上不会挤（本车 1920×1080 没问题；
  更窄的屏上两列各约 560dp×scale，需实车确认是否要退化成单列）。
- **出包**（等车主明确说"出包"）。

---

## PATCH-0097 · 移植 Ecarx 原厂热点客户端与路由只读识别

### 背景

CS11/KX11 原厂热点由 Ecarx/tcam 管理，传统 Android SoftAP 状态可能假报关闭；热点客户端信息需经厂商 SDK 读取。车机本地 route/source address 也不能与热点网关混为一谈。

### 改动（先前代码已随 origin/dev 到达；本条补登记）

- `EcarxHotspotReader` 反射读取 Ecarx AP 状态、客户端与 MAC 关联的邻居记录；客户端地址只读地映射到内核路由选中的本机接口/源地址。
- `EcarxClientRoute` 对跨网段 route/table/gateway 作严格验证；CS11/KX11 门控为 Android 9、eth* 与 198.18/15 源地址。
- 快照含 `verified / no_current_client / no_verified_local_route / ambiguous / unavailable` 诊断结论；不启动/配置热点，不改路由。

### 验证与边界

- `EcarxHotspotAdaptationTest` 覆盖平台门控、地址分类与路由/前置判断。
- `routedHosts` 的 value 是**通往 Ecarx SDK 客户端的本机路由源地址**，不是 AP 网关（车机 UI 中的 192.168.15.1）；只有唯一已验证路由能用于后续连接决策。
- 历史提交：`1b5c64c`（`feat(hotspot): 移植吉利 Ecarx 原厂热点识别`）。

---

## PATCH-0098 · 诊断包采集 Ecarx 原厂热点识别快照

### 改动

- `system/wifi_direct.txt` 追加 Ecarx SDK/API 与客户端路由识别快照，包括平台标识、状态、route/interface 与候选地址。
- 新字段中的本机 route source address 不可标注成“SDK 报的热点宿主/网关地址”；后续 `PATCH-0099` 将统一修正文案并加入本次连接决策来源与结果。

### 验证与边界

- 首个实车快照：CS11/Android 9，`verified clients=1 routes=[eth0.11]`，本机 source `198.18.34.15`；后续包 `no_current_client`，代表采集时无 SDK 可见客户端，并非接口/路由探测错误。
- 历史提交：`e693ebf`（`feat(diagnostic): 诊断包补 Ecarx 原厂热点识别段`）。

---

## PATCH-0099 · Ecarx 验证路由作为 MANUAL CarPlay endpoint，并诊断地址角色

### 背景与根因

车机 Wi-Fi AP 广播在外置 T-Box/MCU 网络（客户端通常为 `192.168.15.x`），该 AP 的 `.1` 不在 Android 本机接口上。AirPlay/CarPlay endpoint 则是本机监听服务的可达地址：既有实车日志证明 iPhone peer `192.168.15.8` 可经车载网络访问 `198.18.34.15`，且 RTSP URL 使用该地址。PATCH-0097 识别到的 `eth0.11 / 198.18.34.15` 是此本机 route source，不是热点网关。

此前 PATCH-0098 只在采包时重读路由；`CarPlaySessionBridge` 会因 `manualHotspotHostAddress` 为空而拦截，且上游 `ManualHotspotManager` 只按用户给的地址找接口，无法表达“一个 SDK 验证的 eth 子接口 + 本机 endpoint”组合。

### 定案

- 仅当 `MANUAL && reused`、Ecarx 型号门控通过、厂商 SDK 返回唯一 verified client route、状态不冲突，且该接口当前 UP 并持有本机 IPv4 时，使用该 `(interfaceName, localSourceAddress)` 作为 `0x4301.wirelessIP` / Bonjour 的 endpoint；优先于错误地把 T-Box 网关 `.1` 当本机地址。
- 该证据同时可作为热点 ON 的正向旁证，覆盖本车标准 SoftAP 的假关闭；`enabled=false` 与 verified route 矛盾时不自动选路，留诊断后走既有手动/快速失败路径。
- 无客户端、无唯一路由、路由歧义/过期或非 Ecarx 时不猜、不把 `198.18.34.15` 硬编码，也不改变其他热点模式。用户设置值只读对照、不回写。
- `:shared` 增加可选的严格接口名 + IPv4 override：只在二开层把通过门控的 Ecarx route 明确传入时接受 eth 上行接口；接口/地址不再匹配时不回落到名称打分。默认 null 保持旧 MANUAL/P2P 行为。
- 诊断包明确区分 `oemGateway/configuredHost`、`routeSource`、`selectedInterface`、`advertisedEndpoint`、Android SoftAP 状态、SDK enabled 与 route status；连接运行日志记录实际选路与原因，不记录密码/MAC/客户端原始 IP。

### 验证

- **新增单测（纯 JVM，无需真车）**
  - `car/core-bridge/.../hotspot/EcarxHotspotAttachmentPolicyTest.kt`：verified route 选出**本机** endpoint（显式断言结果 ≠ `192.168.15.1`）；`enabled=null` 可接受但仅限唯一 verified；`enabled=false` 与 verified route 冲突时 fail closed；无客户端 / 路由歧义不猜；接口 down、地址不符、接口名不符各自拒绝；非 Ecarx 平台与非 `198.18/15` 源地址隔离。
  - `car/core-bridge/.../hotspot/EcarxHotspotDiagnosticSummaryTest.kt`：诊断文本必须区分 configured gateway / route source / selected advertised endpoint，且**不得**出现 `clientMac` / `passphrase`。
  - `car/core-bridge/.../developer/CarDiagnosticEcarxContractTest.kt`：静态读 `CarDiagnosticPackager` 源码，钉住 Ecarx 段确实走 `EcarxHotspotDiagnosticSummary.render(...)`、`readStandardWifiApState(appContext)`、`EcarxHotspotSessionObservation.latest`、`Current local interface inventory:`、`Read-only OEM route table`。
  - `shared/.../network/ManualHotspotInterfaceSelectionTest.kt`：严格选择成功；DOWN / 回环 / **目标地址不在指定接口上（不得跨接口兜底）** 三种 fail closed；并显式钉住"接口名由调用方给 ⇒ 同地址挂多网卡不算歧义"这一语义。
  - 既有用例增补：`EcarxHotspotAdaptationTest`（Ecarx 上"未知 + 标准 SoftAP 假 OFF"降级为 `UNKNOWN`，明确 OFF 仍保留；唯一 verified route 可作 ON 旁证而空客户端不行）、`CarManualHotspotAddressTest`（验证的接口名 + 本机 endpoint 一起透传上游）、`ManualHotspotConfigTest`（接口名必须与地址成对、且必须是合法纯接口名）、`CarManualHotspotPrecheckTest`（文案要说明这是**本机** CarPlay endpoint、并提醒不要填外置网关）。
- **被本设计推翻、已同步更新的既有契约断言**（`PATCH-0099` 改变了判据输入与日志文案，非放宽）：
  - `CarHotspotModeIsolationContractTest`：`plan.*` → `effectivePlan.*`；"不按网卡名猜"改为"放行日志必须标明地址来源（`user configuration` / `Ecarx verified client route`）"；**新增**"Ecarx 覆写必须按接口名 + UP + 持有该地址三条件严格核对""严格分支不得出现 `addressPresentAnywhere` 兜底"。
  - `CarDiagnosticEcarxContractTest`：断言从全限定名改为方法名（receiver 换行导致字面量匹配不到，那是格式不是契约）。
- **实测结果（本机，2026-10-12）**
  - `:car:core-bridge:testDebugUnitTest` → **779 tests, 0 failures**
  - `:shared:testDebugUnitTest` → **239 tests, 0 failures**
  - 全链路 `compileDebugKotlin`（`:shared` `:car:api` `:car:platform-android` `:car:platform-flyme` `:car:platform-ecarx` `:car:platform-geely` `:car:core-bridge` `:car:ui` `:car:steering` `:car:app`）→ **BUILD SUCCESSFUL**
  - 只用 `compileDebugKotlin` 验证，未用 `assemble*`（会撞 `allowRelease` 闸门，见 REF-debug）。
- **尚未做**：真车回归（需 iPhone 连车机原厂热点后复测 `0x4301.wirelessIP` 与诊断包新段落）；未出包、未提交、未推送、未同步上游。

### 验证过程中发现的既有问题（非本次改动引入）

- `CarDiagnosticSettingsDumpTest` 的「白名单完整性」用例在本次开工前即为**红**：`c70e617` 往 `CarSessionSettings` 加了 `clusterManualCruise` 却漏了 `CarDiagnosticSettingsDump` 的对应行。已按该用例自身指引补上 `clusterManualCruise: …`（含注释说明它是 `CarClusterCruiseGate.canManualOpen()` 的一半条件）。属 `car/api` 二开层文件，不涉及上游。

---

## PATCH-0100 · 仪表投屏改为三指手势按需触发

### 背景与根因

仪表副屏投屏原先的语义是「投屏开关一开就**一直投**」：`CarAppController` 在
`CarSessionEvent.SessionStarted` 分支里**无条件**调 `startClusterIfPossible()`，而
`GeelyClusterPresenter.attach()` 末尾会 `seizeCluster()`
（`DimMenuInteraction.switchNaviMode(3)`）。

**后果**：只要 iPhone 连上，车机仪表就被抢成"全屏地图"模式 —— 用户想用车机原厂
仪表（油量 / 车速 / 胎压）反而用不了。真车诊断包
`1791725755414_dc2d79e5_67b3c24af6b5` 逐字对得上：

```
21:34:25.824  Cluster: 仪表副屏已挂载 displayId=2 面板=1920x1080 可见高=720
21:34:25.828  Cluster: 仪表抢屏 entered=true（类=com.ecarx.xui.adaptapi.diminteraction.DimMenuInteraction）
```

而这一切的触发点只是"会话建立了"，与用户意图无关。

车主口径（2026-10-12）：

> 仪表投屏功能默认开启，但启动后不要立即投屏，改为按需触发。
> 默认保持仪表投屏功能可用，只有在用户需要时，通过三指滑动手势主动触发……
> 未触发手势时，不主动投屏，避免影响车机正常使用。

### 实施前调研结论（三条，都改变了对改法的判断）

1. **「三指上滑」整条链是悬空的**：`CarUiActions.toggleProjectionUi()` →
   `CarAppController.toggleProjectionUi()` → `CarSessionController.toggleProjectionUi()`
   → `CarPlaySessionBridge.toggleProjectionUi()` → `AirPlaySession.setClusterUiShown()`。
   **全工程零个触摸侧调用点**；`clusterUiShown` 恒 false；`setClusterUiShown()` 从未执行过。
   ⇒ 不能"复用三指上滑"，那条链需要接的是**触摸入口**，不是显示开关。
2. **真正让 iPhone 出画的不是我们的 `showUI`，是 `/info` 里的 `initialURL`**：
   5 个诊断包里 `仪表内容：showUI … → 未发（clusterStream=1）` —— `showUI` 一次都没发成功，
   而投屏确实成了（161 秒 / 2968 帧）。原因在 `CarPlayConfigBuilder.toAirPlayDisplay()`：
   副屏声明带了 `initialUrl`，iPhone 建 111 流后**自己**按它渲染。
   ⇒ 本次**刻意不动** `initialUrl`：动它就必须依赖 `showUI`，而那条路当前是坏的
   （去掉 `initialUrl` 会得到"手势点了没反应"）。
3. 参考项目 `GeelyCarPlay-吉利集成-还原` 用的是**四指**（`GeelyFourFingerGesture`，
   判据 `pointerCount == 4`；四指左滑 = 开仪表地图，四指下滑 = 开设置）。
   **判据可移植，指数量按车主口径改成三指。**

### 定案

- 手势：**CarPlay 画面页三指左滑 = 投屏 / 三指右滑 = 收起**。
- **收起 = 完全拆窗**：`stopCluster()` → `GeelyClusterPresenter.stop()` 内部
  `dim.exitFullNavi()`，把仪表还给车机原厂。只停画不拆窗会在仪表上留一块透明窗口、
  且抢屏状态不还回去，与"不影响车机正常使用"矛盾。
- 手势**只在 CarPlay 画面页生效** —— 与参考实现同范围（它的 gestureLayer 挂在视频页上），
  语义也匹配"把**当前画面**投到仪表盘"；不动主页/设置页的触摸分发。
- 判据移植自原版 `GeelyFourFingerGesture`，**一字未改**：触点**质心**位移 + 阈值 72dp +
  方向占优 1.15× + `isConsuming` 吞事件 + 一次手势只触发一次（`fired`）。
- 触摸分流：**未进入手势时完全照旧转发**；进入手势的那一刻发一次
  `onTouch(emptyList())` 清掉已报出去的前 1~2 个触点（防 iPhone 侧幽灵触点）；
  手势期间吞掉事件不转发（CarPlay HID 触摸描述符只有 2 点，三指事件截成 2 点只会更乱）。
- `settings.projectionEnabled` 语义从"立刻投"改为"**功能是否可用**"，
  **默认值 false → true**（"开着"不再等于"投出去"，默认开着没有代价；车主口径"默认开启"）。
- `CarPlaySessionBridge.updateSettings` **删除** `projectionWanted = next.projectionEnabled`
  （PATCH-0025 那一行）：持久开关不再灌进运行时意图，否则"上次开着 ⇒ 这次一连上就自动投"。
  `projectionWanted` 现在只由 `setProjectionEnabled()` 写，并在 `onSessionEnded` 清成 false
  （**断线重连不算一次手势**，必须重新滑）。

### 改动清单

| 文件 | 改动 |
| --- | --- |
| `car/ui/.../CarThreeFingerGesture.kt` | **新增**。纯逻辑手势判据（不 import 任何 Android 类，JVM 可测） |
| `car/ui/.../CarUiContract.kt` | `CarUiActions` 新增 `onClusterGesture(action)`；订正 `toggleProjectionUi()` 的语义注释（它不是手势入口） |
| `car/ui/.../screens/CarPlayScreen.kt` | `CarPlayVideoSurface` 触摸层插入手势识别与分流；新增 `pointerCentroid()` |
| `car/ui/.../screens/SettingsScreen.kt` | 「仪表地图投屏」开关描述改写；`summaryCluster()` 末条改「已就绪」并写入手势提示；`ClusterSection` KDoc 第 1 条改写 |
| `car/app/.../CarAppController.kt` | `SessionStarted` 删除 `startClusterIfPossible()`；新增 `onClusterGesture()` / `showClusterByGesture()` / `hideClusterByGesture()`；`startClusterIfPossible()` KDoc 订正 |
| `car/api/.../settings/CarSessionSettings.kt` | `projectionEnabled` 默认 `true` + KDoc 重写；`clusterManualCruise` 补"当前无消费点"说明 |
| `car/api/.../session/CarProjectionState.kt` | 「三指上滑」→「三指左滑」（含 `summary` 文案） |
| `car/api/.../session/CarSessionController.kt` | `toggleProjectionUi()` 注释订正 |
| `car/api/.../display/CarClusterCruiseGate.kt` | 补"当前没有任何生产消费点"整节说明 |
| `car/api/.../developer/CarDiagnosticSettingsDump.kt` | `clusterManualCruise` 行注释订正 |
| `car/core-bridge/.../CarPlaySessionBridge.kt` | 删 `projectionWanted = next.projectionEnabled`；`onSessionEnded` 清投屏意图；两处文案 |

**未动**：`:shared`（含 `AirPlaySession` 的 `setClusterUiShown` / `setClusterUrl` / `requestClusterKeyFrame`）、
`:car:platform-geely`、方控 / 音频 / 导航链路、`initialUrl` 的生成。

### 验证

- **新增单测（纯 JVM，无需真车）** `car/ui/src/test/java/com/dbtool/carplay/ui/CarThreeFingerGestureTest.kt`
  （18 例）。放在 `car:ui` 而不是 `car:core-bridge`：本模块 `build.gradle.kts` 早就为此
  配好了 `testImplementation(libs.junit)` 并注明"三指手势判定是纯函数（见 `CarThreeFingerGesture`），
  必须能单测" —— 本次是把这个既有的既定约定落地。
  两侧后果都钉住了：
  - **太灵敏**（会让画面页点不动）：`两指滑动不吞事件`、`未达阈值不触发`、`斜滑不触发`、`多按一指不误触发`；
  - **太迟钝 / 状态没复位**（会让手势时灵时不灵）：`抬手后下一次仍可触发`、`取消后下一次仍可触发`、
    `DOWN 会清掉上一轮的残留状态`；
  - 另有 `一次手势只触发一次`、`触发后到抬手前一直保持接管`、`掉一指后不再判定但仍保持接管`、
    `横向占优的斜滑仍然触发`（真人划不出完美水平线）、`默认值与原版一致`（72f / 1.15f / 3 指）、
    两条构造参数校验。
- **实测结果（本机，2026-10-12）**
  - `:car:ui:testDebugUnitTest` → **18 tests, 0 failures**
  - `:car:core-bridge:testDebugUnitTest` → **779 tests, 0 failures**
  - 全链路 `compileDebugKotlin`（`:shared` `:car:api` `:car:platform-android` `:car:platform-flyme`
    `:car:platform-ecarx` `:car:platform-geely` `:car:core-bridge` `:car:ui` `:car:steering` `:car:app`）
    → **BUILD SUCCESSFUL**
  - 只用 `compileDebugKotlin` 验证，未用 `assemble*`（会撞 `allowRelease` 闸门，见 REF-debug）。
- **尚未做**：真车回归（需在车上试三指左滑/右滑，并确认收起后车机仪表恢复原厂界面）；
  未出包、未提交、未推送、未同步上游。

### 未做 / 需要真车确认的三件事

1. **延迟挂窗后能否立刻出画**。原先 Surface 在 111 流 SETUP 后约 2 秒到达；现在可能延迟到
   用户手势那一刻（数分钟）。依据：`AndroidCarPlayMediaSink.attachClusterSurface()` 走
   `MediaCodec.setOutputSurface()`（支持运行中换 Surface），且诊断包里首帧与
   `surfaceValid=true` 出现在**同一时刻** ⇒ 预期可以。**但未真车验证。**
   若实测"窗在、画面黑"，补一帧 `AirPlaySession.requestClusterKeyFrame()` 即可
   （该 API 已在 `ClusterProjectionController` 的非平台路径里用过，属既有能力）。
2. **`dim.exitFullNavi()` 中途收回的效果**。`GeelyClusterPresenter.stop()` 里一直有这一条，
   但此前只被"会话结束 / 用户点停止"走到过，**"投屏中途 stop"从未在真车验证**。
3. **`clusterManualCruise`（允许无导航时手动开启）目前没有任何生产消费点**。
   `CarClusterCruiseGate` 只有单测在用它，手势**刻意不读**这个门：它默认关闭，且
   `routeActive` 还要从桥里的 `Iap2RouteState` 取 —— 一旦接上，新装用户三指左滑会变成
   "划了没反应"（提示只出现在日志里），与"按需触发"的本意正好相反。
   本次**没有**扩大范围去接它，只把"无消费点"这件事写进 KDoc 与诊断包注释。
   要接的时候先读 `CarUiActions.onClusterGesture` 那一侧的接线方式，别把"用户显式要求"
   与"跟随路线状态"两条链并成一条。

### 验证过程中发现的既有问题（非本次改动引入）

- 本机 `car/api/build` 产物陈旧（早于 `d43991a` 新增 `CarDeveloperContract.kt`），
  导致 `:car:app:compileDebugKotlin` 报
  `Unresolved reference 'DEVELOPER_UNLOCK_TAP_COUNT'`，而 `:car:api:compileDebugKotlin`
  与 `:car:ui:compileDebugKotlin` 都是绿的（后者 import 同一个常量）。
  `./gradlew :car:api:clean` 后即恢复。**不是代码问题**，但值得记住：
  "同模块编译绿、下游模块报顶层常量 unresolved" 先怀疑 `car/api/build` 陈旧。

## PATCH-0101 · 三指手势做成独立开关（`clusterGestureEnabled`）

### 背景

车主问「三指滑屏没有设置按钮吗」。查实：三指手势**没有**专属设置入口 ——
当时唯一相关的开关是「仪表地图投屏」（`projectionEnabled`），而它在 PATCH-0100 之后
的语义是"**功能**是否可用"（能不能选副屏、要不要向 iPhone 声明第二块屏），
**不是**"手势要不要受理"。手势的方向（左滑投 / 右滑收）、起手指数（3）、
阈值（72dp）、生效页面（仅画面页）全部硬编码，设置页改不了。

车主随后选定：**加一个独立的手势开关** —— 能单独关掉三指手势，
但**不关掉整个投屏功能**。

### 定案

- 新增设置项 `CarSessionSettings.clusterGestureEnabled`（Boolean，**默认 true**），
  与 `projectionEnabled` 是**两个键、两个开关**。合并成一个键就没法表达
  "关手势但留功能"这个状态。
- **闸门位置**：`CarAppController.onClusterGesture()` 开头，**在动作分派之前** ——
  Show 与 Hide **一起**拦。开关的语义是"这个手势不被受理"，
  不是"只禁投屏、不禁收起"。
  - 不塞进 `showClusterByGesture()` 里那两道闸门旁边：那两道讲的是
    "投屏这件事为什么没成"（功能没开 / 没选屏），本项讲的是"手势压根不该被响应"，
    层级不同；混在一起会让日志文案失真（用户会看到"功能未开启"这种误导说法）。
- **触摸侧（`CarPlayScreen`）不做这个判断**：关掉手势后三指触摸**照旧被吞掉**、
  不转发给 iPhone。这不是漏改 —— CarPlay HID 触摸描述符只有 2 点
  （`CarTouchMapper.MAX_CONTACTS`），三指本来就传不过去；转发只会被截成 2 点，
  在 iPhone 上变成一次**没人想要的双指手势**（缩放/滑动）。
  "关手势"改的是**投屏动作**，不是触摸分流。
- **设置页**：`ClusterTargetGroup` 里在「仪表地图投屏」下方加「三指手势投屏」开关；
  前置条件不满足（`projectionEnabled == false`）时**置灰而不隐藏**
  （项目纪律：前置条件不满足要常驻 + 置灰，见 REF-pitfalls §4）。
  同时把「仪表地图投屏」的描述收窄成只管"功能"，手势说明下移到新手势项。
- **摘要**：`summaryCluster()` 新增 `!s.clusterGestureEnabled -> "…三指手势已关闭"`
  且**排在"未选副屏"之前** —— 否则摘要会显示"已就绪"，而用户划了三指什么也没发生，
  看起来像手势坏了 / 设置没保存。
- 与 `projectionEnabled` 一样走「保存并重连」，不立即生效（交互一致性）。

### 改动清单

| 文件 | 改动 |
| --- | --- |
| `car/api/.../settings/CarSessionSettings.kt` | 新增 `clusterGestureEnabled: Boolean = true` + KDoc（含"为什么关掉手势仍吞触摸"） |
| `car/core-bridge/.../SharedPreferencesCarSettingsStore.kt` | 新 key `cluster_gesture_enabled` + load + save |
| `car/api/.../developer/CarDiagnosticSettingsDump.kt` | 加 `clusterGestureEnabled: …` 一行（否则白名单完整性反射测试会红） |
| `car/app/.../CarAppController.kt` | `onClusterGesture()` 入口加闸门（Show/Hide 一起拦）+ KDoc |
| `car/ui/.../screens/SettingsScreen.kt` | 新增「三指手势投屏」开关（含置灰）；「仪表地图投屏」描述改写；`summaryCluster()` 新分支；`ClusterSection` KDoc 四件事 → 五件事 |
| `car/core-bridge/src/test/.../ClusterGestureGateContractTest.kt` | **新增**（10 例）静态源码契约 |

### 验证

- **新增契约测试** `car/core-bridge/src/test/java/com/dbtool/carplay/bridge/ClusterGestureGateContractTest.kt`（10 例）。
  放 `:car:core-bridge` 是因为闸门落在 `:car:app`（**无 test source set**）与
  `:car:core-bridge`，没有运行时可测的接缝 ⇒ 用**静态读源码断言**兜住
  （与 `ClusterSafeAreaContractTest` 同一套路）。钉住三个"错了完全看不出来"的失败模式：
  1. **闸门没接上**（开关能拨、也存进了 prefs，但 `onClusterGesture` 不读它 ⇒ 关了照样投屏）；
  2. **只读不写**（`save()` 漏一项 ⇒ 重启又变回开，`fullscreen` 就这么丢过一次）；
  3. **设置页开关不受总开关约束**（功能关着还能拨手势开关，用户以为拨了有用）。
  另有：默认值 true、两个键不合并、诊断包写的是**字段值**而非写死字面量、
  新开关文案里不许出现 markdown 星号（§82）。
  ⚠️ 断言一律**先剥注释**（§29）—— `CarAppController` 的注释里必然出现这些字面量。
- **实测结果（本机，2026-10-12）**
  - `:car:core-bridge:testDebugUnitTest` → **789 tests, 0 failures**
    （较 PATCH-0100 的 779 **+10**，全部是本次新增）
  - `:car:ui:testDebugUnitTest` → **18 tests, 0 failures**
  - 全链路 `compileDebugKotlin`（`:shared` `:car:api` `:car:platform-android` `:car:platform-flyme`
    `:car:platform-ecarx` `:car:platform-geely` `:car:core-bridge` `:car:ui` `:car:steering` `:car:app`）
    → **BUILD SUCCESSFUL**
  - 只用 `compileDebugKotlin` 验证，未用 `assemble*`（会撞 `allowRelease` 闸门）。
- **断言非空转已核**（§117：验证脚本自己的 bug 会让结论失效）：剥注释后
  `onClusterGesture` 函数体内 `settings.clusterGestureEnabled` 的命中位置（176）
  **先于** `showClusterByGesture()`（459）与 `hideClusterByGesture()`（530）；
  且该文件里非注释命中**只有闸门那一处** ⇒ 切片不会命中注释或别处。
- **尚未做**：真车回归（车上确认关掉开关后三指滑动确实无反应、
  且投屏功能的其余部分——选屏、副屏声明、设置页回显——不受影响）。
  未出包、未提交、未推送、未同步上游。

### 与 PATCH-0100 的关系

PATCH-0100 把"建窗"从会话事件挪到手势事件，但手势本身是**无条件**受理的。
本 patch 给手势补上独立开关，**不动** PATCH-0100 的任何判据、阈值、方向语义，
也不动 `/info` 的 `initialUrl`（出画真因，见 PATCH-0100）。

## PATCH-0102 · 修诊断包滚动日志覆盖丢失（§124）+ 复用热点失败时列出本机地址候选（§125）

### 背景

车主让排查 `日志/3` 里「复用车机热点无法使用」。报告出来后发现两件要落代码的事：

1. **诊断包丢中间整段日志**（`REF-pitfalls.md` §124）。车机上滚动日志是
   `xcertplay.log.3 / .2 / .1 / xcertplay.log` 四份，而采集清单里 `rollingLog`
   四条记录**全部把输出名写成 `logs/xcertplay.log`** ⇒ 后一条覆盖前一条，包里只剩最后一个。
   - `日志/1` 因此丢了 21:25:23–21:33:34（含仪表第一次挂载的完整过程）；
   - `日志/3` 丢了 16:57–17:00:09（那一段只能从 `logcat.txt` 拼回来）。
2. **「复用热点该填什么地址」是条死配置**（`REF-pitfalls.md` §125）。precheck 要求
   "你填的地址此刻必须在某块网卡上"，但设置页只有一个**空输入框**，而提示文案里
   写死的 `192.168.15.1` 是**外置 T-Box 网关、永远不在本机网卡上** ⇒ **照提示填必然被拦**。
   真车 `日志/3` 里 10 次尝试全灭（3 次空地址 + 7 次填 `192.168.15.1`），
   并拿到了闭环自相矛盾证据：16:58:41 说「本车是 192.168.15.1」，
   16:59:48 用户照填后说「它现在不在任何网卡上」。

车主选定：**两条都修，然后出包**。

### 定案

**§124 —— 输出名带上源文件自己的名字**

- `entryName` 从**族名** `name` 改成**源文件自己的名字** `source.name`
  ⇒ `logs/xcertplay.log.3` / `.2` / `.1` / `xcertplay.log` 各占一个条目，不再互相覆盖。
- 只改这一个标识符，**不动** `RollingLogFileWriter.familyOf()`
  （"索引越大越老"这个约定仍然只有一处实现）。

**§125 —— 把本机真实地址列出来让用户点选**

- 新增纯判据 `:car:api` `CarHotspotEndpointCandidates.of(...)`：从网卡快照里挑出
  **可作本机 CarPlay endpoint** 的地址，去重、基础网卡优先。
  - 保留：`isUp`、非 `lo`、IPv4 字面量、非链路本地（`169.254.*`）；
  - 排除：`selfAddresses`（我方自建**实际配上去**的地址）+ 只落在我方候选池里的地址
    （自建残留，历史上就是它被误当成车机热点通告出去）；
  - ⚠️ **刻意不排除"上行网卡"** —— 本车默认路由那张卡（`eth0.11`）挂的
    `198.18.34.15` 恰恰是唯一可用的 endpoint，按"排除上行"写会把正确答案筛掉。
  - 本车实测（`日志/3` 的 `wifi_direct.txt` 全量快照）⇒ 唯一一条 **`198.18.34.15（eth0）`**。
- 数据流：`CarAppServices.hotspotBriefing()` 读网卡 → `CarHotspotBriefing.endpointCandidates`
  → `CarUiModel.hotspotAddressCandidates` → 设置页「本机 CarPlay 地址」下方
  **渲染成可点的行**（`CarSettingAction`），点一下直接填进地址框。
- **关键：与 precheck 同源**（同一份简报、同一次网卡枚举）⇒ 从这儿点进去的地址，
  连接时一定过得了"在不在网卡上"那道校验，不会再现"页面让我填、连接说没有"的自相矛盾。

### 改动清单

| 文件 | 改动 |
| --- | --- |
| `car/core-bridge/.../developer/CarDiagnosticPackager.kt` | §124：`entryName` 改用 `source.name` + 注释（含两次丢日志的实证） |
| `car/api/.../hotspot/CarHotspotEndpointCandidates.kt` | **新增**：`CarHotspotEndpointCandidate` + 纯判据 `of(...)` |
| `car/core-bridge/.../CarAppServices.kt` | `CarHotspotBriefing` 加 `endpointCandidates` 字段；`hotspotBriefing()` 填充 |
| `car/ui/.../CarUiContract.kt` | `CarUiModel` 加 `hotspotAddressCandidates` |
| `car/app/.../CarAppController.kt` | `refreshModel()` 填充候选 |
| `car/ui/.../screens/SettingsScreen.kt` | 「本机 CarPlay 地址」下方渲染可点候选行 |
| `car/core-bridge/src/test/.../hotspot/CarHotspotEndpointCandidatesTest.kt` | **新增**（10 例，纯行为测试） |
| `car/core-bridge/src/test/.../developer/CarDiagnosticRollingLogContractTest.kt` | **新增**（2 例：行为 + 源码契约） |
| `car/core-bridge/src/test/.../HotspotEndpointCandidatesWiringContractTest.kt` | **新增**（4 例，跨四模块接线契约） |

### 验证

- **新增测试 16 例**（全部落 `:car:core-bridge`，因为 `:car:api` 未配 JUnit、
  `:car:app` 无 test source set，见 §127）：
  - `CarHotspotEndpointCandidatesTest`（10）：本车快照只出一条 `198.18.34.15（eth0）`、
    回环 / down / 链路本地 / 自建残留各自被筛掉、车机网段地址保留、
    **上行网卡地址必须保留**、空快照给空表、`label` 带网卡名。
  - `CarDiagnosticRollingLogContractTest`（2）：整族四份各占一个输出名（行为断言）+
    源码里 `entryName` 必须取 `source.name`、不许回退成族名（防回退）。
  - `HotspotEndpointCandidatesWiringContractTest`（4）：`api → core-bridge → app → ui`
    四段接线各一条，防"策略写好了却没接上"（§104-③ 静默失败）。
- **实测结果（本机，2026-10-12）**
  - `:car:core-bridge:testDebugUnitTest` → **805 tests, 0 failures**
    （较 PATCH-0101 的 789 **+16**，全部是本次新增）
  - `:car:ui:testDebugUnitTest` → **18 tests, 0 failures**
  - 全链路 `compileDebugKotlin`（`:shared` `:car:api` `:car:platform-*` `:car:core-bridge`
    `:car:ui` `:car:steering` `:car:app` 十模块）→ **BUILD SUCCESSFUL**
  - 只用 `compileDebugKotlin` 验证，未用 `assemble*`（会撞 `allowRelease` 闸门）。
- **断言非空转已核（§117）**：把两处实现**临时改回错误写法**
  （`entryName` 用族名 / `hotspotAddressCandidates` 写死 `emptyList()`），
  两条契约测试**当场变红**（`6 tests completed, 2 failed`）⇒ 断言确实在守实现，
  不是"永远绿"的假测试。随后改回、复跑全绿。
- **尚未做**：真车回归（车上确认设置页确实列出 `198.18.34.15（eth0）` 且点一下能填、
  下次诊断包里 `logs/xcertplay.log.1/.2/.3` 都在）。

### 与 PATCH-0099 的关系

§125 的**核心**（Ecarx 验证路由 + 不再把 T-Box 网关当答案）已由 **PATCH-0099** 修掉；
本 patch 补的是它剩下的那一半 —— "用户拿不到真实值"这个**交互层**缺口。
两者不冲突：Ecarx 路由验证成功时 `ensureManualHotspotReady` 会**临时用验证到的本机地址**
（设置项不被改写），候选清单只在用户需要手填时给参考。


## PATCH-0103 · 设置页改自动保存（改完即落盘 + 底部常驻状态行 + 重连仍手点）

### 背景

车主原话：

> 请将设置功能改为自动保存模式：在用户修改任意设置项后自动持久化保存，
> 无需手动点击保存按钮，并确保保存状态有相应提示。

### 定案（两个关键决策均由车主选定）

1. **自动保存，重连仍手点**。改动立刻落盘；但那些**必须重连才生效**的设置
   （画布尺寸 / `/info` 声明 / 音频通道号 / 热点身份…）不自动重连 ——
   重连会掐断正在投的屏。界面给出提示 + 「重连生效」按钮。
2. **保存状态提示用底部常驻状态行**：保存中 / 已保存 `HH:mm:ss` / 保存失败（带原因）。

### 实现

**1. 判据：哪些改动必须重连（新增，`:car:api` 纯逻辑）**

`car/api/.../settings/CarSettingsReconnectPolicy.kt`

- 手法是**白名单**（列"就地生效"的），不是黑名单（列"要重连"的）。
  `requiresReconnect(previous, next)` 把白名单字段用**旧值**覆盖后整体比较。
- **刻意选白名单的原因是不对称的失败方向**：漏列一个"就地生效"项 ⇒ 界面多一句
  提示、用户多点一次重连（无害）；漏列一个"必须重连"项 ⇒ 改动不生效却毫无提示，
  且用户因为"已经自动保存了"根本不会怀疑。所以**新增字段默认落在"要重连"一侧**，
  要被当成就地生效必须显式登记。
- `LIVE_FIELDS`（10 项）：`clusterFlip180` / `dockAppOrder` / `keepHotspotAlive` /
  `safeArea` / `safeAreaDrawOutside` / `debugLogs` / `hideTopBar` / `hideBottomBar` /
  `dockBar` / `fullscreen`。

**2. 生效基线（`:car:app`）**

- `CarAppController` 新增 `reconnectBaseline`：**上一次真正灌给会话的设置快照**。
  - 推进时机刻意只有三处：`startSession()`（冷启动）、`reconnect()`（用户点重连）、
    `applySettings()` 里**这一次确实发生了重连**时（换仪表屏会自动触发重连）。
  - ⚠️ **刻意不在**自动重连（会话断了自检重连、画布尺寸上报变化重连）里推进
    —— 那些是系统自发行为，在那里推进会"待重连"提示在用户毫无操作时悄悄消失。
- `CarUiModel.settingsPendingReconnect` = `requiresReconnect(reconnectBaseline, settings)`。

**3. 页面骨架（`:car:ui`）**

- `CarScreen` 新增可选参数 `footer: (@Composable () -> Unit)? = null`，渲染在
  `weight(1f)` 的内容区**之后** ⇒ **常驻底部、不随内容滚走**。
  设置页分区内容往往要滚一两屏，状态行若在末尾就等于没有提示。

**4. 设置页（`:car:ui`）**

- `update(next)` 只改本地草稿；`LaunchedEffect(draft, model.settings)` 静置 500ms 后
  调 `applySettings(draft, reconnect = false)`。
  - 防抖的理由：数字框/滑条是逐帧回调的，而每次提交都会走 `refreshModel()`
    （重算停靠栏、当前播放、日志快照…），在主线程逐帧跑会掉帧。
- 🔴 `DisposableEffect(Unit)` 兜底：离开设置页时若草稿仍未落盘就补交一次。
  **没有它就有数据丢失** —— 防抖窗口内直接返回，effect 被取消而新的不会再启动，
  但用户以为"自动保存"早就存好了。用 `rememberUpdatedState` 读最新草稿。
- 撤掉「保存并重连」与「放弃修改」：自动保存之后保存不需要用户动作；
  而已落盘的草稿也没有"放弃"的对象。
- 新增 `SettingsSaveStatusBar`：失败 > 进行中 > 待重连 > 已保存 的优先级显示，
  待重连时右侧给「重连生效」按钮。`applySettings(draft, reconnect = true)`。
  - ⚠️ `applySettings` 的短路条件是 `!reconnect && settings == this.settings`：
    `reconnect=true` **不能**被短路，否则用户点按钮时草稿恰好已自动保存完、按钮就成空操作。
- 顺手把设置页里 13 处过时的"改完点「保存并重连」"文案（含运行时字符串）全部改成
  「重连生效」—— 那是**面向用户的话**，留着会与自动保存直接矛盾。

### 验证

- `:car:core-bridge` 单测 **841 tests / 0 failures**（805 → 841，新增 36 例）；
  `:car:ui` **18 tests / 0 failures**。
- 新增 `CarSettingsReconnectPolicyTest`（26 例）：10 个白名单字段逐项、代表性必重连字段、
  方向无关、白名单字段名真实存在（防拼写错 ⇒ 某字段永远不被判定为就地生效）、白名单无重复。
- 新增 `SettingsAutosaveContractTest`（10 例，静态读源码）：四个失败模式各一条 ——
  提示接不上 / 提示永不消失 / 基线在系统自动重连里被顺手推进 / 防抖窗口内草稿丢失；
  外加 `reconnectBaseline` 赋值点总量==3（startSession / reconnect / applySettings）。
- §117 非空转已核：把 `requiresReconnect` 改恒 `false`、`refreshModel` 的两个实参互换、
  `commitSettings` 的 `reconnect` 改 `true` ⇒ **13 例当场变红**；改回后全绿。
- 全链路 `compileDebugKotlin`（十模块）BUILD SUCCESSFUL。
- ⚠️ 曾因重构把 `if (reconnect || clusterDisplayChanged) session.reconnect()` 收进局部变量，
  撞红既有契约 `CarHotspotModeInitContractTest`（它钉的就是那行的原文，
  要证明"热点模式清理排在重连之前"）。**回退写法**（两处条件重复写），不去改那条既有契约。

---

## PATCH-0104 · 修「复用车机热点」白等 60 秒：`eth` 排除表拆分（方向 2）+ 秒级闸门与中文文案（方向 3）

### 背景

日志 4（CS11，`1791800023427_43526b31_e3de67df72a0`，2026-10-12）排查结论：
用户在设置里填好了车机热点地址 `198.18.34.15`，二开层自己的预检也说通过
（`manual hotspot precheck: state=ON effective=ON` / `endpoint: source=user configuration … interface=eth0.11`），
但上游 `ManualHotspotManager` 每次返回 null，最终
`Timed out after 60000ms waiting for the manual hotspot: local hotspot interface was not found`。
4 分钟日志里白等了 **60 秒**，且每 2.27 秒刷一行 warning。

根因是一条**共用的排除表**：`EXCLUDED_INTERFACE_PREFIXES` 里的 `eth`
（2026-10-03 `5f1c43e` 为"按名字猜 AP"那条路加的）把
"用户填了地址、按地址找网卡"那条路一起挡死了 —— 本机承载该地址的网卡全是 `eth0` / `eth0.2~6` / `eth0.11`。

完整排查报告：`CarPlay/DBPlay-复用车机热点排查报告-日志4-20261012.md`。
本次只实施其中的**方向 2** 与 **方向 3**（车主原话「一起做」）。

### 定案

**主语不同，排除表就该不同。**

| 表 | 用在 | 含 `eth`/`usb` |
| --- | --- | --- |
| `EXCLUDED_INTERFACE_PREFIXES` | 两条路的地基（回环/隧道/bond） | 不含 |
| `GUESS_EXCLUDED_INTERFACE_PREFIXES` | **只有"猜 AP"那条路** | 含 |

- "**猜**"是我们在猜 → 有线口一个都不能要（2026-10-03 的场景：没有 AP 在跑时按名字猜到 `eth0.3`）。
- "**找**"是用户已经说了算（他填了地址）→ 声明的就是权威，`eth*` 完全可能是对的
  （Ecarx 的车机热点根本不走标准 SoftAP，地址挂在 `eth*` VLAN 子接口上）。

### 实现

**1. `:shared` 拆分排除表（方向 2，动了上游文件）**

`shared/.../network/ManualHotspotManager.kt`

- `EXCLUDED_INTERFACE_PREFIXES` 去掉 `eth`/`usb`，只留
  `lo / dummy / rmnet / r_rmnet / tun / ppp / sit / ip6 / bond`。
- 新增 `GUESS_EXCLUDED_INTERFACE_PREFIXES = listOf("eth", "usb")`，KDoc 说明上述分工。
- `isUsableInterface()` 改用 GUESS 表；`findInterfaceCarryingAddress()` 只排通用表
  （KDoc 明写"刻意不排有线口"）。
- 🔴 严格路径（`findInterfaceCarryingVerifiedAddress` → `selectStrict`）**一个字节没动**：
  它本来就是 iface+IP 精确匹配，日志 4 里 3 次成功的 `iface=eth0.11` 走的就是它。

**2. 秒级闸门（方向 3，`:car:api` 新增纯逻辑）**

`car/api/.../hotspot/CarManualEndpointGate.kt`

- `evaluate(address, interfaces, verifiedRouteInterface)` → `CarManualEndpointVerdict`：
  `OK` / `NOWHERE` / `WIRED_ONLY(interfaces)`。
- 判据**刻意保守**：
  - 有 Ecarx 已验证 route（`verifiedRouteInterface` 非空）⇒ **永不拦**（最强证据）；
  - 地址不在任何 UP 的网卡上 ⇒ `NOWHERE`（与改动前行为一致）；
  - 地址**只在**有线口上 ⇒ `WIRED_ONLY`（本次新增，即日志 4 那种"预检说通过、上游必然失败"）；
  - 只要有一张非有线口也带着它 ⇒ 放行，宁可错放不误杀。
- `WIRED_INTERFACE_PREFIXES = listOf("eth", "usb")` 与上游 GUESS 表是**语义同一份的两处**
  （跨模块引不动），由测试钉住一致。

**3. 接线（`:car:core-bridge`）**

`car/.../bridge/CarPlaySessionBridge.kt`

- 预检拦截条件由 `onInterface == null` 改为
  `onInterface == null || endpointVerdict != CarManualEndpointVerdict.OK`。
- `message` 改 `when`，新增两条 `WIRED_ONLY` 中文文案（Ecarx / 非 Ecarx 各一条）：
  - Ecarx 版要点明"只挂在车机内部有线网口 + SDK 没报告任何已连接客户端"，
    并给两条出路（先把 iPhone 连上原厂热点；仍不行 ⇒ 车机热点客户端读取失败，改用 DBPlay 自建热点）。
  - 非 Ecarx 版直接说"不能作为给 iPhone 的 Wi-Fi 通道，请核对地址"。
- 既有日志前缀 `manual hotspot precheck blocked: host=` **保留**，只追加 `gate=$endpointVerdict`
  —— 排查工具/既有断言若按前缀匹配不会失效。

### 验证

- `:car:core-bridge` 单测 **862 tests / 0 failures**（841 → 862，新增 21 例）；
  `:car:ui` **18 tests / 0 failures**。
- 新增 `CarManualEndpointGateTest`（14 例）：空地址 OK、有 verified route 永不拦、
  blank verified 不当成已验证、地址同时在非有线口不拦、
  **日志 4 现场判 `WIRED_ONLY([eth0, eth0.11, eth0.2])`**、NOWHERE、
  DOWN 视作不存在、`usb` 前缀、大小写、trim、排序、三个判定两两不相等。
- 新增 `ManualHotspotExclusionSplitTest`（7 例，静态读源码、**先剥注释**）：
  通用表不含有线口、GUESS 表含、`isUsableInterface` 用 GUESS、
  `findInterfaceCarryingAddress` 不用 GUESS、严格路径无前缀排除、两侧前缀一致。
- §117 非空转已核：把 `"eth"` 放回 `EXCLUDED_INTERFACE_PREFIXES`、
  把闸门判据改成恒不拦 ⇒ **8 例当场变红**（`CarManualEndpointGateTest` 7 例 +
  `ManualHotspotExclusionSplitTest` 1 例）；改回后全绿。
- 全链路 `compileDebugKotlin`（`:car:app` / `:car:steering` / `:car:platform-geely` 等）BUILD SUCCESSFUL。
- ⚠️ 静态读源码的 helper 曾用 `substringBefore("}")` 取函数体，被 `.none { … }` 的花括号切断
  ⇒ 断言永远不成立（假红）。已改为平衡花括号的 `functionBody()`。

### 刻意未做 / 遗留

- 方向 1（把 SoftAP 客户端视图不可信、`Wifi6ApImpl` ClassNotFound 等一并治理）**未做**：
  它要动上游读取客户端的那条链，超出"两条判据同源即可提前说"的范围。
- 闸门让失败从"60 秒后一句英文"变成"秒级一句中文"，但**没有**让这条路变得能连上。
  若实车上 iPhone 挂在原厂热点、地址在 `eth0.11` 上且 SDK 读不到客户端（日志 4 正是如此），
  仍会走 WIRED_ONLY 文案提示改用自建热点。真正要能复用，得让 `getWifiApClients() 可用
  （`Wifi6ApImpl` ClassNotFound 待查）或让上游接受用户填的地址本身。

---

## PATCH-0105 · 修「改完设置必须点一次重连，其他按钮才能用」：窗口标志单通道 + 输入探针 + 过程态留出口

**日期**：2026-10-12　**状态**：✅ 已应用（**未实机验证**）

**车主口径**（verbatim）：

> 我在调试界面交互时发现一个问题：当在设置发生改动后，必须直接执行重连操作，
> 其他按钮才能被正常点击或触发。

### 现场证据（三层都"正常"，但触摸没进进程）

模拟器 21:12–21:24（`io.dushu.car`，pid 4297）：

| 观测项 | 实测 |
|---|---|
| 主线程栈（66 份，21:12–21:15） | 全程 `MessageQueue.nativePollOnce`（空闲）⇒ **不是主线程阻塞** |
| `dumpsys gfxinfo` 累计帧数 | 9595 连续 40s 不变，21:24:09 一次跳 +33 ⇒ 期间连按压水波纹都没画 |
| 日志 | 无 ANR、无 `Input dispatching timed out`、App 侧只剩每分钟一条心跳 |
| `dumpsys window windows` | 本进程只有一个窗口，`mHasSurface=true`、`isVisible=true`、被 focus、flags 无 `NOT_TOUCHABLE` |
| 恢复点 | 21:24:07 `requestAudioFocus()`（= 一次会话启动）⇒ 帧数随即恢复 ⇒ **与"必须重连"吻合** |

同一时刻的 `dumpsys window` 里留着 IM E 事务的铁证：

```
mControl=InsetsSourceControl type=ITYPE_IME mLeash=… animation-leash of insets_animation
mIsLeashReadyForDispatching=true mImeShowing=false mHasSurface=false mImeHeight=0
```

**IME 自己已经没了（无 surface / 不显示 / 高度 0），insets 动画事务却还攥着 leash**
⇒ 事务永远走不完 ⇒ 窗口收不到触摸。

车机（CS11，21:14–21:19 诊断包）侧同样只有输入法抖动：成片的
`InputMethodManager: onViewDetachedFromWindow`、
`Ignoring onPreWindowFocus(). mCurRootView=DecorView@168d591[MainActivity]`，
以及 21:18:43 一次 `addView … fl=DIM_BEHIND ty=APPLICATION wrapxwrap gr=CENTER`
（Compose `Dialog`，560×485）—— 设置页"改设置 ⇒ 自动保存 ⇒ 整页重组 + 输入法重启"
这条抖动链是两台设备共有的现场。

### 根因

`MainActivity.applySystemBars()` **同时驱动两套系统栏 API**：
`WindowInsetsControllerCompat.hide()/show()`（现代通道，API 30+ 是**带 leash 的动画**）
**再**裸写 `decorView.systemUiVisibility`。而设置页有文本框、改设置又触发 PATCH-0103 的
自动保存与整页重组 ⇒ "输入法正忙 + 我们又把两条栏的标志重写一遍"，两套通道互相打架，
insets 事务可能永远走不完 ⇒ **窗口收不到触摸**（表现就是"按钮点不动"）。
会话（重）建立会带来一次窗口重排/重建，于是"点一次重连就恢复"。

另有一条同源的**结构性漏洞**：状态机在过程态只给 `STOP`
（`CarUiStateMapper.project` 的 `else ->`），而主页只渲染 `RECONNECT` / `OPEN_SETTINGS`
⇒ **过程态主页一个可点按钮都没有**，用户要出来只能等、重启、或去点一次重连
—— 与车主描述逐字吻合。

### 改（四处代码 + 两处契约）

1. `MainActivity.applySystemBars`（`:car:app`）：
   - **API 30+ 不再裸写 `systemUiVisibility`**，只走 Compat 通道；裸写兜底只留给
     **API ≤ Q**（CS11/Android 9 = API 28 能力一点没少，`FullscreenInsetContractTest` 仍绿）；
   - **输入法事务在途时不写任何标志位**，整轮 `decorView.post` 顺延一帧，
     守卫 `systemBarsDeferredOnce` 只允许顺延一次（避免"输入法一直开着 ⇒ 永远不应用"）。
2. **输入健康探针**（`:car:app` + `:car:ui`）：`MainActivity.dispatchTouchEvent` 在
   `ACTION_DOWN` 打「窗口层收到按下」并起 800ms 计时；`CarPlayApp` 新增
   `onUiTouch` 回调（由根节点 `Modifier.touchHealthProbe`，**`PointerEventPass.Initial` 只读、
   不 `consume`**）打「UI 层收到按下」。窗口层收到、UI 层没收到 ⇒ 打 `W` 并
   顺手 `requestApplyInsets()`（猜测性自愈：不改任何标志位）。下次复现不用再抓线程栈。
3. `HomeScreen`（`:car:ui`）：过程态把状态机**本来就给出**的 `STOP` 摆成次要按钮
   「停止连接」⇒ "整页没有出口"在结构上不可能再出现。
4. `SettingsScreen`（`:car:ui`）：草稿不再用 `remember(model.settings)` 做 key
   —— 旧写法会让**每次自动保存成功都把草稿状态丢掉重建**（整页重组 + 输入框重新要焦点
   + 输入法 `startInput` 抖动）。改为"已保存值被本页之外改了才跟随"；
   本页自己的提交是等值写入，不会触发重组。

### 验证

- `:car:core-bridge` 单测 **866 / 0**（862 → 866），`:car:ui` **21 / 0**（18 → 21）。
  新增 `SystemBarApplyDisciplineContractTest`(4) + `HomeWorkingExitContractTest`(3)。
- §117 非空转已核：① 去掉 API 闸门、② 去掉顺延分支的 `return`、
  ③ 主页不摆「停止连接」 ⇒ **三条对应断言当场变红**（2+1 failed），改回全绿。
- 全链路 `compileDebugKotlin`（十模块）+ `:car:app:compileDebugKotlin --rerun-tasks` **BUILD SUCCESSFUL**。
- ⚠️ **踩坑（新）**：静态读源码的契约测试读的是 `:car:app` 的源码，**改那边不会让
  `:car:core-bridge:testDebugUnitTest` 失效**（Gradle 只按本模块输入判 UP-TO-DATE）
  ⇒ 验证这类契约必须 `--rerun-tasks`，否则会看到假绿。

### 刻意未做 / 遗留

- **没有自动重连**：车主 PATCH-0103 定案是"自动保存，重连仍手点"。我也确认过
  "会话没在投屏时自动落地"这条路不可取 —— 会命中"用户自己按了停止"的状态，
  等于擅自把用户停掉的会话又拉起来（`stopRequestedByUser` 就是为区分这件事存在的）。
- **相位超时看门狗未做**：`WAITING_FOR_DEVICE`（等 iPhone 接入）本来就允许等几分钟，
  给它加超时会误判。这次只把"没有出口"补齐，不做会误伤的时间判据。
- 真实根因（insets 事务为何卡住）**未在框架层证实**：本 patch 只把"我们自己两条通道打架 +
  输入法事务在途时重写标志位"这条**可控的成因**消掉，并留下能把责任层判死的探针。
  实机复现时若日志里"窗口层/UI 层两行都没有"，那就是系统/输入法侧的事务问题，
  需要按探针的 `W` 行继续往下查。
## PATCH-0108 · 无线 attach 超时的中文失败原文不再被兜底吃掉（CarErrorText 放行）

### 背景

`:shared` 的 `CarPlayController.armWirelessAttachWatchdog` 在「蓝牙 CSM 已通、
iPhone 始终不来连 AirPlay」重试耗尽后 `fail()` 的是一句**中文**（含 SSID 与
「忽略此网络」指引，PATCH-0009 加）。但这句要经 `CarPlayStatus.Failed` →
`CarPlayStatusMapper.detailOf` 时再过一次 `CarErrorText.of` —— 表里只收上游
英文文案，中文必落 `FALLBACK` ⇒ 失败副标题只剩「连接未能建立」，
那句最有用的指引被吃掉。

### 核对过显示路径（动手前）

全仓 grep「无线 CarPlay 未连上」只有一个生产者（watchdog 的 `fail`）。
`fail` → `onStatus(CarPlayStatus.Failed)` → `CarPlaySessionBridge.onStatus`
（`errorMessage` 与 `detailOf` 两处都过 `CarErrorText.of`）；另一条
`debugLog("wireless attach timed out: …")` 走界面日志区、**不过** CarErrorText，
不受影响。`CarHotspotUnavailableException` 那条 catch 本来就刻意绕开 CarErrorText，
也不受影响。

### 改动

- `CarErrorText.PREFIXES` 加一条放行：`无线 CarPlay 未连上：` →
  `无线 CarPlay 未连上：{0}`（模板原样放行，参数 = 前缀之后的全部内容）。
- `CarErrorTextTest` 补一条放行测试：整条中文原文往返不变。

### 验证

- `:car:core-bridge:testDebugUnitTest` 全绿（含新增用例）。

## PATCH-0109 · 复用车机热点：拒绝把自建池地址通告给 iPhone（闸门新增 SELF_POOL）

### 背景（衔接 PATCH-0086/0102/0104）

「自建池地址排除」此前已覆盖三处：`CarHotspotApInterface.identify`（识别车机
热点网口）、`CarHotspotEndpointCandidates.of`（设置页候选列表）、
`CarHotspotResidue.detectWithinSubnets`（残留检测）。**唯独缺**
`CarManualEndpointGate`（连接前秒级闸门）——用户在「本机 CarPlay 地址」里填
一个自建池地址（如真车 08:57 的残留 `192.168.176.1`，挂在 `wlan0` 上）时：
`wlan0` 非有线 ⇒ 闸门放行 ⇒ 把必然连不上的地址通告给 iPhone ⇒ 40 秒
attach 超时。这正是真车 2026-10-11 08:57 那次失败链的复现入口。

「默认组合」半边此前已齐：默认 SSID 由 `CarManualHotspotMatch.reconcile`
连接时自动校正为车机存储配置名，默认密码（12345678）有中文警告
（`CarPlaySessionBridge` 复用分支）。本次只补地址半边。

### 改动

- `CarManualEndpointGate.evaluate` 新增判据：目标地址
  `CarHotspotApInterface.isSelfPoolOnly`（落在我方候选池 `SUBNET_CANDIDATES`
  且不落在车机固定网段 `192.168.15.0/24`）⇒ 新判定
  `CarManualEndpointVerdict.SELF_POOL`，放在有线判定**之前**（自建残留挂在
  哪张卡上都不改变"它不是车机热点"，这条文案更直指下一步）。判据与识别侧
  同源 ⇒ 设置页候选列表（已排池地址）永远不会推荐一个这里又拦下的值。
  Ecarx 已验证 route 仍**永不拦**（最强证据优先，与 WIRED_ONLY 同纪律）。
- `CarPlaySessionBridge` 拦下分支补 SELF_POOL 中文文案（指出是自建残留、
  给两条出路：切自建再切回复用清残留 / 到设置点选候选地址）。
- `CarManualEndpointGateTest` +6 用例（真车现场、池全段、优先于有线判定、
  verified route 豁免、车机网段直判不误杀、池外地址不受影响），
  互不相等用例纳入 SELF_POOL。

### 验证

- `:car:core-bridge:testDebugUnitTest` 全绿。

## PATCH-0110 · 设置页一键填入车机真实 SSID/密码

### 背景

「复用车机热点」要手填车机热点的 SSID/密码，而这两个值用户在本 App 里看不到、
只能默写——真车日志 4 填 `Lynk&Co`、真名 `Lynk & Co`（空格之差）⇒ 通告一张
没人广播的网，iPhone 静默连不上。探测链路本来就读得到这两个值
（`CarNativeHotspotInfo`），让"填对"变成点一下。

### 改动（四模块同 PATCH-0102 的链路形状）

- `:car:api` 新增 `CarNativeHotspotCredentials(ssid, passphrase)` +
  `of(info)` 纯判据：探测不到 / 没读到名 ⇒ 不给（凭缺失下结论）；
  密码读不到（厂商置空 / 开放网络 / 空白串）⇒ `passphrase = null`
  （界面据此只填名称、**不动密码框**）。SSID 取**存储配置名**（上游
  `ManualHotspotManager` 校验与通告的正是它），不是 `runningSsid`。
- `CarHotspotBriefing` 加 `nativeCredentials`，只在 **MANUAL（复用）模式**
  下计算——自建模式下那两个输入框命名的是我们自己的网，填入车机凭据反而错。
- `CarAppController.refreshModel` → `CarUiModel.nativeHotspotCredentials`
  （来源 = 那一份简报，与候选/警告同源）。
- `SettingsScreen`：「热点密码」下方加可点行「填入车机热点「X」」，右侧值列
  区分「名称 + 密码」/「仅名称（密码读不到）」；点击经自动保存落盘
  （PATCH-0103 机制），密码 null 时保留用户已填值。
- 测试：`:car:core-bridge` 新增 `CarNativeHotspotCredentialsTest`（6 例行为）
  + `NativeHotspotCredentialsWiringContractTest`（4 例四模块接线，§117 非空转：
  断任一环对应断言即红）。

### 验证

- `:car:core-bridge` **896 / 0**（含新增 10 例）、`:car:ui` **21 / 0**；
  全链路 `compileDebugKotlin`（十模块）BUILD SUCCESSFUL。未出包（铁律 1）。

## PATCH-0111 · HUD 导航推送：中文路名 + 内容去重防闪烁（移植 dbplay v40 现场补丁）

### 背景

HUD 卡片有两个缺陷，车主是**在车机侧用 smali 打补丁**修掉的——工作目录
`C:\Users\Santali\Desktop\dbplay`，版本标记 `v40-road-20261013`，**已装机验证通过**，
现场说明见该目录的 `v40-移植文档/README.md`。而源码仓库停在"未修"状态：
从源码打出来的包，仪表仍然显示英文占位符 `Next Road`，且卡片一闪一闪。
本补丁把那套现场改动**回写进源码**，让源码能直接产出同样的行为。

（本次不改上游版本号、不出包——铁律 1。）

### 缺陷 1：路名恒显示英文 `Next Road`

实测（百度 / 高德 / Apple 三个导航 App 表现一致）：iOS 的 `0x5201`
（RouteGuidanceUpdate）里 `0x0003 CURRENT_ROAD_NAME` **全部为 NULL**。原版把这个恒
null 的值透传给仪表盘，车机收到 null 就显示英文占位符。

真正的中文路名在 `0x5202`（ManeuverUpdate）的 `0x0002 DESCRIPTION` 里
（"八一西路" / "安仁大道" / "进入环岛"），而 xcertplay 的 `mergeManeuver()`
**早就把它解析进 `CarNavigationState.maneuverDescription`** ⇒ 数据层无需任何改动，
缺的只是"把它接到 HUD 上"这一步。

### 缺陷 2：卡片闪烁

抓一次完整会话的推送时序：**59 个相邻间隔里 21 个 < 3 秒，最小 0.0 秒**
（同一毫秒推两次），且穿插"切源过渡空帧"（`routeMeters` 偶发 null，
`13:27:07.262 null米 → 13:27:07.443 622000米`）。原版每收到一帧就推一帧
⇒ 车机被反复清空重绘。

修法：**距离与剩余秒数都没变 ⇒ 这一帧对用户无意义，直接丢弃。**
**不加时间节流**——现场试过 800ms 节流，两次因寄存器状态崩溃，已弃用；
内容去重已经足够（静止时不推、行驶时距离每秒都在变，本来就该频繁更新）。

### 改动

**`:car:core-bridge` — `CarPlayNavigationHudClient`**

- 抽出纯逻辑（不含 Android 类型 ⇒ 可 JVM 单测，与 `GeelyHudTurnMapping` 同思路）：
  - `HudPushDeduper`：内容去重判据 + `reset()`；
  - `CarNavigationState.hudCurrentRoad()` / `hudNextRoad()`：路名/第二行映射。
- `onState()` 在 `hasContent` 之后、`toGuidance()` 之前接 `deduper.accept(...)`。
- `toGuidance()` 改用 `hudCurrentRoad()`（`maneuverDescription ?: currentRoad`）
  与 `hudNextRoad()`（`destination ?: nextRoad`）——原字段全部保留作 fallback。
- 判据**逐条复刻现场补丁**：上次值 `null ⇒ 0` 参与比较（`null` 与 `0` 等价）；
  **本次值为 `null` 时一律放行**（空帧因此仍会推一次）。
- `connect()` 复位去重基线 —— **对现场补丁唯一的刻意偏离**：补丁把
  `lastMeters/lastSeconds` 一直留着，于是「导航结束（已发 `update(null)` 清空车机
  提示）→ 重新导航」时，若新会话首帧数值与上一会话末帧相同，首帧会被当成
  "没变化"丢掉 ⇒ 车机提示一直空着。复位只影响每个会话的第一帧，方向是"该推的一定推"。

**`:car:platform-geely` — `GeelyHudBridge`**

- **补上"绑定卡死"的出口**：`onServiceDisconnected` 原来只复位 `bound`，而
  `bindIfNeeded()` 的幂等判据是 `binding || bound` ⇒ 绑定请求发出但服务从未连上时
  `binding` 停在 true，此后永远不再补绑。现同时复位 `binding`，并补
  `onBindingDied` / `onNullBinding`（现场日志证明该服务会走**静默绑定失败**：
  `bindService()` 返回 true 后一直不回调 `onServiceConnected`，只在最后回调
  `onBindingDied`）。
- 组件包名 `com.autonavi.amapauto` → **`com.baidu.naviauto`**。⚠️ 上游 KDoc 原本
  写明"这是原版的写法，照抄，不要顺手修正"，本次按"与原版（车机已验证的补丁）一致
  优先"跟随现场补丁；车机日志 `已连上 HUD 桥服务：ComponentInfo{com.baidu.naviauto/
  com.baidu.naviauto.hudbridge.CarPlayHudBridgeService}` 证明改后可连。
  代码里已标注**回退入口**（换非吉利/非百度车机连不上时第一个回退这里）。
- 清车道事务码 **3 → 12**（现场补丁实测值）。
- `writeLegacy()` 字段布局按现场补丁逐行对齐：帧首多一个常量 `1`、`routeSeconds`
  **被写两次**、转向码改用 `maneuverType` 现算（形参 `turnKind` 因此不再使用，
  两者取值等价——`dispatch()` 已用 `resolvableTurnKind()` 过滤过"不支持转向"的帧）。
  三处"看着像错、但车机就是按这个布局解析"的地方都在代码里逐条注明。

**`:shared` — `Iap2IdentificationClient`**（上游文件，已在 PATCH-0039 动过）

- IdentificationInformation 末尾**追加 RGD 组件宣告**（86 字节 = `0x56` 裸参数：
  id `0x1E`、componentId `0x2A`(42)、名 `"RouteGuidanceDisplayComponent"`）。
  只在 `MESSAGES_RECEIVED_FROM_PHONE` 里声明 `0x5201/0x5202/0x5204` **不够**——
  iPhone 只有在识别帧里看到组件本体才会真正下发导航数据。现场日志链：
  `RGD 0x1E appended (componentId=0x2a)` → `identify: ACCEPTED by iPhone`
  → `iAP2 frame msgId=20993/20994` 开始下发。字节表逐位照抄，位置错一位车机就静默不认。

### 明确**未**移植（有意，附原因）

| 项 | 原因 |
| --- | --- |
| `HudDebug`（`/sdcard` 落盘 + 上报 `dbplay-hud-log.app.workbuddy.host`） | 纯现场诊断脚手架，不属于"界面布局/视觉样式/交互逻辑/显示的数据内容"。且它在现场补丁里被 `:shared` 的类调用，而 `:shared` 是**最底层模块**、不能反向依赖 `com.dbtool.carplay.geely.*` ⇒ 要移植必须先改成走 `:shared` 既有的 `debugLog` 钩子，不能照搬 |
| BIND 探针、`logTransact`、raw TLV dump（`RGD-RAW-`） | 纯日志，无行为 |
| `CarPlayController.resendRgdSubscription()` + `rgdFrameCounter` | 在 v40 APK 里**定义了但从未被调用**（全库仅 1 处命中 = 方法定义本身）⇒ 移植与否行为完全相同，属死代码 |

### 验证

- 全链路 `:car:app:compileDebugKotlin` BUILD SUCCESSFUL（十模块）。
- 新增 `CarHudGuidancePushTest`（14 例）：去重 7 例（首帧推 / 同内容丢 /
  距离变推 / 秒数变推 / 本次 null 放行 ×2 / `null ≡ 0` 哨兵语义 / `reset` 后重推）
  + 路名映射 7 例（描述优先 / fallback / 目的地优先 / 全空给 null）。
- 新增 `HudGuidanceWiringContractTest`（4 例，静态读源码接线契约，§117 非空转）：
  去重与映射真的被调用、`deduper.reset()` 在位、`onBindingDied`/`onNullBinding` 在位、
  包名/事务码已跟随现场补丁且旧值已消失、RGD 追加函数与字节表关键位（`0x03=0x1E`、
  `0x09=0x2A`、组件名与写入偏移）在位。
- `:car:core-bridge` **919 / 0**、`:car:platform-geely` **51 / 0**、`:car:ui` **21 / 0**。
- **未出包**（铁律 1）；版本号未动。

---

## PATCH-0112 · HUD 实车验证后的完善：发帧留痕 + 诊断包能看见车机侧 + RGD 字节级契约

### 背景

PATCH-0111 移植的 HUD 方案**已在实车上验证可用**（2026-10-13，车主确认：仪表能出转向
卡片、路名是中文、卡片不再闪烁）。

因此本补丁**不改任何行为**，只补三件"下次出问题时能断案"的东西 —— 前几轮排查 HUD 时
反复卡在同一个点上：**能看到的日志不足以区分几种完全不同的故障**
（桥没绑上 / iPhone 没下发 / 去重全丢 / 车机拒收），而它们的表现**完全一样**。

（本次不改上游版本号、不出包——铁律 1。）

### 改动

**`:car:platform-geely` — `GeelyHudBridge`**

- `dispatch()` 补**成功路径**留痕。过去只有 `!accepted` 才打日志 ⇒ 实车说"HUD 能用"时
  诊断包里 `GeelyHudAdapter` **一行都没有**，无法区分"推成功了"与"整条链路没跑"。
  现在成功/失败都有单行：`HUD 已受理本帧（request=… generation=… 距离/秒数
  转向语义(kind) 路名 目的地）`，摘要抽成 `summarize()`。
  - 量级可控：上游 `CarPlayNavigationHudClient` 已按内容去重 ⇒ 行驶中约每秒一行、
    静止零行。
  - 🔴 车机侧是裸 Parcel + `transact`、**不给我们任何回执** ⇒ "车机到底被告知了什么"
    **只能**在发送端记。
- 类 KDoc 增「实车状态」一节：把"事务码 / 组件包名 / `writeLegacy()` 字段布局都属
  **已实证**、不要凭'看起来不对'去改、唯一回退入口是 `PACKAGE`"写在代码旁边。

**`:car:core-bridge` — `CarDiagnosticPackager`**

- `KEEP_TAGS` 显式加 `GeelyHudAdapter`（HUD 发帧的唯一出口）。它本来也能靠关键字 `hud`
  命中（整行小写后含 `geelyhudadapter`），但那是**巧合** ⇒ 显式写明，谁精简白名单都滤不掉它。
- **新增 `logcat_system_filtered.txt`**：同一张白名单，切的是 **adb 通道那份全系统日志**。
  - 起因：`logcat_filtered.txt` 抓的是 `--pid=<本进程>` ⇒ **只可能**有我们自己的行。
    车机侧（百度 hudbridge 服务、WMS、SurfaceFlinger）说了什么，包里**一行都没有**
    ⇒ "HUD 连不上 / 卡片不出"只能靠上车的次数去猜 —— 而装车一次很贵。
  - **不额外跑命令**：复用刚取到的 `logcat_system.txt` 文本在内存里过滤
    （它已按 `LOGCAT_MAX_LINES` 截断 ⇒ 过滤结果天然有界）。拿不到 adb 通道时整段跳过，
    清单里那条 `systemLogcatLines: 0` 已如实说明原因。
- ⚠️ **订正 PATCH-0072 的一处归因**（只改注释，不改行为）：当时把"`WifiP2p|p2p|softap`
  在 `logcat_system.txt` 里命中数 0"归因于"被白名单滤掉了"，**站不住** ——
  那个文件是**原样落盘、从不过滤**的；真正原因是那 8000 行尾巴里没轮到它们。
  加了上面这一刀之后，那几条 framework 标签才真正开始起作用。

**`:car:core-bridge` — `CarPlayNavigationHudClient`（仅注释）**

- 把**去重键的已知取舍**写进 KDoc：键只有 `(距离, 剩余秒数)`，**不含转向与路名**
  ⇒ 若手机在"全程剩余距离与剩余时间都没变"的瞬间换了转向提示（理论场景：停车等灯时
  手机细化转向），这一帧会被丢弃，车机箭头保持旧值，直到距离变化才更新。
  **刻意不改**：键越宽 ⇒ 推得越多 ⇒ "防闪烁"这一**已验证**行为就有回退风险。
  万一现场真的复现"箭头卡住"，改法是把 `maneuverType` 并入键。
- 类 KDoc 增「实车状态」一节（同 `GeelyHudBridge`）。

### 新增 / 扩充测试

| 测试 | 变化 |
| --- | --- |
| `shared/…/transport/Iap2RgdComponentDeclarationTest` | **新增 5 例**：RGD 86 字节的**结构 / 语义 / 黄金向量**三层断言 |
| `car/core-bridge/…/HudGuidanceWiringContractTest` | 4 → **6 例**：新增"成功发帧必须留痕"、"诊断包白名单必须留住 HUD 留痕" |
| `car/core-bridge/…/navigation/CarHudGuidancePushTest` | 14 → **15 例**：新增"已知取舍——去重键只有距离与秒数" |

**为什么 RGD 要单独做字节级契约**：PATCH-0111 那个静态读源码的断言只能证明"代码里有
`bytes[0x03] = 0x1e` 这几个字面量"，**证明不了产出的字节对不对**。而它是整条链路的
**咽喉** —— 错一位 ⇒ 桥绑得上但**永远收不到任何帧**，表现与"车机没装 hudbridge 服务"
**一模一样**，日志里看不出区别。

黄金向量取自现场补丁的生成器 `gen_rgd.py`（`tlv(0x001E, inner)`）**独立重算**，
所以能抓到"有人顺手重排 / 美化字节表"。顺带**逐位复核**了生产代码的字节表：
56 个非零位与生成器输出**完全一致**（`0x0E..0x2A` = 29 字符组件名，`0x2B` 留 `0` 作 NUL）。

### 验证

- 全链路 `:car:app:compileDebugKotlin` BUILD SUCCESSFUL（十模块）。
- `:shared` **244 / 0**、`:car:core-bridge` **922 / 0**、`:car:platform-geely` **51 / 0**。
- 新增用例无告警（用例名不含 `"`，避开 Windows 上的 `Name contains character(s)…` 警告）。
- **未出包**（铁律 1）；`car/app/version.properties` 未动。

---

## PATCH-0113 · 彻底移除「P2P 模式」（Wi-Fi Direct 临时组网）：枚举值 + 实现 + 诊断 + 界面入口 + 测试

**日期**：2026-10-13　**分支**：`dev`　**状态**：✅ APPLIED（编译 + 单测全绿；**未出包**）

### 背景（车主需求 + 边界裁定）

车主需求（verbatim）：

> 从代码库中彻底移除 P2P 模式，包括删除相关的功能模块、配置项、依赖引用、接口调用及界面入口，
> 并清理对应的类型定义、注释和文档说明。确保移除后不残留对 P2P 模式的引用，且其余功能
> （如客户端/服务器模式）可正常运行，同时更新受影响的测试用例以验证改动。

侦察阶段发现 **"P2P" 在这个代码库里是三个不同的东西**（见 `REF-pitfalls.md` §135），
若不区分就按 `grep -i p2p` 的字面结果删，会同时**破坏 iAP2 协议层**与**无线 CarPlay 链路**。
两个冲突点报给车主后，车主裁定：

| 问题 | 车主裁定 | 含义 |
| --- | --- | --- |
| 这次"移除 P2P 模式"的边界划到哪一层？ | **「热点模式叫 WiFi Direct」** | 只删 ③（`CarHotspotMode.P2P_DIRECT`）；① 假阳性不碰、② 上游无线链路**原样保留** |
| 删掉 `P2P_DIRECT` 之后 `hotspotMode` 的出厂默认改成哪个？ | **「MANUAL 复用车机热点」** | 默认从 `P2P_DIRECT` 改回 `MANUAL` |

**三层结构（动手前必须分清）**：

| 层 | 是什么 | 落点 | 本次处置 |
| --- | --- | --- | --- |
| ① 假阳性 | `Iap2Parameter` 里的子串 `ap2P` | `shared/.../iap2/**`（5 个文件） | **一个字符都不动**（iAP2 协议层） |
| ② 上游无线链路 | `WifiP2pGroupManager` / `LegacyWifiP2pGroupManager`(PATCH-0064) / `LegacyWifiP2pRecovery` | `shared/.../network/**` | **保留**（车主边界；删了无线 CarPlay 报废） |
| ③ 真正的"P2P 模式" | `CarHotspotMode.P2P_DIRECT`（Wi-Fi Direct 临时组网） | `car/**` | **整体移除** |

⚠️ `CarPlayConfigBuilder` 里 `P2P_DIRECT -> WirelessHotspotMode.WIFI_P2P_LEGACY` 是**唯一**产生
`WIFI_P2P_LEGACY` 的地方 ⇒ 删 ③ 之后 ② 变成**无调用方的死代码**，但它是**上游文件**，按车主边界**不删**。

### 改动

| # | 文件 | 改了什么 |
| --- | --- | --- |
| 1 | `car/api/…/settings/CarSessionSettings.kt` | 删 `P2P_DIRECT` 枚举值及其 KDoc；`hotspotMode` 出厂默认 `P2P_DIRECT` → **`MANUAL`**；枚举头与 `hotspotMode` 的 KDoc 重写（"历次默认值"表新增 2026-10-13 一行 + 撤档理由 + 存量迁移说明） |
| 2 | `car/api/…/hotspot/CarAdbHotspotContract.kt` | 删 `LOCAL_P2P_UDHCPD_CONF`（唯一消费者 `CarRootP2pManager` 已删） |
| 3 | `car/api/…/hotspot/`（**删 3 文件**） | `CarRootP2pCommands.kt` / `CarRootP2pContract.kt`（含 `interface CarRootP2pHost`）/ `CarRootP2pOutput.kt` |
| 4 | `car/core-bridge/…/hotspot/`（**删 6 文件**） | `CarRootP2pManager.kt` / `CarRootP2pSta.kt` / `CarRootP2pTool.kt` / `CarVendorP2pGroup.kt` / `CarVendorP2pChannelPlan.kt` / `CarWifiP2pStateReader.kt` |
| 5 | `car/core-bridge/…/CarPlaySessionBridge.kt` | 删构造参数 `rootP2p` / `vendorP2p`；删 `ensureP2pGroup` / `rewriteToManualPlan` / `hostLabel`；删 `stop()` / `close()` 里的 P2P 收尾块；删 `VENDOR_P2P_TIMEOUT_MILLIS`；清理相关注释 |
| 6 | `car/core-bridge/…/CarAppServices.kt` | 删 `rootP2pManager` / `vendorP2pGroup` 两个单例与 `rootP2pProbe` 接线；清理 `XCExt.p2p` 日志与注释 |
| 7 | `car/core-bridge/…/hotspot/CarHotspotModeInit.kt` | 删 `rootP2p` 参数与整块 `p2pSwept` 逻辑（离开 P2P 模式先清残留）；`Result.note` 不再拼接 P2P 那一段 |
| 8 | `car/core-bridge/…/network/CarHotspotResolver.kt` | 删 `explicit()` 里的 `P2P_DIRECT` 分支、`p2pDirect()`、`p2pGroupPlan()`、`hotspotModeLabel` 的 `P2P_DIRECT` 档 |
| 9 | `car/core-bridge/…/CarPlayConfigBuilder.kt` | `toUpstream()` 删 `P2P_DIRECT -> WIFI_P2P_LEGACY` 分支；`manualHotspotPassphrase` 的 `P2P_DIRECT` 特判简化为直接 `passphraseFor(hotspot)` |
| 10 | `car/core-bridge/…/hotspot/CarHotspotFootprintStore.kt` | 类注释的"四种模式"表删 `P2P_DIRECT` 行 → "三种模式" |
| 11 | `car/core-bridge/…/SharedPreferencesCarSettingsStore.kt` | 新增一次性迁移 `migrateRemovedP2pHotspotMode`（标记 `hotspot_p2p_mode_migrated_v1`、判据**裸字面量** `"P2P_DIRECT"`）+ `load()` 里注册 |
| 12 | `car/core-bridge/…/developer/CarDiagnosticPackager.kt` | **外科式摘除** Wi-Fi Direct 专项探针：`rootP2pProbe` 参数、`ROOT_P2P_PROBES`、`FEATURE_WIFI_DIRECT`、`WifiP2pManager` 可用、`ip link show p2p0`、`init.svc.cleanup_p2p`、`WPA_SUPPLICANT_CONFIG_CANDIDATES` + `grep -i p2p` 块、`WifiP2p`/`wifip2p` 日志白名单；`collectWifiDirect`→`collectWirelessSnapshot`、`wifiDirectInfo`→`wirelessInfo`、输出 `wifi_direct.txt`→`wireless_snapshot.txt`、清单行 `wifiDirect:`→`wireless:` |
| 13 | `car/core-bridge/…/developer/CarDeveloperMode.kt` | 删 `rootP2pProbe` 参数与透传 |
| 14 | `car/ui/…/screens/SettingsScreen.kt` | 删设置页的 P2P 专属卡片与 `p2pDirect` 判定；`hotspotModeLabel` 删 `P2P_DIRECT -> "Wi-Fi Direct"` 档 |

**测试侧**：

| # | 文件 | 改了什么 |
| --- | --- | --- |
| 15 | `car/core-bridge/src/test/…/hotspot/`（**删 6 文件，共 77 例**） | `CarRootP2pCommandsTest`(13) / `CarRootP2pContractTest`(22) / `CarRootP2pOutputTest`(23) / `CarRootP2pPlanTest`(7) / `CarVendorP2pChannelPlanTest`(9) / `CarWifiP2pStateReaderTest`(3) |
| 16 | `car/core-bridge/src/test/…/CarHotspotP2pModeTest.kt`（**删，3 例**） | 3 例全是 P2P 专属 |
| 17 | `car/core-bridge/src/test/…/hotspot/CarHotspotModeIsolationContractTest.kt` | KDoc 表格收窄到三种模式；`P2P 与局域网各自单独成支` → `局域网单独成支`（删 P2P 断言） |
| 18 | `car/core-bridge/src/test/…/hotspot/CarManualHotspotAddressTest.kt` | 删 `P2P 组主翻译成复用形状时不带用户地址`（`p2pGroupPlan` 已删）；清理对已删测试的引用 |
| 19 | `car/core-bridge/src/test/…/developer/CarDiagnosticSettingsDumpTest.kt` | 出厂默认断言 `hotspotMode: P2P_DIRECT` → `hotspotMode: MANUAL` |
| 20 | `car/core-bridge/src/test/…/SettingsDefaultMigrationTest.kt`（**+4 例**） | 新增：出厂默认是 `MANUAL` / `P2P_DIRECT` 已不在枚举里 / 迁移在 `load()` 里注册 / 迁移用独立标记且判据是裸字符串 |

**文档与「保留物」的注释收尾**（同一轮追加；`grep` 全仓扫过一遍后逐条判断"是残留还是保留物/历史记录"）：

| # | 文件 | 改了什么 |
| --- | --- | --- |
| 21 | `DBPlay-UI-DESIGN.md` §8.2.2 | "热点模式现在有五种" → **四种**；删 `P2P_DIRECT` 的 UI 叙述与"专属卡"描述；加 PATCH-0113 注 |
| 22 | `DBPlay-UI-DESIGN.md` §8.12 | "枚举常量名不上界面"条目旁补注：所举两档（`LOCAL_ONLY_HOTSPOT` / `Wi-Fi P2P`）**现都已不在枚举里**（分别 2026-10-03 / 2026-10-13 移除），规矩仍适用 |
| 23 | `common/…/CarPlayHostActivity.kt`（**上游文件**，PATCH-0106 已改） | `WIFI_P2P_LEGACY` 分支的注释加 PATCH-0113 注：`P2P_DIRECT` 移除后这一支**再无产生者**，但**必须留着**（穷尽式 `when`，删了 `:common` 编译不过） |
| 24 | `shared/…/network/LegacyWifiP2pGroupManager.kt`（**上游文件**，PATCH-0064 已改） | KDoc 末尾加注：文中引用的 `P2P_DIRECT` 现场属**历史证据**，本类按车主边界原样保留 |
| 25 | `shared/…/network/LegacyWifiP2pRecovery.kt`（**上游文件**，PATCH-0064 已改）+ 其单测 `shared/src/test/…/LegacyWifiP2pRecoveryTest.kt` | 同上（测试钉的是**保留下来的**判据，照旧跑） |
| 26 | `投屏修复与API防线-20261010.md`（**日期报告**） | 文件头加 banner：本文 §3.9 描述的 Wi-Fi Direct 临时组网一档已整体移除，复现步骤与代码路径不再存在；**正文一个字不改** |

**有意不动**（查证后判定"**不是** P2P 模式的残留"）：

| 文件 / 位置 | 内容 | 为什么不动 |
| --- | --- | --- |
| `README.md` L88 / `README.zh-CN.md` L75 | "Android 9 上 Wi-Fi P2P 5 GHz 不可用，改用 LocalOnlyHotspot" | **上游 README 原文**（HEAD `3753867` 的上游提交，二开从未改过），描述的是**上游 App 自身**的能力约束，不是二开层那一档模式；改它会与上游文档分叉 |
| `ARCHITECTURE.md` L143 | `:shared` `network/` 模块清单"Wi-Fi P2P / LocalOnlyHotspot / 手动热点" | 描述的是**上游 `:shared`** 的模块内容（三者都还在），不是 `CarHotspotMode` 的可选档 |
| `UPSTREAM.md` L77/L93、`ADB-HOTSPOT.md` L79–80/L902–910 | 上游 1.3.x 给 `WIFI_P2P` 加固定凭据 + 我们**早先（2026-09-29）**就删掉 `WIFI_P2P` 的记录 | 说的是**另一档枚举值** `WIFI_P2P`（2026-09-29 删），与本次删的 `P2P_DIRECT` 不是同一个；记录仍准确 |
| `ADB-HOTSPOT.md` L877/L1504、`REF-debug.md`、`REF-pitfalls.md` | `p2p0` / `p2p*` 网卡名与评分表 | **设备事实**：本机 Wi-Fi 网卡之一就叫 `p2p0`（诊断包引文），不是我们的模式 |
| `docs/DiPlay-*.md`（4 处） | DiPlay 上游的 `WIFI_P2P` 分析 | 分析的是**别家仓库**（DiPlay），与 DBPlay 的模式无关 |

### 有意**保留**（附理由，车主边界内的东西）

| 保留项 | 理由 |
| --- | --- |
| `shared/.../iap2/**` 全部文件 | ① 假阳性（`Iap2Parameter` 的 `ap2P`）—— iAP2 协议层，与 Wi-Fi Direct 无关 |
| `shared/.../network/WifiP2pGroupManager.kt` / `LegacyWifiP2pGroupManager.kt` / `LegacyWifiP2pRecovery.kt` | ② 上游无线 CarPlay 链路（车主边界：保留）。后两者的 KDoc 里引用了 `P2P_DIRECT` 作为**历史现场**，已按上表 #24/#25 **加注**（不改写证据） |
| `WirelessHotspotMode.WIFI_P2P_LEGACY`（PATCH-0064 加在上游枚举里的值）+ `CarPlayController` 的对应分支 + `CarErrorText` 的对应条目 | 删 ③ 后**无产生者**（唯一映射点已删）⇒ 死代码。但它是**上游枚举上的二开新增值**，按车主边界**不回收**；且 `:common` 那条 `when` 必须保持穷尽（见上表 #23） |
| `car/platform-geely/.../lynk/LynkWifiP2pGroup.kt` / `LynkWirelessWatchdog.kt` | DiPlay(Lynk) 反编译移植的**无线直连**实现，注释明说"留给 Android 10+ 的车机"；`grep` 确认**零外部消费者** ⇒ 不属 `P2P_DIRECT` 热点模式，按边界保留（**仅报告，未动**） |
| `CarErrorText.kt` 的全部 P2P 条目 + `CarErrorTextTest` | 查证后确认那些字面量的**产生者全在保留的 `:shared`**（`LegacyWifiP2pGroupManager` / `WifiP2pGroupManager` / `LegacyWifiP2pRecovery`）。本文件是"上游错误串 → 中文"的**翻译字典**，与文件内既有的 `LocalOnlyHotspot*` 条目同一条既定政策（**留翻译比删掉稳妥**：删了用户会看到英文原文）。⇒ 整体不动 |
| `KEEP_TAGS`/`KEEP_KEYWORDS_LOWER` 里的 `SoftAp`/`WifiApState`/`wificond`/`softap`/`tether`/`wpa_supplicant` 等 | 车机热点这条链路（保留模式）要用的 framework 日志标签；只摘掉 `WifiP2p` / `wifip2p` |
| 三处 `p2p0` 字面量 | **设备事实、不是我们的模式**：`CarPlaySessionBridge` 的"全量接口清单里只有 wlan0/p2p0 两张 Wi-Fi 网卡"（诊断包引文）、`CarIwDevOutputTest` 用 `p2p0` 验"P2P 网卡不被当成热点"的解析健壮性、`REF-pitfalls.md` 的网卡候选表 |

### 存量安装的迁移（关键）

老安装的 `hotspot_mode` 持久化值若是 `"P2P_DIRECT"`，`enumOf` 的 `runCatching{enumValueOf}.getOrDefault`
只会**静默兜底**成默认值 —— 不会崩，但 prefs 与默认值长期不一致、界面高亮错档。所以显式迁移：

```kotlin
private fun migrateRemovedP2pHotspotMode(d: CarSessionSettings) {
    if (prefs.getBoolean(KEY_P2P_MODE_MIGRATED, false)) return
    val editor = prefs.edit()
    if (prefs.getString(KEY_HOTSPOT_MODE, null) == REMOVED_P2P_MODE_NAME) {
        editor.putString(KEY_HOTSPOT_MODE, d.hotspotMode.name)
    }
    editor.putBoolean(KEY_P2P_MODE_MIGRATED, true).apply()
}
```

⚠️ 判据 `REMOVED_P2P_MODE_NAME = "P2P_DIRECT"` **必须是裸字面量** —— 枚举值已删，
写 `CarHotspotMode.P2P_DIRECT.name` 会编译不过；且迁移**必须带独立标记**
（同 `migrateAutoHotspotModeToSelfBuilt` / `migrateTransportToWireless` 的坑：共用标记会让后跑的那条静默失效）。

### 验证

- 全链路 `:car:app:compileDebugKotlin` **BUILD SUCCESSFUL**（11 个模块），无新增告警。
- `--rerun-tasks` 全量单测：`:shared` **244 / 0**、`:car:core-bridge` **847 / 0**、`:car:ui` **21 / 0**、
  `:car:platform-geely` **51 / 0**。
  - `:car:core-bridge` 用例数 922 → 847：删 7 个测试文件共 **80 例**、删 2 例、新增 **4 例**，
    与"当前源码 `@Test` 总数 = 847 = 实跑数"**逐一对上**（另有 PATCH-0108~0112 未提交的新增用例）。
- 残留扫描（**全仓**，不只 `car/`）：`grep -rn -E "P2P_DIRECT|CarRootP2p|CarVendorP2p|CarWifiP2pStateReader|WIFI_P2P_LEGACY|Wi-Fi Direct 临时组网" --exclude-dir=agent-memory --exclude-dir=.git .`
  ⇒ 命中**全部**归入三类，无一例外：
  ① **有意保留物**（上游无线链路、PATCH-0064 加在上游枚举里的 `WIFI_P2P_LEGACY`、设备事实 `p2p0`）；
  ② **历史记录**（日期报告、`PATCHES.md` 旧条目、上游 `README`）；
  ③ 本轮新写的**加注/迁移判据/契约断言**。
  `car/` 下只剩**注释**与**迁移判据字面量**。
  （唯一噪声：`apk/DBPlay-1.0.54-187-202610111826.apk` —— 旧出包产物里的字符串，与源码无关。）
- 收尾后复验（本轮只动注释与 `.md`，无功能改动）：`:shared:compileDebugKotlin`、`:common:compileDebugKotlin`
  与 `:car:app:compileDebugKotlin` 全链路均 **BUILD SUCCESSFUL**。
- **未出包**（铁律 1）；`car/app/version.properties` 未动。

---

## PATCH-0114 · HUD 导航推送补设置项：状态回显 + 总开关 + 排障计数（挂在「仪表与HUD」页）

**日期**：2026-10-13　**分支**：`dev`　**状态**：✅ APPLIED（编译 + 单测全绿；**未出包**）

### 背景（车主提问 → 排查 → 裁定）

车主提问（verbatim）：

> 为什么HUD在设置里面没有显示

排查结论：**HUD 从来就没有设置项，设计如此。** PATCH-0111/0112 完善的那条
「HUD 导航转向提示桥」是**纯自动接线**——会话起来就无条件连、iPhone 下发就推，
从头到尾**零设置项、零界面**。车主在设置里找不到它，不是漏做，是它本来就不在那儿。

（顺带澄清一个易混点：车主记忆里的「HUD 设置页」其实是**另一件事**——
曾经的「仪表自绘导航」，2026-10-09 被 `7e4c2ff`「整体移除仪表导航（自绘）能力」
删掉（28 文件 / −5300 行）。PATCH-0096~0101 把 `ClusterSection` 重建了回来，
但自绘叠层没有恢复。）

车主随后裁定（verbatim）：

> 我都意思是在此版本的仪表与HUD加入，不用以前的文档

经选项确认要加的内容为：**「状态 + 开关 + 排障信息」**。

（本次不改上游版本号、不出包——铁律 1。）

### 为什么这三样（对应三种"看不到 HUD"的故障）

前几轮排查 HUD 反复卡在同一点：**几种完全不同的故障，表现一模一样**——
仪表上什么都不出。而它们只能靠**能看见的东西**区分：

| 故障 | 过去怎么判断 | 本补丁给出的判据 |
| --- | --- | --- |
| 本车机没有 HUD 桥 | 没法判断 | 「HUD 通道」行：`有 / 无` |
| 桥没绑上那个服务（**常态且完全静默**） | 只能上车抓日志 | 「桥连接」行 + 「最近一次失败」行 |
| iPhone 没下发 / 全被去重丢掉 / 车机拒收 | 三者表现相同、无法区分 | 「推送统计」行：`收 X · 去重 Y · 受理 Z` |

三个数字的**差值**就是断点位置：`收=0` ⇒ 手机没发（没在导航）；`收>0 且 去重≈收` ⇒
内容没变化（静止）；`收>0 且 受理=0` ⇒ 车机拒收（**我们没问题、车机有问题**）。

### 改动

**`:car:api`（契约层，零第三方依赖）**

| 文件 | 改动 |
| --- | --- |
| `navigation/CarHudBridge.kt` | 新增 `status(): CarHudStatus = CarHudStatus()` 默认方法 + **新增 `CarHudStatus`** data class（9 字段：`supported/available/target/pushing/lastFailure/lastPush/pushedFrames/receivedFrames/droppedFrames`） |
| `session/CarSessionHost.kt` | 新增 `hudStatus(): CarHudStatus = CarHudStatus()` 默认方法 |
| `settings/CarSessionSettings.kt` | 新增 `hudGuidanceEnabled: Boolean = true`（**默认开**） |
| `settings/CarSettingsReconnectPolicy.kt` | `hudGuidanceEnabled` 进 `LIVE_FIELDS`（**就地生效**）+ `copy(...)` 抹平 |
| `developer/CarDiagnosticSettingsDump.kt` | 白名单加 `hudGuidanceEnabled` 一行 |

`status()` 的三条硬约束写在契约 KDoc 里：**只读**（不得触发绑定/重连/发包）、
**便宜且不阻塞**（渲染路径上调用 ⇒ 只读内存字段，不许 binder 调用或 I/O）、**不抛异常**。

**`:car:core-bridge`（实现层）**

| 文件 | 改动 |
| --- | --- |
| `navigation/CarPlayNavigationHudClient.kt` | 新增 `enabled`/`receivedFrames`/`droppedFrames` 三个 `@Volatile` 字段 + `setEnabled()` + `status()`；`onState()` **首行加闸门**（含 `state == null` 那一支）+ 收帧/丢帧计数 |
| `CarPlaySessionBridge.kt` | 覆写 `hudStatus()`（合成桥侧 + 上层侧）；`start()` 与 `updateSettings()` **两处**都调 `setEnabled(...)` |
| `SharedPreferencesCarSettingsStore.kt` | `load()` / `save()` + `KEY_HUD_GUIDANCE_ENABLED = "hud_guidance_enabled"` |

**`:car:platform-geely`（平台层）**

| 文件 | 改动 |
| --- | --- |
| `GeelyHudBridge.kt` | 新增 `lastFailure`/`lastPush`/`pushedFrames` 三个 `@Volatile` 字段 + `status()`；四条 binder 回调、`bindIfNeeded()` 两条失败路径、`transact()` 的 catch 全部写 `lastFailure`；`dispatch()` 成功分支写 `lastPush` + 计数。**另修掉一个既有潜在 bug（见下）** |

**`:car:app` / `:car:ui`（投影 + 界面）**

| 文件 | 改动 |
| --- | --- |
| `CarAppController.kt` | `refreshModel()` 里 `hud = session.hudStatus()` |
| `CarUiContract.kt` | `CarUiModel` 加 `val hud: CarHudStatus = CarHudStatus()` |
| `screens/SettingsScreen.kt` | 新增 `ClusterHudGroup`，挂在 `ClusterSection` **最后一块**；KDoc「本页管五件事」→**六件**、决策顺序表 6 行 → **7 行** |

### 四个关键取舍（改之前先读这里）

**1. 开关做成"闸门"，不是"断开"。** `hudGuidanceEnabled = false` 只**停推送**，
**不** `disconnect()`、**不** `bridge.stop()`。理由：`stop()` 会把桥推进"已停止"态
（`GeelyHudBridge.closed`），而平台实例是 `by lazy`、**进程内唯一**
（`GeelyCarPlatform.hud`）⇒ "关一次"会变成"本进程内**永久**失效"——**开关关不回来**。
代价是多持一个外部服务绑定；取舍与 `reportMediaCardToCar` 同形（"开关只决定推不推"）。
关掉时**主动补发一次 `update(null)`** 清掉车机上已画出来的卡片（否则"开关关了、卡片还在"）。

**2. 必须接两处：`start()` 和 `updateSettings()`。** 只放 `start()` 不够——
`CarAppController` 的 `reconnect()` **不经过** `start()` 的接线段 ⇒ "关掉 → 点重连生效"
照样没用。`start()` 那处也不能省：会话起来时用户可能早就关着它。

**3. 状态合成成一个对象，不拆两个。** 桥侧 6 项（住平台层）+ 上层侧 3 项（住会话内
`CarPlayNavigationHudClient`），合成的**唯一**位置是 `CarPlaySessionBridge.hudStatus()`，
由客户端 `status()` 用 `copy()` 合并。拆成两个对象会让界面自己拼、迟早一边漏更新。

**4. 顺手修掉一个既有潜在 bug。** `GeelyHudBridge.stop()` 用
`closed.compareAndSet(false, true)` 置位，而 `start()` 第一行原是 `if (closed.get()) return`
—— **永不复位**。而 `CarPlaySessionBridge` 每次会话 stop 都调
`hudClient.disconnect()` → `bridge.stop()`，平台实例又是进程内唯一 ⇒
**"断开 → 再连接"之后 HUD 在本进程内永久失效，且无任何报错**。
修法：`start()` 改 `closed.set(false)`，并把 `onAvailability` 赋值挪进
`mainHandler.post { ... }`（因为 `stop()` 也往同一个 handler 排了"清回调"任务，
同步赋值会被它清掉）。**这也是取舍 1 的直接动因** —— 不修它，开关就是关不回来。

### 界面：`ClusterHudGroup`（本页最后一块）

- 位置：`ClusterSection` 末尾，与分区标题「仪表与**HUD**」的语序一致。
- 内容：`SectionHeader("HUD 导航推送")` + 一张 `CarCard`：
  「推送导航转向提示」开关（`enabled = hud.supported`）+「HUD 通道」+「桥连接」+
  「推送统计」+ 条件渲染的「最近一次失败」。
- **全部只读回显 + 一个开关**；前置条件不满足（本车机无 HUD 通道）时开关**置灰不隐藏**
  （项目纪律），并给出"为什么"。
- 所有 `description` 控制在 `CarSettingRow` 的 `maxLines = 3` 以内
  （§9 坑 7：**超了会被硬切且不补省略号**）。
- 顺带清理本分区里 PATCH-0096 双列时代的**残留 KDoc**（`左列 · / 右列 ·` 共 5 处）——
  PATCH-0097 已把本页改回**单列**，留着会与本文档自相矛盾。纯注释、零行为改动。

### 新增 / 扩充测试

| 测试 | 变化 |
| --- | --- |
| `car/core-bridge/…/HudSettingsContractTest` | **新增 15 例**（静态读源码钉住全部接线点：契约形状 / 持久化 load-save-新键 / 诊断白名单 / 重连策略 LIVE_FIELDS + copy / 客户端闸门-计数-status 合并 / 会话桥两处接闸门 / 吉利桥 status + `closed.set(false)` / 投影层 / UI 契约 / 设置页） |
| `car/core-bridge/…/CarSettingsReconnectPolicyTest` | **10 → 11**：新增「HUD 导航推送开关就地生效」；`assertEquals(10, names.size)` → `11` |
| `car/core-bridge/…/developer/CarDiagnosticSettingsDumpTest` | **新增 1 例**：「HUD 导航推送开关在包里且默认为开」 |
| `car/core-bridge/…/HudGuidanceWiringContractTest` | **断言形状修正**（见下） |

⚠️ `HudGuidanceWiringContractTest` 有一条断言钉的是 `onState()` 里去重那一支的
**源码原文形状**（`if (!deduper.accept(...)) return`）。本补丁要给丢帧计数，那一支变成
带花括号的块 ⇒ 断言跟着改形状，但**判据一字未动**，并**补一条顺序断言**
（`deduper.accept` 必须排在 `toGuidance()` 之前）。这是"静态源码契约测试"的固有脆弱点：
它钉的是文本，任何形状变化都要同步改——但换来的是"接线被悄悄拆掉"能被立刻抓住。

### 验证

- 全链路编译 `:car:api` / `:car:platform-geely` / `:car:core-bridge` / `:car:ui` /
  `:car:app` 的 `compileDebugKotlin` → **BUILD SUCCESSFUL**（无新增告警）。
- `testDebugUnitTest`：`:car:core-bridge` **864 / 0**、`:car:platform-geely` **51 / 0**、
  `:car:ui` **21 / 0**。
  - `:car:core-bridge` 用例数 847 → 864：新增 **17 例**（`HudSettingsContractTest` 15 +
    `CarSettingsReconnectPolicyTest` 1 + `CarDiagnosticSettingsDumpTest` 1），
    与"源码 `@Test` 增量"**逐一对上**。
- UI 违规自查：`SettingsScreen.kt` 无字面色 `Color(0x…)`、无裸 `dp/sp`。
- **未出包**（铁律 1）；`car/app/version.properties` 未动（仍是 `1.1.2 / 195`）。

### 与相邻 patch 的关系

- **PATCH-0111/0112 的延续**：那两轮把 HUD 通道做**通**并补了**日志侧**可观测性；
  本补丁把同样的可观测性搬到**用户能看见的地方**（设置页），并给了用户一个**开关**。
  0112 的取舍（"不改行为、只补能断案的东西"）在这里被继承。
- **不碰 PATCH-0096~0101 的 `ClusterSection`**：只在末尾**追加**一块，
  原有六组的顺序、组件、生效方式全部不变。
- **不恢复** 2026-10-09 `7e4c2ff` 删掉的「仪表自绘导航」——车主明确"不用以前的文档"。

---

## PATCH-0115 · 「后台自动更新」默认改为**开启**（车主口径）

**日期**：2026-10-13　**分支**：`dev`　**状态**：✅ APPLIED（编译 + 单测全绿；**未出包**）

### 背景

车主口径（verbatim）：

> 设置-关于-后台-自动更新默认开启

即：「设置 → 关于」页那颗「后台自动更新」开关，默认值从 `false` 改为 `true`。

### 这条默认值原来为什么是 `false`（**顾虑没有消失**）

`CarUpdatePrefs.silentUpdateEnabled` 原来的 KDoc 写得很重：

> ⚠️ **默认 `false`**。这是唯一一个"能让 App 把自己装死"的功能：默认开 = 把整个车队的
> 可用性押在服务端发布纪律上（推一个坏包，所有车机会反复"装失败 → 重启 → 又装"，
> 用户连设置页都进不去关开关）。

本次**没有推翻**这个判断，只是认定它已被下面**三重保险**接住：

| # | 保险 | 在哪 |
| --- | --- | --- |
| 1 | **服务端那把锁**：`/api/app/check_update` 的 `auto_update` 必须也为真 | `CarUpdateManager.silentAllowed(...)` 的判据链 |
| 2 | **熔断**：同一版本静默失败 `SILENT_MAX_ATTEMPTS`(=2) 次 ⇒ 该版本**永久**不再自动装 | `CarSilentUpdatePolicy.silentAttemptLimitReached` / `silentBlocked` |
| 3 | **用户随时能关**：「关于」页那颗开关，关掉即写盘 | `CarUpdateManager.setSilentUpdateEnabled` |

⚠️ **保险 2 有个已知的洞**：它靠"下一次启动跑 `settlePendingInstall`"推进计数。若坏包在
**启动早期就崩**，计数可能推不动 —— 那时**只剩保险 1**。所以「**服务端默认别开
`auto_update`、要灰度**」这条纪律**仍然成立**（见 `REF-dbapi.md` 的 auto_update 一节）。

### 改动（3 个文件 + 1 个新测试）

| 文件 | 改动 |
| --- | --- |
| `:car:core-bridge` `update/CarUpdatePrefs.kt` | `getBoolean(KEY_SILENT_ENABLED, false)` → **`true`**；KDoc 重写（记录车主口径 / 三重保险 / 生效范围 / 风险） |
| `:car:api` `update/CarUpdateContract.kt` | `CarUpdateState.silentUpdateEnabled` 默认 `false` → **`true`**（界面初始快照，两处必须一致，否则界面先闪"关"再变"开"） |
| `:car:ui` `screens/AboutScreen.kt` | 注释「⚠️ 默认**关**」→ 记录新口径与三重保险（**只改注释**） |
| `:car:core-bridge` `update/SilentUpdateDefaultOnContractTest` | **新增 5 例**（见下） |

### 生效范围（**重要**）

读的是 `getBoolean(KEY, true)` ⇒ prefs 里**没有这个键**时返回默认值：

| 安装 | 结果 |
| --- | --- |
| 新装 | **开** |
| 老安装、**从没碰过**这颗开关 | **也变成开**（这是"改默认值"的应有语义） |
| 老安装、曾经**显式关掉**过 | **保持关**（显式写入的 `false` 不会被默认值覆盖） |

**为什么不需要一次性迁移**：本项**不在** `CarSessionSettings` 里，没有"出厂默认值"要回写
（对照 `REF-rules.md` 的"改已有字段默认值必须配迁移"——那条针对的是 `CarSessionSettings`
的字段，因为它们有 `CarSettingsReconnectPolicy` / 诊断包 / 恢复默认值等一串下游）。
改 `getBoolean` 的默认参数就是全部。

### 新增测试：`SilentUpdateDefaultOnContractTest`（5 例）

静态读源码钉住——这个默认值有**三个错了完全看不出来**的失败模式：

1. **默认值只改了一半**（prefs 改了、界面初始快照没改）⇒ 界面闪错值；
2. **被改回 `false`**（它放宽了一个安全默认，将来"顺手改回去"不会有东西变红）；
3. **保险被拆掉**（默认开之后，唯一拦住"坏包反复装"的就是熔断与服务端那把锁）。

| 用例 | 钉什么 |
| --- | --- |
| `持久化默认必须是开` | `prefs.getBoolean(KEY_SILENT_ENABLED, true)` 在位、旧 `false` 已消失 |
| `界面初始快照默认必须与持久化一致` | `val silentUpdateEnabled: Boolean = true` 在位、旧 `false` 已消失 |
| `服务端那把锁必须在` | `silentAllowed` 的判据链里仍有 `serverAutoUpdate` 且仍与车主开关**相与** |
| `熔断必须在且上限不能放大` | `SILENT_MAX_ATTEMPTS: Int = 2` 与 `silentBlocked` 在位 |
| `关于页的开关仍然写回持久化` | 界面绑定 `model.update.silentUpdateEnabled` + 控制器 `setSilentUpdateEnabled` |

### 验证

- 全链路 `:car:app:compileDebugKotlin` → **BUILD SUCCESSFUL**。
- `testDebugUnitTest`：`:car:core-bridge` **869 / 0**（864 → 869 = 新增 **5 例**）、
  `:car:platform-geely` **51 / 0**、`:car:ui` **21 / 0**。
- **未出包**（铁律 1）；`car/app/version.properties` 未动（仍是 `1.1.2 / 195`）。

### 与相邻 patch 的关系

- **不碰** PATCH-0095 建立的静默更新链路本身（`CarSilentUpdatePolicy` / `CarUpdateManager`
  一行未改）——本补丁**只改一个默认值**及其论证。
- 与 **PATCH-0114** 同日、同分支，但**互不相干**（一个动 HUD 设置项，一个动更新默认值）。

## PATCH-0116 · 自建热点不再拿车机系统配置校验它；空 SSID 兜底成出厂名；复用连接窗口补两条可判定观测

> 依据：**日志 9**（`日志/9/1791897995606_b92cbf3f_67b3c24af6b5`，2026-10-13 21:21–21:26）。
> 本节每条结论都给出该包里的时间戳，可逐条回查。

### 现象（三件事，两条是"必然失败"）

| # | 现场 | 日志原文 / 时间戳 |
| --- | --- | --- |
| 1 | 复用一次之后**切回自建就连不上** | `hotspot plan: 自建热点「Lynk」：… 信道 36` → `wireless bring-up failed` → `java.io.IOException: Manual hotspot band 2.4 GHz does not match configured band 5 GHz`（21:24:22 / 21:24:26 / 21:24:30 各一次）；把名字改回 `DBPlay` 后同一条链**立刻成功**（21:25:28，`iface=wlan0 host=192.168.175.1 band=5 GHz`） |
| 2 | 热点名**清空**直接崩 | `hotspot plan: 自建热点「系统随机」` → `java.lang.IllegalArgumentException: manualHotspotSsid is required in manual hotspot mode`（21:24:54 / 21:25:01） |
| 3 | 复用失败**无法定性** | `wireless attach timed out: iPhone did not open the AirPlay connection within 40000ms`（21:23:32 / 21:24:13，各 40 秒）；同一份日志里"通告给 iPhone 的值"是 `advertise SSID="Lynk" passphraseLength=8 ip=198.18.34.15`（21:22:52），而**车机侧到底有没有客户端关联上来，日志里一个字都没有** |

### 根因（现象 1、2 都是"判据用错了前提"，不是缺兜底）

**现象 1**：上游 `ManualHotspotManager.start()` 的处置是
`readApConfiguration()?.takeIf { it.ssid == expectedSsid }` —— **名字一致 ⇒ 当成"真·复用"**，
于是拿车机系统里那份 `SoftApConfiguration`（信道/频段/加密）去校验本次这张网。

这个前提在自建路径上必碎：设置页的 SSID 是**复用与自建共用**的**一个**字段。
用户先填「Lynk」复用，再切回自建时名字还是「Lynk」⇒ 我们自己的 5 GHz AP 与车机那份
**同名**的 2.4 GHz 配置撞上 ⇒ 校验生效 ⇒ 必抛，刚起好的 hostapd 被就地拆掉。
（换名就对不上 ⇒ 校验被跳过 ⇒ 同一份日志里立刻成功。）

⇒ 能不能用系统那份配置，取决于本类这次是**接管别人的 AP**还是**给我们自己的 AP 做就绪判定**，
**与名字是否相同无关**。而这个事实只有二开侧知道：复用 / ADB 自建 / AUTO **三种模式在上游
都映射成 `WirelessHotspotMode.MANUAL`**（`CarPlayConfigBuilder` 的 `toUpstream()`），
枚举本身分不出来。

**现象 2**：`CarHotspotResolver.explicit()` 把空 SSID 原样留成 `null`，而计划文案把它显示成
「系统随机」。但**自建没有"让系统随机生成"这条路** —— 那张 AP 是我们用 hostapd 起的；
上游 `CarPlayRuntimeConfig` 对 `WIRELESS + MANUAL` 有硬 `require`，`null`/空白直接抛。
（⚠️ 本仓既有契约测试 `空 SSID 留 null 而不是空串` 的**前提"上游会用自己的默认值"**由此被推翻，
已连同证据订正，见"测试"一节。）

### 改动

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `shared/…/network/ManualHotspotManager.kt`（**上游文件**） | 新增构造参数 `selfHostedAccessPoint: Boolean = false`；为 `true` 时 **`apConfiguration` 直接取 `null`**（不读、不校验、也不借它的信道/频段/加密） |
| 2 | `shared/…/orchestration/CarPlayRuntimeConfig.kt`（**上游文件**） | 新增 `manualHotspotSelfHosted: Boolean = false` 并写清语义 |
| 3 | `shared/…/orchestration/CarPlayController.kt`（**上游文件**） | 构造 `ManualHotspotManager` 时透传该开关 |
| 4 | `car/core-bridge/…/CarPlayConfigBuilder.kt` | 按二开模式推导：`hotspot.mode != MANUAL && != SAME_LAN`（AUTO 在解析阶段已落到自建） |
| 5 | `car/core-bridge/…/network/CarHotspotResolver.kt` | `explicit()` 里**自建**且 SSID 空白 ⇒ 兜底成 `CarAdbHotspotDefaults.SSID`；复用分支**一字未动**（仍留 `null`） |
| 6 | `car/api/…/hotspot/CarManualHotspotMatch.kt` | 新增纯函数 `describeCarPassphraseRead()`：把"车机密码读到了什么"写成**三档可区分**的一句话（密码本身不入日志） |
| 7 | `car/core-bridge/…/CarPlaySessionBridge.kt` | 那句 `passphrase is DBPlay factory default (reuse mode)` 告警**旁边**补一行读数（现象 3 的前半）；复用分支启动只读的客户端观测器（后半） |
| 8 | `car/core-bridge/…/hotspot/CarHotspotClientWatcher.kt`（**新增**） | 复用连接窗口内**只读、有界**地采样车机侧客户端列表（5 秒 ×18 次上限），并在结束时把结论写成一行 |

### 为什么观测器是"决定性的"

`attach timed out` 底下藏着两种**完全不同**的故障，而应用侧日志原先只能证明
"iPhone 没连到我们的 7000 端口"，证不了它有没有关联上那张网络 —— 于是只能去问手机侧：

| 车机侧有没有客户端关联 | 说明 | 下一步查什么 |
| --- | --- | --- |
| **没有** | iPhone 压根没连上车机那张 AP | 热点名 / 密码 / 手机侧「忽略此网络」 |
| **有** | 关联成功，但到不了我们通告的 endpoint | endpoint 选址 / 路由 / AirPlay 回听 |

车机自己知道这个答案（Ecarx SDK 客户端列表，诊断包里的 `Ecarx clients=` 就是它），
我们只是**从来没在连接窗口里读过**。

⚠️ 三种结论分开写：`读到客户端` / `全程没有客户端` / **`读数全程不可用⇒判不了`**。
最后一种**绝不允许**被写成"没有客户端"（读不到 ≠ 否，本仓铁律）。

### 测试

- 新增 `CarSelfHostedHotspotContractTest`（6 例，源码级契约）：开关必须存在、自建时**不读**系统配置、
  RuntimeConfig 带着它且默认 `false`、Controller 透传、ConfigBuilder **按模式**推导（不许按 SSID 猜）、
  观测器只在复用分支启动、读数不可用时不许下"没有客户端"结论、采样必须有上限。
- `CarManualHotspotMatchTest` 新增 `车机密码读数三档可区分`（三档文案两两不同、不含明文）。
- **订正** `HotspotManualReuseContractTest` 的两条既有用例（都给了实测证据）：
  ① `空 SSID 留 null` → 改为"自建兜底成出厂名、复用仍留 `null`"；
  ② `热点名核对只挂在复用分支上` → 改为"截出复用分支的函数体再断言"，因为它现在**多了**
  观测器启动那一行（窄断言 `\{\s*reconcileReusedSsid\(` 会假失败）。

### 验证

- `:car:app:compileDebugKotlin`（含全部 `car/*` 模块）→ **BUILD SUCCESSFUL**。
- `testDebugUnitTest`：`:shared` **244 / 0**、`:car:core-bridge` **889 / 0**
  （`CarSelfHostedHotspotContractTest` 6 例 + `CarManualHotspotMatchTest` 1 例 = **新增 7 例**，
  变更前 882；另有 2 条既有用例被**改写**，条数未变）。
- **未出包**（铁律 1）；`car/app/version.properties` 未动（仍是 `1.1.3 / 196`）。
- ⚠️ **未实机验证**：现象 1、2 的修复要有真车才能证明"切回自建能连上"；
  观测器的价值也要等**下一次复用日志**才兑现。

### 与相邻 patch 的关系

- **不改** PATCH-0087 / 0099 的地址判据（`hostAddressOverride` 那条路一字未动），
  新开关与它同一条纪律：**"这次是哪种模式"由调用方声明，不在上游靠名字猜**。
- **不碰** PATCH-0113 的 P2P 移除、不碰 `LocalSoftApReader` 的读数口径。
- 现象 3 只**补观测**，**不改**复用行为：密码/名字/地址的替换与拦截判据全部保持原样。

## PATCH-0117 · 仪表投屏：补一个**点得到**的入口 + 补上三处"问不出来"的证据；HUD：把"桥为什么不可用"变成可判定

> 依据：**仪表投屏诊断报告**（`docs/DBPlay-仪表投屏失败诊断-20261013.md`，诊断包 `1791886957013_e3de67df72a0`）
> + 全部 `日志/` 里的触摸与 HUD 侧证据。本节改动**全部是"补入口 / 补证据"**，
> 不动任何已存在的判据（尤其不回退 PATCH-0100）。

### 现象

| # | 现场 | 证据 |
| --- | --- | --- |
| 1 | **仪表投屏"失败"** —— 开关都开着，仪表上什么都不出 | 诊断报告：副屏窗口**从未被创建**（`GeelyClusterPresenter.start()` 的 6 条落盘日志全包 0 命中；`switchNaviMode naviMode:3` = 0 次；`dumpsys/window.txt` 里 `mDisplayId=2` 上一个 `io.dushu.car` 窗口都没有）。而 iPhone 侧一切正常：`/info` 声明了 `type=111`，`screen stream type=111 active=true` 两次 |
| 2 | 建窗的唯一入口是**三指左滑**，而**它能不能被这台车的面板识别，日志回答不了** | 输入探针只记"按下"、不记触点数；手势类零日志；手势文案全走内存浮层（不落盘）⇒ 诊断包 0 命中 |
| 3 | HUD：一次会话刷 **30+ 条** `HUD 桥可用性：false`，而**成因不可判定** | 日志 8（32 次）/ 日志 9（21 次）；"车机没装那个服务" / "装了但没绑上" / "绑上了但从不回调" 三种成因在包里长得一模一样 |

### 根因（三条，都是"取不到证据"而不是"判据错了"）

1. **设置页没有任何"开始投屏"的入口**。这一页的两个开关（「仪表地图投屏」「三指手势投屏」）
   都只是**闸门**；真正的触发器只有三指左滑，而那个约定**只存在于文档里**。
   用户把开关打开 → 什么都没发生 → 看起来就是"投屏失败"。
   （`toggleProjectionUi()` 也帮不上：它的语义是"只换 iPhone 画不画、不动副屏窗口"，
   没有窗口时按下就是**无反应**。）
2. **手势那条链一句都不落盘**：`onClusterGesture` / `showClusterByGesture` 的文案全走
   `appendLog()`（内存浮层，`debugLogs: false` 时连浮层都没有）；
   `CarThreeFingerGesture` 本身零日志。
3. **多指这条前提没被观测过**：三指手势要求 `ACTION_POINTER_DOWN` 且触点数到 3，
   而现有探针只记"按下"。侧证全部指向"面板只上报单指"（车机自己的
   `[XCGestureService] ThreeFingerObserver` **170 条全是 `CSD pointer 0`**、无一条 pointer 1/2；
   `DoubleFingerObserver` 每次都打 `PointerCount=1`；全部日志里 `ACTION_POINTER_DOWN`
   与 `pointerCount>=2` **零命中**）—— 但那些都是**系统侧**读数、样本也小，**不足以定案**。

### 改动

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `car/ui/…/CarUiContract.kt` | 新增 `setClusterVisible(visible)`：与三指手势**同动作**（建窗+抢屏 / 拆窗+还屏），但**不受** `clusterGestureEnabled` 约束（那个开关管的是"手势要不要被受理"） |
| 2 | `car/ui/…/screens/SettingsScreen.kt` | 「投屏状态」卡补一个整宽按钮：**投到仪表 / 收起仪表投屏（还给车机）**；总闸关着时**置灰 + 一句为什么**（前置条件不满足要常驻 + 置灰） |
| 3 | `car/app/…/CarAppController.kt` | 新增 `clusterLog()`：**同时**写 `platform.diagnostics.log` 与浮层；三道闸门 + 两个结局全改走它；`showClusterByGesture` / `hideClusterByGesture` 加 `source` 参数（手势 = `三指左滑/右滑`，按钮 = `仪表投屏按钮`），两个入口复用同一对函数 |
| 4 | `car/app/…/MainActivity.kt` | 输入探针补 `pointerCount`；收到 `pointerCount >= 2` 时**每个触摸序列补一行**「多指观测」（带 actionMasked） |
| 5 | `car/platform-geely/…/GeelyHudBridge.kt` | ① 目标存在性探针（`getPackageInfo` + `resolveService`，**只算一次**）⇒ 直接回答"车机有没有那个服务"；② `bindService` 返回 false 这条路径**补留痕**（原先一个字都不打）；③ 可用性通知**去重**（同值不再重复推给上层） |
| 6 | `car/core-bridge/…/CarPlaySessionBridge.kt` | 可用性日志带上 `target` 与 `lastFailure`（三种成因从"一句话"变成"可区分"） |

### 为什么按钮放在「投屏状态」卡里

用户在这一页最先看的就是"现在投上没有"；把"投一下"放在同一张卡里，
与"为什么没投上"（下一行的 `state.summary`）是同一条视线。**不改**会话建立的行为：
仍然什么都不做，只有用户点按钮或划三指才建窗（PATCH-0100 的口径一字未动）。

### 明确**没做**的事

- **没有**恢复"会话建立就自动建窗 + 抢屏" —— 那是 PATCH-0100 按车主口径去掉的：
  自动抢屏会让"一连上仪表就变成全屏地图"，用户想用原厂仪表反而用不了。
- **没有**改三指手势的判据（仍要求恰好 3 指 + 左/右方向 + 阈值 + 占优倍数）。
  面板到底会不会上报多指，由第 4 条那行日志回答；**答完再决定**要不要给它一个降级通道。
- **没有**动 HUD 的推送协议（`transact` 字段布局 / 事务码 / 去重键）——
  PATCH-0112 已把那些钉成"已实证、别凭'看起来不对'去改"。

### 测试

- 新增 `CarClusterProjectionDiagnosabilityContractTest`（4 例，源码级契约）：
  落盘日志入口同时写两处、三道闸门与两个结局不许再只走浮层、输入探针必须记触点数并单独报多指、
  设置页必须有能点到投屏入口且两个入口复用同一对函数。
- `HudGuidanceWiringContractTest` 4 → **6 例**：新增"HUD 不可用时必须留下可判定的原因"
  （目标探针 / 绑定失败留痕 / 可用性去重）与"可用性日志必须带上目标与最近失败原因"。

### 验证

- `:car:app:compileDebugKotlin`（含全部 `car/*` 模块）→ **BUILD SUCCESSFUL**。
- `testDebugUnitTest`：`:shared` **244 / 0**、`:car:core-bridge` **895 / 0**（889 → 895 = +6）、
  `:car:platform-geely` **51 / 0**、`:car:ui` **21 / 0**。
- **未出包**（铁律 1）；`car/app/version.properties` 未动（仍 `1.1.3 / 196`）。
- ⚠️ **未实机验证**：按钮能不能真的把窗口建起来、以及那几行新证据的取值，
  都要等一次装车。尤其第 4 条（多指观测）是**唯一**能定案"面板上不上报多指"的东西。

### 与相邻 patch 的关系

- **不回退 PATCH-0100**（会话建立时不建窗）；只**增加**一个显式入口。
- 与 PATCH-0101 的手势独立开关**解耦**：按钮走 `setClusterVisible`，不经过那道闸门。
- 不改 PATCH-0111/0112 的 HUD 协议与去重键；PATCH-0114 的 `CarHudStatus.lastFailure`
  第一次**进了诊断包**（原先只有设置页能看到）。


---

## PATCH-0118 · 静默更新链路四处加固：坏包不再拿去装 + 连接不再泄漏 + HTTPS 不再降级 + 状态上报能判定

> 依据：**车主点名**「检查 DBPlay 静默更新功能的完整实现逻辑，排查当前是否能够正常使用……
> 找出可能导致更新失败的问题**并修复**」（2026-10-15）。
> 通读 `update/` 全部源码（`CarUpdateManager` / `CarApkInstaller` / `CarApkDownloader` /
> `CarUpdateApi` / `CarUpdatePrefs` / `CarSilentUpdatePolicy` / `CarInstallScript`）+ 界面与接线。

### 结论先行

**链路本身是通的**：检测（`check_update`）→ 下载 → 「平台静默 → ADB 静默 → 系统安装器」
三级串联完整；两把锁（车主开关 + 服务端 `auto_update`）+ 熔断（同版本 2 次失败）+
空闲窗口（≥30s）+ 共享 adb 通道（不与热点互踢）都在位，**不需要重写**。

但读代码时找出 **4 类"会让静默更新失败、而且不报错"的缺陷**。它们的共同点是
**失败现象离真正原因很远**（用户只看到"版本没变"），所以必须逐个堵掉。

### 现象 / 缺陷（4 类）

| # | 缺陷 | 后果 |
| --- | --- | --- |
| 1 | **本地缓存的 APK 从不校验身份**。`downloadedApkPath` 只记路径与版本号；文件被截断、被写坏、或服务端换过包（同名不同内容）都照样进"待安装" | 拿坏包去跑六级降级 ⇒ 白跑几分钟后失败，**还顺手烧掉一次熔断额度**（上限只有 2 次）⇒ 一个**本来能装好**的版本被永久熔断 |
| 2 | **装的可能不是 DBPlay**。服务端 `download_url` 配错时，会把别的 APK 交给安装器，或把结果记成 DBPlay 的更新 | 装错应用 / 更新账目错乱 |
| 3 | **下载连接泄漏**。`CarApkDownloader.download()` 在非 2xx 时提前 `return`，**不 `disconnect()`**；`CarUpdateApi.check()` 同样 | 连接池里堆着不释放的 socket；车机长期在线 ⇒ 累积 |
| 4 | **HTTPS → HTTP 降级被放行**。下载重定向只跟随 `Location`，不校验协议 | 从 TLS 跳到明文 ⇒ 中间人可换包（与"更新"这个动作的信任级别不匹配） |

附带：`reportStatus()` 原来**不读响应体、不判 `ok`**，"上报失败"永远查不出来。

### 改动

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `car/core-bridge/…/update/CarUpdateApkIdentity.kt`（**新增**） | 纯函数 `validateCarUpdateApkIdentity(actual, expectedPackageName, expectedVersionCode): String?` —— 包名解析不出 / 包名不匹配 / 版本号非法 / 版本号不匹配各给一条中文原因，全对返回 `null` |
| 2 | `car/core-bridge/…/update/CarApkInstaller.kt` | 新增 `validateUpdateApk(file, expectedVersionCode)`（`getPackageArchiveInfo` + 上面的纯函数）；`installWithoutAdb` / `installViaAdb` **签名补 `expectedVersionCode`**，内部先校验再动手 |
| 3 | `car/core-bridge/…/update/CarUpdateManager.kt` | ① `download()` 命中缓存**先校验**，失败 ⇒ 删包重下（不再直接跳过下载）；② `install()` 入口**先校验**（覆盖"ADB 失败 → 回退系统安装器"那条不校验的路）；③ `notifyIdleWindow()` 校验放在 `recordSilentAttempt()` **之前**（坏包不许烧熔断额度）；④ `restoreDownloaded()` 恢复前先校验（半截文件不再进 PENDING_INSTALL）；⑤ 三处调用点补 `versionCode` |
| 4 | `car/core-bridge/…/update/CarApkDownloader.kt` | 连接改用 `finally` 兜底 `disconnect()`；改名改 `Files.move(… ATOMIC_MOVE)`（不支持时退普通 move）；重定向遇 `isHttpsDowngrade` ⇒ 拒绝；`openOnce` 校验协议只允许 http/https；新增纯函数 `isHttpsDowngrade` |
| 5 | `car/core-bridge/…/update/CarUpdateApi.kt` | `reportStatus` 改返回 `StatusReportResult(success, message)`：读响应体、判 `ok`、`finally` 断开；`check` 也改 `finally` 断开，并对 `hasUpdate && latestVersionCode <= 0` 报错 |

### 为什么"校验"要重复四次（不是冗余）

四个调用点各自对应一种**不同的坏包来源**，缺一个就有一种漏网：

- `download()` 缓存命中 —— 文件被写坏 / 服务端换包；
- `restoreDownloaded()` —— 上次写到一半被杀留下的**半截文件**；
- `notifyIdleWindow()` —— 上面两条之后包又坏了（例如存储被清理）；
- `install()` 手动入口 —— 用户点按钮这条**独立路径**（且它是"ADB 失败 → 系统安装器"的入口）。

而"校验必须在 `recordSilentAttempt` **之前**"是这次最要紧的一条：
熔断额度只有 2 次，用它去记一次"包坏了"的失败，等于**用装包的次数去背包的错**。

### 明确**没做**的事

- **没有**改静默判据链（车主开关 + 服务端 `auto_update` + 有下载地址 + 未熔断）——
  `SilentUpdateDefaultOnContractTest` 钉着的那条链一字未动。
- **没有**改熔断上限 `SILENT_MAX_ATTEMPTS = 2`、空闲窗口 `30_000L`、安装通道次序。
- **没有**改服务端任何东西（`dbapi2.0` 未动）；也没动 `version.properties`。
- **没有**给 `reportStatus` 的失败加任何界面提示 —— 它是"顺带告诉后台"的记录，
  失败不该影响用户装不装得上（只是现在**查得到**了）。

### 测试

- 新增 `CarUpdateHardeningTest`（**18 例**，`car/core-bridge/src/test/…/update/`）：
  - 纯函数真值：APK 身份 5 例（全对 / 无包名 / 错包名 / 版本号非法 / 版本号不匹配）、
    HTTPS 降级 5 例（https→http 判降级 / https→https / http→http / http→https / 畸形地址不抛）；
  - 静态接线契约 8 例：缓存命中前必校验、三处校验点都在、静默校验在熔断计数之前、
    调用点必须带 `versionCode`、安装器两条入口内部自校验、下载器 `finally`+`ATOMIC_MOVE`+协议校验、
    状态上报返回结果且 `finally` 断开、熔断上限仍是 2。

### 验证

- `:car:core-bridge:testDebugUnitTest` → **BUILD SUCCESSFUL**，**937 例 / 0 失败**
  （其中新增 `CarUpdateHardeningTest` **18 例**、`SilentUpdateDefaultOnContractTest` 5 例）。
- `:car:app:compileDebugKotlin`（含全部 `car/*` 模块）→ **BUILD SUCCESSFUL**。
- `git diff --check` → 无空白错误。
- **未出包**（铁律 1）；`car/app/version.properties` 未动。
- ⚠️ **未实机验证**：以上全部是源码级 / 纯函数级验证。真正的判据——
  "坏包被删掉后能不能重新下到好包"、"静默安装这次到底成不成"——
  仍要等一次**装车**（`system/update_state.txt` 与 `/data/local/tmp/dbplay-install.log`）。

---

## PATCH-0122 · 车机重启后 adbd 回到厂商 5555：自建热点补一次"一次性自愈"（1.1.9 实车回归）

> 本次改动**全在 `car/`**（`car/core-bridge/…/bridge/hotspot/`），**不涉及上游文件**。
> 登记在这里是为了让"1.1.9 内置 ADB 改造"这条线的前因后果连成一条。

### 结论先行

1.1.9 报的"**自建热点无法正常使用**"不是网卡/hostapd 的问题，而是**热点还没开始建就结束了**：
车机重启后 `service.adb.tcp.port` 回到厂商 **5555**，而 1.1.9 的自建热点只固定连 **5556**
（`adb hotspot step 1 初始化策略｜固定连接 127.0.0.1:5556；本次不改端口、不扫描候选端口`）
⇒ 第 4 步 `Connection refused` ⇒ 整条链路在起 hostapd 之前失败。

修法**不是**回退成"每次扫描候选端口"（那正是 1.1.9 去掉的），而是：
固定口确实不可用、**且设备当前口确实不是 5556** 时，把用户点「永久开启 ADB」走的那条路
补跑**一次**（幂等；不扫描候选端口、不改用 5555）。

### 证据（两份诊断包，同一天两次开机，现象一致）

诊断包 `1792054941726`（用户报障那次，`appVersion 1.1.9 (202)`，`开机时长 1 分钟`）：

```
17:01:40.934 carplay-boot: 收到 BOOT_COMPLETED：… adbPort=瞬态=5555 持久=5556 adbd=running
17:01:43.594 XCExt.hotspot.mode: 切换后端口快照（只读，不补写）：瞬态=5555 持久=5556 adbd=running
17:01:55.290 XCExt.session: ADB hotspot failed: 第 4 步「检查专用 ADB 端口 5556」失败：
             5556 未返回 online 设备。connect: failed to connect to '127.0.0.1:5556': Connection refused；
             devices: List of devices attached emulator-5554	device
system_property.txt: [service.adb.tcp.port]=[5555]  [persist.adb.tcp.port]=[5556]  [init.svc.adbd]=[running]
```

三条要点，逐条都有实证：

1. **持久属性是写进去了的**（`persist.adb.tcp.port=5556`），**但这个 ROM 开机不回填它** ——
   两次开机（本包 17:01、另一包 `1792053705047` 的 16:33 开机快照）读到的都是
   `瞬态=5555 持久=5556`。⇒ 1.1.9 的设计前提（"单次初始化后重启仍可用"）在本车**不成立**。
2. **adbd 确实活着**（`adbd=running`），只是听 5555 —— `devices` 里的 `emulator-5554`
   就是 adb 对 `127.0.0.1:5555` 的命名，反过来证明活着的口是 5555 而不是 5556。
3. **失败点在热点流程的第 1 段**：`adb hotspot step 1/2/3 OK`、`step 4 FAIL`，
   后面 21 步（ifconfig / hostapd / udhcpd / 路由规则 / SSID 校验）一步都没跑到。

对照 1.1.8（`日志/1.1.18/1792037679717…`，12:13）：`adb hotspot step 1 OK 开通车机 ADB（端口 5556）：
瞬态口已是 5556；持久口已是 5556`，21 步全绿、`udhcpd: started` ——
那时**每次会话都补写瞬态口**，所以重启后也自愈。1.1.9 把它删掉后，补写只能靠用户手点。

### 改动（4 个文件，全在 `car/core-bridge/`）

| 文件 | 改动 |
| --- | --- |
| `hotspot/DeviceAdbEnabler.kt` | **新增只读** `currentPort()`（瞬态口；读不到返回**空串** = "未验证"，不猜 5555/5556） |
| `hotspot/AdbShellSession.kt` | **新增** `repairDedicatedPortOnce(): RepairOutcome` + `RepairOutcome`；`repairAttemptedThisProcess` 保证**每进程最多一次**；内部复用既有 `initializeDedicatedPort()` |
| `hotspot/CarAdbHotspotManager.kt` | `runBringUp()` 里通道不可用时**先补跑一次自愈再判失败**，并把这一步记进 steps（诊断包里看得到原因） |
| `bridge/DeviceAdbEnablerPlatformGateTest.kt` | 契约测试改写 + 新增 2 例（见下） |

判据/边界（刻意保守，写进 KDoc 与步骤文案）：

- 只在 **①固定口 5556 连不上、②设备当前口 ≠ 5556** 两条同时成立时才动 adbd：
  口本来就是 5556 却连不上 ⇒ 那是别的问题，自愈救不了，不白白重启 adbd；
- **每进程最多一次**：自愈会 `ctl.stop/ctl.start adbd`，会掐断此刻任何正在用 5555 的连接
  （厂商组件、现场工程师的笔记本）⇒ 失败后不反复重启，如实报错把决定权交回用户；
- **不扫描候选端口、不改用 5555**：`acquire()` 固定连 5556 这条口径**一字未动**，
  1.1.9 契约测试里"普通 acquire 不得调用端口扫描 `connect()`"的断言原样保留。

### 明确**没做**的事

- **没有**恢复"每次 acquire 都写系统属性"（1.1.8 的行为）：自愈只在**自建热点真正要建**时才发生；
- **没有**改 `CarHotspotMode` 五种形态、没有动 `MANUAL` 复用的地址校验与 SSID 校正；
- **没有**动 `version.properties`、**未出包**（铁律 1：出包必须由车主点名）；
- **没有**改上游任何文件。

> 附注：本包 17:01 那次会话里还有一条**与自建热点无关**的失败 ——
> 用户当时先选了 `MANUAL`（复用车机热点）但没填地址，`manual hotspot precheck blocked:
> state=ON corroborated=false`，被 `CarManualEndpointGate` 按设计拦下并提示填地址。
> 那是既有口径（PATCH-0087），不在本次改动范围内。

### 测试

- 改写 `DeviceAdbEnablerPlatformGateTest.只有用户显式初始化会操作 adbd` →
  `日常 acquire 不写属性也不扫描端口，自愈只由显式路径触发`（原断言全部保留）；
- **新增** `自愈复用初始化入口且不扫描候选端口`（源码级：自愈必读 `currentPort()`、
  必复用 `initializeDedicatedPort()`、不得出现 `runner.connect()`；
  且 `runBringUp()` 必须调用 `session.repairDedicatedPortOnce()`）；
- **新增** `读不到运行端口时返回空串而不是猜一个`（"未验证"不得伪装成 5555/5556）。

### 验证

- `:car:core-bridge:testDebugUnitTest` → **BUILD SUCCESSFUL**，**939 例 / 0 失败**；
- `:car:app:compileDebugKotlin`（含全部 `car/*` 模块）→ **BUILD SUCCESSFUL**；
- `:car:app:assembleDebug` 被**出包闸门**拦下（"只做验证不改版本号请用 compile/test 任务"）⇒
  按要求只做编译验证，未出包。
- ⚠️ **未实机验证**：真正的判据是"车机重启后再连一次自建热点能不能直接起来"，
  要等装车看 `adb hotspot step 1..N` 里是否出现
  `专用 ADB 端口 5556 一次性自愈` 这一步、以及它之后 hostapd/udhcpd 是否就绪。

---

## PATCH-0123 · 自建热点 ADB 端口：补写厂商持久属性 + 自愈改冷却窗口可重入（方案 A+F）

> 承接 PATCH-0122。车主口径："按 A（补写厂商属性）+ F（自愈可重入）执行"。

### 结论先行

PATCH-0122 的兜底能救"车机重启后 adbd 回 5555"这一次，但还有两个残余短板，本 patch 补上：

1. **治本（A）**：初始化时只写了 AOSP 的 `persist.adb.tcp.port`，而这台 Flyme 车的开机链路
   读的是厂商自己的 `persist.adb.tcpip`（开机快照里两条并存、adbd 却跟着后者走）。
   ⇒ 现在把 `persist.adb.tcpip` 也写成 5556 并读回，低成本、可回滚、不生效由自愈兜底。
2. **可重入（F）**：PATCH-0122 是"每进程最多修一次"；厂商组件会反复把 adbd 打回厂商口，
   修一次就永久放弃 ⇒ 本次进程内厂商再打回一次，自建热点又起不来。
   ⇒ 改为**冷却窗口可重入**：同窗口内不重复重启 adbd，窗口过后口又不对就再修。

### 改动（4 个文件，全在 `car/`）

| 文件 | 改动 |
| --- | --- |
| `api/…/hotspot/CarAdbHotspotContract.kt` | 新增常量 `ADB_VENDOR_TCPIP_PROPERTY = "persist.adb.tcpip"` 与 `ADB_REPAIR_COOLDOWN_MILLIS = 15_000L` |
| `core-bridge/…/hotspot/DeviceAdbEnabler.kt` | `initializeDedicatedPort()` 增写厂商持久属性并读回，detail 带"写前/写后"两值 |
| `core-bridge/…/hotspot/AdbShellSession.kt` | `repairDedicatedPortOnce` → `repairDedicatedPort`；一次性布尔换成 `lastRepairAttemptAtMillis` + 冷却窗口 |
| `core-bridge/…/hotspot/CarAdbHotspotManager.kt` | 调用点/步骤名/注释同步改名（步骤标题"专用 ADB 端口 5556 自愈"） |

### 明确**没做**的事

- 没动 `acquire()`（仍固定连 5556、不扫描候选端口、不改用 5555）；
- 没动五种热点形态、`MANUAL` 复用校验；
- 没动 `version.properties`、未出包（铁律 1）；未动上游文件。

### 测试

- `三条属性都要写`：钉死瞬态 + AOSP 持久 + 厂商持久三条都写（原"两条属性都要写"改名）；
- `自愈复用初始化入口且不扫描候选端口`：随方法改名同步；
- **新增** `自愈按冷却窗口可重入，而不是一进程一次`（源码级：引用冷却常量、契约含
  厂商属性与冷却常量、不得退回 `repairAttemptedThisProcess` 一次性布尔）。

### 验证

- `:car:core-bridge:testDebugUnitTest` → **BUILD SUCCESSFUL**，**940 例 / 0 失败**；
- `:car:app:compileDebugKotlin`（含全部 `car/*` 模块）→ **BUILD SUCCESSFUL**；
- `git diff --check` → 无空白错误；未出包。
- ⚠️ **未实机验证**：两个真正判据都要上车——① 重启后 adbd 是否直接落在 5556
  （验 A 是否治本）；② 运行中厂商把 adbd 打回后，下一次自建热点能否在冷却窗口过后再修回来（验 F）。

---

## PATCH-0124 · ADB 专用口看门狗：把"点连接才修"提前成"巡检时发现口不对就修"

> 承接 PATCH-0122/0123。车主问"是不是还可以加一个 ADB 看门狗"，按**窄职责 + 三道保险**落地。

### 结论先行

PATCH-0122/0123 的修复是**被动**的：只有会话启动、`ensure()` 发现 5556 连不上时才修。
这留下一个体验缺口——车机重启 / 厂商把 adbd 打回厂商口之后，用户**第一次点连接**可能
失败或多等几秒。看门狗把这个动作提前：App 常驻期间每 30 秒只读一条瞬态属性，发现口不对
就在用户点连接**之前**修回来。

### 三道保险（为什么不会乱动 adbd）

1. **enable 门**：仅当当前热点方案是 `ADB_HOSTAPD` 或 `AUTO`（都依赖 DBPlay 专用口）才巡检；
   `MANUAL` / `SAME_LAN` / `P2P_DIRECT` / USB 这些不用专用口的模式，看门狗整个不动作；
2. **占用门**：只有"短命 owner"（探测 `probe` / 自检 `selfcheck` / 更新 `update` /
   热点 `hotspot`）正在用通道时才避让；长期 owner `OWNER_USER_ADB`（用户已授权）**不算占用**
   —— 否则用户一旦授权过，看门狗会被永远挡住（这就是新增 `hasTransientOwner` 的原因）；
3. **限速门**：真正动手交给 PATCH-0123 的 `repairDedicatedPort()`，自带 15 秒冷却。

健康态**不刷日志**；每个周期只读一条系统属性（毫秒级、无副作用），
不做全量 `adb connect + shell` liveness——端到端验活仍由会话启动的 `acquire()` 负责。

### 改动（5 个文件，全在 `car/`）

| 文件 | 改动 |
| --- | --- |
| `api/…/hotspot/CarAdbHotspotContract.kt` | 新增 `ADB_WATCHDOG_PERIOD_MILLIS = 30_000L` |
| `core-bridge/…/hotspot/CarAdbWatchdog.kt` | **新增**纯 JVM 组件：依赖全部注入（enable / ownersActive / livePort / repair / log），`shouldRepairNow()` 纯函数 + `tick()` + `start()/stop()`（单线程调度器） |
| `core-bridge/…/hotspot/AdbShellSession.kt` | 新增 `hasTransientOwner`（排除长期 `OWNER_USER_ADB`） |
| `core-bridge/…/CarAppServices.kt` | 接线：`adbWatchdog` 字段 + `startAdbWatchdog()` / `stopAdbWatchdog()` |
| `app/…/CarAppController.kt` | 启动路径（心跳之后）调用 `services.startAdbWatchdog()`，幂等常驻 |

### 明确**没做**的事

- 没在开机瞬间（`BOOT_COMPLETED`）动 adbd——仍是"App 起来、确认无人占用之后才开始看"；
- 没做全量 liveness 探测（避免每 30 秒 `adb connect` 噪声与 adb server 注册）；
- 没动五种热点形态、`acquire()` 口径（仍固定 5556、不扫描候选端口、不改用 5555）；
- 没动 `version.properties`、未出包（铁律 1）；未动上游文件。

### 测试

新增 `CarAdbWatchdogTest` **9 例**（纯函数真值 5 + 行为 1 + 源码级接线 3）：
关闭/占用/已是 5556 时不修、不是 5556 且空闲时修、读不到端口也算需修、
健康态 tick 不刷日志、看门狗不自行扫描端口或初始化属性、CarAppServices 接线正确、
`hasTransientOwner` 排除长期授权 owner。

### 验证

- `:car:core-bridge:testDebugUnitTest` → **BUILD SUCCESSFUL**，**949 例 / 0 失败**；
- `:car:app:compileDebugKotlin`（含全部 `car/*` 模块）→ **BUILD SUCCESSFUL**；
- `git diff --check` → 无空白错误；未出包。
- ⚠️ **未实机验证**：判据是"重启/厂商打回后，**不点连接**、等一个巡检周期，日志里是否出现
  `XCExt.adb.watchdog: … 已发起修复`"，以及"正在自检/更新时看门狗是否确实让路"。


---

## PATCH-0125 · 复用车机热点失败的诊断取证：客户端 IPv4 逐包可选 + AirPlay TCP/7000 限时握手包头

> 承接车主对 67b3c24af6b5「手机提示已连、车机始终无画面」的追问。前序结论把故障边界定在
> 「原厂热点关联之后、AirPlay TCP 建立之前」，但现有诊断包**采不到**手机侧 DHCP/子网，也**没有**
> 7000 端口的 SYN/SYN-ACK——只能知道应用没接到连接，无法区分"手机没发/AP 没转发/车机没回包"。
> 本 patch 只补**取证能力**，不改任何连接逻辑、不改 SSID/接口/端点选择。

### 结论先行

两件事**可获得性不同**，所以分两档落地，且**都不伪造结论**：

1. **客户端地址：可在应用权限内做，但必须逐包显式勾选。** Ecarx SDK 本来就报客户端 IP/MAC，
   过去只是为隐私刻意不写。现在默认仍不写；用户在开发者页勾「本次包包含网络端点 IPv4」才写出
   **SDK 当前报告**的客户端 IPv4（并顺带保留 TCP 抓包两端 IP）。**MAC 永不写出。**
   这得到的是"车机看到的已连接客户端地址"，**不等于** DHCP 租约原文——租期/网关/options 仍读不到，
   诊断里如实写 `(not collected; no supported read-only OEM lease API)`。
2. **SYN/SYN-ACK：普通诊断权限做不到。** 抓包需要**已就绪的 root ADB + tcpdump**；工具**不会**
   自动开 ADB 或提权，条件不足就明确标 `ROOT_SHELL_UNAVAILABLE` / `TCPDUMP_UNAVAILABLE`。
   采集是**限时 90 秒、只 7000 端口、只 SYN/RST 包头（`-s 96`、不带 `-X/-A`）**，不抓应用负载；
   远端临时文件用 `trap … EXIT` 清理。

### 改动（14 个文件，全在 `car/`）

| 文件 | 改动 |
| --- | --- |
| `core-bridge/…/developer/AirPlayTcpCapture.kt` | **新增**：`rootShellProvider` 注入、先探测 tcpdump 再限时抓包；`buildCaptureCommand` 纯函数（路径白名单 + 输出路径正则 + 单引号转义）；`AirPlayTcpCaptureDiagnosticSummary.render` 如实渲染（未请求就写 `NOT_REQUESTED`，不写 `SYN observed: false`） |
| `core-bridge/…/developer/CarDiagnosticPackager.kt` | 新增 `airplay_tcp_evidence.txt`；`collectAndPack` 增 `includeClientIpv4`；wireless 段透传勾选与**本次会话窗口**客户端证据 |
| `core-bridge/…/developer/CarDeveloperMode.kt` | 新增独立 `tcpCaptureWorker` + `tcpCaptureBusy`；`startAirPlayTcpCapture()`；上传与抓包互斥 |
| `core-bridge/…/hotspot/EcarxHotspotReader.kt` | `Snapshot` 增 `clientIpv4Addresses`（只在内存）；邻居表**同时查 IPv4 ARP 与 IPv6**，按 SDK MAC 在内存匹配补地址 |
| `core-bridge/…/hotspot/CarHotspotClientWatcher.kt` | 新增 `RecentClientEvidence`（内存 only、10 分钟 TTL），供诊断包回看**连接窗口**那次观测 |
| `core-bridge/…/hotspot/EcarxHotspotDiagnosticSummary.kt` | 客户端 IPv4 按 `includeClientIpv4` 决定写/隐；新增会话窗口证据行与"DHCP 未采集""MAC 不采集"两行 |
| `api/…/developer/CarDeveloperContract.kt` | `CarDeveloperState` 增抓包状态字段；`collectAndUpload` 增 `includeClientIpv4`；新增 `startAirPlayTcpCapture` |
| `ui/…/CarUiContract.kt`、`ui/…/screens/DeveloperScreen.kt` | 开发者页：IPv4 勾选开关（默认关）+「开始 90 秒 TCP 抓包」次级按钮与状态回显 |
| `app/…/CarAppController.kt` | 两处 action 接线 |
| `DBPlay-UI-DESIGN.md` | 开发者页新增「网络隐私与抓包」行 |
| 测试 ×3 | `AirPlayTcpCaptureTest`（**新增 6 例**）、`EcarxHotspotDiagnosticSummaryTest`（+1 例 opt-in）、`CarDiagnosticEcarxContractTest`（+3 断言） |

### 明确**没做**的事

- 没改任何连接/握手/选路逻辑；没改 SSID 校准、接口选择、通告 endpoint；
- 没自动开 ADB、没自动提权、没常驻抓包（限时、限端口、限包头、有界输出）；
- 没把「应用没接到连接」当成「没收到 SYN」——两种证据在诊断里分开写；
- 没动 `version.properties`、未出包（铁律 1）；未动上游 `shared/`。

### 验证

- `:car:core-bridge:testDebugUnitTest` → **BUILD SUCCESSFUL**，**982 例 / 0 失败**；
- `:car:api:compileDebugKotlin` + `:car:ui:compileDebugKotlin` + `:car:app:compileDebugKotlin` → **BUILD SUCCESSFUL**；
- `git diff --check` → 无空白错误；未出包。
- ⚠️ **未实机验证**：真正的判据要上车——① 勾选后包里是否出现手机 IPv4；
  ② root ADB + tcpdump 就绪时抓包能否拿到 7000 端口的 SYN/SYN-ACK，从而区分
  "手机没发 / AP 没转发 / 车机没回 SYN-ACK / TCP 通了但 RTSP 没继续"。

---

## PATCH-0126 · TCAM 热点复用：移植 DiPlay 已验证路径，热点模式收敛为三档（出包 1.1.13 / 206）

> 承接车主对 67b3c24af6b5「手机提示已连、车机始终无画面」的定位。前序结论把故障边界定在
> 「原厂热点关联之后、AirPlay TCP 建立之前」，而 PATCH-0125 只补了取证能力、没碰连接逻辑。
> 本 patch 换方向：不再自建热点等手机来连，而是**复用车机原厂 TCAM 热点**——照搬同源二开
> DiPlay V3.2 已在车上跑通的实现（逆向材料 `workspace/diplay_v32/report/raw/src`）。
> 热点模式随之 4 档收敛为 3 档；另修一处被 `lintVitalRelease` 拦下的 minSdk 不兼容调用。

### 结论先行

1. **复用而非自建**：新增 `CarTcamHotspot` + `TcamHotspotManager`，接入卡固定 `eth0.11`，
   经 `NetworkCallback` 跟踪地址与链路状态，由 `CarPlaySessionBridge` 做会话级接入与回收。
   TCAM 语义是**只连不建**——只读接口、**从不配地址**，故切走时没有我们要清理的东西。
2. **模式收敛 4→3**：设置页移除 `MANUAL`（它与 TCAM 撞名是根因），选项改为 自建 / 局域网 / TCAM 复用；
   默认值 `MANUAL` → `TCAM_HOTSPOT`，配一次性迁移 + `load()` 解析层兜底，存量 `"MANUAL"`
   等价到 `TCAM_HOTSPOT`；老 SSID/口令字段**只读不清空**。
3. **切档矩阵纳入 TCAM**：`CarHotspotModeInit` 的 from→to 矩阵中 `ADB_HOSTAPD → 任何` 仍先拆自建清残留。
4. **顺手修掉一个真机必崩**：`TcamHotspotManager.kt` 调 `NetworkRequest.Builder#clearCapabilities()`
   （API 30），本机 `minSdk=28`、目标车机 Android 9 → 真机必 `NoSuchMethodError`。
   新建 builder 本无任何 capability，清理行冗余 → 删除，并加源码级契约测试钉死。

### 改动（提交 249a7de + 35ae63f）

| 文件 | 改动 |
| --- | --- |
| `…/carplay/api/hotspot/CarTcamHotspot.kt` | **新增**（181 行）：TCAM 复用核心，固定 eth0.11、只读 |
| `…/carplay/bridge/hotspot/TcamHotspotManager.kt` | **新增**（295 行）：NetworkCallback 跟踪地址/链路、会话级接入与回收；后删 API 30 调用 |
| `…/carplay/api/settings/CarSessionSettings.kt` | 移除 MANUAL、默认值改 TCAM_HOTSPOT、一次性迁移 |
| `…/carplay/bridge/SharedPreferencesCarSettingsStore.kt` | `load()` 解析层兜底：存量 `"MANUAL"` → `TCAM_HOTSPOT` |
| `…/carplay/bridge/hotspot/CarHotspotModeInit.kt` | from→to 矩阵纳入 TCAM |
| `…/carplay/bridge/network/CarHotspotResolver.kt` | 接线（43 行）|
| `…/carplay/bridge/CarPlayConfigBuilder.kt`、`CarPlaySessionBridge.kt` | TCAM 复用映射到上游「只连不建」语义 + 会话接线 |
| `ui/…/screens/SettingsScreen.kt`、`ui/…/components/SettingItem.kt` | 模式项 `filterNot(MANUAL)`，选项 4→3 |
| 测试 ×5 | `CarTcamHotspotTest`（新增 228 行）、`CarHotspotModeUiConvergenceContractTest`、`CarHotspotModeInitContractTest`（+41）、`TcamHotspotApiLevelGuardTest`（新增 74 行）、`SettingsDefaultMigrationTest` 与 `CarDiagnosticSettingsDumpTest` 断言同步 |

### 明确**没做**的事

- 没改上游 `shared/`；没改通告地址（本车 eth0 与全部 VLAN 子接口共用 `198.18.34.15/16`，
  按网卡名区分无意义）；
- 没保留过渡态：旧「本次开机」口径由永久开关取代，不留两套并存；
- 没自作主张改原厂配色/尺寸；没手改 `version.properties`（版本号由构建自动递增）。

### 验证

- `:car:core-bridge:testDebugUnitTest --rerun-tasks` → **BUILD SUCCESSFUL**（1000 项 / 0 失败）；
- `:car:app:assembleRelease -PallowRelease=true` → **BUILD SUCCESSFUL**，产物 **1.1.13 (206)**；
- 无头模拟器（API 30 / x86_64）安装 + 启动 MainActivity 无崩溃；
  （以上三项取自 249a7de / 35ae63f 提交记录）
- **加固交付包复核（出包当轮独立跑）**：`classes.dex` 3→1；4 个 ABI 均有伪名壳 so
  （`libmoduleutilbridge.so`，含 MSHD + chacha20）；apksigner 证书指纹与原包、keystore
  **三方一致** `30ded7ab…`；md5 `2fd008ee…` 与商店落盘端一致。
- ⚠️ **未实机验证**：TCAM 复用能否让 iPhone 连上原厂热点并走到 AirPlay 会话、
  MFi 认证是否通过——必须在车机（VIN `L6TTEST1111111111`）上验。

### 交付

| 项 | 值 |
| --- | --- |
| 交付包 | `DBPlay-1.1.13-206-202610161910_protected_signed.apk`（18,343,305 字节）|
| 落盘 | `/vol02/1000-1-6253137b/DBPlay/`、`/vol1/1000/Brooke/Deliverables/` |
| 商店直链 | `https://apps.sosun.cc/static/downloads/dbplay_1.1.13_206_202610161910_protected_signed.apk` |
| 商店状态 | 只在文件区，**未创建应用条目**（不上架）|

---

## PATCH-0127 · 复用档位判据统一：车机热点凭据识别与「一键填入」回到界面（TCAM 档）

> 2026-10-16 · 触发：车主在设置页找不到车机热点**账号/密码的识别与一键填入**
> （老版「复用车机热点」有，PATCH-0126 换档后消失）

### 背景：一次**无声**的功能丢失

PATCH-0126 把界面上的复用档从 `MANUAL`（「复用车机热点」）换成 `TCAM_HOTSPOT` 后，
仓库里**六处**判据仍写着 `== CarHotspotMode.MANUAL`，于是一整套「识别车机热点凭据」
的能力同时失效：

| 判据点 | 丢失的行为 |
| --- | --- |
| `CarAppServices` 简报的 `nativeCredentials` | 凭据根本不下发 ⇒ 一键填入按钮**永不出现** |
| `CarAppServices.manualSsidWarningOf` | 「手填名字 ≠ 车机真名」警告不再出现（真车日志 4：`Lynk&Co` ↔ `Lynk & Co`）|
| `SettingsScreen` 一键填入行 | `if (manualAttachment && …)` 恒假 |
| `SettingsScreen`「车机自带热点」读数行 | 车机热点开关状态（开/关/读不到）看不到 |
| `SettingsScreen` SSID placeholder | 仍显示「本机热点名」，与复用语义不符 |
| `CarManualHotspotPrecheck.blocker` | TCAM 档连接前不预检，热点关着时干等到超时 |

这六处**既没有编译错误，也没有测试失败**——三条契约测试恰好把旧写法
（`contains("== CarHotspotMode.MANUAL")`）当成「正确」钉住了。属于典型
「口径切换 → 判据漂移 → 静默失效」。

### 改了什么

| 层 | 文件 | 内容 |
| --- | --- | --- |
| api | `settings/CarSessionSettings.kt` | 新增**唯一判据** `CarHotspotMode.reusesCarNativeHotspot`（MANUAL ∨ TCAM_HOTSPOT），KDoc 写明这次静默失效 |
| api | `hotspot/CarManualHotspotPrecheck.kt` | 预检门槛放宽到复用类两档；`reuseRequested`（= 地址必须由用户填）仍只对 MANUAL 成立 |
| core-bridge | `CarAppServices.kt` | 简报 `nativeCredentials` 与 `manualSsidWarningOf` 改用统一判据 |
| core-bridge | `CarPlaySessionBridge.kt` | 预检传参由 `effectivePlan.reused` 改为 `mode == MANUAL`（TCAM 的 `reused` 同为 true，照它传会误报「地址没填」）|
| ui | `screens/SettingsScreen.kt` | 新增 `reuseAttachment`；读数行 / 一键填入行 / SSID placeholder 改用它（频段信道、本机 CarPlay 地址仍 `manualAttachment` 专属）|
| 测试 ×4 | `CarManualHotspotPrecheckTest`、`NativeHotspotCredentialsWiringContractTest`、`CarHotspotModeIsolationContractTest`、`HotspotManualReuseContractTest` | 旧口径断言翻新，并补一条 TCAM 用例（受管辖但**不**索要地址）|

界面效果（TCAM 档）：车机热点开着时出现一行
「填入车机热点「<车机真名>」 · 名称 + 密码」，点一下把**车机侧真实** SSID 与密码
写进设置页两个输入框；车机热点关着时，设置页当场给出可执行文案，连接前也会拦下。

### 明确**没做**的事

- 没把 MANUAL 枚举删掉（老安装 SharedPreferences 里存着 `"MANUAL"`，删了解析器会漏一支）；
- 没让 TCAM 档要求用户填地址 —— 它的接入卡（`eth0.11`）与地址由 `TcamHotspotManager`
  连接时自动识别，这是它与 MANUAL 的唯一实质差别；
- 没改上游 `shared/`、没动通告地址、没手改 `version.properties`。

### 验证

- 编译：`:car:ui` / `:car:core-bridge` / `:car:api` / `:car:app` `compileDebugKotlin` → **BUILD SUCCESSFUL**；
- 单测：`:car:core-bridge:testDebugUnitTest` → **BUILD SUCCESSFUL**（1003 项 / 0 失败）；
- ⚠️ **未验证**：车机上界面是否真的出现该行、一键填入读到的密码是否与车机一致
  —— 车机当前离线（隧道 `device_online=false`），需上车后再核。

### 提交

- `c72b07d` `feat(hotspot): 复用档位恢复车机热点凭据识别与一键填入`（9 文件 / +90 −18）
---

## PATCH-0128 · 复用档补进程级网络绑定：把回包路径从「偶然」钉成「必然」（出包 1.1.14 / 207）

> 2026-10-17 · 承接 PATCH-0126 落地的 TCAM 复用档「偶发用不了 / 手机约 40 秒超时」
> 完整核实链见 `docs/DBPlay-复用档回包路径核实与改动方案-20261017.md`（提交 `7428170`）

### 结论先行

1. **根因**：复用档以前只「读」网卡、**不绑任何东西**。回包走哪张路由表由 socket 的
   `fwmark` 决定 —— 只有「进程默认网络恰好是 `eth0.11`」时才正确。默认网络一漂
   （蜂窝抢到默认 / 另一路 Ethernet 上来），回包就从错口出去 ⇒ 手机连不上 7000。
2. **对策（方案 A）**：附着成功后把**本进程**显式绑到选中的那条 `Network`
   （`ConnectivityManager.bindProcessToNetwork`，API 23+），`close()` 里解绑。
   把这个「偶然」钉成「必然」。
3. **时序是硬要求**：绑定必须在 AirPlay `7000` 监听 socket 建立**之前**
   （socket 的 `fwmark` 在创建时取进程默认网络），故落在 `start()` 启动期链路复查通过之后、
   返回之前。
4. **绑定/解绑同处收尾**：两件事都留在 `TcamHotspotManager` 内，使**解绑覆盖面
   == NetworkCallback 注销覆盖面**；调用方只读 `attachedNetwork`，不再自己绑一次，
   避免出现两个「谁负责解绑」的源头。

### 改动（提交 `1ac7a23`，2 文件 +102 / −1）

| 文件 | 改动 |
| --- | --- |
| `…/bridge/hotspot/TcamHotspotManager.kt` | `start()` 返回前 `bindProcessToNetwork(network)`；`close()` **最前面**解绑，只解自己绑过的那一次（`processBound` 标志）；新增只读 `attachedNetwork` / `processBound`；attach 诊断行加 `processBind=true/false` |
| `…/bridge/CarPlaySessionBridge.kt` | **零控制流改动**，仅加一行只读诊断 `TCAM selected network=<net> iface=<if> host=<ip>`，让真机日志一眼看到钉的是哪条网络 |

边界处理：绑定**失败**只记一条诊断、**不回滚会话**（回包没钉住是风险，不是本次会话的
致命错误）；用独立 `processBound` 标志而不是「看 `selectedNetwork` 非空」——绑定可能返回
`false` 或抛异常，那种情况下我们根本没绑过，`close()` 就不该去动系统默认网络；
捕获 `Throwable`（厂商 ROM 缺方法会抛 `NoSuchMethodError`）。

**落点相对原任务书的偏离（如实登记）**：任务书原写「桥梁侧 bind」，实现落在
`TcamHotspotManager` 内。理由是解绑覆盖面必须等于回调注销覆盖面，放 manager 里天然满足；
桥梁侧只保留只读诊断。

### 明确**没做**的事

- 没用 `clearCapabilities()`（API 30，本机 `minSdk=28` / 车机 Android 9，PATCH-0126 已踩过）；
- 没改热点、没改网卡配置 —— TCAM 档「复用而不自建、只读不改网卡」的语义没有变；
  绑定只影响本进程自己的路由选择；
- 没改上游 `shared/`；没手改 `version.properties`（版本号由构建自动递增）。

### 验证

- 编译 + 单测（出包当轮独立跑）：`:car:core-bridge:compileDebugKotlin`
  `:car:core-bridge:testDebugUnitTest` → **BUILD SUCCESSFUL in 14s**（`EXIT=0`）；
  XML 聚合 `classes=106 tests=1003 failures=0 errors=0`；
- 出包：`:car:app:assembleRelease -PallowRelease=true --no-configuration-cache`
  → **BUILD SUCCESSFUL in 1m 42s**，产物 **1.1.14 (207)**；
- 进包核验：裸包 `classes*.dex` 内三条新串全部命中
  （`processBind=` / `TCAM selected network=` / `TCAM process bind failed`）；
- 加固复核：加固 16 秒，裸包 40.8 MB → 加固 17.1 MB → 重签 18 MB（`18,343,471` 字节）；
  `classes.dex` **3→1**；`libmoduleutilbridge.so` 含 **MSHD ×2 + chacha ×2**；
  apksigner 证书指纹与原包**一致** `30ded7ab…`（同证书，可覆盖安装）；
  三份落盘文件 md5 一致；
- ⚠️ **未实机验证**：`processBind=true` 是否真出现、回包是否真走 `eth0.11`
  —— 必须在车机（VIN `L6TTEST1111111111`）上验。

### 交付

| 项 | 值 |
| --- | --- |
| 交付包 | `DBPlay-1.1.14-207-202610170725_protected_signed.apk`（18,343,471 字节）|
| 落盘 | `/vol02/1000-1-6253137b/DBPlay/`、`/vol1/1000/Brooke/Deliverables/` |
| 商店直链 | `https://apps.sosun.cc/static/downloads/dbplay_1.1.14_207_202610170725_protected_signed.apk`（HTTP 200 · 18,343,471 字节，与本地一致）|
| 商店状态 | 只在文件区，**未创建应用条目**（不上架）；落盘名 `-`→`_` |

---

## PATCH-0129 · 调试隧道开关「拨上即连」：把只在启动时建链的缺口补上（未出包）

> 2026-10-17 · 现场症状：车机上「开启调试隧道」拨到开，跳板 `status.json` 的
> `device_online` 永远 `false`，`/root/relay.log` 里连一次来自车机的连接都没有。

### 根因（源码事实，不是猜）

全仓只有两处会真正拉起隧道：

| 位置 | 触发时机 |
| --- | --- |
| `CarAppController.startSession()`（`startDebugTunnelIfNeeded(settings)`）| App 启动 |
| `CarAppController.restartDebugTunnel()` | 用户点「立即重连隧道」 |

而开发者页的开关回调只做保存：`applySettings(copy(debugTunnelEnabled = it), reconnect = false)`。
⇒ **拨开关只落盘、不拨号**，必须重启 App 或再点一次「立即重连隧道」才建链。
现场诊断包（`DBPlay_Diagnostic_1.1.14_20261017133309.zip`，device `f9e4aaa2cac0`，
VIN `L6TTEST1111111115`）里 `effective_settings.txt` 也是 `debugTunnelEnabled: false`
—— 连同「拨了没连」一起构成了完整证据链。

### 改动

`car/app/src/main/java/com/dbtool/carplay/app/CarAppController.kt`

- `applySettings()` 里在 `this.settings = settings` **之前**记下旧值
  `previousDebugTunnelEnabled`；
- 函数末尾（`refreshModel()` 前）判 `settings.debugTunnelEnabled != previousDebugTunnelEnabled`
  ⇒ 写一条日志并调 `startDebugTunnelIfNeeded(settings)`（关闭时该方法本身会 `stop()`）。
- 只在**开关本身变化**时动作：自动保存会在防抖窗口内高频提交同一份设置，不能每次都重建。

### 验证

| 项 | 结果 |
| --- | --- |
| `:car:app:compileDebugKotlin` | `BUILD SUCCESSFUL in 20s`（仅既有 width/height 弃用告警）|
| `:car:core-bridge:testDebugUnitTest` | 1003 用例 / 0 失败 / 0 错误 |
| 版本号 | 未动（只编 debug、不出包）|

### 未验证

⚠️ 实车未验：拨开关后隧道是否能立刻上线，要在车机上拨一次并看跳板
`device_online=true` / `7000` 有 ESTABLISHED 才算真通。

---

## PATCH-0130 · 调试隧道设置真正落盘 + 默认口令补到 12 位（未出包）

> 2026-10-17 · 承接 PATCH-0129。现场：车机上「开启调试隧道」拨到开，跳板
> `status.json` 的 `device_online` 永远 false，relay.log 里连一次车机拨号都没有。

### 根因（两条都在源码里坐实）

1. **隧道 5 个字段完全没过 SharedPreferences**
   `SharedPreferencesCarSettingsStore` 的 `load()` / `save()` 里
   `debugTunnelEnabled / Host / Port / LocalAdbPort / Secret` **一个都没有**。
   ⇒ 开发者页上改地址、改口令、拨开关，全是内存态：重启回默认，
   而 `refreshModel()` 读回的还是默认值（诊断包里因此是 `debugTunnelEnabled: false`）。
2. **默认口令 11 位 < 跳板要求 12 位**
   旧默认 `dbplay-2026` 正好 11 字符；跳板 `relay.py` 弱校验模式
   `MIN_SECRET_LEN = 12`，`len(sec) < 12` 直接 `return False`
   ⇒ 即使拨通也会被判「口令不匹配」断开。

批量取证：`python3 car_scene_diag.py --scan 3` 三台车全是 `隧道=false / 口令 11 位`，
说明这不是单台车的偶发，是**所有车都踩**。

### 改动

| 文件 | 改什么 |
| --- | --- |
| `CarSessionSettings.kt` | 默认口令 `dbplay-2026` → `dbplay-2026!`（12 位），带注释说明跳板约束 |
| `SharedPreferencesCarSettingsStore.kt` | 新增 5 个键常量；`load()` 读回 5 字段；`save()` 写全 5 字段；新增一次性迁移 `migrateDebugTunnelSecretToTwelveChars`（独立标记 `debug_tunnel_secret_migrated_v1`，判据是裸字符串 `"dbplay-2026"`，用户自己写过的口令不动）|
| `CarAppController.kt` | `startDebugTunnelIfNeeded()` 拨号前检查口令长度，<12 位直接写日志说明「服务器要求至少 12 位，拨号会被拒绝」|

### 验证

| 项 | 结果 |
| --- | --- |
| 编译 | `:car:core-bridge` + `:car:api` + `:car:app` 三个 `compileDebugKotlin` 全 `BUILD SUCCESSFUL` |
| 单测 | 1010 用例 / 0 失败 / 0 错误（含新增 `DebugTunnelSettingsContractTest` 7 条）|
| 新增契约 | 默认口令 ≥12 位、不再是旧值、5 键齐全、load 读 / save 写、迁移有独立标记且只改旧默认、`applySettings` 拨开关即建链 |
| 版本号 | 未动（只编 debug、未出包）|

### 未验证

⚠️ 实车未验：装上后「拨开关 → 跳板 `device_online=true`」「重启后开关仍是开」两件都要在车上确认。
老安装升级后会跑一次口令迁移（11 → 12 位），跳板侧无需改动。

---

## PATCH-0131 · 按 VIN 分车：车机握手带 VIN + 跳板多槽中继 + 工具链（未出包、未部署 v8）

> 2026-10-17 · 斌哥口径：「我每次面对的车不固定，要按 VIN 区分」；并明确**授权方案他自己做**，
> 我这边只负责「报 VIN → 连到那台」。

### 车机侧

| 文件 | 改动 |
| --- | --- |
| `LynkDebugTunnel.kt` | 构造新增 `vin` / `appVersion` / `token`；握手帧抽成 `internal fun buildHandshake(...)`，四种形态：老帧（无 VIN）逐字节不变、有 VIN、有 VIN+token |
| `CarAppController.kt` | 构造隧道时传 `readCachedVinForTunnel()`（复用 `CarVinReader` 的 `dbplay_vin_cache`）+ 版本名 + token |
| `CarSessionSettings.kt` / `SharedPreferencesCarSettingsStore.kt` | 新增 `debugTunnelToken`（新键，老安装取空串，不需迁移），`load/save` 读写 |
| `CarDiagnosticSettingsDump.kt` | token 只报「有无 + 长度」 |

### 跳板侧（新文件，`scripts/jumphost/`）

- `relay_v7.py`：**按 VIN 分槽**（default 15555；VIN 槽 `15600 + VIN后6位数字 % 200`，线性探测；last-wins；同时在线上限 20），`status.json` 增 `devices`。**已部署到 39.108.105.65:7000**，老帧照旧落 15555；备份 `/root/relay.py.bak.20261017_1830`。
- `relay_v8.py`：在 v7 上加「token 鉴权」（`tok=` 校验失败不占槽、`--strict` 关 legacy、VIN 打码）。**未部署**——斌哥口径是授权方案他自己做，v8 先留仓。
- `car_adb.py`：`--vin <后6位>` 从 status.json 找端口 → `adb -s 127.0.0.1:<port>`，收尾必 `adb kill-server`。
- `tunnel_token_cli.py`：按 VIN 从授权中心换 token（管理员令牌或该车授权码两种）。

### 授权中心（另一台机，生产已上线）

`POST /api/license/tunnel-token`（纯新增）：`token = hex(HMAC-SHA256(TUNNEL_KEY,"dbplay-tunnel:"+VIN))[:24]`。
两式鉴权：带 `licenseCode`（校验码↔VIN 绑定且 active）或 `X-Tunnel-Admin`（管理令牌）。
密钥 `/etc/dbplay-tunnel/tunnel_key`（32B/0600），跳板与授权中心同一份。
回滚脚本 `workspace/p2_rollback.sh`。

### 验证

| 项 | 结果 |
| --- | --- |
| 编译 | `:car:platform-geely` / `:car:app` / `:car:core-bridge` 全 `BUILD SUCCESSFUL` |
| 单测 | platform-geely 67、core-bridge 1013，0 失败（含新增握手字节级测试） |
| 跳板 | v7 `--selftest` `PASS: devices=3`；7000 启动日志 `VIN 槽=15600..15799` |
| 授权中心 | admin/license 两种方式均签发成功，token 与本地 HMAC 重算一致；错 VIN 403 |
| 版本号 | 未动（未出包）|

### 未验证

⚠️ 车机侧代码**未装车**：要「报 VIN」必须出 1.1.15 才能生效；在那之前所有车仍走 default 槽 15555。

---

## PATCH-0132 · QQ音乐无封面：封面收到即用 + NowPlaying 属性 dump（未出包）

> 2026-10-17 · 用户症状：**QQ音乐** 卡片无封面，且「标题」位置显示的是**歌词**；同一台车用**网易云**封面正常。

### 根因（源码事实）

| 现象 | 机制 |
| --- | --- |
| 网易云有封面 | `iap2 artwork received id=129 bytes=49990` → 卡片 `artwork=49990B`，封面 ID 与当前曲目一致 |
| QQ音乐没封面 | `publishArtwork` 原实现**只在 `fileTransferId == nowPlaying.artworkFileTransferId` 时接收**；手机先推封面、或 ID 不一致 ⇒ **直接丢弃且不再重试** |
| 标题显示歌词 | 三字段疑似整体错位（历史解包见 Title 内是「监制：陈辉阳」这类署名，artist/album 也像下移一格），但当时原始帧被截断，**没有定论** ⇒ 本次不猜着改映射，先加实证 |

### 改动

| 文件 | 改动 |
| --- | --- |
| `CarPlayMediaSessionService.kt` | 新增纯函数 `shouldPublishArtwork(incomingId, currentTrackId, hasSameIdCached)`：**ID 不等也接收**（只跳过「同 ID 已缓存」的重复）；`publish()` 只失效幂等缓存，**不清已显示封面**；ID 不匹配日志每会话最多 5 条 |
| `Iap2BodyReader.kt` | 新增只读 `forEachParam(groupIndex, peek)` 遍历，**不改既有 `string()/u32()/has()` 行为** |
| `Iap2Messages.kt` | 新增 `describeAttributes(frame)`：逐属性输出 `g0[id=1 str(33)="…"]`，单条预览 ≤48 字符、整串 ≤500 字符 |
| `CarPlayController.kt` | 每个 `0x5001` 帧 dump 一次 `NowPlaying attrs: …`，`AtomicInteger` 控制每会话 20 条，会话起止复位 |

### 验证

| 项 | 结果 |
| --- | --- |
| 编译 | `:car:app:compileDebugKotlin` `BUILD SUCCESSFUL` |
| 单测 | `shared` 248 用例 0 失败（含新增 `Iap2NowPlayingAttributesTest` 4 条：属性枚举、预览截断、既有解析不变、`shouldPublishArtwork` 语义）|
| 版本号 | 未动（未出包）|

### 未验证

⚠️ 只能装车确认：
1. QQ音乐封面是否恢复（若它根本不推封面，则本补丁无效，需实现「主动请求封面」）；
2. 「标题=歌词」的真实属性 ID —— 拿到 `NowPlaying attrs` dump 后再改映射表；
3. 切歌瞬间是否短暂显示上一首封面（本方案用「收到即用」换可用性）。

## PATCH-0133 热点连接提速：简报缓存 + RFCOMM 无客户端早停

> 依据：`docs/DBPlay-热点连接速度与自愈-方案-20261017.md`（12 份诊断包跨版本实测）。

### 现象

- 复用档（TCAM）一次连接里热点简报重复 4–8 次，合计 **18.0–32.7s**；自建档单次 0.2–3.5s（EX11 机型约 4s/次）。
- 蓝牙 iAP2 首连必失败，2 台手机 × 3 轮共 6 次 RFCOMM，**26.7s 才放弃**，而当时热点侧客户端数为 0。

### 改动

| 文件 | 内容 |
| --- | --- |
| `hotspot/HotspotBriefingCache.kt`（新）| 按「模式 + 关键设置」指纹缓存简报结果，TTL 30s；模式切换、手动刷新、恢复出厂强制失效 |
| `CarAppController.kt` | `requestHotspotBriefing(force)`；命中日志 `热点简报：命中缓存（剩余 Ns）`；排队任务开跑前再查一次缓存 |
| `shared/.../CarPlayController.kt` | `shouldStopRfcommRetry(round, clientCount, apEnabled)`：热点已开 + 客户端 0 + 已跑过 1 轮 ⇒ 停止后续 RFCOMM 轮询 |
| `hotspot/CarHotspotClientWatcher.kt` | 只读发布 `clientCount`，加世代号防旧采样写回 |

### 判据

| 项 | 结果 |
| --- | --- |
| `:car:core-bridge` | 1025 用例 0 失败 |
| `:shared` | 252 用例 0 失败 |
| 新增测试 | `HotspotBriefingCacheTest` 4、`RfcommRetryPolicyTest` 4、`HotspotClientCountWiringTest` 3 |

### 未验证

⚠️ 装车才能确认：简报从 6–8 次降到 ≤1 次；`RFCOMM 早停` 日志是否出现、首连放弃时间是否从 26.7s 降到约 9s。

---

## PATCH-0134 TCAM 复用档接入客户端数采样

### 现象

PATCH-0133 的早停判据依赖客户端数，但观测器原先只在 `ensureManualHotspotReady` 启动；TCAM 复用档在连接窗口内**没有任何客户端数采样**，实测那台 6 连败 26.7s 的车无法早停。

### 改动

| 文件 | 内容 |
| --- | --- |
| `CarPlaySessionBridge.kt` | `ensureTcamHotspotReady()` 里启动同一个 `CarHotspotClientWatcher`（纯只读） |
| `CarPlayConfigBuilder.kt` | 早停的客户端数 provider 条件加入 `TCAM_HOTSPOT` |

### 未验证

⚠️ 读不到 Ecarx 客户端数的机型仍会跑满 3 轮（刻意不猜）。

---

## PATCH-0135 热点简报按模式裁剪探测 + 分段耗时

### 现象

自建档并不使用原厂探测结果，但简报仍会调 `nativeHotspotProbe.cachedInfo()`；缓存过期时触发一次几秒级 ADB 探测，结果被丢弃。且日志只有「简报慢」，看不出慢在哪一段。

### 改动

| 文件 | 内容 |
| --- | --- |
| `CarAppServices.kt` | `hotspotBriefing` 里 `nativeInfo` 先判 `reusesCarNativeHotspot`，自建档直接 `null`；新增 `热点简报分段：mode=… probe=NNNms resolve=NNNms total=NNNms` |

### 未验证

⚠️ EX11 单次约 4s 的成本在 `probe` 还是 `resolve` 段，需新日志确认后再决定是否进 `CarHotspotResolver` 拆解。

---

## PATCH-0136 复用原厂热点：不再用保存的 SSID 校验 + attach 前清自建残留

### 现象（VIN 尾号 368562 / 1.1.14 / 2026-10-17 20:08 包）

```
IOException: Saved hotspot name differs from the active TCAM hotspot   ×4
IOException: The car TCAM hotspot is off                              ×19
failed to start CarPlay session                                       ×23
```

- 模式已切到 `TCAM_HOTSPOT`，但 `manualHotspotSsid` 仍是自建档的 `DBPlay` ⇒ 名字校验直接拒连。
- 同一份快照里 `wlan0=[192.168.175.1]`：自建热点地址残留，复用会把我们的地址当成车机热点通告给 iPhone。

### 改动

| 文件 | 内容 |
| --- | --- |
| `network/CarHotspotResolver.kt` | `tcam()` 的 `ssid` 传 `null`，不再引用 `manualHotspotSsid`；简报标签改「车机原厂热点（名字连接时自动读取）」 |
| `CarPlaySessionBridge.kt` | `TcamHotspotManager(savedSsid = null, savedPassphrase = null)`；attach 前若检出**我们自己配过**的地址（`adbHotspot.state().hostAddress`）就先 `stop()` + `releaseLeftoverAddress()` |

### 判据

| 项 | 结果 |
| --- | --- |
| `:car:core-bridge` | 1025 用例 0 失败（含 `TcamSsidPolicyTest` 3 条） |
| `:car:app:compileDebugKotlin` | 通过 |

### 未验证

⚠️ `The car TCAM hotspot is off` 是用户侧未开原厂热点，App 按语义不自行开启，只能提示；真车验证需装 1.1.15。

---

## PATCH-0138 · 工程模式构建开关：暂停仪表主题授权门禁（出包 1.1.26 / 219 工程版）

> 本次改动**全在 `car/`**（`car/app/build.gradle.kts`、`car/app/…/MainActivity.kt`、新增单测），**不涉及上游文件**。
> 登记在这里是为了把「仪表主题 P1.5 的授权链路」和「工程版怎么绕开它」连成一条。

### 现象（2026-10-19 车主）

> 「我需要先把仪表主题的授权先暂停下，我在模拟器进去都看不到界面」

仪表主题模块自 1.1.18 起带完整授权门禁，模拟器里这条链路处处断：

| 环节 | 模拟器里的结果 |
| --- | --- |
| 入口门禁 `themeModuleActive` | 云端 catalog 没有本机授权 ⇒ `licensed = false` |
| 点「仪表主题」入口 | 只弹「获取授权」弹窗（`ThemeRedeemSheet`），**主题页打不开** |
| 弹窗内扫码 | 模拟器读不到 VIN ⇒ 只显示「本车暂未读到 VIN」引导 |

⇒ 没有服务端授权、读不到 VIN 的环境下看不到主题页界面，UI 开发调试被卡死。

### 改动

| 文件 | 内容 |
| --- | --- |
| `car/app/build.gradle.kts` | 新增 `-Pdbplay.engineering=true` 构建期开关 ⇒ `BuildConfig.ENGINEERING_MODE`（默认 `false`）；`buildFeatures.buildConfig = true`（AGP 8+ 默认不生成 BuildConfig） |
| `car/app/…/MainActivity.kt` | 新增纯函数 `themeModuleGateOpen(licensed, engineering)`：工程模式把模块按已授权放行（入口直达主题页）；`themeAccessForUi()`：工程模式下 catalog 的按主题锁定一律视为 `AUTHORIZED`；启动日志打一行 |
| `car/app/src/test/…/ThemeModuleGateTest.kt`（新增） | 门禁真值表 4 条 |

**刻意不做运行时开关**：那等于给最终用户留一个"自己关掉付费授权"的口子，DBP 授权码体系会直接失效。工程版只活在带 `-Pdbplay.engineering=true` 的那次构建里。

### 判据

| 项 | 结果 |
| --- | --- |
| `:car:app:testDebugUnitTest` | BUILD SUCCESSFUL（含 `ThemeModuleGateTest` 4 条） |
| `:car:app:assembleRelease -PallowRelease=true -Pdbplay.engineering=true` | BUILD SUCCESSFUL，出包 **1.1.26 (219)** |
| 生成的 `BuildConfig.java`（release 变体） | `ENGINEERING_MODE = true`（已核实） |
| `lintVitalRelease` | 通过（NewApi fatal 门禁未触发） |

### 未验证

⚠️ 未在模拟器实机走查：装 `DBPlay-1.1.26-219-202610191100.apk` 后需确认「设置 → 仪表主题」入口直达主题页、且不弹授权窗。普通包（不带该参数）行为不变，待下次正常出包回归。

---

## PATCH-0139 · HUD 连接重放 + 20 秒保活（对齐 HudBridge 反编译工作流）

> 依据 `HUD/HudBridge_修复版.apk` 的 REA/JADX 静态分析。该服务端在最后一次
> 有效导航后约 **30.5 秒**无新帧就清空导航状态；客户端连接成功后会重放
> `current`，断线时若仍有导航则主动重绑。

### 现象

DBPlay 的 HUD 绑定是异步的：导航首帧可能早于 `onServiceConnected`，旧实现
在 `ensureBound()` 为 false 时直接丢掉该帧，仪表要等下一帧才出现卡片；
服务重启后也不会主动重绑。停车等灯时 iPhone 的距离/秒数长期不变，被内容
去重后不再发帧，服务端 30 秒超时会把卡片清掉。

### 改动

| 文件 | 内容 |
| --- | --- |
| `car/platform-geely/.../HudTargetResolver.kt` | 新增 `HudPendingGuidance`（有效期待发帧）、`HudKeepalive`（20 秒单调计时）、`HUD_RECONNECT_DELAY_MS` |
| `car/platform-geely/.../GeelyHudBridge.kt` | 发帧前先保存有效 guidance；`onServiceConnected` 重放待发帧或发停止；断线后 3 秒退避重绑；成功发帧后排 20 秒保活；停止/过期/结束统一撤计时 |
| `car/platform-geely/.../HudTargetResolverTest.kt` | 待发帧有效期、清空、断线退避、保活周期与边界用例 |
| `car/core-bridge/.../HudGuidanceWiringContractTest.kt` | 静态钉住重放、重连与保活接线 |

### 1.1.24 现场日志补丁（同日追加）

车机日志显示 HUD 服务已连上，但 `request=1/2` 都返回 `HUD 未受理本帧`：

```text
HUD 桥目标解析：com.baidu.naviauto/.../CarPlayHudBridgeService
已连上 HUD 桥服务
推送 HUD：null米 / 未知位置
HUD 未受理本帧（request=1）
推送 HUD：17300米 / 未知位置
HUD 未受理本帧（request=2）
```

根因有两层：

1. `0x5201` 先到、`0x5202` 后到。DBPlay 第一帧没有 maneuverType，
   `writeLegacy()` 却用 `turnKind(maneuverType ?: 0)` 写成 **0**；
   服务端 `h.a()` 要求 `turnKind > 0`，因此整帧被拒。原版在这一支写的是
   `resolvableTurnKind()` 的 `0x9c`（156）哨兵。
2. 0x5202 补上真实转向时，距离/秒数没变；旧的去重键只有
   `(距离, 秒数)`，把这一帧丢掉了，所以没有第二次推送真实转向。

修复：

| 文件 | 内容 |
| --- | --- |
| `GeelyHudBridge.kt` | `writeLegacy()` 写调用方算好的 `turnKind`，不再用 maneuverType 现算 |
| `CarPlayNavigationHudClient.kt` | 去重签名加入 `maneuverType + 当前路名 + 目的地/下一段路`；同距离同秒数但转向/路名变化时仍推送 |
| 两个测试文件 | 增加 `turnKind=0x9c` 写帧契约、同距离同秒数转向更新、签名映射用例 |

### 判据

| 项 | 结果 |
| --- | --- |
| `:car:platform-geely:testDebugUnitTest` | BUILD SUCCESSFUL |
| `:car:core-bridge:testDebugUnitTest` | BUILD SUCCESSFUL |
| `:car:app:compileDebugKotlin -PallowRelease=false --no-configuration-cache` | BUILD SUCCESSFUL |

### 未验证 / 未做

- 未上真车验证；需确认首次导航、服务重启、长时间停车三种场景下仪表卡片是否持续正确。
- **未发送 code 11 车道帧**：当前 `HudBridge_修复版.apk` 的 `b(Parcel)` 会读取车道帧，但随后只创建空 lane 对象，`FakeBaiduLbsService.c()` 也不生成/发送事件；按现有服务端实现补车道不会真正显示。等桥端车道解析补齐后再接。

---

## PATCH-0140 · 仪表转速改读安全真实值 31612，移除本 ROM 无效的 31495

### 现象 / 根因

实车反馈 DBPlay 仪表主题转速与实际发动机转速对不上。2026-10-24 ADB 取证：

- App 日志每 200ms 一次 `读取信号 31495 失败: InvocationTargetException`，底层是
  `Invalid property Id: 0x7b07` —— 本车（CS11 ROM）`ecarx.car.jar` 信号表里**没有 31495**，
  所以 `fda490a` 引入的「真实转速」读取永远失败，每次都回落 31534。
- 只读探针连采 30 轮：31495 全抛非法属性；31534（EngSpdDispd，DIM 显示值）在
  1500↔2200 间平滑摆动，正是会与实际转速脱节的来源；31612（EngNSafeEngN）在台架返回 -1。
- 本 ROM 真实转速族是 `31612~31615`（EngNSafeEngN / Chks / Cntr / Grdt），
  没有其他发动机转速信号。

### 改动

| 文件 | 内容 |
| --- | --- |
| `car/core-bridge/.../vehicle/EcarxVehicleSignals.kt` | 转速链改为 `readValidRpm(31612) ?: readValidRpm(31534)`；负值（-1）按读不到处理；删除 31495 常量与注释；转速源切换时打一条 debug 日志（`runCatching` 包住，JVM 单测不受影响） |
| `car/core-bridge/src/test/.../EcarxVehicleSignalsTest.kt` | 全成功/车速异常用例改为显式给 31612；新增「负值回落 31534」「31612 有效时优先」两个用例；失败路径调用次数注释更新（仍为 6） |

### 判定

| 项 | 结果 |
| --- | --- |
| `:car:core-bridge:compileDebugKotlin` | BUILD SUCCESSFUL |
| `:car:core-bridge:testDebugUnitTest` | BUILD SUCCESSFUL（1234 tests, 0 failed） |
| `:car:app:compileDebugKotlin -PallowRelease=false --no-configuration-cache` | BUILD SUCCESSFUL |

### 未验证 / 未做

- 未路测：需在实车怠速/踩油门时对比 31612 与 31534、原厂仪表读数，确认 31612 即真实转速；
  台架上 31612 为 -1 时仍回落 31534（与原行为一致）。
