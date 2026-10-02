package com.example.nhumonglenh.ui.trading

import com.example.nhumonglenh.data.remote.CandleDto
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Event nến kline thời gian thực từ Binance WebSocket (kline_1s).
 */
data class BinanceKlineEvent(
    val openTime: Long,
    val closeTime: Long,
    val symbol: String,
    val interval: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val isClosed: Boolean
)

/**
 * Parser phân tích và xác thực tính toàn vẹn của payload Binance Kline.
 * Từ chối payload thiếu trường, số âm/zero, hoặc vi phạm bất biến OHLC:
 * - open, high, low, close <= 0
 * - volume < 0
 * - high < low
 * - high < open hoặc high < close
 * - low > open hoặc low > close
 */
object BinanceKlineParser {

    fun parse(jsonString: String?): BinanceKlineEvent? {
        if (jsonString.isNullOrBlank()) return null
        return try {
            val root = JSONObject(jsonString)
            val k = if (root.has("k")) root.getJSONObject("k") else root

            val openTime = k.optLong("t", -1L)
            val closeTime = k.optLong("T", -1L)
            val symbol = k.optString("s")
            val interval = k.optString("i")
            val isClosed = k.optBoolean("x", false)

            if (openTime <= 0L || closeTime < openTime || symbol.isNullOrBlank() || interval != "1s") {
                return null
            }

            val openStr = k.optString("o")
            val highStr = k.optString("h")
            val lowStr = k.optString("l")
            val closeStr = k.optString("c")
            val volumeStr = k.optString("v")

            if (openStr.isBlank() || highStr.isBlank() || lowStr.isBlank() || closeStr.isBlank() || volumeStr.isBlank()) {
                return null
            }

            val open = openStr.toDoubleOrNull() ?: return null
            val high = highStr.toDoubleOrNull() ?: return null
            val low = lowStr.toDoubleOrNull() ?: return null
            val close = closeStr.toDoubleOrNull() ?: return null
            val volume = volumeStr.toDoubleOrNull() ?: return null

            if (open.isNaN() || open.isInfinite() ||
                high.isNaN() || high.isInfinite() ||
                low.isNaN() || low.isInfinite() ||
                close.isNaN() || close.isInfinite() ||
                volume.isNaN() || volume.isInfinite()
            ) {
                return null
            }

            if (open <= 0.0 || high <= 0.0 || low <= 0.0 || close <= 0.0 || volume < 0.0) {
                return null
            }

            if (high < low || high < open || high < close || low > open || low > close) {
                return null
            }

            BinanceKlineEvent(
                openTime = openTime,
                closeTime = closeTime,
                symbol = symbol,
                interval = interval,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume,
                isClosed = isClosed
            )
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Pure Reducer cập nhật chuỗi 30 nến:
 * - Nếu openTime trùng cây cuối: thay thế OHLCV cây cuối (cập nhật chuyển động real-time kể cả khi x=false).
 * - Nếu openTime mới hơn: thêm cây mới và giữ tối đa 30 cây.
 * - Nếu event cũ hơn cây cuối: bỏ qua.
 * - Tuyệt đối không nội suy hay sinh dữ liệu ngẫu nhiên.
 */
object CandleSeriesReducer {

    fun reduce(
        currentSeries: List<CandleDto>,
        event: BinanceKlineEvent,
        maxCandles: Int = 30
    ): List<CandleDto> {
        if (currentSeries.isEmpty()) {
            val single = CandleDto(
                time = CandleTimeFormatter.formatDateTime(event.openTime),
                open = event.open,
                high = event.high,
                low = event.low,
                close = event.close,
                volume = event.volume,
                openTime = event.openTime,
                isClosed = event.isClosed
            )
            return listOf(single)
        }

        val last = currentSeries.last()
        val lastOpenTime = last.openTime ?: CandleTimeFormatter.parseTimeToMillis(last.time)

        return when {
            event.openTime == lastOpenTime -> {
                val updatedLast = last.copy(
                    open = event.open,
                    high = event.high,
                    low = event.low,
                    close = event.close,
                    volume = event.volume,
                    openTime = event.openTime,
                    isClosed = event.isClosed
                )
                currentSeries.dropLast(1) + updatedLast
            }
            event.openTime > lastOpenTime -> {
                val newCandle = CandleDto(
                    time = CandleTimeFormatter.formatDateTime(event.openTime),
                    open = event.open,
                    high = event.high,
                    low = event.low,
                    close = event.close,
                    volume = event.volume,
                    openTime = event.openTime,
                    isClosed = event.isClosed
                )
                val combined = currentSeries + newCandle
                if (combined.size > maxCandles) {
                    combined.takeLast(maxCandles)
                } else {
                    combined
                }
            }
            else -> {
                // event.openTime < lastOpenTime: event cũ bị bỏ qua
                currentSeries
            }
        }
    }
}

/**
 * Ánh xạ và kiểm tra khớp mã tài sản:
 * - BTCUSDT khớp BTCUSDT / BTC.
 * - ETHUSDT khớp ETHUSDT / ETH.
 * - PAXGUSDT khớp XAUUSD / XAU / PAXGUSDT.
 * - Event của symbol/socket cũ không được cập nhật sang symbol mới.
 */
object MarketSymbolMatcher {

    fun matches(eventSymbol: String?, currentSymbol: String?): Boolean {
        if (eventSymbol.isNullOrBlank() || currentSymbol.isNullOrBlank()) return false
        val ev = eventSymbol.uppercase().trim()
        val cur = currentSymbol.uppercase().trim()
        if (ev == cur) return true
        if ((cur == "BTCUSDT" || cur == "BTC") && (ev == "BTCUSDT" || ev == "BTC")) return true
        if ((cur == "ETHUSDT" || cur == "ETH") && (ev == "ETHUSDT" || ev == "ETH")) return true
        if ((cur == "XAUUSD" || cur == "XAU" || cur == "PAXGUSDT") && (ev == "PAXGUSDT" || ev == "XAUUSD" || ev == "XAU")) return true
        if ((cur == "BNBUSDT" || cur == "BNB") && (ev == "BNBUSDT" || ev == "BNB")) return true
        if ((cur == "SOLUSDT" || cur == "SOL") && (ev == "SOLUSDT" || ev == "SOL")) return true
        if ((cur == "XRPUSDT" || cur == "XRP") && (ev == "XRPUSDT" || ev == "XRP")) return true
        if ((cur == "ADAUSDT" || cur == "ADA") && (ev == "ADAUSDT" || ev == "ADA")) return true
        if ((cur == "DOGEUSDT" || cur == "DOGE") && (ev == "DOGEUSDT" || ev == "DOGE")) return true
        return false
    }
}

/**
 * Quyết định hành động khi màn hình Trading quay lại foreground (onResume).
 */
enum class TradingResumeAction {
    DO_NOTHING,
    LOAD_INITIAL_CANDLES,
    CONNECT_WEBSOCKET
}

/**
 * Pure policy điều phối vòng đời Trading (ngăn chặn double REST call giữa onViewCreated và onResume).
 */
object TradingLifecyclePolicy {
    fun decideResumeAction(
        isStock: Boolean,
        hasActiveCandleCall: Boolean,
        hasCandles: Boolean,
        hasActiveSocket: Boolean
    ): TradingResumeAction {
        if (isStock) return TradingResumeAction.DO_NOTHING
        if (hasActiveCandleCall) return TradingResumeAction.DO_NOTHING
        if (hasActiveSocket) return TradingResumeAction.DO_NOTHING

        return if (!hasCandles) {
            TradingResumeAction.LOAD_INITIAL_CANDLES
        } else {
            TradingResumeAction.CONNECT_WEBSOCKET
        }
    }
}

/**
 * Bảo vệ callback WebSocket chống lại stale sockets, sai generation, sai symbol hoặc background lifecycle.
 * Bắt buộc kiểm tra danh tính tham chiếu socket (webSocket === activeWebSocket).
 */
object SocketCallbackGuard {
    fun isCallbackAllowed(
        callbackSocket: Any?,
        activeSocket: Any?,
        callbackGeneration: Long,
        activeGeneration: Long,
        callbackSymbol: String?,
        activeSymbol: String?,
        isFragmentVisible: Boolean
    ): Boolean {
        if (!isFragmentVisible) return false
        if (activeSocket == null || callbackSocket == null || callbackSocket !== activeSocket) return false
        if (callbackGeneration != activeGeneration) return false
        if (callbackSymbol.isNullOrBlank() || activeSymbol.isNullOrBlank()) return false
        return callbackSymbol.equals(activeSymbol, ignoreCase = true)
    }
}

/**
 * Quyết định xử lý khi WebSocket bị lỗi kết nối (onFailure).
 */
data class SocketFailureDecision(
    val shouldScheduleReconnect: Boolean,
    val nextAttempt: Int,
    val delayMs: Long,
    val shouldClearActiveSocket: Boolean
)

/**
 * Chính sách Reconnect WebSocket có backoff hữu hạn và kiểm tra vòng đời:
 * - Backoff hữu hạn: 2s, 5s, 10s, 30s (tối đa 4 lần thử).
 * - Không reconnect khi fragment bị ẩn (hidden) hoặc destroy.
 * - Chỉ socket hiện hành mới được schedule reconnect.
 * - Khi hết 4 lần thử, giải phóng active socket để lần foreground tiếp theo có thể khởi động lại.
 */
object SocketReconnectPolicy {
    val BACKOFF_DELAYS = listOf(2000L, 5000L, 10000L, 30000L)
    const val MAX_RETRIES = 4

    fun getDelayMs(attempt: Int): Long {
        val index = attempt.coerceIn(0, BACKOFF_DELAYS.size - 1)
        return BACKOFF_DELAYS[index]
    }

    fun shouldReconnect(attempt: Int, isVisible: Boolean): Boolean {
        return isVisible && attempt < MAX_RETRIES
    }

    fun decideFailureAction(
        callbackSocket: Any?,
        activeSocket: Any?,
        callbackGeneration: Long,
        activeGeneration: Long,
        currentAttempt: Int,
        isVisible: Boolean
    ): SocketFailureDecision {
        val isCurrentSocket = isVisible &&
                activeSocket != null &&
                callbackSocket != null &&
                callbackSocket === activeSocket &&
                callbackGeneration == activeGeneration

        if (!isCurrentSocket) {
            return SocketFailureDecision(
                shouldScheduleReconnect = false,
                nextAttempt = currentAttempt,
                delayMs = 0L,
                shouldClearActiveSocket = false
            )
        }

        return if (currentAttempt < MAX_RETRIES) {
            SocketFailureDecision(
                shouldScheduleReconnect = true,
                nextAttempt = currentAttempt + 1,
                delayMs = getDelayMs(currentAttempt),
                shouldClearActiveSocket = false
            )
        } else {
            SocketFailureDecision(
                shouldScheduleReconnect = false,
                nextAttempt = currentAttempt,
                delayMs = 0L,
                shouldClearActiveSocket = true
            )
        }
    }
}

/**
 * Định dạng thời gian nến và trục tọa độ X.
 */
object CandleTimeFormatter {

    // Múi giờ chuẩn hóa cố định trên Android cho toàn bộ nhãn biểu đồ:
    // Dựng toàn bộ nhãn từ openTime epoch millis theo cùng một TimeZone thiết bị,
    // loại bỏ hoàn toàn việc phụ thuộc vào chuỗi `time` do backend định dạng.
    val CHART_TIME_ZONE: TimeZone = TimeZone.getDefault()

    fun formatDateTime(millis: Long, timeZone: TimeZone = CHART_TIME_ZONE): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        sdf.timeZone = timeZone
        return sdf.format(Date(millis))
    }

    val UTC_TIME_ZONE: TimeZone = TimeZone.getTimeZone("UTC")

    /**
     * Parse chuỗi thời gian trả về từ backend (được backend định dạng theo chuẩn UTC)
     * sang epoch millis tuyệt đối.
     * Mặc định sử dụng UTC_TIME_ZONE để bảo đảm tính chuẩn xác của mốc thời gian epoch.
     */
    fun parseTimeToMillis(timeStr: String, timeZone: TimeZone = UTC_TIME_ZONE): Long {
        val direct = timeStr.toLongOrNull()
        if (direct != null && direct > 100000000000L) return direct
        val formats = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")
        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.timeZone = timeZone
                val d = sdf.parse(timeStr)
                if (d != null) return d.time
            } catch (_: Exception) {}
        }
        return 0L
    }

