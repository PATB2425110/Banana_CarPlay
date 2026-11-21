package com.dbtool.carplay.bridge.hotspot

import com.dbtool.carplay.api.hotspot.CarHotspotEnabledState
import com.dbtool.carplay.api.hotspot.CarManualHotspotPrecheck
import com.dbtool.carplay.api.settings.CarHotspotMode
import com.dbtool.carplay.api.settings.reusesCarNativeHotspot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「复用车机热点」连接前快速检查的单测（PATCH-0040）。
 *
 * ## 这一组用例在防什么
 *
 * 这条检查是**唯一一处会主动拦掉用户操作**的新逻辑，所以两个方向都必须钉死：
 *
 * - **不能漏拦**：热点确实关着（`13` 以外的合法取值）时，必须给出可执行文案，
 *   而不是让上游空转 `HOTSPOT_START_TIMEOUT_MILLIS` 再抛一句看不懂的超时。
 * - **不能错杀**：读不到（`null` / 区间外的怪值 / 权限被裁）时必须**放行**。
 *   车机 ROM 把隐藏 API 裁掉是常态，把"读不到"当"关着"会让本来能用的热点连不上——
 *   那比多等几十秒糟得多。
 *
 * 另外还钉死**作用域**：只有**复用类两档**（[CarHotspotMode.MANUAL] 存量值、
 * [CarHotspotMode.TCAM_HOTSPOT] 界面上的「复用车机热点」，判据见
 * `CarHotspotMode.reusesCarNativeHotspot`）才受这条检查管辖。
 * 自建热点（`ADB_HOSTAPD`）那张网是**我们自己起的**，
 * 车机自带热点开关关着才正常 —— 一旦 [CarManualHotspotPrecheck.blocker]
 * 对它也返回文案，自建热点会被这条检查直接打死。
 *
 * 移植来源：DiPlay v0.2.15 `shared/.../network/CarHotspotStatus.kt`
 * （`isEnabled(context): Boolean?`，原文注释 *"null when the firmware hides it
 * (then callers must not block the connection)"*）。
 */
class CarManualHotspotPrecheckTest {

    // ---------------------------------------------------------------- fromWifiApState

    /**
     * `WifiManager.getWifiApState()` 的合法区间是 `10..14`，其中 `13` 才是「已开启」。
     *
     * ⚠️ `12`（ENABLING，正在开）**也算 OFF**：这一刻热点还没起来，上游此刻 attach
     * 必然找不到接口。让它走快速失败 + 可执行文案，比空转数十秒好。
     * 这条是刻意的产品决策，所以用断言固定住，别让后人"顺手改成 ON"。
     */
    @Test
    fun `ap state thirteen is the only enabled value`() {
        assertEquals(CarHotspotEnabledState.ON, CarManualHotspotPrecheck.fromWifiApState(13))

        assertEquals(CarHotspotEnabledState.OFF, CarManualHotspotPrecheck.fromWifiApState(10))
        assertEquals(CarHotspotEnabledState.OFF, CarManualHotspotPrecheck.fromWifiApState(11))
        assertEquals(CarHotspotEnabledState.OFF, CarManualHotspotPrecheck.fromWifiApState(12))
        assertEquals(CarHotspotEnabledState.OFF, CarManualHotspotPrecheck.fromWifiApState(14))
    }

    /**
     * 区间外的取值一律 UNKNOWN，**不是 OFF**。
     *
     * 有些 ROM 在"读不到"时返回 `0` / `-1`，还有的返回自己的枚举；把它们当"关着"
     * 就是错杀。这里逐个钉住边界（`9` / `15` 是区间外紧邻的两个值）。
     */
    @Test
    fun `an out of range ap state is unknown rather than off`() {
        assertEquals(CarHotspotEnabledState.UNKNOWN, CarManualHotspotPrecheck.fromWifiApState(null))
        assertEquals(CarHotspotEnabledState.UNKNOWN, CarManualHotspotPrecheck.fromWifiApState(0))
        assertEquals(CarHotspotEnabledState.UNKNOWN, CarManualHotspotPrecheck.fromWifiApState(-1))
        assertEquals(CarHotspotEnabledState.UNKNOWN, CarManualHotspotPrecheck.fromWifiApState(9))
        assertEquals(CarHotspotEnabledState.UNKNOWN, CarManualHotspotPrecheck.fromWifiApState(15))
        assertEquals(CarHotspotEnabledState.UNKNOWN, CarManualHotspotPrecheck.fromWifiApState(Int.MIN_VALUE))
    }

    // ---------------------------------------------------------------------- blocker

