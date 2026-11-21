package com.dbtool.carplay.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 自绘图标集。
 *
 * **为什么不用 `material-icons-extended`：**
 * 1. 那是一个独立 artifact，引入它需要改上游的 `gradle/libs.versions.toml`
 *    ——每多改一个上游文件，就多一份长期同步要处理的冲突；
 * 2. Material 图标集是"Android App 味"最重的视觉元素之一，
 *    直接用会让界面一秒露馅成"装在车机上的 APK"，而这正是要避免的。
 *
 * 这里全部是 24x24 视口的线性描边图标，颜色由使用处的 `Icon(tint = ...)` 决定，
 * 因此同一套图标在深色/浅色、正常/高亮状态下都通用。
 */
object CarIcons {

    /** Local 111 entry, visually similar to a screen/AirPlay shortcut; not a phone application. */
    val ImmersiveNavigation: ImageVector by lazy {
        strokeIcon(
            "CarImmersiveNavigation",
            "M7 17H3.5V4.5h17V17H17",
            "M12 13l5 7H7z",
        )
    }

    /** 主页（仪表风格）。 */
    val Home: ImageVector by lazy {
        strokeIcon(
            "CarHome",
            "M4 16a8 8 0 1 1 16 0",
            "M12 16l4.2-4.6",
            "M12 16h0.01",
        )
    }

    /** 设备（手机轮廓）。 */
    val Device: ImageVector by lazy {
        strokeIcon(
            "CarDevice",
            "M8 3.5h8a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2v-13a2 2 0 0 1 2-2z",
            "M11 18.5h2",
        )
    }

    /** 显示（外接屏轮廓）。 */
    val Display: ImageVector by lazy {
        strokeIcon(
            "CarDisplay",
            "M3.5 5.5h17v11h-17z",
            "M9 20.5h6",
            "M12 16.5v4",
        )
    }

    /** 音频（喇叭 + 声波）。 */
    val Audio: ImageVector by lazy {
        strokeIcon(
            "CarAudio",
            "M4.5 9.5h3.5l4.5-3.5v12l-4.5-3.5H4.5z",
            "M16 9.2a4 4 0 0 1 0 5.6",
            "M18.6 6.6a7.6 7.6 0 0 1 0 10.8",
        )
    }

    /** 方控（方向盘）。 */
    val Steering: ImageVector by lazy {
        strokeIcon(
            "CarSteering",
            "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17z",
            "M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7z",
            "M12 15.5v5",
            "M8.4 13.4 4.2 17",
            "M15.6 13.4 19.8 17",
        )
    }

    /**
     * 媒体卡片（双音符）。
     *
     * 用于"媒体卡片 / 仪表盘封面"设置分区——它管的是**车机上显示什么**，
     * 与 [Audio]（音频通道、麦克风）不是一件事，所以单独一个图标。
     */
    val Media: ImageVector by lazy {
        strokeIcon(
            "CarMedia",
            "M9.4 17.2a2.2 2.2 0 1 1-4.4 0 2.2 2.2 0 0 1 4.4 0z",
            "M20 15.2a2.2 2.2 0 1 1-4.4 0 2.2 2.2 0 0 1 4.4 0z",
            "M9.4 17.2V6.4l10.6-2.4v11.2",
        )
    }

    /** 设置（滑杆）。 */
    val Settings: ImageVector by lazy {
        strokeIcon(
            "CarSettings",
            "M3 7h18",
            "M3 12h18",
            "M3 17h18",
            "M8 5v4",
            "M15 10v4",
            "M11 15v4",
        )
    }

    /** 连接（插头）。 */
    val Link: ImageVector by lazy {
        strokeIcon(
            "CarLink",
            "M9 3.5v5",
            "M15 3.5v5",
            "M6 8.5h12v1.4a6 6 0 0 1-12 0z",
            "M12 17v3.5",
        )
    }

