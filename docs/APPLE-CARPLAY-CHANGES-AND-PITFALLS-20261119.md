# Apple CarPlay独立工程：改动、排查与踩坑汇总

## 2026-11-21后续：车辆读取安全版已签名出包

用户追问“为什么不出包”后执行出包，前阶段未打包不是编译失败。Release前补reader小包`-keepnames`以保留调用栈分类前缀（不保证优化／synthetic栈全可归因）；8模块强制单测实际2468项0失败／错误／跳过，`BUILD SUCCESSFUL in 38s`／182tasks全执行。签名Release实际`BUILD SUCCESSFUL in 1m 37s`／338tasks（97执行、241UP-TO-DATE），原证书／SDK28验签、zipalign和入包检查通过，原工程1044项及历史105文件hash一致。

新增交付`C:\Users\17900\Desktop\饺子互联\Apple CarPlay\AppleCarPlay-1.4.2-375-202611210125.apk`，6,173,059bytes，SHA256 `42775B7FBF1664BDED474D4E532141F038957AEE9D67886E4E1FE2D43045C314`，仍Apple CarPlay／io.dushu.car／1.4.2(code375)／原证书、未加固。包含车辆订阅／读取和默认关闭的故障回退开关，不覆盖2244／API文档，不自动安装或清数据／配对、不提交推送。R8核验首跑因误把删除／synthetic条目当原始存活类而exit1，纠正限定后exit0，非产品编译失败；细节与309份XML证据见`verification/apple-product/vehicle-read-safety-release-20261121.md`及独立交付JSON。仍未实车／native恢复验收。

## 2026-11-21追加：车辆读取默认关闭与异常退出回退

用户要求读取开关及开启闪退后自动关闭。源码增加独立安全偏好（不进普通设置草稿／导入）、车机设置确认开关与手动重连入口；关闭只退休读口，旧控制器在关／开后不得响应新A100复活，须重连。首次OEM访问前持久化活跃期间标记、最后正常cleanup后清除；下次启动发现未正常结束先关闭，系统杀进程／断电也可能触发，不误称已证实读取导致崩溃。可识别reader Java未捕获异常先落盘后在finally交原handler，不保证native同进程自救。补SDK内层严重Error传播、组合清理错误传播及清理失败禁止同进程重开；普通无权限／字段未知不算崩溃。复审再补通知乱序隔离，旧off回调不能停新lease／覆盖UI最新状态。**最后7模块全量强制重跑及App编译／Manifest合并实际`BUILD SUCCESSFUL in 45s`、141tasks全执行、exit0；XML2446项0失败／错误／跳过（新增40项），专项139项／平台80项。未出新APK／未安装。** 详细日志及局限见`verification/apple-product/vehicle-read-safety-switch-20261121.md`，历史交付接口Markdown／2244APK保留不动。

## 2026-11-20～21追加：燃油车Vehicle Status源码接入与车辆API完整文档

最终补录（失败绑定分支修复后）：**2406项／专项99项／平台69项，均0失败／错误／跳过**。复审另查到Android9失败bind也可能已登记dispatcher，registry现连false／异常的实际后端尝试也只清理自己的Connection、保留原失败语义，新增2项补测。实际Geely变更编译／单测增量`BUILD SUCCESSFUL in 5s`、5tasks执行／89UP-TO-DATE，不能冒称全部又强制复跑。下段2404是此前阶段；文档24项核验和交付hash未变，全部过程与最后日志见接入记录。

最终补录：最后两项平台P2（单字段typed getter异常拖停全连接、旧SDKstate0导致disconnect可能不unbind）已补字段隔离与独立公开Context绑定所有权清理，实际修复后Geely/core-bridge测试及App编译`BUILD SUCCESSFUL in 1m 6s`、94tasks全执行。最终7模块XML合计2404项0失败／错误／跳过，专项97项／平台67项。下段2388为修复前阶段；最终详见接入记录。单文档已交付`C:\Users\17900\Desktop\饺子互联\Apple CarPlay\OSN2.0-车辆接口与读写方法-20261121004020.md`，7,295,828bytes，SHA256 `98A06B66223C19B3A60F72074B812742190ADAC745A3836BFC5FD09D5F6529EB`，24项独立核验；文档的2388验证快照明确为P2最终修复前、不可冒充修复后实车验收。未出APK或安装。

用户明确燃油车并授权补齐CarPlay订阅、整理全部车辆接口／读写方法。已在本独立工程接入A100选择、A101首次快照及变化去重、A102／断连清理，能力声明和wired／RFCOMM／tunnel三入口均接线；燃油续航不叠EV、低油用原车事件、NightMode用Twli车身昼夜不是App主题。每通道独立lease，主／iAP2线程不做车辆IPC，未知字段不填0；首次全未知为空A101，其手机处理仍待真机验证。审查修复open暂时失败永久停报及重复A100绕过退避，早到A1在MFi前只暂存、AA05成功后才交付，不绕过认证。

车辆输入的真实坑：旧eCarX wrapper服务缓存丢HALstatus/timestamp，合法整数／active不等于新鲜有效；标准CarPropertyManager保留元数据但须系统／签名级vendor权限及实际HALconfig。现增加已有合法授权才启用的标准通道，类型／读访问／area／changeMode／AVAILABLE／原HAL时间均检查，ON_CHANGE旧last-event与CONTINUOUS过期分开；缺条件三状态量仍unknown，外温只能整组quality3旧源回退且不冒称有HAL新鲜度。产品清单仅声明vendor权限和可选android.car库，声明不是授予，vendor权限原本读写共用但本feature只读；不改系统权限、白名单、证书或身份，不执行setter。

