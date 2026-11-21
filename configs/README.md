# configs/ — 车机部署配置

## 目的

把"车机差异"里属于**纯数据**的部分（键码表、安全区尺寸、物理尺寸、音频总线 ID、
MFi 目标）从代码里拿出来，做到**现场改配置不改代码、不重新编译**。

## 现状（Phase 1：只定义结构，未定义格式）

本目录目前**只有说明文件，没有具体配置格式**。这是刻意的：

> 现在还没有任何一个真实配置项被绑定到代码。
> 在没有真实需求的情况下发明一套 schema，只会得到一个没人用、
> 且以后大概率要推翻的格式。

**等第一个真实需求出现时再定格式**，并且：

1. 格式定义写进本文件；
2. 加载代码放在 `:car:platform-*` 或产品 App 模块（**不放 `:car:api`**）；
3. 在 `car/README.md` 登记。

## 目录约定

```
configs/
├── default/     所有车机共用的默认值（也是"没有配置时的回落基准"）
├── flyme/       Flyme Auto 覆盖项
├── ecarx/       Ecarx / 吉利 OneOS 覆盖项（候选项最多，见其 README）
└── osn/         OSN 覆盖项（未启用）
```

约定：**只写覆盖项**，不复制 `default/` 的内容。
加载顺序为 `default/` → `<platform>/`，后者覆盖前者。

## 候选配置项（按出现概率排序）

以下是**已识别但尚未绑定**的配置点，作为将来定义格式时的输入：

| 候选项 | 对应适配器方法 | 为什么适合做成配置 |
| --- | --- | --- |
| 方控键码表 | `CarInputAdapter.handleKeyCode` / `CarKeyMap` | 同款车机不同批次键码可能不同；`CarKeyMap` 已预留可替换入口 |
| 安全区（上/下/左/右 px） | `CarDisplayAdapter.safeArea` | 不同配置的装饰件/顶栏高度不同，且常需现场微调 |
| 物理尺寸（宽 mm） | `CarSessionOptions.widthPhysicalMm` | 影响 iPhone 侧 CarPlay 布局，实测调整比算出来的准 |
| 分辨率缩放档（0.3x~1.0x） | `CarSessionOptions.displayScaleTenths` | 与面板/用户偏好相关 |
| 音频总线 ID | `CarAudioAdapter.routeFor(...).busId` | 车机专有总线编号，属于部署数据 |
| MFi 目标与 I2C 路径 | `CarSessionOptions.mfiTarget` / `linuxI2cPath` | 硬件形态相关 |
| 热点模式与凭据 | `CarSessionOptions.hotspotMode` 等 | 现场网络环境相关 |

## 与 upstream 设置页的关系

upstream `common/AirPlayPersistence.kt` 已有一套 SharedPreferences 设置
（`xcertplay_airplay` 文件，约 60 个键）。二开侧**不替换、不修改**它。

关系：

```
configs/            →  出厂/部署默认值（随车机出货，只读）
AirPlayPersistence  →  用户在设置页的运行时选择（可写）
        ↓ 合并（运行时值优先）
CarSessionOptions   →  :car:core-bridge 翻译为 upstream 配置对象
```

即：`configs/` 提供"这台车的默认值"，用户在设置页的改动覆盖它。
两者都不直接触碰 upstream 的 `CarPlayRuntimeConfig` / `AirPlayConfig`，
统一由 `CarPlayConfigBuilder` 翻译——保持单一翻译点。
