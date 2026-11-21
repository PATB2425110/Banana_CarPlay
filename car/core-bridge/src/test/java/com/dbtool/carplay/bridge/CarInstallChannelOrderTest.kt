package com.dbtool.carplay.bridge

import com.dbtool.carplay.api.update.CarInstallChannel
import com.dbtool.carplay.api.update.CarInstallOutcome
import com.dbtool.carplay.api.update.isDelegated
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「优先用 ADB 安装，失败就退到系统安装器」这条**顺序**的守门测试。
 *
 * ## 为什么必须钉死
 *
 * 车主 2026-10-07 明确要求安装顺序：**ADB 优先，系统安装器兜底**。
 * 顺序是纯逻辑、没有编译期保护：
 *
 * - 谁把枚举[声明次序]调换（IDE 的"按字母排序"就能干这事），
 *   实际行为会静默变成"每次都弹系统安装器"——用户看到的就是"更新又要手点"，
 *   而且**不报错**，很难联想到是顺序被改。
 * - 谁把 [CarInstallOutcome.FAILED] 也当成"已委托"，
 *   ADB 失败就再也不会退到系统安装器 ⇒ 更新彻底装不上，同样不报错。
 *
 * 这两类回归都只能靠测试拦住。
 *
 * ⚠️ 测试放在 `:car:core-bridge` 而不是 `:car:api`：后者是**零依赖契约模块**
 * （见其 build.gradle.kts 注释），不引入 junit；本模块已有 junit。
 */
class CarInstallChannelOrderTest {

    @Test
    fun adbIsThePreferredChannel() {
        assertEquals(
            "车主口径：首选必须是 ADB 静默安装",
            CarInstallChannel.ADB_SILENT,
            CarInstallChannel.PREFERRED,
        )
    }

    @Test
    fun systemInstallerIsTheLastResort() {
        assertEquals(
            "系统安装器必须是最后一个通道",
            CarInstallChannel.SYSTEM_INSTALLER,
            CarInstallChannel.entries.last(),
        )
        assertNull(
            "最后一个通道不应有后继",
            CarInstallChannel.SYSTEM_INSTALLER.next(),
        )
    }

    /** 声明次序 = 尝试次序；ADB 必须排在系统安装器之前。 */
    @Test
    fun declarationOrderPutsAdbBeforeSystemInstaller() {
        val adb = CarInstallChannel.ADB_SILENT.ordinal
        val sys = CarInstallChannel.SYSTEM_INSTALLER.ordinal
        assertTrue(
            "ADB($adb) 必须排在系统安装器($sys)之前，否则'优先用 ADB'不成立",
            adb < sys,
        )
    }

    /** `next()` 链必须从首选一路走到系统安装器，不断链。 */
    @Test
    fun nextChainReachesSystemInstaller() {
        val visited = mutableListOf<CarInstallChannel>()
        var current: CarInstallChannel? = CarInstallChannel.PREFERRED
        while (current != null && visited.size <= CarInstallChannel.entries.size) {
            visited.add(current)
            current = current.next()
        }
        assertTrue(
            "从首选出发的 next() 链必须包含系统安装器，实际走过：$visited",
            CarInstallChannel.SYSTEM_INSTALLER in visited,
        )
    }

    // --------------------------------------------------------- isDelegated

    @Test
    fun delegatedOutcomesStopTheFallbackChain() {
        assertTrue(CarInstallOutcome.SILENT_COMMITTED.isDelegated())
        assertTrue(CarInstallOutcome.ADB_COMMITTED.isDelegated())
        assertTrue(CarInstallOutcome.LAUNCHER_OPENED.isDelegated())
    }

    /** ⚠️ 核心：FAILED 必须**不算**已委托，否则"失败退到系统安装器"就不发生。 */
    @Test
    fun failureIsNotDelegated() {
        assertFalse(
            "FAILED 若被判为已委托，ADB 失败就不会退到系统安装器——更新会彻底装不上",
            CarInstallOutcome.FAILED.isDelegated(),
        )
    }

    /**
     * `NEEDS_UNKNOWN_SOURCES` 也不算委托（安装并没提交），
     * 但它由 installer 在**最后一步**产生，所以实际不会引发"继续往下试"。
     * 这条测试只是把语义写明白，防止有人误以为它等同于成功。
     */
    @Test
    fun needsUnknownSourcesIsNotDelegated() {
        assertFalse(CarInstallOutcome.NEEDS_UNKNOWN_SOURCES.isDelegated())
    }
}
