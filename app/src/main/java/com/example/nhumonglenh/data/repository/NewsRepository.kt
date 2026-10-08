package com.example.nhumonglenh.data.repository

import android.content.Context
import com.example.nhumonglenh.data.local.AiAnalysisEntity
import com.example.nhumonglenh.data.local.AppDatabase
import com.example.nhumonglenh.data.local.NewsEntity
import com.example.nhumonglenh.ui.common.AutoRefreshScheduler
import com.example.nhumonglenh.ui.news.ApiClient
import com.example.nhumonglenh.ui.news.News
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single Source of Truth cho News và AI Analysis:
 * 1. Online: Đồng bộ từ /api/news/sync, lưu đồng thời vào Room DB (news_table & ai_analysis_table).
 *    Sau khi ghi Room thành công, đọc ngược lại từ Room để hiển thị lên UI.
 *    Nếu ghi Room thất bại -> trả CacheWriteFailure, tuyệt đối không trả cache cũ và nói là isFromCache=false.
 * 2. Offline: Đọc dữ liệu đã lưu trong Room DB (CacheFallback).
 * 3. Phân biệt rõ: SyncSuccess, CacheFallback, CacheWriteFailure, Empty.
 */
class NewsRepository internal constructor(private val context: Context) {

    sealed class NewsResult {
        data class SyncSuccess(
            val news: List<News>,
            val isStale: Boolean = false,
            val dataAsOf: String? = null,
            val latestPublishedAt: String? = null
        ) : NewsResult()
        data class CacheHit(
            val news: List<News>,
            val isStale: Boolean = false,
            val dataAsOf: String? = null,
            val latestPublishedAt: String? = null
        ) : NewsResult()
        data class CacheFallback(val news: List<News>, val reason: String, val isStale: Boolean = true) : NewsResult()
        data class CacheWriteFailure(val error: Throwable) : NewsResult()
        data class Empty(val message: String) : NewsResult()
    }

    sealed class NewsRefreshResult {
        data class Success(
            val news: List<News>,
            val remainingRefreshes: Int?,
            val quotaDate: String?,
            val isStale: Boolean = false,
            val dataAsOf: String? = null,
            val latestPublishedAt: String? = null
        ) : NewsRefreshResult()
        data class DegradedOrEmpty(
            val status: String,
            val message: String,
            val cachedNews: List<News>,
            val remainingRefreshes: Int?,
            val quotaDate: String?,
            val isStale: Boolean = false,
            val dataAsOf: String? = null,
            val latestPublishedAt: String? = null
        ) : NewsRefreshResult()
        data class QuotaExhausted(val message: String, val cachedNews: List<News>) : NewsRefreshResult()
        data class Unauthorized(val message: String) : NewsRefreshResult()
        data class ServerError(val message: String, val cachedNews: List<News>, val remainingRefreshes: Int? = null) : NewsRefreshResult()
        data class NetworkError(val message: String, val cachedNews: List<News>) : NewsRefreshResult()
    }

    /**
     * SharedPreferences lưu metadata đồng bộ tin tức bền vững qua app restart.
     */
    private val syncPrefs by lazy {
        context.getSharedPreferences(PREFS_NEWS_SYNC, Context.MODE_PRIVATE)
    }

    fun getLastSyncTime(): Long {
        if (lastSyncTimeMs > 0L) return lastSyncTimeMs
        return syncPrefs.getLong(KEY_LAST_SYNC_TIME, 0L)
    }

    fun isLastSyncStale(): Boolean = syncPrefs.getBoolean(KEY_LAST_SYNC_STALE, false)
    fun getLastDataAsOf(): String? = syncPrefs.getString(KEY_LAST_DATA_AS_OF, null)
    fun getLastLatestPublishedAt(): String? = syncPrefs.getString(KEY_LAST_LATEST_PUBLISHED_AT, null)

    fun recordSyncMetadata(timeMs: Long, isStale: Boolean, dataAsOf: String?, latestPublishedAt: String?) {
        lastSyncTimeMs = timeMs
        syncPrefs.edit()
            .putLong(KEY_LAST_SYNC_TIME, timeMs)
            .putBoolean(KEY_LAST_SYNC_STALE, isStale)
            .putString(KEY_LAST_DATA_AS_OF, dataAsOf)
            .putString(KEY_LAST_LATEST_PUBLISHED_AT, latestPublishedAt)
            .apply()
    }

