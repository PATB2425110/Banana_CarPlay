package com.dbtool.carplay.geely

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock

/**
 * 还原账本：记录「我们曾把仪表切到全屏地图模式」这件事，进程被杀后据此补还原。
 *
 * ## 为什么必须先写账本、再调 `switchNaviMode(3)`
 *
 * 账本是「进程被杀后还能还屏」的唯一依据。如果先切模式再记账，中间被杀就
 * 永远不知道要还 —— 原厂仪表会停在全屏地图页。因此 [markAcquireIntent]
 * 使用 `SharedPreferences.commit()` **同步落盘**，调用方必须在它返回后
 * 才能切模式。
 *
 * ## 什么时候清
 *
 * 只有**回读确认已经回到 1**（或车机明确回 `onChangeNaviMode:1`）时，
 * 才允许 [markReleased] + [clear]。调用失败、回读失败、回读超时一律保留，
 * 交给下次启动的 [GeelyClusterModeSession.restoreIfLeftOn]。
 *
 * 本类不依赖 `Handler` / `Looper`，存储通过 [GeelyClusterJournalStore] 注入，
 * 因此可以在纯 JVM 单测里用内存假实现验证。
 */
internal class GeelyClusterRestoreJournal(
    private val store: GeelyClusterJournalStore,
) {
    /** 上次未完成的抢屏记录。 */
    data class Pending(
        val displayId: Int,
        val originalMode: Int?,
        /** 记账时刻（`elapsedRealtime` 时间戳，不是时长）。 */
        val sinceElapsedMs: Long,
    )

    constructor(context: Context) : this(
        SharedPreferencesJournalStore(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        ),
    )

    /**
     * 在调 `switchNaviMode(3)` **之前**同步写账本。
     *
     * @param originalMode 切模式前读到的原厂模式；读不到传 null（落盘为 -1）。
     */
    fun markAcquireIntent(
        displayId: Int,
        originalMode: Int?,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ) {
        store.writeAcquireRecord(displayId, originalMode ?: NO_MODE, nowElapsedMs)
    }

    /** 记录进入模式是否经过回读确认（账本仍保持 active）。 */
    fun markAcquired(verified: Boolean) {
        if (!isActive()) return
        store.putBoolean(KEY_ACQUIRED_VERIFIED, verified)
    }

    /**
     * 记录一次释放结果。
     *
     * @param verified 只有回读确认 =1 才传 true；false 时账本保持 active。
     */
    fun markReleased(verified: Boolean) {
        store.putBoolean(KEY_RELEASED_VERIFIED, verified)
        if (verified) {
            store.putBoolean(KEY_ACTIVE, false)
        }
    }

    /** 上次未完成记录；没有（或已确认还原）时返回 null。 */
    fun pending(): Pending? {
        if (!isActive()) return null
        val rawOriginal = store.getInt(KEY_ORIGINAL_MODE, NO_MODE)
        return Pending(
            displayId = store.getInt(KEY_DISPLAY_ID, -1),
            originalMode = if (rawOriginal == NO_MODE) null else rawOriginal,
            sinceElapsedMs = store.getLong(KEY_SINCE_ELAPSED, 0L),
        )
    }

    /** 账本是否仍有未完成记录。 */
    fun isActive(): Boolean = store.getBoolean(KEY_ACTIVE, false)

    /** 清账本。**只允许在回读确认已还原后调用。** */
    fun clear() {
        store.clearRecord()
    }

    internal companion object {
        /** 账本文件名（独立于设置，避免被「恢复默认」清掉）。 */
        const val PREFS_NAME = "carplay_cluster_fullmap"

        // 键名与 fulldeskmap_评估.md §C.2 保持一致。
        const val KEY_ACTIVE = "cluster_fullmap_active"
        const val KEY_DISPLAY_ID = "cluster_fullmap_display_id"
        const val KEY_ORIGINAL_MODE = "cluster_fullmap_original_mode"
        const val KEY_SINCE_ELAPSED = "cluster_fullmap_since_elapsed"

        /** 补充键：进入 / 退出是否经过回读确认（只用于诊断）。 */
        const val KEY_ACQUIRED_VERIFIED = "cluster_fullmap_acquired_verified"
        const val KEY_RELEASED_VERIFIED = "cluster_fullmap_released_verified"

        /** `original_mode` 读不到时的落盘值。 */
        const val NO_MODE = -1
    }
}

