package com.dbtool.carplay.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * DBPlay 语义色板。
 *
 * 命名规则（与 Material 惯例一致，便于迁移）：
 * - 无前缀 = 该角色本身（如 [primary] 是主色）
 * - `on*` = 画在该颜色**之上**的前景色（如 [onPrimary] 是主色上的文字色）
 * - `*Container` = 该角色的大面积浅底（Tag / 状态胶囊 / 选中卡片）
 *
 * **所有颜色只能从这里取。** 界面代码里出现 `Color(0xFF...)` 视为违规——
 * 否则"以后整体改 UI 只改 Design System"就不成立。
 *
 * 取值依据见仓库根目录 `DBPlay-UI-DESIGN.md` §3。色阶来自
 * Arco Design（`arcoblue` / `neutral`）与 TDesign（功能色），
 * 文字层级用 Ant Design 的透明度方案。
 */
@Immutable
data class CarColors(
    // ------------------------------------------------------------------ 主色
    /** 主色**填充**色：按钮底、选中态、进度条。浅深两套相同——白字对比度 5.19:1。 */
    val primary: Color,

    /** 主色的**文字/图标**用法。深色下必须比 [primary] 更亮，否则在深底上读不出来。 */
    val primaryText: Color,

    /** 悬停/聚焦态。 */
    val primaryHover: Color,

    /** 按下态。 */
    val primaryActive: Color,

    val onPrimary: Color,

    /** 主色浅底：选中卡片、主色 Tag。 */
    val primaryContainer: Color,
    val onPrimaryContainer: Color,

    // ---------------------------------------------------------------- 次要色
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,

    // ------------------------------------------------------------------ 背景
    /** 最底层背景（页面底色）。 */
    val background: Color,
    val onBackground: Color,
    /** 卡片/面板底色。 */
    val surface: Color,
    val onSurface: Color,
    /** 次级表面（卡片内嵌区块、输入框底、分段控件轨道）。 */
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    /** 抬升表面（对话框、浮层）——比 [surface] 更亮/更暗一档。 */
    val surfaceElevated: Color,

    /**
     * **选中态背景**（2026-10-23 设计系统重构新增）。
     *
     * 左侧导航选中项、列表当前项、分段控件当前段都用它 —— 统一成一个
     * **低对比度**底色，不用主色块（车机深色下大面积高饱和色很刺眼）。
     *
     * 之前各处分别用 `primaryContainer` / `surfaceVariant` / 手写值，
     * 同一语义三种深浅，是页面之间"看起来不统一"的主要来源之一。
     */
    val selectedBackground: Color,

    /**
     * **左侧导航栏背景**（2026-10-23 特斯拉风格改造新增）。
     *
     * 比 [background] 略亮一档、比 [surface] 略暗一档 —— 让导航栏与内容区
     * 形成"同一纵深、不同工种"的层次，而不是靠一条竖直分割线。
     * 特斯拉车控页的左侧菜单就是这种处理：整块底色比页面稍亮，不用边框。
     */
    val navBackground: Color,

    // ------------------------------------------------------------------ 文本
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,

    // ------------------------------------------------------------------ 线条
    /** 列表内部分割线、顶栏下沿。极淡——它只负责"提示边界"，不负责"划分区块"。 */
    val divider: Color,
    /** 常规描边：输入框、描边按钮、次级容器。 */
    val outline: Color,
    /** 强调内容的描边（选中态卡片）。 */
    val outlineStrong: Color,

    // ------------------------------------------------------------ 语义状态色
    /** 状态色的**强**档：图标、状态点、按钮底。 */
    val success: Color,
    val onSuccess: Color,
    /** 状态色的**浅底**档：状态胶囊、提示条。 */
    val successContainer: Color,
    val onSuccessContainer: Color,

    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,

    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,

    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,

    // ------------------------------------------------------------------ 其它
    /** 视频区/全屏时的底色。不能纯黑，否则与车机系统黑边无法区分。 */
    val videoBackground: Color,
    /**
     * **视频区/顶栏专用的文字色**（2026-10-05 配色修复）。
     *
     * 顶栏底衬恒为 [videoBackground]（近黑 `#0E0F12`，因为视频区天然是暗的），
     * 所以它上面的文字**必须恒定是浅色**，不能跟着 `textPrimary` 走主题。
     *
     * 旧实现直接用 `textPrimary`：浅色主题下那是 `#1D1D1F`（近黑）——
     * **黑字压黑底**，顶栏标题几乎看不见。车机默认跟随系统，多半正好走浅色主题。
     */
    val onVideoSurface: Color,
    /** 视频区次要文字（副标题、提示）。比 [onVideoSurface] 弱一档但仍保证可读。 */
    val onVideoSurfaceVariant: Color,
    /** 覆盖层遮罩。 */
    val scrim: Color,
    /** 半透明浮层底色（日志浮层等）。 */
    val overlaySurface: Color,
    /** 强调色上的图标/文字（按钮内）。 */
    val onAccentSurface: Color,
)