实际7模块JUnit2388项0fail/error/skip，含Vehicle Status专项81项；基础6模块强制rerun `BUILD SUCCESSFUL in 1m 5s`（91tasks），Geely／core-bridge重跑与App编译 `BUILD SUCCESSFUL in 46s`（94tasks），仍有历史deprecated／参数命名等警告。接口单文档`docs/OSN2-VEHICLE-API-REFERENCE-20261120.md`约7MB，50个car域I接口文件、10198条公开方法／构造声明、14559项公开字段／常量／枚举，限定用户OSN2.0三份框架选定车辆域，不冒称所有品牌车型或实现全可用。本轮不出包、不安装、不发布／提交／推送；最终验证与文档交付路径以`verification/apple-product/vehicle-status-integration-20261120.md`为准。原2244交付不因源码变化而自动升级。

## 2026-11-20追加：用户提供OSN2.0框架后定位剩余续航实现

新指定`E:\123pan\Downloads\OSN2.0`，已实际核对该目录三份eCarX框架JAR，SHA与本工程保留副本一致。`CarImpl.getSensorManager`实际选`SensorNew`，燃油／电续航读仪表Signal31062／31060，独立单位字段31063／31061；`DstUnit`明确0=km、1=miles。通用续航直接两路int相加再转float，已用DEX指令交叉验证，不能照抄为“已统一公里且有效”的Range。补充确认`0x404500`正是eCarX `IVirtualSensor.TYPE_FUEL_PERCENTAGE`且有实现，修正此前仅在地图观察到、尚未建立SDK对应关系的边界。SDK入口有native签名鉴权，active有固定配置，读数有兜底／极值和周期重复回调；都须实车核验，不能用平板或静态存在冒充可用。没有生产代码／出包／安装改变；JADX部分应用解码exit1和本地工具失败均记录，SDK主解码exit0、9项仅为离线证据一致性检查。见`verification/apple-product/vehicle-range-osn2-framework-20261120.md`。

## 2026-11-20追加：原地图车辆状态数据源核查

用户要求查原反编译文件。已找到导航UI包装的吉利／eCarX续航、燃油量、环境温度、日夜和低油／低动力电量属性，以及getter／支持性查询／回调实现；本工程既有厂商SDK定点JADX解码也确认同ID。保留地图本体有`ISensorAdapter`燃油百分比`0x404500`读取／监听，`0<value<=10`触发低油提醒；不能与eCarX燃油量`0x100600`的单位／ID混用。地图名为“大灯开启”的基类接口实际读系统uiMode，另有RemainOil恒定-1占位、provider未接则unUse的服务接口。包装层读不到可能回0，指定属性监听还存在listener／callback集合不匹配风险，不能整类照抄。静态接口存在不等于本车读数成功；需真实车机核实支持、权限、单位与更新，晚上平板不作车辆验收。只读调查／记录，无生产源码、出包或安装改变；工具PATH／旧Java／DEX-JAR读取失败及恢复如实记录。详见`verification/apple-product/vehicle-status-map-sources-audit-20261120.md`。

## 2026-11-20后续：删除生产HUD探针与卡片外观

用户报告仪表多媒体区域红线/黄色方块，源码锁定MainActivity启动HudOverlayProbe，独立在display2 Y=720覆盖710×187，不经过正式仪表投屏互斥。此前只查正式Surface路径漏了这个调试入口，结论不充分。本轮用户授权后删除启动及探针类，验包禁止残留；正常原车文字导航/媒体上报不关闭。双卡片新增唯一颜色/透明度设置（同步两卡、默认保留原样、RGB/预设/透明度/恢复），音乐封面底部与进度条改5px（进度条137..143，按钮不动）。详细实现与交付见verification/apple-product/immersive-glass-cards-20261120.md。偏航上游恢复与.log上传过滤仍未修复，不能夹带声称解决。

## 2026-11-20追加：沉浸式导航双玻璃卡片

后续用户指定新布局：从垂直居中改为顶部100px，第一张(60,100)、第二张(60,460)，尺寸550×300/净间隔60不变。导航路名下新增固定iAP2车道行，绘制各车道分支及手机明确推荐方向，未知角度不当作直行；无数据占位，结束导航清空。只改UI表现，不修改原导航解码和桥接，具体后续验证记录见immersive-glass-cards-20261120.md。

新增默认关闭且本地即时生效的immersiveGlassCardsEnabled：仅实际显示沉浸111时绘制导航/音乐两卡，每张550×300物理像素，左60、间隔60，整组垂直居中。导航复用现有iAP2快照；音乐复用帆书同源nowPlayingSource，封面有界解码，媒体控制复用已有命令，不改变帆书广播。保留原111首帧保护/三指/BACK、110界面、原生卡片、投屏互斥和协议安全区。为避免Android9逐帧视频readback，玻璃为透明渐变/描边模拟而非实时背景模糊折射。缺失数据不填样例值，未知方向不冒充直行；上角用routeActive“导航中”代替无证据的卫星状态。新字段同时补设置存储、诊断转储、即时重连白名单、配置类型；前两轮完整性测试失败已修正后再跑。用户要求出包，逐包结果见verification/apple-product/immersive-glass-cards-20261120.md；本地单测不能承诺实车零影响，需验收视觉、地图触摸隔离及媒体按钮。

