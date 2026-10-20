package app.fusion.wallet.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BrandVectorTest {
    @Test fun officialMarksHaveVisibleForegroundAndValidViewport() {
        for ((name, viewport, count) in listOf(Triple("ic_crypto_usdc", "32", 3), Triple("ic_network_base", "146", 2))) {
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(File("src/main/res/drawable/$name.xml"))
            val ns = "http://schemas.android.com/apk/res/android"
            assertEquals(viewport, doc.documentElement.getAttributeNS(ns, "viewportWidth"))
            assertEquals(viewport, doc.documentElement.getAttributeNS(ns, "viewportHeight"))
            val paths = doc.getElementsByTagName("path")
            assertEquals(count, paths.length)
            for (i in 0 until paths.length) {
                val p = paths.item(i) as org.w3c.dom.Element
                assertTrue(p.getAttributeNS(ns, "pathData").isNotBlank())
                assertEquals("nonZero", p.getAttributeNS(ns, "fillType"))
                if (i > 0) {
                    assertEquals("#FFFFFF", p.getAttributeNS(ns, "fillColor"))
                    // Foreground uses normalized absolute curves, never compact SVG arc flags.
                    assertFalse(p.getAttributeNS(ns, "pathData").contains(Regex("[aA]")))
                }
            }
            val circle = (paths.item(0) as org.w3c.dom.Element).getAttributeNS(ns, "pathData")
            assertTrue(circle.startsWith("M 0 ${viewport.toInt() / 2.0}"))
            assertEquals(2, circle.count { it == 'A' })
        }
    }
}
