package com.dbtool.carplay.bridge.media

import com.dbtool.carplay.api.media.CarMediaControlCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [CarMediaControlCommand] 的单测。
 *
 * ## 为什么这条链路值得单独钉
 *
 * 它是**车机 → 我们**唯一的媒体命令入口，而四个命令值全是跨进程字符串契约
 * （来自反编译 carplaybox_v32 的 `MediaControlReceiver`）。写错的表现是
 * **按了没反应，日志里一行都不报**：广播照发，只是 [CarMediaControlCommand.fromWireValue]
 * 认不出来而已。这种故障在真机上极难归因 —— 没有异常、没有堆栈、没有回调，
 * 只能靠"是不是字符串拼错了"去猜。
 *
 * 所以这里把三件事钉死：
 *
 * 1. **四个字面量本身**：不许"顺手美化"成 `PLAY` / `SkipToNext` 之类；
 * 2. **大小写与下划线敏感**：车机发的是全大写 + 下划线，模糊匹配等于放过错值；
 * 3. **action 与 extra key**：清单里是硬写的字符串，与代码常量只能靠人肉同步，
 *    测试至少能保证代码这一侧不再漂移。
 */
class CarMediaControlCommandTest {

    // ---------------------------------------------------------------- 字面量

    @Test
    fun `四个命令值必须与车机契约逐字一致`() {
        // 这些字符串是从反编译的 MediaControlReceiver 里抄出来的，不是自己起的名字。
        assertEquals("MEDIA_CONTROL_PLAY", CarMediaControlCommand.PLAY.wireValue)
        assertEquals("MEDIA_CONTROL_PAUSE", CarMediaControlCommand.PAUSE.wireValue)
        assertEquals("MEDIA_CONTROL_SKIP_TO_NEXT", CarMediaControlCommand.NEXT.wireValue)
        assertEquals("MEDIA_CONTROL_SKIP_TO_PREVIOUS", CarMediaControlCommand.PREVIOUS.wireValue)
    }

    @Test
    fun `action 与 extra key 必须与清单里硬写的字符串一致`() {
        assertEquals(
            "ecarx.intent.broadcast.action.MEDIA_CONTROL_RECEIVER",
            CarMediaControlCommand.ACTION,
        )
        assertEquals("RECEIVER_MEDIA_CONTROL", CarMediaControlCommand.EXTRA_COMMAND)
    }

    // ------------------------------------------------------------------ 解析

    @Test
    fun `四个合法取值都能解析出对应命令`() {
        assertEquals(CarMediaControlCommand.PLAY, CarMediaControlCommand.fromWireValue("MEDIA_CONTROL_PLAY"))
        assertEquals(CarMediaControlCommand.PAUSE, CarMediaControlCommand.fromWireValue("MEDIA_CONTROL_PAUSE"))
        assertEquals(CarMediaControlCommand.NEXT, CarMediaControlCommand.fromWireValue("MEDIA_CONTROL_SKIP_TO_NEXT"))
        assertEquals(
            CarMediaControlCommand.PREVIOUS,
            CarMediaControlCommand.fromWireValue("MEDIA_CONTROL_SKIP_TO_PREVIOUS"),
        )
    }

    @Test
    fun `每个枚举项都能被自己的 wire 值解析回来`() {
        // 防止将来新增枚举项却忘了加进解析分支（或改了 wireValue 却漏改解析）。
        CarMediaControlCommand.values().forEach { command ->
            assertEquals(command, CarMediaControlCommand.fromWireValue(command.wireValue))
        }
    }

    @Test
    fun `null 与空串返回 null`() {
        assertNull(CarMediaControlCommand.fromWireValue(null))
        assertNull(CarMediaControlCommand.fromWireValue(""))
    }

    @Test
    fun `大小写不匹配视为未知`() {
        // 契约是全大写。做大小写不敏感匹配会把车机侧拼错的命令也放过去，
        // 而那种错误本该在"按了没反应"时暴露出来。
        assertNull(CarMediaControlCommand.fromWireValue("media_control_play"))
        assertNull(CarMediaControlCommand.fromWireValue("Media_Control_Play"))
    }

    @Test
    fun `未知命令返回 null 而不是抛异常`() {
        // 同一个 action 上还挂着车机自己的其它命令（音量、音源切换等），
        // 我们不认识它们是正常的，不能因此崩掉接收者。
        assertNull(CarMediaControlCommand.fromWireValue("MEDIA_CONTROL_VOLUME_UP"))
        assertNull(CarMediaControlCommand.fromWireValue("MEDIA_CONTROL_PLAY_"))
        assertNull(CarMediaControlCommand.fromWireValue(" MEDIA_CONTROL_PLAY"))
    }
}
