# car/ — xcertplay 二次开发扩展层

本目录下**全部文件都是新增的**，上游 xcertplay 没有这个路径。
因此 `git merge upstream/master` 在这里**不可能产生冲突**。

## 模块一览

| 目录 | Gradle 模块 | namespace | 依赖 | 职责 |
| --- | --- | --- | --- | --- |
| `api/` | `:car:api` | `com.dbtool.carplay.api` | **无** | 契约层：`CarPlatform` + 8 个域适配器 + 会话状态/控制器 + 设置模型 |
| `core-bridge/` | `:car:core-bridge` | `com.dbtool.carplay.bridge` | `:car:api`, `:shared` | 唯一接触上游的模块：状态映射、配置翻译、会话桥接、方控 → CarPlay HID 转发、持久化 |
| `platform-android/` | `:car:platform-android` | `com.dbtool.carplay.android` | `:car:api` | 通用 Android 基线适配（所有车机的父类） |
| `platform-flyme/` | `:car:platform-flyme` | `com.dbtool.carplay.flyme` | `:car:api`, `:car:platform-android` | Flyme Auto 差异实现（骨架） |
| `platform-ecarx/` | `:car:platform-ecarx` | `com.dbtool.carplay.ecarx` | `:car:api`, `:car:platform-android` | Ecarx / 吉利 OneOS 差异实现（**方控已接 OneOS KeyInput**） |
| `steering/` | `:car:steering` | `com.dbtool.carplay.steering` | `:car:api` | 方控键码映射、抖动抑制、事件路由 |
| `ui/` | `:car:ui` | `com.dbtool.carplay.ui` | `:car:api` | Design System + 组件库 + 5 个界面（Compose） |
| `app/` | `:car:app` | `com.dbtool.carplay.app` | `:car:ui` `:car:core-bridge` `:car:platform-*` | 可运行产品 App：宿主、权限、系统栏、装配 |

依赖方向与约束详见根目录 `ARCHITECTURE.md`。

## 两条最重要的规则

### 1. 只有 `:car:core-bridge` 允许 import `com.shilapi.xcertplay.*`

其他任何 `car/*` 模块都不允许出现 `com.shilapi.xcertplay` 的 import。
上游 API 变化时，需要跟进的代码只有 `:car:core-bridge` 一个模块，
且集中在：

- `CarPlayStatusMapper.kt` —— `CarPlayStatus` 取值变化（穷尽 `when` 会编译报错提醒）
- `CarPlayConfigBuilder.kt` —— `CarPlayRuntimeConfig` / `AirPlayConfig` 字段变化
- `CarAppServices.kt` —— upstream 类型出现在构造函数签名时的封装点
- `CarPlaySessionBridge.kt` —— `AirPlaySession` / `AirPlaySessionListener` 的方法变化
  （方控 → HID 的转发挂在这里，靠 `onSessionActive` 拿到会话）
- `CarKeyHidMap.kt` —— `AirPlayHid` 的媒体/旋钮描述符或报告编码方式变化

自查命令：

```powershell
Select-String -Path car\*\src\main\java\com\dbtool\carplay\*\*.kt -Pattern "com\.shilapi\.xcertplay"
# 期望：只命中 car\core-bridge\ 下的文件
```

### 2. 应用层对上游的可见依赖为零

`:car:app` 只依赖 `:car:ui` / `:car:core-bridge` / `:car:platform-*` / `:car:api`，
**不依赖** `:shared` `:common` `:mobile` `:automotive`。

原因是 `:car:core-bridge` 用 `implementation(project(":shared"))`：
`CarPlayController`、`AirPlayIdentity`、`MediaSink` 这些类型
**不在应用层的编译类路径上**，想直接 new 都 new 不了。
所有对上游的访问都经过 `CarAppServices` 这一个入口。

这带来两个好处：
- 上游改宿主 UI（`common/`）不会影响我们的 App 构建；
- 不会把上游那个 3427 行的 Activity 及其清单入口带进产品包。

