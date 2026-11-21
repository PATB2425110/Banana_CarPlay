package com.dbtool.carplay.geely

import com.dbtool.carplay.api.display.ClusterDialLayout
import com.dbtool.carplay.api.display.ClusterDialLayoutHost
import com.dbtool.carplay.api.display.ClusterDrawRegion
import com.dbtool.carplay.api.display.ClusterDrawRegionHost
import com.dbtool.carplay.api.display.ClusterVideoTransform
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.dbtool.carplay.api.display.CarClusterSpec
import com.dbtool.carplay.api.display.ClusterLayerComposition
import com.dbtool.carplay.api.display.ClusterLayerMode
import com.dbtool.carplay.api.projection.ClusterLayerRuntime
import kotlin.math.roundToInt

/**
 * 仪表副屏窗口。
 *
 * ## 移植自哪
 *
 * 反编译 `GeelyCarPlay-0.3.2` 的 `io.dushu.car.geely.GeelyClusterPresentation`
 * （baksmali 逐行实证），骨架与关键窗口参数**原样保留**：
 *
 * - `Window.setFormat(PixelFormat.TRANSLUCENT = -3)`、背景全透明、
 *   `clearFlags(FLAG_DIM_BEHIND = 2)` —— 原版这三行都写了，缺一不可：
 *   仪表屏下方 360px 是车机自己的仪表，必须能透出来。
 * - 外层 FrameLayout 高 = **可见高**（原版 `videoHeight` = 720），
 *   内层 TextureView 高 = **面板高**（1080）；`gravity = TOP|START`。
 *   视觉结果是"视频播在上方 720，下方 360 透明"。
 * - `TextureView.setOpaque(false)`，视频未就绪时保持透明，不盖住仪表底图。
 *   Android 9 上解码器已向 Surface 提交首帧时，`onSurfaceTextureUpdated` 可能
 *   晚于解码器首帧回调；因此不把视频/窗口的可见性锁在该回调之后。
 *
 * ## 与原版的差异（刻意收敛）
 *
 * 原版还挂了 `GeelySelfDrawOverlayView`（车机自绘导航叠层，含标线/路口图）
 * 与安全区校正、夜间模式、全屏切换等一整套。
 *
 * **2026-10-09（PATCH-0063）**：自绘导航（含叠层、桥、设置分区）已**整体移除**，
 * 本窗口现在只负责把 CarPlay 仪表视频流显示到副屏。原版那套"工厂全屏地图模式 /
 * 手动重开"本就不复刻。
 */