    /**
     * Dựng nhãn trục X trực tiếp từ epoch millis (openTime) theo cùng một múi giờ cố định.
     * Hoàn toàn không phụ thuộc vào chuỗi text do server backend định dạng.
     */
    fun formatChartAxisLabelFromEpoch(
        openTimeMs: Long,
        isSubDaily: Boolean = true,
        timeZone: TimeZone = CHART_TIME_ZONE
    ): String {
        val pattern = if (isSubDaily) "HH:mm:ss" else "MM-dd"
        val sdf = SimpleDateFormat(pattern, Locale.US)
        sdf.timeZone = timeZone
        return sdf.format(Date(openTimeMs))
    }

    /**
     * Định dạng nhãn trục tọa độ cho CandleDto:
     * - Ưu tiên tuyệt đối openTime epoch để bảo đảm đồng bộ múi giờ giữa REST và WebSocket.
     * - Khi openTime == null: Parse chuỗi candle.time (do backend format bằng UTC) bằng UTC_TIME_ZONE
     *   để có epoch millis chính xác, sau đó format epoch theo timeZone hiển thị (CHART_TIME_ZONE).
     */
    fun formatCandleAxisLabel(candle: CandleDto, timeZone: TimeZone = CHART_TIME_ZONE): String {
        val epoch = candle.openTime ?: parseTimeToMillis(candle.time, UTC_TIME_ZONE)
        if (epoch > 0L) {
            val isSubDaily = candle.openTime != null || candle.time.contains(":")
            return formatChartAxisLabelFromEpoch(epoch, isSubDaily, timeZone)
        }
        return formatChartAxisLabel(candle.time)
    }

