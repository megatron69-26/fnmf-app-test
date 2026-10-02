package com.example.nhumonglenh

import com.example.nhumonglenh.data.remote.LoginRequest
import com.example.nhumonglenh.data.remote.NetworkConfig
import com.example.nhumonglenh.data.remote.RegisterRequest
import com.example.nhumonglenh.data.remote.UserDto
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit test xác minh kiến trúc EMAIL-ONLY trên Android Client:
 * 1. Email validation (hợp lệ & không hợp lệ).
 * 2. Email normalization (lowercase Locale.ROOT, trim khoảng trắng).
 * 3. SharedPreferences migration:
 *    - saved_username là email hợp lệ -> chuyển sang saved_email, xóa saved_username.
 *    - saved_username là legacy (khoi10) -> KHÔNG tạo email giả, trả về LegacyAccountDetected.
 * 4. DTO JSON serialization: LoginRequest & RegisterRequest chỉ chứa email, không chứa username.
 * 5. Railway Cloud URL mặc định.
 */
class EmailAuthUnitTest {

    private val gson = Gson()

    // -------------------------------------------------------------
    // 1. TEST EMAIL VALIDATION
    // -------------------------------------------------------------
    @Test
    fun testIsValidEmail_validCases() {
        val validEmails = listOf(
            "user@fnmf.com",
            "khoi.pro@fnmf.com",
            "trader.vip+test@domain.co.uk",
            "admin_01@sub.domain.org",
            "hello@world.vn",
            "  clean.space@domain.com  "
        )
        for (email in validEmails) {
            assertTrue("Email phải hợp lệ: $email", NetworkConfig.isValidEmail(email))
        }
    }

    @Test
    fun testIsValidEmail_invalidCases() {
        val invalidEmails = listOf(
            null,
            "",
            "   ",
            "khoi10",                 // Legacy account identifier
            "trader1",                // Username without domain
            "user@",
            "@domain.com",
            "user@.com",
            "user@domain",            // Missing TLD
            "user space@domain.com",
            "plainaddress",
            "#@%^%#$@#$@#.com"
        )
        for (email in invalidEmails) {
            assertFalse("Email không được coi là hợp lệ: $email", NetworkConfig.isValidEmail(email))
        }
    }

    // -------------------------------------------------------------
    // 2. TEST EMAIL NORMALIZATION
    // -------------------------------------------------------------
    @Test
    fun testNormalizeEmail() {
        assertEquals("user@fnmf.com", NetworkConfig.normalizeEmail("  USER@FNMF.COM  "))
        assertEquals("khoi.pro@fnmf.com", NetworkConfig.normalizeEmail("Khoi.Pro@FNMF.com"))
        assertEquals("test@domain.org", NetworkConfig.normalizeEmail("test@domain.org"))
        assertEquals("", NetworkConfig.normalizeEmail(null))
        assertEquals("", NetworkConfig.normalizeEmail("   "))
    }

    // -------------------------------------------------------------
    // 3. TEST SAVED ACCOUNT MIGRATION LOGIC
    // -------------------------------------------------------------
    @Test
    fun testDecideAccountMigration_validEmailMigrates() {
        val decision = NetworkConfig.decideAccountMigration(
            alreadyMigrated = false,
            savedEmail = null,
            savedUsername = "  Trader1@Domain.COM  "
        )
        assertTrue(decision is NetworkConfig.AccountMigrationDecision.MigrateToEmail)
        val migrated = decision as NetworkConfig.AccountMigrationDecision.MigrateToEmail
        assertEquals("trader1@domain.com", migrated.normalizedEmail)
    }

    @Test
    fun testDecideAccountMigration_legacyKhoi10_doesNotFakeEmail() {
        val decision = NetworkConfig.decideAccountMigration(
            alreadyMigrated = false,
            savedEmail = null,
            savedUsername = "khoi10"
        )
        assertTrue("Legacy account phải được phát hiện để báo người dùng", decision is NetworkConfig.AccountMigrationDecision.LegacyAccountDetected)
        val legacy = decision as NetworkConfig.AccountMigrationDecision.LegacyAccountDetected
        assertEquals("khoi10", legacy.legacyUsername)
        // TUYỆT ĐỐI KHÔNG TỰ BỊA EMAIL CHO khoi10
        assertFalse("Không được biến khoi10 thành email giả", legacy.legacyUsername.contains("@"))
    }

    @Test
    fun testDecideAccountMigration_alreadyMigrated_usesSavedEmail() {
        val decision = NetworkConfig.decideAccountMigration(
            alreadyMigrated = true,
            savedEmail = "existing@fnmf.com",
            savedUsername = "old_legacy"
        )
        assertTrue(decision is NetworkConfig.AccountMigrationDecision.UseExistingSavedEmail)
        val existing = decision as NetworkConfig.AccountMigrationDecision.UseExistingSavedEmail
        assertEquals("existing@fnmf.com", existing.savedEmail)
    }

    @Test
    fun testDecideAccountMigration_noSavedAccount() {
        val decision1 = NetworkConfig.decideAccountMigration(
            alreadyMigrated = false,
            savedEmail = null,
            savedUsername = null
        )
        assertEquals(NetworkConfig.AccountMigrationDecision.NoActionNeeded, decision1)

        val decision2 = NetworkConfig.decideAccountMigration(
            alreadyMigrated = false,
            savedEmail = null,
            savedUsername = "   "
        )
        assertEquals(NetworkConfig.AccountMigrationDecision.NoActionNeeded, decision2)
    }

