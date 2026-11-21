# configs/ecarx

Ecarx / 吉利 OneOS 车机覆盖项。

当前**只有本说明文件，没有配置文件**——`configs/` 整体还没定义格式（见
`configs/README.md` 的 Phase 1 说明）。但 Ecarx 是**第一个有真实候选项的车机**，
所以这里把候选项列清楚，作为将来定格式时的输入。

**注意**：配置只放**数据**。调用 OneOS binder 的逻辑（`bindService`、`transact`）
必须留在 `car/platform-ecarx/` 的代码里，不能放进配置。

## 候选配置项

这些值目前都是 `car/platform-ecarx/` 里的 Kotlin 常量，且**都需要真机确认**
（见模块 README 的"三个尚未在真机确认的点"）。它们适合做成配置的原因很一致：
**同款车机不同批次/车型会不一样，而现场改配置比重新编译现实得多。**

| 候选项 | 当前落点 | 为什么适合配置 |
| --- | --- | --- |
| 方控键码表 | `EcarxKeyCodes` | 同款车机不同批次键码可能不同；`configs/README.md` 已把"方控键码表"列为第一候选 |
| 订阅键码集合 | `EcarxKeyCodes.SUBSCRIBED` | 只订阅会映射的键，避免音量旋钮微调刷爆 binder；哪些键存在因车型而异 |
| `registerListener` 的 clientId | `OneOsKeyInputBridge.DEFAULT_CLIENT_ID`（`io.dushu.car`） | 车机对能接方控的第三方有白名单；不同车机的白名单包名不同 |
| 媒体控制器索引 | `OneOsKeyInputBridge.CONTROL_INDEX_MEDIA`（`2`） | 焦点门禁用；不同车机的媒体页索引可能不是 `2` |
| `getService` 的 TX 号 | `OneOsServiceManagerProxy.TX_GET_SERVICE` / `_LEGACY` | OneOS 版本间不一致（见模块 README），现场要能试 |
| 音量键语义 | 未实现 | 调车机功放还是调 CarPlay，取决于整车音频路由，属部署数据 |

## 建议的覆盖粒度

按 `configs/README.md` 的约定，本目录**只写覆盖项**，不复制 `default/`。
将来若出现"领克某车型"与"吉利某车型"键码不同的情况，倾向在本目录下再分层
（例如 `ecarx/geely/` `ecarx/lynk/`），而不是把差异堆进一个文件——
但**在真正遇到第二个车型之前不预先分层**，理由同 `configs/README.md`：
没有真实需求就发明结构，只会得到一个以后要推翻的格式。