## 当前进度

| 能力 | 状态 |
| --- | --- |
| 契约层 `:car:api` | ✅ 完成（8 个域适配器 + 会话状态 + 设置模型） |
| Core 桥接 `:car:core-bridge` | ✅ 完成（不改 Core 搭起完整会话 + 自有持久化） |
| 通用 Android 适配 | ✅ 完成（基线实现） |
| 方控路由与仲裁 | ⚠️ 代码完成但**未接线**（`CarInputRouter` 全仓无构造点 → 抖动抑制实际未生效） |
| 基线平台按键接线 | ✅ 完成（`MainActivity.dispatchKeyEvent` → `CarAppController.handlePlatformKeyEvent` → `AndroidCarInputAdapter.handleKeyEvent`）。2026-09-28 修复：此前这条链断在第一环，**listener 挂了、编译全绿、按键全死** |
| Ecarx 方控接入 | ✅ 完成（OneOS KeyInput binder 直连，不需要 SDK 或 root；已驱动 CarPlay HID） |
| Design System | ✅ 完成（浅/深两套 Colors + 插画色板 / Typography / Dimensions / Shapes / 自绘图标） |
| 组件库 | ✅ 完成（Card / StatusCard / SettingItem / DeviceCard / Button / Dialog / 分段选择 / 连接插画） |
| 界面 | ✅ 5 个：主页（连接状态主视觉）/ CarPlay 画面 / 设备 / 设置（索引+分区）/ 关于 |
| 连接动画 | ✅ 完成（三图标沿连接线投递入卡 + 拖影 + 粒子流动与提亮 + 呼吸光晕 + 手机微动 + 屏幕噪点；纯 Compose 绘制） |
| 动画状态机 | ✅ 完成（`playNormal()` 投递就位并回调跳转 / `playStuck()` 入口反复弹射，模拟 MFi 卡死） |
| 浅色 / 深色 | ✅ 两套完整实现，跟随系统；深色有独立插画色板 |
| 可运行宿主 App | ✅ 完成（权限、系统栏、进程级会话） |
| Flyme 差异实现 | 🚧 骨架（全部继承默认） |
| Ecarx 差异实现 | ✅ 方控已接 OneOS KeyInput；其余域继承默认 |
| OSN 差异实现 | ⬜ 未创建 |
| 自定义 AirPlay 图标（选图+裁剪） | ⬜ 未实现（原上游有，属已知缺口） |
| 分通道音量 | ⬜ 未实现（需要 Core 暴露接口） |
| `CarKey` → CarPlay HID | ✅ 完成（`CarKeyHidMap` 纯映射 + `CarPlaySessionBridge` 转发）；HID index 语义待真机确认 |

## 已知功能缺口（相对上游 UI）

新 UI 没有把上游设置页的每一项都搬过来，以下三项是**明确未实现**，不是显示错误：

| 缺口 | 原因 | 补偿 |
| --- | --- | --- |
| 自定义 AirPlay 图标（选择图片 + 1:1 裁剪） | 上游用独立的 `ImageCropActivity` + `SquareCropView`，在 `:common` 里；复用会把上游宿主 UI 拖进产品包 | 使用内置图标；功能列入下一阶段 |
| 顶部系统状态栏 / 底部空调控制条 | 按需求明确**不做**：那属于车机系统层 UI，不是 CarPlay 接收端的职责；且温度/风量需要 CAN 适配 | 主页只保留连接状态主视觉 |
| 分通道音量（媒体/导航/Siri） | 上游 Core 未暴露各通道音量控制接口 | 音频页明确标注"暂不支持" |
| 安全区可视化编辑器 | 上游 `SafeAreaEditorView` 同理位于 `:common` | 保留安全区数值设置 + 一键重置 |

## 未建模块的处理原则

`modules/audio` `modules/media` `modules/microphone` `modules/navigation`
`modules/diagnostics` **刻意没有提前创建空模块**：

