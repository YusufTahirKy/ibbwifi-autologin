package com.yusuftahir.ibbwifi

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.yusuftahir.ibbwifi.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedAccount(
    val id: String = UUID.randomUUID().toString(),
    val phone: String,
    val pass: String,
    var isSelected: Boolean = false
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val accounts = mutableListOf<SavedAccount>()

    class WebAppInterface(private val activity: MainActivity) {
        @JavascriptInterface
        fun onLoginStatus(status: String, message: String) {
            activity.runOnUiThread {
                when (status) {
                    "PROGRESS" -> activity.setStatusText(message, StatusType.PROGRESS)
                    "SUCCESS" -> {
                        activity.setStatusText(message, StatusType.SUCCESS)
                        activity.binding.btnConnectNow.isEnabled = true
                        activity.binding.btnQuickApiConnect.isEnabled = true
                        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                    }
                    "ERROR" -> {
                        activity.setStatusText(message, StatusType.ERROR)
                        activity.binding.btnConnectNow.isEnabled = true
                        activity.binding.btnQuickApiConnect.isEnabled = true
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Enforce pure dark mode
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val prefs = getSharedPreferences("ibbwifi_prefs", Context.MODE_PRIVATE)

        // Load saved accounts
        loadAccountsFromPrefs()

        // Set initial toggle state
        binding.switchAutoLogin.isChecked = prefs.getBoolean("auto_login_enabled", false)

        // Save Button: always creates a new record so same phone with different passwords can be kept
        binding.btnSave.setOnClickListener {
            val phone = binding.etPhone.text?.toString()?.trim().orEmpty()
            val pass = binding.etPassword.text?.toString()?.trim().orEmpty()

            if (phone.isBlank() || pass.isBlank()) {
                setStatusText("Lütfen telefon ve şifre girin.", StatusType.ERROR)
                Toast.makeText(this, "Lütfen telefon ve şifre girin.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            saveNewAccount(phone, pass)
            Toast.makeText(this, "Bilgiler yeni kayıt olarak eklendi.", Toast.LENGTH_SHORT).show()
        }

        // Auto-login Switch (AÇ / KAPA)
        binding.switchAutoLogin.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_login_enabled", isChecked).apply()

            val serviceIntent = Intent(this, IbbBackgroundService::class.java)
            if (isChecked) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    setStatusText("Otomatik giriş AÇIK (Ağ bekleniyor)", StatusType.INFO)
                } catch (e: Exception) {
                    setStatusText("Servis başlatılamadı: ${e.message}", StatusType.ERROR)
                }
            } else {
                stopService(serviceIntent)
                setStatusText("Otomatik giriş KAPALI", StatusType.NEUTRAL)
            }
        }

        // Close WebView button
        binding.btnCloseWebView.setOnClickListener {
            binding.cardWebView.visibility = View.GONE
        }

        // Reload WebView button
        binding.btnReloadWeb.setOnClickListener {
            val selected = accounts.firstOrNull { it.isSelected }
            val typedPhone = binding.etPhone.text?.toString()?.trim().orEmpty()
            val typedPass = binding.etPassword.text?.toString()?.trim().orEmpty()
            val phone = if (typedPhone.isNotBlank()) typedPhone else selected?.phone.orEmpty()
            val pass = if (typedPass.isNotBlank()) typedPass else selected?.pass.orEmpty()
            if (phone.isNotBlank() && pass.isNotBlank()) {
                startDesktopWebViewLogin(phone, pass)
            } else {
                binding.webViewPortal.reload()
            }
        }

        // Open in Browser (192.168.1.1)
        binding.btnOpenBrowser.setOnClickListener {
            val selected = accounts.firstOrNull { it.isSelected }
            val typedPhone = binding.etPhone.text?.toString()?.trim().orEmpty()
            val typedPass = binding.etPassword.text?.toString()?.trim().orEmpty()
            val phone = if (typedPhone.isNotBlank()) typedPhone else selected?.phone.orEmpty()
            val pass = if (typedPass.isNotBlank()) typedPass else selected?.pass.orEmpty()

            if (pass.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("IBB Pass", pass)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Şifreniz panoya kopyalandı! Chrome'da sağ üstteki 3 noktadan 'Masaüstü sitesi'ni seçip yapıştırabilirsiniz.", Toast.LENGTH_LONG).show()
            }

            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://192.168.1.1"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Tarayıcı açılamadı: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // Connect Button (Masaüstü Modu)
        binding.btnConnectNow.setOnClickListener {
            val typedPhone = binding.etPhone.text?.toString()?.trim().orEmpty()
            val typedPass = binding.etPassword.text?.toString()?.trim().orEmpty()

            val selected = accounts.firstOrNull { it.isSelected }
            val phone = if (typedPhone.isNotBlank()) typedPhone else selected?.phone.orEmpty()
            val pass = if (typedPass.isNotBlank()) typedPass else selected?.pass.orEmpty()

            if (phone.isBlank() || pass.isBlank()) {
                setStatusText("Hata: Lütfen telefon ve şifre girin veya alttan bir hesap seçin.", StatusType.ERROR)
                Toast.makeText(this, "Lütfen önce bir hesap seçin veya bilgileri girin.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            checkMobileDataWarning()
            startDesktopWebViewLogin(phone, pass)
        }

        // Quick Direct API Connect Button
        binding.btnQuickApiConnect.setOnClickListener {
            val typedPhone = binding.etPhone.text?.toString()?.trim().orEmpty()
            val typedPass = binding.etPassword.text?.toString()?.trim().orEmpty()

            val selected = accounts.firstOrNull { it.isSelected }
            val phone = if (typedPhone.isNotBlank()) typedPhone else selected?.phone.orEmpty()
            val pass = if (typedPass.isNotBlank()) typedPass else selected?.pass.orEmpty()

            if (phone.isBlank() || pass.isBlank()) {
                setStatusText("Hata: Lütfen telefon ve şifre girin veya alttan bir hesap seçin.", StatusType.ERROR)
                Toast.makeText(this, "Lütfen önce bir hesap seçin veya bilgileri girin.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            checkMobileDataWarning()

            lifecycleScope.launch {
                binding.btnQuickApiConnect.isEnabled = false
                binding.btnConnectNow.isEnabled = false
                setStatusText("Masaüstü kimliğiyle doğrudan portala bağlanılıyor...", StatusType.PROGRESS)

                val result = IbbLoginEngine.login(this@MainActivity, phone, pass) { msg ->
                    runOnUiThread { setStatusText(msg, StatusType.PROGRESS) }
                }

                binding.btnQuickApiConnect.isEnabled = true
                binding.btnConnectNow.isEnabled = true

                result.onSuccess { msg ->
                    setStatusText(msg, StatusType.SUCCESS)
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    setStatusText("Hata: ${err.message}", StatusType.ERROR)
                    Toast.makeText(this@MainActivity, "Giriş başarısız: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkMobileDataWarning()
    }

    private fun checkMobileDataWarning() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val isCellularActive = cm.allNetworks.any { network ->
                val caps = cm.getNetworkCapabilities(network)
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            }
            binding.tvMobileDataWarning.visibility = if (isCellularActive) View.VISIBLE else View.GONE
        } catch (ignored: Exception) {}
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun startDesktopWebViewLogin(phone: String, pass: String) {
        val wifiNetwork = IbbLoginEngine.getWifiNetwork(this)
        if (wifiNetwork == null) {
            setStatusText("Telefonunuz ibbWiFi ağına bağlı görünmüyor. Lütfen önce Wi-Fi'yi açıp ibbWiFi ağına bağlanın.", StatusType.ERROR)
            Toast.makeText(this, "Lütfen önce ibbWiFi ağına bağlanın.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.cardWebView.visibility = View.VISIBLE
        binding.pbWebLoading.visibility = View.VISIBLE
        binding.btnConnectNow.isEnabled = false
        binding.btnQuickApiConnect.isEnabled = false
        setStatusText("Masaüstü portal aranıyor...", StatusType.PROGRESS)

        val webView = binding.webViewPortal
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.allowContentAccess = true
        settings.allowFileAccess = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        // Standard Linux Desktop Chrome User-Agent
        settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.removeJavascriptInterface("AndroidApp")
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidApp")

        val client = IbbLoginEngine.createClient(wifiNetwork)

        var hasPageError = false

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    binding.pbWebLoading.visibility = View.VISIBLE
                } else {
                    binding.pbWebLoading.visibility = View.GONE
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                // Crucial for captive portals: proceed through captive portal SSL cert warnings
                handler?.proceed()
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url ?: return null
                val host = url.host.orEmpty()

                // Intercept ibbwifi.istanbul domain if method is GET to guarantee zero-latency resolution via CaptiveDns
                if (request.method.equals("GET", ignoreCase = true) &&
                    (host.contains("ibbwifi.istanbul", ignoreCase = true) || host == "10.18.53.200")) {
                    try {
                        val okReq = Request.Builder()
                            .url(url.toString())
                            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .header("Accept", "*/*")
                            .build()
                        val res = client.newCall(okReq).execute()
                        val ct = res.header("Content-Type") ?: "text/html; charset=utf-8"
                        val mime = ct.substringBefore(";").trim()
                        val enc = if (ct.contains("charset=")) ct.substringAfter("charset=").trim() else "utf-8"
                        val headers = mutableMapOf<String, String>()
                        for (i in 0 until res.headers.size) {
                            headers[res.headers.name(i)] = res.headers.value(i)
                        }
                        return WebResourceResponse(mime, enc, res.code, res.message.ifBlank { "OK" }, headers, res.body?.byteStream())
                    } catch (ignored: Exception) {}
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                hasPageError = false
                binding.pbWebLoading.visibility = View.VISIBLE
                val display = url?.take(40).orEmpty()
                binding.tvWebTitle.text = "🌐 $display"
                setStatusText("Masaüstü portala bağlanılıyor: $display...", StatusType.PROGRESS)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    hasPageError = true
                    val errDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.description?.toString().orEmpty() else "Ağ Hatası"
                    val failedUrl = request.url?.toString().orEmpty()
                    setStatusText("Tarayıcı uyarısı: $errDesc ($failedUrl)", StatusType.ERROR)
                    binding.btnConnectNow.isEnabled = true
                    binding.btnQuickApiConnect.isEnabled = true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val v = view ?: return
                binding.pbWebLoading.visibility = View.GONE
                val currentUrl = url.orEmpty()
                binding.btnConnectNow.isEnabled = true
                binding.btnQuickApiConnect.isEnabled = true

                if (hasPageError) return

                if (currentUrl.contains("generate_204") || currentUrl.contains("google.com/search") || currentUrl.contains("gstatic.com")) {
                    setStatusText("🎉 Giriş başarılı! İnternet aktif.", StatusType.SUCCESS)
                    Toast.makeText(this@MainActivity, "🎉 Giriş başarılı! İnternet aktif.", Toast.LENGTH_SHORT).show()
                    return
                }

                // Inject Desktop Viewport meta tag (1280px) and auto-pilot JavaScript
                val cleanPhone = phone.replace("'", "\\'").trim()
                val cleanPass = pass.replace("'", "\\'").trim()

                val js = """
                    (function() {
                        // Force Desktop Viewport (1280px width)
                        try {
                            var meta = document.querySelector('meta[name="viewport"]');
                            if (meta) {
                                meta.setAttribute('content', 'width=1280, initial-scale=0.5, user-scalable=yes');
                            } else {
                                var m = document.createElement('meta');
                                m.name = 'viewport';
                                m.content = 'width=1280, initial-scale=0.5, user-scalable=yes';
                                document.head.appendChild(m);
                            }
                            if (navigator.userAgentData) {
                                Object.defineProperty(navigator, 'userAgentData', {
                                    get: function() { return { brands: [{brand: 'Google Chrome', version: '120'}], mobile: false, platform: 'Linux' }; }
                                });
                            }
                        } catch(e) {}

                        if (window.__ibbAutoPilotActive) return;
                        window.__ibbAutoPilotActive = true;

                        var phoneVal = '$cleanPhone';
                        var passVal = '$cleanPass';
                        var attempts = 0;

                        var poll = setInterval(function() {
                            attempts++;
                            if (attempts > 50) {
                                clearInterval(poll);
                                window.__ibbAutoPilotActive = false;
                                return;
                            }

                            var body = document.body ? document.body.innerText : '';
                            if (body.indexOf('Oturum Bulunamadı') !== -1 || body.indexOf('uzun süre oturum') !== -1) {
                                clearInterval(poll);
                                if (window.AndroidApp) {
                                    window.AndroidApp.onLoginStatus('ERROR', '⚠️ İBB Zaman Aşımı: Lütfen Wi-Fi kapatıp 2 dk bekleyin.');
                                }
                                return;
                            }

                            if (body.indexOf('Giriş Başarılı') !== -1 || body.indexOf('Hoş Geldiniz') !== -1) {
                                clearInterval(poll);
                                if (window.AndroidApp) {
                                    window.AndroidApp.onLoginStatus('SUCCESS', '🎉 Giriş Başarılı! İnternet aktif.');
                                }
                                return;
                            }

                            // 1. Check phone input
                            var phoneInput = document.querySelector('input[type="tel"], input[name*="Phone"], input[id*="Phone"], input[name*="phone"]');
                            if (phoneInput && phoneInput.value !== phoneVal) {
                                phoneInput.focus();
                                phoneInput.value = phoneVal;
                                phoneInput.dispatchEvent(new Event('input', { bubbles: true }));
                                phoneInput.dispatchEvent(new Event('change', { bubbles: true }));
                                if (window.AndroidApp) {
                                    window.AndroidApp.onLoginStatus('PROGRESS', 'Telefon numarası yazıldı...');
                                }
                            }

                            // 2. Check password visibility
                            var passInput = document.querySelector('input[type="password"], input[name*="Password"], input[id*="Password"], input[name*="pass"]');
                            var isPassVisible = passInput && (passInput.offsetWidth > 0 || passInput.offsetHeight > 0 || passInput.getClientRects().length > 0);

                            if (!isPassVisible) {
                                var nextBtn = document.querySelector('#btnLandingCheck, button[name*="Landing"], button.btn-primary');
                                if (nextBtn && !nextBtn.disabled) {
                                    nextBtn.click();
                                    if (window.AndroidApp) {
                                        window.AndroidApp.onLoginStatus('PROGRESS', 'Numara gönderildi, şifre alanı bekleniyor...');
                                    }
                                }
                            } else {
                                if (passInput.value !== passVal) {
                                    passInput.focus();
                                    passInput.value = passVal;
                                    passInput.dispatchEvent(new Event('input', { bubbles: true }));
                                    passInput.dispatchEvent(new Event('change', { bubbles: true }));
                                    if (window.AndroidApp) {
                                        window.AndroidApp.onLoginStatus('PROGRESS', 'Şifre yazıldı...');
                                    }
                                }

                                var submitBtn = document.querySelector('#btnLogin, button[type="submit"], input[type="submit"], button.btn-success');
                                if (submitBtn && !submitBtn.disabled) {
                                    submitBtn.click();
                                    if (window.AndroidApp) {
                                        window.AndroidApp.onLoginStatus('PROGRESS', 'Giriş butonuna tıklandı, onay bekleniyor...');
                                    }
                                }
                            }
                        }, 600);
                    })();
                """.trimIndent()

                v.evaluateJavascript(js, null)
            }
        }

        lifecycleScope.launch {
            val portalUrl = withContext(Dispatchers.IO) {
                IbbLoginEngine.detectPortalUrl(client)
            }
            setStatusText("Masaüstü portal sayfası açılıyor...", StatusType.PROGRESS)
            webView.loadUrl(portalUrl)
        }
    }

    private fun loadAccountsFromPrefs() {
        accounts.clear()
        val prefs = getSharedPreferences("ibbwifi_prefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("saved_accounts_json", null)

        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    accounts.add(
                        SavedAccount(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            phone = obj.getString("phone"),
                            pass = obj.getString("pass"),
                            isSelected = obj.optBoolean("isSelected", false)
                        )
                    )
                }
            } catch (ignored: Exception) {}
        }

        // Migration from legacy single credential
        if (accounts.isEmpty()) {
            val legacyPhone = prefs.getString("phone", "").orEmpty()
            val legacyPass = prefs.getString("password", "").orEmpty()
            if (legacyPhone.isNotBlank() && legacyPass.isNotBlank()) {
                accounts.add(SavedAccount(phone = legacyPhone, pass = legacyPass, isSelected = true))
                saveAccountsToPrefs()
            }
        }

        // Ensure at least one is selected if accounts exist
        if (accounts.isNotEmpty() && accounts.none { it.isSelected }) {
            accounts[0].isSelected = true
        }

        renderAccountsList()
    }

    private fun saveAccountsToPrefs() {
        val prefs = getSharedPreferences("ibbwifi_prefs", Context.MODE_PRIVATE)
        val array = JSONArray()
        for (acc in accounts) {
            val obj = JSONObject().apply {
                put("id", acc.id)
                put("phone", acc.phone)
                put("pass", acc.pass)
                put("isSelected", acc.isSelected)
            }
            array.put(obj)
        }
        prefs.edit().putString("saved_accounts_json", array.toString()).apply()

        // Also sync active account to legacy keys for background service
        val selected = accounts.firstOrNull { it.isSelected }
        if (selected != null) {
            prefs.edit()
                .putString("phone", selected.phone)
                .putString("password", selected.pass)
                .apply()
        }
    }

    private fun saveNewAccount(phone: String, pass: String) {
        // Deselect others and select this new one
        accounts.forEach { it.isSelected = false }
        val newAccount = SavedAccount(
            id = UUID.randomUUID().toString(),
            phone = phone,
            pass = pass,
            isSelected = true
        )
        accounts.add(0, newAccount)

        saveAccountsToPrefs()
        renderAccountsList()
        val masked = if (pass.length > 2) "•••" + pass.takeLast(2) else "••••"
        setStatusText("Yeni kayıt eklendi ve seçildi: $phone ($masked)", StatusType.INFO)
    }

    private fun selectAccount(selectedAccount: SavedAccount) {
        accounts.forEach { it.isSelected = (it.id == selectedAccount.id) }
        saveAccountsToPrefs()
        renderAccountsList()

        binding.etPhone.setText(selectedAccount.phone)
        binding.etPassword.setText(selectedAccount.pass)
        val masked = if (selectedAccount.pass.length > 2) "•••" + selectedAccount.pass.takeLast(2) else "••••"
        setStatusText("Seçili Hesap: ${selectedAccount.phone} ($masked). Giriş yapabilirsiniz.", StatusType.INFO)
    }

    private fun deleteAccount(account: SavedAccount) {
        val wasSelected = account.isSelected
        accounts.removeAll { it.id == account.id }
        if (wasSelected && accounts.isNotEmpty()) {
            accounts[0].isSelected = true
        }
        saveAccountsToPrefs()
        renderAccountsList()
        Toast.makeText(this, "${account.phone} silindi.", Toast.LENGTH_SHORT).show()
    }

    private fun renderAccountsList() {
        binding.llSavedAccounts.removeAllViews()

        if (accounts.isEmpty()) {
            binding.tvEmptyAccounts.visibility = View.VISIBLE
            return
        }
        binding.tvEmptyAccounts.visibility = View.GONE

        val inflater = LayoutInflater.from(this)

        for (acc in accounts) {
            val itemView = inflater.inflate(R.layout.item_saved_account, binding.llSavedAccounts, false)
            val card = itemView.findViewById<MaterialCardView>(R.id.cardAccount)
            val tvPhone = itemView.findViewById<TextView>(R.id.tvAccountPhone)
            val tvStatus = itemView.findViewById<TextView>(R.id.tvAccountStatus)
            val ivRadio = itemView.findViewById<ImageView>(R.id.ivSelectRadio)
            val btnDelete = itemView.findViewById<ImageButton>(R.id.btnDeleteAccount)

            val maskedPass = if (acc.pass.length > 2) "•••" + acc.pass.takeLast(2) else "••••"
            tvPhone.text = "${acc.phone}  ($maskedPass)"

            if (acc.isSelected) {
                card.strokeColor = ContextCompat.getColor(this, R.color.selected_stroke)
                card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.selected_bg))
                ivRadio.setImageResource(android.R.drawable.radiobutton_on_background)
                ivRadio.setColorFilter(ContextCompat.getColor(this, R.color.primary))
                tvStatus.text = "✓ Seçili hesap • Girişe hazır"
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.primary))

                // Also populate fields if they are currently blank
                if (binding.etPhone.text.isNullOrBlank()) {
                    binding.etPhone.setText(acc.phone)
                    binding.etPassword.setText(acc.pass)
                }
            } else {
                card.strokeColor = ContextCompat.getColor(this, R.color.card_stroke)
                card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.card_bg))
                ivRadio.setImageResource(android.R.drawable.radiobutton_off_background)
                ivRadio.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
                tvStatus.text = "Seçmek için dokunun"
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }

            card.setOnClickListener {
                selectAccount(acc)
            }

            btnDelete.setOnClickListener {
                deleteAccount(acc)
            }

            binding.llSavedAccounts.addView(itemView)
        }
    }

    enum class StatusType {
        NEUTRAL, PROGRESS, SUCCESS, ERROR, INFO
    }

    private fun setStatusText(text: String, type: StatusType) {
        binding.tvStatus.text = text
        when (type) {
            StatusType.NEUTRAL -> {
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                binding.cardStatus.strokeColor = ContextCompat.getColor(this, R.color.card_stroke)
            }
            StatusType.PROGRESS -> {
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
                binding.cardStatus.strokeColor = ContextCompat.getColor(this, R.color.accent)
            }
            StatusType.SUCCESS -> {
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.primary))
                binding.cardStatus.strokeColor = ContextCompat.getColor(this, R.color.primary)
            }
            StatusType.ERROR -> {
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.error))
                binding.cardStatus.strokeColor = ContextCompat.getColor(this, R.color.error)
            }
            StatusType.INFO -> {
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                binding.cardStatus.strokeColor = ContextCompat.getColor(this, R.color.primary_dark)
            }
        }
    }
}
