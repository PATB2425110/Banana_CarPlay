# 车辆读取开关与异常退出保护（2026-11-21）

> **最终本地结果**：含通知乱序补丁的最终全量实跑`BUILD SUCCESSFUL in 45s`，141tasks全执行，exit0。7模块JUnit XML合计**2446项、0失败／错误／跳过**，相对开关前新增40项；Vehicle Status专项139项（平台80项）。未生成新APK、未安装、未实车／native恢复验收。下文2445项是首次完整验证的阶段记录，不是最后结果。

## 用户要求与边界

用户问“会不会闪退？增加一个开关，打开闪退了就自动关闭这个读取功能”。

不能保证厂商SDK、native库、Android 9系统以及实际iPhone组合绝不闪退。本轮只在独立Apple CarPlay工程增加车辆遥测读取保护，不改原工程、证书、包名`io.dushu.car`或固定版本1.4.2/code375；不执行车辆setter、安装、清数据／配对、提交／推送或发布。

原2244 APK与已经交付的OSN2.0接口Markdown不变。源码完成或本地测试通过不表示历史APK已经包含本功能。

## 开关行为

- 位置：**设置 → 车机 → 车辆状态读取 → 读取车辆状态**。
- 首次默认关闭；开启前明确提示厂商SDK未实车验收及异常退出回退的局限，必须用户确认。
- 只读燃油续航、外温、原车低油警告和Twli车身昼夜；不叠加电续航、不替换为手机电量、不把物理大灯未知枚举猜成夜间。
- 普通平板／没有车辆源的平台显示不支持，开关禁用；不会伪造车况。
- 开启不会自动断开或重连CarPlay，提供独立的手动重连按钮。当前控制器在创建时冻结开关代次，关闭再开启也不能在旧连接收到新A100时复活读取；须新建CarPlay控制器。
- 手动关闭／保护触发会立即屏蔽快照并关闭全部读取lease，只退休车辆读取worker和其自有绑定，不停止CarPlay、导航、音乐、热点或原车导航／媒体上报。
- 不能强制中断已经进入厂商SDK的阻塞IPC；已有调用的结果不能再发布，清理在原worker排队完成。清理仍在途时不允许重开。
- 默认关闭时`core-bridge`不提供Vehicle Status provider，故新连接不会新增A100/A101/A102能力声明。中途关闭不会重写已经发出的identify；当前连接停止读车、字段返回未知，不填0／白天／无警告来冒充数据。

## 持久化与自动回退

独立文件`car_vehicle_read_safety`包含：

| 键 | 含义 |
| --- | --- |
| `enabled` | 用户明确允许读取，缺省false。 |
| `active_reading` | 活跃读取期间的持久保护标记。 |
| `disabled_reason` | 自动关闭／保护存储失败的说明，不记录原始异常消息、身份或凭据。 |

开关不进入`CarSessionSettings`、普通设置草稿或导入流程，避免旧草稿自动保存时把已触发保护的读口重新打开。进程内`generation`只用于使旧控制器失效，不是新增偏好键。

1. `Application.onCreate`在平台安装之前初始化保护并恢复上次未结束的标记。
2. 每个读取worker在首次构造／调用OEM客户端之前取得session token；第一个token必须先以同步`commit()`持久化`active_reading=true`。落盘失败则不允许OEM工厂执行，保持关闭。
3. 多个wired／RFCOMM／tunnel来源共享“活跃期间”标记，但拥有独立token、lease、worker和绑定；关闭旧RFCOMM不能清除新tunnel仍活跃的标记。
4. 最后一个token正常清理后才清除标记。不逐样本写磁盘，不是每秒一次`commit()`。
5. 再启动发现未结束标记，先持久化关闭，再允许平台／控制器启动；不会自动再试读。重新开启必须由用户确认。
6. 工厂／读值／快照向保护层传播的异常会关闭；清理失败保留token与标记，禁止同进程重开，以免叠加未清完的OEM资源。此时需重启应用后再决定是否手动开启。
7. 内层SDK适配器捕获的反射信封会解包识别`Error`（如合成测试中的`UnsatisfiedLinkError`），交给保护层关闭，不反复重试严重链接错误。缺方法、无权限、无config、字段非法或无数据继续按原契约返回未知，不将其认定为闪退。
8. 可识别的reader线程／reader调用栈Java未捕获异常先尝试持久化关闭，然后在`finally`委托既有全局处理器。保护本身抛错也不能跳过原处理器；不吞掉其它导航／音频异常，保留既有`navi.action.status`专门处理链。

## 必须明确的限制

