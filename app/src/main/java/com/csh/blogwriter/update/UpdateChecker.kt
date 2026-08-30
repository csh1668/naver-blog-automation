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

/** GitHub Releases 최신 릴리스를 조회해 현재 버전보다 새 버전이 있으면 알려 준다. */
interface UpdateChecker {
    /** 실패(오프라인·5xx·파싱)와 "새 버전 없음"(success(null))을 구분한다 — 설정 화면이 둘을 다르게 보여 준다. */
    suspend fun check(
        repo: String = BuildConfig.GITHUB_REPO,
        currentVersion: String = BuildConfig.VERSION_NAME,
    ): Result<UpdateInfo?>

    /** 실패를 조용히 넘기는 쪽(채팅 배너)에서 쓰는 편의 래퍼. */
    suspend fun checkForUpdate(
        repo: String = BuildConfig.GITHUB_REPO,
        currentVersion: String = BuildConfig.VERSION_NAME,
    ): UpdateInfo? = check(repo, currentVersion).getOrNull()
}

class GithubUpdateChecker(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://api.github.com",
) : UpdateChecker {
    override suspend fun check(
        repo: String,
        currentVersion: String,
    ): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body?.string() ?: error("빈 응답")
                val release = json.decodeFromString(GithubRelease.serializer(), body)
                val tag = release.tagName ?: error("tag_name 없음")
                val htmlUrl = release.htmlUrl ?: error("html_url 없음")
                val latest = SemVer.parse(tag) ?: error("버전 형식 아님: $tag")
                val current = SemVer.parse(currentVersion) ?: error("버전 형식 아님: $currentVersion")
                // 새 버전이 없는 건 실패가 아니다 — "최신 버전이에요" 로 보여야 한다.
                Result.success(if (latest > current) UpdateInfo(tag, htmlUrl) else null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d(TAG, "update check failed", e)
            Result.failure(e)
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
