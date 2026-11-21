# :car:platform-ecarx

Ecarx / 吉利 OneOS 车机适配层。

## 现状

**方控与媒体卡片已接入**；其余域沿用 `:car:platform-android` 的默认实现。

| 域 | 状态 | 说明 |
| --- | --- | --- |
| 方控 `input` | ✅ 已实现 | OneOS KeyInput binder 直连，见下 |
| 媒体 `media` | ✅ 已实现 | **伪装帆书**上报曲目给车机媒体中心 + 本地 HTTP 喂封面，见下 |
| 系统 `system` | ✅ 已实现 | 平台身份报 `ecarx` 而非 `android` |
| 音频 / 显示 / 麦克风 / 定位 / 诊断 | ⬜ 继承默认 | 没有已验证的差异。不凭猜测写实现 |

硬约束同 `:car:platform-flyme`：**本模块不依赖 `:shared` / `:common`**，
编译期就挡住"绕过契约层去动 CarPlay 协议核心"。

## 方控为什么不能走 `KeyEvent`

车机 ECU 的方控按键**不走** Android `KeyEvent`。它们是车机自己的 `swsm`
键码空间（20 万量级），由 OneOS 的 KeyInput 服务通过 binder 推给注册过的应用。
所以 `:car:platform-android` 里那张 `KeyEvent.KEYCODE_*` 映射表**一个也命中不了**。

链路：

```
bindService(com.geely.service.oneosapi/.OneOSApiService)   → IServiceManager
        ↓ getService(8)                                     → IInputManager
        ↓ registerListener(binder, clientId, keyCodes)       → 声明关注哪些键码
IInputListener.onKeyCodeEvent / onShortClick                → 车机把方控按键推过来
        ↓
EcarxCarInputAdapter.onRawKey  → 门禁 → keyCodeToCarKey → handleKeyCode()
        ↓
CarInputEvent(key, action, STEERING_WHEEL)
        ├─→ :car:steering 路由 → UI（诊断显示）
        └─→ :car:core-bridge 的 CarKeyHidMap → AirPlaySession.sendMedia / sendKnob
                                             → CarPlay HID（iPhone 真的响应）
```

后半段（`CarInputEvent` → HID）**不在本模块**：本模块只负责"把车机按键采集出来"，
送进 CarPlay 是会话层的活，见 `:car:core-bridge` 的 `CarKeyHidMap`。

全程 binder 直连，**不依赖任何 OneOS SDK**，也不需要系统签名或 root。

## 键码表

见 `EcarxKeyCodes`。值取自 DBTOOL2.0 在吉利车机上的实测常量：

| 键码 | 语义 |
| --- | --- |
| 200085 | 播放 / 暂停 |
| 200087 | 下一首 |
| 200088 | 上一首 |
| 200024 | 音量 + |
| 200025 | 音量 − |

只订阅这五个。不要图省事传空数组"全都要"——那会把音量旋钮的每一次微调、
乃至车机内部按键都推过来，既浪费 binder 往返，也让诊断日志不可读。

## 三个尚未在真机确认的点

这三处都不是"随手写的"，而是**移植时找不到可信来源**，因此显式标注，
真机排查时优先看它们：

| 点 | 现状 | 怎么调 |
| --- | --- | --- |
| `getService` 的 TX 号 | 强类型 `TX=1` 与裸 `TX=2` 两种都试，成功即用 | 看日志里"走强类型/走裸 transact 命中"哪条出现 |
| `onKeyCodeEvent` 的 action 值 | 推断为 `0=DOWN / 1=UP` | 若"按下即抬起"或完全无响应，改 `OneOsKeyInputBridge.ACTION_*` |
| `registerListener` 的 clientId | 默认 `io.dushu.car` | 车机对能接方控的第三方有白名单；换自己包名大概率注册不上 |

第一点的由来值得记一笔：DBTOOL2.0 的源码树里带着
`com.geely.lib.oneosapi.IServiceManager`（Java AIDL Stub），它的
`onTransact` 写明 `case 1` 是 `getService`、`case 2` 是 `addService`；
但同一个工程的 `OneOSClient` 取 KeyInput binder 时用的是 `transact(2, ...)`。
两者自相矛盾，说明 TX 号在不同 OneOS 版本上不一致。所以本模块不把任一方
固化成唯一实现，而是两种都试。

## 真机排查清单

按顺序看 logcat（tag `Ecarx-OneOS` / `Ecarx-Input`）：

