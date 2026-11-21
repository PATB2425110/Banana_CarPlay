package com.dbtool.carplay.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class AppLauncherIconContractTest {
    private val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private fun xml(relative: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(File(root, relative))
    private val android = "http://schemas.android.com/apk/res/android"

    @Test fun `both launcher masks use supplied CarPlay mark and gradient`() {
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val doc = xml("car/app/src/main/res/mipmap-anydpi-v26/$name.xml")
            assertEquals("@drawable/ic_carplay_background", (doc.getElementsByTagName("background").item(0) as Element)
                .getAttributeNS(android, "drawable"))
            assertEquals("@drawable/ic_carplay_foreground", (doc.getElementsByTagName("foreground").item(0) as Element)
                .getAttributeNS(android, "drawable"))
        }
    }

    @Test fun `white mark is exactly the path in user supplied SVG`() {
        val original = xml("asset/branding/apple-carplay-icon-logo-svg-vector.svg")
        val paths = original.getElementsByTagName("path")
        val source = (0 until paths.length).map { paths.item(it) as Element }
            .single { it.getAttribute("fill") == "#fff" }
        val vector = xml("car/app/src/main/res/drawable/ic_carplay_foreground.xml")
        assertEquals(source.getAttribute("d"), (vector.getElementsByTagName("path").item(0) as Element)
            .getAttributeNS(android, "pathData"))
        assertEquals("108", vector.documentElement.getAttributeNS(android, "viewportWidth"))
        assertEquals("54", (vector.getElementsByTagName("group").item(0) as Element)
            .getAttributeNS(android, "translateX"))
    }

    @Test fun `adaptive foreground is uniformly scaled inside the Android safe circle`() {
        val vector = xml("car/app/src/main/res/drawable/ic_carplay_foreground.xml")
        val groups = vector.getElementsByTagName("group")
        val center = groups.item(0) as Element
        val scale = groups.item(1) as Element
        val sourceOrigin = groups.item(2) as Element
        assertEquals("54", center.getAttributeNS(android, "translateY"))
        val sx = scale.getAttributeNS(android, "scaleX").toFloat()
        val sy = scale.getAttributeNS(android, "scaleY").toFloat()
        assertEquals(sx, sy, 0f)
        assertEquals("-85.225", sourceOrigin.getAttributeNS(android, "translateX"))
        assertEquals("-85.256", sourceOrigin.getAttributeNS(android, "translateY"))
        val radius = 44.707f * sx // outer circle in the source white path
        assertEquals(28f, radius, 0.001f)
        assertTrue("foreground must fit in the 66dp guaranteed circle, including scale tolerance", radius < 33f)
        assertEquals(54f - radius, 26f, 0.001f)
        assertEquals(54f + radius, 82f, 0.001f)
    }

    @Test fun `gradient preserves original three colors and stop positions`() {
        val background = xml("car/app/src/main/res/drawable/ic_carplay_background.xml")
        val items = background.getElementsByTagName("item")
        assertEquals(3, items.length)
        assertEquals(listOf("#0BD319", "#18D722", "#86FC6F"),
            (0 until items.length).map { (items.item(it) as Element).getAttributeNS(android, "color") })
        assertEquals(listOf("0", "0.22", "1"),
            (0 until items.length).map { (items.item(it) as Element).getAttributeNS(android, "offset") })
    }
}
