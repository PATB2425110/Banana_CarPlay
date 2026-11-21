package com.dbtool.carplay.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class AppBrandingIdentityTest {
    private val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test fun `installed package is the requested Fandeng vehicle identity`() {
        val script = File(root, "car/app/build.gradle.kts").readText()
            .replace(Regex("""/\*[\s\S]*?\*/|//[^\n]*"""), "")
        assertTrue(script.contains("applicationId = \"io.dushu.car\""))
        assertFalse(script.contains("applicationId = \"osn.play\""))
        assertTrue("internal namespaces are not install identity", script.contains("namespace = \"com.dbtool.carplay.app\""))
    }

    @Test fun `launcher name uses Apple CarPlay`() {
        val builder = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder()
        val strings = builder.parse(File(root, "car/app/src/main/res/values/strings.xml"))
        val entries = strings.getElementsByTagName("string")
        val name = (0 until entries.length).map { entries.item(it) as Element }
            .single { it.getAttribute("name") == "app_name" }
        assertEquals("Apple CarPlay", name.textContent)
        val manifest = builder.parse(File(root, "car/app/src/main/AndroidManifest.xml"))
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("@string/app_name", application.getAttributeNS("http://schemas.android.com/apk/res/android", "label"))
    }
}
