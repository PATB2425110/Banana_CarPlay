package com.dbtool.carplay.api.media

/**
 * 车机「媒体卡片 / 硬媒体键」下发给第三方播放器的**入向**命令。
 *
 * ## 这是什么、为什么要有
 *
 * 我们向车机**推**正在播放走的是 `FdBookBroadcaster`（伪装成帆书，把歌名/封面
 * 送进 `com.geely.online.service/com.media.control.MediaControlReceiver`）。
 * 反过来的那一半 —— **车机把用户的播放/暂停/上下曲操作发回来** —— 走的是
 * 另一个方向、另一条广播：车机以 `ecarx.intent.broadcast.action.MEDIA_CONTROL_RECEIVER`
 * 为 action 发一条**隐式**广播，接收方从 `RECEIVER_MEDIA_CONTROL` 这个 extra
 * 里读命令字符串。
 *
 * 这两个方向**必须都接上**，卡片才是活的：只推不收 ⇒ 车机上显示着歌名，
 * 按暂停却没反应。
 *
 * ## 取值从哪来（不是猜的）
 *
 * 反编译 `carplaybox_v32` 的
 * `io/dushu/car/receiver/MediaControlReceiver.java` 与它的 `AndroidManifest.xml`：
 *
 * ```java
 * String cmd = intent.getStringExtra("RECEIVER_MEDIA_CONTROL");
 * if ("MEDIA_CONTROL_PLAY".equals(cmd))            tc.play();
 * else if ("MEDIA_CONTROL_PAUSE".equals(cmd))      tc.pause();
 * else if ("MEDIA_CONTROL_SKIP_TO_NEXT".equals(cmd))     tc.skipToNext();
 * else if ("MEDIA_CONTROL_SKIP_TO_PREVIOUS".equals(cmd)) tc.skipToPrevious();
 * ```
 *
 * ⚠️ 这四个字符串是**跨进程契约**，大小写、下划线、`SKIP_TO_` 前缀都不能改。
 * 改错的现场表现是"按了没反应"，日志里一行都不会有 —— 因为广播照发，只是
 * [fromWireValue] 认不出来。
 *
 * ## 为什么做成枚举而不是散落的字符串常量
 *
 * 解析是**纯函数**，可以脱离 Android 跑单测（见 `CarMediaControlCommandTest`）。
 * 这条链路的错误只在真机上暴露，能在单测里钉住一个是一个。
 */
enum class CarMediaControlCommand(val wireValue: String) {

    /** 播放。**显式命令**，不是 toggle。 */
    PLAY("MEDIA_CONTROL_PLAY"),

    /** 暂停。显式命令。 */
    PAUSE("MEDIA_CONTROL_PAUSE"),

    /** 下一曲。 */
    NEXT("MEDIA_CONTROL_SKIP_TO_NEXT"),

    /** 上一曲。 */
    PREVIOUS("MEDIA_CONTROL_SKIP_TO_PREVIOUS"),
    ;

    companion object {

        /** 车机下发媒体命令用的 action（隐式广播）。 */
        const val ACTION = "ecarx.intent.broadcast.action.MEDIA_CONTROL_RECEIVER"

        /** 命令字符串所在的 extra key。 */
        const val EXTRA_COMMAND = "RECEIVER_MEDIA_CONTROL"

        /**
         * 把 extra 里的字符串翻成命令。
         *
         * 认不出来（含 null / 空串 / 车机将来新增的取值）时返回 **null**，
         * 调用方应当**静默忽略**：车机侧同一个 action 上还挂着别的命令
         * （音量、音源切换之类），我们不认识它们不等于出错。
         */
        fun fromWireValue(raw: String?): CarMediaControlCommand? {
            if (raw.isNullOrEmpty()) return null
            return values().firstOrNull { it.wireValue == raw }
        }
    }
}
