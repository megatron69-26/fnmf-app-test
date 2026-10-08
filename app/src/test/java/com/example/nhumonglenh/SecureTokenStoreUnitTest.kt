package com.example.nhumonglenh

import android.content.Context
import android.content.SharedPreferences
import com.example.nhumonglenh.data.local.KeyStoreProvider
import com.example.nhumonglenh.data.local.SecureTokenStore
import com.example.nhumonglenh.data.local.TokenCryptor
import com.example.nhumonglenh.data.local.TokenStoreEngine
import com.example.nhumonglenh.data.remote.ForecastResponse
import com.example.nhumonglenh.data.remote.NetworkConfig
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * JVM Behavioral Unit Test Suite cho FNMF v1.1.18 RC3:
 * 
 * PHẠM VI XÁC THỰC:
 * - Đây là các bài kiểm thử hành vi trong môi trường JVM Local Unit Test (không dùng emulator / device).
 * - Sử dụng TokenCryptor và TokenStoreEngine thông qua internal dependency injection.
 * - Kiểm thử thuật toán AES-GCM, ngẫu nhiên hóa IV, fail-safe khi corrupt ciphertext,
 *   fail-closed state transitions, migration và synchronous commit.
 * - Các kiểm thử AndroidKeyStore phần cứng thực tế được đặt trong androidTest (SecureTokenStoreAndroidTest).
 */
class SecureTokenStoreUnitTest {

    private val gson = Gson()
    private val testKey: SecretKey = SecretKeySpec(ByteArray(32) { (it + 7).toByte() }, "AES")
    private val testKeyProvider = object : KeyStoreProvider {
        override fun getSecretKey(): SecretKey = testKey
    }

    private val cryptor = TokenCryptor(testKeyProvider)
    private val engine = TokenStoreEngine(cryptor)

    // =========================================================================
    // 1. JVM BEHAVIORAL TESTS: AES-GCM MÃ HÓA & GIẢI MÃ
    // =========================================================================

    @Test
    fun testAESGCM_encryptAndDecryptRoundTrip() {
        val originalJwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyQGZubWYuY29tIiwiaWF0IjoxNTE2MjM5MDIyfQ.sampleSignature12345"
        val encrypted = cryptor.encrypt(originalJwt)

        assertNotNull(encrypted)
        assertTrue("Ciphertext Base64 không được rỗng", encrypted.isNotBlank())
        assertNotEquals("Ciphertext không được trùng với Plaintext", originalJwt, encrypted)

        val decrypted = cryptor.decrypt(encrypted)
        assertEquals("Dữ liệu giải mã phải trùng khớp 100% với JWT ban đầu", originalJwt, decrypted)
    }

    @Test
    fun testAESGCM_randomIVProducesDistinctCiphertextsForSamePlaintext() {
        val sample = "test_token_content_for_iv_check"
        val enc1 = cryptor.encrypt(sample)
        val enc2 = cryptor.encrypt(sample)

        assertNotEquals("Mỗi lần mã hoá phải sinh IV ngẫu nhiên khác nhau", enc1, enc2)
        assertEquals("Cả hai ciphertext đều phải giải mã về cùng nội dung gốc", sample, cryptor.decrypt(enc1))
        assertEquals("Cả hai ciphertext đều phải giải mã về cùng nội dung gốc", sample, cryptor.decrypt(enc2))
    }

    @Test
    fun testAESGCM_corruptedCiphertext_returnsEmptyAndDoesNotCrash() {
        assertEquals("", cryptor.decrypt(""))
        assertEquals("", cryptor.decrypt("   "))
        assertEquals("", cryptor.decrypt("short_non_base64"))
        assertEquals("", cryptor.decrypt("dGhpcyBpcyB0b28gc2hvcnQ=")) // base64 < 28 bytes

        val validEncrypted = cryptor.encrypt("valid_token_data")
        val decoded = java.util.Base64.getDecoder().decode(validEncrypted)
        // Làm hỏng 1 byte trong payload/tag
        decoded[decoded.size - 1] = (decoded[decoded.size - 1].toInt() xor 0xFF).toByte()
        val corruptedBase64 = java.util.Base64.getEncoder().encodeToString(decoded)

        val result = cryptor.decrypt(corruptedBase64)
        assertEquals("Dữ liệu bị sửa đổi (tampered GCM tag) phải trả về rỗng", "", result)
    }

    @Test
    fun testFailClosed_whenKeyStoreOrKeyProviderFails() {
        val failingProvider = object : KeyStoreProvider {
            override fun getSecretKey(): SecretKey? = null
        }
        val failingCryptor = TokenCryptor(failingProvider)

        val encrypted = failingCryptor.encrypt("sensitive_jwt_token")
        assertEquals("Khi KeyStore lỗi, encrypt() phải fail-closed và trả về rỗng", "", encrypted)

        val decrypted = failingCryptor.decrypt("any_encrypted_string")
        assertEquals("Khi KeyStore lỗi, decrypt() phải fail-closed và trả về rỗng", "", decrypted)
    }