    fun clearSyncMetadata() {
        lastSyncTimeMs = 0L
        syncPrefs.edit().clear().apply()
    }

    fun getScheduler(): AutoRefreshScheduler {
        return AutoRefreshScheduler(
            prefs = syncPrefs,
            keyNextAttempt = KEY_NEXT_AUTO_ATTEMPT_AT,
            keyFailures = KEY_AUTO_SYNC_FAILURES,
            cycleMs = AUTO_SYNC_THROTTLE_MS,
            baseBackoffMs = AUTO_SYNC_BASE_BACKOFF_MS,
            maxBackoffMs = AUTO_SYNC_MAX_BACKOFF_MS
        )
    }

    fun getOrInitNextAutoAttemptAt(now: Long = System.currentTimeMillis()): Long {
        val lastSync = getLastSyncTime()
        return getScheduler().getOrInitNextAttemptAt(now, lastSync)
    }

    fun recordAutoAttemptSuccess(now: Long = System.currentTimeMillis()): Long {
        return getScheduler().recordSuccess(now)
    }

    fun recordAutoAttemptFailure(now: Long = System.currentTimeMillis()): Long {
        return getScheduler().recordFailure(now)
    }

    /**
     * Đọc ngay tức thì toàn bộ tin tức đã lưu trong Room Database.
     */
    fun getCachedNews(): List<News> {
        val db = AppDatabase.getInstance(context)
        return readNewsFromRoom(db.newsDao())
    }

    /**
     * Đồng bộ tin tức nền với Single-Flight và Throttle 15 phút.
     */
    suspend fun syncNewsInBackground(force: Boolean = false): NewsResult = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val newsDao = db.newsDao()

        val now = System.currentTimeMillis()
        val lastSync = getLastSyncTime()
        if (!force && (now - lastSync < AUTO_SYNC_THROTTLE_MS)) {
            val cached = readNewsFromRoom(newsDao)
            if (cached.isNotEmpty()) {
                return@withContext NewsResult.CacheHit(
                    news = cached,
                    isStale = isLastSyncStale(),
                    dataAsOf = getLastDataAsOf(),
                    latestPublishedAt = getLastLatestPublishedAt()
                )
            }
        }