    /** 信息。 */
    val Info: ImageVector by lazy {
        strokeIcon(
            "CarInfo",
            "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17z",
            "M12 11v6",
            "M12 7.6h0.01",
        )
    }

    /** 诊断日志（文档）。 */
    val Log: ImageVector by lazy {
        strokeIcon(
            "CarLog",
            "M6 3.5h8.5L19 8v12.5H6z",
            "M14.2 3.6V8h4.6",
            "M9 12.5h6",
            "M9 16h6",
        )
    }

    /** 警告。 */
    val Warning: ImageVector by lazy {
        strokeIcon(
            "CarWarning",
            "M12 4.5 20.6 19H3.4z",
            "M12 10v4.4",
            "M12 16.7h0.01",
        )
    }

    /** 错误（圆形叉）。 */
    val Error: ImageVector by lazy {
        strokeIcon(
            "CarError",
            "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17z",
            "M9 9l6 6",
            "M15 9l-6 6",
        )
    }

    /** 成功（圆形勾）。 */
    val Success: ImageVector by lazy {
        strokeIcon(
            "CarSuccess",
            "M12 3.5a8.5 8.5 0 1 0 0 17 8.5 8.5 0 0 0 0-17z",
            "M8 12.4l2.8 2.8L16 9.8",
        )
    }

    /** 重连 / 刷新。 */
    val Refresh: ImageVector by lazy {
        strokeIcon(
            "CarRefresh",
            "M20 12a8 8 0 1 1-2.4-5.7",
            "M20 4.6v4.6h-4.6",
        )
    }

    /** 启停（电源）。 */
    val Power: ImageVector by lazy {
        strokeIcon(
            "CarPower",
            "M12 3.5v8",
            "M7.2 6.6a7 7 0 1 0 9.6 0",
        )
    }

    /** 右箭头（进入子页）。 */
    val ChevronRight: ImageVector by lazy {
        strokeIcon("CarChevronRight", "M9.5 6l6 6-6 6")
    }

    /** 上箭头（展开态：点击收起）。 */
    val ChevronUp: ImageVector by lazy {
        strokeIcon("CarChevronUp", "M6 14.5l6-6 6 6")
    }

    /** 返回。 */
    val ArrowBack: ImageVector by lazy {
        strokeIcon("CarArrowBack", "M14.5 6l-6 6 6 6", "M8.5 12h9")
    }

    /** 关闭。 */
    val Close: ImageVector by lazy {
        strokeIcon("CarClose", "M6 6l12 12", "M18 6 6 18")
    }

    /** 勾选。 */
    val Check: ImageVector by lazy {
        strokeIcon("CarCheck", "M5 12.6l4.6 4.6L19 7.4")
    }

    /** 减（步进器）。 */
    val Minus: ImageVector by lazy {
        strokeIcon("CarMinus", "M6 12h12")
    }

    /** 加（步进器）。 */
    val Plus: ImageVector by lazy {
        strokeIcon("CarPlus", "M12 6v12", "M6 12h12")
    }

    /** 外观 / 图标设置（图片轮廓）。 */
    val Image: ImageVector by lazy {
        strokeIcon(
            "CarImage",
            "M4 5h16a1.5 1.5 0 0 1 1.5 1.5v11A1.5 1.5 0 0 1 20 19H4a1.5 1.5 0 0 1-1.5-1.5v-11A1.5 1.5 0 0 1 4 5z",
            "M6.2 15.6l4.3-4.3 2.8 2.8 2.6-2.6 3.9 3.9",
            "M9.4 9.6h0.01",
        )
    }

    /** 定位。 */
    val Location: ImageVector by lazy {
        strokeIcon(
            "CarLocation",
            "M12 21s6-5.4 6-10a6 6 0 1 0-12 0c0 4.6 6 10 6 10z",
            "M12 11a2 2 0 1 0 0-4 2 2 0 0 0 0 4z",
        )
    }