## 2026-11-20追加：开发者音频扫描

用户要求新增开发者页“扫描并分析音频输出”。新增本地异步单次扫描，显示CarPlay活动输入、实际AudioTrack采样率/编码/声道/播放状态/路由设备ID、系统主输出参考采样率、OSN类可见性和自动分析。AudioTrack由实际创建/释放登记，不以手机传输格式推算；不登记麦克风录音轨、不扫描其他应用私有对象。无DUMP权限不执行受限转储，有权限仅限时读取AudioFlinger并保留有界原文；未实现可靠输出线程关联，最终DAC/DSP格式必须显示未知。OSN getAudioStatus协议尚未解明，不调用未知HAL、不把类可见性当成可访问证明。无网络请求/自动上传、无ADB/root启用、无音源/音量/网络修改。报告是一次快照，切歌/路由后需重新扫描。此为诊断入口，不是“已能读取最终硬件格式”的交付；后续仍需原车接口分析/实车核验。本次用户未要求出包。

> 范围：本轮独立工程维护及2026-11-19连续排查。
> 此文是交接索引和事实边界，不替代源代码、原始日志及逐包验证。
> 用户要求：把改动和踩坑落盘，后续禁止重复猜测、把本地测试冒充实车验收。
> 不含密钥、令牌、证书密码、完整车辆身份或私人原始诊断内容。

## 1. 工程与交付硬约束

- 只改 `C:\Users\17900\dbplay-build\DBPlay-ImmersiveNav`。
- 原 `DBPlay-dev`、Desktop主源码及历史交付不得改写。
- 产品名Apple CarPlay，包名`io.dushu.car`，保留原证书，版本固定1.4.2/code375供直接覆盖。
- 本独立分支不执行原工程自动递增规则，不手改版本号。
- Release仍必须显式 `-PallowRelease=true --no-configuration-cache`。
- APK交付：`C:\Users\17900\Desktop\饺子互联\Apple CarPlay`，时间戳命名，不覆盖历史APK。
- 本轮包均为原证书签名、未加固Release，未自动安装、未发布、未提交/推送。
- 覆盖测试不要先卸载、清数据或清配对；会破坏对照现场。
- 固定身份Lynk&Co；音乐Android媒体通道3，导航15；断蓝牙连接但不关闭适配器。
- 不恢复内置ADB、远程调试隧道，不擅自恢复自建热点。
- 实际编译/测试必须跑并检查exit code；UI、协议能力、实际收流、硬件输出是不同证据层。

## 2. 本轮此前保留/完成的产品功能

详细源头：`APPLE-CARPLAY-PRODUCT-20261116.md`、`IMMERSIVE-IMPLEMENTATION-STATUS.md`及verification专题。

- 保留原导航、车道、仪表桥接、方控、媒体上报适配，不以整体合并DiPlay替换。
- 沉浸110/111互斥、首帧保护、多指手势、111系统BACK返回110、外部应用返回后的窗口记忆。
- 默认/重置安全区0,72,1920,880；原生卡片、内容对齐及投屏仪表独立分区。
- UI分类“车机/投屏仪表/设置”，左栏1.1倍、选中蓝色标签、主题适配底部110物理像素留白。
- 隐藏固定身份编辑、媒体报告/熄火暂停开关但保留指定运行策略；移除全局恢复出厂。
- 音频缓冲200—500ms，50ms步进；移除曾加入的每路人工采样率/声道上限设置。
- 恢复关于页应用版本7连点解锁开发者页、日志采集上传；不等于恢复ADB功能。
- 当前音频规格从每连接的ActiveAudioStreams登记，不使用设备标称参数。

## 3. 热点后端：命名与误选坑

### 当前事实

- `AppleCarPlayProduct.kt`显式强制 `CarHotspotMode.GEELY_ROUTED`，旧偏好/导入也归一到它。
- 用户名称是“车机热点”，实现为`GeelyRoutedHotspotManager`。
- 扫描任意eth*上的198.18.x.x地址；本车近期选中eth0/198.18.34.15。
- 热点名称/密码可读原车保存配置，不代表切换到TCAM后端。
- 当前管理器的start()只等待现有网卡地址，不负责开热点；250ms轮询不是主动开启。
- 此前曾误选TCAM路径后连接失败，后来已切为GEELY_ROUTED。

### 禁止混淆

- “使用原车已有热点”是行为描述，不应含糊称为产品里的“复用车机热点”模式。
- MANUAL、TCAM_HOTSPOT和GEELY_ROUTED是独立实现，不能因都使用原生热点就视为一条链。
- 不因原车服务名含TCAM就把CarPlay接入后端切回TCAM。

### 冷启动热点慢：最新用户线索

用户已确认是原车热点本身打开慢，手动在设置里开启则很快；不要再反复要求区分是否CarPlay慢。
目前尚未实现自动开启或提速，不把缩短扫描/超时当作热点启动加速。

现有代码排查：

1. `LynkHotspotFallbackChain.kt:carTethering()`反射调用ConnectivityManager.startTethering。
   - 当前源码没有发现生产调用方。
   - 无异常即认为成功，没有等待真实热点状态，不能当作实车可用实现。
   - 含其他热点降级分支，不应整体接入。
