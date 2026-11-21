# Apple CarPlay 新产品设置清理与同版本替换

仅改独立工程DBPlay-ImmersiveNav。用户明确要求替换当前DBPLAY，故保留包名io.dushu.car与原证书，版本名/码均与原1.4.2/code375相同；本工程build.gradle.kts按此新要求冻结为读取到的基线版本，不调用自动递增。旧工程规则不变。

## 可见界面

> 2026-11-21车辆遥测与安全开关版已新增交付`AppleCarPlay-1.4.2-375-202611210125.apk`，原包名／版本／证书不变、默认关闭车辆读取。8模块2468项本地测试通过，Release验签／对齐通过；未安装／未实车验收。完整交付及保护局限见`verification/apple-product/vehicle-read-safety-release-20261121.md`。本条不覆盖下文历史阶段说明或旧交付文件。

> 2026-11-19完整交接汇总：见 [改动、排查与踩坑总表](APPLE-CARPLAY-CHANGES-AND-PITFALLS-20261119.md)。涵盖音频声明/实验、USB二次连接与VPN、上传遗漏、互联网选项、封面防闪、原车热点冷启动及逐包验证；明确区分已实现、仅分析和未验收事项。

2026-11-19用户随后授权将USB二次连接修复出包，并要求开关改为有线100声明192kHz/24bit兼容44.1/48k：本轮仅交付已实现的USB生命周期修复。192kHz/24bit请求尚未实现：当前无可靠格式位定义，AndroidMediaSink固定ENCODING_PCM_16BIT、LPCM路径byteSwapS16及每采样2字节；不得只添加未知位或UI文字假装支持。本轮不修改现有实验开关行为，交付说明明确保留其102/LPCM实验及已知无线不兼容风险。七模块测试沿用本次USB修复已强制重跑的2238项成功结果，另实际运行Release构建、验签及原工程hash核验。

2026-11-19用户要求“先修USB二次连接代码，不出包”：拔线/当前有线AirPlay结束/有线iAP2控制通道结束时，底层单次关闭并通知拥有者（含AirPlay未激活的情况）；桥接层按控制器身份、generation、模式及closed状态守卫退休旧activeHandle，等待旧controller.awaitClosed及sink/focus/MFi资源清理后才重建控制器恢复USB监听和发现轮询；停止、切无线、已开始新重连时取消旧任务；旧资源清理超时不自动叠加新栈。start也识别残留已关闭有线handle，而非无条件直接返回。有线控制通道退出finally补detachWired兜底。本轮不改音频声明/实验开关/网络地址，不出APK；单元策略与源码接线测试不代表实车两次插拔验收。

2026-11-19继续修复USB后上传失败：发现此前USB VPN仅配置IPv6地址/fe80::/64路由，未配置或allowFamily放行IPv4。Android VpnService.Builder官方文档明确：未配置且未放行的地址族默认阻断，allowFamily(AF_INET)在不加IPv4路由的情况下允许流量回落原网络。车机此前诊断DNS为IPv4，因而此遗漏是与DNS失败直接相关的配置缺陷，上一版仅补退出清理不完整。本次仅在establish前加入allowFamily(OsConstants.AF_INET)，不添加IPv4/互联网默认路由、不改DNS/上传地址，保留VPN清理修复。新增构建器源码约束回归测试；不是设备端网络验证，不能据此宣称所有USB后上传失败已解决。参考：https://developer.android.com/reference/android/net/VpnService.Builder#allowFamily(int)。

2026-11-19按用户要求修复USB VPN退出生命周期：匹配当前iPhone设备的拔线广播关闭控制器，拆除有线栈优先detachWired；已激活的有线AirPlay会话结束时按attachment代次且无剩余会话释放VPN，旧代次不得关闭新的无线监听；无线启动在热点建立之前显式detachWired，attachWireless同时清理残留tun/bridge而不只检查active。release先关闭TUN，再逐项尽力关闭监听/会话/NCM桥，避免前项异常跳过VPN释放；增加onRevoke清理。不关闭第三方VPN、不修改DNS/上传端点/热点/音频能力。此修复针对残留VPN风险，不把用户报告的DNS失败根因或联网恢复冒充为已实车验证。

