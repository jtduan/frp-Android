package io.github.acedroidx.frp

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL

object RemoteConfigFetcher {
    private const val BASE_URL = "http://8.152.221.172:8080/generate_config"

    fun buildUrl(context: Context): String {
        val model = Build.MODEL
        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return "$BASE_URL?model=${encode(model)}&deviceId=${encode(deviceId)}"
    }

    fun fetchConfig(context: Context): Result<String> {
        return runCatching {
            val url = URL(buildUrl(context))
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
                instanceFollowRedirects = true
            }
            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    error("HTTP $code: ${body.take(200)}")
                }
                body
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun encode(value: String?): String {
        return java.net.URLEncoder.encode(value ?: "", "UTF-8")
    }
}