    @Test
    fun testProductionSingleton_failsClosedInJvmEnvironmentWithoutCrashing() {
        // Trong môi trường JVM thuần không có AndroidKeyStore OS, singleton production phải fail-closed an toàn
        val enc = SecureTokenStore.encrypt("test_token")
        assertEquals("Production singleton trong JVM không có KeyStore phải fail-closed trả về rỗng", "", enc)

        val dec = SecureTokenStore.decrypt("some_ciphertext")
        assertEquals("Production singleton trong JVM không có KeyStore phải fail-closed trả về rỗng", "", dec)
    }

    // =========================================================================
    // 2. JVM BEHAVIORAL TESTS: MIGRATION & SHARED PREFERENCES PERSISTENCE
    // =========================================================================

    @Test
    fun testMigration_fromLegacyPlaintextToEncrypted_synchronouslyAndClearsPlaintext() {
        val fakeContext = FakeContext()
        val legacyPrefs = fakeContext.getSharedPreferences(NetworkConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val securePrefs = fakeContext.getSharedPreferences(SecureTokenStore.SECURE_PREFS_NAME, Context.MODE_PRIVATE)

        val legacyPlaintextJwt = "legacy.jwt.token.plaintext.value"
        legacyPrefs.edit().putString(SecureTokenStore.LEGACY_KEY_JWT_TOKEN, legacyPlaintextJwt).commit()

        // Lúc đầu trong securePrefs chưa có token mã hoá
        assertNull(securePrefs.getString(SecureTokenStore.KEY_ENCRYPTED_TOKEN, null))

        // Gọi getToken(context) qua testable engine -> kích hoạt migration tự động
        val token = engine.getToken(fakeContext)
        assertEquals("Token trả về phải là token cũ đã được di chuyển", legacyPlaintextJwt, token)

        // Sau migration:
        // 1. securePrefs đã lưu bản mã hoá
        val encryptedStored = securePrefs.getString(SecureTokenStore.KEY_ENCRYPTED_TOKEN, null)
        assertNotNull("securePrefs phải có token mã hoá sau migration", encryptedStored)
        assertEquals(legacyPlaintextJwt, cryptor.decrypt(encryptedStored!!))

        // 2. legacyPrefs đã được xoá sạch token plaintext
        assertNull(
            "Legacy SharedPreferences phải bị xóa sạch token plaintext cũ",
            legacyPrefs.getString(SecureTokenStore.LEGACY_KEY_JWT_TOKEN, null)
        )
    }

    @Test
    fun testMigration_failClosedWhenKeyStoreFails_doesNotExposeOrDeleteLegacyToken() {
        val fakeContext = FakeContext()
        val legacyPrefs = fakeContext.getSharedPreferences(NetworkConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val legacyPlaintextJwt = "legacy.jwt.token.plaintext.value"
        legacyPrefs.edit().putString(SecureTokenStore.LEGACY_KEY_JWT_TOKEN, legacyPlaintextJwt).commit()

        // Giả lập lỗi KeyStore
        val failingEngine = TokenStoreEngine(TokenCryptor(object : KeyStoreProvider {
            override fun getSecretKey(): SecretKey? = null
        }))

        val token = failingEngine.getToken(fakeContext)
        assertEquals("Khi KeyStore lỗi, migration thất bại phải fail-closed và KHÔNG trả lại token thô", "", token)

        // Bản lưu legacy không bị xóa oan uổng nhưng cũng không bị ghi đè dữ liệu rác
        assertEquals(legacyPlaintextJwt, legacyPrefs.getString(SecureTokenStore.LEGACY_KEY_JWT_TOKEN, null))
    }

    @Test
    fun testSaveToken_synchronousCommitAndCleansLegacy() {
        val fakeContext = FakeContext()
        val legacyPrefs = fakeContext.getSharedPreferences(NetworkConfig.PREFS_NAME, Context.MODE_PRIVATE)
        legacyPrefs.edit().putString(SecureTokenStore.LEGACY_KEY_JWT_TOKEN, "old_dummy_token").commit()

        val newJwt = "new.secure.jwt.token"
        val saved = engine.saveToken(fakeContext, newJwt)
        assertTrue("saveToken() phải trả về true khi commit thành công", saved)

        assertEquals("getToken() phải trả về token mới đã được giải mã", newJwt, engine.getToken(fakeContext))
        assertNull("legacyPrefs phải được dọn dẹp sạch", legacyPrefs.getString(SecureTokenStore.LEGACY_KEY_JWT_TOKEN, null))
    }

    @Test
    fun testClearToken_clearsBothSecureAndLegacyPrefs() {
        val fakeContext = FakeContext()
        val legacyPrefs = fakeContext.getSharedPreferences(NetworkConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val securePrefs = fakeContext.getSharedPreferences(SecureTokenStore.SECURE_PREFS_NAME, Context.MODE_PRIVATE)

        engine.saveToken(fakeContext, "active_token")
        legacyPrefs.edit().putString(SecureTokenStore.LEGACY_KEY_JWT_TOKEN, "leftover_plaintext").commit()

        engine.clearToken(fakeContext)

        assertEquals("", engine.getToken(fakeContext))
        assertNull(securePrefs.getString(SecureTokenStore.KEY_ENCRYPTED_TOKEN, null))
        assertNull(legacyPrefs.getString(SecureTokenStore.LEGACY_KEY_JWT_TOKEN, null))
    }

    // =========================================================================
    // 3. AUDIT TESTS: KIỂM TRA MÃ NGUỒN PRODUCTION & BẢO MẬT TUYỆT ĐỐI
    // =========================================================================

    @Test
    fun testSecureTokenStore_sourceCodeArchitectureAndSecurityConstraints() {
        val storeFile = File("src/main/java/com/example/nhumonglenh/data/local/SecureTokenStore.kt")
        val altFile = File("app/src/main/java/com/example/nhumonglenh/data/local/SecureTokenStore.kt")
        val target = if (storeFile.exists()) storeFile else altFile
        assertTrue("SecureTokenStore.kt phải tồn tại", target.exists())
        val content = target.readText()

        assertTrue("Phải sử dụng AndroidKeyStore", content.contains("AndroidKeyStore"))
        assertTrue("Phải sử dụng thuật toán AES/GCM/NoPadding", content.contains("AES/GCM/NoPadding"))
        assertTrue("Độ dài tag GCM phải là 128 bit", content.contains("128"))
        assertTrue("Độ dài khóa phải là 256 bit", content.contains("256"))
        assertTrue("Phải có cơ chế xóa plaintext token cũ", content.contains("clearLegacyPlaintextToken"))
        assertTrue("Phải sử dụng commit() đồng bộ cho lưu trữ an toàn", content.contains(".commit()"))

        // TUYỆT ĐỐI KHÔNG ĐƯỢC CHỨA KHÓA FALLBACK 0x5A HOẶC MUTABLE TEST HOOK TRONG PRODUCTION CODE
        assertFalse(
            "TUYỆT ĐỐI KHÔNG ĐƯỢC CHỨA KHÓA FALLBACK 0x5A trong production source code",
            content.contains("0x5A") || content.contains("0x5a")
        )
        assertFalse(
            "Không được chứa ByteArray(32) hardcoded fallback key trong production source code",
            content.contains("ByteArray(32)")
        )
        assertFalse(
            "Không được để mutable test hook public trong production singleton",
            content.contains("var testSecretKeyProvider")
        )

        assertFalse(
            "Không được log raw JWT token ra logcat",
            content.contains("Log.d(") || content.contains("Log.i(") || content.contains("println(")
        )
    }

    // =========================================================================
    // 4. POLICY & COMPATIBILITY TESTS
    // =========================================================================

    @Test
    fun testPasswordPolicy_minimum8CharactersEnforced() {
        assertFalse("Mật khẩu null không hợp lệ", NetworkConfig.isValidPassword(null))
        assertFalse("Mật khẩu rỗng không hợp lệ", NetworkConfig.isValidPassword(""))
        assertFalse("Mật khẩu 1 ký tự không hợp lệ", NetworkConfig.isValidPassword("a"))
        assertFalse("Mật khẩu 4 ký tự không hợp lệ", NetworkConfig.isValidPassword("1234"))
        assertFalse("Mật khẩu 7 ký tự không hợp lệ", NetworkConfig.isValidPassword("1234567"))
        assertTrue("Mật khẩu đúng 8 ký tự hợp lệ", NetworkConfig.isValidPassword("12345678"))
        assertTrue("Mật khẩu > 8 ký tự hợp lệ", NetworkConfig.isValidPassword("securePassword123!"))
        assertTrue("Mật khẩu có khoảng trắng giữ nguyên và tính vào độ dài", NetworkConfig.isValidPassword("  1234  "))
    }

    @Test
    fun testForecastResponse_supportsAnalysisSourceAndCandleCount() {
        val json = """
            {
                "symbol": "BTCUSDT",
                "assetName": "Bitcoin",
                "currentPrice": 88000.0,
                "trendPrediction": "BULLISH_UPTREND",
                "timeframe": "24H_7D",
                "supportLevel": 85000.0,
                "resistanceLevel": 92000.0,
                "recommendation": "BUY",
                "confidenceScore": 88,
                "keyDrivers": ["Fed giữ nguyên lãi suất", "Dòng vốn ETF tăng trưởng mạnh"],
                "technicalOutlook": "Đang trong xu hướng tăng mạnh",
                "fundamentalOutlook": "Tích cực",
                "fromCache": false,
                "analysisSource": "GEMINI",
                "candleCount": 30
            }
        """.trimIndent()

        val response = gson.fromJson(json, ForecastResponse::class.java)
        assertNotNull(response)
        assertEquals("BTCUSDT", response.symbol)
        assertEquals("GEMINI", response.analysisSource)
        assertEquals(Integer.valueOf(30), response.candleCount)
        assertEquals("BUY", response.recommendation)
        assertEquals(88, response.confidenceScore)
        assertEquals(2, response.keyDrivers?.size)
    }

    @Test
    fun testAndroidManifest_declaresSecurityHardeningAttributes() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        val altFile = File("app/src/main/AndroidManifest.xml")
        val target = if (manifestFile.exists()) manifestFile else altFile
        assertTrue("AndroidManifest.xml phải tồn tại", target.exists())
        val content = target.readText()

        assertTrue(
            "Manifest phải khai báo android:allowBackup=\"false\" để chống trích xuất dữ liệu",
            content.contains("android:allowBackup=\"false\"")
        )
        assertTrue(
            "Manifest phải vô hiệu hoá Cleartext HTTP: android:usesCleartextTraffic=\"false\"",
            content.contains("android:usesCleartextTraffic=\"false\"")
        )
    }

    @Test
    fun testStringsXml_passwordTooShortErrorMessageUpdated() {
        val stringsFile = File("src/main/res/values/strings.xml")
        val altFile = File("app/src/main/res/values/strings.xml")
        val target = if (stringsFile.exists()) stringsFile else altFile
        assertTrue("strings.xml phải tồn tại", target.exists())
        val content = target.readText()

        assertTrue(
            "Thông báo lỗi mật khẩu quá ngắn phải yêu cầu ít nhất 8 ký tự",
            content.contains("Mật khẩu phải có ít nhất 8 ký tự")
        )
        assertFalse(
            "Không được còn thông báo yêu cầu 4 ký tự",
            content.contains("Mật khẩu phải có ít nhất 4 ký tự")
        )
    }

    @Test
    fun testBuildGradle_versionCodeMatchesVersionName() {
        val buildGradleFile = File("app/build.gradle.kts")
        val fallbackFile = File("build.gradle.kts")
        val target = if (buildGradleFile.exists()) buildGradleFile else fallbackFile
        assertTrue("build.gradle.kts phải tồn tại", target.exists())
        val content = target.readText()

        val versionCode = Regex("versionCode\\s*=\\s*(\\d+)").find(content)?.groupValues?.get(1)?.toInt()
        val versionPatch = Regex("versionName\\s*=\\s*\"1\\.1\\.(\\d+)\"").find(content)?.groupValues?.get(1)?.toInt()
        assertNotNull("versionCode must be declared", versionCode)
        assertTrue("versionCode must be at least 20", versionCode!! >= 20)
        assertEquals("1.1.x patch must match versionCode", versionCode, versionPatch)
        assertTrue("buildConfig phải được kích hoạt", content.contains("buildConfig = true"))
    }

    // =========================================================================
    // FAKE IN-MEMORY IMPLEMENTATIONS CHO CONTEXT & SHARED PREFERENCES
    // =========================================================================

    private class FakeContext : android.content.ContextWrapper(null) {
        private val prefs = mutableMapOf<String, FakeSharedPreferences>()

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            return prefs.getOrPut(name) { FakeSharedPreferences() }
        }
    }

    private class FakeSharedPreferences : SharedPreferences {
        val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String> ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val parent: FakeSharedPreferences) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val toRemove = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) {
                    pending[key] = value
                    toRemove.remove(key)
                }
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) {
                    pending[key] = values
                    toRemove.remove(key)
                }
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) {
                    pending[key] = value
                    toRemove.remove(key)
                }
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) {
                    pending[key] = value
                    toRemove.remove(key)
                }
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) {
                    pending[key] = value
                    toRemove.remove(key)
                }
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) {
                    pending[key] = value
                    toRemove.remove(key)
                }
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) {
                    toRemove.add(key)
                    pending.remove(key)
                }
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                pending.clear()
                toRemove.clear()
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) {
                    parent.map.clear()
                }
                for (key in toRemove) {
                    parent.map.remove(key)
                }
                for ((key, value) in pending) {
                    parent.map[key] = value
                }
            }
        }
    }
}