2026-11-19按用户授权定向移植DiPlay USB修复（非整体同步）：从已保存DiPlay-main/shared源码移植UsbConfigurationSelection及NcmUsbBridge有界重试（GPL-3.0，参考问题#518）。配置切换失败先GET_CONFIGURATION回读，已在目标配置则直接继续；否则强制占用各接口脱离阻塞驱动，close而非releaseInterface释放临时占用，再openDevice重开并重试一次。已知仍为错误配置则报清晰错误；回读不可用保留原有尝试接口占用行为。NCM控制/数据接口占用及备用设置最多5次、间隔100ms，失败仍清理资源。无ADB、root、驱动安装、系统授权修改；无线、音频声明/开关、帆书及导航实现均不改。新增策略测试，不等于实车USB连接验收。

2026-11-19补齐音乐100旧能力并集：修正此前所有media直接跳过合并的遗漏，仅100/media并入旧LPCM集合，默认48k输出0xcbfc（旧0xc3fc与现有0x8800并集）；102保持关闭AAC-LC双采样率、开启实验双采样率LPCM。麦克风、帆书媒体/焦点、热点及3/15路由不变。每次/info记录实际条目的type/audioType/output/input十六进制位及microphone/disableAudioOutput/experimentalBufferedLpcm，不再用固定“PCM+Opus”标签代表实际能力。新增实际bplist序列化回读测试覆盖有线/无线、麦克风开关、实验开关组合。此补齐不代表已证实音频通道消失的唯一根因或实车恢复。

2026-11-19无线音频兼容追加：按用户要求保留当前能力集合，以OR并集补回旧版工作过的非media格式声明。compatibility/default/alert/telephony/speechRecognition/type101补回旧LPCM多采样率、原有相应Opus位；microphone=true时同步并入旧输入集合，false仍不声明输入。compatibility补位不删除新版原有Opus位。100/media保持44.1+48k16bit双声道LPCM，102关闭开关为双采样率AAC-LC、开启为双采样率实验LPCM，不改变UI及收流状态页、3/15路由、焦点和缓冲。此为兼容性修复尝试，并非已证实手机拒绝根因或恢复音频的实车验收。用户20:00诊断microphone=false/higherQualityMedia=false，视频成功但未见当日音频SETUP，故不能归因102 LPCM开启或帆书广播丢失。

2026-11-19恢复开发者日志入口：用户反馈无线失败且无法上传日志，恢复关于页“应用版本”点击动作、7次解锁进度及解锁后开发者模式入口，复用既有DeveloperScreen/collectAndUploadLogs上传链路。只恢复页面可达性，不更改音频声明、连接策略、认证材料、上传端点，不启用内置ADB/远程隧道。开发者页历史ADB探测/抓包依赖仍不可用，不承诺恢复；日志上传需实车联网验证，本地不试传生产日志。

2026-11-19后续按用户纠正：删除音乐/Siri/导航/通话/麦克风的手动采样率/声道上限菜单，删除CarSessionSettings六个上限字段与prefs读取/写入，旧audio_quality_*键不再消费，不会保留用户误设的44.1k限制。共享格式声明使用固定支持集合，100/media为16bit双声道LPCM44.1+48k，102/media关闭开关为AAC-LC44.1+48k、开启为实验性LPCM44.1+48k，iPhone实际选择。各语音用途按已有传输能力声明，不再通过用户偏好限采样率；通话声明扩展为该传输支持集合（有线8/16/24/32，无线16/24/48），麦克风仍有线8/16、无线16k单声道。

原菜单位置改“当前音频流规格”只读卡，显示音乐/Siri/导航或提示/通话/麦克风/其他。数据不是从设置或/info复制，而从UpstreamMediaSinkBridge的onAudioStarted/onAudioStopped、onMicrophoneStarted/onMicrophoneStopped获取，含codec/rate/channels/type；LPCM额外标16位，AAC/Opus不冒充源位深。未协商/当前无活动流不填默认规格，已结束流移除。每个SessionHandle持有独立observer；事件仅触发重新读取当前activeHandle，不携带旧会话快照，旧连接迟到事件不能覆盖新连接数据。纯JVM验证registry与源码接线，不代表Android/iPhone实车验证；收流状态不证明DAC输出或bit-perfect。“使用无损传输”及重连提示继续保留，实验声明不变。

2026-11-19音频质量适配（覆盖前文102单48k声明）：参照上游xcertplay1.4.1的CarPlayAudioFormats/AudioQualityProfiles按用途生成位掩码，不整体合并上游。新增本分支AudioQualitySettings、CarPlayAudioFormats与API CarAudioQuality校验表；音乐100仅16bit双声道LPCM44.1/48k，102默认双声道AAC-LC44.1/48k。“使用无损传输”开启时102改用与100相同的LPCM位掩码，关闭恢复AAC-LC。音乐上限48k时两档都声明，上限44.1k时排除48k。LPCM102仍属用户指定实验，不保证iPhone接受，不增加24bit/96/192k或无损音源承诺。

