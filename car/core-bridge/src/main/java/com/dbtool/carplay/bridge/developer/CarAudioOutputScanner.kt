package com.dbtool.carplay.bridge.developer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import com.shilapi.xcertplay.media.ActiveAudioTrackProbe

/** No writes, network requests, permission prompts or hidden HAL calls. */
internal class CarAudioOutputScanner(private val context: Context) {
    fun scan(inputs: List<String>): String {
        val tracks = ActiveAudioTrackProbe.snapshot()
        val am = context.getSystemService(AudioManager::class.java)
        val dumpAllowed = context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
        val dump = if (dumpAllowed) CarShell.run("dumpsys media.audio_flinger", 2500) else ""
        val vendorAvailable = runCatching {
            Class.forName("com.ecarx.car.audio.manager.EcarxAudioManager")
        }.isSuccess
        return buildString {
            appendLine("扫描时间：${java.util.Date()}（单次快照，切歌/切路由后请重新扫描）")
            appendLine("\n一、CarPlay输入／上行")
            appendLine(inputs.joinToString("\n").ifEmpty { "当前没有活动CarPlay音频流" })
            appendLine("\n二、本应用实际AudioTrack")
            appendLine(tracks.joinToString("\n").ifEmpty { "当前没有可读取的本应用音轨" })
            appendLine("encoding=2表示PCM 16位；播放状态3=播放、2=暂停、1=停止。以上不是DAC格式。")
            appendLine("\n三、系统参考参数（不是最终输出）")
            appendLine("主输出建议采样率：${am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "未知"} Hz")
            appendLine("此值是系统低延迟输出参考值，不代表当前音轨、所有输出设备或DAC实际采样率。")
            appendLine("\n四、OSN原车接口")
            appendLine(if (vendorAvailable) "发现EcarxAudioManager类；getAudioStatus字节协议尚未确认，未调用未知HAL接口。"
                else "当前应用类加载器未发现EcarxAudioManager；不等于系统未安装服务。")
            appendLine("\n五、自动分析")
            appendLine(CarAudioOutputAnalysis.conclusion(inputs.size, tracks.size, dumpAllowed, dump))
            if (dump.isNotBlank()) {
                appendLine("\n系统只读转储节选（仅原始证据，不自动认定为当前DAC输出）：")
                appendLine(dump.take(16000))
                if (dump.length > 16000) appendLine("转储已截断。")
            }
            appendLine("\n本次仅本机读取：未更改音量/音源/网络，未启用ADB/root，未自动上传。")
        }
    }
}

internal object CarAudioOutputAnalysis {
    fun conclusion(inputs: Int, tracks: Int, dumpAllowed: Boolean, dump: String): String = buildString {
        appendLine(if (inputs == 0) "未观察到活动CarPlay输入；请播放音乐后重新扫描。"
            else "已观察到${inputs}条CarPlay音频流，不能由输入编码推断硬件输出。")
        appendLine(if (tracks == 0) "未读取到本应用音轨：可能未开始收流、已停止，或当前使用其他播放器。"
            else "已读取${tracks}条本应用AudioTrack；系统仍可能重采样或混音。")
        appendLine(when {
            !dumpAllowed -> "系统混音/HAL输出未知：本应用无DUMP权限，未执行受限转储。"
            dump.isBlank() -> "系统混音/HAL输出未知：转储为空、不可用或超时。"
            dump.contains("Permission Denial", true) || dump.contains("permission denied", true) ->
                "系统混音/HAL输出未知：系统拒绝读取。"
            else -> "已获取系统转储，但尚未可靠关联当前音轨与硬件输出线程；不能将任一采样率直接当作最终输出。"
        })
        append("最终DAC/DSP输出格式：未知。本次不能判定bit-perfect、原生高解析度或24bit输出。")
    }
}
