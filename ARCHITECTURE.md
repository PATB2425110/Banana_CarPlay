# xcertplay 二次开发架构

> 目标：**官方继续更新，我的车机适配和 UI 继续开发，两边尽量互不干扰。**
>
> 本文是这套约束的落地说明。任何与本文冲突的改动都应当先改本文、再改代码。

---

## 1. 项目整体架构

### 1.1 为什么不是「把 upstream 挪进 `upstream/` 目录」

常见的 fork 整理方案是把上游源码整体搬进 `upstream/xcertplay-core/`，自己的代码放外面。
**本项目不这么做**，原因是这会直接摧毁"持续同步上游"的能力：

- Git 的合并粒度是**文件路径**。把 `shared/` 搬到 `upstream/xcertplay-core/shared/`
  意味着每个文件都被上游视为"删除 + 新增"，每次 `git merge upstream/master`
  都会产生全量重命名冲突，需要人工逐个确认——这正是要避免的事情。
- 上游 4 个 Gradle 模块的 `settings.gradle.kts` 引用、CI 脚本路径、README 链接
  也全部失效，每次同步都要重新对齐。

因此采用**路径保持原样 + 平行新增扩展层**的方案：

```
上游文件原地不动（合并时零冲突）  +  我们的代码全部落在上游没有的路径下（合并时零冲突）
```

### 1.2 实际目录结构

```
xcertplay/                          ← 就是 shilapi/xcertplay 本身的 git 仓库
│
├── shared/          【上游】CarPlay 协议核心（airplay/iap2/mfi/transport/network/media...）
├── common/          【上游】共用宿主 UI + 设置 + 持久化
├── mobile/          【上游】标准 Android 应用壳
├── automotive/      【上游】Android Automotive 应用壳
├── gradle/ gradlew* build.gradle.kts   【上游】构建基建
├── settings.gradle.kts                 【上游 + 1 处追加】PATCH-0001
│
├── car/                              ← 【二开】扩展层（上游无此目录，永不冲突）
│   ├── api/                 :car:api              契约层（零项目依赖）
│   ├── core-bridge/         :car:core-bridge      Core 桥接（唯一允许依赖 :shared）
│   ├── platform-android/    :car:platform-android 通用 Android 基线适配
│   ├── platform-flyme/      :car:platform-flyme   Flyme 适配（骨架）
│   ├── platform-ecarx/      :car:platform-ecarx   Ecarx / 吉利 OneOS 适配（方控已接）
│   ├── steering/            :car:steering         方控路由与仲裁
│   ├── ui/                  :car:ui               自研 UI（Compose，仅依赖 :car:api）
│   └── app/                 :car:app              可运行产品 App（宿主 + 权限 + 装配）
│
├── configs/                          ← 【二开】各车机部署配置
│   ├── default/  flyme/  osn/
│
├── scripts/                          ← 【二开】同步与审计脚本
│   ├── sync-upstream.ps1             一键同步上游到 dev
│   ├── footprint.ps1                 审计改动足迹
│   └── install-mfi-identity.ps1      安装离线 MFi 身份资产
│
│   ⚠️ 不要叫回 `tools/`：上游根 .gitignore 第 21 行有 `tools/`，
│      且该模式匹配任意层级（`car/tools/` 也一样被忽略）。
│      详见 PATCHES.md「已排除的改动」。
│
├── ARCHITECTURE.md  UPSTREAM.md  PATCHES.md     ← 【二开】架构治理文档
└── README.md  README.zh-CN.md  LICENSE          ← 【上游】不要改
```

对应到最初的推荐结构，映射关系如下（未机械照搬的原因见 §1.1）：

| 推荐结构 | 实际落点 | 说明 |
| --- | --- | --- |
| `upstream/xcertplay-core` | `shared/` `common/` `mobile/` `automotive/` | 保持上游原路径 |
| `adapters/common` `adapters/android` | `car/platform-android` | 合并为一个基线适配模块 |
| `adapters/flyme` | `car/platform-flyme`（骨架） | 按模块 README 的清单逐项填 |
| `adapters/ecarx` | `car/platform-ecarx` | 方控已接 OneOS KeyInput |
| `adapters/osn` | — | ⬜ 未创建，按 `platform-ecarx` 模板新增 |
| `adapters/hardware` | 未单独建模块 | MFi 硬件适配属于上游 Core 职责（见 §2.3），当前无差异化需求 |
| `modules/ui` | `car/ui` | 仅依赖 `:car:api` |
| `modules/audio` `media` `microphone` `navigation` `diagnostics` | 暂未建，契约已在 `car/api` | 避免提前造空模块，首个真实需求出现时再建 |
| `modules/steering` | `car/steering` | |
| `app/mobile` `app/automotive` | `mobile/` `automotive/`（上游原样） | 二开产品 App 待 UI 设计确定后新增 |
| `configs/*` `scripts/*` | `configs/*` 同推荐；`scripts/*` 由 `tools/*` 改名而来（避开上游 ignore），见 PATCHES.md |