1. `bindService(...) ok=true` —— 服务在不在、能不能绑。
2. `getService(8) 走强类型 TX=1 命中` 或 `走裸 transact TX=2 命中` —— 哪条路通。
3. `registerListener(clientId=..., keys=5) ok=true` —— 注册成功。
4. 按方控键应出现 `方控 key=PLAY_PAUSE raw=200085 action=... consumed=...`。
5. 每个会话里**首次**成功转发会有一条（tag `session`，只记一次以免刷屏）：
   `steering wheel driving CarPlay HID: key=PLAY_PAUSE -> Media(index=2)`。
   index 是**结合播放状态算出来的**：在播 ⇒ `2`(Pause)，已停 ⇒ `1`(Play)，
   状态未知 ⇒ `3`(Play/Pause toggle)。出现这行说明按键已经进了 CarPlay HID
   通道；此时若 iPhone 仍无反应，问题就在 HID index 语义上，不在车机侧。

若第 4 步没有输出：

- `方控焦点不在媒体控制器(controlIndex=...)` → 焦点门禁挡住了。这是**设计如此**
  （避免在车信息页误触切歌），但如果媒体页也这样，说明该车机的媒体控制器索引
  不是 `2`，改 `OneOsKeyInputBridge.CONTROL_INDEX_MEDIA`。
- 完全没有日志 → 没收到事件。优先怀疑 clientId 白名单。

⚠️ **若出现「下一首跳两首」或「播放/暂停要按两次才生效」**：先看第 4 步那行是不是
**一次按压打了两条 DOWN**。`OneOsKeyInputBridge` 对 `onKeyCodeEvent` 与
`onShortClick` 两条回调**都照发、不做去重**（与参考实现 DBTOOL2.0 一致，
**刻意不加**——加了就是"自造判据比参考更严"）。现状是一次按压只产生一条，
判据是"上一首/下一首正常"：若两条都投递，那两个键早就一次跳两首了。

## 媒体卡片 / 仪表盘封面

CarPlay 的画面是"投上去的"，车机系统不知道 iPhone 在放什么，所以仪表盘一直是空的。
解法是**伪装成车机白名单里的帆书**，走它的广播通道上报曲目：

```
iPhone --iAP2--> 上游 CarPlayMediaSessionService (Media3 MediaSession)
                        ↑ 标准 MediaController（只读，零上游改动）
                CarPlayNowPlayingClient        (:car:core-bridge)
                        ↓ CarNowPlayingSource
                EcarxCarMediaAdapter           (本模块 ecarx/media/)
                  ├─ LocalCoverHttpServer  ← 封面字节 → http://127.0.0.1:<port>/cover.jpg?v=<n>
                  └─ FdBookBroadcaster     ← 歌名/歌手/时长/进度/状态/封面URL
                                               → com.geely.online.service/com.media.control.MediaControlReceiver
                                               → 车机 MediaCenter → 卡片 / 仪表 DimMenu
```

四条最容易记错的：

1. **心跳与数据是两条广播**，混在一起什么都不显示。带 `RECEIVER_MEDIA_APP_STATUS=true`
   的那条在记完心跳后**直接 return，不处理元数据**；心跳周期必须 < 3s（取 2s）。
2. 服务端去重**只看播放状态** ⇒ 播放中切歌（3→3）会被整条 return，连歌名都不读。
   所以歌名变了但状态没变时要**先发一条相反状态再发真实状态**，骗过它。
   逻辑在 `FdBookBroadcastPlan.statusSequence`（有单测）。
3. **封面必须走本地 HTTP**：车机 MediaCenterUI 在另一个进程，`file://` 跨进程读不到
   我们的私有目录。URL 带 `?v=<n>` 破车机端图片缓存。
4. **`?v=` 的取值语义是"封面字节的代次"**（切歌 +1 并清空本地封面；收到新字节 +1 并写入），
   不是参考实现的"切歌计数"——我们的封面走 iAP2 文件传输、**晚于**元数据，
   照抄会出现"歌名换了、封面永远停在上一首"。

现场排查（tag `DBPlay-EcarxMedia` / `DBPlay-FdBroadcast` / `DBPlay-CoverHttp` /
`CarPlay-NowPlaying`）：

0. **先确认开关是开的**：设置 →「媒体卡片 → 上报正在播放」。关掉时整条广播链路不会启动，
   日志里**不会**有 `媒体卡片上报已开启（封面端口 N）` 这行（不是错误，是没开）。
   想知道当时的配置，看诊断包里的 `system/effective_settings.txt`。
