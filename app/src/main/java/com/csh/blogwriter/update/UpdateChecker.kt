package com.csh.blogwriter.update

import android.util.Log
import com.csh.blogwriter.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** 새 버전 안내 배너에 필요한 정보. */
data class UpdateInfo(val tag: String, val htmlUrl: String)

/** GitHub Releases 최신 릴리스를 조회해 현재 버전보다 새 버전이 있으면 알려 준다. 실패 시 null(조용히 넘어감). */
interface UpdateChecker {
    suspend fun checkForUpdate(
        repo: String = BuildConfig.GITHUB_REPO,
        currentVersion: String = BuildConfig.VERSION_NAME,
    ): UpdateInfo?
}

class GithubUpdateChecker(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://api.github.com",
) : UpdateChecker {
    override suspend fun checkForUpdate(
        repo: String,
        currentVersion: String,
    ): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val release = json.decodeFromString(GithubRelease.serializer(), body)
                val tag = release.tagName ?: return@withContext null
                val htmlUrl = release.htmlUrl ?: return@withContext null
                val latest = SemVer.parse(tag) ?: return@withContext null
                val current = SemVer.parse(currentVersion) ?: return@withContext null
                if (latest > current) UpdateInfo(tag, htmlUrl) else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d(TAG, "update check failed", e)
            null
        }
    }

    @Serializable
    private data class GithubRelease(
        @SerialName("tag_name") val tagName: String? = null,
        @SerialName("html_url") val htmlUrl: String? = null,
    )

    companion object {
        private const val TAG = "UpdateChecker"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** `vMAJOR.MINOR.PATCH` 형식만 다룬다. 그 외 형식(프리릴리스 태그 등)은 null. */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")

        fun parse(text: String): SemVer? {
            val match = PATTERN.matchEntire(text) ?: return null
            val (major, minor, patch) = match.destructured
            return SemVer(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}