    /** 开机自启 / 系统。 */
    val System: ImageVector by lazy {
        strokeIcon(
            "CarSystem",
            "M12 3.5 20 7.6v8.8L12 20.5 4 16.4V7.6z",
            "M12 12h0.01",
        )
    }

    /**
     * Apple 标志（**实心填充**，连接页主视觉用）。
     *
     * ⚠️ 与上面那套 24x24 **描边**图标不是一类东西，两个关键差异：
     *
     * 1. **视口按原始比例**（`814x1000`，Apple 标志的经典比例）。硬塞进 24x24
     *    方形视口会被压扁——旧版就是手绘贝塞尔去凑方形视口，结果叶子与主体
     *    粘成一块黑疙瘩，在车机上看起来像一个污点。
     * 2. **填充而非描边**。描边版的 Apple 标志在叶子与咬口这些细处会糊掉。
     *
     * 颜色同样写死白色，实际由 `Icon(tint = ...)` 覆盖，因此它会跟着状态语义色走
     * （连接中是主色蓝、失败是红色），而不是永远一块黑。
     */
    val AppleLogo: ImageVector by lazy {
        ImageVector.Builder(
            name = "CarAppleLogo",
            defaultWidth = 24.dp,
            defaultHeight = 29.dp,
            viewportWidth = 814f,
            viewportHeight = 1000f,
        ).addPath(
            pathData = addPathNodes(APPLE_LOGO_PATH),
            name = "CarAppleLogo-body",
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.NonZero,
        ).build()
    }

    // ------------------------------------------------------------ 底栏（Dock）
    //
    // 底栏这一组**刻意不复用**上面的设置页图标：
    // - 设置页是 1.7 描边的线性图标，语气是"车机系统里的一个功能页"；
    // - 底栏要的是 Apple SF Symbols 那种语气：**主体实心 + 微圆角**，
    //   只有箭头是 2.0 圆头描边。两套混用会让底栏显得"飘"。
    //
    // 几何来源（2026-10-11）：房子的轮廓、播放/下一曲的三角、箭头、四宫格
    // 参照 **lucide-static v1.55.0**（ISC 许可，https://lucide.dev）的路径数据，
    // 但**按 SF Symbols 的习惯改造过**：主体实心、圆角用统一的柔化描边收口
    // （见 [solid]）、媒体控件用实心变体而不是线性变体。是参考，不是照搬。
    //
    // 主页图标是**房子**，不是 [CarIcons.Home] 那个仪表盘指针：两者语义都是
    // "回主页"，但一个是内容页的语义图标、一个是系统底栏的通用符号。
    // 保留两个是有意的，别合并。

    /**
     * 左箭头（温度步进器的"减"侧）。
     *
     * **刻意复用**上面那个 [ChevronRight] 的比例与 1.7 描边（这里只是镜像），
     * 而不是另起一套 2.0 的"苹果版"：一对箭头如果线宽/角度不一致，
     * 并排看就是歪的 —— 一致性比单支的"更好看"重要。
     */
    val ChevronLeft: ImageVector by lazy {
        strokeIcon("CarChevronLeft", "M14.5 6l-6 6 6 6")
    }

    /**
     * 实心房子（底栏主页）。
     *
     * ⚠️ 房身与门**必须在同一段路径里**（一个 `d` 里的两条子路径）+ EvenOdd，
     * 门才会被挖空。拆成两个 `addPath` 是不行的 —— 每个 path 各自独立填色，
     * 后画的门会**盖**在房身上，房子照样是个实心五边形（预览里实测踩过）。
     */
    val HomeSolid: ImageVector by lazy {
        symbolIcon("CarDockHome")
            .solidEvenOdd(
                "house",
                "M3 10a2 2 0 0 1 .709-1.528l7-6a2 2 0 0 1 2.582 0l7 6A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" +
                    " M9 21v-7.6a1.2 1.2 0 0 1 1.2-1.2h3.6a1.2 1.2 0 0 1 1.2 1.2V21z",
            )
            .build()
    }

