package md.sime.opensime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Tests ensuring the branded cold-start screen requirements are strictly satisfied:
 * 1. Brand blue (#3A5BA9) matches the canonical favicon.svg and ic_launcher_background.
 * 2. System splash screen uses Theme.SplashScreen with brand blue and ic_launcher_foreground.
 * 3. Pre-Compose window background (postSplashScreenTheme / Theme.OpenSime) uses brand blue,
 *    eliminating the white flash in both light and dark mode.
 * 4. AndroidManifest.xml configures MainActivity with Theme.OpenSime.Starting.
 */
class BrandedColdStartThemeTest {

    private val repoRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (dir.parentFile != null && !File(dir, "android").isDirectory) {
            val parent = dir.parentFile ?: break
            dir = parent
        }
        dir
    }

    private val androidMainDir: File by lazy {
        File(repoRoot, "android/app/src/main")
    }

    @Test
    fun `canonical brand blue matches across favicon, launcher background, and colors xml`() {
        val colorsFile = File(androidMainDir, "res/values/colors.xml")
        assertTrue("colors.xml must exist", colorsFile.isFile)
        val colorsContent = colorsFile.readText()

        // Brand blue must be #3A5BA9
        assertTrue(
            "colors.xml must define brand_blue as #3A5BA9",
            colorsContent.contains("""<color name="brand_blue">#3A5BA9</color>""", ignoreCase = true)
        )
        assertTrue(
            "colors.xml must define splash_background referencing brand_blue",
            colorsContent.contains("""<color name="splash_background">@color/brand_blue</color>""")
        )

        // Verify matches launcher background
        val launcherBgFile = File(androidMainDir, "res/drawable/ic_launcher_background.xml")
        assertTrue("ic_launcher_background.xml must exist", launcherBgFile.isFile)
        val launcherBgContent = launcherBgFile.readText()
        assertTrue(
            "ic_launcher_background must use #3A5BA9",
            launcherBgContent.contains("""android:fillColor="#3A5BA9"""", ignoreCase = true)
        )

        // Verify matches canonical favicon.svg
        val faviconFile = File(repoRoot, "web/public/assets/favicon.svg")
        if (faviconFile.isFile) {
            val faviconContent = faviconFile.readText()
            assertTrue(
                "favicon.svg must use #3a5ba9",
                faviconContent.contains("""fill="#3a5ba9"""", ignoreCase = true)
            )
        }
    }

    @Test
    fun `themes xml configures Theme OpenSime with branded blue windowBackground`() {
        val themesFile = File(androidMainDir, "res/values/themes.xml")
        assertTrue("res/values/themes.xml must exist", themesFile.isFile)

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(themesFile)
        val styles = doc.getElementsByTagName("style")

        var foundOpenSime = false
        var foundStarting = false

        for (i in 0 until styles.length) {
            val element = styles.item(i) as Element
            when (element.getAttribute("name")) {
                "Theme.OpenSime" -> {
                    foundOpenSime = true
                    val items = element.getElementsByTagName("item")
                    var hasWindowBg = false
                    for (j in 0 until items.length) {
                        val item = items.item(j) as Element
                        if (item.getAttribute("name") == "android:windowBackground") {
                            assertEquals("@color/brand_blue", item.textContent.trim())
                            hasWindowBg = true
                        }
                    }
                    assertTrue("Theme.OpenSime must set android:windowBackground to @color/brand_blue", hasWindowBg)
                }
                "Theme.OpenSime.Starting" -> {
                    foundStarting = true
                    assertEquals("Theme.SplashScreen", element.getAttribute("parent"))
                    val items = element.getElementsByTagName("item")
                    val itemMap = (0 until items.length).associate {
                        val itm = items.item(it) as Element
                        itm.getAttribute("name") to itm.textContent.trim()
                    }
                    assertTrue(
                        "windowSplashScreenBackground must be splash_background or brand_blue",
                        itemMap["windowSplashScreenBackground"] in setOf("@color/splash_background", "@color/brand_blue")
                    )
                    assertEquals(
                        "@drawable/ic_launcher_foreground",
                        itemMap["windowSplashScreenAnimatedIcon"]
                    )
                    assertEquals(
                        "@style/Theme.OpenSime",
                        itemMap["postSplashScreenTheme"]
                    )
                }
            }
        }

        assertTrue("Theme.OpenSime must be defined in res/values/themes.xml", foundOpenSime)
        assertTrue("Theme.OpenSime.Starting must be defined in res/values/themes.xml", foundStarting)
    }

    @Test
    fun `themes v31 xml configures API 31 splash and windowBackground`() {
        val themesV31File = File(androidMainDir, "res/values-v31/themes.xml")
        assertTrue("res/values-v31/themes.xml must exist", themesV31File.isFile)

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(themesV31File)
        val styles = doc.getElementsByTagName("style")

        var foundStarting = false
        for (i in 0 until styles.length) {
            val element = styles.item(i) as Element
            if (element.getAttribute("name") == "Theme.OpenSime.Starting") {
                foundStarting = true
                assertEquals("Theme.SplashScreen", element.getAttribute("parent"))
                val items = element.getElementsByTagName("item")
                val itemMap = (0 until items.length).associate {
                    val itm = items.item(it) as Element
                    itm.getAttribute("name") to itm.textContent.trim()
                }

                assertTrue(
                    "android:windowSplashScreenBackground must be splash_background or brand_blue",
                    itemMap["android:windowSplashScreenBackground"] in setOf("@color/splash_background", "@color/brand_blue")
                )
                assertEquals(
                    "@drawable/ic_launcher_foreground",
                    itemMap["android:windowSplashScreenAnimatedIcon"]
                )
                assertEquals(
                    "@color/brand_blue",
                    itemMap["android:windowBackground"]
                )
                assertEquals(
                    "@style/Theme.OpenSime",
                    itemMap["postSplashScreenTheme"]
                )
            }
        }

        assertTrue("Theme.OpenSime.Starting must be defined in res/values-v31/themes.xml", foundStarting)
    }

    @Test
    fun `AndroidManifest xml configures MainActivity with Theme OpenSime Starting`() {
        val manifestFile = File(androidMainDir, "AndroidManifest.xml")
        assertTrue("AndroidManifest.xml must exist", manifestFile.isFile)

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
        val activities = doc.getElementsByTagName("activity")

        var mainActivityFound = false
        for (i in 0 until activities.length) {
            val act = activities.item(i) as Element
            val name = act.getAttribute("android:name")
            if (name == ".MainActivity" || name.endsWith("MainActivity")) {
                mainActivityFound = true
                val theme = act.getAttribute("android:theme")
                assertEquals(
                    "MainActivity must use @style/Theme.OpenSime.Starting",
                    "@style/Theme.OpenSime.Starting",
                    theme
                )
            }
        }

        assertTrue("MainActivity must be declared in AndroidManifest.xml", mainActivityFound)
    }

    @Test
    fun `no values-night themes exist so startup background remains branded blue in all modes`() {
        val valuesNightDir = File(androidMainDir, "res/values-night")
        assertFalse(
            "values-night should not override cold-start theme; startup background must remain branded blue",
            valuesNightDir.exists()
        )
    }
}