    fun formatChartAxisLabel(timeStr: String): String {
        if (timeStr.length >= 19 && timeStr.contains(" ")) {
            return timeStr.substring(11, 19)
        }
        if (timeStr.length >= 10 && timeStr.contains("-")) {
            return timeStr.substring(5, 10)
        }
        return timeStr
    }
}

/**
 * Kết quả tính toán dải trục Y cho biểu đồ nến.
 */
data class YAxisRangeResult(
    val isCustomRange: Boolean,
    val axisMinimum: Float = 0f,
    val axisMaximum: Float = 0f
)

/**
 * Chính sách điều chỉnh dải hiển thị trục Y để tránh chia cho 0
 * hoặc nến bị kéo dãn tràn màn hình khi thị trường đứng giá.
 */
object ChartAxisPolicy {
    fun calculateYAxisRange(yMin: Float, yMax: Float): YAxisRangeResult {
        if (yMax <= 0f) return YAxisRangeResult(isCustomRange = false)
        val minDelta = maxOf(0.0001f, yMax * 0.0002f)
        return if (yMax - yMin < minDelta) {
            val mid = (yMax + yMin) / 2f
            YAxisRangeResult(
                isCustomRange = true,
                axisMinimum = mid - minDelta,
                axisMaximum = mid + minDelta
            )
        } else {
            YAxisRangeResult(isCustomRange = false)
        }
    }
}

/**
 * Chính sách khởi tạo và quản lý chuỗi nến biểu đồ thời gian thực.
 */
object ChartSeriesPolicy {
    fun createSingleCandleDto(event: BinanceKlineEvent): CandleDto {
        return CandleDto(
            time = CandleTimeFormatter.formatDateTime(event.openTime),
            open = event.open,
            high = event.high,
            low = event.low,
            close = event.close,
            volume = event.volume,
            openTime = event.openTime,
            isClosed = event.isClosed
        )
    }

    /**
     * Đồng bộ danh sách nến an toàn, ngăn ngừa triệt để lỗi tự xóa danh sách
     * khi đối tượng truyền vào là cùng một tham chiếu với currentCandles.
     */
    fun safeSyncCandles(targetList: MutableList<CandleDto>, sourceCandles: List<CandleDto>) {
        if (targetList !== sourceCandles) {
            targetList.clear()
            targetList.addAll(sourceCandles)
        }
    }
}