    /** 关着 → 必须给文案，且文案里要出现让用户能动手的两个词。 */
    @Test
    fun `a switched off car hotspot is reported with an actionable message`() {
        val message = CarManualHotspotPrecheck.blocker(
            mode = CarHotspotMode.MANUAL,
            state = CarHotspotEnabledState.OFF,
            ssid = "Geely-CarPlay",
        )

        assertNotNull("热点关着时必须拦下并给出文案", message)
        assertTrue("文案要指出车机自带热点是关的：$message", message!!.contains("关闭状态"))
        assertTrue("文案要指路「个人热点」：$message", message.contains("个人热点"))
        assertTrue("文案要给退路「自建热点」：$message", message.contains("自建热点"))
    }

    /** 填了 SSID 就指名道姓；没填（或全是空白）也不能漏出一个空的「」。 */
    @Test
    fun `the message names the hotspot only when an ssid was entered`() {
        val named = CarManualHotspotPrecheck.blocker(
            mode = CarHotspotMode.MANUAL,
            state = CarHotspotEnabledState.OFF,
            ssid = "Geely-CarPlay",
        )
        assertTrue("填了 SSID 就该带上它：$named", named!!.contains("「Geely-CarPlay」"))

        val unnamed = CarManualHotspotPrecheck.blocker(
            mode = CarHotspotMode.MANUAL,
            state = CarHotspotEnabledState.OFF,
            ssid = null,
        )
        assertTrue("没填 SSID 就不该出现空引号：$unnamed", !unnamed!!.contains("「」"))

        val blank = CarManualHotspotPrecheck.blocker(
            mode = CarHotspotMode.MANUAL,
            state = CarHotspotEnabledState.OFF,
            ssid = "   ",
        )
        // ⚠️ 不能断言 `!contains("「")`：文案里本来就带着「个人热点」「自建热点」两处引号，
        // 那样断言恒真，什么都证明不了。改成钉住**紧跟在"车机自带热点"之后**就是"现在"，
        // 也就是那个位置没有被插进任何名字。
        assertTrue("空白 SSID 等同于没填：$blank", blank!!.startsWith("车机自带热点现在是关闭状态"))
        assertEquals("空白 SSID 与不填必须得到同一句话", unnamed, blank)
    }

    /** 开着 / 读不到都放行 —— 「读不到不阻断」是这条检查的核心纪律。 */
    @Test
    fun `only a confirmed off state blocks the connection`() {
        assertNull(
            CarManualHotspotPrecheck.blocker(CarHotspotMode.MANUAL, CarHotspotEnabledState.ON),
        )
        assertNull(
            "读不到必须放行，否则会把本来能用的热点错杀",
            CarManualHotspotPrecheck.blocker(CarHotspotMode.MANUAL, CarHotspotEnabledState.UNKNOWN),
        )
    }

    /**
     * 🔴 **作用域**：受管辖的是**复用类两档** —— [CarHotspotMode.MANUAL]（存量值）
     * 与 [CarHotspotMode.TCAM_HOTSPOT]（界面上的「复用车机热点」），判据见
     * `CarHotspotMode.reusesCarNativeHotspot`。
     *
     * 其余三种模式**即使热点明确关着也必须放行**：
     * - `ADB_HOSTAPD`（自建热点）：那张网是我们自己用 hostapd 起的，车机自带热点
     *   关着才是常态 —— 一旦这里返回文案，自建热点会被直接打死；
     * - `AUTO`：解析阶段就已经落到 `ADB_HOSTAPD`，走的同一条路；
     * - `SAME_LAN`（局域网连接）：一张热点都不建，跟车机热点开关毫无关系。
     *
     * ⚠️ 2026-10-16 口径更新：TCAM 档加入管辖（此前这里只放行 MANUAL）。
     * 两档在连接时都要干等车机热点，预检统一把它们拦在连接前。
     */
    @Test
    fun `the precheck never applies to the non reuse hotspot modes`() {
        for (mode in CarHotspotMode.entries) {
            if (mode.reusesCarNativeHotspot) continue
            assertNull(
                "$mode 不受这条检查管辖，即使车机热点关着也必须放行",
                CarManualHotspotPrecheck.blocker(mode, CarHotspotEnabledState.OFF, "Geely-CarPlay"),
            )
        }
    }

    /**
     * 🔴 2026-10-16：TCAM 档（界面上的「复用车机热点」）同样受管辖，
     * 但**不要求用户填地址** —— 它的接入卡与地址由 `TcamHotspotManager` 自动识别。
     *
     * 调用方（`CarPlaySessionBridge.ensureManualHotspotReady`）对它传
     * `reuseRequested = false`：TCAM 的 plan `reused` 同样是 true，
     * 照 `reused` 传会让预检误报「复用模式需要填地址」。
     */
    @Test
    fun `tcam reuse is prechecked but never asked for an address`() {
        val off = CarManualHotspotPrecheck.blocker(
            mode = CarHotspotMode.TCAM_HOTSPOT,
            state = CarHotspotEnabledState.OFF,
            ssid = "Lynk & Co",
            reuseRequested = false,
        )
        assertNotNull("车机热点关着时 TCAM 档必须给出可执行文案", off)
        assertTrue("文案必须指名道姓说清是哪张热点", off!!.contains("Lynk & Co"))
        assertTrue("地址没填不该被 TCAM 档报出来（地址是自动识别的）", !off.contains("地址"))

        assertNull(
            "热点开着就放行",
            CarManualHotspotPrecheck.blocker(
                mode = CarHotspotMode.TCAM_HOTSPOT,
                state = CarHotspotEnabledState.ON,
                ssid = "Lynk & Co",
                reuseRequested = false,
            ),
        )
    }