/**
 * 浅色板 —— **产品默认主题**。
 *
 * 页面底色 `#F7F8FA`（Arco neutral-1），卡片纯白，靠**色差**建立层级而不靠描边；
 * 文字用 `#1D2129` 的透明度分四级；主色 `#165DFF`（Arco arcoblue-6）。
 */
internal val CarLightColors = CarColors(
    primary = Color(0xFF007AFF),
    primaryText = Color(0xFF007AFF),
    primaryHover = Color(0xFF3399FF),
    primaryActive = Color(0xFF0056D6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6F2FF),
    onPrimaryContainer = Color(0xFF0056D6),

    secondary = Color(0xFF6E6E73),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF2F2F7),
    onSecondaryContainer = Color(0xFF6E6E73),

    background = Color(0xFFF5F5F7),
    onBackground = Color(0xFF1D1D1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D1D1F),
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFF6E6E73),
    surfaceElevated = Color(0xFFFFFFFF),
    selectedBackground = Color(0xFFE8E8ED),
    navBackground = Color(0xFFF0F0F3),

    textPrimary = Color(0xFF1D1D1F),
    textSecondary = Color(0xFF86868B),
    textTertiary = Color(0xFFA1A1A6),
    textDisabled = Color(0xFFD1D1D6),

    divider = Color(0x0F1D1D1F),
    outline = Color(0xFFE5E5EA),
    outlineStrong = Color(0xFF007AFF),

    success = Color(0xFF2BA471),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFE3F9E9),
    onSuccessContainer = Color(0xFF1A7A52),

    warning = Color(0xFFE37318),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFF1E9),
    onWarningContainer = Color(0xFF9C4A0A),

    error = Color(0xFFD54941),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFF0ED),
    onErrorContainer = Color(0xFFA3241C),

    info = Color(0xFF007AFF),
    onInfo = Color(0xFFFFFFFF),
    infoContainer = Color(0xFFE6F2FF),
    onInfoContainer = Color(0xFF0056D6),

    videoBackground = Color(0xFF0E0F12),
    // 视频区/顶栏文字恒定浅色：其底衬恒为 #0E0F12（近黑），跟着主题走会在
    // 浅色主题下变成"黑字压黑底"。两套主题都给同一组浅色值。
    onVideoSurface = Color(0xFFF2F3F5),
    onVideoSurfaceVariant = Color(0xB3F2F3F5),
    scrim = Color(0x66000000),
    overlaySurface = Color(0xF2FFFFFF),
    onAccentSurface = Color(0xFFFFFFFF),
)

/**
 * 深色板 —— 夜间/隧道。
 *
 * 中性阶与浅色板同源（Arco neutral 的深端），只是把方向反过来：
 * 页面底色最深、卡片抬一档、浮层再抬一档，**层级靠明度差**而不是靠描边。
 *
 * [primary] 与浅色板保持一致（`#165DFF`）：它是**填充**色，白字压在上面对比度
 * 与主题无关。真正需要随主题变的是 [primaryText]——深底上必须用更亮的 `#6AA1FF`。
 */