### 1.3 依赖方向（唯一允许的方向）

```
                    ┌──────────────────────────────┐
                    │  upstream :shared / :common  │
                    └──────────────┬───────────────┘
                                   │ 只有 core-bridge 能看见
                    ┌──────────────▼───────────────┐
                    │        :car:core-bridge      │
                    └──────────────┬───────────────┘
                                   │ implements
                    ┌──────────────▼───────────────┐
                    │          :car:api            │ ← 契约层，零项目依赖
                    └───┬────────┬────────┬────────┘
                        │        │        │
        ┌───────────────▼──┐ ┌───▼─────┐ ┌▼──────────────┐
        │ :car:ui          │ │ :car:   │ │ :car:platform-│
        │ (只依赖 api)      │ │steering │ │ android       │
        └──────────────────┘ └─────────┘ └───────┬───────┘
                                                │ extends
                                        ┌───────▼───────────┐
                                        │ :car:platform-*   │
                                        │ flyme / osn / ... │
                                        └───────────────────┘
```

**由构建脚本强制的硬约束**（不是口头约定）：

| 模块 | 允许的依赖 | 禁止 |
| --- | --- | --- |
| `:car:api` | 无 | 任何项目依赖 |
| `:car:ui` | `:car:api` | `:shared` `:common` `:car:core-bridge` `:car:platform-*` |
| `:car:platform-*` | `:car:api`（+ 基线） | `:shared` `:common` |
| `:car:steering` | `:car:api` | `:shared` `:common` |
| `:car:core-bridge` | `:car:api` + `:shared` | `:car:platform-*`（不反向依赖具体车机） |
| `:car:app` | `:car:ui` `:car:core-bridge` `:car:platform-*` `:car:api` | `:shared` `:common` `:mobile` `:automotive` |

> `:car:core-bridge` 用 `implementation(project(":shared"))` 而不是 `api`：
> 这样 `:shared` 的类型**不在** `:car:app` 的编译类路径上，
> 应用层连 `CarPlayController` / `AirPlayIdentity` 都 import 不到。
> 对上游的访问被强制收敛到 `CarAppServices` 一个入口。

只要不改 `car/*/build.gradle.kts` 里的 `dependencies` 块，架构就不会被无意破坏。
这是本方案"防退行"的核心手段。

---

## 2. 上游 Core 的职责

**禁止直接修改的目录**：`shared/`、`common/`、`mobile/`、`automotive/`、
`gradle/`、`gradlew*`、`build.gradle.kts`、`README*.md`、`LICENSE`、`.github/`、
`asset/`，以及 `settings.gradle.kts` 中非追加区域。

### 2.1 `:shared` — CarPlay 协议核心

| 包 | 文件数 | 职责 |
| --- | --- | --- |
| `airplay/` | 27 | AirPlay/CarPlay 协议与会话：RTSP 分帧、pair-setup(SRP-6a)、pair-verify(X25519)、auth-setup(MFiSAP)、info、SETUP/RECORD、屏幕流(110/111)、音频流(100/101/102)、iAP2 隧道(130)、NTP |
| `iap2/` | 9 | iAP2 CSM 协议：wire 编解码、endpoint 目录、schema 化 body 构造/读取、会话门面、帧追踪 |
| `mfi/` | 5 | MFi 认证：本地 I2C 客户端、远程 HTTP 客户端、iAP2 交换驱动、设备扫描、自检 |
| `transport/` | 28 | 物理链路：CH341 USB-I2C、Linux `/dev/i2c-N`、iPhone USB(usbmux/NCM)、Lockdown 配对与 TLS、iAP2 链路引擎、蓝牙 RFCOMM、NTB16 |
| `network/` | 7 | 无线接入：Bonjour/mDNS、VpnService 托管、IPv6 over NCM、Wi-Fi P2P / LocalOnlyHotspot / 手动热点 |
| `media/` | 6 | Android 渲染：`AndroidMediaSink`(MediaCodec→Surface / AudioTrack)、通道映射、触摸映射、Opus 编码 |
| `orchestration/` | 3 | **总编排**：`CarPlayController`（1838 行）、运行时配置、MFi 运行时 |
| `location/` | 1 | Android 定位源 |
| `shared/` | 3 | Car App Library 占位（`MyCarAppService` 等，未参与主流程） |

native：`shared/src/main/jni/linux_i2c_jni.c` → `libxcertplay_i2c.so`，
仅 `ioctl(I2C_RDWR)`，无第三方 so 依赖。