车机新增“音频质量上限”卡：音乐/Siri/导航/通话/麦克风分别设置采样率上限，导航支持的有线单双声道可选，音乐固定双声道，Siri/通话/麦克风固定单声道（协议格式表不提供任意声道）。菜单按有线/无线限制：音乐44.1/48k；Siri有线24/32/44.1/48k、无线24/48k；导航有线44.1/48k及1/2声道、无线Opus48k单声道；通话有线8/16/24/32k、无线16/24/48k；麦克风有线8/16k、无线16k。上限表示允许的格式集合，非强制采样率。跨传输方式的历史上限若无法形成任何合法格式，则回落至可用集合，不发空掩码，UI显示有效上限。

默认上限音乐48k双声道、Siri48k单声道、导航48k（有线最高双声道，无线单声道）、通话24k单声道、麦克风16k单声道。旧偏好无键使用默认；API产品约束校验、prefs load/save与诊断均已接线。六个字段不进LIVE_FIELDS，改变上限或开关均需重连并沿用右上角重连生效按钮；3/15输出路由、焦点、媒体缓冲、原车卡片和111逻辑不变。

适配边界：没有整套移植上游音频菜单/动态MediaCodec逐格式探测，也没有移植AAC-ELD编码器/关闭Opus选项。本分支只提供现有编解码器已实现的PCM/AAC-LC/Opus组合，不能称为完整升级上游1.4.1音频栈；当前源码/单测验证不等于实车Siri/电话/麦克风验收。共享代码参考https://github.com/shilapi/xcertplay/commit/0bed3d9768cd1375ce6a464f65907352d3e746d4，保留GPL-3.0来源。

2026-11-19音频实验开关按用户要求更名为“使用无损传输”，仅改显示名称，higher_quality_media存储键、默认关闭、102 LPCM实验声明及关闭恢复AAC-LC不变。名称不代表手机已接受或全链路无损/Hi-Res，原实验说明保留。

2026-11-19实验音频开关：“车机”音频卡新增“使用更高音质”，默认false。开启仅将/info type=102/media的audioOutputFormats由AAC-LC改为16bit双声道LPCM（默认48kHz bit15；44.1kHz bit11），关闭恢复原AAC-LC bit23/22。type100/101、语音、麦克风、3/15路由及媒体缓冲均不变。higherQualityMedia持久化并映射到immutable AirPlayConfig.experimentalBufferedLpcm；不进LIVE_FIELDS，双向切换均通过已有待重连比较在右上角显示“重连生效”。手机实际选择以SETUP/audio format日志为准。

重要限制：type102 LPCM是用户指定的实验性能力声明，不是已核验的Apple协议保证，也不代表已成功接收/播放LPCM或音质必然提升。上游当前实现为type102 AAC-LC，无iPhone/实车环境验证本组合；UI明确可能无声，异常请关闭后重连恢复AAC-LC。不自动退回AAC以免开关显示与实际声明不符。单测仅验证声明、配置接线及重连策略，不能证明手机支持。

沉浸111系统返回键追加：视频组件内部增加BackHandler，仅在沉浸模式且实际显示111时优先处理系统返回，清空手机触点后请求110并沿用首帧保护，不弹出CarPlay页面、不重连。已显示110时处理器停用，返回键继续交给原应用导航栈。该入口与三指右滑同用ImmersiveNavigationPolicy，窗口记忆继续以实际完成的切换为准；诊断标记source=system_back。真实车机系统是否向应用派发返回仍需实车核验。

最新设置精简（覆盖历史同名文案）：麦克风改“使用车机麦克风”，导航焦点说明改“导航播报时通过按键或旋钮调节导航音量”，方控分组改“使用方向盘按键控制媒体”。显示页摘要仅保留fps/编码，不再附带隐藏底部栏等系统栏说明，不改变栏的实际配置。分类改为“车机 / 投屏仪表 / 设置”。

隐藏车机媒体卡片/上报正在播放及熄火自动暂停设置块，运行时媒体上报经forAppleCarPlay固定true，独立powerPause读口固定默认true，旧false偏好不再关闭功能；不改变现有娱乐电源状态识别和15秒暂停/断开策略，不宣称增加独立车门接口。媒体缓冲改为200/250/300/350/400/450/500毫秒七档，UI/保存校验共用同一档位表，默认300；旧1000等非法值沿用校验回落300，不改通话/Siri/导航的小缓冲策略。