        syncMutex.withLock {
            val nowLocked = System.currentTimeMillis()
            val lastSyncLocked = getLastSyncTime()
            if (!force && (nowLocked - lastSyncLocked < AUTO_SYNC_THROTTLE_MS)) {
                val cached = readNewsFromRoom(newsDao)
                if (cached.isNotEmpty()) {
                    return@withContext NewsResult.CacheHit(
                        news = cached,
                        isStale = isLastSyncStale(),
                        dataAsOf = getLastDataAsOf(),
                        latestPublishedAt = getLastLatestPublishedAt()
                    )
                }
            }

            val remoteResult = runCatching {
                ApiClient.service(context).syncNews()
            }

            if (remoteResult.isSuccess) {
                val remoteResponse = remoteResult.getOrNull()
                val remoteNews = remoteResponse?.data ?: emptyList()
                val isStale = remoteResponse?.stale ?: false
                val dataAsOf = remoteResponse?.dataAsOf
                val latestPublishedAt = remoteResponse?.latestPublishedAt
                val serverMsg = remoteResponse?.message?.takeIf { it.isNotBlank() } ?: "Chưa có bản tin mới"

                if (remoteNews.isNotEmpty()) {
                    try {
                        persistNewsToRoom(newsDao, remoteNews)
                    } catch (e: Exception) {
                        android.util.Log.e("NewsRepository", "Lỗi lưu cache Room DB: ${e.message}", e)
                        return@withContext NewsResult.CacheWriteFailure(e)
                    }
                    val roomNews = readNewsFromRoom(newsDao)
                    return@withContext if (roomNews.isNotEmpty()) {
                        // CHỈ ghi metadata sau khi persist và đọc lại Room thành công!
                        recordSyncMetadata(System.currentTimeMillis(), isStale, dataAsOf, latestPublishedAt)
                        NewsResult.SyncSuccess(
                            news = roomNews,
                            isStale = isStale,
                            dataAsOf = dataAsOf,
                            latestPublishedAt = latestPublishedAt
                        )
                    } else {
                        NewsResult.Empty(serverMsg)
                    }
                } else {
                    // Response rỗng/degraded: KHÔNG ghi/cập nhật last_sync_time_ms!
                    val roomNews = readNewsFromRoom(newsDao)
                    return@withContext if (roomNews.isNotEmpty()) {
                        NewsResult.CacheFallback(roomNews, serverMsg, isStale = true)
                    } else {
                        NewsResult.Empty(serverMsg)
                    }
                }
            } else {
                val err = remoteResult.exceptionOrNull()
                android.util.Log.w("NewsRepository", "Không thể tải tin tức trực tuyến: ${err?.message}", err)
                val cachedList = readNewsFromRoom(newsDao)
                if (cachedList.isNotEmpty()) {
                    val errorMsg = "Mất kết nối máy chủ. Đang hiển thị tin tức đã lưu trên thiết bị."
                    return@withContext NewsResult.CacheFallback(cachedList, errorMsg)
                }
                return@withContext NewsResult.Empty("Chưa có bản tin mới")
            }
        }
    }

    suspend fun getNews(): NewsResult = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val newsDao = db.newsDao()

        // 1. Thử đồng bộ trực tuyến từ Backend
        val remoteResult = runCatching {
            ApiClient.service(context).syncNews()
        }

        if (remoteResult.isSuccess) {
            val remoteResponse = remoteResult.getOrNull()
            val remoteNews = remoteResponse?.data ?: emptyList()
            val isStale = remoteResponse?.stale ?: false
            val dataAsOf = remoteResponse?.dataAsOf
            val latestPublishedAt = remoteResponse?.latestPublishedAt
            val serverMsg = remoteResponse?.message?.takeIf { it.isNotBlank() } ?: "Chưa có bản tin mới"
            if (remoteNews.isNotEmpty()) {
                // Ghi vào Room DB nguyên vẹn qua atomic transaction
                try {
                    val newsEntities = ArrayList<NewsEntity>(remoteNews.size)
                    val aiEntities = ArrayList<AiAnalysisEntity>(remoteNews.size)

                    for (item in remoteNews) {
                        val epochMs = parseTimeToEpoch(item.publishedAt)
                        val joinedBulletPoints = if (item.bulletPoints.isNotEmpty()) item.bulletPoints.joinToString("\n") else ""
                        val joinedBulletPointsVi = if (!item.bulletPointsVi.isNullOrEmpty()) item.bulletPointsVi.joinToString("\n") else joinedBulletPoints

                        newsEntities.add(
                            NewsEntity(
                                item.id,
                                item.getEffectiveTitle(),
                                item.link ?: item.id,
                                epochMs,
                                item.getEffectivePublisher(),
                                item.author ?: "",
                                item.publishedAt,
                                item.imageUrl,
                                item.getEffectiveSummary(),
                                item.sentiment,
                                item.confidence,
                                joinedBulletPoints,
                                item.originalTitle ?: "",
                                item.originalSummary ?: "",
                                item.displayTitleVi ?: "",
                                item.displaySummaryVi ?: "",
                                joinedBulletPointsVi,
                                item.publisher ?: ""
                            )
                        )

                        aiEntities.add(
                            AiAnalysisEntity(
                                item.id,
                                item.getEffectiveSummary(),
                                item.sentiment,
                                item.confidence,
                                if (!item.bulletPointsVi.isNullOrEmpty()) item.bulletPointsVi.joinToString(" • ") else if (item.bulletPoints.isNotEmpty()) item.bulletPoints.joinToString(" • ") else item.getEffectiveSummary()
                            )
                        )
                    }

                    newsDao.upsertAllNewsWithAnalysis(newsEntities, aiEntities)

                    // Dọn dẹp các bản ghi cũ chưa được bản địa hóa hợp lệ
                    runCatching {
                        val allInDb = newsDao.getAllNews()
                        val invalidIds = allInDb.filter { it.title.isBlank() && it.originalTitle.isBlank() }.map { it.newsId }
                        if (invalidIds.isNotEmpty()) {
                            newsDao.deleteNewsByIds(invalidIds)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("NewsRepository", "Lỗi lưu cache Room DB: ${e.message}", e)
                    // Nếu ghi Room thất bại, không trả cache cũ và nói là online, cũng không bypass Room
                    return@withContext NewsResult.CacheWriteFailure(e)
                }

                // Đọc lại từ Room DB làm Single Source of Truth
                val roomNews = readNewsFromRoom(newsDao)
                return@withContext if (roomNews.isNotEmpty()) {
                    recordSyncMetadata(System.currentTimeMillis(), isStale, dataAsOf, latestPublishedAt)
                    NewsResult.SyncSuccess(
                        news = roomNews,
                        isStale = isStale,
                        dataAsOf = dataAsOf,
                        latestPublishedAt = latestPublishedAt
                    )
                } else {
                    NewsResult.Empty(serverMsg)
                }
            } else {
                // Server trả danh sách rỗng (status degraded hoặc chưa có tin mới)
                val roomNews = readNewsFromRoom(newsDao)
                return@withContext if (roomNews.isNotEmpty()) {
                    NewsResult.CacheFallback(roomNews, serverMsg, isStale = true)
                } else {
                    NewsResult.Empty(serverMsg)
                }
            }
        } else {
            val err = remoteResult.exceptionOrNull()
            android.util.Log.w("NewsRepository", "Không thể tải tin tức trực tuyến: ${err?.message}", err)

            // 2. Nếu ngoại tuyến hoặc lỗi mạng, đọc từ Room DB Cache đã lọc bản địa hóa
            val cachedList = readNewsFromRoom(newsDao)
            if (cachedList.isNotEmpty()) {
                val errorMsg = "Mất kết nối máy chủ. Đang hiển thị tin tức đã lưu trên thiết bị."
                return@withContext NewsResult.CacheFallback(cachedList, errorMsg)
            }

            // 3. Nếu cả server và Room đều rỗng hoặc không có tin tiếng Việt hợp lệ: Honest Empty State
            return@withContext NewsResult.Empty("Chưa có bản tin mới")
        }
    }

    suspend fun refreshNews(token: String, clientRequestId: String): NewsRefreshResult = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(context)
        val newsDao = db.newsDao()
        val cached = readNewsFromRoom(newsDao)

        val authHeader = if (token.startsWith("Bearer ")) token else "Bearer $token"

        try {
            val response = ApiClient.service(context).refreshNews(
                token = authHeader,
                clientRequestId = clientRequestId,
                limit = 5
            )

            val statusLower = response.status.lowercase(Locale.ROOT)
            val remoteNews = response.data ?: emptyList()

            // Kiểm tra response.status: nếu degraded hoặc data rỗng, giữ dữ liệu cũ, không trả Success giả
            if (statusLower != "ok" || remoteNews.isEmpty()) {
                val msg = response.message?.takeIf { it.isNotBlank() } ?: context.getString(com.example.nhumonglenh.R.string.refresh_news_empty)
                return@withContext NewsRefreshResult.DegradedOrEmpty(
                    status = response.status,
                    message = msg,
                    cachedNews = cached,
                    remainingRefreshes = response.remainingRefreshes,
                    quotaDate = response.quotaDate,
                    isStale = response.stale,
                    dataAsOf = response.dataAsOf,
                    latestPublishedAt = response.latestPublishedAt
                )
            }

            persistNewsToRoom(newsDao, remoteNews)
            val updated = readNewsFromRoom(newsDao)
            if (updated.isNotEmpty()) {
                recordSyncMetadata(System.currentTimeMillis(), response.stale, response.dataAsOf, response.latestPublishedAt)
            }
            NewsRefreshResult.Success(
                news = if (updated.isNotEmpty()) updated else remoteNews,
                remainingRefreshes = response.remainingRefreshes,
                quotaDate = response.quotaDate,
                isStale = response.stale,
                dataAsOf = response.dataAsOf,
                latestPublishedAt = response.latestPublishedAt
            )
        } catch (e: retrofit2.HttpException) {
            when (e.code()) {
                401, 403 -> {
                    NewsRefreshResult.Unauthorized("Phiên đăng nhập đã hết hạn hoặc không hợp lệ.")
                }
                429 -> {
                    NewsRefreshResult.QuotaExhausted(
                        context.getString(com.example.nhumonglenh.R.string.refresh_quota_exhausted),
                        cached
                    )
                }
                503 -> {
                    NewsRefreshResult.ServerError(
                        context.getString(com.example.nhumonglenh.R.string.refresh_news_error),
                        cached
                    )
                }
                else -> {
                    val errorMsg = context.getString(com.example.nhumonglenh.R.string.refresh_server_error_format, e.code())
                    NewsRefreshResult.ServerError(errorMsg, cached)
                }
            }
        } catch (e: java.io.IOException) {
            NewsRefreshResult.NetworkError(
                context.getString(com.example.nhumonglenh.R.string.refresh_network_error),
                cached
            )
        } catch (e: Exception) {
            NewsRefreshResult.ServerError(
                context.getString(com.example.nhumonglenh.R.string.refresh_unknown_error),
                cached
            )
        }
    }

    private fun persistNewsToRoom(newsDao: com.example.nhumonglenh.data.local.NewsDao, remoteNews: List<News>) {
        val newsEntities = ArrayList<NewsEntity>(remoteNews.size)
        val aiEntities = ArrayList<AiAnalysisEntity>(remoteNews.size)

        for (item in remoteNews) {
            val epochMs = parseTimeToEpoch(item.publishedAt)
            val joinedBulletPoints = if (item.bulletPoints.isNotEmpty()) item.bulletPoints.joinToString("\n") else ""
            val joinedBulletPointsVi = if (!item.bulletPointsVi.isNullOrEmpty()) item.bulletPointsVi.joinToString("\n") else joinedBulletPoints

            newsEntities.add(
                NewsEntity(
                    item.id,
                    item.getEffectiveTitle(),
                    item.link ?: item.id,
                    epochMs,
                    item.getEffectivePublisher(),
                    item.author ?: "",
                    item.publishedAt,
                    item.imageUrl,
                    item.getEffectiveSummary(),
                    item.sentiment,
                    item.confidence,
                    joinedBulletPoints,
                    item.originalTitle ?: "",
                    item.originalSummary ?: "",
                    item.displayTitleVi ?: "",
                    item.displaySummaryVi ?: "",
                    joinedBulletPointsVi,
                    item.publisher ?: ""
                )
            )

            aiEntities.add(
                AiAnalysisEntity(
                    item.id,
                    item.getEffectiveSummary(),
                    item.sentiment,
                    item.confidence,
                    if (!item.bulletPointsVi.isNullOrEmpty()) item.bulletPointsVi.joinToString(" • ") else if (item.bulletPoints.isNotEmpty()) item.bulletPoints.joinToString(" • ") else item.getEffectiveSummary()
                )
            )
        }

        newsDao.upsertAllNewsWithAnalysis(newsEntities, aiEntities)

        runCatching {
            val allInDb = newsDao.getAllNews()
            val invalidIds = allInDb.filter { it.title.isBlank() && it.originalTitle.isBlank() }.map { it.newsId }
            if (invalidIds.isNotEmpty()) {
                newsDao.deleteNewsByIds(invalidIds)
            }
        }
    }

    private fun readNewsFromRoom(newsDao: com.example.nhumonglenh.data.local.NewsDao): List<News> {
        val cachedEntities = try {
            newsDao.getAllNews()
        } catch (e: Exception) {
            android.util.Log.e("NewsRepository", "Lỗi đọc tin từ Room DB: ${e.message}", e)
            emptyList()
        }

        // Ưu tiên bài đã bản địa hóa; nếu Qwen lỗi/chưa dịch, vẫn giữ bài gốc để đọc (fail-closed)
        val validEntities = cachedEntities.filter {
            com.example.nhumonglenh.ui.news.NewsLocalizationPolicy.isEntityFullyLocalized(it) ||
                    ((it.title.isNotBlank() || it.originalTitle.isNotBlank()) && it.url.isNotBlank())
        }.sortedByDescending {
            // Repair ordering for rows cached by older builds with an unparsed epoch of 0.
            effectivePublishedEpoch(it.publishedAtRaw, it.publishedAt)
        }

        return validEntities.map { entity ->
            val analysis = try {
                newsDao.getCachedAIAnalysis(entity.newsId)
            } catch (e: Exception) {
                android.util.Log.w("NewsRepository", "Lỗi đọc phân tích AI cho ${entity.newsId}: ${e.message}")
                null
            }

            val finalPublishedAt = if (entity.publishedAtRaw.isNotBlank()) {
                entity.publishedAtRaw
            } else {
                formatEpochToDate(entity.publishedAt)
            }

            val rawBullets = if (entity.bulletPointsVi.isNotBlank()) {
                entity.bulletPointsVi.split("\n").filter { it.isNotBlank() }
            } else if (entity.bulletPoints.isNotBlank()) {
                entity.bulletPoints.split("\n").filter { it.isNotBlank() }
            } else if (analysis?.reason != null && analysis.reason.isNotBlank()) {
                analysis.reason.split(" • ").filter { it.isNotBlank() }
            } else {
                emptyList()
            }

            val effectiveTitle = if (entity.displayTitleVi.isNotBlank()) entity.displayTitleVi else entity.title
            val effectiveSummary = if (entity.displaySummaryVi.isNotBlank()) {
                entity.displaySummaryVi
            } else if (entity.summary.isNotBlank()) {
                entity.summary
            } else {
                analysis?.summary ?: ""
            }
            val effectivePublisher = if (entity.publisher.isNotBlank()) {
                entity.publisher
            } else if (entity.source.isNotBlank() && !com.example.nhumonglenh.ui.news.NewsCardPresentationMapper.isGeneric(entity.source)) {
                entity.source
            } else {
                ""
            }

            News(
                id = entity.newsId,
                title = effectiveTitle,
                source = effectivePublisher,
                publishedAt = finalPublishedAt,
                summary = effectiveSummary,
                sentiment = if (entity.sentiment.isNotBlank()) entity.sentiment else (analysis?.sentiment ?: "neutral"),
                confidence = if (entity.confidence > 0) entity.confidence else (analysis?.confidenceScore ?: 0),
                bulletPoints = rawBullets,
                author = entity.author,
                imageUrl = entity.imageUrl,
                link = entity.url,
                originalTitle = entity.originalTitle,
                originalSummary = entity.originalSummary,
                displayTitleVi = entity.displayTitleVi,
                displaySummaryVi = entity.displaySummaryVi,
                bulletPointsVi = rawBullets,
                publisher = effectivePublisher
            )
        }
    }

    companion object {
        const val PREFS_NEWS_SYNC = "fnmf_news_sync_metadata"
        const val KEY_LAST_SYNC_TIME = "last_sync_time_ms"
        const val KEY_LAST_SYNC_STALE = "last_sync_stale"
        const val KEY_LAST_DATA_AS_OF = "last_data_as_of"
        const val KEY_LAST_LATEST_PUBLISHED_AT = "last_latest_published_at"
        const val KEY_NEXT_AUTO_ATTEMPT_AT = "next_auto_attempt_at_ms"
        const val KEY_AUTO_SYNC_FAILURES = "auto_sync_failures"

        const val AUTO_SYNC_THROTTLE_MS = 15 * 60 * 1000L // 15 phút
        const val AUTO_SYNC_BASE_BACKOFF_MS = 60 * 1000L // 1 phút
        const val AUTO_SYNC_MAX_BACKOFF_MS = 5 * 60 * 1000L // 5 phút
        @Volatile
        var lastSyncTimeMs: Long = 0L
        val syncMutex = Mutex()

        @Volatile
        private var INSTANCE: NewsRepository? = null

        fun getInstance(context: Context): NewsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NewsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun createForTesting(context: Context): NewsRepository = NewsRepository(context)

        fun effectivePublishedEpoch(raw: String?, stored: Long): Long {
            return parseTimeToEpoch(raw).takeIf { it > 0L } ?: stored
        }

        fun parseTimeToEpoch(timeStr: String?): Long {
            if (timeStr == null || timeStr.isBlank()) return 0L

            val isoFormats = arrayOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd'T'HH:mm",
                "yyyy-MM-dd HH:mm:ss",
                "yyyyMMdd'T'HHmmss"
            )

            for (pattern in isoFormats) {
                try {
                    val sdf = SimpleDateFormat(pattern, Locale.US)
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    val date = sdf.parse(timeStr)
                    if (date != null) return date.time
                } catch (_: Exception) {
                }
            }

            try {
                val epoch = timeStr.toLongOrNull()
                if (epoch != null) {
                    return if (epoch < 10000000000L) epoch * 1000L else epoch
                }
            } catch (_: Exception) {
            }

            return 0L
        }

        fun formatEpochToDate(epochMs: Long): String {
            if (epochMs <= 0) return ""
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                sdf.format(Date(epochMs))
            } catch (_: Exception) {
                ""
            }
        }
    }
}