### 2.2 `:common` — 宿主 UI 与持久化

| 文件 | 职责 |
| --- | --- |
| `CarPlayHostActivity.kt`（3427 行） | **上游的完整宿主**：Surface、权限、设置页(~60 项)、三指手势、整栈重启、后台会话复用 |
| `AirPlayPersistence.kt`（575 行） | SharedPreferences 持久化：身份、配对记录、全部设置项 |
| `MainActivity.kt` | I2C 自检诊断页（独立入口） |
| `ImageCropActivity.kt` / `SafeAreaEditorView.kt` | 图标裁剪 / 安全区编辑器 |
| `BootReceiver.kt` | 开机自启 |
| `DarkMode.kt` / `SessionLogFile.kt` / `ui/theme/*` | 深色模式 / 日志文件 / Compose 主题 |

### 2.3 哪些属于 Core 而**不应该**被抽成适配器

判断标准：**是否与"车机差异"有关**。

- MFi 三条认证路径（CH341 / I2C / Remote）——上游已经用
  `MfiTarget` 枚举 + `MfiAuthenticator` 接口做成了策略，
  这是**硬件形态差异**而非车机品牌差异，保持在上游。二开侧只在
  `CarSessionOptions.mfiTarget` 里选择。
- iAP2 / NCM / AirPlay 协议栈——与车机无关，永远属于 Core。
- USB 枚举、Lockdown 配对、TLS——与车机无关，属于 Core。
- **视频解码→Surface、音频解码→AudioTrack**——属于 Core 的默认实现，
  但通过 `CarPlayMediaSink` 槽位（§4.3）允许二开侧替换。

---

## 3. Extension 的职责

Extension = `car/` 下的所有模块 + `configs/`。它们的共同特征：**全部是新增路径**，
不含任何上游文件的副本。

| Extension | 是否依赖上游 | 职责 |
| --- | --- | --- |
| `:car:api` | 否 | 定义契约：`CarPlatform` + 8 个域适配器 + 会话状态/事件/控制器 + 设置模型 |
| `:car:core-bridge` | 是（仅 `:shared`） | 把 upstream 类型翻译成契约类型；是**唯一**的上游耦合点 |
| `:car:platform-android` | 否 | 通用 Android 基线适配，作为所有车机的父类 |
| `:car:platform-flyme` | 否 | Flyme 差异实现（当前为骨架） |
| `:car:platform-ecarx` | 否 | Ecarx / 吉利 OneOS 差异实现（方控已接 OneOS KeyInput） |
| `:car:steering` | 否 | 方控键码映射、抖动抑制、事件路由 |
| `:car:ui` | 否 | Design System + 组件库 + 5 个界面（Compose） |
| `:car:app` | 否（只经 `:car:core-bridge`） | 产品宿主：权限申请、系统栏、把 UI 与控制器接起来 |

**`car/` 之外的 `:car:app` 是唯一的 application 模块。** 上游的 `:mobile` /
`:automotive` 保持原样不动，作为"上游行为基准"保留——需要在真机上对比
新旧行为时可以直接装上游 APK（`applicationId` 不同，两者可共存）。

**Extension 的收益**：上游更新时，需要人工处理的面 = `:car:core-bridge` 一个模块，
且集中在两个文件：`CarPlayStatusMapper.kt`（状态映射）与
`CarPlayConfigBuilder.kt`（配置映射）。

---

## 4. Adapter 的职责

### 4.1 `CarPlatform` 聚合

```kotlin
interface CarPlatform {
    val id: String                  // "android" / "flyme" / "osn" / "ecarx"
    val displayName: String
    val audio: CarAudioAdapter
    val display: CarDisplayAdapter
    val media: CarMediaAdapter
    val input: CarInputAdapter
    val system: CarSystemAdapter
    val navigation: CarNavigationAdapter
    val microphone: CarMicrophoneAdapter
    val diagnostics: CarDiagnosticsAdapter
}
```

安装点：`CarPlatformRegistry.install(platform)`，在 `Application.onCreate` 调用一次。
用显式注册而非反射/`ServiceLoader`：车机环境有 SELinux 与类加载器限制，
显式注册更容易排查，也不在启动阶段引入不可控的类加载。

### 4.2 各域适配器的边界

每个适配器都遵守同一条规则：**只回答"车机差异"，不实现通用能力**。

