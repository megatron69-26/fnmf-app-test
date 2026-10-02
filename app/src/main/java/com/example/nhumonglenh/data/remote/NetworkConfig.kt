package com.example.nhumonglenh.data.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Nguồn cấu hình mạng tập trung cho toàn bộ ứng dụng FNMF.
 * Mặc định trỏ đến Railway Cloud Backend (HTTPS).
 * Đảm bảo RetrofitClient, News ApiClient và Activity1 dùng chung 1 nguồn cấu hình duy nhất.
 */
object NetworkConfig {
    private const val TAG = "NetworkConfig"

    const val PREFS_NAME = "fnmf_prefs"
    const val KEY_SERVER_URL = "server_url"
    const val KEY_CONFIG_VERSION = "server_config_version"

    // Môi trường Test: Mặc định trỏ đến backend thử nghiệm cục bộ (port 8083)
    // Tuyệt đối không trỏ đến Railway Production
    const val DEFAULT_SERVER_URL = "http://10.0.2.2:8083/"
    const val SERVER_CONFIG_VERSION = 100

    const val BLOCKED_PRODUCTION_KEYWORD = "railway.app"

    val LEGACY_HOSTS = listOf(
        "172.18.97.109",
        "10.174.64.109",
        "10.174.64.59",
        "10.0.2.2",
        "localhost",
        "127.0.0.1",
        ":3000"
    )