class GeelyClusterPresentation(
    context: Context,
    display: Display,
    private val spec: CarClusterSpec,
    /**
     * 画面是否旋转 180°（副屏倒装；见 `CarClusterFlipControl`）。
     *
     * 只影响窗口内的视图变换，**不改**本窗对外声明的任何尺寸 ⇒ 可随时切。
     */
    private var flip180: Boolean = false,
    private val onSurface: (Surface?) -> Unit,
) : Presentation(context, display) {

    private var video: TextureView? = null

    /** 主题层宿主：铺满 root，位于视频层之下；主题为空时整层隐藏。 */
    private var skinHost: FrameLayout? = null
    private var independentCards: ClusterIndependentCardsView? = null
    private var safeAreaPreview: InstrumentSafeAreaPreviewView? = null
    private var skinView: View? = null

    /** 当前图层模式；建窗前先取运行时值，presenter 建窗后会补应用 pending 值。 */
    private var layerMode: ClusterLayerMode = ClusterLayerRuntime.mode()

    /**
     * 导航视频层此刻是否显示（2026-10-22 解耦，P0）。
     *
     * 与 [layerMode] 正交：关闭导航只把 `clip` 设为 `INVISIBLE`（保留 Surface），
     * 主题层 `skinHost` 与整窗可见性都不受影响。
     */
    private var navigationVisible: Boolean = ClusterLayerRuntime.navigationVisible()

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 实际点亮的可见高度（0 / 越界 ⇒ 铺满面板）。
     *
     * 图层内缩以这条带子为基准，与现有裁剪语义一致：视频仍在
     * `[0, visibleHeight)` 这条带子里显示，下方留给车机自己的仪表。
     */
    private val visibleHeight: Int = spec.visibleHeightPixels
        .takeIf { it in 1 until spec.heightPixels }
        ?: spec.heightPixels

    /**
     * 真正出画的那块可见容器（`clip`，高 = [CarClusterSpec.visibleHeightPixels]）。
     *
     * ## ⚠️ 为什么旋转的是它，而不是整窗根视图（2026-10-11 真车纠正）
     *
     * 这块面板**报 1920×1080、实际只显示 1920×720**：1080 只是 display mode，
     * 真正点亮的只有一条 720 高的带子，内容就摆在这条带子里
     * （`clip` 高 = `visibleHeightPixels` = 720，`gravity = TOP|START`）。
     *
     * 所以翻转必须在**这条带子内部原地做**：
     * 若去转整块 1080 高的根视图，带子会被绕面板中心转到 `360..1080` 行 ——
     * 一半落在不显示的区里，画面会被裁掉一半。转 `clip`（绕自身中心）
     * 则带宽与位置都不变，只有内容上下左右翻过来。
     *
     * 占位文字同一块区域，一并跟着转（见 [placeholder]），免得面板倒装时
     * 中文提示是倒的。
     */
    private var contentBlock: FrameLayout? = null
    private var surface: Surface? = null
    private var released = false

    /**
     * 首帧上屏前的占位文字（PATCH-0030）。
     *
     * 视频层保持透明内容；占位文案只提示尚未收到画面，不参与视频可见性的控制。
     */
    private var placeholder: TextView? = null

    /** 首帧是否已显形（只做一次）。 */
    private var firstFrameShown = false

    /**
     * `onSurfaceTextureUpdated` 累计触发次数（PATCH-0054，诊断用）。
     *
     * 这个回调是「TextureView 到底有没有收到画面」的唯一直接证据。
     * 计数从 0 开始；**日志里出现 `帧更新 #1` 就说明回调确实触发了**，
     * 一条都不出现则说明回调从未被调用（正是本轮要判定的问题）。
     */
    private var updatedCallbackCount = 0

    @Volatile
    private var lastFrameAtMs: Long = 0L

    private var vitalsTickCount = 0

    /** 首帧到达回调（供上层记状态；可空）。 */
    var onFirstFrame: (() -> Unit)? = null

    /**
     * 画面通道（SurfaceTexture）被系统回收时的回调（PATCH-0049）。
     *
     * 与 [onSurface] 的区别很关键：`onSurface(null)` 只说明「这一路 Surface 没了」，
     * 上层可以原地等下一路；本回调说明「**这个窗口里的通道已经废了**」，
     * 上层必须把整个窗口丢掉重建。
     *
     * 真车实证（VIN L6TTEST1111111113，22:55:27 `BufferQueue has been abandoned`）：
     * 通道被回收后窗口壳子仍在，上层 `start()` 的短路判据（只看窗口对象在不在）
     * 依旧成立，于是重连时把这条**死通道**又交了一次 ⇒ 仪表一直黑。
     */
    var onSurfaceInvalidated: (() -> Unit)? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(
            TAG,
            "仪表窗口 onCreate：displayId=${display?.displayId} " +
                "面板=${spec.widthPixels}x${spec.heightPixels} 可见高=${spec.visibleHeightPixels}",
        )
        window?.let { w: android.view.Window ->
            // 原版：TRANSLUCENT(-3) + 全透明背景 + 清掉 FLAG_DIM_BEHIND(2)。
            @Suppress("DEPRECATION")
            w.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            @Suppress("DEPRECATION")
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // PATCH-0053：窗口从创建起保持可见。Android 9 实车日志里
            // MediaCodec 已向 ALT(111) Surface 渲染首帧，但不能证明 TextureView
            // 的 updated 回调及时触发；若在该回调前把整窗 alpha 锁为 0，
            // 仪表会一直露出底层空白。root/TextureView 本身透明，空窗仍透底。
            w.attributes = w.attributes.apply { alpha = 1f }
            // 诊断（PATCH-0054）：把窗口实际生效的属性回读出来。
            // 「设了 alpha=1」不等于「真的生效了」——有的 ROM 会在 addView 前
            // 重置 attributes，光看源码看不出，必须回读 + 打日志。
            Log.i(
                TAG,
                "仪表窗口属性：format=${w.attributes.format} alpha=${w.attributes.alpha} " +
                    "flags=0x${Integer.toHexString(w.attributes.flags)}",
            )
        } ?: Log.w(TAG, "仪表窗口 onCreate：window 为 null（Presentation 尚未 addView？）")

        val root = FrameLayout(context).apply { setBackgroundColor(Color.TRANSPARENT) }
        // 主题层必须先加、铺满 root：它位于视频层之下，主题为空时整层隐藏。
        val skin = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.INVISIBLE
        }

        val clip = FrameLayout(context).apply {
            clipChildren = true
            clipToPadding = true
            setBackgroundColor(Color.TRANSPARENT)
        }

        val texture = TextureView(context).apply {
            isOpaque = false
            // 对齐原版（jadx 实证 GeelyClusterPresentation$onCreate$3）：初始透明，
            // 首帧到达后由 setVideoVisible(true) 置 1f。避免视频未就绪时露出空 Surface 黑块。
            alpha = 0f
        }
        // 2026-10-22 修正：texture 必须跟着 clip 走，不能写死 1920x720。
        // 车主把 CarPlay 缩到中央红框（653x381）后，固定尺寸会让 texture 比容器大 3 倍，
        // 画面被裁成左上角一小块 —— 现场表现就是「中央只剩两条蓝竖条」。
        clip.addView(
            texture,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            clip,
            FrameLayout.LayoutParams(spec.widthPixels, visibleHeight).apply {
                // 原版 gravity = 0x800033 = TOP | START。
                gravity = Gravity.TOP or Gravity.START
            },
        )

        // PATCH-0030：首帧前的占位，放在 clip 之上、自绘叠层之下。
        val label = TextView(context).apply {
            text = "正在接收仪表画面…"
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
        }
        root.addView(
            label,
            FrameLayout.LayoutParams(spec.widthPixels, visibleHeight).apply {
                gravity = Gravity.TOP or Gravity.START
            },
        )
        placeholder = label

        // 2026-10-22 车主口径：CarPlay 投屏在**下层**，主题双环在**上层**，
        // 主题中央透明 ⇒ 能看见下面的 CarPlay 地图。
        // 所以 skin 必须在 clip / label 之后才 addView（后加的在上面）。
        root.addView(
            skin,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                visibleHeight,
            ).apply { gravity = Gravity.TOP or Gravity.START },
        )
        skinHost = skin
        val cards = ClusterIndependentCardsView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        root.addView(cards, FrameLayout.LayoutParams(spec.widthPixels, visibleHeight).apply { gravity = Gravity.TOP or Gravity.START })
        independentCards = cards
        val preview = InstrumentSafeAreaPreviewView(context)
        root.addView(preview, FrameLayout.LayoutParams(spec.widthPixels, visibleHeight).apply { gravity = Gravity.TOP or Gravity.START })
        safeAreaPreview = preview

        setContentView(root)
        video = texture
        contentBlock = clip
        applyFlip180()
        // 2026-10-22：纹理此刻还没有尺寸；这里先试一次，真正的落点在
        // SurfaceTexture 回调与全局布局回调里（都会再调）。
        applyVideoTransformMatrix()
        // 首次布局：主题可能还没准备好，此处只做一次安全的初始应用。
        refreshLayers()
        mainHandler.postDelayed(vitalsTick, VITALS_REFRESH_MILLIS)

        // 挂 decorView 的可见性监听（诊断用；PATCH-0061 的纹理重建已移除）。
        // 车机把仪表交还/收回时走可见性事件，不一定重走 onStart。
        window?.decorView?.setOnSystemUiVisibilityChangeListener(null)
        window?.decorView?.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    Log.i(TAG, "仪表 decorView attach")
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            },
        )
        // ⚠️ 2026-10-10 修（真车"投屏完全不工作"的直接根因）：
        //
        // 这里原来写的是
        //   `ViewTreeObserver.addOnWindowVisibilityChangeListener` +
        //   `ViewTreeObserver.OnWindowVisibilityChangeListener`
        // —— 那是 **API 37 才有的接口**，而本车机是 **Android 9（SDK 28）**。
        // Android 9 的 `ViewTreeObserver` 里**根本没有这个内部类**：类校验收不了它，
        // 于是**整个 `onCreate` 直接抛 `NoClassDefFoundError`**，仪表窗口一次都建不起来。
        //
        // 真车日志（v161 / CS11）：
        //   `Rejecting re-init on previously-failed class
        //    com.dbtool.carplay.geely.GeelyClusterPresentation$onCreate$5:
        //    java.lang.NoClassDefFoundError: Failed resolution of:
        //    Landroid/view/ViewTreeObserver$OnWindowVisibilityChangeListener;`
        //   `Cluster: 创建仪表窗口失败 NoClassDefFoundError: …`
        // 现象就是"点了投屏、iPhone 那条 111 流也建起来了，但仪表一直不出画面"。
        //
        // 教训：本工程 minSdk = 28，但 compileSdk = 37 —— **只看编译器不够**，
        // 任何"新框架里的监听器"都要先确认它在 28 上存在。这里改成两个 28 上一定有的
        // 监听，凑出与原来同一个语义（"窗口重新可见 ⇒ 补建被回收的纹理"）：
        //
        //   1. `OnGlobalLayoutListener`（API 1）：每次全局布局回读
        //      `decorView.windowVisibility`，**状态变化时**才回调 —— 与原语义等价；
        //   2. `OnWindowFocusChangeListener`（API 18）：车机把仪表交还/收回时窗口焦点会变，
        //      重新拿到焦点就当作"我们重新上屏了"。
        //
        // 两个回调现在只记诊断日志，不再重建纹理（PATCH-0061 已移除）。
        // 所以多一路信号不会带来抖动。
        val decor = window?.decorView
        if (decor != null) {
            val observer = decor.viewTreeObserver
            observer.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    private var lastVisibility = decor.windowVisibility

                    override fun onGlobalLayout() {
                        // 2026-10-22：布局完成后纹理才有真实宽高，补下发一次
                        // 视频缩放矩阵（窗口重建 / 屏幕尺寸变化都会走到这里）。
                        applyVideoTransformMatrix()
                        val now = decor.windowVisibility
                        if (now != lastVisibility) {
                            lastVisibility = now
                            onDecorVisibilityChanged(now)
                        }
                    }
                },
            )
            observer.addOnWindowFocusChangeListener(
                object : ViewTreeObserver.OnWindowFocusChangeListener {
                    override fun onWindowFocusChanged(hasFocus: Boolean) {
                        if (hasFocus) onDecorVisibilityChanged(View.VISIBLE)
                    }
                },
            )
        }

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                val next = android.view.Surface(st)
                Log.i(
                    TAG,
                    "仪表 SurfaceTexture available ${w}x${h} surface=$next " +
                        "textureAlpha=${texture.alpha} windowAlpha=${windowAlphaOrNull()}",
                )
                publish(next)
                // 2026-10-22：纹理就绪后补下发视频缩放矩阵（尺寸随后由布局确定）。
                texture.post { applyVideoTransformMatrix() }
            }

            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                Log.i(TAG, "仪表 SurfaceTexture sizeChanged ${w}x${h}")
                texture.post { applyVideoTransformMatrix() }
            }

            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                Log.i(
                    TAG,
                    "仪表 SurfaceTexture destroyed surface=$surface " +
                        "textureAlpha=${texture.alpha} windowAlpha=${windowAlphaOrNull()}",
                )
                // 对齐原版（jadx 实证 GeelyClusterPresentation$onCreate$3：
                // `onSurfaceTextureDestroyed` 里先 `onSurfaceGone.invoke(it)` 再
                // `it.release()`）：把包装 Surface 的 native 引用还掉。
                //
                // 只 publish(null) 不够 —— 那只把引用置空，`new Surface(texture)` 建的
                // 那个 Surface 包装仍持有底层 BufferQueue 引用。返回 true 只让框架
                // 释放 `SurfaceTexture`，不释放我们自己包的那层。窗口每次销毁漏一个，
                // 长时间多次重连会累积（PATCH-0069）。
                val doomed = surface
                publish(null)
                runCatching { doomed?.release() }
                    .onFailure { Log.w(TAG, "释放旧 Surface 失败：${it.javaClass.simpleName}") }
                // PATCH-0049：通道废了必须通知上层丢窗口。只 publish(null) 不够——
                // 上层短路判据看的是「窗口对象还在不在」，于是重连会把死通道再交一次。
                onSurfaceInvalidated?.invoke()
                // 返回 true：由框架释放 SurfaceTexture，与原版一致。
                return true
            }

            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {
                // PATCH-0030：首帧信号。
                //
                // TextureView 的帧回调只用于隐藏占位文字和更新诊断状态；视频层
                // 与窗口已从创建时保持可见，避免车机迟发此回调时把已解码画面藏住。
                //
                // 诊断（PATCH-0054）：这个回调**每次有帧上屏都会响**（不止首帧），
                // 是「TextureView 到底有没有收到画面」的唯一直接证据。
                // 之前只在首帧打一条日志，日志缺失时无法区分
                // 「回调从没触发」和「触发了但没被记录」——现在每次都打（含计数）。
                updatedCallbackCount += 1
                lastFrameAtMs = SystemClock.uptimeMillis()
                // 2026-10-24 降频：实测 17.5 条/秒 × 120 字符，持续写 logcat。
                // 改为「首帧必打 + 之后每 FRAME_LOG_INTERVAL 帧一条」，
                // 与 HUD 侧（GeelyHudPresentation.FRAME_LOG_INTERVAL = 300）一致。
                if (!firstFrameShown || updatedCallbackCount % FRAME_LOG_INTERVAL == 0) {
                    Log.i(
                        TAG,
                        "仪表 TextureView 帧更新 #$updatedCallbackCount firstFrameShown=$firstFrameShown " +
                            "textureAlpha=${texture.alpha} windowAlpha=${windowAlphaOrNull()} surface=$surface",
                    )
                }
                if (!firstFrameShown) {
                    firstFrameShown = true
                    setVideoVisible(true)
                    onFirstFrame?.invoke()
                }
            }
        }
    }

    /**
     * 视频层可见性与占位状态。
     *
     * 2026-10-22 解耦（P0）：本方法**只改视频层**，不再调用
     * `setWindowVisible(visible)`。窗口是否显示由 [updateWindowVisibility]
     * 按“主题层 ∨ 导航层”计算 —— 导航首帧不再强制点亮/熄灭整窗。
     */
    fun setVideoVisible(visible: Boolean) {
        val before = video?.alpha
        video?.alpha = if (visible) 1f else 0f
        // 导航层本身被关闭时，占位文字也必须一起藏起来（它挂在 root 上，
        // 不在 clip 里，不会随 clip 的 INVISIBLE 一起消失）。
        placeholder?.visibility =
            if (visible || !navigationVisible) View.INVISIBLE else View.VISIBLE
        updateWindowVisibility()
        // 诊断（PATCH-0054）：可见性开关是白屏问题的核心变量，
        // 记下"谁在什么时刻把它切成了什么"，尤其是首个 `true` 的来源。
        Log.i(
            TAG,
            "仪表可见性切换 setVideoVisible($visible)：textureAlpha $before -> ${video?.alpha} " +
                "windowAlpha=${windowAlphaOrNull()} firstFrameShown=$firstFrameShown",
        )
    }

    /**
     * 应用仪表图层模式（三模式分层）。
     *
     * **不拆窗、不断流**：只改 `skinHost` / `clip` 的可见性与 `clip` 的几何。
     * THEME_ONLY 下 `clip` 用 `INVISIBLE`（保留 Surface），
     * 切回 CARPLAY_ONLY 时视频下一帧即可出现。
     *
     * 必须在主线程执行；非主线程调用会 post 到主线程。
     */
    /** 待应用的圆表布局（2026-10-21）；建窗后由 [refreshLayers] / [onCreate] 补应用。 */
    private var pendingDialLayout: ClusterDialLayout = ClusterDialLayout.DEFAULT

    /** 待应用的自绘区域（2026-10-22）；与表盘布局同款：建窗后补应用。 */
    private var pendingDrawRegion: ClusterDrawRegion = ClusterDrawRegion.DEFAULT

    /** 视频层本机变换（2026-10-22）；建窗前先记住，纹理有尺寸后落到矩阵。 */
    private var videoTransform: ClusterVideoTransform = ClusterVideoTransform.DEFAULT

    /**
     * 应用左右圆表布局：只更新已存在的皮肤 View，不重建、不重连。
     * 皮肤尚未创建时记住该值，建窗后立即补应用一次。
     */
    fun applyDialLayout(layout: ClusterDialLayout) {
        val safe = ClusterDialLayout.sanitize(layout)
        pendingDialLayout = safe
        val apply = {
            (skinView as? ClusterDialLayoutHost)?.setDialLayout(safe)
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply()
        else mainHandler.post(apply)
    }

    /**
     * 应用自绘区域：只更新已有皮肤 View 的绘制参数，不重建、不重连。
     *
     * 皮肤尚未创建时记住该值，[rebuildSkin] 建好后立即补应用。
     */
    fun applyDrawRegion(region: ClusterDrawRegion) {
        val safe = ClusterDrawRegion.sanitize(region)
        pendingDrawRegion = safe
        val apply = {
            // 主题层：只改 Canvas 绘制区域（圆表/自绘内容缩进这块矩形）
            (skinView as? ClusterDrawRegionHost)?.applyDrawRegion(safe)
            // 2026-10-23 修正（现场「编辑方框改了不生效」）：
            // 原实现只把区域给了 skinView，**完全没碰 clip（TextureView 容器）**，
            // 所以地图视频一直按 clusterLayerViewPlan 算出的全屏矩形显示，
            // 编辑器怎么拖都不影响实际投屏。
            // 这里把同一块区域同步到 clip 的 LayoutParams，才真正生效。
            applyDrawRegionToVideo(safe)
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply()
        else mainHandler.post(apply)
    }

    /**
     * 把自绘区域同步到视频层（`clip`）的布局。
     *
     * ## 坐标换算
     *
     * `ClusterDrawRegion` 是 1920×720 **设计画布**坐标；`clip` 的父容器是
     * `spec.widthPixels × visibleHeight` 的**像素**尺寸。两者按宽高各自等比换算。
     *
     * ## 为什么改 clip 而不是 texture
     *
     * `texture` 已经是 `MATCH_PARENT`，跟着 `clip` 走；改 `clip` 就等于改真正
     * 上屏的矩形。`TextureView` 的 `SurfaceTexture` 尺寸不变 ⇒ 不动解码、
     * 不打断 ALT(111) 流。
     */
    private fun applyDrawRegionToVideo(region: ClusterDrawRegion) {
        val clipView = contentBlock ?: return
        // 图层计划给出视频层的基础矩形（当前恒为全屏），再与自绘区域取交集。
        val plan = clusterLayerViewPlan(
            mode = layerMode,
            panelWidth = spec.widthPixels,
            visibleHeight = visibleHeight,
            themeAvailable = skinView != null,
            navigationVisible = navigationVisible,
        )
        applyVideoBounds(
            clipView = clipView,
            planLeft = plan.clipLeft,
            planTop = plan.clipTop,
            planWidth = plan.clipWidth,
            planHeight = plan.clipHeight,
            region = region,
        )
    }

    /**
     * 把「图层计划矩形 ∩ 自绘区域」落到 `clip` 的 LayoutParams。
     *
     * 两个来源必须交集而不是互相覆盖：
     * - 图层计划在每个 200ms tick 都会下发自己的 bounds；
     * - 自绘区域是用户拖动出来的，必须同样在 tick 之后仍然生效。
     *
     * 自绘区域用 1920×720 设计坐标，按面板实际像素宽高等比换算。
     */
    private fun applyVideoBounds(
        clipView: FrameLayout,
        planLeft: Int,
        planTop: Int,
        planWidth: Int,
        planHeight: Int,
        region: ClusterDrawRegion,
    ) {
        val hostW = spec.widthPixels.toFloat().takeIf { it > 0f } ?: return
        val hostH = visibleHeight.toFloat().takeIf { it > 0f } ?: return

        val sx = hostW / ClusterDrawRegion.BASE_WIDTH
        val sy = hostH / ClusterDrawRegion.BASE_HEIGHT
        val rLeft = (region.x * sx).roundToInt()
        val rTop = (region.y * sy).roundToInt()
        val rRight = (region.right * sx).roundToInt()
        val rBottom = (region.bottom * sy).roundToInt()

        val left = maxOf(planLeft, rLeft)
        val top = maxOf(planTop, rTop)
        val right = minOf(planLeft + planWidth, rRight).coerceAtLeast(left + 1)
        val bottom = minOf(planTop + planHeight, rBottom).coerceAtLeast(top + 1)
        val w = right - left
        val h = bottom - top

        val lp = clipView.layoutParams as? FrameLayout.LayoutParams
        if (lp != null && lp.width == w && lp.height == h &&
            lp.leftMargin == left && lp.topMargin == top
        ) {
            return
        }
        clipView.layoutParams = FrameLayout.LayoutParams(w, h).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = left
            topMargin = top
        }
        clipView.requestLayout()
    }

    /**
     * 应用视频层本机缩放/平移：只改 `TextureView` 的纹理矩阵。
     *
     * 不碰 SurfaceTexture、不改缓冲区尺寸、不发任何 AirPlay 命令 ⇒ 不会打断
     * ALT(111) 通道。纹理尚未布局（宽高为 0）时先记住，布局回调里补应用。
     */
    fun applyVideoTransform(transform: ClusterVideoTransform) {
        val safe = ClusterVideoTransform.sanitize(transform)
        videoTransform = safe
        val apply = { applyVideoTransformMatrix(); Unit }
        if (Looper.myLooper() == Looper.getMainLooper()) apply()
        else mainHandler.post(apply)
    }

    /**
     * 把 [videoTransform] 写成 `TextureView.setTransform(Matrix)` 的矩阵。
     *
     * 语义见 `ClusterVideoTransform`：先绕**视图中心**等比缩放，再平移。
     * 矩阵作用于视图坐标（与 `HudCropSpec` 的推导同一前提）。
     * 宽高为 0 时直接返回 —— 下一次布局回调会再调一次。
     */
    private fun applyVideoTransformMatrix() {
        val texture = video ?: return
        val viewWidth = texture.width
        val viewHeight = texture.height
        if (viewWidth <= 0 || viewHeight <= 0) return

        val transform = videoTransform
        if (transform.isDefault) {
            // 恒等矩阵：恢复 TextureView 默认「整帧铺满视图」。
            texture.setTransform(Matrix())
            return
        }
        val matrix = Matrix()
        matrix.setScale(transform.scale, transform.scale, viewWidth / 2f, viewHeight / 2f)
        if (transform.dx != 0f || transform.dy != 0f) {
            matrix.postTranslate(transform.dx, transform.dy)
        }
        texture.setTransform(matrix)
    }

    fun applyLayerMode(mode: ClusterLayerMode) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            layerMode = mode
            refreshLayers(logLayer = true)
            return
        }
        mainHandler.post {
            if (released) return@post
            layerMode = mode
            refreshLayers(logLayer = true)
        }
    }

    /**
     * 只切换导航视频层的显隐（2026-10-22 解耦，P0）。
     *
     * **不拆窗、不断流、不解绑 Surface、不改主题层**：`visible=false` 只把
     * `clip` 设为 `INVISIBLE`（与 `THEME_ONLY` 同款，保留 BufferQueue），
     * 重新打开时下一帧即可出现。窗口 alpha 由“主题 ∨ 导航”重新计算。
     */
    fun applyNavigationVisible(visible: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            navigationVisible = visible
            refreshLayers(logLayer = true)
            return
        }
        mainHandler.post {
            if (released) return@post
            navigationVisible = visible
            refreshLayers(logLayer = true)
        }
    }

    /**
     * 把当前 [layerMode] 落到视图上，并顺带刷新主题与车辆读数。
     *
     * 主题/皮肤工厂晚于窗口就绪时，下一次 tick 会自动补建主题层；
     * 拿不到主题只影响 `themeVisible`，不改模式值。
     */
    private fun refreshLayers(logLayer: Boolean = false) {
        val host = skinHost ?: return
        val clip = contentBlock ?: return

        val plan = clusterLayerViewPlan(
            mode = layerMode,
            panelWidth = spec.widthPixels,
            visibleHeight = visibleHeight,
            themeAvailable = skinView != null,
            navigationVisible = navigationVisible,
        )
        applyClusterLayerPlan(
            plan,
            object : ClusterLayerTarget {
                override fun setThemeVisible(visible: Boolean) {
                    host.visibility = if (visible) View.VISIBLE else View.GONE
                }

                override fun setVideoVisible(visible: Boolean) {
                    // INVISIBLE：保留 Surface / BufferQueue，不拆视频通道。
                    clip.visibility = clipVisibilityFor(visible)
                }

                override fun setVideoBounds(width: Int, height: Int, left: Int, top: Int) {
                    // 图层计划每次 tick 都要下发，但用户的自绘区域也必须保留：
                    // 两者取交集（见 applyVideoBounds）。
                    applyVideoBounds(
                        clipView = clip,
                        planLeft = left,
                        planTop = top,
                        planWidth = width,
                        planHeight = height,
                        region = pendingDrawRegion,
                    )
                }

                override fun setCenterReserved(reserved: Boolean) {
                }
            },
        )

        independentCards?.apply {
            val config = com.dbtool.carplay.api.projection.InstrumentCards.load(context)
            navigationEnabled = config.navigationEnabled
            musicEnabled = config.musicEnabled
            navigationX = config.navigationX
            navigationY = config.navigationY
            musicX = config.musicX
            musicY = config.musicY
            navigation = com.dbtool.carplay.api.projection.InstrumentCards.navigation()
            nowPlaying = com.dbtool.carplay.api.projection.InstrumentCards.music()
            visibility = if (navigationEnabled || musicEnabled) View.VISIBLE else View.GONE
        }
        safeAreaPreview?.apply {
            visibility = if (ClusterLayerRuntime.safeAreaPreview() == null) View.INVISIBLE else View.VISIBLE
            invalidate()
        }
        placeholder?.visibility = when {
            !plan.clipVisible -> View.INVISIBLE
            firstFrameShown -> View.INVISIBLE
            else -> View.VISIBLE
        }
        // 2026-10-22 解耦（P0）：窗口可见性 = 主题层 ∨ 导航层。
        // 视频首帧、导航开关都只改各自图层，不再单边改写整窗 alpha。
        updateWindowVisibility()
        // applyVideoBounds() requests layout only when bounds changed. Do not force
        // a full-window measure/layout on every 200ms refresh tick.

        if (logLayer) {
            val inset = ClusterLayerComposition.layoutFor(layerMode, spec.widthPixels, visibleHeight).videoInset
            Log.i(
                TAG,
                "仪表图层：mode=${layerMode.name} theme=${plan.themeVisible} " +
                    "video=${plan.clipVisible} " +
                    "inset=(${inset.left},${inset.top},${inset.right},${inset.bottom})",
            )
        }
    }


    /** 每 200ms 刷新车辆读数与主题（主题目录可能晚于窗口就绪）。 */
    private val vitalsTick = object : Runnable {
        override fun run() {
            if (released) return
            if (isShowing) refreshLayers()
            vitalsTickCount += 1
            if (vitalsTickCount % 50 == 0) {
                runCatching {
                    val lastFrameAgeMs = lastFrameAtMs
                        .takeIf { it != 0L }
                        ?.let { SystemClock.uptimeMillis() - it }
                        ?: -1L
                    Log.i(
                        TAG,
                        "T0|frame.stats|firstFrame=$firstFrameShown " +
                            "updated=$updatedCallbackCount lastFrameAgeMs=$lastFrameAgeMs",
                    )
                }
            }
            mainHandler.postDelayed(this, VITALS_REFRESH_MILLIS)
        }
    }

    /**
     * 切换画面 180° 翻转。**就地生效**（窗口已在时立刻改视图变换）。
     *
     * 只动 `rotation`：不碰 `SurfaceTexture`、不重新 `addView`、不解绑 Surface，
     * 因此不会打断 ALT(111) 那条通道，也不需要重连 CarPlay。
     */
    fun setFlip180(enabled: Boolean) {
        if (flip180 == enabled) return
        flip180 = enabled
        applyFlip180()
    }

    /**
     * 把 [flip180] 落到根视图上。
     *
     * `rotation` 的旋转中心默认是**视图中心**，根视图铺满整块面板 ⇒ 等价于
     * 绕面板几何中心转 180°（见 [contentRoot] 的 KDoc）。
     *
     * 根视图在 `onCreate` 之前为 null（`setContentView` 还没跑）⇒ 这里允许空转，
     * `onCreate` 里会再调一次补上。
     */
    private fun applyFlip180() {
        val block = contentBlock ?: return
        val degrees = if (flip180) 180f else 0f
        // 只转可见块（绕自身中心 ⇒ 原地翻转）；占位文字同区域，一起转。
        block.rotation = degrees
        placeholder?.rotation = degrees
        Log.i(
            TAG,
            "仪表画面翻转 flip180=$flip180 rotation=$degrees " +
                "可见块=${block.width}x${block.height} 面板=${spec.widthPixels}x${spec.heightPixels}",
        )
    }

    /**
     * 按当前两个图层的实际可见性重算整窗 alpha（2026-10-22 解耦，P0）。
     *
     * 只有**主题层与导航层都不可见**时才隐藏窗口；任一可见就必须显示。
     * 这样“关导航”不会连带隐藏主题，“关主题”也不会连带隐藏导航。
     */
    private fun updateWindowVisibility() {
        val host = skinHost ?: return
        val clip = contentBlock ?: return
        setWindowVisible(
            host.visibility == View.VISIBLE || clip.visibility == View.VISIBLE,
        )
    }

    /**
     * 窗口级整窗透明度（PATCH-0030）。
     *
     * 视图层 `alpha` 只透纹理，窗口自己的底照样挡着；副屏 Presentation
     * 默认底不透明 ⇒ 必须同时调 `window.attributes.alpha`。原版 `setMapVisible` 同解。
     */
    fun setWindowVisible(visible: Boolean) {
        window?.let { w ->
            val alpha = if (visible) 1f else 0f
            if (w.attributes.alpha != alpha) {
                w.attributes = w.attributes.apply { this.alpha = alpha }
            }
        }
    }

    /** 当前窗口 alpha（诊断用；窗口尚未存在时返回 null）。 */
    private fun windowAlphaOrNull(): Float? = window?.attributes?.alpha

    /**
     * 窗口暂停：**只记日志，不解绑 Surface**（PATCH-0060）。
     *
     * 反编译原版 `GeelyClusterPresentation` **没有覆写 `onStop`**（`GeelyClusterPresentation.smali`
     * 的方法表里只有 `onCreate` + 6 个 setter，无 `onStop`/`onStart`）。也就是说：
     * 车机把仪表收回、切回原厂界面时，ALT(111) 上那条 Surface **一直挂着**，
     * 画面回来时不需要任何人再喂一次。
     *
     * 我们原来在这里 `publish(null)`，而**没有任何回补路径** —— `publish` 只在
     * `onSurfaceTextureAvailable` 与 `release()` 里被调用，窗口重新可见时
     * `TextureView` 不会重建 `SurfaceTexture` ⇒ `onSurfaceTextureAvailable` 不再触发
     * ⇒ **窗口只要停过一次，ALT(111) 就永久空着**（白屏，与 PATCH-0052 同一族病根：
     * "通道还在但已被我们自己解绑"）。
     *
     * 会话结束的解绑由 [release] 负责（先 `publish(null)` 再 `dismiss()`），
     * 纹理真被系统回收由 `onSurfaceTextureDestroyed` 负责，这里不需要兜。
     */
    override fun onStop() {
        super.onStop()
        Log.i(
            TAG,
            "仪表窗口 onStop：Surface 保持绑定（原版无 onStop 解绑）；" +
                "updated回调累计=$updatedCallbackCount firstFrameShown=$firstFrameShown",
        )
    }

    /**
     * 窗口重新显示。
     *
     * 窗口停止过再回来时，纹理可能已被系统回收；此处主动补建，
     * 不依赖 `onSurfaceTextureAvailable` 自己再触发（实测它不会）。
     */
    override fun onStart() {
        super.onStart()
        Log.i(
            TAG,
            "仪表窗口 onStart：textureAvailable=${video?.isAvailable} surfaceValid=${surface?.isValid}",
        )
        // 这里不再调 reattachIfTextureLost()（PATCH-0061 已移除）：
        // onStart 早于窗口真正 attach，此时替换 SurfaceTexture 会让系统
        // 丢弃紧随其后的 onSurfaceTextureAvailable 回调，导致 surface 恒为 null。
        // 原版（jadx 实证）正是不做任何重建，只依赖 available 回调一次交付 Surface。
    }

    /**
     * 窗口可见性变化（PATCH-0054 诊断用）。
     *
     * 注意：`onWindowVisibilityChanged` 是 `View` 的方法，`Presentation` 本身
     * 没有；这里挂在窗口的 decorView 上监听（`onCreate` 里注册）。
     *
     * PATCH-0061 已移除：原先在此调 `reattachIfTextureLost()` 重建纹理，
     * 但重建发生在窗口 attach 之前/之中，会吞掉 `onSurfaceTextureAvailable`，
     * 使 surface 恒为 null（真车日志 20:16:27 实证）。现在仅保留日志。
     */
    private fun onDecorVisibilityChanged(visibility: Int) {
        if (visibility == View.VISIBLE) {
            Log.i(
                TAG,
                "仪表窗口重新可见：textureAvailable=${video?.isAvailable} " +
                    "surfaceValid=${surface?.isValid}",
            )
        }
    }

    /**
     * 幂等地上报 Surface；`released` 之后一律上报 null。
     *
     * PATCH-0069（对齐原版 `GeelyClusterSurfaceOwner.onSurfaceAvailable/Gone`）：
     * 原版换 Surface 是**一对原子动作**——
     * ```
     * old != null  → sink.clearSurface(111, old)   // 先声明旧通道作废
     * this.surface = value
     *                sink.setSurface(111, value)    // 再交付新通道
     * ```
     * 当前版只有 `onSurface(next)` 一个出口，没有「先清旧」这一步。后果：上层
     * （解码器侧）在收到新 Surface 前，仍认为旧 Surface 有效 —— 真车日志里
     * 「旧 Surface 被再交一次 ⇒ 白屏」那一族病根（见 PATCH-0052 注释）正是这样来的。
     *
     * 这里用同一出口补齐：换 Surface 且旧的那条**非空**时，先显式交一次 `null`
     * 代表「旧通道作废」，再交新的。语义与原版 `clearSurface + setSurface` 等价，
     * 且不需要引入新的回调参数（保持上层契约不变）。
     */
    private fun publish(s: Surface?) {
        val next = if (released) null else s
        if (surface === next) return
        val previous = surface
        surface = next
        // 先作废旧通道（仅当旧的非空且确实在换）。
        if (previous != null) {
            Log.i(
                TAG,
                "仪表 Surface 作废 ALT(111) 旧通道 previous=$previous " +
                    "isValid=${previous.isValid}",
            )
            onSurface(null)
        }
        Log.i(
            TAG,
            "仪表 Surface 转交 ALT(111) surface=$next " +
                "isValid=${next?.isValid} released=$released",
        )
        runCatching {
            Log.i(
                TAG,
                "T0|surface.publish|next=${next ?: "null"} next_valid=${next?.isValid} " +
                    "released=$released prev=${previous ?: "null"}",
            )
        }
        onSurface(next)
    }

    /** 释放：先解绑 Surface 再 dismiss，避免上层拿到已销毁的 Surface。 */
    fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacks(vitalsTick)
        Log.i(TAG, "仪表窗口释放 release() surface=$surface updated回调累计=$updatedCallbackCount")
        publish(null)
        runCatching { dismiss() }
    }

    /**
     * `onSurfaceTextureUpdated` 累计触发次数（PATCH-0054，诊断快照用）。
     *
     * 供诊断包/上层把「TextureView 到底有没有收到画面」这个事实一次性取出来：
     * `0` = 回调从未触发，`>0` = 触发过（次数）。
     */
    fun updatedCallbackCountOrZero(): Int = updatedCallbackCount

    /**
     * 一次性诊断快照（PATCH-0054）。
     *
     * 把与"画面是否真的到屏"有关的几个量打成一个字符串，写进日志一条就够，
     * 免得排查时来回翻若干条日志还拼不出全貌。
     *
     * 除了窗口/视频层状态，还带上 `isShowing`、`isAttachedToWindow`、
     * 显示器尺寸与刷新率 —— 这些是"窗口虽在但不显示"类问题的关键旁证。
     */
    fun diagnosticSnapshot(): String {
        val d = display
        val metrics = resources.displayMetrics
        return "displayId=${d?.displayId} 屏=${d?.mode?.physicalWidth}x${d?.mode?.physicalHeight}" +
            "@${d?.refreshRate?.toInt()}Hz 视图=${video?.width}x${video?.height} " +
            "surface=$surface surfaceValid=${surface?.isValid} " +
            "textureAlpha=${video?.alpha} windowAlpha=${windowAlphaOrNull()} " +
            "textureVisible=${video?.isShown} textureAttached=${video?.isAttachedToWindow} " +
            "firstFrameShown=$firstFrameShown updated回调累计=$updatedCallbackCount " +
            "isShowing=$isShowing released=$released dpi=${metrics?.densityDpi} " +
            "flip180=$flip180 blockRotation=${contentBlock?.rotation}"
    }

    /** 暴露给上层的根 View（诊断/尺寸核对用）。 */
    fun rootViewOrNull(): View? = video

    /** 当前已发布的 Surface（诊断用：核对窗口交给 ALT(111) 的那条通道）。 */
    fun surfaceOrNull(): Surface? = surface


    companion object {
        private const val TAG = "XCExt.carplay-cluster"

        /** 车辆读数刷新周期（毫秒）。 */
        private const val VITALS_REFRESH_MILLIS = 200L

    /** 帧更新日志降频间隔（与 HUD 侧保持一致）。 */
    private const val FRAME_LOG_INTERVAL = 300
    }
}

