# :car:platform-flyme

Flyme Auto / Flyme 车机适配层。

## 现状（Phase 1：骨架，未实现车机专有能力）

本模块当前**只是一个可编译的骨架**，`FlymeCarPlatform` 全部继承
`:car:platform-android` 的默认行为。这是刻意的：

> Phase 1 的目标是"把接缝留好"，不是"提前把 Flyme 功能写出来"。
> 只要接缝正确，后续加功能不需要改任何 Core 代码。

设计上遵循一条硬约束：**本模块不依赖 `:shared` / `:common`**。
即车机适配无法偷偷绕过契约层去动 CarPlay 协议核心——编译期就挡住了。

## 待实现清单（按对接难度排序）

每一项都只需覆写对应适配器的一个方法，Core 不动：

| 域 | 文件 | 需要覆写的方法 | 说明 |
| --- | --- | --- | --- |
| 音频 | `FlymeCarAudioAdapter` | `routeFor()` / `supportsAdvancedChannelMapping()` | Flyme 导航/媒体分总线；确认车机是否有独立导航通道 |
| 显示 | `FlymeCarDisplayAdapter` | `safeArea()` | Flyme 顶栏/底栏高度；是否有副驾屏 |
| 方控 | `FlymeCarInputAdapter` | `handleKeyCode()` | 方控是否走 `KeyEvent`，还是 Flyme 专有按键服务 |
| 系统 | `FlymeCarSystemAdapter` | `supportsRoot()` / `requiresVpnConsent()` / `systemProperty()` | Flyme root 入口、VPN 是否预授权 |
| 媒体 | `FlymeCarMediaAdapter` | `onAudioStreamActive()` 等 | 桌面歌词 / 媒体卡片 / 音源切换 |
| 麦克风 | `FlymeCarMicrophoneAdapter` | `preferredSource()` | 车机 mic 通道与 ANC 抢占 |
| 定位 | `FlymeCarNavigationAdapter` | `start()` / `usesNonWgs84Coordinates()` | Flyme 定位服务；坐标系是否为 GCJ-02 |

## 新增一个车机（OSN / Ecarx）的步骤

1. 复制本模块目录为 `car/platform-<vendor>/`。
2. 改 `build.gradle.kts` 的 `namespace` 与 `settings.gradle.kts` 的 include。
3. 新建 `<Vendor>CarPlatform` 继承 `AndroidCarPlatform`，只覆写有差异的域。
4. 在 `configs/<vendor>/` 放该车机的默认配置。
5. **不要**修改 `:shared` / `:common` / `:mobile` / `:automotive` 的任何文件。

如果发现某个差异点无法通过覆写实现，说明 Core 缺一个接缝——
此时走 PATCHES.md 的流程，而不是在 Core 里直接改。
