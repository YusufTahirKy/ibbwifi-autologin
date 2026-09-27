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
            // Immediate zero-latency mapping for IBB internal infrastructure
            if (hostname.equals("viracaptive.ibbwifi.istanbul", ignoreCase = true)) {
                return listOf(InetAddress.getByAddress(hostname, byteArrayOf(10, 18, 53, 200.toByte())))
            }
            if (hostname.equals("captive.ibbwifi.istanbul", ignoreCase = true)) {
                return listOf(InetAddress.getByAddress(hostname, byteArrayOf(10, 18, 53, 102.toByte())))
            }

            // Connectivity check domains mapped to Google IP so port 80 interception works instantly
            if (hostname.contains("connectivitycheck.gstatic.com", ignoreCase = true) ||
                hostname.contains("clients3.google.com", ignoreCase = true)) {
                return listOf(InetAddress.getByAddress(hostname, byteArrayOf(172.toByte(), 217.toByte(), 18.toByte(), 14.toByte())))
            }

            // For other domains, try Wi-Fi interface DNS first
            if (wifiNetwork != null) {
                try {
                    val addrs = wifiNetwork.getAllByName(hostname).toList()
                    if (addrs.isNotEmpty()) return addrs
                } catch (ignored: Exception) {}
            }

            // Fallback to system DNS
            try {
                return Dns.SYSTEM.lookup(hostname)
            } catch (e: Exception) {
                // Return portal IP as safe fallback to avoid crash
                return listOf(InetAddress.getByAddress(hostname, byteArrayOf(10, 18, 53, 200.toByte())))
            }
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

    private fun isConnected(wifiNetwork: Network?): Boolean {
        return try {
            val checkClient = OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .followRedirects(false)
                .dns(CaptiveDns(wifiNetwork))
                .build()
            val req = Request.Builder().url(CHECK_URL).build()
            checkClient.newCall(req).execute().use { it.code == 204 }
        } catch (e: Exception) {
            false
        }
    }

    fun createClient(wifiNetwork: Network?): OkHttpClient {
        val cookieStore = mutableMapOf<String, MutableMap<String, Cookie>>()

        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .dns(CaptiveDns(wifiNetwork))
            .addInterceptor { chain ->
                val originalRequest = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "*/*")
                    .header("Origin", PORTAL_URL)
                    .build()

                var response = chain.proceed(originalRequest)
                var currentRequest = originalRequest
                var redirectCount = 0

                // Custom redirect loop: follow internal IBB redirects, but NEVER follow external URLs (like httpvshttps.com)
                while (response.isRedirect && redirectCount < 5) {
                    val location = response.header("Location") ?: break
                    val nextUrl = currentRequest.url.resolve(location) ?: break

                    // Stop if redirected to an external domain outside IBB
                    if (!nextUrl.host.contains("ibbwifi.istanbul", ignoreCase = true)) {
                        break
                    }

                    response.close()
                    currentRequest = currentRequest.newBuilder().url(nextUrl).build()
                    response = chain.proceed(currentRequest)
                    redirectCount++
                }

                response
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
            .build()
    }

    private fun getCsrfToken(html: String): String? {
        val matcher = Pattern.compile("name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"").matcher(html)
        return if (matcher.find()) matcher.group(1) else null
    }

    fun detectPortalUrl(client: OkHttpClient): String {
        val probeUrls = listOf(
            "http://192.168.1.1",
            "http://neverssl.com",
            CHECK_URL
        )
        for (url in probeUrls) {
            try {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { res ->
                    val loc = res.header("Location")
                    if (loc != null) {
                        val resolved = try { res.request.url.resolve(loc)?.toString() ?: loc } catch (e: Exception) { loc }
                        if (resolved.contains("ibbwifi") || resolved.contains("viracaptive") || resolved.contains("mac=")) {
                            return resolved
                        }
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
        onStatus("[1/5] Wi-Fi ağı kontrol ediliyor...")
        val wifiNetwork = getWifiNetwork(context)
        if (wifiNetwork == null) {
            return@withContext Result.failure(Exception("Telefonunuz bir Wi-Fi ağına bağlı görünmüyor. Lütfen önce ibbWiFi ağına bağlanın."))
        }

        if (isConnected(wifiNetwork)) {
            return@withContext Result.success("🎉 Zaten internete bağlısınız!")
        }

        if (phone.isBlank() || pass.isBlank()) {
            return@withContext Result.failure(Exception("Telefon veya şifre boş bırakılamaz."))
        }

        val client = createClient(wifiNetwork)

        onStatus("[2/5] Karşılama portalı aranıyor...")
        val landingUrl = detectPortalUrl(client)

        onStatus("[3/5] Açılış sayfası yükleniyor...")
        val landingReq = Request.Builder()
            .url(landingUrl)
            .build()

        val landingRes = try {
            client.newCall(landingReq).execute()
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Portala bağlanılamadı: ${e.message}"))
        }

        val landingHtml = landingRes.use { it.body?.string().orEmpty() }

        // Check for IBB session timeout / blocked state
        if (landingHtml.contains("Oturum Bulunamadı", ignoreCase = true) ||
            landingHtml.contains("session_not_found", ignoreCase = true) ||
            landingHtml.contains("uzun süre oturum açılmadan", ignoreCase = true)) {
            return@withContext Result.failure(Exception("⚠️ İBB Oturum Zaman Aşımı: Uzun süre giriş yapılmadığı için İBB bu cihazı kilitledi. Lütfen telefonunuzdan Wi-Fi'yi kapatıp 2-3 dakika bekleyin veya 'Ağı Unut' yapıp tekrar bağlanın."))
        }

        val token1 = getCsrfToken(landingHtml)
            ?: run {
                if (isConnected(wifiNetwork)) {
                    return@withContext Result.success("🎉 İnternet bağlantınız zaten aktif!")
                }
                return@withContext Result.failure(Exception("İlk güvenlik jetonu (CSRF) alınamadı. Lütfen Wi-Fi'yi kapatıp tekrar açın."))
            }

        onStatus("[4/5] Telefon numarası gönderiliyor...")
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

        kotlinx.coroutines.delay(600)

        onStatus("[5/5] Şifre doğrulanıyor...")
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

            kotlinx.coroutines.delay(1200)
            if (isConnected(wifiNetwork)) {
                onStatus("🎉 Giriş başarılı! İnternet aktif.")
                return@withContext Result.success("🎉 Giriş başarılı! İnternet aktif.")
            } else {
                onStatus("Giriş yapıldı. Birkaç saniye içinde internetiniz açılacaktır.")
                return@withContext Result.success("Giriş yapıldı. Birkaç saniye içinde internetiniz açılacaktır.")
            }
        } else {
            val errMsg = json.optString("message", "Giriş başarısız oldu. Lütfen şifrenizi kontrol edin.")
            return@withContext Result.failure(Exception(errMsg))
        }
    }
}