    /** 实心播放三角（媒体卡"暂停态"下显示）。 */
    val Play: ImageVector by lazy {
        symbolIcon("CarDockPlay")
            .solid(
                "body",
                "M5 5a2 2 0 0 1 3.008-1.728l11.997 6.998a2 2 0 0 1 .003 3.458l-12 7A2 2 0 0 1 5 19z",
            )
            .build()
    }

    /** 实心双竖条（媒体卡"播放态"下显示）。 */
    val Pause: ImageVector by lazy {
        symbolIcon("CarDockPause")
            .solid(
                "body",
                "M5.6 6.4a1 1 0 0 1 1-1h3.1a1 1 0 0 1 1 1v11.2a1 1 0 0 1-1 1H6.6a1 1 0 0 1-1-1z",
                "M13.3 6.4a1 1 0 0 1 1-1h3.1a1 1 0 0 1 1 1v11.2a1 1 0 0 1-1 1h-3.1a1 1 0 0 1-1-1z",
            )
            .build()
    }

    /** 下一曲（实心三角 + 圆头竖条，两者之间留缺口）。 */
    val Next: ImageVector by lazy {
        symbolIcon("CarDockNext")
            .solid(
                "body",
                "M6.029 4.285A2 2 0 0 0 3 6v12a2 2 0 0 0 3.029 1.715l9.997-5.998a2 2 0 0 0 .003-3.432z",
            )
            .line("bar", 2.4f, "M21 4.4v15.2")
            .build()
    }

    /** 四宫格（底栏"文件夹"槽）。 */
    val Folder: ImageVector by lazy {
        symbolIcon("CarDockFolder")
            .solid(
                "body",
                "M6.3 4.2h2.5a2.1 2.1 0 0 1 2.1 2.1v2.5a2.1 2.1 0 0 1-2.1 2.1H6.3a2.1 2.1 0 0 1-2.1-2.1V6.3a2.1 2.1 0 0 1 2.1-2.1z",
                "M15.2 4.2h2.5a2.1 2.1 0 0 1 2.1 2.1v2.5a2.1 2.1 0 0 1-2.1 2.1h-2.5a2.1 2.1 0 0 1-2.1-2.1V6.3a2.1 2.1 0 0 1 2.1-2.1z",
                "M6.3 13.1h2.5a2.1 2.1 0 0 1 2.1 2.1v2.5a2.1 2.1 0 0 1-2.1 2.1H6.3a2.1 2.1 0 0 1-2.1-2.1v-2.5a2.1 2.1 0 0 1 2.1-2.1z",
                "M15.2 13.1h2.5a2.1 2.1 0 0 1 2.1 2.1v2.5a2.1 2.1 0 0 1-2.1 2.1h-2.5a2.1 2.1 0 0 1-2.1-2.1v-2.5a2.1 2.1 0 0 1 2.1-2.1z",
            )
            .build()
    }
}

/**
 * Apple 标志的轮廓路径（视口 `814x1000`，含"主体 + 叶子"两段子路径）。
 *
 * 拆成文件末尾的常量，是因为它有一千多个字符——塞在 [CarIcons.AppleLogo]
 * 的构建代码中间会让那段逻辑完全读不出来。
 */
