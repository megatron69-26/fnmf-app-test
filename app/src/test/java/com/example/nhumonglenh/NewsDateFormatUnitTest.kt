package com.example.nhumonglenh

import com.example.nhumonglenh.ui.news.NewsAdapter
import com.example.nhumonglenh.data.repository.NewsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class NewsDateFormatUnitTest {

    @Test
    fun minutePrecisionTimestampPreservesUtcPublicationTime() {
        assertEquals(1790655420000L, NewsRepository.parseTimeToEpoch("2026-09-29T04:17"))
        assertEquals(
            NewsRepository.parseTimeToEpoch("2026-09-28T10:15:00Z"),
            NewsRepository.parseTimeToEpoch("2026-09-28T10:15")
        )
    }

    @Test
    fun cachedZeroEpochArticlesSortAheadOfSeptember23() {
        val rows = listOf(
            "2026-09-23T11:10:08" to NewsRepository.parseTimeToEpoch("2026-09-23T11:10:08"),
            "2026-09-28T10:15" to 0L,
            "2026-09-29T04:17" to 0L
        )
        assertEquals(
            listOf("2026-09-29T04:17", "2026-09-28T10:15", "2026-09-23T11:10:08"),
            rows.sortedByDescending { NewsRepository.effectivePublishedEpoch(it.first, it.second) }.map { it.first }
        )
    }

    @Test
    fun unreadableRawDateKeepsStoredEpoch() {
        assertEquals(123456L, NewsRepository.effectivePublishedEpoch("invalid", 123456L))
        assertEquals(123456L, NewsRepository.effectivePublishedEpoch(null, 123456L))
    }

    @Test
    fun testFormatReleaseDate_alphaVantageStandard() {
        val raw = "20260908T031438"
        val formatted = NewsAdapter.formatReleaseDate(raw)
        assertEquals("08/09/2026", formatted)
    }

    @Test
    fun testFormatReleaseDate_nullOrEmpty() {
        assertEquals("--", NewsAdapter.formatReleaseDate(null))
        assertEquals("--", NewsAdapter.formatReleaseDate(""))
        assertEquals("--", NewsAdapter.formatReleaseDate("   "))
    }

    @Test
    fun testFormatReleaseDate_malformed() {
        assertEquals("--", NewsAdapter.formatReleaseDate("invalid_date"))
        assertEquals("--", NewsAdapter.formatReleaseDate("2026/09/08"))
        assertEquals("--", NewsAdapter.formatReleaseDate("abcTxyz"))
        assertEquals("--", NewsAdapter.formatReleaseDate("123"))
    }

    @Test
    fun testFormatReleaseDate_isoFallback() {
        assertEquals("08/09/2026", NewsAdapter.formatReleaseDate("2026-09-08T03:14:38"))
        assertEquals("08/09/2026", NewsAdapter.formatReleaseDate("2026-09-08"))
    }
}