| 适配器 | 负责 | 明确**不**负责 |
| --- | --- | --- |
| `CarAudioAdapter` | 通道→车机音频路由、焦点申请、高级映射开关 | PCM 解码、AudioTrack 播放 |
| `CarDisplayAdapter` | 屏尺寸/物理尺寸/安全区/分辨率策略 | 画面渲染、Surface 管理 |
| `CarMediaAdapter` | 流生命周期广播（供桌面歌词/媒体卡片/音源切换） | 解码、渲染、媒体会话协议 |
| `CarInputAdapter` | 方控/硬键事件来源与独占 | CarPlay HID 报文编码（由 `:car:core-bridge` 的 `CarKeyHidMap` 负责） |
| `CarSystemAdapter` | 平台身份、系统属性、root/自启/前台保活能力 | 具体提权实现 |
| `CarNavigationAdapter` | 定位数据源、坐标系 | NMEA 编码（由 bridge 调用上游编码器） |
| `CarMicrophoneAdapter` | 采集源选择、采集准入 | `AudioRecord` 采集、Opus 编码 |
| `CarDiagnosticsAdapter` | 统一日志出口、状态快照 | 日志文件轮转策略 |

### 4.3 三个"槽位"（向上游借力的接缝）

这三种能力**不改 Core** 就能替换，因为它们在上游本来就是接口/构造参数：

| 槽位 | 上游接缝 | 二开侧接口 |
| --- | --- | --- |
| 媒体渲染 | 构造函数注入 `AirPlayMediaHandler`；`MediaSink` 是接口 | `CarPlayMediaSink` + `CarPlayMediaSinkFactory` |
| 位置回传 | 构造函数注入 `Iap2LocationProvider?` | `CarNavigationAdapter`（经 `UpstreamLocationBridge`） |
| 会话监听 | 构造函数注入 `AirPlaySessionListener` + `reportStatus` | `CarSessionController` / `CarSessionListener` |

配套的持久化也是注入的：`loadPairRecord` / `savePairRecord` / `clearPairRecord`
三个 lambda，以及 `AirPlayIdentity` / `PairingStore` 两个对象。

> 这些接缝是 `CarPlayController` 构造函数暴露的，**不是我们改出来的**。
> 这也是 `:car:core-bridge` 能完全不改 Core 就搭起完整会话的原因。

---

## 5. UI 层职责

### 5.0 技术选型：Compose（不是 XML，也不是 View）

上游 UI 的真实形态需要先说清楚：**它没有一行 XML 布局**，
`common/CarPlayHostActivity.kt` 是 3427 行**纯 Kotlin 代码手工 new View**
（`FrameLayout` + `setPadding` + `GradientDrawable` + 8 个颜色常量）。
所谓"基于 XML 优化"在这里没有对象可优化——上游的写法恰恰是最难做
设计系统的形态：样式散落在代码里、无法用主题、无法预览。

因此新 UI 用 **Compose**，理由是它已经在项目里启用（`:common` 的
Compose BOM / material3 依赖现成），不需要引入新技术栈；
而"统一 Colors/Typography/Dimensions/Shapes/Components"正是
`MaterialTheme` + `CompositionLocal` 的标准能力，用 View 体系反而要自造一套。

性能上无风险：界面是低频重绘的表单；CarPlay 视频走独立的
`TextureView`（`AndroidView` 互操作），不经过 Compose 渲染管线。

**上游 `CarPlayHostActivity` 一行未改**，继续作为行为基准保留。

### 5.1 分层

```
CarPlay Core (upstream)
      ↓  CarPlayStatusMapper / 事件转发      ← :car:core-bridge
      ↑  CarKeyHidMap / 方控 → HID 转发      ← :car:core-bridge
CarSessionState / CarSessionEvent           ← :car:api（稳定契约）
      ↓  CarUiStateMapper                    ← :car:ui
CarPlayScreenState                          ← :car:ui
      ↓
CarPlayApp（页面栈）+ 5 个界面               ← :car:ui
```

### 5.1.1 导航模型：主页 + 二级页面

**不设常驻导航栏。** 早期版本用过左侧竖直导航轨，实测两个问题：
横屏车机的宽度应该让给内容；常驻导航栏会让界面看起来像"一个装了
四五个 tab 的手机 App"，而不是"车机系统里的一个功能入口"。

现在的模型：

```
主页  ──右上角齿轮──→  设置（二级）
      ──快捷卡────→  设备 / 关于 / 音频（二级）
                              ↓
                        设置分区（三级）
```

用一个页面栈（`List<CarDestination>`）实现，天然支持任意深度，
返回键逐级回退。**设置项只在设置页定义一处**——首页的"音频"快捷卡
只是带着 focus 参数跳到对应分区，不复制任何开关。

### 5.1.1.1 主页 = 连接状态页

主页不是"功能聚合页"，而是**连接状态页**：状态图标（96dp 圆底 + 56dp 图标，
底色随状态语义）+ 主标题 + 副标题 + 不定量进度条 + 关键参数表，整页居中。
顶栏只留设置齿轮。

