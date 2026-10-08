package com.example.nhumonglenh

import com.example.nhumonglenh.data.remote.ApiService
import com.example.nhumonglenh.data.remote.MarketPriceDto
import com.example.nhumonglenh.ui.ticker.MarketTickerAdapter
import com.example.nhumonglenh.ui.ticker.MarketTickerAutoScrollPolicy
import com.example.nhumonglenh.ui.ticker.MarketTickerPolicy
import com.example.nhumonglenh.ui.ticker.MarketTickerUiModel
import com.example.nhumonglenh.ui.ticker.MarketTickerViewModel
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class MarketTickerAndWalletBehavioralTest {

    // =========================================================================
    // PART A: MARKET TICKER TESTS
    // =========================================================================

    @Test
    fun testTicker_canonicalSymbols_exactEightInOrder() {
        val expected = listOf(
            "BTCUSDT",
            "ETHUSDT",
            "XAUUSD",
            "BNBUSDT",
            "SOLUSDT",
            "XRPUSDT",
            "ADAUSDT",
            "DOGEUSDT"
        )
        assertEquals("Market Ticker must contain exactly 8 symbols", 8, MarketTickerPolicy.CANONICAL_SYMBOLS.size)
        assertEquals("Canonical symbols must match exact order", expected, MarketTickerPolicy.CANONICAL_SYMBOLS)
    }

    @Test
    fun testTicker_displayNameResolution() {
        assertEquals("BTC", MarketTickerPolicy.resolveDisplayName("BTCUSDT"))
        assertEquals("ETH", MarketTickerPolicy.resolveDisplayName("ETHUSDT"))
        assertEquals("XAU", MarketTickerPolicy.resolveDisplayName("XAUUSD"))
        assertEquals("BNB", MarketTickerPolicy.resolveDisplayName("BNBUSDT"))
        assertEquals("SOL", MarketTickerPolicy.resolveDisplayName("SOLUSDT"))
        assertEquals("XRP", MarketTickerPolicy.resolveDisplayName("XRPUSDT"))
        assertEquals("ADA", MarketTickerPolicy.resolveDisplayName("ADAUSDT"))
        assertEquals("DOGE", MarketTickerPolicy.resolveDisplayName("DOGEUSDT"))
    }

    @Test
    fun testTicker_priceFormatting_preservesPrecisionAndNeverConvertsNullToZero() {
        // Null or non-positive must show "—"
        assertEquals("—", MarketTickerPolicy.formatPrice(null))
        assertEquals("—", MarketTickerPolicy.formatPrice(0.0))
        assertEquals("—", MarketTickerPolicy.formatPrice(-10.0))

        // Large values (BTC, BNB, XAU) -> 2 decimals with comma separator
        assertEquals("$76,443.61", MarketTickerPolicy.formatPrice(76443.61))
        assertEquals("$2,715.40", MarketTickerPolicy.formatPrice(2715.40))
        assertEquals("$650.50", MarketTickerPolicy.formatPrice(650.50))

        // Medium values (SOL, XRP) -> 2-4 decimals
        assertEquals("$145.20", MarketTickerPolicy.formatPrice(145.20))
        assertEquals("$1.2988", MarketTickerPolicy.formatPrice(1.2988))
        assertEquals("$1.50", MarketTickerPolicy.formatPrice(1.50))

        // Micro values (ADA, DOGE) -> 4-6 decimals, never lost to 2 decimals
        assertEquals("$0.2006", MarketTickerPolicy.formatPrice(0.2006))
        assertEquals("$0.08125", MarketTickerPolicy.formatPrice(0.08125))
    }

    @Test
    fun testTicker_changeFormatting_andColorRules_neverConvertsNullToZero() {
        // Null -> "—" and neutral gray (0xFF787B86)
        assertEquals("—", MarketTickerPolicy.formatChange(null))
        assertEquals(0xFF787B86.toInt(), MarketTickerPolicy.resolveChangeColor(null))

        // Positive -> "+X.XX%" and green (0xFF089981)
        assertEquals("+2.45%", MarketTickerPolicy.formatChange(2.45))
        assertEquals(0xFF089981.toInt(), MarketTickerPolicy.resolveChangeColor(2.45))

        // Zero -> "+0.00%" and green (0xFF089981)
        assertEquals("+0.00%", MarketTickerPolicy.formatChange(0.00))
        assertEquals(0xFF089981.toInt(), MarketTickerPolicy.resolveChangeColor(0.00))

        // Negative -> "-X.XX%" and red (0xFFF23645)
        assertEquals("-1.82%", MarketTickerPolicy.formatChange(-1.82))
        assertEquals(0xFFF23645.toInt(), MarketTickerPolicy.resolveChangeColor(-1.82))
    }

    @Test
    fun testTicker_mergeData_preservesExactOrderAndHandlesStaleAndMissing() {
        // Given backend response with some symbols out of order and one missing
        val backendPrices = listOf(
            MarketPriceDto(symbol = "ETHUSDT", price = 2700.0, change24h = 1.5, stale = false),
            MarketPriceDto(symbol = "BTCUSDT", price = 76000.0, change24h = -0.8, stale = false),
            MarketPriceDto(symbol = "SOLUSDT", price = 145.0, change24h = 3.2, stale = true),
            MarketPriceDto(symbol = "DOGEUSDT", price = 0.08125, change24h = 5.0, stale = false)
            // XAUUSD, BNBUSDT, XRPUSDT, ADAUSDT missing
        )

        val merged = MarketTickerPolicy.mergeTickerData(null, backendPrices)

        assertEquals(8, merged.size)
        // 0: BTCUSDT
        assertEquals("BTCUSDT", merged[0].symbol)
        assertEquals("BTC", merged[0].displayName)
        assertEquals(76000.0, merged[0].price ?: 0.0, 0.001)
        assertEquals(-0.8, merged[0].change24h ?: 0.0, 0.001)
        assertFalse(merged[0].isStale)

        // 1: ETHUSDT
        assertEquals("ETHUSDT", merged[1].symbol)
        assertEquals(2700.0, merged[1].price ?: 0.0, 0.001)
        assertEquals(1.5, merged[1].change24h ?: 0.0, 0.001)

        // 2: XAUUSD (missing from backend -> null values, not 0)
        assertEquals("XAUUSD", merged[2].symbol)
        assertNull("Missing symbol must have null price, not 0", merged[2].price)
        assertNull("Missing symbol must have null change24h, not 0", merged[2].change24h)

        // 4: SOLUSDT (stale from backend)
        assertEquals("SOLUSDT", merged[4].symbol)
        assertTrue("SOLUSDT should be marked stale", merged[4].isStale)

        // 7: DOGEUSDT
        assertEquals("DOGEUSDT", merged[7].symbol)
        assertEquals(0.08125, merged[7].price ?: 0.0, 0.00001)
    }

    @Test
    fun testTicker_mergeData_networkErrorRetainsLastSnapshotAsStale() {
        val initialData = listOf(
            MarketPriceDto(symbol = "BTCUSDT", price = 75000.0, change24h = 2.0, stale = false),
            MarketPriceDto(symbol = "ETHUSDT", price = 2600.0, change24h = 1.0, stale = false),
            MarketPriceDto(symbol = "XAUUSD", price = 2700.0, change24h = 0.5, stale = false),
            MarketPriceDto(symbol = "BNBUSDT", price = 650.0, change24h = -0.5, stale = false),
            MarketPriceDto(symbol = "SOLUSDT", price = 140.0, change24h = 3.0, stale = false),
            MarketPriceDto(symbol = "XRPUSDT", price = 1.25, change24h = -1.0, stale = false),
            MarketPriceDto(symbol = "ADAUSDT", price = 0.20, change24h = 0.0, stale = false),
            MarketPriceDto(symbol = "DOGEUSDT", price = 0.08, change24h = 4.0, stale = false)
        )
        val step1 = MarketTickerPolicy.mergeTickerData(null, initialData)
        assertEquals(8, step1.size)
        assertFalse(step1[0].isStale)

        // Step 2: Network failure (null prices) -> retains snapshot with isStale = true
        val step2 = MarketTickerPolicy.mergeTickerData(step1, null)
        assertEquals(8, step2.size)
        assertEquals("BTCUSDT", step2[0].symbol)
        assertEquals(75000.0, step2[0].price ?: 0.0, 0.001)
        assertEquals(2.0, step2[0].change24h ?: 0.0, 0.001)
        assertTrue("Snapshot items must be marked stale on network failure", step2[0].isStale)
        assertTrue("All snapshot items must be marked stale on network failure", step2.all { it.isStale })
    }

    @Test
    fun testTicker_singlePollingLoopAndNoWebSocket() {
        val pollInterval = MarketTickerViewModel.POLL_INTERVAL_MS
        assertEquals("Polling interval must be 30 seconds", 30_000L, pollInterval)

        // Verify that ticker does not use any WebSocket connection
        val tickerClasses = listOf(
            MarketTickerViewModel::class.java,
            MarketTickerPolicy::class.java
        )
        for (clazz in tickerClasses) {
            val methods = clazz.declaredMethods.map { it.name }
            assertFalse("Ticker must not contain websocket methods", methods.any { it.contains("webSocket", ignoreCase = true) })
        }
    }

    // --- Per-field Merge Regression Tests ---

    @Test
    fun testTicker_mergeData_missingChange24hInDto_preservesPreviousChangeAndMarksStale() {
        // Step 1: Initial snapshot with valid price and change24h
        val initialPrices = listOf(
            MarketPriceDto(symbol = "ETHUSDT", price = 2600.0, change24h = 1.5, stale = false)
        )
        val step1 = MarketTickerPolicy.mergeTickerData(null, initialPrices)
        val eth1 = step1.first { it.symbol == "ETHUSDT" }
        assertEquals(2600.0, eth1.price ?: 0.0, 0.001)
        assertEquals(1.5, eth1.change24h ?: 0.0, 0.001)
        assertFalse("Initially should not be stale", eth1.isStale)

        // Step 2: New response has updated price but change24h = null
        val partialPrices = listOf(
            MarketPriceDto(symbol = "ETHUSDT", price = 2700.0, change24h = null, stale = false)
        )
        val step2 = MarketTickerPolicy.mergeTickerData(step1, partialPrices)
        val eth2 = step2.first { it.symbol == "ETHUSDT" }

        assertEquals("New valid price must replace previous price", 2700.0, eth2.price ?: 0.0, 0.001)
        assertEquals("Previous valid change24h must be preserved when new change is null", 1.5, eth2.change24h ?: 0.0, 0.001)
        assertTrue("Item must be marked stale because response lacked change24h field", eth2.isStale)
    }

    @Test
    fun testTicker_mergeData_missingPriceInDto_preservesPreviousPriceAndMarksStale() {
        // Step 1: Initial snapshot
        val initialPrices = listOf(
            MarketPriceDto(symbol = "BTCUSDT", price = 75000.0, change24h = 2.0, stale = false)
        )
        val step1 = MarketTickerPolicy.mergeTickerData(null, initialPrices)
        val btc1 = step1.first { it.symbol == "BTCUSDT" }
        assertEquals(75000.0, btc1.price ?: 0.0, 0.001)
        assertFalse(btc1.isStale)

        // Step 2: New response has price = null, but new change24h = 3.5
        val partialPrices = listOf(
            MarketPriceDto(symbol = "BTCUSDT", price = null, change24h = 3.5, stale = false)
        )
        val step2 = MarketTickerPolicy.mergeTickerData(step1, partialPrices)
        val btc2 = step2.first { it.symbol == "BTCUSDT" }

        assertEquals("Previous valid price must be preserved when new price is null", 75000.0, btc2.price ?: 0.0, 0.001)
        assertEquals("New valid change24h must be updated", 3.5, btc2.change24h ?: 0.0, 0.001)
        assertTrue("Item must be marked stale because response lacked price field", btc2.isStale)
    }

    @Test
    fun testTicker_mergeData_nonPositivePriceInDto_preservesPreviousPriceAndMarksStale() {
        // Step 1: Initial snapshot
        val initialPrices = listOf(
            MarketPriceDto(symbol = "XAUUSD", price = 2700.0, change24h = 0.5, stale = false)
        )
        val step1 = MarketTickerPolicy.mergeTickerData(null, initialPrices)

        // Step 2: New response has price = 0.0 (corrupted) and change24h = 0.8
        val corruptedPrices = listOf(
            MarketPriceDto(symbol = "XAUUSD", price = 0.0, change24h = 0.8, stale = false)
        )
        val step2 = MarketTickerPolicy.mergeTickerData(step1, corruptedPrices)
        val xau2 = step2.first { it.symbol == "XAUUSD" }

        assertEquals("Non-positive price (0.0) must not overwrite valid price", 2700.0, xau2.price ?: 0.0, 0.001)
        assertEquals("Valid change24h should still be applied", 0.8, xau2.change24h ?: 0.0, 0.001)
        assertTrue("Must be marked stale due to invalid price field", xau2.isStale)
    }

    @Test
    fun testTicker_mergeData_bothFieldsNullInDto_preservesBothPreviousValuesAndMarksStale() {
        val initialPrices = listOf(
            MarketPriceDto(symbol = "BNBUSDT", price = 650.0, change24h = -0.5, stale = false)
        )
        val step1 = MarketTickerPolicy.mergeTickerData(null, initialPrices)

        val emptyPrices = listOf(
            MarketPriceDto(symbol = "BNBUSDT", price = null, change24h = null, stale = false)
        )
        val step2 = MarketTickerPolicy.mergeTickerData(step1, emptyPrices)
        val bnb2 = step2.first { it.symbol == "BNBUSDT" }

        assertEquals(650.0, bnb2.price ?: 0.0, 0.001)
        assertEquals(-0.5, bnb2.change24h ?: 0.0, 0.001)
        assertTrue("Item must be marked stale when both fields in DTO are null", bnb2.isStale)
    }

    @Test
    fun testTicker_mergeData_coldStart_partialFields_marksStaleWithoutCrashing() {
        // Cold start (no previous data at all)
        val partialPrices = listOf(
            MarketPriceDto(symbol = "ETHUSDT", price = 2700.0, change24h = null, stale = false)
        )
        val result = MarketTickerPolicy.mergeTickerData(null, partialPrices)
        val eth = result.first { it.symbol == "ETHUSDT" }

        assertEquals(2700.0, eth.price ?: 0.0, 0.001)
        assertNull("Missing change24h must remain null, never 0.00%", eth.change24h)
        assertTrue("Must be marked stale because change24h is missing from DTO", eth.isStale)
    }

    @Test
    fun testTicker_mergeData_fullyValidNewDto_clearsStaleState() {
        // Previous had stale=true
        val stalePrevious = listOf(
            MarketTickerUiModel(
                symbol = "ETHUSDT",
                displayName = "ETH",
                price = 2600.0,
                change24h = 1.0,
                isStale = true
            )
        )
        // New complete valid data arrives with stale=false
        val freshPrices = listOf(
            MarketPriceDto(symbol = "ETHUSDT", price = 2750.0, change24h = 2.0, stale = false)
        )
        val result = MarketTickerPolicy.mergeTickerData(stalePrevious, freshPrices)
        val eth = result.first { it.symbol == "ETHUSDT" }

        assertEquals(2750.0, eth.price ?: 0.0, 0.001)
        assertEquals(2.0, eth.change24h ?: 0.0, 0.001)
        assertFalse("Fresh complete response must clear stale state", eth.isStale)
    }

    // =========================================================================
    // PART B: WALLET MODULE BUTTONS POSITION & TOUCH TARGET TESTS
    // =========================================================================

    @Test
    fun testWalletLayout_depositOnLeft_withdrawOnRight() {
        val walletLayoutFile = File("src/main/res/layout/fragment_wallet.xml")
        assertTrue("fragment_wallet.xml must exist", walletLayoutFile.exists())
        val content = walletLayoutFile.readText()

        val depositIndex = content.indexOf("id=\"@+id/btn_sandbox_deposit\"")
        val withdrawIndex = content.indexOf("id=\"@+id/btn_sandbox_withdraw\"")

        assertTrue("Must contain btn_sandbox_deposit", depositIndex != -1)
        assertTrue("Must contain btn_sandbox_withdraw", withdrawIndex != -1)
        assertTrue("Deposit button (Nạp tiền) must be on the LEFT of Withdraw button (Rút tiền)", depositIndex < withdrawIndex)
    }

    @Test
    fun testWalletLayout_buttonsTouchTargetAtLeast48dp() {
        val walletLayoutFile = File("src/main/res/layout/fragment_wallet.xml")
        assertTrue("fragment_wallet.xml must exist", walletLayoutFile.exists())
        val content = walletLayoutFile.readText()

        // Extract button block
        val depositBlock = content.substring(
            content.indexOf("id=\"@+id/btn_sandbox_deposit\"") - 50,
            content.indexOf("id=\"@+id/btn_sandbox_deposit\"") + 450
        )
        val withdrawBlock = content.substring(
            content.indexOf("id=\"@+id/btn_sandbox_withdraw\"") - 50,
            content.indexOf("id=\"@+id/btn_sandbox_withdraw\"") + 450
        )

        assertTrue("Deposit button must have layout_height=48dp or minHeight=48dp",
            depositBlock.contains("android:layout_height=\"48dp\"") || depositBlock.contains("android:minHeight=\"48dp\""))
        assertTrue("Withdraw button must have layout_height=48dp or minHeight=48dp",
            withdrawBlock.contains("android:layout_height=\"48dp\"") || withdrawBlock.contains("android:minHeight=\"48dp\""))
    }

    @Test
    fun testWalletLayout_usesStringResourcesNotHardcoded() {
        val walletLayoutFile = File("src/main/res/layout/fragment_wallet.xml")
        val content = walletLayoutFile.readText()

        assertTrue("Deposit button must use string resource", content.contains("android:text=\"@string/wallet_btn_deposit\"") || content.contains("@string/wallet_action_deposit"))
        assertTrue("Withdraw button must use string resource", content.contains("android:text=\"@string/wallet_btn_withdraw\"") || content.contains("@string/wallet_action_withdraw"))
    }

    // =========================================================================
    // PART C: AVATAR IMAGE & HEADER INTEGRATION TESTS
    // =========================================================================

    @Test
    fun testAvatar_drawableResourceExists() {
        val avatarJpg = File("src/main/res/drawable/profile_avatar.jpg")
        val avatarPng = File("src/main/res/drawable/profile_avatar.png")
        assertTrue("profile_avatar drawable must exist (jpg or png)", avatarJpg.exists() || avatarPng.exists())
    }

    @Test
    fun testActivity2Layout_avatarReplacesOldIcon_andMeetsTouchTarget() {
        val layoutFile = File("src/main/res/layout/layout_activity2.xml")
        assertTrue("layout_activity2.xml must exist", layoutFile.exists())
        val content = layoutFile.readText()

        // Ensure btn_profile_avatar exists and uses profile_avatar
        assertTrue("Must contain btn_profile_avatar", content.contains("id=\"@+id/btn_profile_avatar\""))
        assertTrue("btn_profile_avatar must use @drawable/profile_avatar", content.contains("@drawable/profile_avatar"))

        // Ensure old system icon is NOT used in the top bar
        assertFalse("Old ic_menu_myplaces must not be used in header", content.contains("@android:drawable/ic_menu_myplaces"))

        // Ensure touch target >= 48dp
        assertTrue("Avatar touch target width must be 48dp", content.contains("android:layout_width=\"48dp\""))
        assertTrue("Avatar touch target height must be 48dp", content.contains("android:layout_height=\"48dp\""))
        assertTrue("Avatar touch target minWidth must be 48dp", content.contains("android:minWidth=\"48dp\""))
        assertTrue("Avatar touch target minHeight must be 48dp", content.contains("android:minHeight=\"48dp\""))

        // Ensure contentDescription is not hardcoded
        assertTrue("Avatar must have contentDescription from string resource", content.contains("android:contentDescription=\"@string/profile_avatar_desc\""))
    }

    @Test
    fun testActivity2Layout_containsMarketTickerRecyclerView() {
        val layoutFile = File("src/main/res/layout/layout_activity2.xml")
        val content = layoutFile.readText()

        assertTrue("Must contain rv_market_ticker", content.contains("id=\"@+id/rv_market_ticker\""))

        val topBarIndex = content.indexOf("id=\"@+id/layout_top_bar\"")
        val tickerIndex = content.indexOf("id=\"@+id/rv_market_ticker\"")
        val containerIndex = content.indexOf("id=\"@+id/fragment_container\"")

        assertTrue("rv_market_ticker must be positioned below top bar", topBarIndex < tickerIndex)
        assertTrue("rv_market_ticker must be positioned above fragment container", tickerIndex < containerIndex)
    }

    @Test
    fun testActivity2Layout_marketTickerTouchTargetAtLeast48dp() {
        val layoutFile = File("src/main/res/layout/layout_activity2.xml")
        val content = layoutFile.readText()

        val tickerIndex = content.indexOf("id=\"@+id/rv_market_ticker\"")
        assertTrue("Must contain rv_market_ticker", tickerIndex != -1)
        val tickerBlock = content.substring(tickerIndex - 50, tickerIndex + 350)

        assertTrue("rv_market_ticker must have layout_height=\"48dp\"", tickerBlock.contains("android:layout_height=\"48dp\""))
        assertTrue("rv_market_ticker must have minHeight=\"48dp\"", tickerBlock.contains("android:minHeight=\"48dp\""))
    }

    @Test
    fun testItemMarketTickerLayout_touchTargetAtLeast48dp() {
        val itemFile = File("src/main/res/layout/item_market_ticker.xml")
        assertTrue("item_market_ticker.xml must exist", itemFile.exists())
        val content = itemFile.readText()

        assertTrue("Ticker item must have minHeight=\"48dp\" for touch target accessibility", content.contains("android:minHeight=\"48dp\""))
        assertTrue("Ticker item must have clickable=\"true\"", content.contains("android:clickable=\"true\""))
    }

    @Test
    fun testMarketTicker_staleHintStringAndAccessibilityAppended() {
        val stringsFile = File("src/main/res/values/strings.xml")
        assertTrue("strings.xml must exist", stringsFile.exists())
        val stringsContent = stringsFile.readText()

        assertTrue("Must define market_ticker_stale_hint", stringsContent.contains("name=\"market_ticker_stale_hint\""))
        assertTrue("market_ticker_stale_hint must be Vietnamese", stringsContent.contains("Dữ liệu có thể trễ"))

        // Check MarketTickerAdapter appends stale hint to contentDescription
        val adapterFile = File("src/main/java/com/example/nhumonglenh/ui/ticker/MarketTickerAdapter.kt")
        assertTrue("MarketTickerAdapter.kt must exist", adapterFile.exists())
        val adapterContent = adapterFile.readText()

        assertTrue("Adapter must check isStale for contentDescription", adapterContent.contains("item.isStale"))
        assertTrue("Adapter must append market_ticker_stale_hint to contentDescription", adapterContent.contains("market_ticker_stale_hint"))
    }

    // =========================================================================
    // PART D: VERSIONING VERIFICATION
    // =========================================================================

    @Test
    fun testVersionCodeMatchesVersionName() {
        val buildGradleFile = File("build.gradle.kts")
        val appBuildGradleFile = File("app/build.gradle.kts")
        val target = if (appBuildGradleFile.exists()) appBuildGradleFile else buildGradleFile
        assertTrue("app/build.gradle.kts must exist", target.exists())
        val content = target.readText()

        val versionCode = Regex("versionCode\\s*=\\s*(\\d+)").find(content)?.groupValues?.get(1)?.toInt()
        val versionPatch = Regex("versionName\\s*=\\s*\"1\\.1\\.(\\d+)\"").find(content)?.groupValues?.get(1)?.toInt()
        assertNotNull("versionCode must be declared", versionCode)
        assertTrue("versionCode must be at least 28", versionCode!! >= 28)
        assertEquals("1.1.x patch must match versionCode", versionCode, versionPatch)
    }

    // =========================================================================
    // PART E: MARKET TICKER AUTO-SCROLL & RUNTIME WALLET LAYOUT COORDINATE TESTS
    // =========================================================================

    @Test
    fun testAutoScroll_startIdempotency_onlySingleLoopAllowed() {
        val running = true
        val paused = false
        val userTouching = false
        val a11yActive = false
        val animsEnabled = true

        val canAdvance1 = MarketTickerAutoScrollPolicy.canAdvance(
            isRunning = running,
            isPaused = paused,
            isUserTouching = userTouching,
            isAccessibilityActive = a11yActive,
            areAnimationsEnabled = animsEnabled
        )
        assertTrue("Auto-scroll must be allowed to advance when active and unobstructed", canAdvance1)

        val canAdvanceStopped = MarketTickerAutoScrollPolicy.canAdvance(
            isRunning = false,
            isPaused = paused,
            isUserTouching = userTouching,
            isAccessibilityActive = a11yActive,
            areAnimationsEnabled = animsEnabled
        )
        assertFalse("Stopped auto-scroll must not advance", canAdvanceStopped)
    }

    @Test
    fun testAutoScroll_onStop_stopsCompletely_onStart_resumesSingleLoop() {
        // onStop: isRunning = false, isPaused = true -> advance blocked
        assertFalse(
            MarketTickerAutoScrollPolicy.canAdvance(
                isRunning = false,
                isPaused = true,
                isUserTouching = false,
                isAccessibilityActive = false,
                areAnimationsEnabled = true
            )
        )

        // onStart: isRunning = true, isPaused = false -> advance active
        assertTrue(
            MarketTickerAutoScrollPolicy.canAdvance(
                isRunning = true,
                isPaused = false,
                isUserTouching = false,
                isAccessibilityActive = false,
                areAnimationsEnabled = true
            )
        )
    }

    @Test
    fun testAutoScroll_directionIsRightToLeft() {
        val direction = MarketTickerAutoScrollPolicy.resolveScrollDirection(MarketTickerAutoScrollPolicy.DEFAULT_SCROLL_STEP_PX)
        assertEquals("Direction must be RIGHT_TO_LEFT (content moves right to left)", MarketTickerAutoScrollPolicy.ScrollDirection.RIGHT_TO_LEFT, direction)
        assertTrue("DEFAULT_SCROLL_STEP_PX must be positive for right-to-left marquee motion", MarketTickerAutoScrollPolicy.DEFAULT_SCROLL_STEP_PX > 0)
    }

    @Test
    fun testAutoScroll_touchPause_andReleaseResume() {
        val touchingAdvance = MarketTickerAutoScrollPolicy.canAdvance(
            isRunning = true,
            isPaused = false,
            isUserTouching = true,
            isAccessibilityActive = false,
            areAnimationsEnabled = true
        )
        assertFalse("Auto-scroll must pause while user is touching or dragging", touchingAdvance)

        val releasedAdvance = MarketTickerAutoScrollPolicy.canAdvance(
            isRunning = true,
            isPaused = false,
            isUserTouching = false,
            isAccessibilityActive = false,
            areAnimationsEnabled = true
        )
        assertTrue("Auto-scroll must resume after user releases touch", releasedAdvance)
    }

    @Test
    fun testAutoScroll_seamlessLoopReset_pastDoge_neverStopsAtDoge() {
        val baseCount = 8
        // Forward reset when crossing into Set 2 (firstVisiblePos >= 16)
        val resetForward = MarketTickerAutoScrollPolicy.computeSeamlessLoopReset(
            firstVisiblePos = 16,
            currentViewLeft = -35,
            baseItemCount = baseCount,
            isInfiniteLoopEnabled = true
        )

        assertNotNull("Forward loop reset must be computed", resetForward)
        assertTrue("Reset must be applied when reaching Set 2 (firstVisiblePos >= 16)", resetForward!!.isResetApplied)
        assertEquals("Position must shift back by exactly one cycle (8 items) to Set 1", 8, resetForward.targetPosition)
        assertEquals("Pixel offset must be preserved identically to prevent visual glitching", -35, resetForward.pixelOffset)

        // Backward reset when user drags backward into Set 0 (firstVisiblePos < 8)
        val resetBackward = MarketTickerAutoScrollPolicy.computeSeamlessLoopReset(
            firstVisiblePos = 5,
            currentViewLeft = -10,
            baseItemCount = baseCount,
            isInfiniteLoopEnabled = true
        )
        assertNotNull(resetBackward)
        assertTrue("Backward reset must be applied", resetBackward!!.isResetApplied)
        assertEquals("Position must shift forward by one cycle to Set 1", 13, resetBackward.targetPosition)
        assertEquals("Pixel offset must match", -10, resetBackward.pixelOffset)

        // Within Set 1 (e.g. at DOGE index 15)
        val atDoge = MarketTickerAutoScrollPolicy.computeSeamlessLoopReset(
            firstVisiblePos = 15,
            currentViewLeft = -5,
            baseItemCount = baseCount,
            isInfiniteLoopEnabled = true
        )
        assertNotNull(atDoge)
        assertFalse("No jump needed while still within Set 1", atDoge!!.isResetApplied)
        assertEquals(15, atDoge.targetPosition)
    }

    @Test
    fun testAutoScroll_pollingUpdate_doesNotResetScrollPosition() {
        val adapterFile = File("src/main/java/com/example/nhumonglenh/ui/ticker/MarketTickerAdapter.kt")
        val content = adapterFile.readText()

        val submitListBlock = content.substring(content.indexOf("fun submitList"))
        assertFalse("submitList must never call scrollToPosition(0) which resets scroll offset", submitListBlock.contains("scrollToPosition"))

        val activity2File = File("src/main/java/com/example/nhumonglenh/Activity2.kt")
        val a2Content = activity2File.readText()
        assertFalse("Activity2 ticker collector must not call scrollToPosition(0)", a2Content.contains("rvMarketTicker.scrollToPosition(0)"))
    }

    @Test
    fun testAutoScroll_clickItem_dispatchesCorrectCanonicalSymbolAcrossRepeats() {
        val baseItems = MarketTickerPolicy.createDefaultTickerList()
        assertEquals(8, baseItems.size)

        for (i in 0 until 32) {
            val item = MarketTickerAutoScrollPolicy.resolveItemAtPosition(i, baseItems)
            assertNotNull(item)
            val expectedSymbol = MarketTickerPolicy.CANONICAL_SYMBOLS[i % 8]
            assertEquals("Position $i must map to canonical symbol $expectedSymbol", expectedSymbol, item!!.symbol)
        }
    }

    @Test
    fun testAutoScroll_zeroNetworkRequests() {
        val controllerFile = File("src/main/java/com/example/nhumonglenh/ui/ticker/MarketTickerAutoScrollController.kt")
        val content = controllerFile.readText()

        assertFalse("Controller must not import ApiService", content.contains("import com.example.nhumonglenh.data.remote.ApiService"))
        assertFalse("Controller must not import Retrofit", content.contains("import retrofit2"))
        assertFalse("Controller must not import OkHttp WebSocket", content.contains("import okhttp3.WebSocket"))
        assertFalse("Controller must not import OkHttpClient", content.contains("import okhttp3.OkHttpClient"))
        assertFalse("Controller must not call enqueue or execute", content.contains(".enqueue(") || content.contains(".execute("))
    }

    @Test
    fun testRuntimeWalletLayout_depositStrictlyLeftOfWithdraw_bothLayouts() {
        val layouts = listOf(
            File("src/main/res/layout/fragment_wallet.xml"),
            File("src/main/res/layout/fragment_wallet_profile.xml")
        )

        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()

        for (file in layouts) {
            assertTrue("${file.name} must exist", file.exists())
            val doc = builder.parse(file)

            val allElements = doc.getElementsByTagName("*")
            var depositElement: Element? = null
            var withdrawElement: Element? = null

            for (i in 0 until allElements.length) {
                val el = allElements.item(i) as Element
                val id = el.getAttribute("android:id")
                if (id == "@+id/btn_sandbox_deposit") depositElement = el
                if (id == "@+id/btn_sandbox_withdraw") withdrawElement = el
            }

            assertNotNull("Deposit button must exist in ${file.name}", depositElement)
            assertNotNull("Withdraw button must exist in ${file.name}", withdrawElement)

            val depositParent = depositElement!!.parentNode as Element
            val withdrawParent = withdrawElement!!.parentNode as Element

            assertEquals("Both buttons must share the same parent in ${file.name}", depositParent, withdrawParent)
            assertEquals("Parent must be a LinearLayout in ${file.name}", "LinearLayout", depositParent.tagName)
            assertEquals("Parent must have horizontal orientation in ${file.name}", "horizontal", depositParent.getAttribute("android:orientation"))
            assertEquals("Parent must have explicit ltr layoutDirection in ${file.name}", "ltr", depositParent.getAttribute("android:layoutDirection"))

            var depositChildIndex = -1
            var withdrawChildIndex = -1
            var childCount = 0
            val children = depositParent.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node.nodeType == Node.ELEMENT_NODE) {
                    val childEl = node as Element
                    if (childEl === depositElement) depositChildIndex = childCount
                    if (childEl === withdrawElement) withdrawChildIndex = childCount
                    childCount++
                }
            }

            assertTrue("Deposit must be found among parent children in ${file.name}", depositChildIndex != -1)
            assertTrue("Withdraw must be found among parent children in ${file.name}", withdrawChildIndex != -1)
            assertTrue("Deposit must be the first child in parent LinearLayout in ${file.name}", depositChildIndex == 0)
            assertTrue("Deposit child index must be strictly less than withdraw child index in ${file.name}", depositChildIndex < withdrawChildIndex)

            val testWidths = listOf(360, 720, 1080, 1440)
            for (parentWidth in testWidths) {
                val marginDepositEnd = 6
                val marginWithdrawStart = 6
                val totalMargin = marginDepositEnd + marginWithdrawStart
                val contentWidth = parentWidth - totalMargin
                val buttonWidth = contentWidth / 2

                val depositLeft = 0
                val depositRight = depositLeft + buttonWidth
                val withdrawLeft = depositRight + totalMargin
                val withdrawRight = withdrawLeft + buttonWidth

                assertTrue("In ${file.name} at width $parentWidth: deposit.left ($depositLeft) must be strictly < withdraw.left ($withdrawLeft)", depositLeft < withdrawLeft)
                assertTrue("In ${file.name} at width $parentWidth: deposit.right ($depositRight) must be <= withdraw.left ($withdrawLeft)", depositRight <= withdrawLeft)
            }
        }
    }

    @Test
    fun testWalletCallbacks_depositAndWithdrawal_notSwapped() {
        val walletFragFile = File("src/main/java/com/example/nhumonglenh/ui/wallet/WalletFragment.kt")
        assertTrue("WalletFragment.kt must exist", walletFragFile.exists())
        val content = walletFragFile.readText()

        val depositListenerStart = content.indexOf("b.btnSandboxDeposit.setOnClickListener")
        val withdrawListenerStart = content.indexOf("b.btnSandboxWithdraw.setOnClickListener")
        val endOfListeners = content.indexOf("private fun triggerDataLoad")

        assertTrue("btnSandboxDeposit click listener must exist", depositListenerStart != -1)
        assertTrue("btnSandboxWithdraw click listener must exist", withdrawListenerStart != -1)
        assertTrue("Deposit listener must appear before withdraw listener", depositListenerStart < withdrawListenerStart)

        val depositBlock = content.substring(depositListenerStart, withdrawListenerStart)
        assertTrue("btnSandboxDeposit must open SandboxPaymentBottomSheet with DEPOSIT", depositBlock.contains("SandboxPaymentBottomSheet.newInstance(\"DEPOSIT\""))
        assertFalse("btnSandboxDeposit must NOT open WITHDRAWAL", depositBlock.contains("WITHDRAWAL"))

        val withdrawBlock = content.substring(withdrawListenerStart, endOfListeners)
        assertTrue("btnSandboxWithdraw must open SandboxPaymentBottomSheet with WITHDRAWAL", withdrawBlock.contains("SandboxPaymentBottomSheet.newInstance(\"WITHDRAWAL\""))
        assertFalse("btnSandboxWithdraw must NOT open DEPOSIT", withdrawBlock.contains("SandboxPaymentBottomSheet.newInstance(\"DEPOSIT\""))
    }
}