    // ------------------------------------------------- 「地址由用户填」（PATCH-0087）

    /**
     * 🔴 2026-10-11（PATCH-0087）：车主口径 ——
     * *"复用车机热点是要用户自己手动设置，而不是自动识别"*。
     *
     * ⇒ 复用模式下地址没填就**在连接前拦下**，并且文案要**说清填什么**。
     * 上一版（PATCH-0086）走的是反方向（"我们认网口，认不出就拦"），
     * 而两次认错（2026-10-11 的 `1791624952142` 与 `1791702691839`）都是
     * 我们**猜到了自己自建热点的残留地址**。
     */
    @Test
    fun `复用模式没填地址时拦下并说清填什么`() {
        val message = CarManualHotspotPrecheck.blocker(
            mode = CarHotspotMode.MANUAL,
            // 热点明确**开着**也要拦：缺的是我们这边的设置，与车机状态无关。
            state = CarHotspotEnabledState.ON,
            ssid = "Lynk",
            hostAddress = "",
            reuseRequested = true,
        )

        assertNotNull("复用没填地址必须拦下（否则只能按网卡名猜，猜到的可能是自建残留）", message)
        assertTrue("要说清是「复用」这一支要填：$message", message!!.contains("复用"))
        assertTrue("要明确这项是本机 CarPlay endpoint：$message", message.contains("本机 CarPlay/AirPlay IPv4"))
        assertTrue("要提醒不要填外置网关：$message", message.contains("不是本机地址"))
        assertTrue("要指路设置页里的那一栏：$message", message.contains("热点身份"))
        // 状态是 ON 时不该冒出"关闭状态"那句（两句话各自独立成立）。
        assertTrue("热点开着就不该说它关着：$message", !message.contains("关闭状态"))
    }

    /** 填了地址、热点开着 ⇒ 放行。空白串与不填等价。 */
    @Test
    fun `填了地址就放行，空白等同不填`() {
        for (address in listOf("192.168.15.1", " 10.0.0.1 ")) {
            assertNull(
                "填了地址且热点开着必须放行：$address",
                CarManualHotspotPrecheck.blocker(
                    mode = CarHotspotMode.MANUAL,
                    state = CarHotspotEnabledState.ON,
                    ssid = "Lynk",
                    hostAddress = address,
                    reuseRequested = true,
                ),
            )
        }
        for (address in listOf("", "   ")) {
            assertNotNull(
                "空白串等同于没填：'$address'",
                CarManualHotspotPrecheck.blocker(
                    mode = CarHotspotMode.MANUAL,
                    state = CarHotspotEnabledState.UNKNOWN,
                    hostAddress = address,
                    reuseRequested = true,
                ),
            )
        }
    }

    /**
     * 两个问题一起出现时**一次说清**（用户不必来回试两次）：
     * 热点关着 **且** 地址没填 ⇒ 两句话都在，且"关着"那句仍在开头
     * （2026-10-08 移植 DiPlay 时的文案位置不动）。
     */
    @Test
    fun `热点关着且地址没填时两句话一起给`() {
        val message = CarManualHotspotPrecheck.blocker(
            mode = CarHotspotMode.MANUAL,
            state = CarHotspotEnabledState.OFF,
            ssid = "Lynk",
            hostAddress = "",
            reuseRequested = true,
        )

        assertNotNull(message)
        assertTrue(
            "仍以「关着」那句开头：$message",
            message!!.startsWith("车机自带热点「Lynk」现在是关闭状态"),
        )
        assertTrue("也要指出缺少本机endpoint：$message", message.contains("本机 CarPlay/AirPlay IPv4"))
    }

    /**
     * `reuseRequested = false`（= 不是用户显式选的「复用」，例如 P2P 组主被翻译成
     * `MANUAL` 形状）**不受地址那一项管辖**：那条路的地址来自组主的 GO，
     * 从来没经过设置页。
     */
    @Test
    fun `不是「复用」那一支时地址缺失不拦人`() {
        assertNull(
            CarManualHotspotPrecheck.blocker(
                mode = CarHotspotMode.MANUAL,
                state = CarHotspotEnabledState.ON,
                hostAddress = null,
                reuseRequested = false,
            ),
        )
    }