- 它们的**契约**已经在 `:car:api` 里定义好了（接缝已留）；
- 默认实现已在 `:car:platform-android` 里可用；
- 单独建模块的价值只在于"放车机专有的替代实现"，
  在**没有这个需求之前建空模块只是增加维护面**。

第一个真实需求出现时，按 `platform-flyme/README.md` 的模板新增即可。

## 构建与运行

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"

# 二开全部模块
.\gradlew.bat :car:app:assembleDebug

# 上游 4 个模块（不受影响，可单独构建）
.\gradlew.bat :shared:testDebugUnitTest :mobile:assembleDebug :automotive:assembleDebug
```

### 产物命名与版本号

产品名 **DBPlay**。版本号规则：从 `0.0.1` 起步，每次正式构建**末位 +1**
（`0.0.1 → 0.0.2 → 0.0.3 …`），`versionCode` 同步 +1。

发布时**只改 `car/app/build.gradle.kts` 顶部这两行**：

```kotlin
val dbplayVersionName = "0.0.1"
val dbplayVersionCode = 1
```

APK 输出名会自动变成 `DBPlay-<版本号>-<yyyyMMddHHmm>.apk`，例如
`DBPlay-0.0.1-202609281454.apk`。改名逻辑在同一个文件的
`androidComponents.onVariants { … output.outputFileName.set(…) }` 里，
**只改文件名、不改产物内容**。

⚠️ 时间戳取的是 **Gradle 配置阶段**的时刻。**配置缓存命中时配置阶段被跳过**，
时间戳会沿用上一次的值——同一版本号连构两次，第二次名字不变（APK 内容是新编的）。
正常发布流程不受影响（改版本号必然让配置缓存失效）。需要"同版本 + 新时间戳"时：

```powershell
.\gradlew.bat :car:app:assembleDebug --no-configuration-cache
```

安装（`applicationId = com.dbtool.carplay`，与上游 `com.shilapi.xcertplay`
不同，两者可共存，便于真机对比）：

```powershell
# 文件名带版本与时间戳，按实际产物替换
adb install -r "car\app\build\outputs\apk\debug\DBPlay-0.0.1-202609281454.apk"
```

### UI 设计规范

界面的视觉依据是仓库根目录的 `DBPlay-UI-DESIGN.md`（色彩 / 间距 / 圆角 /
字体 / 组件规范）。**任何界面改动先看那份文档**；改视觉先改文档、再改
`car/ui/src/main/java/com/dbtool/carplay/ui/theme/` 下的 Token。
界面代码里出现字面色（`Color(0xFF…)`）或裸 `16.dp` / `15.sp` 视为违规。

### 应用图标

启动图标取自 `asset/xcertplay.png`。源图是一张**已经画好圆角底板 + logo 的整图**，
不能整张当自适应图标的某一层用：自适应图标是 108dp 画布，系统遮罩只保证中间 **72dp**
可见（中心 **66dp** 圆是安全区），而源图里 logo 占画布 68.6%×77.4%，直接铺满会被上下切掉。

生成脚本 `_scratch/make-icon2.py` 把源图拆成两层，按密度输出到
`car/app/src/main/res/mipmap-*/`：

- `ic_launcher_background_dbplay.png` —— 抠掉 logo 后用三次多项式拟合底板渐变，向四周铺满（不带圆角，遮罩交给系统）；
- `ic_launcher_foreground_dbplay.png` —— 抠出的 logo，等比缩到画布 **52%** 后居中
  （与上游图标实测的 52.1% 同比例，落在 66dp 安全区内）。

再由 `mipmap-anydpi-v26/ic_launcher{,_round}.xml` 引用这两层。

**换图只要替换 `asset/xcertplay.png` 后重跑生成脚本**，不必改代码。
这是零上游足迹的覆盖手法：资源合并优先级 app > library，`shared/` 里的
`@mipmap/ic_launcher` 会被本模块同名资源顶掉；minSdk 28 ≥ 26，`anydpi-v26` 必定命中。