1. `CarPlay-NowPlaying`：`已连上 CarPlayMediaSessionService` / `正在播放更新：...`
   —— 读上游这一步通了没。连不上是**常态**（上游服务只在会话期间存在），
   客户端会自己退避重连。
2. `DBPlay-EcarxMedia`：`看不到 com.geely.online.service/...（探不到 ≠ 没有），仍然照常上报`
   —— **这行是信息，不是错误**。探测受 `<queries>`/导出标志/用户 ID 影响，
   而向不存在的组件发广播是无害的，所以**只报告、不阻断**。
3. `DBPlay-FdBroadcast`：`数据广播：<歌名> - <歌手> status=... pos=.../... cover=...`
   —— 报上去了没。若 `cover=无`，看下一条。
4. `DBPlay-CoverHttp`：`封面已写入：http://127.0.0.1:<port>/cover.jpg?v=N（xxxxx 字节）`
   与 `200 OK /cover.jpg`（说明车机真的来拉了）。
5. `CarPlay-NowPlaying`：`封面过大（... > 524288），为避免 Binder 事务超限已丢弃`
   —— 出现它说明这首歌的封面 > 512KB 被我们主动丢了。这是**刻意的**：
   `MediaMetadata` 跨 Binder 传、单次事务上限 1MB，而 media3 自己**不兜底**
   （1.11.1 里搜不到 `TransactionTooLargeException`），宁可没封面也不能让整个媒体会话服务崩。

⚠️ 已知行为：只要 CarPlay 会话活着，我们就每 2s 维持一次心跳，**卡片会被我们占住**。
若此时用户去放真正的帆书，两边会互相覆盖。参考实现同样如此，我们的处理是
**给一个开关**：设置 →「媒体卡片 → 上报正在播放」关掉即可（见下）。

### 现场开关：`reportMediaCardToCar`

设置 →「媒体卡片 → 上报正在播放」，默认**开**。关掉后：不再发心跳、不起本地封面 HTTP 服务，
车机约 3 秒后自动清掉卡片，交还给车机自己的媒体源。**点「保存并重连」后生效。**

⚠️ 一处**刻意的不对称**：`nowPlayingClient.connect()` 不受这个开关控制 —— 方控的播放 / 暂停键
要靠 `current()?.isPlaying` 决定发 `0xB0` 还是 `0xB1`（见 `CarKeyHidMap.playPauseIndex`），
"关掉卡片上报" ≠ "关掉这个状态来源"。所以**关掉后方控按键依然生效**，别当成漏改。

⚠️ 本开关只管**我们伪装帆书那条广播**。上游 `CarPlayMediaSessionService` 在会话期间
本来就会挂一条**自己的**媒体通知（`setShowNotificationForIdlePlayer(ALWAYS)`），
它由上游管理、**不受本开关影响**。

## 已知缺口

- **音量键的语义未定**。车机原生是调系统 `AudioManager` 的 `STREAM_MUSIC`；
  接入 CarPlay 后到底该调车机音量还是 CarPlay 音量，取决于整车音频路由，
  需要真机确认后再接。目前音量键**刻意不转发**也不消费，留给车机自己处理。
- **媒体卡片通道未在真机验证过**。广播常量与去重规则来自 DBTOOL2.0（吉利车机实车
  验证），但**这台车机上到底有没有 `com.geely.online.service` 的
  `MediaControlReceiver` 还没确认过**——见上面第 2 条排查日志。没有也不会出错，
  只是卡片不显示。

## 历史：HID index 语义（2026-09-29 已由实车结论关闭）

这个缺口曾经挂着"读描述符得出、非抓包，需真机确认"，并给了两条猜测修法。
**实车结果推翻了其中一条，另一条不成立**，所以更新如下：

| 曾经的猜测 | 实车结论 |
| --- | --- |
| "播放暂停若变成只播不停 ⇒ 改 `MEDIA_PLAY_PAUSE` 的取值" | ❌ **不是"只播不停"，是完全没反应**。真因是 iOS **不认** `0xCD`(Play/Pause toggle)，只认成对的 `0xB0`(Play) / `0xB1`(Pause)。已改成**读当前播放状态后显式发**（`CarKeyHidMap.playPauseIndex`），与 DBTOOL2.0 的 `sendPlayOrPauseCommand()` 同构 |
| "上/下一曲若颠倒 ⇒ 把 NEXT 与 PREVIOUS 对调" | ✅ 未发生：`0xB5`/`0xB6` 在实车上一直正常 |

⇒ 描述符解读本身是对的（index 就是 usage 数组下标），错的只是"toggle 可用"这个假设。
