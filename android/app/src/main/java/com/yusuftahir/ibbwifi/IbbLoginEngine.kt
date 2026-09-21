package com.yusuftahir.ibbwifi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object IbbLoginEngine {

    private const val PORTAL_URL = "https://viracaptive.ibbwifi.istanbul"
    private const val CHECK_URL = "http://connectivitycheck.gstatic.com/generate_204"

    private fun createClient(): OkHttpClient {
        val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

        return OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    val list = cookieStore.getOrPut(url.host) { mutableListOf() }
                    list.addAll(cookies)
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    return cookieStore[url.host] ?: emptyList()
                }
            })
            .build()
    }

    suspend fun isAlreadyConnected(): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
            val request = Request.Builder().url(CHECK_URL).build()
            val response = client.newCall(request).execute()
            response.code == 204
        } catch (e: Exception) {
            false
        }
    }

    private fun getCsrfToken(html: String): String? {
        val matcher = Pattern.compile("name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"").matcher(html)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun detectPortalUrl(client: OkHttpClient): String {
        val testUrls = listOf(
            CHECK_URL,
            "http://detectportal.firefox.com/canonical.html",
            "http://clients3.google.com/generate_204"
        )
        for (url in testUrls) {
            try {
                val req = Request.Builder().url(url).build()
                val res = client.newCall(req).execute()
                val finalUrl = res.request.url.toString()
                if (finalUrl.contains("ibbwifi") || finalUrl.contains("viracaptive")) {
                    return finalUrl
                }
            } catch (ignored: Exception) {
            }
        }
        return "$PORTAL_URL/"
    }

    suspend fun login(
        phone: String,
        pass: String,
        onStatus: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        if (isAlreadyConnected()) {
            onStatus("Zaten internete bağlısınız.")
            return@withContext Result.success("Zaten bağlı.")
        }

        if (phone.isBlank() || pass.isBlank()) {
            return@withContext Result.failure(Exception("Telefon veya şifre boş bırakılamaz."))
        }

        val client = createClient()

        onStatus("Portal aranıyor...")
        val landingUrl = detectPortalUrl(client)

        onStatus("Açılış sayfası yükleniyor...")
        val landingReq = Request.Builder()
            .url(landingUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) Mobile")
            .build()

        val landingHtml = try {
            client.newCall(landingReq).execute().use { it.body?.string().orEmpty() }
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Portala bağlanılamadı: ${e.message}"))
        }

        val token1 = getCsrfToken(landingHtml)
            ?: return@withContext Result.failure(Exception("İlk güvenlik jetonu (CSRF) alınamadı."))

        onStatus("Telefon numarası gönderiliyor...")
        val phoneJson = JSONObject().apply {
            put("PhoneNumber", phone.trim())
            put("CountryCode", "90")
            put("FlagCode", "tr")
        }.toString()

        val checkReq = Request.Builder()
            .url("$PORTAL_URL/LandingCheck")
            .post(phoneJson.toRequestBody("application/json".toMediaType()))
            .header("X-CSRF-TOKEN", token1)
            .header("Referer", landingUrl)
            .build()

        val checkRes = try {
            client.newCall(checkReq).execute()
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Numara doğrulama hatası: ${e.message}"))
        }

        if (checkRes.code == 429) {
            return@withContext Result.failure(Exception("İstek limiti aşıldı (429). Lütfen birkaç dakika bekleyin."))
        }

        val checkHtml = checkRes.body?.string().orEmpty()
        val token2 = getCsrfToken(checkHtml)
            ?: return@withContext Result.failure(Exception("2. güvenlik jetonu alınamadı."))

        Thread.sleep(800)

        onStatus("Şifre doğrulanıyor...")
        val loginJson = JSONObject().apply {
            put("Password", pass.trim())
        }.toString()

        val loginReq = Request.Builder()
            .url("$PORTAL_URL/Login")
            .post(loginJson.toRequestBody("application/json".toMediaType()))
            .header("X-CSRF-TOKEN", token2)
            .header("Referer", landingUrl)
            .build()

        val loginRes = try {
            client.newCall(loginReq).execute()
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Giriş isteği hatası: ${e.message}"))
        }

        val loginBody = loginRes.body?.string().orEmpty()
        val json = try { JSONObject(loginBody) } catch (e: Exception) { JSONObject() }

        val wisprUrl = json.optString("url")
        if (loginRes.isSuccessful && wisprUrl.isNotBlank()) {
            onStatus("Ağ geçidi onaylanıyor...")
            val wisprReq = Request.Builder().url(wisprUrl).build()
            client.newCall(wisprReq).execute().close()
            onStatus("Giriş başarılı! İnternet aktif.")
            return@withContext Result.success("İnternet aktif!")
        } else {
            val errMsg = json.optString("message", "Giriş başarısız oldu.")
            return@withContext Result.failure(Exception(errMsg))
        }
    }
}
