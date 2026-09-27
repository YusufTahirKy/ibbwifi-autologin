package com.yusuftahir.ibbwifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object IbbLoginEngine {

    private const val PORTAL_URL = "https://viracaptive.ibbwifi.istanbul"
    private const val CHECK_URL = "http://connectivitycheck.gstatic.com/generate_204"

    class CaptiveDns(private val wifiNetwork: Network?) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            // 1. First attempt: resolve using Wi-Fi interface DNS
            if (wifiNetwork != null) {
                try {
                    val addrs = wifiNetwork.getAllByName(hostname).toList()
                    if (addrs.isNotEmpty()) return addrs
                } catch (ignored: Exception) {}
            }

            // 2. Second attempt: system DNS
            try {
                val addrs = Dns.SYSTEM.lookup(hostname)
                if (addrs.isNotEmpty()) return addrs
            } catch (ignored: Exception) {}

            // 3. Fallback for IBB internal domains if Android Private DNS caused NXDOMAIN
            if (hostname.equals("viracaptive.ibbwifi.istanbul", ignoreCase = true)) {
                return listOf(InetAddress.getByAddress(hostname, byteArrayOf(10, 18, 53, 200.toByte())))
            }
            if (hostname.equals("captive.ibbwifi.istanbul", ignoreCase = true)) {
                return listOf(InetAddress.getByAddress(hostname, byteArrayOf(10, 18, 53, 102.toByte())))
            }

            throw UnknownHostException("Unable to resolve host \"$hostname\"")
        }
    }

    fun getWifiNetwork(context: Context): Network? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val wifiNetwork = cm.allNetworks.firstOrNull { network ->
                    val caps = cm.getNetworkCapabilities(network)
                    caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                }
                if (wifiNetwork != null) {
                    cm.bindProcessToNetwork(wifiNetwork)
                    return wifiNetwork
                }
            } else {
                @Suppress("DEPRECATION")
                val wifiNetwork = cm.allNetworks.firstOrNull { network ->
                    val info = cm.getNetworkInfo(network)
                    info != null && info.type == ConnectivityManager.TYPE_WIFI
                }
                if (wifiNetwork != null) {
                    @Suppress("DEPRECATION")
                    ConnectivityManager.setProcessDefaultNetwork(wifiNetwork)
                    return wifiNetwork
                }
            }
        } catch (ignored: Exception) {}
        return null
    }

    private fun createClient(wifiNetwork: Network?): OkHttpClient {
        val cookieStore = mutableMapOf<String, MutableMap<String, Cookie>>()

        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .dns(CaptiveDns(wifiNetwork))
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "*/*")
                    .header("Origin", PORTAL_URL)
                    .build()
                chain.proceed(request)
            }
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    val map = cookieStore.getOrPut(url.host) { mutableMapOf() }
                    for (c in cookies) {
                        map[c.name] = c
                    }
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    val map = cookieStore[url.host] ?: return emptyList()
                    return map.values.toList()
                }
            })

        if (wifiNetwork != null) {
            try {
                builder.socketFactory(wifiNetwork.socketFactory)
            } catch (ignored: Exception) {}
        }

        return builder.build()
    }

    suspend fun isAlreadyConnected(client: OkHttpClient): Boolean = withContext(Dispatchers.IO) {
        try {
            val checkClient = client.newBuilder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
            val request = Request.Builder().url(CHECK_URL).build()
            checkClient.newCall(request).execute().use { response ->
                response.code == 204
            }
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
                client.newCall(req).execute().use { res ->
                    val finalUrl = res.request.url.toString()
                    if (finalUrl.contains("ibbwifi") || finalUrl.contains("viracaptive")) {
                        return finalUrl
                    }
                }
            } catch (ignored: Exception) {}
        }
        return "$PORTAL_URL/"
    }

    suspend fun login(
        context: Context,
        phone: String,
        pass: String,
        onStatus: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        onStatus("Wi-Fi ağına bağlanılıyor...")
        val wifiNetwork = getWifiNetwork(context)
        if (wifiNetwork == null) {
            return@withContext Result.failure(Exception("Telefonunuz bir Wi-Fi ağına bağlı görünmüyor. Lütfen önce ibbWiFi ağına bağlanın."))
        }

        if (phone.isBlank() || pass.isBlank()) {
            return@withContext Result.failure(Exception("Telefon veya şifre boş bırakılamaz."))
        }

        val client = createClient(wifiNetwork)

        if (isAlreadyConnected(client)) {
            onStatus("Zaten internete bağlısınız.")
            return@withContext Result.success("Zaten internete bağlısınız.")
        }

        onStatus("Portal aranıyor...")
        val landingUrl = detectPortalUrl(client)

        onStatus("Açılış sayfası yükleniyor...")
        val landingReq = Request.Builder()
            .url(landingUrl)
            .build()

        val landingHtml = try {
            client.newCall(landingReq).execute().use { it.body?.string().orEmpty() }
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Portala bağlanılamadı: ${e.message}"))
        }

        val token1 = getCsrfToken(landingHtml)
            ?: return@withContext Result.failure(Exception("İlk güvenlik jetonu (CSRF) alınamadı. (Lütfen portal kapsama alanında olduğunuzdan emin olun)"))

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
            checkRes.close()
            return@withContext Result.failure(Exception("İstek limiti aşıldı (429). Lütfen 2-3 dakika bekleyip tekrar deneyin."))
        }

        val checkHtml = checkRes.use { it.body?.string().orEmpty() }
        val token2 = getCsrfToken(checkHtml)
            ?: return@withContext Result.failure(Exception("2. güvenlik jetonu alınamadı. Numaranız kayıtlı olmayabilir."))

        kotlinx.coroutines.delay(800)

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

        val loginBody = loginRes.use { it.body?.string().orEmpty() }
        val json = try { JSONObject(loginBody) } catch (e: Exception) { JSONObject() }

        val wisprUrl = json.optString("url")
        if (loginRes.isSuccessful && wisprUrl.isNotBlank()) {
            onStatus("Ağ geçidi onaylanıyor...")
            try {
                val wisprReq = Request.Builder().url(wisprUrl).build()
                client.newCall(wisprReq).execute().close()
            } catch (ignored: Exception) {}

            onStatus("Giriş başarılı! İnternet aktif.")
            return@withContext Result.success("Giriş başarılı! İnternet aktif.")
        } else {
            val errMsg = json.optString("message", "Giriş başarısız oldu. Lütfen şifrenizi kontrol edin.")
            return@withContext Result.failure(Exception(errMsg))
        }
    }
}
