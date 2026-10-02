package com.example.nhumonglenh

import com.example.nhumonglenh.data.remote.NetworkConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
