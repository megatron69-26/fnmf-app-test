package com.example.nhumonglenh

import android.content.SharedPreferences
import com.example.nhumonglenh.data.remote.NetworkConfig
import org.junit.Assert.*
import org.junit.Test

class NetworkConfigUnitTest {

    @Test
    fun testDefaultServerUrl() {
        assertEquals("http://10.0.2.2:8083/", NetworkConfig.DEFAULT_SERVER_URL)
        assertEquals(100, NetworkConfig.SERVER_CONFIG_VERSION)
    }

    @Test
    fun testDecideServerUrl_nullOrBlank() {
        val (url1, ver1) = NetworkConfig.decideServerUrl(null, 0)
        assertEquals(NetworkConfig.DEFAULT_SERVER_URL, url1)
        assertEquals(100, ver1)

        val (url2, ver2) = NetworkConfig.decideServerUrl("", 0)
        assertEquals(NetworkConfig.DEFAULT_SERVER_URL, url2)
        assertEquals(100, ver2)

        val (url3, ver3) = NetworkConfig.decideServerUrl("   ", 1)
        assertEquals(NetworkConfig.DEFAULT_SERVER_URL, url3)
        assertEquals(100, ver3)
    }

    @Test
    fun testDecideServerUrl_strictlyBlocksRailwayProduction() {
        val railwayUrls = listOf(
            "https://fnmf-backend-production.up.railway.app/",
            "https://fnmf-backend-production.up.railway.app",
            "http://fnmf-backend-production.up.railway.app:8080/",
            "https://my-service.railway.app/"
        )

        for (url in railwayUrls) {
            val (resolved, ver) = NetworkConfig.decideServerUrl(url, 0)
            assertEquals("Railway production must be strictly blocked and reverted to test default: $url",
                NetworkConfig.DEFAULT_SERVER_URL, resolved)
            assertEquals(100, ver)
        }
    }

    @Test
    fun testDecideServerUrl_preservesCustomTestServers() {
        val testServers = listOf(
            "http://192.168.1.15:8083/" to "http://192.168.1.15:8083/",
            "http://192.168.1.15:8083" to "http://192.168.1.15:8083/",
            "http://10.0.2.2:8083/" to "http://10.0.2.2:8083/",
            "http://localhost:8083" to "http://localhost:8083/",
            "https://test-node10.ngrok-free.app/" to "https://test-node10.ngrok-free.app/"
        )

        for ((input, expected) in testServers) {
            val (resolved, ver) = NetworkConfig.decideServerUrl(input, 50)
            assertEquals("Custom test URL should be accepted: $input", expected, resolved)
            assertEquals(100, ver)
        }
    }

    @Test
    fun testDecideServerUrl_corruptedOrWeirdStrings() {
        val invalidInputs = listOf(
            "not_a_url",
            "ftp://bad-protocol.com/",
            "just random text",
            "://missing-scheme"
        )

        for (invalid in invalidInputs) {
            val (resolved, ver) = NetworkConfig.decideServerUrl(invalid, 0)
            assertEquals("Invalid input should fallback to test default: $invalid", NetworkConfig.DEFAULT_SERVER_URL, resolved)
            assertEquals(100, ver)
        }
    }

    @Test
    fun testNormalizeUrl() {
        assertEquals("https://example.com/", NetworkConfig.normalizeUrl("https://example.com/"))
        assertEquals("https://example.com/", NetworkConfig.normalizeUrl("https://example.com"))
        assertEquals("https://example.com/", NetworkConfig.normalizeUrl("  https://example.com  "))
        assertEquals(NetworkConfig.DEFAULT_SERVER_URL, NetworkConfig.normalizeUrl(""))
        assertEquals(NetworkConfig.DEFAULT_SERVER_URL, NetworkConfig.normalizeUrl("   "))
        assertEquals(NetworkConfig.DEFAULT_SERVER_URL, NetworkConfig.normalizeUrl("https://fnmf-backend-production.up.railway.app/"))
    }

    @Test
    fun testIsBlockedProductionUrl() {
        assertTrue(NetworkConfig.isBlockedProductionUrl("https://fnmf-backend-production.up.railway.app/"))
        assertTrue(NetworkConfig.isBlockedProductionUrl("http://custom.railway.app"))
        assertFalse(NetworkConfig.isBlockedProductionUrl("http://192.168.1.15:8083/"))
        assertFalse(NetworkConfig.isBlockedProductionUrl("http://localhost:8083/"))
        assertFalse(NetworkConfig.isBlockedProductionUrl(null))
    }

    @Test
    fun testValidateAndSetTestServerUrl_flow() {
        val prefs = TestFakePrefs()

        // 1. Initial state: not configured
        assertFalse(NetworkConfig.isTestServerConfigured(prefs))

        // 2. Reject empty
        val (resEmpty, errEmpty) = NetworkConfig.validateAndSetTestServerUrl(prefs, "")
        assertFalse(resEmpty)
        assertNotNull(errEmpty)
        assertFalse(NetworkConfig.isTestServerConfigured(prefs))

        // 3. Reject Railway production
        val (resRailway, errRailway) = NetworkConfig.validateAndSetTestServerUrl(prefs, "https://fnmf-backend-production.up.railway.app/")
        assertFalse(resRailway)
        assertTrue(errRailway!!.contains("Railway Production"))
        assertFalse(NetworkConfig.isTestServerConfigured(prefs))

        // 4. Accept valid HTTPS tunnel URL
        val (resHttps, warnHttps) = NetworkConfig.validateAndSetTestServerUrl(prefs, "https://fnmf-test.mydomain.com")
        assertTrue(resHttps)
        assertNull(warnHttps) // HTTPS has no cleartext warning
        assertTrue(NetworkConfig.isTestServerConfigured(prefs))
        assertEquals("https://fnmf-test.mydomain.com/", prefs.getString(NetworkConfig.KEY_SERVER_URL, null))

        // 5. Accept HTTP LAN URL with warning
        val (resHttp, warnHttp) = NetworkConfig.validateAndSetTestServerUrl(prefs, "http://192.168.1.50:8083")
        assertTrue(resHttp)
        assertNotNull(warnHttp)
        assertTrue(warnHttp!!.contains("cleartext"))
        assertTrue(NetworkConfig.isTestServerConfigured(prefs))
        assertEquals("http://192.168.1.50:8083/", prefs.getString(NetworkConfig.KEY_SERVER_URL, null))
    }

    private class TestFakePrefs : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class Editor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor { temp[key ?: ""] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { temp[key ?: ""] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { temp[key ?: ""] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { temp[key ?: ""] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { temp[key ?: ""] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { temp[key ?: ""] = null; return this }
            override fun clear(): SharedPreferences.Editor { target.clear(); return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                for ((k, v) in temp) {
                    if (v == null) target.remove(k) else target[k] = v
                }
            }
        }
    }
}