这样做的取舍：**放弃在首页放快捷入口**。首页的职责是
"让用户一眼确认系统在做什么"，一旦堆上快捷卡、系统信息、
设置开关，这个能力就没了。设备 / 关于 / 设置全部从右上角二级页面进。

#### 为什么删掉了那幅插画

上一版的主视觉是一幅 756 行的自绘插画（iPhone ↔ 连接线 ↔ 应用坞，
含粒子、拖影、光晕、投递弹射）加一个 163 行的动画状态机。它被删掉不是
因为画得不好，而是**信息效率为负**：

| 问题 | 说明 |
| --- | --- |
| 需要解码隐喻 | "手机""通道""坞"是比喻，用户得先翻译一遍才知道在等什么 |
| 状态靠"看动画"分辨 | 正常 / 卡死两种动效的差别要盯着看几秒，而车机上没人会看 |
| 挤掉了真正有用的信息 | 整屏给了插画，"当前阶段""链路参数"反而没地方放 |

现在是**静态状态页**：状态由图标语义 + 文案 + 参数表表达，一瞥可读。
动效只保留一条进度条，且只在"正在推进"时出现。
会话信息（链路 / 分辨率 / 编码 / 认证方式）直接排进参数表，
数据源仍是 `CarPlayScreenState.facts`，没有丢也没有改结构。

#### 状态色的映射只有一处

`CarStatusIndicator` 是唯一的状态语义。颜色映射集中在
`car/ui/.../components/StatusIndicators.kt`，分三个函数：

| 函数 | 返回 | 用途 |
| --- | --- | --- |
| `indicatorColor` | 强档色 | 图标、状态点、进度条 |
| `indicatorContainerColor` | 浅底档 | 图标圆底、状态胶囊底 |
| `indicatorOnContainerColor` | 浅底上的字色 | 状态胶囊文字 |

`HomeScreen` 的图标、`CarStatusView` 的圆底、`CarStatusPill` 的配色
**都由同一个 `indicator` 推出**。这样既不会出现"绿色底配叉号"这种
自相矛盾的组合，也不会出现"连接失败在首页是红的、在设置页是橙的"。

#### ⚠️ 自动进入画面页的判定在 `CarPlayApp`，不在 `HomeScreen`

会话激活后自动跳进画面页，逻辑写在 `CarPlayApp`。
**这是一处修过的真实 bug**：`HomeScreen` 处在 `when (current)` 分支里，
导航离开时会退出组合，它内部的 `remember` 随之被丢弃。守卫若放在里面，
"连上 → 自动进画面页 → 用户按返回 → 回到主页 → 守卫被重置 → 立刻弹回画面页"
就构成死循环，用户永远退不出去。

另外两条约束：
- 会话结束时必须解除标记，否则断开再连不会自动进入；
- 只在**停留在主页**时才跳——用户正在设置页里操作时被强行拽走很讨厌。

### ### 5.1.2 设置语义：草稿 → 显式提交

设置页的修改**只存在于界面草稿**里，点「保存并重连」才落盘。
这与上游 "Save & Reconnect" 一致。

这条是踩坑后改的：早期版本每次改动立即持久化，
结果"用户随便点两下看看"就静默改掉了配置——实测中把链路从 USB
改成了无线，首页随即显示错误的连接方式。
**用户不确认的修改不应该产生副作用。**

**UI 只做两件事**：订阅 `CarSessionController` 的状态；调用它的
`start/stop/reconnect/sendTouch`。

**UI 禁止**：
- 持有 `CarPlayController` / `AirPlaySession` / `AndroidMediaSink` / `AirPlayConfig`；
- 判断 `CarPlayStatus`；
- 自己构造 upstream 配置对象。

### 5.2 为什么状态模型要独立一层

上游 `CarPlayStatus` 有 20+ 个取值，且随版本增减（例如
`WaitingForReenumeration` 就是为 iPhone 重新枚举加的）。
若 UI 直接 `when(status)`，每次上游加状态都要改 UI。

映射层 `CarPlayStatusMapper` 使用**穷尽式 `when` 且不写 `else`**：
上游新增状态时该文件**编译失败**，强制我们显式决定新状态归属哪个阶段。
这是刻意设计——用编译错误代替"忘了处理"。

### 5.3 上游 UI 的处置

`common/CarPlayHostActivity.kt` **保持原样不动**。它是二开初期的可用参考实现，
也是验证上游行为的基准。自研 UI 在 `:car:ui` 里独立生长，
做完之后再决定是否用新的产品 App 模块替换 `:mobile` / `:automotive`。

---

## 6. CarPlay Core 与车机适配的关系

```
CarPlay Core (:shared)
   │  构造注入（Core 不认识下面的任何接口）
   ▼
CarSessionController / CarPlatform        ← 抽象
   ▲
   │  实现
CarAdapter = AndroidCarPlatform → FlymeCarPlatform → 具体车机
```

