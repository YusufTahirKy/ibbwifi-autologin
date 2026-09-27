package com.yusuftahir.ibbwifi

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
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
import kotlinx.coroutines.launch
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

        // Save Button: adds new credential record
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

        // Single Primary Connect Button
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

            lifecycleScope.launch {
                binding.btnConnectNow.isEnabled = false
                setStatusText("Portala bağlanılıyor...", StatusType.PROGRESS)

                val result = IbbLoginEngine.login(this@MainActivity, phone, pass) { msg ->
                    runOnUiThread { setStatusText(msg, StatusType.PROGRESS) }
                }

                binding.btnConnectNow.isEnabled = true

                result.onSuccess { msg ->
                    setStatusText(msg, StatusType.SUCCESS)
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }.onFailure { err ->
                    setStatusText("Giriş Başarısız: ${err.message}", StatusType.ERROR)
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

    private fun loadAccountsFromPrefs() {
        val prefs = getSharedPreferences("ibbwifi_prefs", Context.MODE_PRIVATE)
        accounts.clear()

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
        // Deselect previous
        accounts.forEach { it.isSelected = false }
        val newAcc = SavedAccount(phone = phone, pass = pass, isSelected = true)
        accounts.add(0, newAcc)

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