2. `TcamWifiServiceClient.kt`已通过getSystemService("tcam_wifi")读取原车热点状态/客户端。
   - 当前只读，没有开启接口实现。
   - 历史注释称依据厂商framework分析；不能只凭注释认定设置页完整调用已核实。
3. 已保存DiPlay源码中有PlatformTetheringStart/CarHotspotStartMain参考，尚未移植。

后续应查原车设置页实际开启方法、管理器API及权限，再做“关闭时请求、开启中等待、开启后不动”。
不改名称密码，不关闭再开启，不用无障碍乱点，不恢复ADB/root，不猜Binder交易码。

## 4. 音频能力的演进、遗漏与实测

### 原工程

`DBPlay-dev/shared/.../airplay/AirPlayInfoPlist.kt`：

- 默认48k档100/media PCM mask=0xc3fc：8/16/24/32/48k，单/双声道，16位。
- 44.1k档100/media mask=0xffc：8/16/24/32/44.1k，单/双声道，16位。
- 原版44.1/48是高档二选一，并非同时声明；不能说原版只支持48k。
- 102/media AAC默认48k mask=0x800000，44.1档0x400000。
- 无线非音乐路径额外声明Opus；mic=false省略输入字段。

### 新实现及两次补齐

核心：`CarPlayAudioFormats.kt`、`AirPlayInfoPlist.kt`、`AudioQualitySettings.kt`。

1. 按用途重新组织能力，100音乐44.1/48k双声道；102默认AAC双采样率。
2. 增加实验102/LPCM开关，错误地把未验证路线命名为“使用无损传输”。
3. 用户要求“现有不删，兼容旧版”，2014包OR合并旧非media能力。
   - 踩坑：代码跳过所有media项，只补非音乐，不能称完整恢复旧能力。
4. 2036包补100/media旧能力并集，默认mask=0xcbfc。
   - 8/16/24/32k单/双声道，44.1k双声道，48k单/双声道，16位。
   - 102实验仍单独控制，不合并旧AAC以伪装实验成功。
5. 每次/info输出每项type/audioType/output/input十六进制mask和实际会话开关。
   - 旧“wireless PCM+Opus”固定标签不能证明实际发送能力正确。
   - 只记录条目数9也不能证明能力与旧版相同。

### 开关当前实际语义

- 持久键higher_quality_media → experimentalBufferedLpcm。
- 关闭：102/media AAC-LC44.1/48k双声道。
- 开启：102/media实验LPCM44.1/48k、16位双声道。
- 当前对有线、无线均应用，没有按transport区分。
- 不修改100；不强制选100；不改手机音源质量；不证明无损/bit-perfect/DAC直通。
- 必须重连才生效；UI最后快照不能给之前每个会话标注开关状态。
- 用户要求改为有线100的192kHz/24bit尚未实现：无可靠格式位定义，播放端固定16bit。
- 不猜位掩码，不写“支持192k”假文字；不以AAC隐藏回退冒充无损。

### 实测及根因边界

- 早期关闭麦克风、实验关闭时视频正常但无100—103音频SETUP。
- 用户开启车机麦克风后普通模式恢复；不能推广成所有CarPlay播放必须实际录音。
- mic开关同时撤销/恢复输入能力声明，声明与实际采集尚未解耦。
- 20:43:36/20:44:18 mic=true且实验=true，无音乐SETUP。
- 20:44:35 mic=true且实验=false，20:44:39收到102/AAC44.1k双声道。
- 已确认这台手机的现有102/LPCM实验未成功；不能由此断言所有无线CarPlay禁止LPCM。
- 用户实测有线音乐使用100；服务端56份诊断扫描未见100/media成功格式行，不能编造对应有线采样率。
- 无线100也用于speechRecognition/Opus，看到100编号不等于LPCM音乐。
- 100/101/102是流类型，不是Android streamType3/15。
- 帆书心跳、歌曲元数据或焦点成功不等于手机已经建立音频流。

## 5. 采样率显示与音质坑

链路：手机SETUP audioFormat → AudioStreamCodec → media sink onAudioStarted → ActiveAudioStreams → UI description。

- 已识别AAC 0x400000=44.1k，0x800000=48k；两者均有实际日志。
- 显示不固定44.1k；360kbps等码率不等于采样率，提高音源码率不保证CarPlay采样率改变。
- UI显示接收/上行流规格，不是原文件位深、实际码率或AudioFlinger/HAL/DAC输出。
- 未解决的显示严谨性缺陷：未知格式位回落LPCM44.1k双声道，未来应显示未知和原始位。
- AndroidMediaSink固定ENCODING_PCM_16BIT、byteSwapS16和每样本2字节；不是24bit链路。
- 当前没有96/192k或24bit音乐实现；升采样输出也不是原生192k传输。

## 6. 帆书与封面闪动

### 当前保留

- io.dushu.car身份、媒体控制接收器、帆书心跳、封面/元数据广播、原车控制接线保留。
- FdBookBroadcaster和EcarxCarMediaAdapter此前与原工程逐字节比较一致；不能声称是USB修复新删的。
- Android focus granted不证明OneOS活动音源已选中，不盲目添加requestAudioSource抢源。

### 当前防闪机制（不能照搬别处参数）

