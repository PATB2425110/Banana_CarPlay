package io.dushu.car.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dbtool.carplay.api.media.CarMediaControlCommand
import com.dbtool.carplay.app.CarPlayApplication

/**
 * 车机媒体卡片 / 硬媒体键的**入向**广播接收者。
 *
 * ## 它补的是哪一半
 *
 * 我们推给车机的那一半早就有（[com.dbtool.carplay.ecarx.media.FdBookBroadcaster]，
 * 伪装成帆书往 `com.geely.online.service/com.media.control.MediaControlReceiver`
 * 发歌名与封面）。但**车机把用户操作发回来**的那一半一直是空的：
 * 卡片上显示着歌名，按暂停没有任何反应。
 *
 * 反编译 `carplaybox_v32` 里能看到完整契约：车机以
 * `ecarx.intent.broadcast.action.MEDIA_CONTROL_RECEIVER` 发**隐式**广播，
 * 接收方从 `RECEIVER_MEDIA_CONTROL` 读命令字符串（四个取值见
 * [CarMediaControlCommand]），再落到 `MediaSession` 的 transport controls 上。
 *
 * ## 为什么类名必须叫 `io.dushu.car.receiver.MediaControlReceiver`
 *
 * 这是**刻意占用帆书的包名与类名**，与本产品其它几处伪装同一套路
 * （`applicationId=io.dushu.car`、OneOS 方控的 `clientId=io.dushu.car`）：
 * 车机若按组件名**显式**发这条广播，只有名字一字不差才收得到；
 * 若是隐式广播，同名也无副作用。反编译里的类正是这个名字，照抄即可。
 *
 * ⚠️ 因此本类的**包路径是契约的一部分**，不要"顺手"挪进
 * `com.dbtool.carplay.*` —— 挪了在部分车型上会静默失效。
 *
 * ## 为什么在这里只做"翻译 + 转发"
 *
 * 真正的出口在 [CarAppController.onCarMediaControl]：它握着会话与
 * `CarNowPlayingSource`。接收者由系统实例化，既没有依赖注入也没有生命周期，
 * 所以只能通过 [CarPlayApplication.controllerOrNull] 这条静态口子拿控制器。
 * 拿不到（进程刚起、`onCreate` 未完成、或用户还没进 CarPlay）就**丢弃**这条命令 ——
 * 此时没有可控制的播放器，重试也没有意义。
 *
 * ## 与方控的关系：两条独立通道，不要合并
 *
 * - **方向盘 / 方控**：OneOS binder（`IInputListener`）→ `EcarxKeyCodes` →
 *   `CarKeyHidMap` → CarPlay HID 报文。走的是 HID，不经过本类。
 * - **中控媒体卡片按钮**：本类。走的是广播。
 *
 * 两者来源不同、物理通路不同，所以各自接各自的，不存在重复消费。
 */
class MediaControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 系统也可能把别的 action 派进来（同一个接收者被复用），先挡一道。
        if (intent.action != CarMediaControlCommand.ACTION) return

        val raw = intent.getStringExtra(CarMediaControlCommand.EXTRA_COMMAND)
        val command = CarMediaControlCommand.fromWireValue(raw)
        if (command == null) {
            // 同一个 action 上还挂着车机自己的其它命令（音量、音源等），
            // 认不出来是**正常**的，不是错误 —— 只记一行，不抛不崩。
            Log.i(TAG, "忽略未识别的媒体命令：$raw")
            return
        }

        val controller = CarPlayApplication.controllerOrNull()
        if (controller == null) {
            Log.i(TAG, "收到 $command，但应用控制器尚未就绪，丢弃")
            return
        }

        Log.i(TAG, "收到车机媒体命令 $command")
        controller.onCarMediaControl(command)
    }

    private companion object {
        const val TAG = "DBPlay-MediaKey"
    }
}