/**
 * 一次图层应用要落到视图上的纯数据。
 *
 * 单独抽出来是为了在 JVM 单测里验证三模式的可见性与内缩计算，
 * 不必实例化 `Presentation` / `View`。
 */
internal data class ClusterLayerViewPlan(
    val mode: ClusterLayerMode,
    val themeVisible: Boolean,
    val clipVisible: Boolean,
    val clipWidth: Int,
    val clipHeight: Int,
    val clipLeft: Int,
    val clipTop: Int,
    val centerReserved: Boolean,
) {
    /**
     * 仪表窗口是否需要显示 = 主题层 ∨ 导航层。
     *
     * 2026-10-22 解耦（P0）：窗口是两个模块的共用容器，任一层可见就保留；
     * 只有两层都不可见时才允许隐藏/拆除。
     */
    val windowVisible: Boolean
        get() = themeVisible || clipVisible
}

/**
 * 计算某个模式下的视图计划。
 *
 * 几何基准是**面板宽 × 可见高**（与现有 `clip` 的裁剪带一致），
 * 内缩来自 [ClusterLayerComposition.layoutFor]。主题为空/皮肤未注入时
 * `themeVisible=false`，但 [ClusterLayerViewPlan.mode] 原样保留。
 */
internal fun clusterLayerViewPlan(
    mode: ClusterLayerMode,
    panelWidth: Int,
    visibleHeight: Int,
    themeAvailable: Boolean,
    /**
     * 导航视频层运行时开关（2026-10-22 解耦，P0）。
     *
     * 默认 `true` ⇒ 只关心三态模式的既有调用方/单测行为与解耦前完全一致。
     * `false` 只关闭视频层：主题层、窗口可见性与 Surface 生命周期都不受影响。
     */
    navigationVisible: Boolean = true,
): ClusterLayerViewPlan {
    val layout = ClusterLayerComposition.layoutFor(
        themeVisible = mode.showsTheme,
        navigationVisible = mode.showsNavigation && navigationVisible,
        panelWidth = panelWidth,
        panelHeight = visibleHeight,
    )
    val inset = layout.videoInset
    return ClusterLayerViewPlan(
        mode = mode,
        themeVisible = layout.themeVisible && themeAvailable,
        clipVisible = layout.videoVisible,
        clipWidth = (panelWidth - inset.left - inset.right).coerceAtLeast(1),
        clipHeight = (visibleHeight - inset.top - inset.bottom).coerceAtLeast(1),
        clipLeft = inset.left,
        clipTop = inset.top,
        centerReserved = layout.themeCenterReserved && themeAvailable,
    )
}

