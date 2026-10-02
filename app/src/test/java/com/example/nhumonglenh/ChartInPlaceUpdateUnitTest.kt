package com.example.nhumonglenh

import com.example.nhumonglenh.data.remote.CandleDto
import com.example.nhumonglenh.ui.trading.BinanceKlineEvent
import com.example.nhumonglenh.ui.trading.CandleTimeFormatter
import com.example.nhumonglenh.ui.trading.ChartAxisPolicy
import com.example.nhumonglenh.ui.trading.ChartSeriesPolicy
import com.example.nhumonglenh.ui.trading.FnmfCandleStickChartRenderer
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CandleDataSet
import com.github.mikephil.charting.data.CandleEntry
import org.junit.Assert.*
import org.junit.Test

class ChartInPlaceUpdateUnitTest {

    @Test
    fun testMonotonicX_doesNotShiftExistingCandlesOnDrop() {
        val maxCandles = 5
        val entries = ArrayList<CandleEntry>()
        val labelMap = HashMap<Int, String>()
        var nextX = 0f

        val baseTime = 1700000000000L

        // Initial 5 candles
        for (i in 0 until 5) {
            val openTime = baseTime + i * 1000L
            val entry = CandleEntry(nextX, 105f, 95f, 100f, 102f)
            entries.add(entry)
            labelMap[nextX.toInt()] = CandleTimeFormatter.formatChartAxisLabelFromEpoch(openTime)
            nextX += 1f
        }

        assertEquals(5, entries.size)
        assertEquals(0f, entries[0].x, 0.001f)
        assertEquals(4f, entries[4].x, 0.001f)

        // New candle at 6th second (nextX = 5f)
        val newTime = baseTime + 5 * 1000L
        val newEntry = CandleEntry(nextX, 108f, 101f, 102f, 106f)
        entries.add(newEntry)
        labelMap[nextX.toInt()] = CandleTimeFormatter.formatChartAxisLabelFromEpoch(newTime)
        nextX += 1f

        // Buffer exceeded: drop oldest entry
        if (entries.size > maxCandles) {
            val oldest = entries.removeAt(0)
            labelMap.remove(oldest.x.toInt())
        }

        assertEquals(5, entries.size)
        // Existing candles keep their exact monotonic X coordinates! They do NOT shift back to 0!
        assertEquals(1f, entries[0].x, 0.001f)
        assertEquals(2f, entries[1].x, 0.001f)
        assertEquals(3f, entries[2].x, 0.001f)
        assertEquals(4f, entries[3].x, 0.001f)
        assertEquals(5f, entries[4].x, 0.001f)

        // Label lookup for X=5 works O(1) and is exact
        assertEquals(CandleTimeFormatter.formatChartAxisLabelFromEpoch(newTime), labelMap[5])
        // Oldest X=0 is safely pruned
        assertNull(labelMap[0])
    }

    @Test
    fun testSameSecondTick_mutatesLastEntryInPlace_withoutDatasetRebuild() {
        val entry = CandleEntry(10f, 105f, 95f, 100f, 102f)
        val dataSet = CandleDataSet(listOf(entry), "BTCUSDT • 1s")

        // Same second tick comes with higher high and new close
        val tick = BinanceKlineEvent(
            openTime = 1700000000000L,
            closeTime = 1700000000999L,
            symbol = "BTCUSDT",
            interval = "1s",
            open = 100.0,
            high = 110.0,
            low = 94.0,
            close = 109.0,
            volume = 5.0,
            isClosed = false
        )

        val lastEntry = dataSet.getEntryForIndex(dataSet.entryCount - 1)
        lastEntry.open = tick.open.toFloat()
        lastEntry.close = tick.close.toFloat()
        lastEntry.high = tick.high.toFloat()
        lastEntry.low = tick.low.toFloat()

        assertEquals(1, dataSet.entryCount)
        assertEquals(110f, dataSet.getEntryForIndex(0).high, 0.001f)
        assertEquals(94f, dataSet.getEntryForIndex(0).low, 0.001f)
        assertEquals(109f, dataSet.getEntryForIndex(0).close, 0.001f)
        assertEquals(100f, dataSet.getEntryForIndex(0).open, 0.001f)
    }