private const val APPLE_LOGO_PATH: String =
    "M788.1 340.9c-5.8 4.5-108.2 62.2-108.2 190.5 0 148.4 130.3 200.9 134.2 202.2" +
        "-.6 3.2-20.7 71.9-68.7 141.9-42.8 61.6-87.5 123.1-155.5 123.1s-85.5-39.5-164-39.5" +
        "c-76.5 0-103.7 40.8-165.9 40.8s-105.6-57-155.5-127C46.7 790.7 0 663 0 541.8" +
        "c0-194.4 126.4-297.5 250.8-297.5 66.1 0 121.2 43.4 162.7 43.4 39.5 0 101.1-46 176.3-46" +
        " 28.5 0 130.9 2.6 198.3 99.2zm-234-181.5c31.1-36.9 53.1-88.1 53.1-139.3" +
        " 0-7.1-.6-14.3-1.9-20.1-50.6 1.9-110.8 33.7-147.1 75.8-28.5 32.4-55.1 83.6-55.1 135.5" +
        " 0 7.8 1.3 15.6 1.9 18.1 3.2.6 8.4 1.3 13.6 1.3 45.4 0 102.5-30.4 135.5-71.3z"

/**
 * 构造一个 24x24 描边图标。
 *
 * 描边宽度固定 1.7（视口单位），在任何显示尺寸下比例一致；
 * 颜色写死白色，实际颜色由 `Icon(tint = ...)` 覆盖。
 */
private fun strokeIcon(name: String, vararg pathData: String): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    pathData.forEachIndexed { index, data ->
        builder.addPath(
            pathData = addPathNodes(data),
            name = "$name-$index",
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathFillType = PathFillType.NonZero,
        )
    }
    return builder.build()
}

/**
 * 实心件的**柔化描边**宽度（见 [solid]）。
 *
 * 取 1.2 ⇒ 每个直角被收成约 0.6 半径的小圆角，整体外扩 0.6。
 * 这是 SF Symbols 实心件的通用手法：不用给每个角手写圆弧，
 * 描一圈就把"硬邦邦的方块"变成"有微圆角的器物"。
 */
private const val SOFT_CORNER = 1.2f

/**
 * 底栏图标的构建器。
 *
 * 与 [strokeIcon] 的区别：SF Symbols 风格的图标**同一个图标里既有实心件
 * 又有描边件**（"下一曲" = 实心三角 + 圆头竖条），所以要能逐段追加、
 * 逐段选择画法 —— [solid] 画实心件、[line] 画圆头线。
 */
private fun symbolIcon(name: String): ImageVector.Builder =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )

/**
 * 追加一个**实心件**。
 *
 * 除了 `fill`，还会用 [SOFT_CORNER] 的圆头描边再描一圈 ⇒ 直角自动收圆
 * （见该常量的说明）。颜色写死白色，实际由 `Icon(tint = ...)` 覆盖。
 */
private fun ImageVector.Builder.solid(
    name: String,
    vararg pathData: String,
): ImageVector.Builder = addSolid(name, PathFillType.NonZero, pathData)

/**
 * 追加一个**带挖空**的实心件（[PathFillType.EvenOdd]）。
 *
 * 目前只有房子用它 —— 门是第二段子路径，EvenOdd 下那块不填色。
 * 用 NonZero 会把门一起填实，房子就变成一个实心五边形。
 */
private fun ImageVector.Builder.solidEvenOdd(
    name: String,
    vararg pathData: String,
): ImageVector.Builder = addSolid(name, PathFillType.EvenOdd, pathData)

private fun ImageVector.Builder.addSolid(
    name: String,
    fillType: PathFillType,
    pathData: Array<out String>,
): ImageVector.Builder = apply {
    pathData.forEachIndexed { index, data ->
        addPath(
            pathData = addPathNodes(data),
            name = "$name-$index",
            fill = SolidColor(Color.White),
            stroke = SolidColor(Color.White),
            strokeLineWidth = SOFT_CORNER,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathFillType = fillType,
        )
    }
}

/** 追加一条**圆头描边**（箭头、"下一曲"的竖条）。 */
private fun ImageVector.Builder.line(
    name: String,
    width: Float,
    vararg pathData: String,
): ImageVector.Builder = apply {
    pathData.forEachIndexed { index, data ->
        addPath(
            pathData = addPathNodes(data),
            name = "$name-$index",
            stroke = SolidColor(Color.White),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
}
