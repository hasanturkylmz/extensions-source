package eu.kanade.tachiyomi.extension.tr.mangatr

import eu.kanade.tachiyomi.network.GET
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

class DDoSGuardInterceptor(private val client: OkHttpClient) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)

        // DDoS-Guard sometimes returns a 200 OK with a JavaScript challenge instead of a 403.
        val isDdosGuard = response.code == 403 || (
            response.code == 200 && response.header("Content-Type")?.contains("text/html") == true && runCatching {
                response.peekBody(1024 * 1024 * 5).string().contains("check.ddos-guard.net/check.js")
            }.getOrDefault(false)
            )

        // Check if DDos-GUARD is on
        if (!isDdosGuard) {
            return response
        }

        // Save cookies from the blocked response first to OkHttp's CookieJar
        val cookies = okhttp3.Cookie.parseAll(originalRequest.url, response.headers)
        client.cookieJar.saveFromResponse(originalRequest.url, cookies)

        response.close()

        val wellKnown = try {
            client.newCall(GET(WELL_KNOWN_URL, originalRequest.headers))
                .execute().use { it.body.string() }
        } catch (e: Exception) {
            ""
        }

        if (wellKnown.isNotBlank()) {
            val paths = PATH_REGEX.findAll(wellKnown)
                .map { m -> m.groupValues[1] }
                .toList()

            for (path in paths) {
                val checkUrl = when {
                    path.startsWith("http") -> path
                    else -> {
                        val formattedPath = if (path.startsWith("/")) path else "/$path"
                        "${originalRequest.url.scheme}://${originalRequest.url.host}$formattedPath"
                    }
                }
                try {
                    client.newCall(GET(checkUrl, originalRequest.headers)).execute().close()
                } catch (_: Exception) {}
            }
        }

        // Re-execute the original request with the injected cookie applied natively.
        return chain.proceed(originalRequest)
    }

    companion object {
        private const val WELL_KNOWN_URL = "https://check.ddos-guard.net/check.js"
        private val PATH_REGEX = Regex("""['"]([^'"]+)['"]""")
    }
}
