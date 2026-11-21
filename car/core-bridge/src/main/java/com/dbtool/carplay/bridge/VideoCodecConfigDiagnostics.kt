package com.dbtool.carplay.bridge

/**
 * 把解码配置（hvcC / avcC）翻译成一行可读的诊断。
 *
 * ## 为什么需要它
 *
 * 真机上出现过"HEVC 画面几何、文字、图标布局**全对**，但整屏偏绿"的问题。
 * 像素量化显示色度（U/V）**全为 0**，只剩亮度——即"灰度图 + 均匀绿偏色"：
 *
 * ```
 * R 通道 90%+ 的采样恰好为 0，B 通道 93%+ 恰好为 0，只有 G 在变化
 * 背景 Y=16（纯黑）+ U=V=0 → G = 27.3 + 68.2 = 95.5   ← 实测 (0, 95.2, 0)
 * ```
 *
 * ## 结论：这三行日志把"流有问题"这条线彻底排除了
 *
 * 真机实测（同一台 Holi，4 轮会话，2 轮绿 2 轮正常）：
 *
 * ```
 * hevc config bytes=133 profile=Main(1) level=123 chromaFormat=4:2:0(1) bitDepth=8/8
 *   tail=fdf8f800000b03  csd=[VPS(24),SPS(42),PPS(7)] csdBytes=73
 * ```
 *
 * **绿的那两轮与正常的两轮逐字节相同**：profile / 色度格式 / 位深 / 参数集全对，
 * `csd-0` 确实带上了 VPS+SPS+PPS。所以不是单色流、不是 Main10、不是缺 CSD。
 *
 * 进一步排除（见 `repro.txt` 那次取证）：
 * - 解码器输出格式、`CCodecBufferChannel` 的分配器查询、`SurfaceUtils` 的
 *   Surface 连接序列、`first render dataSpace` —— 绿与不绿**完全一致**；
 * - 整个进程的日志序列归一化后 diff，只剩 GC/JIT 时序与随机数差异。
 *
 * 因此：**问题在解码器之后**，是"解码出的色度没被正确送进/采样进合成路径"。
 * 当前最强的机制假设是**多平面图形缓冲的 fence / 布局竞态**——亮度平面先到、
 * 色度平面未就绪就被采样，于是读到全 0。这与"间歇性"和"只影响 HEVC 硬解"都吻合。
 *
 * 频率实测：`c2.qti.hevc.decoder`（1920x896）**2/4 绿**；
 * `c2.android.hevc.decoder`（1056x492）0/4；`c2.qti.avc.decoder`（1920x896）0。
 *
 * 因为本机 HEVC 与 H.264 的画布上限完全相同（都是 `chosen=1920x896`），
 * HEVC **换不到任何画质收益**，所以 `CarSessionSettings.hevc` 的出厂默认已改为 `false`。
 *
 * ## 为什么放在二开层
 *
 * 唯一能打这行日志的上游位置是 `ScreenStream`，但改它等于新增一处上游偏离。
 * 二开层的 [UpstreamMediaSinkBridge] 本来就旁路观测 `MediaSink`，在这里打**零成本**。
 *
 * ## 读法
 *
 * - `chromaFormat=monochrome(0)` → 流本身是单色（4:0:0）。解码器**合法地**不产出色度，
 *   任何接收端都只能渲染成灰度。**本机实测不是这种**，但别的车机可能踩到。
 * - `bitDepth=10/10` → 高位深（Main10）。**本机实测不是这种。**
 * - `csd=NONE(numOfArrays=0)` → 上游不会设置 `csd-0`，解码器只能等带内参数集。
 *   **本机实测不是这种**（`csdBytes=73`）。
 * - 三项全正常却仍然绿 → 就是本机这个平台级竞态，**换 H.264**。
 */
internal object VideoCodecConfigDiagnostics {

    /** hvcC 里 `numOfArrays` 之前的固定记录长度；短于它就不是合法的 hvcC。 */
    private const val HEVC_FIXED_RECORD_SIZE = 23

    /** `numOfArrays` 在 hvcC 里的偏移；数组表紧随其后。 */
    private const val HEVC_ARRAY_COUNT_OFFSET = 22

    /** 数组表里每一项的头：1 字节 NAL 类型 + 2 字节 NAL 个数。 */
    private const val ARRAY_HEADER_SIZE = 3

    /** avcC 的最小长度：version + profile + compatibility + level。 */
    private const val AVC_FIXED_RECORD_SIZE = 4

    /** 单色流（4:0:0）—— 解码器不会产出任何色度采样。 */
    private const val CHROMA_FORMAT_MONOCHROME = 0

    /** 8 bit 是基线；超过它就意味着 P010/高位深输出。 */
    private const val BIT_DEPTH_BASE = 8

    /** HEVC 参数集 NAL 类型：32=VPS、33=SPS、34=PPS。 */
    private const val HEVC_NAL_VPS = 32
    private const val HEVC_NAL_SPS = 33
    private const val HEVC_NAL_PPS = 34

    fun describe(hevc: Boolean, codecData: ByteArray): String {
        val head = codecData.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return if (hevc) describeHevc(codecData, head) else describeAvc(codecData, head)
    }

