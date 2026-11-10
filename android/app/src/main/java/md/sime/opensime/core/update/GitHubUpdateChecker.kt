package md.sime.opensime.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Android release versions come from the Android APK filename, never the shared release tag. */
data class AndroidUpdate(val version: String, val releaseUrl: String, val notes: String)

sealed interface UpdateResult {
    data object Idle : UpdateResult
    data class Available(val update: AndroidUpdate) : UpdateResult
    data object NoUpdate : UpdateResult
    data object Unavailable : UpdateResult
}

object GitHubUpdateChecker {
    private const val API = "https://api.github.com/repos/Dany0443/open-sime/releases?per_page=30"
    private val assetName = Regex("Open-Sime-Android-v([0-9]+(?:\\.[0-9]+)+)\\.apk")
    private val versionPattern = Regex("[0-9]+(?:\\.[0-9]+)+")

    private fun compareVersions(a: String, b: String): Int {
        val left = a.split('.').map { it.toBigInteger() }
        val right = b.split('.').map { it.toBigInteger() }
        for (i in 0 until maxOf(left.size, right.size)) {
            val comparison = (left.getOrNull(i) ?: java.math.BigInteger.ZERO).compareTo(right.getOrNull(i) ?: java.math.BigInteger.ZERO)
            if (comparison != 0) return comparison
        }
        return 0
    }

    suspend fun check(installedVersion: String): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val connection = URL(API).openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                if (connection.responseCode != 200) return@withContext UpdateResult.Unavailable
                val body = connection.inputStream.bufferedReader().use { it.readTextLimited() }
                if (body.length > 1_000_000) return@withContext UpdateResult.Unavailable
                parse(body, installedVersion)
            } finally { connection.disconnect() }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { UpdateResult.Unavailable }
    }

    private fun java.io.Reader.readTextLimited(): String {
        val buffer = CharArray(8192)
        val output = StringBuilder()
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.length + count <= 1_000_000)
            output.append(buffer, 0, count)
        }
        return output.toString()
    }

    fun parse(body: String, installedVersion: String): UpdateResult = try {
        require(installedVersion.length <= 64 && versionPattern.matches(installedVersion))
        val releases = JSONArray(body)
        val updates = mutableListOf<AndroidUpdate>()
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft", true) || release.optBoolean("prerelease", true)) continue
            val tag = release.optString("tag_name")
            val url = release.optString("html_url")
            val uri = runCatching { URI(url) }.getOrNull() ?: continue
            if (tag.isBlank() || uri.scheme != "https" || uri.host != "github.com" || uri.port != -1 ||
                uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
                uri.path != "/Dany0443/open-sime/releases/tag/$tag") continue
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val match = assetName.matchEntire(asset.optString("name")) ?: continue
                val version = match.groupValues[1]
                if (version.length > 64) continue
                if (compareVersions(version, installedVersion) > 0) updates += AndroidUpdate(version, url, release.optString("body").take(500))
            }
        }
        updates.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }?.let(UpdateResult::Available) ?: UpdateResult.NoUpdate
    } catch (_: Exception) { UpdateResult.Unavailable }
}
