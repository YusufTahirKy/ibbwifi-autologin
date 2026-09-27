package com.yusuftahir.ibbwifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
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
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object IbbLoginEngine {

    private const val PORTAL_URL = "https://viracaptive.ibbwifi.istanbul"
    private const val CHECK_URL = "http://connectivitycheck.gstatic.com/generate_204"

    enum class PortalStatus {
        ALREADY_CONNECTED,
        READY_TO_LOGIN,
        QUARANTINED,
        NO_WIFI,
        UNKNOWN
    }

    class CaptiveDns(private val wifiNetwork: Network?) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            // Immediate zero-latency mapping for IBB internal infrastructure
            if (hostname.equals("viracaptive.ibbwifi.istanbul", ignoreCase = true)) {
                return listOf(InetAddress.getByAddress(hostname, byteArrayOf(10, 18, 53, 200.toByte())))
            }
            if (hostname.equals("captive.ibbwifi.istanbul", ignoreCase = true)) {
                return listOf(InetAddress.getByAddress(hostname, byteArrayOf(10, 18, 53, 102.toByte())))
            }

            // Numeric IPv4 address bypass
            if (hostname.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))) {
                try {
                    return listOf(InetAddress.getByName(hostname))
                } catch (ignored: Exception) {}
            }

            // For connectivitycheck domains: try Wi-Fi DNS first (works if online)
            // If DNS is blocked before login, fallback to 1.1.1.1 so router intercepts port 80 instantly
            if (hostname.contains("connectivitycheck.gstatic.com", ignoreCase = true) ||
                hostname.contains("clients3.google.com", ignoreCase = true)) {
                if (wifiNetwork != null) {
                    try {
                        val addrs = wifiNetwork.getAllByName(hostname).toList()
                        if (addrs.isNotEmpty()) return addrs
                    } catch (ignored: Exception) {}
                }
                return listOf(InetAddress.getByAddress(hostname, byteArrayOf(1, 1, 1, 1)))
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

    fun getGatewayIp(context: Context): String {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val dhcp = wm?.dhcpInfo
            if (dhcp != null && dhcp.gateway != 0) {
                val ip = dhcp.gateway
                return String.format("%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
            }
        } catch (ignored: Exception) {}
        return "10.32.0.1"
    }

    fun isNetworkOnline(context: Context, wifiNetwork: Network?): Boolean {
        if (wifiNetwork == null) return false
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(wifiNetwork)
            if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
                return true
            }
        } catch (ignored: Exception) {}
        return isConnected(wifiNetwork)
    }

    fun isConnected(wifiNetwork: Network?): Boolean {
        if (wifiNetwork == null) return false
        return try {
            val checkClient = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .followRedirects(false)
                .dns(CaptiveDns(wifiNetwork))
                .build()
            val req = Request.Builder().url(CHECK_URL).build()
            checkClient.newCall(req).execute().use { it.code == 204 }
        } catch (e: Exception) {
            false
        }
    }

    fun detectPortalUrl(client: OkHttpClient): String = "$PORTAL_URL/"

    fun detectPortalUrl(context: Context, client: OkHttpClient, wifiNetwork: Network?): String {
        val gwIp = getGatewayIp(context)
        val probeUrls = mutableListOf<String>()
        if (gwIp != "0.0.0.0" && gwIp.isNotBlank()) {
            probeUrls.add("http://$gwIp/")
        }
        probeUrls.add("http://192.168.1.1/")
        probeUrls.add("http://1.1.1.1/")
        probeUrls.add(CHECK_URL)

        val probeClient = client.newBuilder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        // 1. Probe router port 80: router intercepts in 12ms and returns the Location URL containing mac & session!
        for (url in probeUrls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()
                probeClient.newCall(req).execute().use { res ->
                    val loc = res.header("Location")
                    if (loc != null) {
                        val resolved = try { res.request.url.resolve(loc)?.toString() ?: loc } catch (e: Exception) { loc }
                        if (resolved.contains("ibbwifi", ignoreCase = true) ||
                            resolved.contains("viracaptive", ignoreCase = true) ||
                            resolved.contains("mac=", ignoreCase = true)) {
                            return resolved
                        }
                    }
                }
            } catch (ignored: Exception) {}
        }

        // 2. Direct portal check as fallback
        try {
            val req = Request.Builder().url(PORTAL_URL).build()
            probeClient.newCall(req).execute().use { res ->
                val loc = res.header("Location")
                if (loc != null) {
                    val resolved = try { res.request.url.resolve(loc)?.toString() ?: loc } catch (e: Exception) { loc }
                    if (resolved.contains("ibbwifi", ignoreCase = true) || resolved.contains("viracaptive", ignoreCase = true)) {
                        return resolved
                    }
                }
            }
        } catch (ignored: Exception) {}

        return "$PORTAL_URL/"
    }

    suspend fun quickCheckStatus(context: Context, wifiNetwork: Network?): PortalStatus = withContext(Dispatchers.IO) {
        if (wifiNetwork == null) return@withContext PortalStatus.NO_WIFI

        if (isNetworkOnline(context, wifiNetwork)) {
            return@withContext PortalStatus.ALREADY_CONNECTED
        }

        try {
            val client = createClient(wifiNetwork)
            val landingUrl = detectPortalUrl(context, client, wifiNetwork)
            val req = Request.Builder().url(landingUrl).build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (body.contains("Oturum Bulunamadı", ignoreCase = true) ||
                    body.contains("session_not_found", ignoreCase = true) ||
                    body.contains("uzun süre oturum açılmadan", ignoreCase = true) ||
                    body.contains("dakika bekleyin", ignoreCase = true) ||
                    body.contains("5-10", ignoreCase = true) ||
                    res.code == 429) {
                    return@withContext PortalStatus.QUARANTINED
                }
                return@withContext PortalStatus.READY_TO_LOGIN
            }
        } catch (e: Exception) {
            PortalStatus.UNKNOWN
        }
    }

    fun sendKeepAlivePing(wifiNetwork: Network?) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .followRedirects(false)
                .dns(CaptiveDns(wifiNetwork))
                .build()
            val req = Request.Builder().url(CHECK_URL).build()
            client.newCall(req).execute().close()
        } catch (ignored: Exception) {}
    }

    fun createClient(wifiNetwork: Network?): OkHttpClient {
        val cookieStore = mutableMapOf<String, MutableMap<String, Cookie>>()

        return OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .dns(CaptiveDns(wifiNetwork))
            .addInterceptor { chain ->
                val originalRequest = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Origin", PORTAL_URL)
                    .build()

                var response = chain.proceed(originalRequest)
                var currentRequest = originalRequest
                var redirectCount = 0

                // Follow redirects to internal IBB portal or local gateway IPs, but block external ads (httpvshttps.com)
                while (response.isRedirect && redirectCount < 5) {
                    val location = response.header("Location") ?: break
                    val nextUrl = currentRequest.url.resolve(location) ?: break

                    val host = nextUrl.host
                    val isIbb = host.contains("ibbwifi.istanbul", ignoreCase = true)
                    val isLocalIp = host.matches(Regex("^(10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[0-1])\\.|1\\.1\\.1\\.1).*"))

                    if (!isIbb && !isLocalIp) {
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
        val patterns = listOf(
            "name=[\"']__RequestVerificationToken[\"'][^>]*value=[\"']([^\"']+)[\"']",
            "value=[\"']([^\"']+)[\"'][^>]*name=[\"']__RequestVerificationToken[\"']",
            "__RequestVerificationToken[^>]*value=[\"']([^\"']+)[\"']",
            "data-csrf=[\"']([^\"']+)[\"']",
            "<meta[^>]*name=[\"'](?:csrf-token|_token)[\"'][^>]*content=[\"']([^\"']+)[\"']"
        )
        for (patternStr in patterns) {
            val matcher = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE).matcher(html)
            if (matcher.find()) {
                val token = matcher.group(1)
                if (!token.isNullOrBlank()) return token
            }
        }
        return null
    }

    private fun kickGatewayAndValidate(
        context: Context,
        wifiNetwork: Network?,
        client: OkHttpClient,
        wisprUrl: String?,
        baseUrl: HttpUrl
    ) {
        // 1. Follow wisprUrl if returned by server
        if (!wisprUrl.isNullOrBlank()) {
            try {
                val fullWisprUrl = try { baseUrl.resolve(wisprUrl)?.toString() ?: wisprUrl } catch (e: Exception) { wisprUrl }
                val wisprReq = Request.Builder()
                    .url(fullWisprUrl)
                    .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()
                client.newCall(wisprReq).execute().close()
            } catch (ignored: Exception) {}
        }

        // 2. Gateway port 80 triggers (releases the router captive firewall rule)
        val gwIp = getGatewayIp(context)
        val hosts = listOf("192.168.1.1", gwIp).distinct()

        for (host in hosts) {
            try {
                val socket = Socket()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && wifiNetwork != null) {
                    wifiNetwork.bindSocket(socket)
                }
                socket.connect(InetSocketAddress(host, 80), 1000)
                val out = socket.getOutputStream()
                val httpReq = "GET / HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36\r\nConnection: close\r\n\r\n"
                out.write(httpReq.toByteArray())
                out.flush()
                socket.close()
            } catch (ignored: Exception) {}
        }

        // 3. Request connectivitycheck over OkHttpClient bound to Wi-Fi
        try {
            val triggerReq = Request.Builder()
                .url(CHECK_URL)
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
                .build()
            client.newCall(triggerReq).execute().close()
        } catch (ignored: Exception) {}

        // 4. Report connectivity to Android OS NetworkMonitor so exclamation mark disappears immediately
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && wifiNetwork != null) {
                cm.reportNetworkConnectivity(wifiNetwork, true)
            }
        } catch (ignored: Exception) {}
    }

    suspend fun login(
        context: Context,
        phone: String,
        pass: String,
        onStatus: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        onStatus("[1/4] Wi-Fi ağı kontrol ediliyor...")
        val wifiNetwork = getWifiNetwork(context)
        if (wifiNetwork == null) {
            return@withContext Result.failure(Exception("Telefonunuz bir Wi-Fi ağına bağlı görünmüyor. Lütfen önce ibbWiFi ağına bağlanın."))
        }

        if (isNetworkOnline(context, wifiNetwork)) {
            return@withContext Result.success("🎉 Zaten internete bağlısınız!")
        }

        if (phone.isBlank() || pass.isBlank()) {
            return@withContext Result.failure(Exception("Telefon veya şifre boş bırakılamaz."))
        }

        // Ensure DHCP lease is assigned
        var gwIp = getGatewayIp(context)
        if (gwIp == "0.0.0.0") {
            kotlinx.coroutines.delay(1000)
            gwIp = getGatewayIp(context)
        }

        val client = createClient(wifiNetwork)

        onStatus("[2/4] İBB portalına bağlanılıyor...")
        val landingUrl = detectPortalUrl(context, client, wifiNetwork)

        val landingReq = Request.Builder()
            .url(landingUrl)
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        val landingRes = try {
            client.newCall(landingReq).execute()
        } catch (e: Exception) {
            return@withContext Result.failure(Exception("Portala bağlanılamadı: ${e.message}"))
        }

        val landingHtml = landingRes.use { it.body?.string().orEmpty() }

        // Check for IBB session timeout / blocked state / quarantine
        if (landingHtml.contains("Oturum Bulunamadı", ignoreCase = true) ||
            landingHtml.contains("session_not_found", ignoreCase = true) ||
            landingHtml.contains("uzun süre oturum açılmadan", ignoreCase = true) ||
            landingHtml.contains("dakika bekleyin", ignoreCase = true) ||
            landingHtml.contains("5-10", ignoreCase = true)) {
            return@withContext Result.failure(Exception("⚠️ İBB Oturum Zaman Aşımı (Kilit): Uzun süre giriş yapılmadığı için İBB bu cihazı kilitledi. Lütfen '⚙️ MAC Değiştir' butonuna basarak MAC tipini değiştirin veya 5-10 dk bekleyin."))
        }

        val token1 = getCsrfToken(landingHtml)
            ?: run {
                if (isNetworkOnline(context, wifiNetwork)) {
                    return@withContext Result.success("🎉 İnternet bağlantınız zaten aktif!")
                }
                val title = Regex("<title>([^<]+)</title>", RegexOption.IGNORE_CASE).find(landingHtml)?.groupValues?.get(1)?.trim()
                val bodySnippet = landingHtml.replace(Regex("<[^>]+>"), " ").replace("\\s+".toRegex(), " ").take(100).trim()
                val detail = if (!title.isNullOrBlank()) " ($title)" else if (bodySnippet.isNotBlank()) " ($bodySnippet)" else ""
                return@withContext Result.failure(Exception("İlk güvenlik jetonu (CSRF) alınamadı$detail. Lütfen Wi-Fi'yi kapatıp açın veya tekrar deneyin."))
            }

        onStatus("[3/4] Bilgiler doğrulanıyor...")
        val cleanPhone = phone.trim().removePrefix("+90").removePrefix("90").removePrefix("0")
        val phoneJson = JSONObject().apply {
            put("PhoneNumber", cleanPhone)
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
            return@withContext Result.failure(Exception("İstek limiti aşıldı (429). Lütfen '⚙️ MAC Değiştir' butonunu kullanarak MAC tipini değiştirin veya 5-10 dakika bekleyin."))
        }

        val checkHtml = checkRes.use { it.body?.string().orEmpty() }
        val checkJson = try { JSONObject(checkHtml) } catch (ignored: Exception) { null }
        if (checkJson != null && checkJson.has("message") && !checkJson.optBoolean("success", true)) {
            val errMsg = checkJson.optString("message")
            if (errMsg.isNotBlank()) {
                return@withContext Result.failure(Exception("Numara hatası: $errMsg"))
            }
        }

        // Token 2 defaults to token1 if no new CSRF input is in JSON/HTML response
        val token2 = getCsrfToken(checkHtml) ?: token1

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
        if (loginRes.isSuccessful && (wisprUrl.isNotBlank() || json.optBoolean("success", false) || loginBody.contains("true"))) {
            onStatus("[4/4] Ağ geçidi ve internet etkinleştiriliyor...")

            // Kick the gateway and flush captive portal firewall rule
            kickGatewayAndValidate(context, wifiNetwork, client, wisprUrl, loginReq.url)

            // Verify internet connectivity
            var verified = false
            for (i in 1..6) {
                if (isNetworkOnline(context, wifiNetwork)) {
                    verified = true
                    break
                }
                kotlinx.coroutines.delay(400)
            }

            // Signal OS once more if verified to remove exclamation mark (!)
            if (verified && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    cm.reportNetworkConnectivity(wifiNetwork, true)
                } catch (ignored: Exception) {}
            }

            onStatus("🎉 Giriş başarılı! İnternet aktif.")
            return@withContext Result.success("🎉 Giriş başarılı! İnternet aktif ve doğrulandı.")
        } else {
            val errMsg = json.optString("message", "Giriş başarısız oldu. Lütfen şifrenizi kontrol edin.")
            return@withContext Result.failure(Exception(errMsg))
        }
    }
}