    /**
     * Kiểm tra URL có chứa domain Railway production hay không để chặn tuyệt đối.
     */
    fun isBlockedProductionUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.lowercase().contains(BLOCKED_PRODUCTION_KEYWORD)
    }

    /**
     * Chuẩn hoá URL: loại bỏ khoảng trắng và luôn đảm bảo kết thúc bằng dấu /
     * Nếu URL trỏ về Railway production, cưỡng chế chuyển về DEFAULT_SERVER_URL thử nghiệm.
     */
    fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || isBlockedProductionUrl(trimmed)) return DEFAULT_SERVER_URL
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    /**
     * Kiểm tra một URL có thuộc danh sách legacy hosts / LAN / localhost hay không.
     */
    fun isLegacyUrl(url: String): Boolean {
        val lower = url.lowercase()
        return LEGACY_HOSTS.any { lower.contains(it.lowercase()) }
    }

    /**
     * Kiểm tra tính hợp lệ cơ bản của URL (giao thức http/https và host hợp lệ).
     */
    fun isValidUrl(url: String): Boolean {
        val trimmed = url.trim().lowercase()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return false
        }
        val withoutScheme = trimmed.removePrefix("https://").removePrefix("http://")
        return withoutScheme.isNotBlank() && (withoutScheme.contains(".") || withoutScheme.startsWith("localhost"))
    }

    /**
     * Bản test cho phép cấu hình URL backend thử nghiệm tùy chỉnh (LAN IP, tunnel, Note10+).
     * Tuyệt đối chặn không cho sử dụng Railway Production.
     */
    fun decideServerUrl(savedUrl: String?, savedConfigVersion: Int): Pair<String, Int> {
        if (savedUrl.isNullOrBlank()) {
            return Pair(DEFAULT_SERVER_URL, SERVER_CONFIG_VERSION)
        }
        val normalized = normalizeUrl(savedUrl)
        if (isBlockedProductionUrl(normalized) || !isValidUrl(normalized)) {
            return Pair(DEFAULT_SERVER_URL, SERVER_CONFIG_VERSION)
        }
        return Pair(normalized, SERVER_CONFIG_VERSION)
    }

    /**
     * Cập nhật URL server thử nghiệm một cách an toàn vào SharedPreferences.
     */
    fun setTestServerUrl(prefs: SharedPreferences, url: String): Boolean {
        if (isBlockedProductionUrl(url) || !isValidUrl(url)) return false
        val normalized = normalizeUrl(url)
        prefs.edit()
            .putString(KEY_SERVER_URL, normalized)
            .putInt(KEY_CONFIG_VERSION, SERVER_CONFIG_VERSION)
            .apply()
        return true
    }

    /**
     * Lấy và tự động migrate URL từ SharedPreferences.
     */
    fun getOrMigrateServerUrl(prefs: SharedPreferences): String {
        val savedUrl = prefs.getString(KEY_SERVER_URL, null)
        val savedVersion = prefs.getInt(KEY_CONFIG_VERSION, 0)

        val (resolvedUrl, newVersion) = decideServerUrl(savedUrl, savedVersion)

        if (savedUrl != resolvedUrl || savedVersion != newVersion) {
            prefs.edit()
                .putString(KEY_SERVER_URL, resolvedUrl)
                .putInt(KEY_CONFIG_VERSION, newVersion)
                .apply()
            Log.d(TAG, "Server URL đã cập nhật: $resolvedUrl (version $savedVersion -> $newVersion)")
        }

        return resolvedUrl
    }

    /**
     * Hàm tiện ích tương thích lấy URL từ Context.
     */
    fun getServerUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return getOrMigrateServerUrl(prefs)
    }

    const val KEY_SAVED_EMAIL = "saved_email"
    const val KEY_SAVED_USERNAME = "saved_username"
    const val KEY_LEGACY_ACCOUNT_IDENTIFIER = "legacy_account_identifier"
    const val KEY_EMAIL_MIGRATION_DONE = "email_migration_done"

    private val EMAIL_PATTERN = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()

    fun isValidEmail(email: String?): Boolean {
        if (email.isNullOrBlank()) return false
        return EMAIL_PATTERN.matches(email.trim())
    }

    fun isValidPassword(password: String?): Boolean {
        return password != null && password.length >= 8
    }

    fun normalizeEmail(email: String?): String {
        return email?.trim()?.lowercase(java.util.Locale.ROOT) ?: ""
    }

    sealed class EmailMigrationResult {
        data class Migrated(val email: String) : EmailMigrationResult()
        data class LegacyAccountNeedsUpdate(val legacyUsername: String) : EmailMigrationResult()
        object AlreadyMigrated : EmailMigrationResult()
        object NoSavedAccount : EmailMigrationResult()
    }

    sealed class AccountMigrationDecision {
        data class MigrateToEmail(val normalizedEmail: String) : AccountMigrationDecision()
        data class LegacyAccountDetected(val legacyUsername: String) : AccountMigrationDecision()
        data class UseExistingSavedEmail(val savedEmail: String) : AccountMigrationDecision()
        object NoActionNeeded : AccountMigrationDecision()
    }

    /**
     * Thuật toán quyết định xử lý migration độc lập với Android framework, phục vụ Unit Test.
     */
    fun decideAccountMigration(
        alreadyMigrated: Boolean,
        savedEmail: String?,
        savedUsername: String?
    ): AccountMigrationDecision {
        if (alreadyMigrated) {
            return if (!savedEmail.isNullOrBlank()) {
                AccountMigrationDecision.UseExistingSavedEmail(savedEmail.trim())
            } else {
                AccountMigrationDecision.NoActionNeeded
            }
        }
        if (savedUsername.isNullOrBlank()) {
            return AccountMigrationDecision.NoActionNeeded
        }
        val trimmed = savedUsername.trim()
        return if (isValidEmail(trimmed)) {
            AccountMigrationDecision.MigrateToEmail(normalizeEmail(trimmed))
        } else {
            AccountMigrationDecision.LegacyAccountDetected(trimmed)
        }
    }

    /**
     * Di chuyển SharedPreferences từ saved_username sang saved_email.
     * Chạy duy nhất một lần.
     * - Nếu saved_username là email hợp lệ: normalize sang saved_email và xoá saved_username.
     * - Nếu saved_username là legacy (như khoi10): lưu riêng vào key legacy_account_identifier chỉ để hiển thị cảnh báo chuyển đổi, sau đó xóa saved_username.
     * - Tuyệt đối KHÔNG tự tạo email giả.
     */
    fun migrateSavedAccount(prefs: SharedPreferences): EmailMigrationResult {
        val alreadyMigrated = prefs.getBoolean(KEY_EMAIL_MIGRATION_DONE, false)
        val savedEmail = prefs.getString(KEY_SAVED_EMAIL, null)
        val savedUsername = prefs.getString(KEY_SAVED_USERNAME, null)

        // Dọn dẹp triệt để saved_username nếu đã migrate trước đó nhưng còn sót
        if (alreadyMigrated && !savedUsername.isNullOrBlank()) {
            prefs.edit().remove(KEY_SAVED_USERNAME).apply()
        }

        return when (val decision = decideAccountMigration(alreadyMigrated, savedEmail, savedUsername)) {
            is AccountMigrationDecision.UseExistingSavedEmail -> {
                EmailMigrationResult.Migrated(decision.savedEmail)
            }
            is AccountMigrationDecision.NoActionNeeded -> {
                if (!alreadyMigrated) {
                    prefs.edit()
                        .remove(KEY_SAVED_USERNAME)
                        .putBoolean(KEY_EMAIL_MIGRATION_DONE, true)
                        .apply()
                }
                if (!savedEmail.isNullOrBlank()) {
                    EmailMigrationResult.Migrated(savedEmail)
                } else {
                    EmailMigrationResult.NoSavedAccount
                }
            }
            is AccountMigrationDecision.MigrateToEmail -> {
                prefs.edit()
                    .putString(KEY_SAVED_EMAIL, decision.normalizedEmail)
                    .remove(KEY_SAVED_USERNAME)
                    .putBoolean(KEY_EMAIL_MIGRATION_DONE, true)
                    .apply()
                EmailMigrationResult.Migrated(decision.normalizedEmail)
            }
            is AccountMigrationDecision.LegacyAccountDetected -> {
                prefs.edit()
                    .putString(KEY_LEGACY_ACCOUNT_IDENTIFIER, decision.legacyUsername)
                    .remove(KEY_SAVED_USERNAME)
                    .putBoolean(KEY_EMAIL_MIGRATION_DONE, true)
                    .apply()
                EmailMigrationResult.LegacyAccountNeedsUpdate(decision.legacyUsername)
            }
        }
    }
}
