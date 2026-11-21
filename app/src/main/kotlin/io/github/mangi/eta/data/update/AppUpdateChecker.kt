package io.github.mangi.eta.data.update

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** GitHub 最新 Release 信息，[version] 已去掉 tag 的 v 前缀。 */
internal data class AppLatestRelease(
    val version: String,
    val url: String,
)

/**
 * 应用更新检查：读取本 fork 的 GitHub 最新 Release 并与当前版本比较。
 *
 * 版本号约定为 `v` 前缀 + 点分数字 + 可选发行标记（`v1.1.3-dsh`）。比较按段进行：
 * 两段都是数字时按数值比较，否则按字符串比较。`+提交号`、`-dsh`、`-debug`
 * 这类后缀**两侧都不参与比较**（见 [normalize]）。
 * 所有网络与响应错误统一抛 [IOException]，由调用方提示。
 */
internal object AppUpdateChecker {

    fun fetchLatest(): AppLatestRelease {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    AppStrings.get(
                        R.string.update_err_github_release_http,
                        "GitHub Release 请求失败（HTTP %1\$d）",
                        response.code,
                    ),
                )
            }
            val json = runCatching { JSONObject(response.body.string()) }
                .getOrElse {
                    throw IOException(
                        AppStrings.get(
                            R.string.update_err_github_release_invalid_json,
                            "GitHub Release 响应不是有效 JSON",
                        ),
                        it,
                    )
                }
            val tag = json.optString("tag_name").trim()
            val url = json.optString("html_url").trim()
            if (tag.isEmpty() || url.isEmpty()) {
                throw IOException(
                    AppStrings.get(
                        R.string.update_err_github_release_missing_fields,
                        "GitHub Release 响应缺少 tag_name/html_url",
                    ),
                )
            }
            return AppLatestRelease(version = tag.removePrefix("v"), url = url)
        }
    }

    fun isNewer(latest: String, current: String): Boolean {
        val latestParts = normalize(latest).split('.')
        val currentParts = normalize(current).split('.')
        for (index in 0 until maxOf(latestParts.size, currentParts.size)) {
            val latestPart = latestParts.getOrNull(index) ?: "0"
            val currentPart = currentParts.getOrNull(index) ?: "0"
            val latestNumber = latestPart.toIntOrNull()
            val currentNumber = currentPart.toIntOrNull()
            if (latestNumber != null && currentNumber != null) {
                if (latestNumber != currentNumber) return latestNumber > currentNumber
            } else {
                val compared = latestPart.compareTo(currentPart)
                if (compared != 0) return compared > 0
            }
        }
        return false
    }

    /**
     * 去掉 v 前缀、构建元数据（+提交号）和预发布/发行标记（-dsh、-debug）后返回可比较的版本号。
     *
     * 两侧都必须归一化：本仓库发的是 `1.1.3-dsh`，若只剥当前版本的后缀，
     * 远端 `1.1.3-dsh` 会被切成 `["1","1","3-dsh"]`，与 `["1","1","3"]` 走字符串比较，
     * `"3-dsh" > "3"` 恒成立 —— 同一版本也会被判定为「有新版本」。
     */
    private fun normalize(version: String): String =
        version.removePrefix("v").substringBefore('+').substringBefore('-')

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /**
     * 本 fork 自己的 Release 源。
     *
     * 原先指向上游 `Mangi-11/Eta`，导致「检查更新」永远提示上游的 3.4.x，
     * 用户发的版本反而收不到通知。上游版本号（3.x）与本 fork（1.1.x-dsh）不同轨，
     * 所以必须查自己的仓库。
     */
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/LANDRINKJUICE/EtaETA/releases/latest"
    private const val USER_AGENT = "Eta-Update-Checker"
}
