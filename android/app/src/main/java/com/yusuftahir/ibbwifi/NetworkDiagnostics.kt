package com.yusuftahir.ibbwifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object NetworkDiagnostics {

    suspend fun runFullDiagnostic(
        context: Context,
        testPhone: String?,
        testPass: String?
    ): String = withContext(Dispatchers.IO) {
        val report = StringBuilder()
        fun log(s: String) { report.append(s).append("\n") }

        log("=== İBB Wİ-Fİ AĞ & PORT ANALİZ RAPORU ===")
        log("Tarih: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        log("Cihaz: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
        log("----------------------------------------")

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

        // [1] Network Interfaces
        log("\n[1] AĞ BAĞLANTILARI DURUMU:")
        val allNets = cm.allNetworks
        val wifiNet = allNets.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val cellNet = allNets.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        }

        log("• Wi-Fi Durumu: ${if (wifiNet != null) "BAĞLI (wlan0 mevcut)" else "BAĞLI DEĞİL ❌"}")
        log("• Mobil Veri (4.5G): ${if (cellNet != null) "AÇIK ⚠️ (Kapatılması gerekir!)" else "KAPALI ✓ (İdeal)"}")

        if (wifiNet != null) {
            val caps = cm.getNetworkCapabilities(wifiNet)
            log("• İnternet Doğrulandı mı?: ${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true}")
            log("• Captive Portal İşareti: ${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true}")

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cm.bindProcessToNetwork(wifiNet)
                    log("• Süreç Wi-Fi Ağına Bağlandı: BAŞARILI")
                }
            } catch (e: Exception) {
                log("• bindProcessToNetwork: HATA (${e.message})")
            }
        }

        // [2] DHCP & Gateway
        log("\n[2] DHCP VE AĞ GEÇİDİ (GATEWAY):")
        var gatewayIp = "192.168.1.1"
        try {
            val dhcp = wm?.dhcpInfo
            if (dhcp != null) {
                fun ipStr(ip: Int) = String.format("%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
                val devIp = ipStr(dhcp.ipAddress)
                val gw = ipStr(dhcp.gateway)
                val dns1 = ipStr(dhcp.dns1)
                val dns2 = ipStr(dhcp.dns2)
                val netmask = ipStr(dhcp.netmask)
                if (dhcp.gateway != 0) gatewayIp = gw

                log("• Cihaz IP: $devIp")
                log("• Ağ Geçidi (Router / Gateway): $gw")
                log("• DNS 1: $dns1")
                log("• DNS 2: $dns2")
                log("• Netmask: $netmask")
            } else {
                log("• DHCP bilgisi okunamadı.")
            }
        } catch (e: Exception) {
            log("• DHCP Hatası: ${e.message}")
        }

        // [3] DNS Resolution
        log("\n[3] DNS ÇÖZÜMLEME TESTLERİ:")
        val testDomains = listOf(
            "viracaptive.ibbwifi.istanbul",
            "captive.ibbwifi.istanbul",
            "connectivitycheck.gstatic.com",
            "neverssl.com"
        )
        for (domain in testDomains) {
            try {
                val addrs = InetAddress.getAllByName(domain)
                log("• $domain -> ${addrs.joinToString { it.hostAddress }} [BAŞARILI]")
            } catch (e: Exception) {
                log("• $domain -> ÇÖZÜLEMEDİ (${e.javaClass.simpleName}: ${e.message})")
            }
        }

        // [4] TCP Socket Connection
        log("\n[4] TCP PORT & SOCKET TESTLERİ (4 sn timeout):")
        val socketTargets = listOf(
            Pair(gatewayIp, 80),
            Pair("192.168.1.1", 80),
            Pair("10.18.53.200", 443),
            Pair("10.18.53.102", 443)
        )
        for ((host, port) in socketTargets) {
            try {
                val start = System.currentTimeMillis()
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 4000)
                val elapsed = System.currentTimeMillis() - start
                socket.close()
                log("• $host:$port -> BAĞLANDI ($elapsed ms) ✓")
            } catch (e: Exception) {
                log("• $host:$port -> BAŞARISIZ (${e.javaClass.simpleName}: ${e.message}) ❌")
            }
        }

        // [5] HTTP Port 80 Interception
        log("\n[5] PORT 80 YÖNLENDİRME TESTLERİ:")
        val rawClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val probeUrls = listOf(
            "http://$gatewayIp",
            "http://192.168.1.1",
            "http://connectivitycheck.gstatic.com/generate_204",
            "http://neverssl.com"
        )
        for (url in probeUrls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()
                rawClient.newCall(req).execute().use { res ->
                    val loc = res.header("Location")
                    log("• $url -> HTTP ${res.code} (Hedef: ${loc ?: "YOK"})")
                }
            } catch (e: Exception) {
                log("• $url -> HATA (${e.javaClass.simpleName}: ${e.message})")
            }
        }

        // [6] HTTPS Portal Inspection
        log("\n[6] İBB PORTAL SAYFASI İNCELEMESİ:")
        val captiveClient = IbbLoginEngine.createClient(wifiNet)
        try {
            val detectedUrl = IbbLoginEngine.detectPortalUrl(context, captiveClient, wifiNet)
            log("• Tespit Edilen Portal URL: $detectedUrl")

            val req = Request.Builder().url(detectedUrl).build()
            captiveClient.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                log("• Yanıt Kodu: HTTP ${res.code}")
                log("• Sayfa Boyutu: ${body.length} bayt")
                val hasCsrf = body.contains("__RequestVerificationToken")
                val isBlocked = body.contains("Oturum Bulunamadı") || body.contains("session_not_found") || body.contains("uzun süre")
                val isHttpVsHttps = body.contains("httpvshttps") || res.header("Location")?.contains("httpvshttps") == true

                log("• CSRF Jetonu: ${if (hasCsrf) "Mevcut ✓" else "YOK ❌"}")
                log("• Oturum Kilitli mi?: ${if (isBlocked) "EVET ⚠️ (Bekleme gerekli)" else "HAYIR ✓"}")
                log("• httpvshttps.com Yönlendirmesi: $isHttpVsHttps")

                val titleMatch = Regex("<title>([^<]+)</title>", RegexOption.IGNORE_CASE).find(body)
                log("• Sayfa Başlığı: ${titleMatch?.groupValues?.get(1)?.trim() ?: "Bulunamadı"}")
            }
        } catch (e: Exception) {
            log("• Portal Sayfası Hatası: ${e.javaClass.simpleName}: ${e.message}")
        }

        // [7] Canlı Giriş Adımı (Bilgiler seçilmişse)
        if (!testPhone.isNullOrBlank() && !testPass.isNullOrBlank()) {
            log("\n[7] CANLI GİRİŞ MOTORU TESTİ ($testPhone):")
            try {
                val loginRes = IbbLoginEngine.login(context, testPhone, testPass) { stepMsg ->
                    log("  > $stepMsg")
                }
                loginRes.onSuccess {
                    log("• Sonuç: BAŞARILI! ($it) 🎉")
                }.onFailure {
                    log("• Sonuç: BAŞARISIZ (${it.message}) ❌")
                }
            } catch (e: Exception) {
                log("• Giriş Hatası: ${e.javaClass.simpleName}: ${e.message}")
            }
        } else {
            log("\n[7] CANLI GİRİŞ MOTORU: Telefon/Şifre girilmediği için test edilmedi.")
        }

        log("\n================ RAPOR SONU ================")
        report.toString()
    }
}
