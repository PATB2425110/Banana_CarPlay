# :car:app

可运行的产品宿主应用（`applicationId = io.dushu.car` —— **刻意占用帆书车机版的包名**，
这样车机上点「帆书」卡片才会打开本应用；理由与代价见 `build.gradle.kts` 里
`applicationId` 上方那段注释）。

## 职责

刻意只做三件事，**不含任何 CarPlay 业务逻辑**：

1. **权限申请** —— VPN / 无线 / 麦克风 / 定位。这是唯一必须在 Activity 层
   做的事（Android 的权限对话框只能由 Activity 发起）。
2. **系统栏控制** —— 隐藏状态栏 / 导航栏。车机上必须做，否则画面会被系统栏挤压。
3. **装配** —— 安装 `CarPlatform`、创建进程级会话控制器、把 UI 挂到 Compose。

## 为什么"装配"放在这里而不是更上层

```
CarPlayApplication
├── CarPlatformRegistry.install(AndroidCarPlatform(this))   ← 车机适配的唯一选择点
├── CarAppServices(this)                                    ← 上游访问的唯一入口
└── CarAppController(app, services)                         ← 进程级会话中间层
```

- **车机平台**：换 Flyme / OSN 只改 `install(...)` 这一行的实现类，上层不动。
- **上游访问**：`:car:app` 的编译类路径上没有 `:shared`，
  所有对 `CarPlayController` / `MediaSink` / `AirPlayIdentity` 的访问
  都被 `CarAppServices` 挡住。`CarAppServices` 能编译过，
  就说明应用层不可能意外引入 upstream 依赖。
- **会话控制器是进程级单例**：CarPlay 会话必须跨 Activity 重建、
  跨页面切换存活，所以它挂在 `Application` 而不是 Activity 上。
  这与上游用 `CarPlayBackgroundSession` 保住 controller 的思路一致，
  但更简单——没有"谁负责回收"的歧义。

## 为什么用 `android:screenOrientation="landscape"`

车机中控是固定横屏。跟随传感器翻转会导致 CarPlay 画面重新握手，
代价远大于收益。

同时 `configChanges` 覆盖了 `screenSize` / `density` / `fontScale` / `uiMode`：
这些变化由 Compose 自行重排，**不应重建 Activity**——重建会打断 CarPlay 会话。

## 与上游的关系

| 上游文件 | 处置 |
| --- | --- |
| `:shared`（协议栈、VpnService） | 通过 `:car:core-bridge` 间接使用，宿主清单会合并 `CarPlayVpnService` |
| `:common`（宿主 UI、持久化） | **完全不用**。设置/身份/配对记录由 `:car:core-bridge` 用自有 prefs 文件实现 |
| `:mobile` / `:automotive`（上游应用壳） | **完全不用**，保持原样作为行为基准 |
| `com.shilapi.xcertplay.shared.MyCarAppService` | 用 `tools:node="remove"` 从合并清单移除（与本产品无关的 Car App 占位） |

> 上述 `tools:node="remove"` 写在**本模块的清单**里，没有修改上游任何文件。