新增 OSN / Ecarx 的完整动作清单：

1. 新增 `car/platform-osn/`（复制 flyme 目录，改 `namespace`）；
2. `settings.gradle.kts` 的**追加块**里加一行 `include(":car:platform-osn")`；
3. 新建 `OsnCarPlatform : AndroidCarPlatform(context)`，只覆写有差异的域；
4. 在 `configs/osn/` 放该车机默认配置；
5. **不动** `:shared` / `:common` / `:mobile` / `:automotive` / `:car:api` / `:car:core-bridge`。

如果第 3 步发现某差异**无法**通过覆写实现，说明 Core 缺一个接缝。
此时走 PATCHES.md 流程（登记 → 评审 → 最小化改动 → 记录），
而不是直接在 Core 里改。

---

## 7. 音频架构

### 7.1 上游现状

`CarPlayMediaEngine.onAudio()` 从 SETUP 参数解析 `audioType`，
构造 `AudioStream` 接收加密 RTP，解密后回调
`MediaSink.onAudioStarted/onAudioRtp/onAudioStopped(type, format: AudioFormat, ...)`。
`AndroidMediaSink.AudioRenderer` 用 `MediaCodec` 解 AAC/Opus → `AudioTrack`。
通道映射在 `media/AudioChannelMapping.kt`，两档：

| 档位 | VOICE | NAVIGATION | MEDIA | ALERT / COMPAT |
| --- | --- | --- | --- | --- |
| `MOBILE_COMPATIBLE` | PHONE/SPEECH | ASSISTANT/SPEECH | MEDIA/MUSIC | NAVIGATION/SPEECH |
| `AUTOMOTIVE_BUS` | PHONE/SPEECH | ASSISTANT/SPEECH | MEDIA/MUSIC | NAVIGATION（不占媒体总线） |

### 7.2 二开侧切法

```
CarPlay Media (Core: AudioStream → MediaSink)
      ↓
UpstreamMediaSinkBridge（旁路观测，不解码）
      ↓
CarMediaAdapter / CarAudioAdapter  (:car:api)
      ↓
车机音频系统（Flyme 导航总线 / OSN 音源切换 / DSP）
```

- **路由决策**由 `CarAudioAdapter.routeFor(channel)` 提供，纯查询无副作用；
- **流生命周期**由 `CarMediaAdapter.onAudioStreamActive(...)` 广播，供车机驱动
  音源切换、桌面歌词、媒体卡片；
- PCM 解码与播放**仍在上游**，因为它是通用的，且改它风险最高。

`advancedAudioChannelMapping` 开关由
`CarAudioAdapter.supportsAdvancedChannelMapping()` 决定，
最终传给 upstream `AndroidMediaSink` 构造参数——**语义与上游完全一致**。

---

## 8. Media 架构

```
CarPlay Screen (Core: ScreenStream → MediaSink)
      ↓
UpstreamMediaSinkBridge（只覆盖 4 个生命周期方法，`by delegate` 委托其余全部）
      ↓
CarPlayMediaSink（渲染槽位）        ← 可替换
      ↓
默认: AndroidMediaSink (MediaCodec → Surface)
未来: 车机硬件视频层 / 外接 DSP
```

替换渲染实现的做法：

```kotlin
val bridge = CarPlaySessionBridge(
    ...,
    sinkFactory = CarPlayMediaSinkFactory { request -> MyFlymeHardwareSink(request) },
)
```

`MediaSink` 有 10 个方法，`UpstreamMediaSinkBridge` 用 Kotlin 接口委托
（`class X(...) : MediaSink by delegate`）只覆写
`onVideoCodec` / `onScreenStreamActive` / `onAudioStarted` / `onAudioStopped`。
**帧与 RTP 数据路径完全不接触**，因此不可能影响已经正常工作的播放链路。
所有对 `CarMediaAdapter` 的调用都包在 `runCatching` 里——车机侧适配器异常
不允许打断播放。

---

## 9. Input / 方控架构

```
CarPlay Input (Core: AirPlay HID 报文)
      ↑
CarPlaySessionBridge.forwardToCarPlay   ← CarKeyHidMap（纯映射）
      ↑                                    → sendMedia / sendKnob / invokeSiri
CarInputRouter (:car:steering)   ← 键码映射 / 抖动抑制 / 分发
      ↑
CarInputAdapter (:car:api)       ← 车机按键来源
      ↑
KeyEvent / 车机专有按键服务 / CAN 总线
```

现状：

- `AndroidCarInputAdapter` 已把 `KeyEvent` 媒体键翻译成 `CarInputEvent`，
  提供 `handleKeyEvent(event)` / `handleKeyCode(...)` 两个入口供产品 App 驱动；