    // ------------------------------------------------------------- CarHotspotEnabledReader

    /**
     * 粘性广播的三字段 → 三态。
     *
     * `wifi_ap_mode` 为 `1` 才是普通共享热点；其余（尤其 local-only AP）**不表态**。
     * DiPlay 原文：*"Local-only AP state says nothing about the car's tethered hotspot,
     * including on multi-AP firmware."*
     */
    @Test
    fun `a local only ap broadcast says nothing about the car hotspot`() {
        assertEquals(
            CarHotspotEnabledState.ON,
            CarHotspotEnabledReader.fromSticky(hasApMode = true, apMode = 1, rawState = 13),
        )
        assertEquals(
            "local-only AP（apMode != 1）不表态",
            CarHotspotEnabledState.UNKNOWN,
            CarHotspotEnabledReader.fromSticky(hasApMode = true, apMode = 0, rawState = 13),
        )
        assertEquals(
            CarHotspotEnabledState.UNKNOWN,
            CarHotspotEnabledReader.fromSticky(hasApMode = true, apMode = 2, rawState = 11),
        )
    }

    /** 老 ROM 不带 `wifi_ap_mode`：没有这个字段就没有反对意见，按 `wifi_state` 判。 */
    @Test
    fun `without the ap mode extra the raw state is trusted`() {
        assertEquals(
            CarHotspotEnabledState.ON,
            CarHotspotEnabledReader.fromSticky(hasApMode = false, apMode = -1, rawState = 13),
        )
        assertEquals(
            CarHotspotEnabledState.OFF,
            CarHotspotEnabledReader.fromSticky(hasApMode = false, apMode = -1, rawState = 11),
        )
    }

    /** 粘性广播里 `wifi_state` 缺失（`-1`）或离谱 → UNKNOWN，不是 OFF。 */
    @Test
    fun `a missing or absurd sticky state is unknown`() {
        assertEquals(
            CarHotspotEnabledState.UNKNOWN,
            CarHotspotEnabledReader.fromSticky(hasApMode = false, apMode = -1, rawState = -1),
        )
        assertEquals(
            CarHotspotEnabledState.UNKNOWN,
            CarHotspotEnabledReader.fromSticky(hasApMode = true, apMode = 1, rawState = 0),
        )
    }

    /**
     * 逐级降级：① `getWifiApState()` 读通就定案，后面的信号**根本不会被求值**。
     *
     * 这也是为什么三个入参是 lambda 而不是值 —— 若在调用前就把三次读取全做掉，
     * 一次 `SecurityException` 就会把后面本来能用的信号一起废掉。
     */
    @Test
    fun `the first readable signal wins and the later ones are not evaluated`() {
        var enabledReads = 0
        var stickyReads = 0

        val state = CarHotspotEnabledReader.read(
            state = { 13 },
            enabled = { enabledReads++; false },
            sticky = { stickyReads++; null },
        )

        assertEquals(CarHotspotEnabledState.ON, state)
        assertEquals("state 读通后不该再去问 isWifiApEnabled", 0, enabledReads)
        assertEquals("state 读通后不该再去读粘性广播", 0, stickyReads)
    }

    /** ① 抛异常 / 返回区间外的怪值 ⇒ 落到 ② `isWifiApEnabled()`。 */
    @Test
    fun `an unreadable ap state degrades to isWifiApEnabled`() {
        assertEquals(
            CarHotspotEnabledState.ON,
            CarHotspotEnabledReader.read(
                state = { throw SecurityException("hidden api removed") },
                enabled = { true },
                sticky = { null },
            ),
        )
        assertEquals(
            CarHotspotEnabledState.OFF,
            CarHotspotEnabledReader.read(
                state = { throw NoSuchMethodException("no getWifiApState") },
                enabled = { false },
                sticky = { null },
            ),
        )
        assertEquals(
            "0 是区间外的怪值，不能当 OFF，要落到下一个信号",
            CarHotspotEnabledState.ON,
            CarHotspotEnabledReader.read(state = { 0 }, enabled = { true }, sticky = { null }),
        )
    }

    /** ①② 都读不通 ⇒ UNKNOWN（粘性广播取不到，或 ② 抛异常）。 */
    @Test
    fun `when nothing can be read the answer is unknown`() {
        assertEquals(
            CarHotspotEnabledState.UNKNOWN,
            CarHotspotEnabledReader.read(state = { null }, enabled = { null }, sticky = { null }),
        )
        assertEquals(
            CarHotspotEnabledState.UNKNOWN,
            CarHotspotEnabledReader.read(
                state = { null },
                enabled = { throw IllegalStateException("binder dead") },
                sticky = { null },
            ),
        )
    }
}
