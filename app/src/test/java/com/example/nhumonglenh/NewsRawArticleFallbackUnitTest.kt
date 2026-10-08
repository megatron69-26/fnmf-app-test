package com.example.nhumonglenh

import com.example.nhumonglenh.data.local.NewsEntity
import com.example.nhumonglenh.ui.news.News
import org.junit.Assert.*
import org.junit.Test

class NewsRawArticleFallbackUnitTest {

    @Test
    fun testNews_whenQwenFails_effectiveTitleAndSummaryFallBackToOriginal() {
        val rawNews = News(
            id = "news-raw-1",
            title = "Federal Reserve Holds Interest Rates Steady",
            summary = "The central bank decided to keep its key borrowing rate unchanged at 5.25%-5.50%.",
            source = "CoinDesk",
            publishedAt = "2026-10-08 10:00:00",
            imageUrl = "https://example.com/fed.jpg",
            sentiment = "neutral",
            confidence = 80,
            bulletPoints = listOf(
                "Interest rates held steady",
                "Inflation cools towards target"
            ),
            author = "Jane Doe",
            link = "https://example.com/fed",
            originalTitle = "Federal Reserve Holds Interest Rates Steady",
            originalSummary = "The central bank decided to keep its key borrowing rate unchanged at 5.25%-5.50%.",
            displayTitleVi = null, // Qwen thất bại
            displaySummaryVi = null,
            bulletPointsVi = null,
            publisher = "CoinDesk"
        )

        // Khi Qwen lỗi: Người dùng vẫn đọc được trọn vẹn tiêu đề và tóm tắt gốc của bài báo
        assertEquals("Federal Reserve Holds Interest Rates Steady", rawNews.getEffectiveTitle())
        assertEquals("The central bank decided to keep its key borrowing rate unchanged at 5.25%-5.50%.", rawNews.getEffectiveSummary())
        assertEquals(2, rawNews.getEffectiveBullets().size)
        assertEquals("Interest rates held steady", rawNews.getEffectiveBullets()[0])
        assertEquals("CoinDesk", rawNews.getEffectivePublisher())
    }

    @Test
    fun testNews_whenQwenSucceeds_effectiveTitleAndSummaryUseVietnamese() {
        val localizedNews = News(
            id = "news-qwen-1",
            title = "Bitcoin Surges Past Resistance",
            summary = "BTC broke key technical levels as ETF inflows continued.",
            source = "CoinDesk",
            publishedAt = "2026-10-08 11:00:00",
            imageUrl = "https://example.com/btc.jpg",
            sentiment = "bullish",
            confidence = 90,
            bulletPoints = listOf("BTC breaks resistance"),
            author = "Staff",
            link = "https://example.com/btc",
            originalTitle = "Bitcoin Surges Past Resistance",
            originalSummary = "BTC broke key technical levels as ETF inflows continued.",
            displayTitleVi = "Bitcoin vượt qua ngưỡng cản kỹ thuật quan trọng",
            displaySummaryVi = "Giá Bitcoin tăng mạnh nhờ dòng vốn ETF tiếp tục đổ vào thị trường.",
            bulletPointsVi = listOf(
                "Dòng vốn ETF duy trì ở mức cao kỷ lục.",
                "Thanh khoản thị trường phái sinh phục hồi tích cực."
            ),
            publisher = "CoinDesk"
        )

        // Khi Qwen thành công: Hiển thị bản tiếng Việt đã được kiểm định chất lượng
        assertEquals("Bitcoin vượt qua ngưỡng cản kỹ thuật quan trọng", localizedNews.getEffectiveTitle())
        assertEquals("Giá Bitcoin tăng mạnh nhờ dòng vốn ETF tiếp tục đổ vào thị trường.", localizedNews.getEffectiveSummary())
        assertEquals(2, localizedNews.getEffectiveBullets().size)
        assertEquals("Dòng vốn ETF duy trì ở mức cao kỷ lục.", localizedNews.getEffectiveBullets()[0])
    }

    @Test
    fun testNewsEntity_rawArticlePreservationCondition() {
        val rawEntity = NewsEntity(
            "news-10",
            "Ethereum L2 Activity Hits New High",
            "https://coindesk.com/eth-l2",
            1725800000000L,
            "CoinDesk",
            "Staff",
            "2026-10-08 12:00:00",
            "",
            "Layer 2 networks processed over 100 million transactions this week.",
            "bullish",
            85,
            "100M transactions processed",
            "Ethereum L2 Activity Hits New High",
            "Layer 2 networks processed over 100 million transactions this week.",
            "", // Chưa có displayTitleVi
            "",
            "",
            "CoinDesk"
        )

        // Bản ghi gốc có tiêu đề và URL không bị coi là rác
        assertFalse(rawEntity.title.isBlank() && rawEntity.originalTitle.isBlank())
        assertTrue(rawEntity.title.isNotBlank())
        assertTrue(rawEntity.url.isNotBlank())
    }
}