- ⚠️ **走 `KeyEvent` 的平台，必须由宿主 Activity 覆写 `dispatchKeyEvent`**：
  `MainActivity.dispatchKeyEvent` → `CarAppController.handlePlatformKeyEvent`
  → `AndroidCarInputAdapter.handleKeyEvent`。适配器自己没有 Activity，拿不到
  这个回调。**漏了这一环的后果极具迷惑性**：listener 挂得好好的、编译全绿、
  日志一条错都不报，就是按键没反应（2026-09-28 修好）。
  Ecarx 那条路自给自足（`OneOsKeyInputBridge.onRawKey` → `handleKeyCode`），不受影响；
- `CarInputRouter` 已实现**可替换键码表**（`CarKeyMap`）与**抖动抑制**
  （默认 120ms 静默窗口；车机方控电气抖动通常 50~100ms，人手真实间隔 ≥150ms），
  但 ⚠️ **全仓没有任何构造点，目前是死代码**。按键现在由 `AndroidCarInputAdapter`
  直接 `dispatch`，**没有经过 Router 的抖动抑制**。接线属功能增强（非缺陷修复），
  需要把 Router 插到「适配器 → listener」之间；`:car:app` 已经依赖 `:car:steering`，
  分层上不阻塞，缺的只是接线决策；
- `CarSessionBridge` 已把按键事件转发为 `CarSessionEvent.InputReceived`；
- **`CarKey` → CarPlay HID 已实现**（原计划的 Phase 2）：
  - `CarKeyHidMap` 是纯映射（不引用 upstream 类型，可单测）；
  - `CarPlaySessionBridge.forwardToCarPlay` 负责执行，**会话来源是公开回调
    `AirPlaySessionListener.onSessionActive`** —— upstream 的 `CarPlayController`
    只转发了 `sendTouch`，`activeSession` 又是 `private var`，
    走回调是零上游改动拿到会话的唯一入口；
  - 只转发按下（upstream 的 `sendMedia` / `sendKnob(momentary = true)` 自带抬起）；
  - 音量键**不转发也不消费**，仍归车机功放；
  - **HOME / BACK 按来源区分**（2026-09-28）：只有 `CarInputSource.STEERING_WHEEL`
    （Ecarx 方控）才转发。基线平台经 `dispatchKeyEvent` 进来的是 `PANEL` 来源，
    **不转发也不消费** —— 否则事件被消费后不再流向 `super`，车机自己的
    返回/主页硬键失效，**用户会被困在 CarPlay 画面里**。
    见 `CarKeyHidMap.forwardsNavigationKeys`；
  - 可用设置项「方向盘与按键 → 转发方向盘按键」关闭。

车机专有按键**不放进 Core**：Flyme/OSN 的方控往往不走 `KeyEvent`，
这类差异一律在 `:car:platform-*` 里实现，通过 `handleKeyCode` 统一出口进入路由。

---

## 10. Flyme / OSN / Ecarx 适配方式

### 10.1 继承而非重写

`AndroidCarPlatform` 的所有适配器都是 `open val` + `by lazy`。
车机适配**继承**它，只覆写有差异的域：

```kotlin
class FlymeCarPlatform(context: Context) : AndroidCarPlatform(context) {
    override val id = "flyme"
    override val displayName = "Flyme Auto"
    override val audio: CarAudioAdapter by lazy { FlymeCarAudioAdapter() }   // 只覆写这一项
}
```

### 10.2 当前状态

| 车机 | 模块 | 状态 |
| --- | --- | --- |
| 通用 Android | `:car:platform-android` | ✅ 已实现基线 |
| Flyme Auto | `:car:platform-flyme` | 🚧 骨架（全部继承默认行为，见模块 README 待实现清单） |
| Ecarx / 吉利 OneOS | `:car:platform-ecarx` | ✅ 方控已接 OneOS KeyInput；其余域继承默认（见模块 README） |
| OSN | — | ⬜ 未创建，按 `platform-ecarx` 模板新增 |

**Phase 1 刻意不实现任何 Flyme 专有能力**——先把接缝建对，
功能在 Phase 2 按 `car/platform-flyme/README.md` 的清单逐项填。

### 10.3 配置分离

车机差异中有相当一部分是**纯配置**（键码、安全区尺寸、物理尺寸、总线 ID），
不需要写代码：

```
configs/default/     所有车机共用的默认值
configs/flyme/       Flyme 覆盖项
configs/osn/         OSN 覆盖项
```

当前 `configs/` 只有说明文件，尚未定义具体格式——**等第一个真实需求出现时再定格式**，
避免现在发明一套没人用的配置 schema。

---

