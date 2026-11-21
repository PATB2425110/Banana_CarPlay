package com.dbtool.carplay.bridge.developer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这段 shell 输出是权限拒绝，而不是真的内容」的判据。
 *
 * ## 为什么这条判据值得单测
 *
 * 它决定 `system/package_status.txt` 里写的是**数据**还是**失败说明**。
 * 判错的两个方向代价不对称：
 *
 * - **漏判**（把拒绝当数据）⇒ 服务端拿不到包版本却看不出是失败，
 *   这正是 2026-09-29 实车包里发生的事（310 字节全是拒绝文本）；
 * - **误判**（把数据当拒绝）⇒ 会把真内容标成失败，更糟。
 *
 * 所以下面同时钉住了"拒绝"与"真内容"两侧，而不是只测一侧。
 */
class CarShellOutputTest {

    /** 实车 `system/package_status.txt` 的原文。 */
    private val realDenial = "Permission Denial: can't dump PackageManager from from pid=17064, " +
        "uid=10089 due to missing android.permission.DUMP permission"

    @Test
    fun `实车原文的权限拒绝认得出`() {
        assertTrue(CarShell.looksLikePermissionDenial(realDenial))
    }

    @Test
    fun `真的 dumpsys 内容不算拒绝`() {
        val real = """
            Package [com.flyme.auto.launcher] (1a2b3c):
              userId=10086
              pkg=Package{abc com.flyme.auto.launcher}
              versionName=1.2.3
              versionCode=10203
        """.trimIndent()
        assertFalse(CarShell.looksLikePermissionDenial(real))
    }

    @Test
    fun `空输出不算拒绝（那是另一种失败，调用方另行标记）`() {
        assertFalse(CarShell.looksLikePermissionDenial(""))
        assertFalse(CarShell.looksLikePermissionDenial("   \n  "))
    }

    @Test
    fun `提到 permission 但没被拒的正常输出不算拒绝`() {
        // `dumpsys package` 的真内容里本来就会出现权限名（requested permissions 段），
        // 所以只认"Permission Denial"这个组合，不能只认 "permission"。
        val withPermissions = """
            Package [com.example.app] (abc):
              requested permissions:
                android.permission.DUMP
                android.permission.INTERNET
        """.trimIndent()
        assertFalse(CarShell.looksLikePermissionDenial(withPermissions))
    }
}