删除歌词来源说明行及“不依赖仪表主题”文字，保留歌曲/歌词卡功能。删除双栏和窄屏“恢复默认设置”按钮、确认弹窗、UI动作及controller重置实现；安全区/图标等局部恢复不在此删除范围。设置底部避让从128物理像素改为110物理像素，主题背景不变，沉浸安全区0/72/1920/880保持独立不变。

右侧选项选中态追加：CarChoiceChip选中填充从selectedBackground灰色改为当前主题primary（与Switch.checkedTrackColor同源），启用且选中的文字用onPrimary白色；未选中surfaceVariant及禁用文字规则保留。浅色/深色均跟随主题蓝色，不修改左侧导航选中底色或其它全局配色。

左栏比例后续调整：按用户最新要求，NAV_RAIL_SCALE从1.2改为1.1，基于原始尺寸而非在1.2基础上叠乘。左栏宽度、所有选项卡（含关于/恢复默认）的行高、文字、图标与内部间距同步采用1.1；右侧内容不变。下文1.2为历史记录，以本条1.1为准。

应用设置与左栏放大追加：“身份与外观”入口/右侧标题统一改为“应用设置”（保留IDENTITY内部枚举，避免破坏导航语义）。双栏设置左侧宽度、所有条目（含关于/恢复默认）高度/图标/文字字号及行高/内部间距放大1.2倍，使用真实布局尺寸而非graphicsLayer视觉缩放，点击区随之扩大；上方入口区保留独立滚动，右侧内容与全局UI比例不变。

设备标识追加隐藏：移除“设备标识”设置块及设备名称/制造商/型号/OEM标签/恢复出厂标识控件；底层四字段仍由产品边界固定Lynk&Co，外观、AirPlay图标、驾驶侧保留。麦克风回传开关本轮仅解释，不擅自删除或关闭。

VIEW调查（非修复结论）：GeelyClusterPresenter通过稳定3→1回读让ClusterProjectionIntent进入closed抑制，阻止自动抢屏；mode-off日志并非VIEW按键身份。当前没有可靠VIEW键toggle接线，关闭后的重开仍可走设置或三指手动请求。本轮没有实车连续两次VIEW的输入/模式日志，不宣称第二次按键已被识别，也不启用模式1定时重抢以覆盖用户关闭。

设置左栏追加精简：删除“日常 / 仪表 / 系统”三个分类文字与标题行，不保留空白标题占位。各设置入口、原分组内顺序、关于和恢复默认设置均保留；不删除关于页面独立的“系统”信息标题。

## 沉浸导航返回窗口、内容选择与默认安全区追加

仪表投屏开关打开后，该设置卡内明确显示“重新连接”按钮，调用既有applySettings(draft,reconnect=true)，不会自行断线重连；开关原有即时显隐行为保留。

沉浸视频视图原先每次重建都configure(true)而重新请求111，现由Application持有的CarAppController保存最后实际显示的110/111。重建视图时恢复该目标，仍以对应视频首帧为切换门槛，未出帧的手势请求不覆盖已显示窗口的记忆。不把该状态写成产品偏好，不改变手机触控隔离。此项有纯状态机与源码接线测试，不代表已执行真实Android外部应用切换验收。

沉浸导航安全区出厂与“恢复沉浸窗口默认安全区”均为x=0,y=72,w=1920,h=880（对应top72/bottom128），保持1920×1080画布。无存储键或旧空矩形采用此默认，已有有效自定义矩形保留；数字框空矩形展示口径同步更新。此区域与设置页底部128px留空是独立设置，仍需重连。

“沉浸窗口显示iPhone导航卡片”改名“沉浸导航显示苹果原生卡片”，与“沉浸导航内容对齐”放在同一内容卡中。移除错误的居中/靠左/靠右按钮，改用与仪表内容相同的CarChoiceDialog单选：0左侧→leftAligned，1右侧→rightAligned，2顶部→topAligned。协议映射不变，旧数字不会偷偷迁移成另一方向；显示标签纠正为真实协议含义，不再承诺手机必然画在几何中心。