## 11. 上游同步方式

### 11.1 分支模型

```
upstream/master  ──●──●──●──●──────►  官方持续更新
        │                   │
        │  git merge        │  git merge
        ▼                   ▼
      master ──●────────────●────────►  master = 上游镜像（只允许 fast-forward）
        │                   │
        │  git merge        │  git merge
        ▼                   ▼
        dev ──●──●──●───────●────────►  dev = 二开主线（我们的代码在这里）
```

| 分支 | 用途 | 规则 |
| --- | --- | --- |
| `master` | 上游镜像 | 只接受 `--ff-only` 合并，**永不直接提交** |
| `dev` | 二开主线 | 所有二开提交在这里；同步时把 `master` 合进来 |

这样 `git diff master..dev --stat` 就等于"我们的全部改动"，一眼可见。

### 11.2 为什么选 merge 而不是 rebase

| | merge（推荐） | rebase |
| --- | --- | --- |
| 提交历史 | 保留上游原始 commit hash | 每次同步重写我们所有提交的 hash |
| 冲突解决 | 只需解决一次，下次不再重复问 | 每次同步都要重放，可能反复解同一处 |
| 已推送分支 | 安全 | 需要 `--force-with-lease`，多人协作危险 |
| 可追溯 | `git log --graph` 能看出"哪批改动来自上游" | 看不出边界 |

结论：**日常同步用 merge**。rebase 只用于把本地尚未推送的小改动整理成整洁提交
（`git rebase -i`），不用于跨上游同步。

### 11.3 同步操作

`scripts/sync-upstream.ps1` 已把流程脚本化，等价于：

```powershell
git fetch upstream
git checkout master
git merge --ff-only upstream/master        # master 保持与上游一致
git checkout dev
git merge master                            # 把上游改进带进二开主线
# 可能冲突的文件（见 PATCHES.md 汇总表，当前 3 个）：
#   settings.gradle.kts                          PATCH-0001
#   shared/.../network/LocalOnlyHotspotManager.kt PATCH-0004
#   shared/.../orchestration/CarPlayController.kt PATCH-0005
.\gradlew.bat :shared:testDebugUnitTest :mobile:assembleDebug :automotive:assembleDebug
```

---

## 12. 禁止直接修改的目录

| 路径 | 说明 |
| --- | --- |
| `shared/**` | CarPlay 协议核心。见 §13 例外流程 |
| `common/**` | 上游宿主 UI / 持久化 |
| `mobile/**` `automotive/**` | 上游应用壳 |
| `gradle/**` `gradlew` `gradlew.bat` | 构建基建 |
| `build.gradle.kts` | 根构建脚本 |
| `README.md` `README.zh-CN.md` `LICENSE` `asset/**` | 上游文档与资源 |
| `.github/**` | 上游 CI |
| `settings.gradle.kts` | **仅允许**在文件末尾追加 `car/*` 的 include 块（PATCH-0001） |

另：`local.properties` 是本地文件且已被 `.gitignore` 忽略，可以自由创建。

---

## 13. 必须修改 upstream 时如何记录

**流程**：

1. **先尝试不改**。确认差异无法通过 `CarPlatform` 适配器、
   `CarPlayMediaSink` 槽位、`CarSessionOptions` 配置表达。
2. 在 `PATCHES.md` **登记**：文件、行号锚点、原因、改动内容、
   是否可用 Adapter 替代、对应 upstream commit、同步注意事项。
3. 改动必须满足三条硬要求：
   - **行为等价**：默认参数下与改动前逐字节一致；
   - **最小化**：优先"新增可选构造参数"这类 hunk，而不是改逻辑；
   - **锚点稳定**：避开上游高频改动区域。
4. 应用后在 `PATCHES.md` 把状态从 `PROPOSED` 改为 `APPLIED`，
   并记录应用到的 commit。
5. 同步上游后逐个复核 patch 是否仍然必要——**能撤销就撤销**。

**禁止**：悄悄改、顺手重构、为了"更优雅"而改上游代码、
把二开业务逻辑写进 `shared/`。

---

## 14. 治理文件

| 文件 | 作用 |
| --- | --- |
| `ARCHITECTURE.md` | 本文。架构与边界，改架构先改这里 |
| `UPSTREAM.md` | 上游地址、当前同步 commit、二开版本、同步方法、注意事项 |
| `PATCHES.md` | 所有对上游的改动登记（当前 3 项 APPLIED + 2 项 PROPOSED） |
| `scripts/sync-upstream.ps1` | 一键同步上游到 `dev` |
| `scripts/footprint.ps1` | 审计当前改动足迹（哪些文件偏离上游） |
| `scripts/install-mfi-identity.ps1` | 安装离线 MFi 身份资产到 APK / 设备 |
