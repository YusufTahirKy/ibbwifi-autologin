package com.yusuftahir.ibbwifi

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yusuftahir.ibbwifi.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("ibbwifi_prefs", Context.MODE_PRIVATE)

        // Load saved values
        binding.etPhone.setText(prefs.getString("phone", ""))
        binding.etPassword.setText(prefs.getString("password", ""))
        binding.switchAutoLogin.isChecked = prefs.getBoolean("auto_login_enabled", false)

        // Save Credentials Button
        binding.btnSave.setOnClickListener {
            val phone = binding.etPhone.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()

            prefs.edit()
                .putString("phone", phone)
                .putString("password", pass)
                .apply()

            Toast.makeText(this, "Bilgiler kaydedildi.", Toast.LENGTH_SHORT).show()
        }

        // Auto-login Switch (AÇ / KAPA)
        binding.switchAutoLogin.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_login_enabled", isChecked).apply()

            val serviceIntent = Intent(this, IbbBackgroundService::class.java)
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                binding.tvStatus.text = "Durum: Otomatik giriş AÇIK (Bağlantı bekleniyor)"
            } else {
                stopService(serviceIntent)
                binding.tvStatus.text = "Durum: Otomatik giriş KAPALI"
            }
        }

        // Connect Now (Manual Trigger)
        binding.btnConnectNow.setOnClickListener {
            val phone = binding.etPhone.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()

            if (phone.isBlank() || pass.isBlank()) {
                Toast.makeText(this, "Lütfen önce telefon ve şifre girin.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnConnectNow.isEnabled = false
            lifecycleScope.launch {
                IbbLoginEngine.login(phone, pass) { status ->
                    runOnUiThread {
                        binding.tvStatus.text = "Durum: $status"
                    }
                }.onSuccess { msg ->
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(this@MainActivity, "Hata: ${err.message}", Toast.LENGTH_LONG).show()
                }
                binding.btnConnectNow.isEnabled = true
            }
        }

        // Set initial status text
        if (binding.switchAutoLogin.isChecked) {
            binding.tvStatus.text = "Durum: Otomatik giriş AÇIK"
        } else {
            binding.tvStatus.text = "Durum: Hazır"
        }
    }
}
