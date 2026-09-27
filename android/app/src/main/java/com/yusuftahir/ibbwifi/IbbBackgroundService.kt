package com.yusuftahir.ibbwifi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

class IbbBackgroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val isLoggingIn = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        val notification = createNotification("İBB Wi-Fi dinleniyor...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1001, notification)
        }
        registerNetworkListener()
        startNightKeepAliveLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Trigger auto login check immediately when service starts/restarts
        attemptAutoLogin("Servis Başlatıldı")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val prefs = getSharedPreferences("ibbwifi_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("auto_login_enabled", false)) {
            val restartIntent = Intent(applicationContext, IbbBackgroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(restartIntent)
                } else {
                    startService(restartIntent)
                }
            } catch (ignored: Exception) {}
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun registerNetworkListener() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try { cm.bindProcessToNetwork(network) } catch (ignored: Exception) {}
                }
                attemptAutoLogin("Wi-Fi Bağlandı", network)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try { cm.bindProcessToNetwork(network) } catch (ignored: Exception) {}
                    }
                    attemptAutoLogin("Captive Portal Algılandı", network)
                }
            }
        }

        cm.registerNetworkCallback(request, networkCallback!!)
    }

    /**
     * Gece 00:00 - 08:00 Arası Canlı Tutucu & Oturum Kurtarıcı:
     * - Her 5 dakikada bir kontrol eder.
     * - Eğer İBB oturumu düşmüşse, telefonun saatlerce askıda kalıp karantinaya düşmesini engellemek için anında tekrar giriş yapar.
     * - Eğer internet açıksa ve gece saatlerindeyse (00:00 - 08:00), dağıtıcıya ufak bir sinyal göndererek bağlantının boşta kalmasını (idle timeout) önler.
     */
    private fun startNightKeepAliveLoop() {
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    delay(5 * 60 * 1000L) // 5 dakika

                    val prefs = getSharedPreferences("ibbwifi_prefs", Context.MODE_PRIVATE)
                    val isEnabled = prefs.getBoolean("auto_login_enabled", false)
                    if (!isEnabled) continue

                    val wifiNetwork = IbbLoginEngine.getWifiNetwork(applicationContext) ?: continue

                    val isOnline = IbbLoginEngine.isNetworkOnline(applicationContext, wifiNetwork)
                    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                    val isNightTime = (hour in 0..7) // 00:00 - 08:00 arası

                    if (!isOnline) {
                        // Gece oturum düşmüşse derhal yeniden bağlanarak karantinayı önle
                        attemptAutoLogin("Gece Canlı Tutucu", wifiNetwork)
                    } else if (isNightTime) {
                        // Gece canlılık sinyali gönder
                        IbbLoginEngine.sendKeepAlivePing(wifiNetwork)
                    }
                } catch (ignored: Exception) {}
            }
        }
    }

    private fun getSelectedCredentials(): Pair<String, String>? {
        val prefs = getSharedPreferences("ibbwifi_prefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("saved_accounts_json", null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                var fallback: Pair<String, String>? = null
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val p = obj.optString("phone", "").trim()
                    val pass = obj.optString("pass", "").trim()
                    if (p.isNotBlank() && pass.isNotBlank()) {
                        if (fallback == null) fallback = Pair(p, pass)
                        if (obj.optBoolean("isSelected", false)) {
                            return Pair(p, pass)
                        }
                    }
                }
                if (fallback != null) return fallback
            } catch (ignored: Exception) {}
        }
        val legacyPhone = prefs.getString("phone", "")?.trim().orEmpty()
        val legacyPass = prefs.getString("password", "")?.trim().orEmpty()
        if (legacyPhone.isNotBlank() && legacyPass.isNotBlank()) {
            return Pair(legacyPhone, legacyPass)
        }
        return null
    }

    private fun attemptAutoLogin(source: String = "Otomatik", targetNetwork: Network? = null) {
        val prefs = getSharedPreferences("ibbwifi_prefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("auto_login_enabled", false)
        if (!isEnabled) return

        val creds = getSelectedCredentials() ?: return
        val (phone, password) = creds

        // Concurrency guard: avoid launching concurrent login attempts
        if (!isLoggingIn.compareAndSet(false, true)) {
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                updateNotification("İBB Wi-Fi algılandı ($source)...")

                // Settle delay: give DHCP lease and routing 1.8 seconds to establish
                delay(1800L)

                val wifiNetwork = targetNetwork ?: IbbLoginEngine.getWifiNetwork(applicationContext) ?: run {
                    updateNotification("İBB Wi-Fi dinleniyor...")
                    return@launch
                }

                if (IbbLoginEngine.isNetworkOnline(applicationContext, wifiNetwork)) {
                    updateNotification("🟢 İBB Wi-Fi bağlı ve internet aktif!")
                    return@launch
                }

                updateNotification("İBB Wi-Fi algılandı, otomatik bağlanılıyor...")

                // Phase 1: Immediate retry loop (3 attempts with 2s delay)
                var lastErrorMessage = ""
                for (attempt in 1..3) {
                    val result = IbbLoginEngine.login(applicationContext, phone, password) { status ->
                        updateNotification(status)
                    }

                    if (result.isSuccess) {
                        updateNotification("🎉 İBB Wi-Fi Girişi Başarılı! İnternet aktif.")
                        return@launch
                    }

                    lastErrorMessage = result.exceptionOrNull()?.message.orEmpty()
                    if (attempt < 3) {
                        delay(2000L)
                    }
                }

                // Phase 2: If failure is due to previous session lock (e.g. after 'Ağı Unut'),
                // run an Extended Cooldown Loop: İBB RADIUS drops dead MAC sessions after 2-3 minutes.
                // The service retries every 25 seconds for up to ~2.5 minutes!
                if (lastErrorMessage.contains("Önceki Oturum", ignoreCase = true) ||
                    lastErrorMessage.contains("Kapanmadı", ignoreCase = true) ||
                    lastErrorMessage.contains("Oturum açma", ignoreCase = true) ||
                    lastErrorMessage.contains("hata meydana", ignoreCase = true) ||
                    lastErrorMessage.contains("açma sırasında", ignoreCase = true) ||
                    lastErrorMessage.contains("bekleyin", ignoreCase = true)) {

                    for (cooldownRound in 1..7) {
                        val waitSec = cooldownRound * 20
                        updateNotification("⏳ İBB eski oturumu bırakıyor (~${waitSec} sn)... Bekleniyor.")
                        delay(20000L)

                        val currentWifi = IbbLoginEngine.getWifiNetwork(applicationContext) ?: break
                        if (IbbLoginEngine.isNetworkOnline(applicationContext, currentWifi)) {
                            updateNotification("🟢 İBB Wi-Fi bağlı ve internet aktif!")
                            return@launch
                        }

                        updateNotification("İBB Wi-Fi tekrar deneniyor ($cooldownRound/7)...")
                        val retryRes = IbbLoginEngine.login(applicationContext, phone, password) { status ->
                            updateNotification(status)
                        }

                        if (retryRes.isSuccess) {
                            updateNotification("🎉 İBB Wi-Fi Girişi Başarılı! İnternet aktif.")
                            return@launch
                        }
                    }
                }

                updateNotification("Otomatik giriş tamamlanamadı. Wi-Fi sinyali bekleniyor.")
            } catch (e: Exception) {
                updateNotification("İBB Wi-Fi dinleniyor...")
            } finally {
                isLoggingIn.set(false)
            }
        }
    }

    private fun createNotification(text: String): Notification {
        val channelId = "ibbwifi_channel_v2"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "İBB Wi-Fi Otomatik Giriş",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("İBB Wi-Fi Otomatik Giriş")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1001, createNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            try { cm.unregisterNetworkCallback(it) } catch (ignored: Exception) {}
        }
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
