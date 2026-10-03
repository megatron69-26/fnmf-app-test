package com.example.nhumonglenh

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.nhumonglenh.ui.SystemBarInsets
import com.example.nhumonglenh.data.remote.AuthResponse
import com.example.nhumonglenh.data.remote.LoginRequest
import com.example.nhumonglenh.data.remote.NetworkConfig
import com.example.nhumonglenh.data.remote.RetrofitClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * =====================================================================
 * ACTIVITY 1 - MÀN HÌNH ĐĂNG NHẬP (AUTH ENTRYPOINT)
 * =====================================================================
 * 1. ĐĂNG NHẬP (LOGIN): Dùng Email & Password
 * 2. ĐĂNG KÝ (REGISTER): Mở màn hình đăng ký riêng
 * 3. KẾT NỐI: Chỉ kết nối máy chủ thử nghiệm cục bộ/tunnel, tuyệt đối chặn kết nối tới Railway Production
 * =====================================================================
 */
class Activity1 : AppCompatActivity() {

    private var loginCall: Call<AuthResponse>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.layout_activity1)
        SystemBarInsets.apply(this, findViewById(android.R.id.content))
        Log.d(TAG, "Activity1 onCreate")

        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val btnRegister = findViewById<Button>(R.id.btnRegister)
        val btnTestServerConfig = findViewById<Button>(R.id.btnTestServerConfig)
        val tvCurrentServerStatus = findViewById<TextView>(R.id.tvCurrentServerStatus)

        val prefs = getSharedPreferences(NetworkConfig.PREFS_NAME, Context.MODE_PRIVATE)

        fun updateServerStatusDisplay() {
            val currentUrl = prefs.getString(NetworkConfig.KEY_SERVER_URL, null)
            val isConfigured = NetworkConfig.isTestServerConfigured(prefs)
            if (isConfigured && !currentUrl.isNullOrBlank()) {
                tvCurrentServerStatus.text = "Server: $currentUrl"
                tvCurrentServerStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            } else {
                tvCurrentServerStatus.text = "Server: Chưa cấu hình (Bấm để cài đặt)"
                tvCurrentServerStatus.setTextColor(android.graphics.Color.parseColor("#FFA726"))
            }
        }

        fun showServerConfigDialog() {
            val input = EditText(this).apply {
                hint = "https://your-tunnel-hostname.com/"
                setText(prefs.getString(NetworkConfig.KEY_SERVER_URL, "") ?: "")
                setSelection(text.length)
                setPadding(40, 30, 40, 30)
                setTextColor(android.graphics.Color.WHITE)
                setHintTextColor(android.graphics.Color.parseColor("#787B86"))
            }

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("⚙️ Cấu hình Backend Note10+")
                .setMessage("Nhập URL máy chủ thử nghiệm (ưu tiên HTTPS tunnel như https://...):\n\nLƯU Ý: Tuyệt đối không trỏ về Railway Production.")
                .setView(input)
                .setPositiveButton("Lưu") { _, _ ->
                    val enteredUrl = input.text.toString().trim()
                    val (success, message) = NetworkConfig.validateAndSetTestServerUrl(prefs, enteredUrl)
                    if (success) {
                        val activeUrl = NetworkConfig.getOrMigrateServerUrl(prefs)
                        RetrofitClient.updateBaseUrl(activeUrl)
                        updateServerStatusDisplay()
                        Toast.makeText(this, "Đã lưu server test: $activeUrl", Toast.LENGTH_SHORT).show()
                        if (message != null) {
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("Lưu ý kết nối")
                                .setMessage(message)
                                .setPositiveButton("Đã hiểu", null)
                                .show()
                        }
                    } else {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Cấu hình không hợp lệ")
                            .setMessage(message ?: "URL không hợp lệ.")
                            .setPositiveButton("Thử lại") { _, _ -> showServerConfigDialog() }
                            .setNegativeButton("Hủy", null)
                            .show()
                    }
                }
                .setNegativeButton("Hủy", null)
                .show()
        }

        btnTestServerConfig.setOnClickListener {
            showServerConfigDialog()
        }

        // Khởi tạo hiển thị server test
        updateServerStatusDisplay()
        if (NetworkConfig.isTestServerConfigured(prefs)) {
            RetrofitClient.updateBaseUrl(NetworkConfig.getOrMigrateServerUrl(prefs))
        }

        // Di chuyển SharedPreferences từ saved_username sang saved_email (chạy đúng 1 lần)
        when (val migration = NetworkConfig.migrateSavedAccount(prefs)) {
            is NetworkConfig.EmailMigrationResult.Migrated -> {
                etEmail.setText(migration.email)
            }
            is NetworkConfig.EmailMigrationResult.LegacyAccountNeedsUpdate -> {
                // Tài khoản legacy (như khoi10): KHÔNG autofill, hiển thị cảnh báo
                etEmail.setText("")
                Toast.makeText(
                    this,
                    "Tài khoản '${migration.legacyUsername}' cần được quản trị viên cập nhật sang email thật.",
                    Toast.LENGTH_LONG
                ).show()
            }
            NetworkConfig.EmailMigrationResult.AlreadyMigrated -> {
                val saved = prefs.getString(NetworkConfig.KEY_SAVED_EMAIL, "") ?: ""
                val legacyIdentifier = prefs.getString(NetworkConfig.KEY_LEGACY_ACCOUNT_IDENTIFIER, "") ?: ""
                if (saved.isNotBlank()) {
                    etEmail.setText(saved)
                } else if (legacyIdentifier.isNotBlank()) {
                    etEmail.setText("")
                    Toast.makeText(
                        this,
                        "Tài khoản '$legacyIdentifier' cần được quản trị viên cập nhật sang email thật.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            NetworkConfig.EmailMigrationResult.NoSavedAccount -> {
                // Không có tài khoản lưu trước đó
            }
        }
        // Ô password mặc định để trống theo yêu cầu bảo mật

        // 2. Xử lý ĐĂNG NHẬP
        btnLogin.setOnClickListener {
            if (loginCall != null) return@setOnClickListener

            // Kiểm tra máy chủ test đã được cấu hình chưa trước khi gọi mạng
            if (!NetworkConfig.isTestServerConfigured(prefs)) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("⚠️ Chưa cấu hình Server Test")
                    .setMessage("Ứng dụng FNMF Test cần được kết nối tới Backend đang chạy trên Galaxy Note10+.\n\nVui lòng cấu hình URL máy chủ thử nghiệm trước khi đăng nhập.")
                    .setPositiveButton("Cấu hình ngay") { _, _ -> showServerConfigDialog() }
                    .setNegativeButton("Đóng", null)
                    .show()
                return@setOnClickListener
            }

            val rawEmail = etEmail.text.toString().trim()
            val password = etPassword.text.toString() // Không trim mật khẩu

            if (rawEmail.isEmpty()) {
                Toast.makeText(this, getString(R.string.auth_err_empty_email), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!NetworkConfig.isValidEmail(rawEmail)) {
                Toast.makeText(this, getString(R.string.auth_err_invalid_email), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password.isEmpty()) {
                Toast.makeText(this, getString(R.string.auth_err_empty_password), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val normalizedEmail = NetworkConfig.normalizeEmail(rawEmail)
            prefs.edit().putString(NetworkConfig.KEY_SAVED_EMAIL, normalizedEmail).apply()

            btnLogin.isEnabled = false
            btnLogin.text = getString(R.string.auth_logging_in)

            val request = LoginRequest(email = normalizedEmail, password = password)
            val call = RetrofitClient.apiService.login(request)
            loginCall = call
            call.enqueue(object : Callback<AuthResponse> {
                override fun onResponse(call: Call<AuthResponse>, response: Response<AuthResponse>) {
                    if (loginCall !== call || isFinishing || isDestroyed) return
                    loginCall = null
                    btnLogin.isEnabled = true
                    btnLogin.text = getString(R.string.auth_btn_login)

                    val token = response.body()?.token
                    if (response.isSuccessful && !token.isNullOrEmpty()) {
                        val outcome = com.example.nhumonglenh.data.local.AuthFlowCoordinator.handleAuthSuccess(
                            this@Activity1,
                            token
                        )
                        when (outcome) {
                            is com.example.nhumonglenh.data.local.AuthFlowCoordinator.PersistenceOutcome.NavigateToMain -> {
                                Toast.makeText(this@Activity1, getString(R.string.auth_login_success), Toast.LENGTH_SHORT).show()
                                navigateToTradingScreen()
                            }
                            is com.example.nhumonglenh.data.local.AuthFlowCoordinator.PersistenceOutcome.StayOnAuth -> {
                                btnLogin.isEnabled = outcome.isButtonEnabled
                                btnLogin.text = getString(R.string.auth_btn_login)
                                Toast.makeText(this@Activity1, getString(outcome.errorMessageResId), Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        val errMsg = response.body()?.message ?: getString(R.string.auth_err_invalid_credentials)
                        Toast.makeText(this@Activity1, errMsg, Toast.LENGTH_LONG).show()
                    }
                }

                override fun onFailure(call: Call<AuthResponse>, t: Throwable) {
                    if (loginCall !== call || call.isCanceled || isFinishing || isDestroyed) return
                    loginCall = null
                    btnLogin.isEnabled = true
                    btnLogin.text = getString(R.string.auth_btn_login)
                    Log.e(TAG, "Lỗi kết nối login: ${t.message}")
                    Toast.makeText(this@Activity1, "Không thể kết nối đến máy chủ. Vui lòng kiểm tra lại mạng.", Toast.LENGTH_LONG).show()
                }
            })
        }

        // 3. Mở màn hình đăng ký riêng (yêu cầu cấu hình server trước).
        btnRegister.setOnClickListener {
            if (!NetworkConfig.isTestServerConfigured(prefs)) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("⚠️ Chưa cấu hình Server Test")
                    .setMessage("Vui lòng cấu hình URL máy chủ thử nghiệm trước khi tạo tài khoản.")
                    .setPositiveButton("Cấu hình ngay") { _, _ -> showServerConfigDialog() }
                    .setNegativeButton("Đóng", null)
                    .show()
                return@setOnClickListener
            }
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun navigateToTradingScreen() {
        val intent = Intent(this, Activity2::class.java)
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        loginCall?.cancel()
        loginCall = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Activity1_Auth"
    }
}