“沉浸窗口仅地图布局”保留并增加说明：只在原生卡片关闭时发送maps:/car/instrumentcluster/map?maneuverLayout=...；卡片打开时发送instrumentcluster卡片URL，优先级更高。两者都关时发送纯地图URL。该选项不是本机裁图、不是保证隐藏某个手机组件，具体表现由iPhone导航应用决定。

## 最新连接方案更正：GEELY_ROUTED（覆盖下文历史TCAM口径）

用户明确要求默认使用“车机热点”而非“复用车机热点”。出厂hotspotMode与forAppleCarPlay产品边界现均固定GEELY_ROUTED，旧偏好/导入经load/save/controller/session约束同样切换。实际使用现有GeelyRoutedHotspotManager扫描eth*上的198.18.x.x（eth0优先），不是TCAM的eth0.11/Ethernet判定；不回退TCAM，不自建热点，不引入ADB。仍保留原有Android 9 KX11/CS11/CX11机型校验，失败提示不再引导到已删除方案。

凭据自动读取是独立的产品能力：新增readsAppleCarPlayHotspotCredentials允许GEELY_ROUTED在简报中只读LocalSoftApReader并回传凭据，由现有controller填入保存字段；未改变全局reusesCarNativeHotspot或路由后端的识别语义。路由方案继续从保存的manualHotspotSsid/passphrase构建通告。读不到时保留手填/保存值，未知密码不清空；默认Lynk&Co/12345678不变。反射读到的配置是否与车机当前广播一致仍取决于固件，手填兜底保留，不能把本地测试当作实车连通证明。

### 本轮追加清理

### 设置页底部与音频精简追加

设置页统一向上预留128个物理像素：LocalDensity将128px转为dp，父级先绘制CarTheme.colors.background再padding，底部条带随明暗模式变化，不改变CarPlay110/111视频画布和手机安全区声明。

原车仪表卡片偏好不再以hud.supported置灰；允许连接前保存，但真实supported/available保持原值并显示诊断。该修改不是绕过平台选择或宣称已恢复踏板服务，实际无接口/无绑定仍不推送。

移除高级通道映射、媒体/导航流号选择及两个试听入口；forAppleCarPlay对旧偏好/导入固定媒体3、导航15、advancedAudioChannelMapping=false。数值为用户明确指定，实际ROM支持及音频路由需实车核验；原本真实建轨策略仍由platform能力决定。

删除独立仪表“导航信息卡”与横纵位置设置，读取旧instrument_cards导航偏好也固定false，避免看不见开关但仍残留叠层；歌曲与歌词卡及其位置设置保留。这里删除的是DBPLAY自绘叠层，不是原车仪表卡片，也不是iPhone绘制的111导航卡片。

启动分类从设置导航删除；开机自动启动开关移动到车机配置，保留原偏好键和BootReceiver行为。删除无效的启动性能优化开关，产品仍禁止ADB/启动编译。

“显示主页导航卡片”只控制DBPLAY主页导航入口（HomeScreen中有有效导航时显示），不控制饺子踏板输出的原车仪表卡片或手机111导航卡片。历史通用projection的navigationProvider会读取该字段，但主题皮肤已删除且独立自绘导航叠层已禁用，不再有可用主题导航卡受它联动。不得把旧注释“仪表中央自绘卡片”误认为原车卡片。

后续用户要求删除该主页入口设置：已删除显示页开关与说明区，主页导航入口本身、原偏好存储及原车/iPhone卡片功能不删除，显隐仍遵从已保存值。另核对clusterManualCruise“允许无导航时手动开启”：当前生产投屏没有读取它作门控，只保留偏好和诊断，属于遗留无效开关；本轮用户仅询问用途，未擅自删除。

再后续用户明确要求删除该无效功能：现已移除“显示时机/允许无导航时手动开启”UI、clusterManualCruise会话字段及偏好读取/写入/诊断，旧键不再消费，不影响实际手动投屏。精简原车卡片说明至第一个分号前一句、投屏开关说明至“右滑收起”；仪表内容去掉副标题，卡片标题改为“仪表显示苹果原生导航信息卡片”。删除音频通道0=自动那行文字。

沉浸式安全区增加独立左X/上Y/宽W/高H数值框与应用按钮，未设定显示0/0/1920/1080。编辑未完成/非法/整数溢出不提交，宽高必须正数；按现有CarSettingsSafeArea.normalized夹入1920×1080并保持300×200最小矩形。拖框/数值应用/全屏重置相互同步，只写immersiveNavigationSafeArea，不改变传统仪表1920×720安全区；应用保存后仍需重连。