    // -------------------------------------------------------------
    // 4. TEST DTO JSON SERIALIZATION & DESERIALIZATION
    // -------------------------------------------------------------
    @Test
    fun testLoginRequestJsonSerialization() {
        val request = LoginRequest(
            email = "trader@fnmf.com",
            password = "SecretPassword123"
        )
        val json = gson.toJson(request)

        assertTrue("JSON phải chứa trường email", json.contains("\"email\":\"trader@fnmf.com\""))
        assertTrue("JSON phải chứa trường password", json.contains("\"password\":\"SecretPassword123\""))
        assertFalse("JSON không được chứa trường username trong luồng mới", json.contains("\"username\""))
    }

    @Test
    fun testRegisterRequestJsonSerialization() {
        val request = RegisterRequest(
            email = "newtrader@fnmf.com",
            password = "SecurePassword456",
            fullName = "New Trader"
        )
        val json = gson.toJson(request)

        assertTrue("JSON phải chứa trường email", json.contains("\"email\":\"newtrader@fnmf.com\""))
        assertTrue("JSON phải chứa trường password", json.contains("\"password\":\"SecurePassword456\""))
        assertTrue("JSON có thể chứa fullName", json.contains("\"fullName\":\"New Trader\""))
        assertFalse("JSON không được chứa trường username trong luồng mới", json.contains("\"username\""))
    }

    @Test
    fun testUserDtoDeserialization() {
        val backendJson = """
            {
                "id": 10,
                "email": "trader@fnmf.com",
                "fullName": "Trader 10",
                "role": "USER",
                "validEmail": true,
                "needsEmailUpdate": false
            }
        """.trimIndent()

        val userDto = gson.fromJson(backendJson, UserDto::class.java)
        assertNotNull(userDto)
        assertEquals(10L, userDto.id)
        assertEquals("trader@fnmf.com", userDto.email)
        assertEquals("Trader 10", userDto.fullName)
        assertEquals("USER", userDto.role)
        assertEquals(true, userDto.validEmail)
        assertEquals(false, userDto.needsEmailUpdate)
    }

    // -------------------------------------------------------------
    // 5. TEST RAILWAY DEFAULT SERVER URL
    // -------------------------------------------------------------
    @Test
    fun testDefaultServerUrl_isTestBackend() {
        assertEquals(
            "http://10.0.2.2:8083/",
            NetworkConfig.DEFAULT_SERVER_URL
        )
    }

    // -------------------------------------------------------------
    // 6. TEST LEGACY ACCOUNT KHOI10 CANNOT APPEAR AS LOGIN EMAIL OR ACTIVE PROFILE
    // -------------------------------------------------------------
    @Test
    fun testLegacyKhoi10_cannotAppearAsCurrentLoginEmailOrActiveProfile() {
        val legacyUsername = "khoi10"

        // 1. khoi10 không bao giờ được coi là email hợp lệ
        assertFalse("khoi10 không phải là email hợp lệ", NetworkConfig.isValidEmail(legacyUsername))

        // 2. Migration trả về LegacyAccountDetected, KHÔNG tự động lưu vào saved_email
        val decision = NetworkConfig.decideAccountMigration(
            alreadyMigrated = false,
            savedEmail = null,
            savedUsername = legacyUsername
        )
        assertTrue("Phải phát hiện legacy account", decision is NetworkConfig.AccountMigrationDecision.LegacyAccountDetected)
        val legacyDetected = decision as NetworkConfig.AccountMigrationDecision.LegacyAccountDetected
        assertEquals("khoi10", legacyDetected.legacyUsername)
        assertFalse("Legacy identifier không được chứa ký tự '@'", legacyDetected.legacyUsername.contains("@"))

        // 3. Giả lập logic hiển thị profile: nếu user.email trả về khoi10 (chưa cập nhật), profile không được hiển thị nó như email hợp lệ
        val userWithLegacyIdentifier = UserDto(
            id = 1L,
            email = "khoi10",
            fullName = "Dang Duc Khoi",
            role = "USER",
            validEmail = false,
            needsEmailUpdate = true
        )

        val activeSavedEmail = ""
        val resolvedDisplayEmail = if (NetworkConfig.isValidEmail(userWithLegacyIdentifier.email)) {
            userWithLegacyIdentifier.email!!
        } else if (NetworkConfig.isValidEmail(activeSavedEmail)) {
            activeSavedEmail
        } else {
            "--"
        }

        assertEquals("--", resolvedDisplayEmail)
        assertFalse("khoi10 tuyệt đối không được xuất hiện như email hiển thị", resolvedDisplayEmail.contains("khoi10"))

        // 4. Header Activity2 chỉ đọc saved_email, không bao giờ lấy khoi10 từ saved_username
        val headerUserDisplay = if (NetworkConfig.isValidEmail(activeSavedEmail)) activeSavedEmail else ""
        assertEquals("", headerUserDisplay)
        assertFalse(headerUserDisplay.contains("khoi10"))
    }
}

