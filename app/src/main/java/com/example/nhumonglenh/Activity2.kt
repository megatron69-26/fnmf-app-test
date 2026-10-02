package com.example.nhumonglenh

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.request.RequestOptions
import com.example.nhumonglenh.data.local.AuthSessionManager
import com.example.nhumonglenh.data.remote.NetworkConfig
import com.example.nhumonglenh.data.remote.RetrofitClient
import com.example.nhumonglenh.ui.common.FiniCropTransformation
import com.example.nhumonglenh.ui.news.NewsFeedFragment
import com.example.nhumonglenh.ui.portfolio.PortfolioFragment
import com.example.nhumonglenh.ui.ticker.MarketTickerAdapter
import com.example.nhumonglenh.ui.ticker.MarketTickerAutoScrollController
import com.example.nhumonglenh.ui.ticker.MarketTickerViewModel
import com.example.nhumonglenh.ui.ticker.MarketTickerViewModelFactory
import com.example.nhumonglenh.ui.wallet.WalletFragment
import com.example.nhumonglenh.ui.SystemBarInsets
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class Activity2 : AppCompatActivity() {

    companion object {
        private const val KEY_SELECTED_NAV_ID = "KEY_SELECTED_NAV_ID"
        private const val TAG_TRADING = "TAG_TRADING"
        private const val TAG_FORECAST = "TAG_FORECAST"
        private const val TAG_NEWS = "TAG_NEWS"
        private const val TAG_PORTFOLIO = "TAG_PORTFOLIO"
        private const val TAG_WALLET = "TAG_WALLET"
    }

    private val tickerViewModel: MarketTickerViewModel by viewModels {
        MarketTickerViewModelFactory(RetrofitClient.apiService)
    }
    private lateinit var tickerAdapter: MarketTickerAdapter
    private lateinit var rvMarketTicker: RecyclerView
    private var tickerAutoScrollController: MarketTickerAutoScrollController? = null

    private lateinit var bottomNav: BottomNavigationView
    private val fragmentMap = mutableMapOf<Int, Fragment>()
    private var activeFragment: Fragment? = null

    private var tradingFragment: TradingFragment? = null
    private var activeMarketSymbol: String = "BTCUSDT"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Đảm bảo RetrofitClient luôn nhận cấu hình server_url thực tế từ SharedPreferences
        val netPrefs = getSharedPreferences(NetworkConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val activeServerUrl = NetworkConfig.getOrMigrateServerUrl(netPrefs)
        RetrofitClient.updateBaseUrl(activeServerUrl)

        // Cold start auth check: Nếu chưa đăng nhập hoặc hết phiên, chuyển hướng về màn hình đăng nhập
        if (!AuthSessionManager.isLoggedIn(this)) {
            val loginIntent = Intent(this, Activity1::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(loginIntent)
            finish()
            return
        }

        supportActionBar?.hide()
        setContentView(R.layout.layout_activity2)
        SystemBarInsets.apply(this, findViewById(android.R.id.content))

        bottomNav = findViewById(R.id.bottom_navigation)

        val ivHeaderLogo = findViewById<ImageView>(R.id.iv_header_logo)
        if (ivHeaderLogo != null) {
            try {
                Glide.with(this)
                    .asGif()
                    .load(R.drawable.fini_loading_preview)
                    .apply(
                        RequestOptions()
                            .format(DecodeFormat.PREFER_ARGB_8888)
                            .transform(FiniCropTransformation(cropLeft = 56, cropTop = 58, cropWidth = 110, cropHeight = 80))
                    )
                    .into(ivHeaderLogo)
            } catch (_: Exception) {
            }
        }

        val btnProfileAvatar = findViewById<View>(R.id.btn_profile_avatar)
        btnProfileAvatar?.setOnClickListener {
            showProfileDialog()
        }

        rvMarketTicker = findViewById(R.id.rv_market_ticker)
        rvMarketTicker.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        tickerAdapter = MarketTickerAdapter { symbol ->
            switchToTradingSymbol(symbol)
        }
        rvMarketTicker.adapter = tickerAdapter
        tickerAutoScrollController = MarketTickerAutoScrollController(rvMarketTicker, tickerAdapter)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                tickerViewModel.tickerItems.collect { items ->
                    tickerAdapter.submitList(items)
                }
            }
        }

        val tvHeaderUser = findViewById<TextView>(R.id.tv_header_user)

        val prefs = getSharedPreferences(NetworkConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val savedEmail = prefs.getString(NetworkConfig.KEY_SAVED_EMAIL, "") ?: ""
        if (savedEmail.isNotBlank()) {
            tvHeaderUser.text = savedEmail
            tvHeaderUser.visibility = View.VISIBLE
        } else {
            tvHeaderUser.visibility = View.GONE
        }

        if (savedInstanceState == null) {
            val initialFragment = TradingFragment()
            tradingFragment = initialFragment
            fragmentMap[R.id.nav_trading] = initialFragment
            activeFragment = initialFragment

            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, initialFragment, TAG_TRADING)
                .commit()
        } else {
            // 1. Xây dựng lại fragmentMap từ các Fragment đã được FragmentManager khôi phục
            val navIds = listOf(R.id.nav_trading, R.id.nav_forecast, R.id.nav_news, R.id.nav_portfolio, R.id.nav_wallet)
            for (navId in navIds) {
                val tag = getTagForNavId(navId)
                val restoredFrag = supportFragmentManager.findFragmentByTag(tag)
                if (restoredFrag != null) {
                    fragmentMap[navId] = restoredFrag
                    if (navId == R.id.nav_trading && restoredFrag is TradingFragment) {
                        tradingFragment = restoredFrag
                    }
                }
            }

            // 2. Xác định tab đang chọn và Fragment đang visible
            val selectedNavId = savedInstanceState.getInt(KEY_SELECTED_NAV_ID, R.id.nav_trading)
            var currentVisible = fragmentMap.values.firstOrNull { it.isAdded && !it.isHidden }
            if (currentVisible == null) {
                currentVisible = fragmentMap[selectedNavId] ?: fragmentMap[R.id.nav_trading]
            }
            activeFragment = currentVisible

            // 3. Đồng bộ trạng thái show/hide của các Fragment theo Fragment đang active
            val tx = supportFragmentManager.beginTransaction()
            for ((_, frag) in fragmentMap) {
                if (frag === activeFragment) {
                    tx.show(frag)
                } else {
                    tx.hide(frag)
                }
            }
            tx.commit()

            bottomNav.selectedItemId = selectedNavId
        }

        bottomNav.setOnItemSelectedListener { item ->
            switchTab(item.itemId)
            true
        }

        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!AuthSessionManager.isLoggedIn(this)) {
            val loginIntent = Intent(this, Activity1::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(loginIntent)
            finish()
            return
        }
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if ("fnmf".equals(data.scheme, ignoreCase = true) && "payment".equals(data.host, ignoreCase = true)) {
            bottomNav.selectedItemId = R.id.nav_wallet
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_SELECTED_NAV_ID, bottomNav.selectedItemId)
    }

    private fun getTagForNavId(itemId: Int): String {
        return when (itemId) {
            R.id.nav_trading -> TAG_TRADING
            R.id.nav_forecast -> TAG_FORECAST
            R.id.nav_news -> TAG_NEWS
            R.id.nav_portfolio -> TAG_PORTFOLIO
            R.id.nav_wallet -> TAG_WALLET
            else -> "TAG_$itemId"
        }
    }

    private fun switchTab(itemId: Int) {
        val current = activeFragment ?: return

        val tag = getTagForNavId(itemId)
        // Tìm trong map trước, nếu chưa có thì tìm lại trong FragmentManager theo tag
        var target = fragmentMap[itemId] ?: supportFragmentManager.findFragmentByTag(tag)

        val tx = supportFragmentManager.beginTransaction()

        if (target == null) {
            // Chỉ tạo Fragment mới nếu tag đó thực sự chưa tồn tại
            target = when (itemId) {
                R.id.nav_trading -> tradingFragment ?: TradingFragment().also { tradingFragment = it }
                R.id.nav_forecast -> ForecastFragment()
                R.id.nav_news -> NewsFeedFragment()
                R.id.nav_portfolio -> PortfolioFragment()
                R.id.nav_wallet -> WalletFragment()
                else -> return
            }
            fragmentMap[itemId] = target
            tx.hide(current).add(R.id.fragment_container, target, tag).commit()
        } else {
            fragmentMap[itemId] = target
            if (target === current) return
            tx.hide(current).show(target).commit()
        }
        activeFragment = target
        if (target is ForecastFragment) {
            target.setSymbol(activeMarketSymbol)
        }
    }

    fun getActiveMarketSymbol(): String = activeMarketSymbol

    fun updateActiveSymbol(symbol: String) {
        val clean = symbol.trim().uppercase(java.util.Locale.ROOT)
        if (clean.isNotBlank()) {
            activeMarketSymbol = clean
        }
    }

    /**
     * Cho phép WatchlistFragment/TradingFragment gọi để chuyển về tab Trading và chọn mã tương ứng
     */
    fun switchToTradingSymbol(symbol: String) {
        val clean = symbol.trim().uppercase(java.util.Locale.ROOT)
        activeMarketSymbol = clean
        bottomNav.selectedItemId = R.id.nav_trading
        tradingFragment?.switchMarketSymbol(clean)
    }

    /**
     * Hiển thị hộp thoại Hồ sơ tài khoản khi bấm avatar góc phải trên
     */
    fun showProfileDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_profile, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvEmail = dialogView.findViewById<TextView>(R.id.tv_profile_dialog_email)
        val tvVersion = dialogView.findViewById<TextView>(R.id.tv_profile_dialog_version)
        val btnClose = dialogView.findViewById<Button>(R.id.btn_close_profile)
        val btnLogout = dialogView.findViewById<Button>(R.id.btn_dialog_logout)

        val savedEmail = AuthSessionManager.getUserEmail(this)
        tvEmail.text = if (savedEmail.isNotBlank()) savedEmail else "—"
        tvVersion.text = BuildConfig.VERSION_NAME

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        btnLogout.setOnClickListener {
            dialog.dismiss()
            showLogoutConfirmationDialog()
        }

        dialog.show()
    }

    /**
     * Hiển thị hộp thoại xác nhận Đăng xuất với bảng màu TradingView
     */
    fun showLogoutConfirmationDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_logout_confirmation, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnCancel = dialogView.findViewById<Button>(R.id.btn_cancel_logout)
        val btnConfirm = dialogView.findViewById<Button>(R.id.btn_confirm_logout)

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            dialog.dismiss()
            performLogout()
        }

        dialog.show()
    }

    /**
     * Thực hiện đăng xuất:
     * - Xóa jwt_token khỏi fnmf_prefs
     * - Giữ nguyên server_url và saved_username
     * - Quay về Activity1
     * - Finish Activity2 để không back trở lại khi chưa đăng nhập
     */
    private fun performLogout() {
        com.example.nhumonglenh.data.local.AuthSessionManager.clearSession(this)

        Toast.makeText(this, "Đã đăng xuất tài khoản!", Toast.LENGTH_SHORT).show()

        val intent = Intent(this, Activity1::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    override fun onStart() {
        super.onStart()
        tickerViewModel.startPolling()
        tickerAutoScrollController?.start()
    }

    override fun onStop() {
        super.onStop()
        tickerViewModel.stopPolling()
        tickerAutoScrollController?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        val ivHeaderLogo = findViewById<ImageView>(R.id.iv_header_logo)
        if (ivHeaderLogo != null) {
            try {
                Glide.with(this).clear(ivHeaderLogo)
            } catch (_: Exception) {
            }
        }
        tickerAutoScrollController?.destroy()
        tickerAutoScrollController = null
    }
}