首次启动赞助弹窗的状态、首次标记读取/写入和渲染分支已实际删除，新装和覆盖安装都不依赖“已看过”标志。默认热点名称为Lynk&Co、密码12345678；原有自动读取车机真实凭据仍优先于默认值，读取失败保留已保存值。连接页热点密码以明文显示（按用户要求），日志仍不记录密码。

系统栏页只隐藏“全屏”“隐藏底部栏”两个操作项，不擅自改写其已有保存值或改变系统栏底层行为。仪表分类新增独立“沉浸式导航（与仪表投屏互斥）”选项，其设置不再嵌套在传统仪表页；传统入口更名“仪表投屏设置”。两套卡片/安全区字段及互斥策略不变。关于页已删除抖音作者行和Lynk&Co说明行，仅保留产品名及必要版本信息。

- 应用label为Apple CarPlay；关于页不再提供检查更新、后台自动更新或ADB开发者入口，根UI更新弹窗移除。
- 删除赞助、全屏模式设置分区的枚举及导航条目，赞助二维码资源删除。
- 仪表导航页不再调用画面旋转、地图缩放/本机缩放及高级折叠设置组。保留沉浸111独立卡片和安全区设置，以及传统仪表投屏/原车仪表卡片正常链路。
- 连接页只保留USB有线/无线、配件认证和车机热点。旧手动复用、局域网、自建ADB模式不再可选；后台强制使用TCAM_HOTSPOT（原厂车机热点）连接，不自行开启或改写系统热点。
- 车机热点加密选项仅OPEN和WPA2；名称、密码在现有只读接口返回成功后自动填入并保存。null密码是读取失败，保留旧密码；明确空密码才为OPEN。密码不写日志。读取受厂商接口/当前UID权限限制，不能保证每个固件都返回密码；保留手填和重新读取。
- 设备名称、制造商、型号、OEM标签固定Lynk&Co，旧偏好/导入也不能覆盖；界面外观默认SYSTEM，升级首次迁移一次，之后仍允许用户在可见外观选项中改选择。
- 蓝牙握手固定DISCONNECT_LINK（仅断目标手机，不关闭车机蓝牙适配器），隐藏选择器。ADB移除后不再有特权ADB断链后备；应用内API被系统拒绝时只能如实记录失败，保持适配器开启。默认策略不是物理断开成功保证，需实车确认。

## 不是仅隐藏

CarSessionSettings.forAppleCarPlay在偏好读取、保存、控制器应用和会话更新处约束旧设置；debugTunnel/内置ADB/启动ADB编译为false，clusterFlip180为false，不让旧偏好重新启动已移除功能。

AdbShellSession原执行后端删除，仅保留不可用兼容边界：ensure/check/init/repair不启动任何通道；runner访问明确拒绝。启动ADB检查、看门狗和反连启动调用删除，系统ADB日志录制不再启动；4个随包ADB/BusyBox二进制删除并在打包中排除。保留本应用日志/诊断。源码中旧诊断/适配器类型仍可能为编译兼容存在，但没有可用ADB后端；不操作系统adbd，不关闭系统自带ADB。

更新后端由DisabledProductUpdates替代，不创建CarUpdateManager，不查询/下载/安装，不允许旧自动更新偏好恢复网络更新。相关旧功能测试不再适用，已新增产品约束、只读凭据填充和后端禁用测试；其它导航、音频、沉浸、触摸回归继续运行。

应用清单中安装其它APK的REQUEST_INSTALL_PACKAGES权限也已移除。

部分历史私有UI函数暂保留为不在产品导航中的迁移上下文，Release由R8剔除；不把源码中的历史标签等同于界面仍可操作。最终验收以生产入口、禁用后端及最终APK资源/身份检查为准。

## 构建与签名

仅在本项目使用显式`-PallowRelease=true --no-configuration-cache`打包。原签名配置通过授权的`-Pdbplay.signingDir=<原目录>`只读使用，不复制口令或私钥到交付文件。目标证书SHA256仍为30ded7ab47342f2b3a4746f42c37544cdd6ed181d1e257c37d3c3cc93756a97e。

APK放C:\Users\17900\Desktop\饺子互联\Apple CarPlay，独立文件名防覆盖历史；同包/同版本/同证书用于人工覆盖安装，不宣称已在车机安装。ADB/BusyBox、赞助资源须在最终APK中不存在。未加固签名Release，不标protected-signed。实际文件名、SHA和测试数量以交付清单为准。