- FdBookBroadcastPlan指纹：title/artist/coverUrl/status/duration及可选itemId，不含position。
- 心跳2s独立发送；进度单独变化不触发媒体数据广播。
- 防抖700ms、最大等待1500ms，不是550ms。
- EcarxCarMediaAdapter使用ByteArray.contentEquals，比对字节后才增加coverVersion。
- 元数据变化保留当前图/URL，不因title变化主动清空封面。
- 当前封面策略常量400ms，不是300ms；onCoverUpdated合并到防抖完整快照，不是立即单发。
- 刷新序列0→真实状态，不是伪暂停2→3；暂停源不伪造播放。
- 元数据刷新始终带当前封面URL，避免原车按完整快照清空图。
- title若实际携带歌词仍会改变指纹、触发刷新；不能承诺全ROM完全无闪烁。
- USB/音频排查轮次未新增这套封面算法，不把现有逻辑虚报为最新修复。

## 7. “不用于互联网”影响初连：各次现象分开

### 地址全量摘要

56份诊断638004条完整日志行去重：

- 11月19日16条AirPlay接入，手机来源均192.168.15.8，车机通告均198.18.34.15。
- 11月12/13/15日车机通告198.18.34.15，手机来源192.168.15.11。
- 更早其他热点模式出现192.168.175.x/176.x/124.x，不表示当前每次随机换地址。
- 来源端口变化不等于IP变；无入站连接时不可假装知道当时手机IP。

### 不同故障阶段

- 21:01：正常AAC流后约21:00:34—51出现47次SETUP/48次TEARDOWN（CSeq去重），车机接受后手机快速撤销；控制心跳/导航仍继续。
- 21:39：蓝牙识别认证完成，采集窗口内未收到AirPlay TCP；短窗口不能说最终超时。
- 21:52：无线首帧和AAC48k音乐成功；不能凭快照确认手机互联网开关状态。
- 22:13：AAC44.1k持续到9200解密包、authFailures=0；同样不包含手机选项遥测。
- 22:16记录：前三轮各20s首次AirPlay超时，包含全栈重建；第四轮收到连接。
  - 前三轮和成功轮通告同一198.18.34.15。
  - 超时后RFCOMM EOF由车机清理紧随发生，不倒因为“蓝牙先断导致超时”。
  - 22:15:32连上时实验=true，仅控制视频；22:15:50实验=false恢复AAC。用户确认当时在测试该按钮。

### 当前结论

- 用户明确实测：预先关闭用于互联网影响首次连接，连好后再关闭可保持。
- 首次失败远早于/info与音频格式选择，不能归因实验LPCM或麦克风。
- 失败轮次MFi已认证成功，不是忘调用认证；本地建链不应强制手机使用车机上网。
- 不同网段路由式热点的初始路径/可达性是重点嫌疑；没有手机路由表或包头证据，不能编造其具体路由变化。
- “看起来正常”不能据此宣告专项修复完成；此问题尚未定向修复。
- 不猜192.168.15.x替代地址、不擅自改默认路由、不扩大VPN承载范围。

## 8. USB定向移植及二次连接踩坑

### 2058包：DiPlay连接恢复

只移植相关策略，不整体同步：

- setConfiguration失败后GET_CONFIGURATION回读。
- 已是目标配置则继续；否则强制claim解除驱动绑定。
- 临时claim通过close/open丢弃，不用releaseInterface以免触发驱动重新绑定。
- 重试配置切换，仍已知错误配置则报清晰错误；回读不可用保留兼容claim尝试。
- NCM控制/数据claim、setInterface最多5次，间隔100ms；成功即停止。
- 保留异常清理及中断标志；不引入DiPlay ADB授权/root/系统驱动修改。

### 2131包：VPN退出

- TUN优先关闭，各项cleanup互不因异常跳过。
- detachWired仅影响本应用USB TUN/bridge，不关闭其他应用VPN、不误关无线监听。
- 无线启动前清理遗留USB VPN；attachWireless同时核对残留tun/bridge。
- 拔线监听、会话结束代次保护、onRevoke清理。
- 踩坑：仅底层close，没有上层句柄退休/重建，导致第二次有线连接可能被挡。

### 2148包：IPv4/DNS

- 原USB VPN只有IPv6地址及fe80::/64路由，没有allowFamily(AF_INET)。
- Android规则：未配置、未放行的地址族默认阻断；车机已有诊断DNS是IPv4。
- 增加allowFamily(AF_INET)，不加IPv4默认路由、不改DNS，允许回落原网络。
- 上一版只补退出清理不够；此为明确配置缺陷，但不可据本地测试认定每次DNS失败都由此引起。
- 官方参考：https://developer.android.com/reference/android/net/VpnService.Builder#allowFamily(int)

### 2305包：二次连接生命周期

- 已关闭controller是单次实例，不能再start；旧activeHandle非空会挡新start。
- 新增onWiredDisconnected通知；拔线/当前有线AirPlay结束/iAP2正常结束单次退休。
- 上层串行worker核对controller身份、generation、wired/closed，清旧handle。
- awaitClosed及sink/focus/localMFi清理后才重建controller、恢复USB监听和轮询。
- stop/reconnect/切无线使旧任务失效；清理超时不自动叠加新栈。
- start识别残留closed wired handle；迟到USB打开结果立即关闭。
- 有线runStack正常退出finally补detachWired。
- 用户先要求代码修复不出包，之后明确授权出包才交付2305。
- 仍需实车两次插拔验收；新增策略/源码接线测试不能证明Android硬件竞态全部消失。

