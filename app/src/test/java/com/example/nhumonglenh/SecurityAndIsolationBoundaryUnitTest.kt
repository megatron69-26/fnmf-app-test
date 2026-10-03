package com.example.nhumonglenh

import com.example.nhumonglenh.data.remote.NetworkConfig
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Kiểm thử đơn vị các ranh giới bảo mật cho FNMF App Test:
 * 1. Chặn tuyệt đối kết nối tới Railway Production.
 * 2. Cấu hình bảo vệ mạng: Chỉ cho phép cleartext với loopback (127.0.0.1, localhost, 10.0.2.2) trong debug;
 *    bản release tuyệt đối cấm HTTP cleartext.
 * 3. Không chứa khóa bảo mật, mật khẩu hay token ghi cứng trong tài nguyên ứng dụng.
 */
class SecurityAndIsolationBoundaryUnitTest {

    @Test
    fun testRailwayProductionStrictlyBlocked() {
        val railwayUrls = listOf(
            "https://fnmf-backend-production.up.railway.app",
            "https://fnmf-backend-production.up.railway.app/",
            "http://fnmf-backend-production.up.railway.app/api/auth/login",
            "https://my-app.railway.app",
            "http://custom.railway.app:8080/"
        )

        for (url in railwayUrls) {
            assertTrue("URL $url phải bị nhận diện là Railway Production bị cấm",
                NetworkConfig.isBlockedProductionUrl(url))

            val normalized = NetworkConfig.normalizeUrl(url)
            assertEquals("URL bị cấm phải bị cưỡng chế fallback về DEFAULT_SERVER_URL thử nghiệm",
                NetworkConfig.DEFAULT_SERVER_URL, normalized)
        }
    }

    @Test
    fun testValidTestServerUrlsAllowed() {
        val validUrls = listOf(
            "http://10.0.2.2:8083/",
            "http://127.0.0.1:8083/",
            "https://my-tunnel.trycloudflare.com/"
        )

        for (url in validUrls) {
            assertFalse("URL thử nghiệm hợp lệ không được bị block", NetworkConfig.isBlockedProductionUrl(url))
            assertTrue("URL $url phải được coi là hợp lệ", NetworkConfig.isValidUrl(url))
        }
    }

    @Test
    fun testDebugNetworkSecurityConfig_permitsOnlyLoopbackCleartext() {
        val configFile = File("src/debug/res/xml/network_security_config.xml")
        assertTrue("File debug network_security_config.xml phải tồn tại", configFile.exists())

        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(configFile)

        val domainNodes = doc.getElementsByTagName("domain")
        val allowedDomains = mutableListOf<String>()
        for (i in 0 until domainNodes.length) {
            allowedDomains.add(domainNodes.item(i).textContent.trim())
        }

        assertEquals("Debug chỉ được phép định nghĩa đúng 3 domain loopback", 3, allowedDomains.size)
        assertTrue("Phải chứa 127.0.0.1", allowedDomains.contains("127.0.0.1"))
        assertTrue("Phải chứa localhost", allowedDomains.contains("localhost"))
        assertTrue("Phải chứa 10.0.2.2", allowedDomains.contains("10.0.2.2"))
    }

    @Test
    fun testReleaseNetworkSecurityConfig_strictlyForbidsCleartext() {
        val configFile = File("src/release/res/xml/network_security_config.xml")
        assertTrue("File release network_security_config.xml phải tồn tại", configFile.exists())

        val content = configFile.readText()
        assertTrue("Release phải cấm cleartext traffic",
            content.contains("cleartextTrafficPermitted=\"false\""))
        assertFalse("Release tuyệt đối không có domain cleartextTrafficPermitted=true",
            content.contains("cleartextTrafficPermitted=\"true\""))
    }

    @Test
    fun testMainManifest_declaresUsesCleartextTrafficFalse() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue("Main AndroidManifest.xml phải tồn tại", manifestFile.exists())

        val content = manifestFile.readText()
        assertTrue("Manifest chính phải khai báo android:usesCleartextTraffic=\"false\"",
            content.contains("android:usesCleartextTraffic=\"false\""))
    }

    @Test
    fun testStringsResource_containsNoHardcodedSecrets() {
        val stringsFile = File("src/main/res/values/strings.xml")
        assertTrue("strings.xml phải tồn tại", stringsFile.exists())

        val content = stringsFile.readText().lowercase()
        assertFalse("Không được chứa mật khẩu trong strings.xml", content.contains("password123"))
        assertFalse("Không được chứa jwt secret trong strings.xml", content.contains("jwt_secret"))
        assertFalse("Không được trỏ trực tiếp Railway trong strings.xml", content.contains("railway.app"))
    }
}