- **原生崩溃或杀进程不能保证当次进程内自救**；持久标记提供下次启动回退，不是恢复已经崩溃的进程。
- 系统杀进程、断电、强行结束、卡死后退出都可能留下标记。这是保守保护，UI写“上次车辆读取未正常结束（也可能是系统杀进程或断电）”，不声称已定位闪退根因。
- 只对实际开始的读期保留标记。仅开开关但未收到手机A100、尚未读车时，退出不应误称车辆读取未正常结束。
- 所有崩溃都能被准确归因、所有厂商内部吞掉的异常都能识别、存储损坏／空间耗尽时仍保证落盘，均不是本实现的保证。存储失败本次先关闭；磁盘上可能保留旧标记或旧开关状态，需按界面说明处理。
- Android自动备份可能恢复活跃标记并保守关闭；用户不应为避免保护去清数据或卸载重装。
- 未实车确认vendor权限、native鉴权、HAL发布配置／时间语义、iPhone订阅／空A101接受及地图昼夜表现；参考此前Vehicle Status接入记录。

## 审查发现与限定修复

- 原实现仅外层捕获异常会漏掉SDK适配器内层吞掉的严重`Error`；补充仅反射信封解包与致命错误传播，不扩大成“无权限也触发崩溃保护”。
- 原组合客户端清理吞掉错误，外层看不见；现两路均尽力清理后将异常交给保护层。清理失败的token不假装正常退休。
- 原source在锁里调用`latest/close`，可能让关闭等外部代码；现锁只做记账，迟到快照二次核验后不得逃逸。
- 只在创建provider时检查布尔值不足：关闭再开后旧provider可响应新A100；现控制器冻结generation，新增纯JVM回归。
- 全局crash保护必须用`finally`衔接原handler，不能因标记保存失败吞掉原始异常；新增链路测试。安装handler失败也保持读口关闭。
- SettingsScreen只允许三处接线改动（分支传actions、VehicleSection增加actions参数、插入安全分区）；源码清单反向撤销这三处并校验2244摘要，不笼统放开布局变更。

## 本地验证（首次完整阶段）

已实际执行7模块JUnit、Geely Kotlin、App Kotlin及Manifest合并，使用现有Android Studio JBR，未调用assemble／package／install：

```text
gradlew.bat :shared:testDebugUnitTest :car:api:testDebugUnitTest
  :car:core-bridge:testDebugUnitTest :car:platform-android:testDebugUnitTest
  :car:platform-ecarx:testDebugUnitTest :car:platform-geely:compileDebugKotlin
  :car:platform-geely:testDebugUnitTest :car:ui:testDebugUnitTest
  :car:app:compileDebugKotlin :car:app:processDebugManifest
  -PallowRelease=false --no-configuration-cache --rerun-tasks

> Task :car:api:compileDebugKotlin
> Task :car:api:testDebugUnitTest
> Task :car:platform-geely:compileDebugKotlin
> Task :car:platform-geely:testDebugUnitTest
> Task :car:ui:compileDebugKotlin
> Task :car:ui:testDebugUnitTest
> Task :car:core-bridge:testDebugUnitTest
> Task :car:app:compileDebugKotlin
> Task :car:app:processDebugManifest
BUILD SUCCESSFUL in 1m 24s
141 actionable tasks: 141 executed
GRADLE_EXIT_CODE=0
```

完成后实际执行`node verification/apple-product/summarize-vehicle-status-tests.cjs --safety`，exit0，逐份解析JUnit XML：

| 模块 | tests | failures / errors / skipped |
| --- | ---: | --- |
| shared | 469 | 0 / 0 / 0 |
| car/api | 152 | 0 / 0 / 0 |
| car/core-bridge | 1311 | 0 / 0 / 0 |
| car/platform-android | 8 | 0 / 0 / 0 |
| car/platform-ecarx | 62 | 0 / 0 / 0 |
| car/platform-geely | 291 | 0 / 0 / 0 |
| car/ui | 152 | 0 / 0 / 0 |
| **总计** | **2445** | **0 / 0 / 0** |

相对开关前2406项新增39项；安全专门测试类37项，另在既有bridge与组合cleanup类各新增1项。Vehicle Status相关套件总计138项（含平台80项），不是138项实车／原生运行验收。

新增覆盖：默认关闭与不构造OEM、写标记先于工厂、多个token只在最后正常清理后清标记、模拟进程重建回退、未开始读取不误判、落盘失败阻止OEM、关闭停止全部读口、源open中trip、迟到快照不得发布、关闭不持有外部snapshot锁、generation阻断旧控制器、清理失败禁止同进程重开、严重反射Error传播、正常缺权限／样本未知不误报，以及crash handler保留原thread/error并保证委托。

保留局限：policy用内存store模拟持久状态与重建，`UnsatisfiedLinkError`为主动构造的JVM对象，handler由测试直接调用；均没有真实杀进程、native载入崩溃、Android设备磁盘／SharedPreferences或Compose点击验收。UI用源码契约测试，不冒充仪器化UI测试。

构建仍有既存deprecated API、override参数命名、旧测试空性／Windows字符等警告，以及未配置本地release签名提示；本轮没有签名／打包，不隐瞒成“无警告”或“已验签”。