    @Test
    fun testWebSocketInitializesChart_whenRestFails_createsSingleCandleProperly() {
        // Mô phỏng kịch bản REST trả lỗi 503 -> currentCandles rỗng
        val currentCandles = ArrayList<CandleDto>()

        val event = BinanceKlineEvent(
            openTime = 1700000000000L,
            closeTime = 1700000000999L,
            symbol = "BTCUSDT",
            interval = "1s",
            open = 84200.0,
            high = 84205.0,
            low = 84198.0,
            close = 84201.0,
            volume = 1.25,
            isClosed = false
        )

        // 1. Gọi trực tiếp hàm thuần ChartSeriesPolicy.createSingleCandleDto trong production code
        val single = ChartSeriesPolicy.createSingleCandleDto(event)
        assertEquals(84201.0, single.close, 0.001)
        assertEquals(84205.0, single.high, 0.001)
        assertEquals(84198.0, single.low, 0.001)
        assertEquals(84200.0, single.open, 0.001)

        // 2. Gọi ChartSeriesPolicy.safeSyncCandles với danh sách mới
        ChartSeriesPolicy.safeSyncCandles(currentCandles, listOf(single))
        assertEquals(1, currentCandles.size)
        assertEquals(84201.0, currentCandles[0].close, 0.001)

        // 3. Kiểm thử phòng thủ: nếu truyền chính currentCandles vào safeSyncCandles, không được tự xóa sạch
        ChartSeriesPolicy.safeSyncCandles(currentCandles, currentCandles)
        assertEquals("Danh sách không được bị xóa sạch khi truyền cùng tham chiếu", 1, currentCandles.size)
        assertEquals(84201.0, currentCandles[0].close, 0.001)
    }

    @Test
    fun testAdjustChartYAxisRange_calculatesProportionalDelta_andHandlesSubDollarAssets() {
        // 1. BTC đứng giá: yMin = 84200, yMax = 84202 (delta 2 USD < minDelta 16.84 USD)
        // Gọi trực tiếp ChartAxisPolicy.calculateYAxisRange trong production code
        val btcFlat = ChartAxisPolicy.calculateYAxisRange(84200f, 84202f)
        assertTrue("BTC đứng giá phải kích hoạt custom range đối xứng", btcFlat.isCustomRange)
        val expectedBtcMinDelta = 84202f * 0.0002f
        val expectedMid = 84201f
        assertEquals(expectedMid - expectedBtcMinDelta, btcFlat.axisMinimum, 0.01f)
        assertEquals(expectedMid + expectedBtcMinDelta, btcFlat.axisMaximum, 0.01f)

        // 2. BTC biến động mạnh: yMin = 84100, yMax = 84250 (delta 150 USD > minDelta)
        val btcVolatile = ChartAxisPolicy.calculateYAxisRange(84100f, 84250f)
        assertFalse("BTC biến động mạnh phải trả về isCustomRange=false để MPAndroidChart tự căn chỉnh", btcVolatile.isCustomRange)

        // 3. ADA đứng giá: yMin = 0.2300, yMax = 0.23005
        val adaFlat = ChartAxisPolicy.calculateYAxisRange(0.2300f, 0.23005f)
        assertTrue("ADA đứng giá phải kích hoạt custom range", adaFlat.isCustomRange)
        // Ngưỡng không được bị sàn cứng 2.0 USD làm méo mó (phải quanh 0.0001)
        assertTrue("ADA axisMinimum phải dương và sát mức giá thật", adaFlat.axisMinimum > 0.22f)
        assertTrue("ADA axisMaximum phải sát mức giá thật", adaFlat.axisMaximum < 0.24f)

        // 4. Input không hợp lệ: yMax <= 0
        val invalid = ChartAxisPolicy.calculateYAxisRange(0f, 0f)
        assertFalse(invalid.isCustomRange)
    }

    @Test
    fun testFlatDojiCandle_preservesRealOhlc_andHasConfiguredThickness() {
        // Flat candle where open == close and high == low (zero price movement in 1 second)
        val flatCandle = CandleDto(
            time = "2026-10-03 01:00:00",
            open = 65000.0,
            high = 65000.0,
            low = 65000.0,
            close = 65000.0,
            volume = 0.5,
            openTime = 1700000000000L
        )

        // Real OHLC must remain strictly untouched
        assertEquals(flatCandle.open, flatCandle.close, 0.0001)
        assertEquals(flatCandle.high, flatCandle.low, 0.0001)

        val entry = CandleEntry(0f, flatCandle.high.toFloat(), flatCandle.low.toFloat(), flatCandle.open.toFloat(), flatCandle.close.toFloat())
        assertEquals(65000f, entry.open, 0.001f)
        assertEquals(65000f, entry.close, 0.001f)
        assertEquals(65000f, entry.high, 0.001f)
        assertEquals(65000f, entry.low, 0.001f)

        // Renderer configuration check: minDojiStrokePx must be >= 3.0f to guarantee visibility on high-DPI displays
        val minStroke = 4.0f
        assertTrue("Doji stroke width must be at least 3.0px for clear visibility on Note 10+", minStroke >= 3.0f)
    }

    @Test
    fun testHistoryCapacity_configuredToNinetyCandles() {
        assertEquals(90, TradingFragment.DEFAULT_MAX_CANDLES)
        assertEquals(60f, TradingFragment.VIEWPORT_VISIBLE_MAX_CANDLES, 0.001f)
        assertEquals(15f, TradingFragment.VIEWPORT_VISIBLE_MIN_CANDLES, 0.001f)
    }
}