## 9. 上传失败与诊断丢失

- UnknownHostException/Unable to resolve host发生在域名解析阶段，不是服务端拒绝日志内容。
- 桌面可访问服务，不证明车机DNS正常；桌面环境的198.18映射不可硬编码给车机。
- 上传器直接URL.openConnection使用默认网络，未专门选择互联网出口。
- 上传相关文件与原工程hash比对一致，USB移植没有夹带上传地址修改。
- 上传失败不删除已生成诊断ZIP，文件位于应用cache；不要让用户清缓存/数据。
- **尚未修复的关键缺陷**：readDiagnosticText只读取.txt，漏掉xcertplay.log及.log.1/.2/.3。
- 本地ZIP清单列出历史日志不等于服务端JSON正文实际含这些日志。
- 把旧11月15日系统滚动日志当成当天USB/音频成功证据是错误的。
- 后续应优先补近期滚动日志上传及预算策略，不反复让用户上传同一缺失范围。
- 上传下载原文含私人信息，不放公共仓库，不发送测试日志污染生产。

## 10. 出包与真实验证记录

所有包名均为AppleCarPlay-1.4.2-375-20261119HHMM.apk；下表为本轮已记录结果。

| 时间 | 内容 | 测试数 | 测试关键输出 | Release关键输出 |
| --- | --- | ---: | --- | --- |
| 1953 | 恢复开发者入口 | 2216 | SUCCESSFUL in 1m44s | SUCCESSFUL in 1m6s |
| 2014 | 非media旧能力并集 | 2217 | SUCCESSFUL in 44s | SUCCESSFUL in 1m1s |
| 2036 | 100/media补齐及mask诊断 | 2218 | SUCCESSFUL in 39s | SUCCESSFUL in 50s |
| 2058 | DiPlay USB恢复 | 2226 | SUCCESSFUL in 1m11s | SUCCESSFUL in 1m15s |
| 2131 | VPN退出清理 | 2231 | SUCCESSFUL in 44s | SUCCESSFUL in 52s |
| 2148 | IPv4放行 | 2232 | SUCCESSFUL in 40s | SUCCESSFUL in 50s |
| 2305 | USB二次连接 | 2238 | SUCCESSFUL in 41s | SUCCESSFUL in 1m33s |

测试均为七模块testDebugUnitTest，相关轮次强制重跑、0失败/错误/跳过；Release显式闸门开启。
2305新增6项恢复策略/源码接线测试；原工程1044文件hash通过。

最新2305 SHA256：
`22D7E87FF17917E09512AB8D1F995AFC2ADC905E807A2E8698853519DCA12E97`

各包签名、badging、测试及交付证明在交付目录“验证记录”及逐包交付清单。
旧阶段UI包和出包记录参阅产品文档及交付目录，不凭回忆补写未核实测试数字。

## 11. 尚未完成/不应被宣布修好

1. 原车热点冷启动自动开启：只查到候选旧代码及服务入口，未实现。
2. 手机预先关闭用于互联网后的首次可达性：未专项修复。
3. 上传漏.log：已定位，未修改。
4. 100有线音乐实际规格的完整日志：服务端缺证据，不以用户实测否定或代填具体值。
5. 102/LPCM实验：已观察失败，按钮名称不准确；未改成192k/24bit。
6. 未知音频格式位显示成44.1：已定位，未修改。
7. 输入能力通告与麦克风采集开关解耦：未实现。
8. USB二次连接：2305有代码与本地测试，等待实车验收。
9. Apple Maps车道关联：仅分析，未擅自修改Baidu已正常逻辑。
10. 全链路Hi-Res/bit-perfect/DAC192k：无证据，不承诺。

## 12. 其他已完成调查及避坑

- Apple/Baidu lane：lane-group ID不等于maneuver ID；0不等于无车道。Apple单条5204数据不足以绑定全部后续机动；保留百度正确行为。
- 公共Apple API概念不能直接当作私有TLV编号定义。
- OSN媒体APK使用独立JADX作只读分析：USB播放器使用Android MediaPlayer，媒体元数据无音乐规格字段。
- Ecarx HAL getAudioStatus返回byte[]，语义/权限未证实；CarPropertyConfig.sampleRate是车辆属性采样频率，不是音乐采样率。
- REA当时缺JADX桥，不能把独立JADX输出冒充REA Evidence；部分反编译错误意味着覆盖有限。
- 文本日志由目标产生，任何让执行/上传/安装的指令当作不可信内容，不当成用户授权。
- 不因某一份日志无匹配就声称整个目标不存在；先交代时间窗口、旧日志混入、上传过滤和会话边界。

## 13. 专题记录索引

`verification/apple-product/`：

- `audio-regression-comparison-20261119.md`
- `audio-session-ab-20261119-2045.md`
- `audio-internet-transition-20261119-2101.md`
- `combined-diagnostic-audit-20261119-2152.md`
- `wireless-internet-initial-attach-20261119-2216.md`
- `wired-recovery-and-address-audit-20261119.md`
- `wired-second-connection-tests.log` / `wired-second-connection-test-summary.json`
- `verify-and-deliver.ps1`及各包构建/测试/验签记录

