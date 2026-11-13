package md.sime.opensime

import androidx.test.ext.junit.runners.AndroidJUnit4
import md.sime.opensime.core.update.GitHubUpdateChecker
import md.sime.opensime.core.update.UpdateResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GitHubUpdateCheckerTest {
    private fun release(asset: String, url: String = "https://github.com/Dany0443/open-sime/releases/tag/v1", draft: Boolean = false, prerelease: Boolean = false) =
        """{"tag_name":"v1","html_url":"$url","draft":$draft,"prerelease":$prerelease,"body":"Notes","assets":[{"name":"$asset"}]}"""
    private fun check(body: String) = GitHubUpdateChecker.parse(body, "1.3.0")
    @Test fun excludesMissingOrUnrelatedAssets() {
        assertEquals(UpdateResult.NoUpdate, check("[]"))
        listOf("desktop.exe", "Open-Sime-Android-v1.3.apk.bad", "Open-Sime-Android-v1.bad.apk", "Open-Sime-Android-v1.3.1-debug.apk").forEach {
            assertEquals(UpdateResult.NoUpdate, check("[${release(it)}]"))
        }
    }
    @Test fun distinguishesCurrentAndroidVersionFromMissingAndroidAssets() {
        listOf("Open-Sime-Android-v1.3.0.apk", "Open-Sime-Android-v1.2.9.apk").forEach {
            assertEquals(UpdateResult.UpToDate, check("[${release(it)}]"))
        }
        assertEquals(UpdateResult.NoUpdate, check("[${release("desktop.exe")}]"))
    }
    @Test fun validatesReleaseAndSelectsNewestAndroidCode() {
        val newer = release("Open-Sime-Android-v1.10.0.apk")
        val older = release("Open-Sime-Android-v1.9.0.apk")
        val result = check("[$older,$newer]") as UpdateResult.Available
        assertEquals("1.10.0", result.update.version)
        assertEquals("https://github.com/Dany0443/open-sime/releases/tag/v1", result.update.releaseUrl)
        assertEquals(UpdateResult.NoUpdate, check("[${release("Open-Sime-Android-v1.10.0.apk", url = "https://evil.example/release")}]") )
        assertEquals(UpdateResult.NoUpdate, check("[${release("Open-Sime-Android-v1.10.0.apk", url = "https://github.com/other/repo/releases/tag/v1")}]") )
        assertEquals(UpdateResult.NoUpdate, check("[${release("Open-Sime-Android-v1.10.0.apk", draft = true)}]"))
        assertEquals(UpdateResult.NoUpdate, check("[${release("Open-Sime-Android-v1.10.0.apk", prerelease = true)}]"))
    }
    @Test fun malformedJsonIsUnavailable() {
        assertEquals(UpdateResult.Unavailable, check("not json"))
    }
}
