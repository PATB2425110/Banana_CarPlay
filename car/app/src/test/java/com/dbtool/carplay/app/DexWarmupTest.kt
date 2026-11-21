package com.dbtool.carplay.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DexWarmup.shouldWarmUp] 的边界单测。
 *
 * 这条判定决定"每次覆盖安装后要不要白占进程 25 秒"，判错的后果都是静默的：
 * 判松了每次重装都多撑 25 秒，判紧了升级后不预热、首启又慢回 9 秒。
 */
class DexWarmupTest {

    @Test
    fun `从未预热过要预热`() {
        assertTrue(DexWarmup.shouldWarmUp(lastWarmedVersionCode = 0, currentVersionCode = 209))
    }

    @Test
    fun `同一版本已预热过不再预热`() {
        assertFalse(DexWarmup.shouldWarmUp(lastWarmedVersionCode = 209, currentVersionCode = 209))
    }

    @Test
    fun `版本号变大会再次预热`() {
        assertTrue(DexWarmup.shouldWarmUp(lastWarmedVersionCode = 209, currentVersionCode = 210))
    }

    @Test
    fun `版本号非法时不预热`() {
        assertFalse(DexWarmup.shouldWarmUp(lastWarmedVersionCode = 0, currentVersionCode = 0))
        assertFalse(DexWarmup.shouldWarmUp(lastWarmedVersionCode = 209, currentVersionCode = -1))
    }

    @Test
    fun `降级安装也会预热`() {
        // 降级同样会重新解 dex、重新编译，所以也要撑住进程。
        assertTrue(DexWarmup.shouldWarmUp(lastWarmedVersionCode = 210, currentVersionCode = 209))
    }
}