internal val CarDarkColors = CarColors(
    primary = Color(0xFF165DFF),
    primaryText = Color(0xFF6AA1FF),
    primaryHover = Color(0xFF4080FF),
    primaryActive = Color(0xFF0E42D2),
    onPrimary = Color(0xFFFFFFFF),
    // 主色 18% 叠在深底上的结果，比直接取色阶深端更柔和。
    primaryContainer = Color(0xFF172445),
    onPrimaryContainer = Color(0xFF6AA1FF),

    secondary = Color(0xFFA9AEB8),
    onSecondary = Color(0xFF17181C),
    secondaryContainer = Color(0xFF26282D),
    onSecondaryContainer = Color(0xFFE5E6EB),

    // 2026-10-23 校准（对齐 Dashboard Design System 的层级思路）：
    // 背景 → 表面 → 次级表面 → 抬升，四级各差一档明度，靠明度而不是边框分层。
    background = Color(0xFF17191E),
    onBackground = Color(0xFFF4F5F7),
    surface = Color(0xFF23262D),
    onSurface = Color(0xFFF4F5F7),
    surfaceVariant = Color(0xFF292C34),
    onSurfaceVariant = Color(0xFFA1A6B0),
    surfaceElevated = Color(0xFF2A2D33),
    // 2026-10-23 实测校准：`#30333B` 与未选的 `surfaceVariant #292C34` 只差 7 阶，
    // 截图上几乎分不出哪个是选中态。提亮到 `#3A3E47`，差 17 阶，一眼可辨。
    selectedBackground = Color(0xFF3A3E47),
    navBackground = Color(0xFF1C1E24),

    // 2026-10-23：次级/弱化文字从"白色 alpha"改**实色**。
    // alpha 叠在 surface / surfaceVariant / selectedBackground 上会出现三种明度，
    // 同一段说明文字在不同卡片里看起来深浅不一 —— 这是页面间不统一的隐形来源。
    textPrimary = Color(0xFFF4F5F7),
    textSecondary = Color(0xFFA1A6B0),
    textTertiary = Color(0xFF747B87),
    textDisabled = Color(0x66F4F5F7),

    divider = Color(0x14FFFFFF),
    outline = Color(0xFF33363C),
    outlineStrong = Color(0xFF6AA1FF),

    // 深色下功能色整体提亮一档：低饱和的浅色版本在深底上会糊成一团。
    success = Color(0xFF3FB183),
    onSuccess = Color(0xFF17181C),
    successContainer = Color(0xFF1B312B),
    onSuccessContainer = Color(0xFF3FB183),

    warning = Color(0xFFF08C3C),
    onWarning = Color(0xFF17181C),
    warningContainer = Color(0xFF3C281B),
    onWarningContainer = Color(0xFFF08C3C),

    error = Color(0xFFE86A62),
    onError = Color(0xFF17181C),
    errorContainer = Color(0xFF392123),
    onErrorContainer = Color(0xFFE86A62),

    info = Color(0xFF4080FF),
    onInfo = Color(0xFF17181C),
    infoContainer = Color(0xFF172445),
    onInfoContainer = Color(0xFF6AA1FF),

    videoBackground = Color(0xFF0E0F12),
    // 视频区/顶栏文字恒定浅色：其底衬恒为 #0E0F12（近黑），跟着主题走会在
    // 浅色主题下变成"黑字压黑底"。两套主题都给同一组浅色值。
    onVideoSurface = Color(0xFFF2F3F5),
    onVideoSurfaceVariant = Color(0xB3F2F3F5),
    scrim = Color(0xA6000000),
    overlaySurface = Color(0xE61D1F23),
    onAccentSurface = Color(0xFFFFFFFF),
)