    private fun describeHevc(codecData: ByteArray, head: String): String {
        if (codecData.size < HEVC_FIXED_RECORD_SIZE || codecData[0].toInt() != 1) {
            return "hevc config bytes=${codecData.size} head=$head (not an hvcC record)"
        }
        val profileSpace = (codecData[1].toInt() shr 6) and 0x03
        val tier = (codecData[1].toInt() shr 5) and 0x01
        val profileIdc = codecData[1].toInt() and 0x1f
        val levelIdc = codecData[12].toInt() and 0xff
        val chromaFormatIdc = codecData[16].toInt() and 0x03
        val bitDepthLuma = (codecData[17].toInt() and 0x07) + BIT_DEPTH_BASE
        val bitDepthChroma = (codecData[18].toInt() and 0x07) + BIT_DEPTH_BASE
        return buildString {
            append("hevc config bytes=${codecData.size}")
            append(" profile=${hevcProfileName(profileIdc)}($profileIdc)")
            append(" profileSpace=$profileSpace tier=$tier level=$levelIdc")
            append(" chromaFormat=${chromaFormatName(chromaFormatIdc)}($chromaFormatIdc)")
            append(" bitDepth=$bitDepthLuma/$bitDepthChroma")
            append(" tail=${hexOf(codecData, 16, HEVC_FIXED_RECORD_SIZE)}")
            append(describeHevcParameterSets(codecData))
            append(" head=$head")
            if (chromaFormatIdc == CHROMA_FORMAT_MONOCHROME) {
                append(" !! monochrome stream: no chroma is coded, screen renders grayscale")
            }
            if (bitDepthLuma > BIT_DEPTH_BASE || bitDepthChroma > BIT_DEPTH_BASE) {
                append(" !! high bit depth: check whether the GPU composition path keeps chroma")
            }
        }
    }

    /**
     * 复刻 `MediaCodecSupport.hevcCodecSpecificData()` 的解析，报出它到底能提取到什么。
     *
     * 这一步不是冗余：upstream 只有在提取结果**非空**时才 `setByteBuffer("csd-0", ...)`。
     * 若 hvcC 的 `numOfArrays` 是 0（或数组表被截断），`csd-0` 根本不会被设置，
     * 解码器就只能在码流里等带内参数集——"亮度正确、色度全为 0"这种降级输出
     * 正好是这个情形的典型表现，所以必须把"有没有 csd"和"参数集是否正常"分开报。
     */
    private fun describeHevcParameterSets(codecData: ByteArray): String {
        val arrayCount = codecData[HEVC_ARRAY_COUNT_OFFSET].toInt() and 0xff
        var cursor = HEVC_ARRAY_COUNT_OFFSET + 1
        val units = mutableListOf<String>()
        var totalBytes = 0
        var arrays = 0
        while (arrays < arrayCount && cursor + ARRAY_HEADER_SIZE <= codecData.size) {
            val nalType = codecData[cursor].toInt() and 0x3f
            val nalUnitCount = readU16Be(codecData, cursor + 1)
            cursor += ARRAY_HEADER_SIZE
            var index = 0
            while (index < nalUnitCount && cursor + 2 <= codecData.size) {
                val nalUnitLength = readU16Be(codecData, cursor)
                cursor += 2
                if (cursor + nalUnitLength > codecData.size) break
                totalBytes += nalUnitLength
                units += "${hevcNalName(nalType)}($nalUnitLength)"
                cursor += nalUnitLength
                index++
            }
            arrays++
        }
        return if (units.isEmpty()) {
            " csd=NONE(numOfArrays=$arrayCount) -> upstream sets no csd-0"
        } else {
            " csd=[${units.joinToString(",")}] csdBytes=$totalBytes"
        }
    }

    private fun readU16Be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun hexOf(bytes: ByteArray, fromIndex: Int, toIndex: Int): String =
        bytes.copyOfRange(fromIndex, toIndex).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun hevcNalName(nalType: Int): String = when (nalType) {
        HEVC_NAL_VPS -> "VPS"
        HEVC_NAL_SPS -> "SPS"
        HEVC_NAL_PPS -> "PPS"
        else -> "NAL$nalType"
    }

    private fun describeAvc(codecData: ByteArray, head: String): String {
        if (codecData.size < AVC_FIXED_RECORD_SIZE || codecData[0].toInt() != 1) {
            return "avc config bytes=${codecData.size} head=$head (not an avcC record)"
        }
        return buildString {
            append("avc config bytes=${codecData.size}")
            append(" profile=${codecData[1].toInt() and 0xff}")
            append(" compatibility=${codecData[2].toInt() and 0xff}")
            append(" level=${codecData[3].toInt() and 0xff}")
            // avcC 不带色度/位深字段：H.264 High profile 恒为 8 bit 4:2:0。
            append(" bitDepth=$BIT_DEPTH_BASE/$BIT_DEPTH_BASE chromaFormat=4:2:0")
            append(" head=$head")
        }
    }

    private fun hevcProfileName(idc: Int): String = when (idc) {
        1 -> "Main"
        2 -> "Main10"
        3 -> "MainStillPicture"
        4 -> "RExt"
        else -> "Unknown"
    }

    private fun chromaFormatName(idc: Int): String = when (idc) {
        0 -> "monochrome"
        1 -> "4:2:0"
        2 -> "4:2:2"
        3 -> "4:4:4"
        else -> "Unknown"
    }
}