最终摘要与源码清单另存`vehicle-read-safety-test-summary-20261121.json`与`vehicle-read-safety-source-manifest-20261121.json`，已刷新为下节最终重跑阶段，不覆盖开关前的2406摘要或已交付API文档的2388阶段快照。

日志：`C:\Users\17900\AppData\Local\Temp\opencode\vehicle-read-safety-full-verification-20261121.log`。

### 通知乱序复审与最终补测

首次2445项通过后，复审再补一个并发边界：保护通知可能重入或迟到，旧“关闭”回调不能关闭新控制器的lease，也不能在UI把最新保护原因覆盖为旧值。source现在按即时generation判定退休对象；UI通过有界合并的callbackFlow在界面协程重读当前状态，不直接消费后台旧快照。新增1项通知逆序回归，UI契约增加同一断言。

随后实际将上面完整Gradle命令再次执行，仍带`--rerun-tasks`，不是只补跑一个测试：

```text
> Task :car:api:compileDebugKotlin
> Task :car:api:testDebugUnitTest
> Task :car:ui:compileDebugKotlin
> Task :car:ui:testDebugUnitTest
> Task :car:platform-geely:compileDebugKotlin
> Task :car:platform-geely:testDebugUnitTest
> Task :car:core-bridge:testDebugUnitTest
> Task :car:app:compileDebugKotlin
> Task :car:app:processDebugManifest
BUILD SUCCESSFUL in 45s
141 actionable tasks: 141 executed
GRADLE_EXIT_CODE=0
```

完成后再执行XML汇总exit0：shared469、api**153**、core-bridge1311、platform-android8、platform-ecarx62、platform-geely291、ui152，总计**2446**项，所有failures／errors／skipped均为0。安全专门测试类38项＋既有类补充2项＝新增40项；Vehicle Status套件139项／平台80项。所有7模块和App源码均为本轮最终补丁后的验证；仍没有Android设备／Compose仪器化或实车恢复验证。

最终补测日志：`C:\Users\17900\AppData\Local\Temp\opencode\vehicle-read-safety-final-verification-20261121.log`。

### 边界与历史交付复核

`make-vehicle-status-source-manifest.cjs --safety`实际exit0，核算56个文件摘要，确认version.properties及合并Manifest仍`io.dushu.car`／1.4.2/code375、vendor权限仅声明、android.car可选，无sharedUserId／安装其它APK权限。2244沉浸卡片及其布局／测试摘要均未变；SettingsScreen反向撤销仅三处授权接线后，摘要恢复为2244基线，不将它误记为全文未改。

只读重算历史交付两文件，均与原摘要相同，未写回或覆盖：

- `AppleCarPlay-1.4.2-375-202611202244.apk`：6,108,331 bytes，SHA256 `7DD6F09B85B926F4E4A3CB6159730A8E6234A3E78BD68B2C6BDF6989BD991F9A`。
- `OSN2.0-车辆接口与读写方法-20261121004020.md`：7,295,828 bytes，SHA256 `98A06B66223C19B3A60F72074B812742190ADAC745A3836BFC5FD09D5F6529EB`。

辅助核验第一次exit1：Windows PowerShell向Node标准输入传中文here-string时按旧代码页将路径替换成`????`，引发ENOENT；不是交付文件丢失或产品测试失败。改为从既有UTF-8交付JSON读取原路径、不在管道内嵌中文，重跑实际exit0。未调整系统执行策略或全局编码设置。

验证证据由`freeze-vehicle-read-safety-evidence.cjs`将JUnit XML、构建日志、摘要、源码清单和本记录按不可覆盖副本冻结到Temp/opencode独立目录；目录与逐文件摘要以`vehicle-read-safety-evidence-20261121.json`为准，避免未来build覆盖活动XML后误引用成当前结果。

## 后续人工验收（未执行）

1. 合法权限的QUALCOMM CX11／Android 9车机覆盖安装后默认关闭，不应因为晚间小米平板测试而声称车辆可读。
2. 默认关闭连接时无新增A1声明／读取；确认后未重连不启动；手动重连也须真实iPhone A100才开始。
3. 关闭立即停止新调度与暴露值，CarPlay画面、导航／媒体、热点、110／111切换和首帧保护保持原行为。
4. 停止／A102／断连正常清理，下一启动不无故关闭；有多个来源时退休一条不能清除另一条的活跃标记。
5. 仅在安全隔离的测试环境模拟未正常结束标记，确认下次启动关闭并显示原因。不得在行驶中故意制造native崩溃、低油或危险车控事件。
6. 再手动确认打开但不重连，旧控制器不得重新读车；明确重连后才可建立新读期。
7. 不能把本地合成`Error`或新建policy模拟重启称为真实Android/native崩溃恢复验收。