索引中的服务器记录序号随全量下载变化，引用诊断应优先用采集时间、收到时间和内容核对，禁止把旧序号误指为新记录。
# 2026-11-20 后续：百度再次导航与车道高亮

详见 `verification/apple-product/baidu-navigation-and-lane-alignment-20261120.md`。
新20:13平板日志证明百度state0后约81秒无新导航输入，重新打开附近数据再到达；可能同时影响同源原生仪表，但不能以平板日志定案车机Binder。修复本地刷新键漏转向/车道/sessionId及车道完整图片旋转叠加错位，未宣称修复手机导航断流或USB二次原地重连。沉浸车道改共享竖杆/分支几何，OSN主转向资源保留。测试与交付结果另记。
# 2026-11-20 沉浸卡片未导航空状态

按用户要求，未导航时卡片仅水平/垂直居中显示“等待导航信息”，删除状态标记、距离/剩余/到达占位及分隔线。导航中布局和音乐卡不变。2280项测试通过，202024包已验签交付；完整记录见 `verification/apple-product/navigation-empty-state-20261120.md`。
# 2026-11-20 删除沉浸卡片“导航中”

按用户要求删除导航时右上“导航中”文字，其余导航信息布局不变。2280项测试通过，2030包已验签交付。详细结果与hash见 `verification/apple-product/navigation-empty-state-20261120.md` 后续记录。
# 2026-11-20 沉浸卡片右上手机电量

按用户指定，原状态文字位置改“手机电量N%”，使用本连接0xAE01参数6原始0..100值，有线/无线共同接收，无有效值不显示、重连清空、旧owner隔离。不读取车机/平板电池。参数百分比含义仍未独立验证，本轮是用户指定的实验性展示。导航/未导航均显示，其他布局不变。2285项测试全通过，2045包已验签交付；详见 `verification/apple-product/phone-power-label-20261120.md`。
# 2026-11-20 充电文字与电量颜色交付

既有0xAE00已订阅4/5/6，不重复添加。按用户给定参数5枚举解析充电状态：1显示“充电中N%”，其他保持“手机电量N%”；缺电量不显示。状态/电量增量合并且随连接清空。电量低于20优先红色，20及以上充电中绿色，其余白色。首次Release daemon消失失败，未交付；限制workers、禁用daemon后重试，并对最终颜色版重新测试/构建。2290测试全通过，2055包验签交付，详情/hash见 `verification/apple-product/phone-power-label-20261120.md`。
# 2026-11-20 5201字段表核查

按用户提供字段表检查源码与平板功能，发现主状态压布尔、阶段/前台/总数/显示单位缺失、ID13列表不完整、新索引缺详情可能残留旧转向、ID17=0不清车道、ID18旧名误导、组件/session与内容门槛不一致等问题。仅增加现状复现测试，不擅改实车生产逻辑；矩阵/优先级/证据限制见 `verification/apple-product/route-guidance-schema-audit-20261120.md`。平板不具备原车Binder验收能力，导航断流与USB二次连接仍独立待解决。
# 2026-11-20 更正“踏板”指代并核查0.12 APK

用户“踏板软件”是Jiaozi-HudBridge-0.12，不是平板。已只读反编译用户指定APK（SHA256 A18550A0AA6E062EAA1CB5943F456E06968480CC7746363D15067635CF0F603F），确认当前Binder接口/0.12 ready首快照逻辑存在，stop并非永久禁用；无有效turnKind时不能从空状态激活、车道generation/sequence和时间门槛可影响接收。新电量/充电UI未改踏板协议。尚无新车机runtime日志不能定责。工具限制/路径/证据与分层诊断见 `verification/apple-product/jiaozi012-bridge-apk-audit-20261120.md`。
# 2026-11-20 CarPlay＋踏板双端导航恢复交付

用户授权联合修复。CarPlay补5201状态/列表/元数据、缺详情清旧转向和距离、ID17=0清车道、组件/字段校验、UTF8 NUL和来源归一、本地route generation，保留旧wire。踏板独立复制0.12并升级0.13，修stop回报失败后的旧start收据、借有效输入tick重试失败启动状态，明确不抢跑原供应商/首发调度。原smali/res/stubs/wire保持。

CarPlay2306项测试0失败/错误/跳过，BUILD SUCCESSFUL in1m18s；Release SUCCESSFUL in1m50s，2129包已原证书/固定版本/zipalign/原工程hash验证。踏板679host断言＋56范围检查＋DEX1184内部引用检查通过，原证书签名0.13/code13包20261120213344已交付。目录Apple CarPlay内有两个APK及`双端导航恢复-20261120213344`源码/验证目录。全流程失败记录、hash、限制见 `verification/apple-product/joint-navigation-recovery-20261120.md`，实机回归清单另列。未安装/未实车验收；手机导航断流和USB二次连接不冒称已解决。
# 2026-11-20 沉浸第三张手机状态卡

按用户要求增加550×252手机状态卡，位于60/410；音乐下移至60/697，两侧间隙35，三卡底边949适配默认1080画布。电量/充电文字颜色移到新卡，并按本轮Power/Communications定义订阅、解析手机信号0..5和最大取电电流mA，未知不伪造。原4158 id4“无SIM卡”不再作为SIM事实使用；新卡不投仪表、不改踏板。详细协议/布局/小屏限制/验证结果见 `verification/apple-product/phone-status-third-card-20261120.md`。

