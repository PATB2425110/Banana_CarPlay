package com.dbtool.carplay.geely

/**
 * 虚拟时钟调度器：按时间顺序执行到期任务，不 sleep、不依赖 Android Looper。
 */
internal class FakeClusterScheduler : GeelyClusterScheduler {
    private data class Task(val at: Long, val order: Long, val block: () -> Unit)

    var currentTime = 0L
        private set

    private var order = 0L
    private val tasks = mutableListOf<Task>()

    override fun postDelayed(delayMillis: Long, task: () -> Unit): () -> Unit {
        val scheduled = Task(currentTime + delayMillis, order++, task)
        tasks += scheduled
        return { tasks.remove(scheduled) }
    }

    /** 把虚拟时间向前推进 [millis]，顺序执行期间到期的任务。 */
    fun advanceBy(millis: Long) {
        val target = currentTime + millis
        while (true) {
            val next = tasks
                .filter { it.at <= target }
                .minWithOrNull(compareBy<Task>({ it.at }, { it.order }))
                ?: break
            tasks.remove(next)
            currentTime = next.at
            next.block()
        }
        currentTime = target
    }
}

/**
 * 假车机模式接口：脚本化 enter / exit 返回值与 getNaviMode 读数。
 *
 * `modes` 按调用顺序消费；队列空了以后返回 [defaultMode]。
 */
internal class FakeNaviModeController(
    var available: Boolean = true,
    var defaultMode: Int? = 1,
) : GeelyNaviModeController {
    val enterResults = ArrayDeque<Boolean>()
    val exitResults = ArrayDeque<Boolean>()
    val modes = ArrayDeque<Int?>()

    var enterCalls = 0
        private set
    var exitCalls = 0
        private set
    var readCalls = 0
        private set

    /** 在 `enterFullNavi()` 真正被调用时执行（用来断言账本已落盘）。 */
    var onEnter: () -> Unit = {}

    override fun isAvailable(): Boolean = available

    override fun enterFullNavi(): Boolean {
        enterCalls += 1
        onEnter()
        return if (enterResults.isEmpty()) true else enterResults.removeFirst()
    }

    override fun exitFullNavi(): Boolean {
        exitCalls += 1
        return if (exitResults.isEmpty()) true else exitResults.removeFirst()
    }

    override fun readNaviMode(): Int? {
        readCalls += 1
        return if (modes.isEmpty()) defaultMode else modes.removeFirst()
    }
}

/** 内存账本存储：记录写入顺序，供顺序断言使用。 */
internal class MemoryJournalStore : GeelyClusterJournalStore {
    val values = mutableMapOf<String, Any>()
    val writes = mutableListOf<String>()

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        values[key] as? Boolean ?: defaultValue

    override fun getInt(key: String, defaultValue: Int): Int =
        values[key] as? Int ?: defaultValue

    override fun getLong(key: String, defaultValue: Long): Long =
        values[key] as? Long ?: defaultValue

    override fun writeAcquireRecord(
        displayId: Int,
        originalMode: Int,
        sinceElapsedMs: Long,
    ): Boolean {
        putInt(GeelyClusterRestoreJournal.KEY_DISPLAY_ID, displayId)
        putInt(GeelyClusterRestoreJournal.KEY_ORIGINAL_MODE, originalMode)
        putLong(GeelyClusterRestoreJournal.KEY_SINCE_ELAPSED, sinceElapsedMs)
        putBoolean(GeelyClusterRestoreJournal.KEY_ACQUIRED_VERIFIED, false)
        putBoolean(GeelyClusterRestoreJournal.KEY_RELEASED_VERIFIED, false)
        // active 最后写（与真机实现同一提交点语义）。
        putBoolean(GeelyClusterRestoreJournal.KEY_ACTIVE, true)
        return true
    }

    override fun putBoolean(key: String, value: Boolean): Boolean {
        values[key] = value
        writes += key
        return true
    }

    override fun putInt(key: String, value: Int): Boolean {
        values[key] = value
        writes += key
        return true
    }

    override fun putLong(key: String, value: Long): Boolean {
        values[key] = value
        writes += key
        return true
    }

    override fun clearRecord(): Boolean {
        listOf(
            GeelyClusterRestoreJournal.KEY_ACTIVE,
            GeelyClusterRestoreJournal.KEY_DISPLAY_ID,
            GeelyClusterRestoreJournal.KEY_ORIGINAL_MODE,
            GeelyClusterRestoreJournal.KEY_SINCE_ELAPSED,
            GeelyClusterRestoreJournal.KEY_ACQUIRED_VERIFIED,
            GeelyClusterRestoreJournal.KEY_RELEASED_VERIFIED,
        ).forEach {
            values.remove(it)
            writes += "remove:$it"
        }
        return true
    }
}