/**
 * 视频层不可见时用 `INVISIBLE`，**禁止用 `GONE`**。
 *
 * `INVISIBLE` 仍参与布局、保留 Surface / BufferQueue；`GONE` 会让
 * TextureView 脱离布局并可能触发通道销毁，切回视频时需要等重建。
 */
internal fun clipVisibilityFor(videoVisible: Boolean): Int =
    if (videoVisible) View.VISIBLE else View.INVISIBLE

/**
 * 图层计划的下发出口。
 *
 * 接口刻意**不包含** `detach` / `removeView` / Surface 解绑：应用模式只能
 * 改可见性与几何，从类型上就断掉"切模式顺手拆流"的可能。
 */
internal interface ClusterLayerTarget {
    fun setThemeVisible(visible: Boolean)

    fun setVideoVisible(visible: Boolean)

    fun setVideoBounds(width: Int, height: Int, left: Int, top: Int)

    fun setCenterReserved(reserved: Boolean)
}

/** 把纯数据计划下发到视图；不触碰窗口生命周期与 Surface。 */
internal fun applyClusterLayerPlan(plan: ClusterLayerViewPlan, target: ClusterLayerTarget) {
    target.setThemeVisible(plan.themeVisible)
    target.setVideoVisible(plan.clipVisible)
    target.setVideoBounds(plan.clipWidth, plan.clipHeight, plan.clipLeft, plan.clipTop)
    target.setCenterReserved(plan.centerReserved)
}