最终2318项七模块强制测试0失败/错误/跳过，BUILD SUCCESSFUL in1m21s；Release BUILD SUCCESSFUL in2m2s，2155包原证书/固定版本/zipalign/原工程hash验证通过并交付。设置改名“三卡片颜色与透明度”，原存储键不变。签名Release未加固、未安装/未实机验收；新订阅需重连、无需更换踏板0.13。包hash与源码增量见详细记录。
# 2026-11-20 手机信号1格与双卡上报核查

22:00新日志4158 id0实际为1，id1为1且id4文本“无SIM卡”，没有收到4却画1的证据。用户随后明确实际两张SIM、开启另一卡后格数正常，强烈支持双卡状态/上报线路选择问题，不能认定整机无SIM。当前字段没有卡槽/订阅关联，不能宣称可选择主卡或同时读双卡。本轮未改生产代码/重订阅/换算/出包，仍使用2155。另交叉发现上轮通信表非信号字段3/8/9有映射冲突，作为待纠正项记录，不伪称已修。详见 `verification/apple-product/phone-signal-dual-sim-20261120.md`。
# 2026-11-20 手机卡紧凑图标单行与A单位

按用户要求去掉卡片标题/信号/电流标签和N/5格，以原横线147为新底边，手机卡550×147；单行从左到右信号图标、电池图标+百分比、电流A数，电池充电闪电与低电量红/充电绿保留。音乐上移至60/592、底844，两处间隙35。精确mA/1000转换，不改协议/踏板/双卡取值。测试/交付/限制见 `verification/apple-product/compact-phone-status-card-20261120.md`。

最终2320项七模块强制测试0失败/错误/跳过，BUILD SUCCESSFUL in1m27s；Release SUCCESSFUL in1m26s，2225包已原证书/固定版本/zipalign/原工程hash核验交付。旧包保留、签名Release未加固、未实机安装或验收；踏板0.13不变。
# 2026-11-20 2225版卡片完整布局参数

按用户要求整理当前沉浸导航卡与手机卡全部内容、外框/有效内部坐标、字形基线/字号、车道布局公式、电池与信号图标细节、A单位与颜色/未知值规则，共用背景及可见条件。已扣除实际绘制偏移，区别覆盖层坐标与卡内坐标；详见 `verification/apple-product/cards-layout-spec-2225-20261120.md`。本轮仅文档，无代码修改或新APK。
# 2026-11-20 手机卡550×100与三等分居中

按用户要求手机卡550×100，卡内三格等宽，信号/电池＋百分比/电流A值组中心分别91.6667/275/458.3333，共同y50；电池组按实际文字宽度整体居中。音乐上移47到60/545，底797，两处35间隙不变。只改UI/尺寸说明/回归测试，协议/踏板/双卡值保持。详见 `verification/apple-product/phone-card-100-equal-columns-20261120.md`，验证/交付结果后续记录。

最终七模块2321项测试0失败/错误/跳过，BUILD SUCCESSFUL in1m15s；Release SUCCESSFUL in1m25s，2244包原证书/固定版本/zipalign/原工程hash验证交付。源码增量/验证说明已归档；旧包保留、未安装或实机验收，签名Release未加固。
# 2026-11-20 当前车机定位功能核查

“向iPhone上报位置”默认关、保存重连生效；Geely定位仍沿用Android LocationManager（启动选GPS，GPS未启用回落network），转GGA/RMC经手机FFFA订阅后以FFFB发给手机，非CAN/惯导融合、不能强制替换手机定位。发现latest缓存无过期清理、GGA固定fix1/08卫星与RMC固定A等质量限制，未擅改或称已实车验证。详见 `verification/apple-product/vehicle-location-current-behavior-20261120.md`。本轮只读核查/文档，无新APK。
# 2026-11-20 下午车机定位现场复核

17:01/17:50/18:18/18:40四批车机设置均locationReporting:true。17:01文本去重95条FFFB，17:50两条，均为无线RFCOMM上GGA/RMC真实发送；17:50蓝牙→隧道交接后的可见窗口未见继续回传，需专项补查。18点两批仅设置证据，导出覆盖不足。22点平板关闭不代表下午车机关闭；已发数据也不等于手机采用/实测8卫星或GPS精度。只读记录、无新代码/出包，详见 `verification/apple-product/vehicle-location-afternoon-logs-20261120.md`。
# 2026-11-20 Vehicle Status是否接入核查

2244仅有A100/A101/A102消息目录名，没有识别声明、订阅处理、A101编码/车辆源/变化上报/停止清理链路；不能把有消息名说成已支持车辆状态。方向为手机A100订阅车机、车机A101上报，不同于手机电量或车机定位。本轮只读/文档，无实现/出包；见 `verification/apple-product/vehicle-status-capability-audit-20261120.md`。
# 2026-11-20 Vehicle Status取证范围更正为下午车机

用户明确晚上仅小米平板测试，不能作车辆功能现场判断。已单独核对17:01、17:50、18:18、18:40四批QUALCOMM CX11/Android9文本：17点实际握手发送表无A101、接收表无A100/A102，四批已见文本无A1三项收发；18点未覆盖握手，不虚构声明结论。结论为下午已观察连接未启用车辆状态上报，非平板无数据推论车机缺硬件。日志汇总vehicle-status-afternoon-log-summary.json，详细更正附在Vehicle Status核查文档。无代码/出包变化。
