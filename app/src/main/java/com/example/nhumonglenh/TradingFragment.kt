package com.example.nhumonglenh

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.nhumonglenh.data.local.AuthSessionManager
import com.example.nhumonglenh.data.remote.CandleDto
import com.example.nhumonglenh.data.remote.HoldingDto
import com.example.nhumonglenh.data.remote.PortfolioSummaryDto
import com.example.nhumonglenh.data.remote.RetrofitClient
import com.example.nhumonglenh.data.remote.StockCatalogDto
import com.example.nhumonglenh.data.remote.StockDetailDto
import com.example.nhumonglenh.data.remote.WatchlistItemDto
import com.example.nhumonglenh.data.remote.WatchlistRequest
import com.example.nhumonglenh.databinding.FragmentTradingBinding
import com.example.nhumonglenh.ui.trading.AuthHeaderFactory
import com.example.nhumonglenh.ui.trading.AuthHttpPolicy
import com.example.nhumonglenh.ui.trading.CandleFallbackPolicy
import com.example.nhumonglenh.ui.trading.CandleReloadPolicy
import com.example.nhumonglenh.ui.trading.ChartAxisPolicy
import com.example.nhumonglenh.ui.trading.ChartLabelFormatter
import com.example.nhumonglenh.ui.trading.ChartSeriesPolicy
import com.example.nhumonglenh.ui.trading.MarketDataProviderPolicy
import com.example.nhumonglenh.ui.trading.MarketStreamHelper
import com.example.nhumonglenh.ui.trading.OrderTicketBottomSheet
import com.example.nhumonglenh.ui.trading.PortfolioSyncPolicy
import com.example.nhumonglenh.ui.trading.PriceFormatter
import com.example.nhumonglenh.ui.trading.StockBadgePolicy
import com.example.nhumonglenh.ui.trading.StockCatalogAdapter
import com.example.nhumonglenh.ui.trading.StockPollingPolicy
import com.example.nhumonglenh.ui.trading.StockReportPolicy
import com.example.nhumonglenh.ui.trading.StockTradePolicy
import com.example.nhumonglenh.ui.trading.StockWatchlistMatcher
import com.example.nhumonglenh.ui.trading.SocketCallbackGuard
import com.example.nhumonglenh.ui.trading.SocketReconnectPolicy
import com.example.nhumonglenh.ui.trading.TradingDataReadiness
import com.example.nhumonglenh.ui.trading.TradingLifecyclePolicy
import com.example.nhumonglenh.ui.trading.TradingResumeAction
import com.example.nhumonglenh.ui.trading.TradingStateRestoration
import com.example.nhumonglenh.ui.trading.WatchlistMutation
import com.example.nhumonglenh.ui.trading.WatchlistStateReducer
import com.example.nhumonglenh.ui.watchlist.WatchlistAdapter
import com.example.nhumonglenh.ui.watchlist.WatchlistUiModel
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CandleDataSet
import com.github.mikephil.charting.data.CandleEntry
import com.google.android.material.tabs.TabLayout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Locale
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.Looper
import com.example.nhumonglenh.ui.trading.BinanceKlineEvent
import com.example.nhumonglenh.ui.trading.BinanceKlineParser
import com.example.nhumonglenh.ui.trading.CandleSeriesReducer
import com.example.nhumonglenh.ui.trading.CandleTimeFormatter
import com.example.nhumonglenh.ui.trading.FnmfCandleStickChartRenderer
import com.example.nhumonglenh.ui.trading.MarketSymbolMatcher

/**
 * =====================================================================
 * TRADING FRAGMENT - GIAO DIỆN GIAO DỊCH CHUẨN CHART-FIRST & CỔ PHIẾU HOA KỲ
 * =====================================================================
 * - Instrument Header: Symbol, full name, live price, 24h change %, live/offline badge.
 * - Mode Tabs: "Giao dịch" (Chart, Mua/Bán, Watchlist nhúng) và "Cổ phiếu" (8 mã cổ phiếu Mỹ).
 * - Compact Account Strip: Tiền mặt khả dụng & Số lượng đang giữ của mã hiện tại.
 * - Sticky Actions: Hai nút MUA và BÁN. Tự động vô hiệu hóa và cảnh báo an toàn khi stale=true hoặc thiếu giá.
 * - Nhúng Watchlist trực tiếp dưới nút Mua/Bán; hiển thị khuyến nghị và link báo cáo mới nhất.
 * - Không hiển thị tiêu đề báo cáo tiếng Anh (StockReportPolicy).
 * - Quản lý độc lập nến và WebSocket giữa Crypto/Vàng và Cổ phiếu.
 * =====================================================================
 */
class TradingFragment : Fragment() {

    private var _binding: FragmentTradingBinding? = null
    private val binding get() = _binding

    private var jwtToken: String = ""

    // Dữ liệu nến trong bộ nhớ
    private val currentCandles = ArrayList<CandleDto>()
    private val candleEntries = ArrayList<CandleEntry>()
    private val timeLabels = ArrayList<String>()
    private val candleTimeLabelsMap = HashMap<Int, String>()
    private var candleDataSet: CandleDataSet? = null
    private var loadedCandleSymbol: String? = null
    private var nextCandleXIndex: Float = 0f
    private val maxCandleHistory: Int = DEFAULT_MAX_CANDLES

    // Quản lý mã tài sản đang chọn
    private var currentSymbol: String = "BTCUSDT"
    private var currentAssetPrice: Double? = null
    private var previousAssetPrice: Double? = null
    private var baselinePeriodPrice: Double? = null
    private var userCashBalance: Double? = null
    private var portfolioLoaded: Boolean = false
    private var lastPortfolioFetchTime: Long = 0L
    private var userHoldingsQuantity: Double = 0.0
    private var userHoldingsAvgBuyPrice: Double = 0.0
    private var portfolioHoldingsList: List<HoldingDto> = emptyList()

    // Trạng thái cổ phiếu
    private var isStockDetailStale: Boolean = false
    private var cachedCatalogStocks: List<StockCatalogDto> = emptyList()
    private var cachedCatalogPrices: List<com.example.nhumonglenh.data.remote.MarketPriceDto> = emptyList()

    // Adapters
    private lateinit var stockCatalogAdapter: StockCatalogAdapter
    private lateinit var embeddedWatchlistAdapter: WatchlistAdapter
    private val cachedWatchlistSymbols = mutableSetOf<String>()

    // Calls đang chạy
    private var activeCandleCall: Call<List<CandleDto>>? = null
    private var activePortfolioCall: Call<PortfolioSummaryDto>? = null
    private var activeStockDetailCall: Call<StockDetailDto>? = null
    private var activeStockCatalogCall: Call<List<StockCatalogDto>>? = null
    private var activeEmbeddedWatchlistCall: Call<List<WatchlistItemDto>>? = null

    // WebSocket Client & Quản lý vòng đời / Race condition
    private var binanceWebSocket: WebSocket? = null
    private var activeSocketGeneration: Long = 0L
    private var isFragmentVisible: Boolean = true
    private var reconnectAttempts: Int = 0
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private val stockPollingHandler = Handler(Looper.getMainLooper())
    private var stockPollingRunnable: Runnable? = null

    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        _binding = FragmentTradingBinding.inflate(inflater, container, false)
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        jwtToken = getSavedToken()

        // 1. Cấu hình lắng nghe kết quả đặt lệnh qua FragmentResult API
        setFragmentResultListener(OrderTicketBottomSheet.REQUEST_KEY_ORDER) { _, bundle ->
            if (bundle.getBoolean(OrderTicketBottomSheet.KEY_ORDER_SUCCESS, false)) {
                val orderType = bundle.getString(OrderTicketBottomSheet.KEY_ORDER_TYPE) ?: "BUY"
                val sym = bundle.getString(OrderTicketBottomSheet.KEY_SYMBOL) ?: currentSymbol
                val qty = bundle.getDouble(OrderTicketBottomSheet.KEY_ORDER_QUANTITY, 0.0)
                val customMsg = bundle.getString(OrderTicketBottomSheet.KEY_SUCCESS_MESSAGE)
                val qtyStr = String.format(Locale.US, "%.4f", qty)
                val msg = customMsg ?: getString(R.string.order_success_format, orderType, qtyStr, sym)
                context?.let { ctx ->
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }
                loadPortfolioSilently()
            }
        }

        // 2. Cấu hình Tabs "Giao dịch" vs "Cổ phiếu"
        setupTabs()

        // 3. Cấu hình Adapter danh sách Cổ phiếu Mỹ
        setupStockCatalog()

        // 4. Cấu hình Adapter Watchlist nhúng
        setupEmbeddedWatchlist()

        // 5. Cấu hình nút Theo dõi trên Header
        setupWatchlistToggleAction()

        // 6. Cấu hình giao diện Biểu đồ Nến Dark Theme
        setupCandleChartStyle()

        // 7. Cấu hình các nút đặt lệnh MUA / BÁN
        setupTradeActions()