/**
 * 账本存储的最小契约。
 *
 * 用接口而不是直接吃 `SharedPreferences`，是为了让单测能在**不启 Robolectric、
 * 不碰真机**的前提下验证「先记账后切换」的顺序。
 */
internal interface GeelyClusterJournalStore {
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun getInt(key: String, defaultValue: Int): Int
    fun getLong(key: String, defaultValue: Long): Long
    /**
     * 一次提交写完「进入模式」的全部字段。
     *
     * ⚠️ `active` 必须最后写进 Editor：只要它落盘，其余字段就一定已经写好了。
     * 反过来若先写 active、进程在写完 displayId 前被杀，账本会缺行号。
     */
    fun writeAcquireRecord(displayId: Int, originalMode: Int, sinceElapsedMs: Long): Boolean
    fun putBoolean(key: String, value: Boolean): Boolean
    fun putInt(key: String, value: Int): Boolean
    fun putLong(key: String, value: Long): Boolean
    /** 一次提交清空整条账本。 */
    fun clearRecord(): Boolean
}

/**
 * 真机实现：每次写入都 `commit()`。
 *
 * ⚠️ 不用 `apply()`：它是异步的，返回时数据可能还没落盘。账本的唯一价值
 * 就是「进程立刻被杀也还在」，所以这里宁可多花几次同步写的钱。
 */
private class SharedPreferencesJournalStore(
    private val prefs: SharedPreferences,
) : GeelyClusterJournalStore {
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(key, defaultValue)

    override fun getInt(key: String, defaultValue: Int): Int =
        prefs.getInt(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long): Long =
        prefs.getLong(key, defaultValue)

    override fun writeAcquireRecord(
        displayId: Int,
        originalMode: Int,
        sinceElapsedMs: Long,
    ): Boolean = prefs.edit()
        .putInt(GeelyClusterRestoreJournal.KEY_DISPLAY_ID, displayId)
        .putInt(GeelyClusterRestoreJournal.KEY_ORIGINAL_MODE, originalMode)
        .putLong(GeelyClusterRestoreJournal.KEY_SINCE_ELAPSED, sinceElapsedMs)
        .putBoolean(GeelyClusterRestoreJournal.KEY_ACQUIRED_VERIFIED, false)
        .putBoolean(GeelyClusterRestoreJournal.KEY_RELEASED_VERIFIED, false)
        // active 最后写：它是「这条记录完整」的提交点。
        .putBoolean(GeelyClusterRestoreJournal.KEY_ACTIVE, true)
        .commit()

    override fun putBoolean(key: String, value: Boolean): Boolean =
        prefs.edit().putBoolean(key, value).commit()

    override fun putInt(key: String, value: Int): Boolean =
        prefs.edit().putInt(key, value).commit()

    override fun putLong(key: String, value: Long): Boolean =
        prefs.edit().putLong(key, value).commit()

    override fun clearRecord(): Boolean = prefs.edit()
        .remove(GeelyClusterRestoreJournal.KEY_ACTIVE)
        .remove(GeelyClusterRestoreJournal.KEY_DISPLAY_ID)
        .remove(GeelyClusterRestoreJournal.KEY_ORIGINAL_MODE)
        .remove(GeelyClusterRestoreJournal.KEY_SINCE_ELAPSED)
        .remove(GeelyClusterRestoreJournal.KEY_ACQUIRED_VERIFIED)
        .remove(GeelyClusterRestoreJournal.KEY_RELEASED_VERIFIED)
        .commit()
}
