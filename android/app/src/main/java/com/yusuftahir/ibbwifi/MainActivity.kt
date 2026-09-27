package com.yusuftahir.ibbwifi

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.yusuftahir.ibbwifi.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class SavedAccount(
    val phone: String,
    val pass: String,
    var isSelected: Boolean = false
)

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val accounts = mutableListOf<SavedAccount>()

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

        // Save Button: adds or updates account
        binding.btnSave.setOnClickListener {
            val phone = binding.etPhone.text?.toString()?.trim().orEmpty()
            val pass = binding.etPassword.text?.toString()?.trim().orEmpty()

            if (phone.isBlank() || pass.isBlank()) {
                setStatusText("Lütfen telefon ve şifre girin.", StatusType.ERROR)
                Toast.makeText(this, "Lütfen telefon ve şifre girin.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            saveOrUpdateAccount(phone, pass)
            Toast.makeText(this, "Bilgiler kaydedildi.", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "Şifreniz panoya kopyalandı! Tarayıcıda yapıştırabilirsiniz.", Toast.LENGTH_LONG).show()
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

            // Check mobile data
            checkMobileDataWarning()

            // Run desktop webview auto-login
            startDesktopWebViewLogin(phone, pass)
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
        binding.cardWebView.visibility = View.VISIBLE
        binding.pbWebLoading.visibility = View.VISIBLE
        binding.btnConnectNow.isEnabled = false
        setStatusText("Masaüstü modunda portala bağlanılıyor (192.168.1.1)...", StatusType.PROGRESS)

        val webView = binding.webViewPortal
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        // Set standard Desktop Linux Chrome User-Agent
        settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                binding.pbWebLoading.visibility = View.VISIBLE
                setStatusText("Sayfa açılıyor: ${url?.take(40)}...", StatusType.PROGRESS)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val v = view ?: return
                binding.pbWebLoading.visibility = View.GONE
                val currentUrl = url.orEmpty()

                if (currentUrl.contains("generate_204") || currentUrl.contains("google.com")) {
                    setStatusText("🎉 Giriş başarılı! İnternet aktif.", StatusType.SUCCESS)
                    binding.btnConnectNow.isEnabled = true
                    Toast.makeText(this@MainActivity, "Giriş başarılı! İnternet aktif.", Toast.LENGTH_SHORT).show()
                    return
                }

                // Check for IBB timeout / session error in HTML
                v.evaluateJavascript(
                    "(function() { return document.body ? document.body.innerText : ''; })();"
                ) { bodyText ->
                    if (bodyText != null && (bodyText.contains("Oturum Bulunamadı") || bodyText.contains("uzun süre oturum açılmadan"))) {
                        setStatusText("⚠️ İBB Oturum Zaman Aşımı: Lütfen Wi-Fi'yi kapatıp 2-3 dakika bekledikten sonra yeniden bağlanın.", StatusType.ERROR)
                        binding.btnConnectNow.isEnabled = true
                        return@evaluateJavascript
                    }

                    // Inject auto-fill script for Phone & Password
                    val js = """
                        (function() {
                            var phone = '$phone';
                            var pass = '$pass';
                            
                            var phoneInput = document.querySelector('input[type="tel"], input[name*="Phone"], input[id*="Phone"], input[name*="phone"]');
                            if (phoneInput && !phoneInput.value) {
                                phoneInput.value = phone;
                                phoneInput.dispatchEvent(new Event('input', { bubbles: true }));
                                phoneInput.dispatchEvent(new Event('change', { bubbles: true }));
                            }
                            
                            var passInput = document.querySelector('input[type="password"], input[name*="Password"], input[id*="Password"], input[name*="pass"]');
                            if (passInput && !passInput.value) {
                                passInput.value = pass;
                                passInput.dispatchEvent(new Event('input', { bubbles: true }));
                                passInput.dispatchEvent(new Event('change', { bubbles: true }));
                            }

                            setTimeout(function() {
                                var submitBtn = document.querySelector('button[type="submit"], input[type="submit"], .btn-primary, #btnLogin, #btnLandingCheck');
                                if (submitBtn) {
                                    submitBtn.click();
                                }
                            }, 500);
                        })();
                    """.trimIndent()

                    v.evaluateJavascript(js, null)
                    setStatusText("Bilgiler girildi, giriş doğrulanıyor...", StatusType.PROGRESS)
                }

                binding.btnConnectNow.isEnabled = true
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.description?.toString().orEmpty() else ""
                    if (desc.isNotBlank() && !desc.contains("SUCCESS", ignoreCase = true)) {
                        setStatusText("Tarayıcı uyarısı: $desc (Mobil verinin kapalı olduğundan emin olun)", StatusType.INFO)
                    }
                    binding.btnConnectNow.isEnabled = true
                }
            }
        }

        webView.loadUrl("http://192.168.1.1")
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
                accounts.add(SavedAccount(legacyPhone, legacyPass, isSelected = true))
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

    private fun saveOrUpdateAccount(phone: String, pass: String) {
        val existing = accounts.firstOrNull { it.phone == phone }
        if (existing != null) {
            accounts.remove(existing)
        }

        // Deselect others and select this one
        accounts.forEach { it.isSelected = false }
        accounts.add(0, SavedAccount(phone, pass, isSelected = true))

        saveAccountsToPrefs()
        renderAccountsList()
        setStatusText("Hesap kaydedildi ve seçildi: $phone", StatusType.INFO)
    }

    private fun selectAccount(selectedAccount: SavedAccount) {
        accounts.forEach { it.isSelected = (it.phone == selectedAccount.phone) }
        saveAccountsToPrefs()
        renderAccountsList()

        binding.etPhone.setText(selectedAccount.phone)
        binding.etPassword.setText(selectedAccount.pass)
        setStatusText("Seçili Hesap: ${selectedAccount.phone}. 'Bağlan' butonuna basabilirsiniz.", StatusType.INFO)
    }

    private fun deleteAccount(account: SavedAccount) {
        val wasSelected = account.isSelected
        accounts.remove(account)
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

            tvPhone.text = acc.phone

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
                tvStatus.text = "Şifre kayıtlı • Seçmek için dokunun"
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
