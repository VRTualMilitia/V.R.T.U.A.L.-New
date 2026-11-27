package com.vrtualmilitia.vrtual

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var triedDefaultTts = false
    private var serviceRunning = false
    private lateinit var settingsScroll: ScrollView
    private lateinit var dashboard: FrameLayout
    private lateinit var shellStatus: TextView
    private var chatWeb: WebView? = null
    private var previewWeb: WebView? = null
    private var splitMode = true

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST) {
            shellStatus.text = if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku permission granted." else "Shizuku permission denied."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        initializeTts()
        Shizuku.addRequestPermissionResultListener(permissionListener)
        bindViews()
        restoreSettings()
        requestRuntimePermissions()
        if (ShellResetManager.mode(this) == ShellResetManager.MODE_AUTO) {
            ShellResetManager.reset(this) { result -> shellStatus.text = "Auto-run: $result" }
        }
    }

    private fun initializeTts() {
        try { tts = TextToSpeech(this, this, "com.google.android.tts") }
        catch (_: Exception) { tts = TextToSpeech(this, this) }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.language = Locale.US
        } else if (!triedDefaultTts) {
            triedDefaultTts = true
            tts?.shutdown()
            tts = TextToSpeech(this) { result ->
                ttsReady = result == TextToSpeech.SUCCESS
                if (ttsReady) tts?.language = Locale.US
            }
        }
    }

    private fun bindViews() {
        settingsScroll = findViewById(R.id.settingsScroll)
        dashboard = findViewById(R.id.dashboardContainer)
        shellStatus = findViewById(R.id.shellStatus)
        findViewById<Button>(R.id.testTtsButton).setOnClickListener { testTtsAndNotification() }
        findViewById<Button>(R.id.startServiceButton).setOnClickListener { toggleService() }
        findViewById<Button>(R.id.grantShizukuButton).setOnClickListener {
            if (!Shizuku.pingBinder()) shellStatus.text = "Start the Shizuku app/service on the Quest first."
            else if (ShellResetManager.shizukuReady()) shellStatus.text = "Shizuku is already authorized."
            else ShellResetManager.requestShizukuPermission(SHIZUKU_REQUEST)
        }
        findViewById<Button>(R.id.resetShellButton).setOnClickListener {
            ShellResetManager.reset(this) { shellStatus.text = it }
        }
        val group = findViewById<RadioGroup>(R.id.resetModeGroup)
        group.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) { R.id.modeManual -> ShellResetManager.MODE_MANUAL; R.id.modeAuto -> ShellResetManager.MODE_AUTO; else -> ShellResetManager.MODE_OFF }
            ShellResetManager.saveMode(this, mode)
            shellStatus.text = "Shell reset mode: ${modeName(mode)}"
        }
        findViewById<Button>(R.id.openDashboardButton).setOnClickListener { openDashboard() }
    }

    private fun restoreSettings() {
        val prefs = getSharedPreferences(ShellResetManager.PREFS, MODE_PRIVATE)
        findViewById<EditText>(R.id.twitchChannel).setText(prefs.getString("twitch", "vrtualmilitia"))
        findViewById<EditText>(R.id.youtubeVideo).setText(prefs.getString("youtubeVideo", ""))
        findViewById<EditText>(R.id.youtubeKey).setText(prefs.getString("youtubeKey", ""))
        findViewById<EditText>(R.id.tiktokRelay).setText(prefs.getString("tiktokRelay", ""))
        findViewById<EditText>(R.id.tiktokUsername).setText(prefs.getString("tiktokUsername", "vrtualmilitia"))
        findViewById<EditText>(R.id.chatPopoutUrl).setText(prefs.getString("chatUrl", ""))
        findViewById<EditText>(R.id.streamPreviewUrl).setText(prefs.getString("previewUrl", ""))
        when (ShellResetManager.mode(this)) {
            ShellResetManager.MODE_MANUAL -> findViewById<RadioButton>(R.id.modeManual).isChecked = true
            ShellResetManager.MODE_AUTO -> findViewById<RadioButton>(R.id.modeAuto).isChecked = true
            else -> findViewById<RadioButton>(R.id.modeOff).isChecked = true
        }
        shellStatus.text = "Shell reset mode: ${modeName(ShellResetManager.mode(this))}"
    }

    private fun toggleService() {
        val twitch = findViewById<EditText>(R.id.twitchChannel).text.toString().ifBlank { "vrtualmilitia" }
        val video = findViewById<EditText>(R.id.youtubeVideo).text.toString().trim()
        val key = findViewById<EditText>(R.id.youtubeKey).text.toString().trim()
        val relay = findViewById<EditText>(R.id.tiktokRelay).text.toString().trim()
        val username = findViewById<EditText>(R.id.tiktokUsername).text.toString().ifBlank { "vrtualmilitia" }
        val prefs = getSharedPreferences(ShellResetManager.PREFS, MODE_PRIVATE)
        prefs.edit().putString("twitch", twitch).putString("youtubeVideo", video).putString("youtubeKey", key).putString("tiktokRelay", relay).putString("tiktokUsername", username).apply()
        if (!serviceRunning) {
            val intent = Intent(this, ChatForegroundService::class.java).apply {
                putExtra("twitch", twitch); putExtra("youtubeVideo", video); putExtra("youtubeKey", key); putExtra("tiktokRelay", relay); putExtra("tiktokUsername", username)
            }
            ContextCompat.startForegroundService(this, intent)
            serviceRunning = true
            findViewById<Button>(R.id.startServiceButton).text = "Stop Background Service"
        } else {
            startService(Intent(this, ChatForegroundService::class.java).setAction(ChatForegroundService.ACTION_STOP))
            serviceRunning = false
            findViewById<Button>(R.id.startServiceButton).text = "Start Background Service"
        }
    }

    private fun testTtsAndNotification() {
        postTestNotification()
        if (ttsReady) tts?.speak("V R T U A L text to speech and heads up notification test.", TextToSpeech.QUEUE_FLUSH, null, "vrtual-test")
        else Toast.makeText(this, "TTS is initializing. Try again in a moment.", Toast.LENGTH_SHORT).show()
    }

    private fun postTestNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(TEST_CHANNEL, "VRTUAL Test Alerts", NotificationManager.IMPORTANCE_HIGH))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
            return
        }
        val notification = NotificationCompat.Builder(this, TEST_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("V.R.T.U.A.L.")
            .setContentText("Heads-up notification test successful.").setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setAutoCancel(true).build()
        manager.notify(4202, notification)
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
    }

    private fun openDashboard() {
        val chatUrl = findViewById<EditText>(R.id.chatPopoutUrl).text.toString().trim()
        val previewUrl = findViewById<EditText>(R.id.streamPreviewUrl).text.toString().trim()
        getSharedPreferences(ShellResetManager.PREFS, MODE_PRIVATE).edit().putString("chatUrl", chatUrl).putString("previewUrl", previewUrl).apply()
        dashboard.removeAllViews()
        settingsScroll.visibility = View.GONE
        dashboard.visibility = View.VISIBLE
        enterImmersive()
        val horizontal = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.BLACK) }
        chatWeb = createWebView(chatUrl, false)
        previewWeb = createWebView(previewUrl, true)
        horizontal.addView(chatWeb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        horizontal.addView(previewWeb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        dashboard.addView(horizontal, FrameLayout.LayoutParams(-1, -1))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(8, 4, 8, 4)
            setBackgroundColor(0xCC171722.toInt())
        }
        val toggle = Button(this).apply { text = "Chat Only"; setOnClickListener { splitMode = !splitMode; previewWeb?.visibility = if (splitMode) View.VISIBLE else View.GONE; text = if (splitMode) "Chat Only" else "Split View" } }
        val exit = Button(this).apply { text = "Exit Dashboard"; setOnClickListener { closeDashboard() } }
        bar.addView(toggle); bar.addView(exit)
        val barParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = 8; marginEnd = 8 }
        dashboard.addView(bar, barParams)
        splitMode = true
    }

    private fun createWebView(url: String, muted: Boolean): WebView {
        return WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, pageUrl: String?) {
                    if (muted) view.evaluateJavascript("document.querySelectorAll('video,audio').forEach(e => { e.muted = true; e.volume = 0; });", null)
                }
            }
            webChromeClient = WebChromeClient()
            if (url.startsWith("https://") || url.startsWith("http://")) loadUrl(url)
            else loadData("<html><body style='background:#111;color:#ddd;font:18px sans-serif;padding:24px'>Set a valid ${if (muted) "stream preview" else "chat pop-out"} URL in VRTUAL settings.</body></html>", "text/html", "UTF-8")
        }
    }

    private fun enterImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    private fun closeDashboard() {
        chatWeb?.destroy(); previewWeb?.destroy(); chatWeb = null; previewWeb = null
        dashboard.removeAllViews(); dashboard.visibility = View.GONE; settingsScroll.visibility = View.VISIBLE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun modeName(mode: Int) = when (mode) { ShellResetManager.MODE_MANUAL -> "Manual Only"; ShellResetManager.MODE_AUTO -> "Auto-Run on Startup"; else -> "Off" }

    override fun onBackPressed() {
        if (dashboard.visibility == View.VISIBLE) closeDashboard() else super.onBackPressed()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        tts?.stop(); tts?.shutdown(); tts = null
        super.onDestroy()
    }

    companion object { private const val SHIZUKU_REQUEST = 4101; private const val NOTIFICATION_REQUEST = 4102; private const val TEST_CHANNEL = "vrtual_test" }
}