        // 8. Khởi động với symbol đã lưu hoặc mặc định BTCUSDT (không dùng giá giả)
        val initialSymbol = TradingStateRestoration.resolveInitialSymbol(savedInstanceState?.getString(KEY_SAVED_SYMBOL))
        switchMarketSymbol(initialSymbol, isInitial = true)

        // 9. Tải danh mục đầu tư thật lần đầu
        loadPortfolio()

        // 10. Chạm thẻ tài sản để làm mới số dư chủ động
        binding?.cardAccountStrip?.setOnClickListener {
            context?.let { c ->
                Toast.makeText(c, getString(R.string.trading_toast_syncing_balance), Toast.LENGTH_SHORT).show()
            }
            loadPortfolioSilently()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SAVED_SYMBOL, currentSymbol)
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) {
            isFragmentVisible = true
            val isStock = StockTradePolicy.isStock(currentSymbol)
            val action = TradingLifecyclePolicy.decideResumeAction(
                isStock = isStock,
                hasActiveCandleCall = activeCandleCall != null,
                hasCandles = currentCandles.isNotEmpty(),
                hasActiveSocket = binanceWebSocket != null
            )
            when (action) {
                TradingResumeAction.LOAD_INITIAL_CANDLES -> {
                    reconnectAttempts = 0
                    activeSocketGeneration++
                    loadCandleData(currentSymbol, activeSocketGeneration)
                }
                TradingResumeAction.CONNECT_WEBSOCKET -> {
                    reconnectAttempts = 0
                    connectWebSocketForSymbol(currentSymbol, activeSocketGeneration)
                }
                TradingResumeAction.DO_NOTHING -> {
                    // Không gửi REST lần 2 nếu call đang chạy hoặc là stock hoặc socket còn active
                }
            }
            if (isStock) {
                startStockPolling(currentSymbol, activeSocketGeneration)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isFragmentVisible = false
        disconnectWebSocket()
        stopStockPolling()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            isFragmentVisible = false
            disconnectWebSocket()
            stopStockPolling()
            activeCandleCall?.cancel()
            activeCandleCall = null
            activePortfolioCall?.cancel()
            activePortfolioCall = null
            activeStockDetailCall?.cancel()
            activeStockDetailCall = null
            activeStockCatalogCall?.cancel()
            activeStockCatalogCall = null
            activeEmbeddedWatchlistCall?.cancel()
            activeEmbeddedWatchlistCall = null
        } else {
            isFragmentVisible = true
            reconnectAttempts = 0
            val shouldReloadCandles = CandleReloadPolicy.shouldReloadOnTabVisible(
                hasCandleData = currentCandles.isNotEmpty(),
                loadedCandleSymbol = loadedCandleSymbol,
                currentSymbol = currentSymbol
            )
            if (shouldReloadCandles) {
                activeSocketGeneration++
                loadCandleData(currentSymbol, activeSocketGeneration)
            } else if (!StockTradePolicy.isStock(currentSymbol) && binanceWebSocket == null) {
                connectWebSocketForSymbol(currentSymbol, activeSocketGeneration)
            }
            if (PortfolioSyncPolicy.isStale(lastPortfolioFetchTime)) {
                loadPortfolioSilently()
            }
            if (StockTradePolicy.isStock(currentSymbol)) {
                fetchStockDetail(currentSymbol, activeSocketGeneration)
                startStockPolling(currentSymbol, activeSocketGeneration)
            }
            loadEmbeddedWatchlist()
        }
    }

    override fun onDestroyView() {
        _binding?.pbLoading?.cleanup()
        _binding?.pbStockCatalogLoading?.cleanup()
        isFragmentVisible = false
        disconnectWebSocket()
        stopStockPolling()
        activeCandleCall?.cancel()
        activeCandleCall = null
        activePortfolioCall?.cancel()
        activePortfolioCall = null
        activeStockDetailCall?.cancel()
        activeStockDetailCall = null
        activeStockCatalogCall?.cancel()
        activeStockCatalogCall = null
        activeEmbeddedWatchlistCall?.cancel()
        activeEmbeddedWatchlistCall = null
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectWebSocket()
        stopStockPolling()
    }

    private fun disconnectWebSocket() {
        cancelReconnect()
        val ws = binanceWebSocket
        binanceWebSocket = null
        ws?.close(1000, "Disconnecting")
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun startStockPolling(symbol: String, generation: Long) {
        stopStockPolling()
        if (!StockTradePolicy.isStock(symbol) || !isFragmentVisible) return

        stockPollingRunnable = object : Runnable {
            override fun run() {
                val isTradingTab = binding?.tabLayoutTradingMode?.selectedTabPosition == 0
                if (!StockPollingPolicy.shouldPoll(
                        isStock = StockTradePolicy.isStock(currentSymbol),
                        isFragmentVisible = isFragmentVisible && !isHidden,
                        isTradingTabSelected = isTradingTab
                    ) || generation != activeSocketGeneration
                ) {
                    return
                }
                fetchStockDetail(currentSymbol, generation)
                loadCandleData(currentSymbol, generation)
                stockPollingHandler.postDelayed(this, StockPollingPolicy.STOCK_POLL_INTERVAL_MS)
            }
        }
        stockPollingHandler.postDelayed(stockPollingRunnable!!, StockPollingPolicy.STOCK_POLL_INTERVAL_MS)
    }

    private fun stopStockPolling() {
        stockPollingRunnable?.let { stockPollingHandler.removeCallbacks(it) }
        stockPollingRunnable = null
    }

    private fun setupTabs() {
        val b = binding ?: return
        b.tabLayoutTradingMode.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        b.layoutTradingContainer.visibility = View.VISIBLE
                        b.layoutStockCatalogContainer.visibility = View.GONE
                        if (StockTradePolicy.isStock(currentSymbol)) {
                            startStockPolling(currentSymbol, activeSocketGeneration)
                        }
                        loadEmbeddedWatchlist()
                    }
                    1 -> {
                        b.layoutTradingContainer.visibility = View.GONE
                        b.layoutStockCatalogContainer.visibility = View.VISIBLE
                        stopStockPolling()
                        loadStockCatalog()
                    }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {
                if (tab?.position == 1) {
                    loadStockCatalog()
                }
            }
        })
    }

    private fun setupStockCatalog() {
        val b = binding ?: return
        stockCatalogAdapter = StockCatalogAdapter(
            items = emptyList(),
            onItemClick = { stock ->
                b.tabLayoutTradingMode.getTabAt(0)?.select()
                switchMarketSymbol(stock.symbol)
            },
            onWatchlistToggle = { stock ->
                toggleWatchlistForSymbol(stock.symbol)
            }
        )
        b.rvStockCatalog.layoutManager = LinearLayoutManager(requireContext())
        b.rvStockCatalog.adapter = stockCatalogAdapter
    }

    private fun loadStockCatalog() {
        val b = binding ?: return
        b.pbStockCatalogLoading.visibility = View.VISIBLE

        activeStockCatalogCall?.cancel()
        val call = RetrofitClient.apiService.getStocks()
        activeStockCatalogCall = call

        call.enqueue(object : Callback<List<StockCatalogDto>> {
            override fun onResponse(call: Call<List<StockCatalogDto>>, response: Response<List<StockCatalogDto>>) {
                if (activeStockCatalogCall !== call) return
                if (!isAdded || _binding == null) return
                b.pbStockCatalogLoading.visibility = View.GONE

                if (response.code() == 401 || response.code() == 403) {
                    AuthSessionManager.handleUnauthorized(activity)
                    return
                }

                if (!response.isSuccessful) {
                    Log.w(TAG, "Lỗi khi tải danh mục cổ phiếu: code ${response.code()}")
                    context?.let { ctx ->
                        Toast.makeText(ctx, getString(R.string.stock_catalog_load_failed), Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                val stocks = response.body() ?: emptyList()
                cachedCatalogStocks = stocks
                refreshCatalogUI()

                // Fire getMarketPrices independently
                RetrofitClient.apiService.getMarketPrices().enqueue(object : Callback<List<com.example.nhumonglenh.data.remote.MarketPriceDto>> {
                    override fun onResponse(pCall: Call<List<com.example.nhumonglenh.data.remote.MarketPriceDto>>, pResp: Response<List<com.example.nhumonglenh.data.remote.MarketPriceDto>>) {
                        if (!isAdded || _binding == null) return
                        if (pResp.isSuccessful) {
                            cachedCatalogPrices = pResp.body() ?: emptyList()
                            refreshCatalogUI()
                            renderEmbeddedWatchlistFromCache()
                            updatePriceChangeForCurrentSymbol()
                        }
                    }
                    override fun onFailure(pCall: Call<List<com.example.nhumonglenh.data.remote.MarketPriceDto>>, t: Throwable) {
                        // Do nothing on error
                    }
                })

                // Fire getWatchlist independently
                val authHeader = AuthHeaderFactory.createBearerHeader(jwtToken)
                if (authHeader != null) {
                    RetrofitClient.apiService.getWatchlist(authHeader).enqueue(object : Callback<List<WatchlistItemDto>> {
                        override fun onResponse(wCall: Call<List<WatchlistItemDto>>, wResp: Response<List<WatchlistItemDto>>) {
                            if (!isAdded || _binding == null) return
                            if (wResp.isSuccessful) {
                                val watchlist = wResp.body() ?: emptyList()
                                val valid = watchlist.mapNotNull {
                                    val canon = canonicalTradingSymbol(it.symbol)
                                    if (SUPPORTED_BINANCE_SYMBOLS.contains(canon)) canon else null
                                }
                                cachedWatchlistSymbols.clear()
                                cachedWatchlistSymbols.addAll(valid)
                                refreshCatalogUI()
                                renderEmbeddedWatchlistFromCache()
                            }
                        }
                        override fun onFailure(wCall: Call<List<WatchlistItemDto>>, t: Throwable) {
                            // Do nothing on error
                        }
                    })
                }
            }

            override fun onFailure(call: Call<List<StockCatalogDto>>, t: Throwable) {
                if (activeStockCatalogCall !== call) return
                if (!isAdded || _binding == null) return
                b.pbStockCatalogLoading.visibility = View.GONE
                Log.e(TAG, "Lỗi mạng khi tải danh mục cổ phiếu: ${t.message}")
                context?.let { ctx ->
                    Toast.makeText(ctx, getString(R.string.network_error_msg), Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun refreshCatalogUI() {
        val b = binding ?: return
        if (cachedCatalogStocks.isEmpty()) return
        val watchlist = cachedWatchlistSymbols.map { WatchlistItemDto(symbol = it, name = it) }
        val uiModels = StockWatchlistMatcher.matchCatalogWithWatchlist(cachedCatalogStocks, watchlist, cachedCatalogPrices)
        stockCatalogAdapter.submitList(uiModels)
        updateWatchlistToggleButton()
    }

    private fun renderEmbeddedWatchlistFromCache() {
        val b = binding ?: return
        val validSymbols = cachedWatchlistSymbols
            .map { canonicalTradingSymbol(it) }
            .filter { SUPPORTED_BINANCE_SYMBOLS.contains(it) }
            .distinct()

        if (validSymbols.isEmpty()) {
            b.rvEmbeddedWatchlist.visibility = View.GONE
            b.tvEmbeddedWatchlistEmpty.visibility = View.VISIBLE
            embeddedWatchlistAdapter.updateData(emptyList())
            return
        }

        val uiModels = validSymbols.map { sym ->
            val priceDto = cachedCatalogPrices.firstOrNull {
                canonicalTradingSymbol(it.symbol) == sym || it.symbol?.equals(sym, ignoreCase = true) == true
            }
            val price = priceDto?.price ?: if (canonicalTradingSymbol(currentSymbol) == sym) currentAssetPrice else null
            val change = priceDto?.change24h
            WatchlistUiModel(
                symbol = sym,
                fullName = getFriendlyName(sym),
                price = price,
                changePercent = change,
                priceAsOf = null,
                recommendation = null,
                latestReportTitle = null,
                latestReportUrl = null,
                isStock = false
            )
        }

        b.rvEmbeddedWatchlist.visibility = View.VISIBLE
        b.tvEmbeddedWatchlistEmpty.visibility = View.GONE
        embeddedWatchlistAdapter.updateData(uiModels)
    }

    private fun setupEmbeddedWatchlist() {
        val b = binding ?: return
        embeddedWatchlistAdapter = WatchlistAdapter(
            items = emptyList(),
            onClick = { item ->
                switchMarketSymbol(item.symbol)
            },
            onReportClick = { reportUrl ->
                openReportUrl(reportUrl)
            }
        )
        b.rvEmbeddedWatchlist.layoutManager = LinearLayoutManager(requireContext())
        b.rvEmbeddedWatchlist.adapter = embeddedWatchlistAdapter
    }

    private fun loadEmbeddedWatchlist() {
        val b = binding ?: return
        val authHeader = AuthHeaderFactory.createBearerHeader(jwtToken) ?: return

        activeEmbeddedWatchlistCall?.cancel()
        val call = RetrofitClient.apiService.getWatchlist(authHeader)
        activeEmbeddedWatchlistCall = call

        call.enqueue(object : Callback<List<WatchlistItemDto>> {
            override fun onResponse(call: Call<List<WatchlistItemDto>>, response: Response<List<WatchlistItemDto>>) {
                if (activeEmbeddedWatchlistCall !== call) return
                if (!isAdded || _binding == null) return

                if (AuthHttpPolicy.isUnauthorized(response.code())) {
                    AuthSessionManager.handleUnauthorized(activity)
                    return
                }

                if (response.isSuccessful) {
                    val items = response.body()
                    if (!items.isNullOrEmpty()) {
                        val validItems = items.filter { dto ->
                            val sym = canonicalTradingSymbol(dto.symbol)
                            SUPPORTED_BINANCE_SYMBOLS.contains(sym)
                        }
                        cachedWatchlistSymbols.clear()
                        validItems.forEach { dto ->
                            cachedWatchlistSymbols.add(canonicalTradingSymbol(dto.symbol))
                        }
                        updateWatchlistToggleButton()

                        if (validItems.isNotEmpty()) {
                            if (cachedCatalogPrices.isEmpty()) {
                                RetrofitClient.apiService.getMarketPrices().enqueue(object : Callback<List<com.example.nhumonglenh.data.remote.MarketPriceDto>> {
                                    override fun onResponse(pCall: Call<List<com.example.nhumonglenh.data.remote.MarketPriceDto>>, pResp: Response<List<com.example.nhumonglenh.data.remote.MarketPriceDto>>) {
                                        if (!isAdded || _binding == null) return
                                        if (pResp.isSuccessful) {
                                            cachedCatalogPrices = pResp.body() ?: emptyList()
                                            renderEmbeddedWatchlistFromCache()
                                            updatePriceChangeForCurrentSymbol()
                                        }
                                    }
                                    override fun onFailure(pCall: Call<List<com.example.nhumonglenh.data.remote.MarketPriceDto>>, t: Throwable) {}
                                })
                            }
                            val distinctValidItems = validItems.distinctBy { canonicalTradingSymbol(it.symbol) }
                            val uiModels = distinctValidItems.map { dto ->
                                val sym = canonicalTradingSymbol(dto.symbol)
                                val fallbackPriceDto = cachedCatalogPrices.firstOrNull {
                                    canonicalTradingSymbol(it.symbol) == sym || it.symbol?.equals(sym, ignoreCase = true) == true
                                }
                                val price = fallbackPriceDto?.price ?: dto.currentPrice ?: if (canonicalTradingSymbol(currentSymbol) == sym) currentAssetPrice else null
                                val change = fallbackPriceDto?.change24h ?: dto.change24h
                                WatchlistUiModel(
                                    symbol = sym,
                                    fullName = dto.name?.takeIf { it.isNotBlank() } ?: getFriendlyName(sym),
                                    price = price,
                                    changePercent = change,
                                    priceAsOf = dto.priceAsOf,
                                    recommendation = dto.recommendation,
                                    latestReportTitle = dto.latestReportTitle,
                                    latestReportUrl = dto.latestReportUrl,
                                    isStock = false
                                )
                            }
                            b.rvEmbeddedWatchlist.visibility = View.VISIBLE
                            b.tvEmbeddedWatchlistEmpty.visibility = View.GONE
                            embeddedWatchlistAdapter.updateData(uiModels)
                        } else {
                            renderEmbeddedWatchlistFromCache()
                        }
                    } else {
                        cachedWatchlistSymbols.clear()
                        updateWatchlistToggleButton()
                        b.rvEmbeddedWatchlist.visibility = View.GONE
                        b.tvEmbeddedWatchlistEmpty.visibility = View.VISIBLE
                        embeddedWatchlistAdapter.updateData(emptyList())
                    }
                } else {
                    Log.w(TAG, "Không thể tải danh sách theo dõi: code ${response.code()}")
                    context?.let { ctx ->
                        Toast.makeText(ctx, getString(R.string.watchlist_load_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onFailure(call: Call<List<WatchlistItemDto>>, t: Throwable) {
                if (activeEmbeddedWatchlistCall !== call) return
                if (!isAdded || _binding == null) return
                Log.e(TAG, "Lỗi mạng khi tải embedded watchlist: ${t.message}")
                context?.let { ctx ->
                    Toast.makeText(ctx, getString(R.string.network_error_msg), Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun setupWatchlistToggleAction() {
        binding?.btnTradingWatchlistToggle?.setOnClickListener {
            toggleWatchlistForSymbol(currentSymbol)
        }
    }

    private fun updateWatchlistToggleButton() {
        val b = binding ?: return
        val cleanSym = canonicalTradingSymbol(currentSymbol)
        val isWatchlisted = cachedWatchlistSymbols.contains(cleanSym)
        if (isWatchlisted) {
            b.btnTradingWatchlistToggle.text = getString(R.string.btn_watchlist_remove)
            b.btnTradingWatchlistToggle.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.tv_surface))
            b.btnTradingWatchlistToggle.setTextColor(ContextCompat.getColor(requireContext(), R.color.tv_text_secondary))
        } else {
            b.btnTradingWatchlistToggle.text = getString(R.string.btn_watchlist_toggle_add)
            b.btnTradingWatchlistToggle.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.tv_green))
            b.btnTradingWatchlistToggle.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
        }
    }

    private fun toggleWatchlistForSymbol(symbol: String) {
        val cleanSym = canonicalTradingSymbol(symbol)
        val authHeader = AuthHeaderFactory.createBearerHeader(jwtToken) ?: return
        val isCurrentlyWatchlisted = cachedWatchlistSymbols.contains(cleanSym)

        if (isCurrentlyWatchlisted) {
            RetrofitClient.apiService.removeFromWatchlist(authHeader, cleanSym).enqueue(object : Callback<Map<String, String>> {
                override fun onResponse(call: Call<Map<String, String>>, response: Response<Map<String, String>>) {
                    if (AuthHttpPolicy.isUnauthorized(response.code())) {
                        AuthSessionManager.handleUnauthorized(activity)
                        return
                    }
                    val updated = WatchlistStateReducer.reduce(
                        cachedWatchlistSymbols,
                        WatchlistMutation.Remove(cleanSym),
                        isSuccess = response.isSuccessful
                    )
                    cachedWatchlistSymbols.clear()
                    cachedWatchlistSymbols.addAll(updated)
                    renderEmbeddedWatchlistFromCache()
                    refreshCatalogUI()
                    updateWatchlistToggleButton()

                    if (response.isSuccessful) {
                        loadEmbeddedWatchlist()
                        loadStockCatalog()
                        context?.let {
                            Toast.makeText(it, getString(R.string.watchlist_remove_success, cleanSym), Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Log.w(TAG, "Xóa khỏi watchlist thất bại: code ${response.code()}")
                        context?.let {
                            Toast.makeText(it, getString(R.string.watchlist_update_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onFailure(call: Call<Map<String, String>>, t: Throwable) {
                    Log.e(TAG, "Lỗi mạng khi xóa khỏi watchlist: ${t.message}")
                    val updated = WatchlistStateReducer.reduce(
                        cachedWatchlistSymbols,
                        WatchlistMutation.Remove(cleanSym),
                        isSuccess = false
                    )
                    cachedWatchlistSymbols.clear()
                    cachedWatchlistSymbols.addAll(updated)
                    renderEmbeddedWatchlistFromCache()
                    refreshCatalogUI()
                    updateWatchlistToggleButton()
                    context?.let {
                        Toast.makeText(it, getString(R.string.network_error_msg), Toast.LENGTH_SHORT).show()
                    }
                }
            })
        } else {
            RetrofitClient.apiService.addToWatchlist(authHeader, WatchlistRequest(cleanSym)).enqueue(object : Callback<WatchlistItemDto> {
                override fun onResponse(call: Call<WatchlistItemDto>, response: Response<WatchlistItemDto>) {
                    if (AuthHttpPolicy.isUnauthorized(response.code())) {
                        AuthSessionManager.handleUnauthorized(activity)
                        return
                    }
                    val updated = WatchlistStateReducer.reduce(
                        cachedWatchlistSymbols,
                        WatchlistMutation.Add(cleanSym),
                        isSuccess = response.isSuccessful
                    )
                    cachedWatchlistSymbols.clear()
                    cachedWatchlistSymbols.addAll(updated)
                    renderEmbeddedWatchlistFromCache()
                    refreshCatalogUI()
                    updateWatchlistToggleButton()

                    if (response.isSuccessful) {
                        loadEmbeddedWatchlist()
                        loadStockCatalog()
                        context?.let {
                            Toast.makeText(it, getString(R.string.watchlist_add_success, cleanSym), Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Log.w(TAG, "Thêm vào watchlist thất bại: code ${response.code()}")
                        context?.let {
                            Toast.makeText(it, getString(R.string.watchlist_update_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                override fun onFailure(call: Call<WatchlistItemDto>, t: Throwable) {
                    Log.e(TAG, "Lỗi mạng khi thêm vào watchlist: ${t.message}")
                    val updated = WatchlistStateReducer.reduce(
                        cachedWatchlistSymbols,
                        WatchlistMutation.Add(cleanSym),
                        isSuccess = false
                    )
                    cachedWatchlistSymbols.clear()
                    cachedWatchlistSymbols.addAll(updated)
                    renderEmbeddedWatchlistFromCache()
                    refreshCatalogUI()
                    updateWatchlistToggleButton()
                    context?.let {
                        Toast.makeText(it, getString(R.string.network_error_msg), Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }
    }

    private fun openReportUrl(url: String?) {
        if (url.isNullOrBlank()) return
        try {
            val customTabsIntent = CustomTabsIntent.Builder().build()
            customTabsIntent.launchUrl(requireContext(), Uri.parse(url))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e2: Exception) {
                Toast.makeText(requireContext(), getString(R.string.report_open_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Cấu hình thẩm mỹ chuẩn Dark Theme cho MPAndroidChart sử dụng màu R.color
     */
    private fun setupCandleChartStyle() {
        val chart = binding?.candleChart ?: return
        val ctx = context ?: return
        chart.apply {
            setNoDataText("")
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.tv_bg))
            description.isEnabled = false
            legend.textColor = ContextCompat.getColor(ctx, R.color.white)
            setDrawGridBackground(false)
            isDoubleTapToZoomEnabled = true
            setPinchZoom(true)

            // Custom renderer vẽ nến doji / đứng giá rõ nét với độ dày tối thiểu minDojiStrokePx
            renderer = FnmfCandleStickChartRenderer(
                chart = this,
                animator = this.animator,
                viewPortHandler = this.viewPortHandler,
                minDojiStrokePx = 4.0f
            )

            // Trục X (Thời gian)
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                textColor = ContextCompat.getColor(ctx, R.color.tv_text_secondary)
                setDrawGridLines(false)
                setAvoidFirstLastClipping(true)
            }

            // Trục Y bên Trái (Giá tiền)
            axisLeft.apply {
                textColor = ContextCompat.getColor(ctx, R.color.tv_text_secondary)
                gridColor = ContextCompat.getColor(ctx, R.color.tv_border)
                setDrawAxisLine(false)
                spaceTop = 15f
                spaceBottom = 15f
            }

            // Tắt trục Y bên Phải
            axisRight.isEnabled = false
        }
    }

    /**
     * Chuyển đổi mã tài sản hiển thị biểu đồ Nến (BTCUSDT, ETHUSDT, AAPL, MSFT...)
     */
    fun switchMarketSymbol(symbol: String, isInitial: Boolean = false) {
        val sym = symbol.uppercase(Locale.ROOT)
        currentSymbol = sym
        (activity as? Activity2)?.updateActiveSymbol(sym)

        activeSocketGeneration++
        val generation = activeSocketGeneration

        activeCandleCall?.cancel()
        activeCandleCall = null
        activeStockDetailCall?.cancel()
        activeStockDetailCall = null
        disconnectWebSocket()
        stopStockPolling()
        reconnectAttempts = 0

        currentCandles.clear()
        candleEntries.clear()
        timeLabels.clear()
        candleTimeLabelsMap.clear()
        nextCandleXIndex = 0f
        candleDataSet = null
        loadedCandleSymbol = null

        val b = binding ?: return
        val ctx = context ?: return

        val isStock = StockTradePolicy.isStock(sym)

        // 1. Cập nhật Tiêu đề Header & Provider badge
        b.tvHeaderSymbol.text = formatSymbolDisplay(sym)
        b.tvHeaderFullName.text = if (MarketStreamHelper.isGoldReferenceStream(sym)) {
            getString(R.string.trading_gold_paxg_reference)
        } else {
            getFriendlyName(sym)
        }
        b.tvProviderBadge.text = MarketDataProviderPolicy.resolveProviderName(sym)
        b.tvTimeframeBadge.text = "1s"

        // 2. Xóa giá hiển thị cũ, đưa về trạng thái chờ
        currentAssetPrice = null
        previousAssetPrice = null
        baselinePeriodPrice = null
        isStockDetailStale = false

        b.tvCurrentPrice.text = "—"
        b.tvCurrentPrice.setTextColor(ContextCompat.getColor(ctx, R.color.tv_text_primary))
        b.tvPriceChange.text = "—"
        b.tvPriceChange.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.tv_surface))

        b.candleChart.clear()

        if (!isInitial) {
            Toast.makeText(ctx, getString(R.string.trading_toast_switch_symbol, sym), Toast.LENGTH_SHORT).show()
        }

        updateWatchlistToggleButton()
        updateTradeActionsState()

        // 3. Tải nến (loadCandleData sẽ kết nối WebSocket đúng 1 lần sau khi nạp xong hoặc fallback)
        loadCandleData(sym, generation)

        // 4. Luồng xử lý Cổ phiếu vs Crypto/Gold
        if (isStock) {
            b.tvLiveStatus.text = getString(R.string.trading_no_live_badge)
            b.tvLiveStatus.setTextColor(ContextCompat.getColor(ctx, R.color.tv_text_secondary))
            fetchStockDetail(sym, generation)
            startStockPolling(sym, generation)
        } else {
            b.llStockMetaRow.visibility = View.GONE
            b.tvTradeWarningMessage.visibility = View.GONE
            updatePriceChangeForCurrentSymbol()

            val streamName = MarketStreamHelper.resolveWebSocketStream(sym)
            if (streamName == null) {
                b.tvLiveStatus.text = getString(R.string.trading_no_live_badge)
                b.tvLiveStatus.setTextColor(ContextCompat.getColor(ctx, R.color.tv_text_secondary))
            } else {
                b.tvLiveStatus.text = getString(R.string.trading_connecting_badge)
                b.tvLiveStatus.setTextColor(ContextCompat.getColor(ctx, R.color.tv_yellow))
            }
        }

        // 5. Cập nhật số dư & lượng tài sản sở hữu
        updateHoldingsForCurrentSymbol()
        updatePortfolioDisplay()
        loadEmbeddedWatchlist()
    }

    private fun fetchStockDetail(symbol: String, generation: Long = activeSocketGeneration) {
        activeStockDetailCall?.cancel()
        val call = RetrofitClient.apiService.getStockDetail(symbol)
        activeStockDetailCall = call

        val b = binding ?: return
        val ctx = context ?: return

        call.enqueue(object : Callback<StockDetailDto> {
            override fun onResponse(call: Call<StockDetailDto>, response: Response<StockDetailDto>) {
                if (activeStockDetailCall !== call || generation != activeSocketGeneration || !symbol.equals(currentSymbol, ignoreCase = true)) return
                if (!isAdded || _binding == null) return

                if (response.code() == 401 || response.code() == 403) {
                    AuthSessionManager.handleUnauthorized(activity)
                    return
                }

                val detail = response.body()
                if (response.isSuccessful && detail != null) {
                    val price = detail.currentPrice
                    isStockDetailStale = detail.stale == true

                    // Update Provider Badge
                    b.tvProviderBadge.text = MarketDataProviderPolicy.resolveProviderName(symbol, detail.marketDataProvider)

                    // Update Status Badge (Stale vs Live)
                    val badgeState = StockBadgePolicy.resolveStatusBadge(
                        isStock = true,
                        isStale = isStockDetailStale,
                        hasValidPrice = price != null && price > 0.0
                    )
                    b.tvLiveStatus.text = getString(badgeState.textRes)
                    b.tvLiveStatus.setTextColor(ContextCompat.getColor(ctx, badgeState.colorRes))

                    if (price != null && price > 0.0) {
                        currentAssetPrice = price
                        b.tvCurrentPrice.text = PriceFormatter.formatPrice(price)
                        b.tvCurrentPrice.setTextColor(ContextCompat.getColor(ctx, R.color.tv_text_primary))
                    } else {
                        currentAssetPrice = null
                        b.tvCurrentPrice.text = "—"
                    }

                    if (detail.change24h != null) {
                        val sign = if (detail.change24h >= 0) "+" else ""
                        b.tvPriceChange.text = String.format(Locale.US, "%s%.2f%%", sign, detail.change24h)
                        val colorRes = if (detail.change24h >= 0) R.color.tv_green else R.color.tv_red
                        b.tvPriceChange.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, colorRes))
                    } else {
                        b.tvPriceChange.text = "—"
                        b.tvPriceChange.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.tv_surface))
                    }

                    // Stock metadata: recommendation & latest report
                    if (!detail.recommendation.isNullOrBlank()) {
                        b.tvStockRecommendation.text = detail.recommendation
                        b.tvStockRecommendation.visibility = View.VISIBLE
                    } else {
                        b.tvStockRecommendation.visibility = View.GONE
                    }

                    if (StockReportPolicy.shouldShowReportButton(detail.latestReportUrl)) {
                        b.btnStockReport.text = StockReportPolicy.resolveReportButtonLabel(detail.latestReportTitle)
                        b.btnStockReport.visibility = View.VISIBLE
                        b.btnStockReport.setOnClickListener {
                            openReportUrl(detail.latestReportUrl)
                        }
                    } else {
                        b.btnStockReport.visibility = View.GONE
                    }

                    b.llStockMetaRow.visibility = if (b.tvStockRecommendation.visibility == View.VISIBLE || b.btnStockReport.visibility == View.VISIBLE) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }

                    val tradeEnabled = StockTradePolicy.isTradeEnabled(
                        isStock = true,
                        stale = detail.stale,
                        currentPrice = detail.currentPrice
                    )
                    val statusMsg = StockTradePolicy.resolveTradeStatusMessage(
                        isStock = true,
                        stale = detail.stale,
                        currentPrice = detail.currentPrice
                    )

                    if (!tradeEnabled) {
                        b.tvTradeWarningMessage.text = statusMsg ?: getString(R.string.stock_trade_disabled_warning)
                        b.tvTradeWarningMessage.visibility = View.VISIBLE
                        b.btnBuy.isEnabled = false
                        b.btnBuy.alpha = 0.5f
                        b.btnSell.isEnabled = false
                        b.btnSell.alpha = 0.5f
                    } else {
                        b.tvTradeWarningMessage.visibility = View.GONE
                        updateTradeActionsState()
                    }
                } else {
                    isStockDetailStale = true
                    val badgeState = StockBadgePolicy.resolveStatusBadge(
                        isStock = true,
                        isStale = true,
                        hasValidPrice = false
                    )
                    b.tvLiveStatus.text = getString(badgeState.textRes)
                    b.tvLiveStatus.setTextColor(ContextCompat.getColor(ctx, badgeState.colorRes))
                    b.tvTradeWarningMessage.text = getString(R.string.stock_trade_disabled_warning)
                    b.tvTradeWarningMessage.visibility = View.VISIBLE
                    b.btnBuy.isEnabled = false
                    b.btnBuy.alpha = 0.5f
                    b.btnSell.isEnabled = false
                    b.btnSell.alpha = 0.5f
                }
            }

            override fun onFailure(call: Call<StockDetailDto>, t: Throwable) {
                if (activeStockDetailCall !== call || generation != activeSocketGeneration || !symbol.equals(currentSymbol, ignoreCase = true)) return
                if (!isAdded || _binding == null) return
                isStockDetailStale = true
                val badgeState = StockBadgePolicy.resolveStatusBadge(
                    isStock = true,
                    isStale = true,
                    hasValidPrice = false
                )
                b.tvLiveStatus.text = getString(badgeState.textRes)
                b.tvLiveStatus.setTextColor(ContextCompat.getColor(ctx, badgeState.colorRes))
                b.tvTradeWarningMessage.text = getString(R.string.stock_trade_disabled_warning)
                b.tvTradeWarningMessage.visibility = View.VISIBLE
                b.btnBuy.isEnabled = false
                b.btnBuy.alpha = 0.5f
                b.btnSell.isEnabled = false
                b.btnSell.alpha = 0.5f
            }
        })
    }

    private fun releaseCandleCall(call: Call<List<CandleDto>>?) {
        if (activeCandleCall == call) {
            activeCandleCall = null
        }
    }

    private fun releasePortfolioCall(call: Call<PortfolioSummaryDto>?) {
        if (activePortfolioCall == call) {
            activePortfolioCall = null
        }
    }

    /**
     * Tải dữ liệu nến từ Backend qua Retrofit (1m cho cả Crypto/Commodity và Cổ phiếu)
     */
    private fun loadCandleData(symbol: String, generation: Long = activeSocketGeneration) {
        setLoadingState(true)

        activeCandleCall?.cancel()
        val isStock = StockTradePolicy.isStock(symbol)
        val interval = "1s"
        val call = RetrofitClient.apiService.getCandles(symbol, interval)
        activeCandleCall = call

        call.enqueue(object : Callback<List<CandleDto>> {
            override fun onResponse(call: Call<List<CandleDto>>, response: Response<List<CandleDto>>) {
                try {
                    if (call.isCanceled || generation != activeSocketGeneration || !symbol.equals(currentSymbol, ignoreCase = true) || !isAdded || _binding == null) return
                    setLoadingState(false)

                    if (response.code() == 401 || response.code() == 403) {
                        AuthSessionManager.handleUnauthorized(activity)
                        return
                    }

                    if (response.code() == 503) {
                        context?.let { ctx ->
                            Toast.makeText(ctx, getString(R.string.trading_err_no_candles), Toast.LENGTH_SHORT).show()
                        }
                        handleCandleLoadFallback(symbol)
                        if (!isStock && isFragmentVisible && generation == activeSocketGeneration) {
                            connectWebSocketForSymbol(symbol, generation)
                        }
                        return
                    }

                    val candles = response.body()
                    if (response.isSuccessful && !candles.isNullOrEmpty()) {
                        renderCandleChart(candles, symbol)
                        if (!isStock && isFragmentVisible && generation == activeSocketGeneration) {
                            connectWebSocketForSymbol(symbol, generation)
                        }
                    } else {
                        Log.w(TAG, "API nến rỗng hoặc lỗi code: ${response.code()} cho symbol $symbol")
                        handleCandleLoadFallback(symbol)
                        if (!isStock && isFragmentVisible && generation == activeSocketGeneration) {
                            connectWebSocketForSymbol(symbol, generation)
                        }
                    }
                } finally {
                    releaseCandleCall(call)
                }
            }

            override fun onFailure(call: Call<List<CandleDto>>, t: Throwable) {
                try {
                    if (call.isCanceled || generation != activeSocketGeneration || !symbol.equals(currentSymbol, ignoreCase = true) || !isAdded || _binding == null) return
                    setLoadingState(false)
                    Log.e(TAG, "Lỗi kết nối Retrofit nến: ${t.message}")
                    handleCandleLoadFallback(symbol)
                    if (!isStock && isFragmentVisible && generation == activeSocketGeneration) {
                        connectWebSocketForSymbol(symbol, generation)
                    }
                } finally {
                    releaseCandleCall(call)
                }
            }
        })
    }

    private fun handleCandleLoadFallback(symbol: String) {
        val b = binding ?: return

        val decision = CandleFallbackPolicy.decide(
            currentSymbol = symbol,
            loadedCandleSymbol = loadedCandleSymbol,
            hasCachedCandles = currentCandles.isNotEmpty()
        )

        b.pbLoading.visibility = View.GONE
        if (decision.shouldClearChart) {
            currentCandles.clear()
            candleEntries.clear()
            timeLabels.clear()
            candleTimeLabelsMap.clear()
            nextCandleXIndex = 0f
            b.candleChart.clear()
            b.tvStateMessage.text = getString(R.string.trading_err_no_candles)
            b.tvStateMessage.visibility = View.VISIBLE
        } else {
            b.tvStateMessage.visibility = View.GONE
        }
    }

    private fun renderCandleChart(candles: List<CandleDto>, symbol: String) {
        if (!symbol.equals(currentSymbol, ignoreCase = true)) return
        val b = binding ?: return
        val ctx = context ?: return

        ChartSeriesPolicy.safeSyncCandles(currentCandles, candles)

        candleEntries.clear()
        timeLabels.clear()
        candleTimeLabelsMap.clear()

        for (i in candles.indices) {
            val c = candles[i]
            candleEntries.add(CandleEntry(i.toFloat(), c.high.toFloat(), c.low.toFloat(), c.open.toFloat(), c.close.toFloat()))
            val label = CandleTimeFormatter.formatCandleAxisLabel(c)
            timeLabels.add(label)
            candleTimeLabelsMap[i] = label
        }
        nextCandleXIndex = candles.size.toFloat()

        b.pbLoading.visibility = View.GONE

        if (candleEntries.isEmpty()) {
            b.candleChart.clear()
            b.tvStateMessage.text = getString(R.string.trading_chart_empty)
            b.tvStateMessage.visibility = View.VISIBLE
            return
        }

        b.tvStateMessage.visibility = View.GONE
        loadedCandleSymbol = symbol

        b.candleChart.xAxis.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val index = Math.round(value)
                return candleTimeLabelsMap[index] ?: ""
            }
        }

        val datasetLabel = ChartLabelFormatter.formatChartDatasetLabel(symbol, "1s")
        val dataSet = CandleDataSet(candleEntries, datasetLabel).apply {
            setDrawIcons(false)
            shadowColor = ContextCompat.getColor(ctx, R.color.tv_text_secondary)
            shadowWidth = 1.5f
            decreasingColor = ContextCompat.getColor(ctx, R.color.tv_red)
            decreasingPaintStyle = Paint.Style.FILL
            increasingColor = ContextCompat.getColor(ctx, R.color.tv_green)
            increasingPaintStyle = Paint.Style.FILL
            neutralColor = ContextCompat.getColor(ctx, R.color.white)
            setDrawValues(false)
            highLightColor = ContextCompat.getColor(ctx, R.color.white)
            barSpace = 0.1f
            shadowColorSameAsCandle = true
        }

        candleDataSet = dataSet
        b.candleChart.data = CandleData(dataSet)
        adjustChartYAxisRange(dataSet)
        b.candleChart.setVisibleXRangeMaximum(VIEWPORT_VISIBLE_MAX_CANDLES)
        b.candleChart.setVisibleXRangeMinimum(VIEWPORT_VISIBLE_MIN_CANDLES)
        if (nextCandleXIndex > 0f) {
            b.candleChart.moveViewToX(nextCandleXIndex - 1f)
        }
        b.candleChart.invalidate()

        if (currentAssetPrice == null && candles.isNotEmpty()) {
            val last = candles.last()
            updateLivePriceDisplay(last.close, last.open)
        }
    }

    private fun adjustChartYAxisRange(dataSet: CandleDataSet) {
        val chart = binding?.candleChart ?: return
        val range = ChartAxisPolicy.calculateYAxisRange(dataSet.yMin, dataSet.yMax)
        if (range.isCustomRange) {
            chart.axisLeft.axisMinimum = range.axisMinimum
            chart.axisLeft.axisMaximum = range.axisMaximum
        } else {
            chart.axisLeft.resetAxisMinimum()
            chart.axisLeft.resetAxisMaximum()
        }
    }

    private fun connectWebSocketForSymbol(symbol: String, generation: Long = activeSocketGeneration) {
        disconnectWebSocket()

        if (StockTradePolicy.isStock(symbol) || !isFragmentVisible || generation != activeSocketGeneration) {
            return
        }

        val streamName = MarketStreamHelper.resolveWebSocketStream(symbol) ?: return
        val wsUrl = MarketStreamHelper.buildWebSocketUrl(streamName)
        val request = Request.Builder().url(wsUrl).build()

        val b = binding
        val ctx = context
        if (b != null && ctx != null) {
            b.tvLiveStatus.text = getString(R.string.trading_connecting_badge)
            b.tvLiveStatus.setTextColor(ContextCompat.getColor(ctx, R.color.tv_yellow))
        }

        val ws = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                activity?.runOnUiThread {
                    if (!SocketCallbackGuard.isCallbackAllowed(
                            callbackSocket = webSocket,
                            activeSocket = binanceWebSocket,
                            callbackGeneration = generation,
                            activeGeneration = activeSocketGeneration,
                            callbackSymbol = symbol,
                            activeSymbol = currentSymbol,
                            isFragmentVisible = isFragmentVisible
                        ) || !isAdded || _binding == null
                    ) return@runOnUiThread

                    binding?.tvLiveStatus?.text = getString(R.string.trading_waiting_price_badge)
                    binding?.tvLiveStatus?.setTextColor(ContextCompat.getColor(requireContext(), R.color.tv_yellow))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                activity?.runOnUiThread {
                    if (!SocketCallbackGuard.isCallbackAllowed(
                            callbackSocket = webSocket,
                            activeSocket = binanceWebSocket,
                            callbackGeneration = generation,
                            activeGeneration = activeSocketGeneration,
                            callbackSymbol = symbol,
                            activeSymbol = currentSymbol,
                            isFragmentVisible = isFragmentVisible
                        ) || !isAdded || _binding == null
                    ) return@runOnUiThread

                    handleWebSocketMessage(text, symbol, generation, webSocket)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                activity?.runOnUiThread {
                    val decision = SocketReconnectPolicy.decideFailureAction(
                        callbackSocket = webSocket,
                        activeSocket = binanceWebSocket,
                        callbackGeneration = generation,
                        activeGeneration = activeSocketGeneration,
                        currentAttempt = reconnectAttempts,
                        isVisible = isFragmentVisible
                    )

                    if (decision.shouldClearActiveSocket) {
                        binanceWebSocket = null
                    }

                    if (!decision.shouldScheduleReconnect) {
                        if (webSocket === binanceWebSocket) {
                            val currentContext = context ?: return@runOnUiThread
                            binding?.tvLiveStatus?.text = getString(R.string.trading_offline_badge)
                            binding?.tvLiveStatus?.setTextColor(ContextCompat.getColor(currentContext, R.color.tv_red))
                        }
                        return@runOnUiThread
                    }

                    val currentContext = context ?: return@runOnUiThread
                    binding?.tvLiveStatus?.text = getString(R.string.trading_offline_badge)
                    binding?.tvLiveStatus?.setTextColor(ContextCompat.getColor(currentContext, R.color.tv_red))

                    reconnectAttempts = decision.nextAttempt
                    reconnectRunnable = Runnable {
                        if (generation == activeSocketGeneration && isFragmentVisible) {
                            connectWebSocketForSymbol(symbol, generation)
                        }
                    }
                    reconnectHandler.postDelayed(reconnectRunnable!!, decision.delayMs)
                }
            }
        })
        binanceWebSocket = ws
    }

    private fun handleWebSocketMessage(jsonText: String, expectedSymbol: String, generation: Long, socket: WebSocket? = null) {
        if (!SocketCallbackGuard.isCallbackAllowed(
                callbackSocket = socket ?: binanceWebSocket,
                activeSocket = binanceWebSocket,
                callbackGeneration = generation,
                activeGeneration = activeSocketGeneration,
                callbackSymbol = expectedSymbol,
                activeSymbol = currentSymbol,
                isFragmentVisible = isFragmentVisible
            ) || _binding == null || !isAdded
        ) return

        val event = BinanceKlineParser.parse(jsonText) ?: return

        if (!MarketSymbolMatcher.matches(event.symbol, currentSymbol)) {
            return
        }

        // Nhận được kline chuẩn từ Binance -> reset bộ đếm reconnect về 0
        reconnectAttempts = 0

        val lastCandle = currentCandles.lastOrNull()
        val lastOpenTime = lastCandle?.openTime ?: lastCandle?.let { CandleTimeFormatter.parseTimeToMillis(it.time) } ?: 0L

        if (lastCandle != null && event.openTime < lastOpenTime) {
            // Event cũ hơn nến hiện tại -> bỏ qua
            return
        }

        updateCandleChartInPlace(event)

        updateLivePriceDisplay(event.close, event.open)
    }

    private fun updatePriceChangeForCurrentSymbol() {
        val b = binding ?: return
        val ctx = context ?: return
        val canon = canonicalTradingSymbol(currentSymbol)
        val priceDto = cachedCatalogPrices.firstOrNull { canonicalTradingSymbol(it.symbol) == canon }
        if (priceDto != null) {
            if (currentAssetPrice == null && priceDto.price != null && priceDto.price > 0.0) {
                currentAssetPrice = priceDto.price
                b.tvCurrentPrice.text = PriceFormatter.formatPrice(priceDto.price)
                b.tvCurrentPrice.setTextColor(ContextCompat.getColor(ctx, R.color.tv_text_primary))
            }
            if (priceDto.change24h != null) {
                val sign = if (priceDto.change24h >= 0) "+" else ""
                b.tvPriceChange.text = String.format(Locale.US, "%s%.2f%%", sign, priceDto.change24h)
                val colorRes = if (priceDto.change24h >= 0) R.color.tv_green else R.color.tv_red
                b.tvPriceChange.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, colorRes))
            } else {
                b.tvPriceChange.text = "—"
                b.tvPriceChange.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.tv_surface))
            }
        } else {
            b.tvPriceChange.text = "—"
            b.tvPriceChange.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.tv_surface))
        }
    }

    private fun updateLivePriceDisplay(livePrice: Double, periodOpenPrice: Double?) {
        val b = binding ?: return
        val ctx = context ?: return

        previousAssetPrice = currentAssetPrice
        currentAssetPrice = livePrice

        b.tvLiveStatus.text = getString(R.string.trading_live_badge)
        b.tvLiveStatus.setTextColor(ContextCompat.getColor(ctx, R.color.tv_green))

        b.tvCurrentPrice.text = PriceFormatter.formatPrice(livePrice)
        val priceColor = when {
            previousAssetPrice != null && livePrice > previousAssetPrice!! -> R.color.tv_green
            previousAssetPrice != null && livePrice < previousAssetPrice!! -> R.color.tv_red
            else -> R.color.tv_text_primary
        }
        b.tvCurrentPrice.setTextColor(ContextCompat.getColor(ctx, priceColor))

        val canon = canonicalTradingSymbol(currentSymbol)
        val priceDto = cachedCatalogPrices.firstOrNull { canonicalTradingSymbol(it.symbol) == canon }
        if (priceDto?.change24h != null) {
            val sign = if (priceDto.change24h >= 0) "+" else ""
            b.tvPriceChange.text = String.format(Locale.US, "%s%.2f%%", sign, priceDto.change24h)
            val badgeColor = if (priceDto.change24h >= 0) R.color.tv_green else R.color.tv_red
            b.tvPriceChange.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, badgeColor))
        } else {
            b.tvPriceChange.text = "—"
            b.tvPriceChange.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.tv_surface))
        }

        updateHoldingsForCurrentSymbol()
        updatePortfolioDisplay()
        updateTradeActionsState()
    }

    /**
     * Cập nhật biểu đồ nến tại chỗ (in-place dataset update) tối ưu 60 FPS:
     * - Cùng giây (event.openTime == lastOpenTime): Cập nhật OHLC của CandleEntry cuối, gọi calcMinMax và invalidate (0 allocation).
     * - Sang giây mới (event.openTime > lastOpenTime): Thêm CandleEntry mới với mốc X tăng đơn điệu (monotonic X),
     *   loại bỏ nến cũ nhất nếu vượt quá giới hạn bộ đệm (maxCandles = 90).
     * - Giữ nguyên ma trận zoom/pan của người dùng; chỉ tự động cuộn (auto-scroll) nếu đang xem ở mép phải biểu đồ.
     */
    private fun updateCandleChartInPlace(event: BinanceKlineEvent) {
        val b = binding ?: return
        val chart = b.candleChart
        val dataSet = candleDataSet
        val chartData = chart.candleData ?: chart.data

        if (dataSet == null || chartData == null || currentCandles.isEmpty()) {
            val single = ChartSeriesPolicy.createSingleCandleDto(event)
            renderCandleChart(listOf(single), currentSymbol)
            return
        }

        val lastCandle = currentCandles.last()
        val lastOpenTime = lastCandle.openTime ?: CandleTimeFormatter.parseTimeToMillis(lastCandle.time)

        if (event.openTime == lastOpenTime) {
            // CÙNG GIÂY: Cập nhật cây nến cuối tại chỗ (không cấp phát đối tượng mới)
            val updatedLast = lastCandle.copy(
                open = event.open,
                high = event.high,
                low = event.low,
                close = event.close,
                volume = event.volume,
                openTime = event.openTime,
                isClosed = event.isClosed
            )
            currentCandles[currentCandles.size - 1] = updatedLast

            if (dataSet.entryCount > 0) {
                val lastEntry = dataSet.getEntryForIndex(dataSet.entryCount - 1)
                lastEntry.open = event.open.toFloat()
                lastEntry.close = event.close.toFloat()
                lastEntry.high = event.high.toFloat()
                lastEntry.low = event.low.toFloat()

                dataSet.calcMinMax()
                adjustChartYAxisRange(dataSet)
                chartData.notifyDataChanged()
                chart.notifyDataSetChanged()
                chart.invalidate()
            }
        } else if (event.openTime > lastOpenTime) {
            // SANG GIÂY MỚI: Thêm nến mới với mốc X tăng liên tục, giữ tối đa maxCandleHistory
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
            currentCandles.add(newCandle)
            if (currentCandles.size > maxCandleHistory) {
                currentCandles.removeAt(0)
            }

            val newX = nextCandleXIndex
            nextCandleXIndex += 1f

            val newEntry = CandleEntry(
                newX,
                event.high.toFloat(),
                event.low.toFloat(),
                event.open.toFloat(),
                event.close.toFloat()
            )
            dataSet.addEntry(newEntry)

            val label = CandleTimeFormatter.formatCandleAxisLabel(newCandle)
            candleTimeLabelsMap[newX.toInt()] = label

            if (dataSet.entryCount > maxCandleHistory) {
                val oldestEntry = dataSet.getEntryForIndex(0)
                candleTimeLabelsMap.remove(oldestEntry.x.toInt())
                dataSet.removeEntry(0)
            }

            dataSet.calcMinMax()
            adjustChartYAxisRange(dataSet)
            chartData.notifyDataChanged()
            chart.notifyDataSetChanged()

            // Tự động bám theo mép phải nếu người dùng không cuộn lùi về quá khứ
            val highestVisibleX = chart.highestVisibleX
            val wasAtRightEdge = (newX - highestVisibleX) <= 2.5f
            if (wasAtRightEdge) {
                chart.moveViewToX(newX)
            }

            chart.invalidate()
        }
    }

    private fun updatePortfolioDisplay() {
        val b = binding ?: return
        val cash = userCashBalance
        if (cash != null) {
            b.tvCashBalance.text = String.format(Locale.US, "$%,.2f USD", cash)
        } else {
            b.tvCashBalance.text = "—"
        }

        val ticker = getAssetTicker(currentSymbol)
        b.tvHoldingsLabel.text = getString(R.string.trading_holding_label_format, ticker)
        if (portfolioLoaded) {
            b.tvHoldings.text = String.format(Locale.US, "%.4f %s", userHoldingsQuantity, ticker)
        } else {
            b.tvHoldings.text = "—"
        }
    }

    private fun updateTradeActionsState() {
        val isStock = StockTradePolicy.isStock(currentSymbol)
        val ready = TradingDataReadiness.isReadyForTrading(
            token = jwtToken,
            currentPrice = currentAssetPrice,
            portfolioLoaded = portfolioLoaded,
            userCashBalance = userCashBalance
        ) && (!isStock || (!isStockDetailStale && currentAssetPrice != null && currentAssetPrice!! > 0.0))

        binding?.btnBuy?.isEnabled = ready
        binding?.btnBuy?.alpha = if (ready) 1.0f else 0.5f
        binding?.btnSell?.isEnabled = ready
        binding?.btnSell?.alpha = if (ready) 1.0f else 0.5f
    }

    private fun setupTradeActions() {
        binding?.btnBuy?.setOnClickListener {
            openOrderTicket("BUY")
        }

        binding?.btnSell?.setOnClickListener {
            openOrderTicket("SELL")
        }

        updateTradeActionsState()
    }

    private fun openOrderTicket(orderType: String) {
        if (!TradingDataReadiness.canOpenOrderTicket(jwtToken, currentAssetPrice, portfolioLoaded, userCashBalance)) {
            context?.let { ctx ->
                Toast.makeText(ctx, getString(R.string.trading_err_data_not_ready), Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (parentFragmentManager.findFragmentByTag(OrderTicketBottomSheet.TAG) != null) {
            return
        }

        val price = currentAssetPrice ?: return
        val cash = userCashBalance ?: return

        val bottomSheet = OrderTicketBottomSheet.newInstance(
            orderType = orderType,
            symbol = currentSymbol,
            currentPrice = price,
            availableCash = cash,
            ownedQuantity = userHoldingsQuantity
        )

        bottomSheet.show(parentFragmentManager, OrderTicketBottomSheet.TAG)
    }

    private fun loadPortfolio() {
        val authHeader = AuthHeaderFactory.createBearerHeader(jwtToken) ?: return

        if (activePortfolioCall != null) {
            return
        }

        val call = RetrofitClient.apiService.getPortfolio(authHeader)
        activePortfolioCall = call

        call.enqueue(object : Callback<PortfolioSummaryDto> {
            override fun onResponse(call: Call<PortfolioSummaryDto>, response: Response<PortfolioSummaryDto>) {
                try {
                    if (call.isCanceled || !isAdded || _binding == null) return
                    if (response.code() == 401 || response.code() == 403) {
                        AuthSessionManager.handleUnauthorized(activity)
                        return
                    }
                    val p = response.body()
                    if (response.isSuccessful && p != null) {
                        userCashBalance = p.cashBalanceUsd
                        portfolioLoaded = true
                        lastPortfolioFetchTime = System.currentTimeMillis()
                        portfolioHoldingsList = p.holdings ?: emptyList()
                        updateHoldingsForCurrentSymbol()
                        updatePortfolioDisplay()
                        updateTradeActionsState()
                    }
                } finally {
                    releasePortfolioCall(call)
                }
            }

            override fun onFailure(call: Call<PortfolioSummaryDto>, t: Throwable) {
                try {
                    if (call.isCanceled) return
                    Log.e(TAG, "Không thể tải portfolio: ${t.message}")
                } finally {
                    releasePortfolioCall(call)
                }
            }
        })
    }

    private fun loadPortfolioSilently() {
        val authHeader = AuthHeaderFactory.createBearerHeader(jwtToken) ?: return

        if (activePortfolioCall != null) {
            return
        }

        val call = RetrofitClient.apiService.getPortfolio(authHeader)
        activePortfolioCall = call

        call.enqueue(object : Callback<PortfolioSummaryDto> {
            override fun onResponse(call: Call<PortfolioSummaryDto>, response: Response<PortfolioSummaryDto>) {
                try {
                    if (call.isCanceled || !isAdded || _binding == null) return
                    if (response.code() == 401 || response.code() == 403) {
                        AuthSessionManager.handleUnauthorized(activity)
                        return
                    }
                    val p = response.body()
                    if (response.isSuccessful && p != null) {
                        if (p.cashBalanceUsd != null) {
                            userCashBalance = p.cashBalanceUsd
                        }
                        portfolioLoaded = true
                        lastPortfolioFetchTime = System.currentTimeMillis()
                        portfolioHoldingsList = p.holdings ?: emptyList()
                        updateHoldingsForCurrentSymbol()
                        updatePortfolioDisplay()
                        updateTradeActionsState()
                    }
                } finally {
                    releasePortfolioCall(call)
                }
            }

            override fun onFailure(call: Call<PortfolioSummaryDto>, t: Throwable) {
                releasePortfolioCall(call)
            }
        })
    }

    private fun updateHoldingsForCurrentSymbol() {
        val holding = portfolioHoldingsList.find {
            it.symbol?.equals(currentSymbol, ignoreCase = true) == true ||
            (currentSymbol.contains("BTC") && it.symbol?.contains("BTC") == true) ||
            (currentSymbol.contains("ETH") && it.symbol?.contains("ETH") == true) ||
            (currentSymbol.contains("XAU") && it.symbol?.contains("XAU") == true)
        }
        userHoldingsQuantity = holding?.quantity ?: 0.0
        userHoldingsAvgBuyPrice = holding?.avgBuyPrice ?: 0.0
    }

    private fun setLoadingState(isLoading: Boolean) {
        val b = binding ?: return
        b.pbLoading.visibility = if (isLoading && candleEntries.isEmpty()) View.VISIBLE else View.GONE
        b.tvStateMessage.visibility = View.GONE
    }

    private fun getSavedToken(): String {
        val ctx = context ?: return ""
        return AuthSessionManager.getToken(ctx)
    }

    private fun formatSymbolDisplay(sym: String): String = OrderTicketBottomSheet.formatSymbolDisplay(sym)

    private fun getFriendlyName(sym: String): String {
        if (StockTradePolicy.isStock(sym)) {
            return when (sym) {
                "AAPL" -> "Apple Inc."
                "MSFT" -> "Microsoft Corporation"
                "NVDA" -> "NVIDIA Corporation"
                "TSLA" -> "Tesla, Inc."
                "AMZN" -> "Amazon.com, Inc."
                "META" -> "Meta Platforms, Inc."
                "GOOGL" -> "Alphabet Inc."
                "JPM" -> "JPMorgan Chase & Co."
                else -> "Cổ phiếu Hoa Kỳ"
            }
        }
        return when {
            sym.contains("BTC") -> "Bitcoin / Tether"
            sym.contains("ETH") -> "Ethereum / Tether"
            sym.contains("XAU") || sym.contains("PAXG") -> "Vàng Thế Giới (Gold Spot)"
            sym.contains("BNB") -> "BNB / Tether"
            sym.contains("SOL") -> "Solana / Tether"
            sym.contains("XRP") -> "XRP / Tether"
            sym.contains("ADA") -> "Cardano / Tether"
            sym.contains("DOGE") -> "Dogecoin / Tether"
            else -> "Tài sản tài chính"
        }
    }

    private fun getAssetTicker(sym: String): String = OrderTicketBottomSheet.getAssetTicker(sym)

    companion object {
        private const val TAG = "FNMF_TradingFragment"
        const val KEY_SAVED_SYMBOL = "SAVED_MARKET_SYMBOL"
        const val DEFAULT_MAX_CANDLES = 90
        const val VIEWPORT_VISIBLE_MAX_CANDLES = 60f
        const val VIEWPORT_VISIBLE_MIN_CANDLES = 15f
        val SUPPORTED_BINANCE_SYMBOLS = setOf(
            "BTCUSDT", "ETHUSDT", "XAUUSD", "BNBUSDT", "SOLUSDT", "XRPUSDT", "ADAUSDT", "DOGEUSDT"
        )

        fun canonicalTradingSymbol(sym: String?): String {
            val raw = sym?.trim()?.uppercase(Locale.ROOT) ?: return ""
            val clean = raw.replace("-", "").replace("_", "").replace("/", "")
            return when (clean) {
                "BTC", "BTCUSDT", "BTCUSD" -> "BTCUSDT"
                "ETH", "ETHUSDT", "ETHUSD" -> "ETHUSDT"
                "XAU", "XAUUSD", "PAXG", "PAXGUSDT", "GOLD" -> "XAUUSD"
                "BNB", "BNBUSDT", "BNBUSD" -> "BNBUSDT"
                "SOL", "SOLUSDT", "SOLUSD" -> "SOLUSDT"
                "XRP", "XRPUSDT", "XRPUSD" -> "XRPUSDT"
                "ADA", "ADAUSDT", "ADAUSD" -> "ADAUSDT"
                "DOGE", "DOGEUSDT", "DOGEUSD" -> "DOGEUSDT"
                else -> clean
            }
        }
    }
}
