package com.vrtualmilitia.vrtual

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
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
    private var webViewChat: WebView? = null
    private var webViewPreview: WebView? = null
    private var splitMode = true

    // Independent on/off toggles (Switch or CheckBox in the layout; both are CompoundButtons).
    private lateinit var ttsToggle: CompoundButton
    private lateinit var headsUpToggle: CompoundButton
    private lateinit var chatUrlToggle: CompoundButton
    private lateinit var previewUrlToggle: CompoundButton

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
        setupToggles()
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
        ttsToggle = findViewById(R.id.ttsToggle)
        headsUpToggle = findViewById(R.id.headsUpToggle)
        chatUrlToggle = findViewById(R.id.chatUrlToggle)
        previewUrlToggle = findViewById(R.id.previewUrlToggle)
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

    /** Restores each toggle from SharedPreferences, then saves every change immediately. */
    private fun setupToggles() {
        val prefs = getSharedPreferences(ShellResetManager.PREFS, MODE_PRIVATE)
        bindToggle(ttsToggle, ChatForegroundService.KEY_TTS_ENABLED, prefs)
        bindToggle(headsUpToggle, ChatForegroundService.KEY_HEADS_UP_ENABLED, prefs)
        bindToggle(chatUrlToggle, ChatForegroundService.KEY_CHAT_URL_ENABLED, prefs)
        bindToggle(previewUrlToggle, ChatForegroundService.KEY_PREVIEW_URL_ENABLED, prefs)
    }

    private fun bindToggle(toggle: CompoundButton, key: String, prefs: SharedPreferences) {
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = prefs.getBoolean(key, true)
        toggle.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
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

    /** Test Alerts button: honors both the TTS switch and the Heads-Up switch independently. */
    private fun testTtsAndNotification() {
        val speakOn = ttsToggle.isChecked
        val headsUpOn = headsUpToggle.isChecked
        if (!speakOn && !headsUpOn) {
            Toast.makeText(this, "Both alert outputs are OFF. Turn on TTS and/or Heads-Up notifications to test.", Toast.LENGTH_LONG).show()
            return
        }
        if (headsUpOn) postTestNotification()
        if (speakOn) {
            if (ttsReady) tts?.speak("V R T U A L text to speech and heads up notification test.", TextToSpeech.QUEUE_FLUSH, null, "vrtual-test")
            else Toast.makeText(this, "TTS is initializing. Try again in a moment.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun postTestNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            // The old test channel had a notification sound that would chime over TTS; replace it with a silent one.
            manager.deleteNotificationChannel(LEGACY_TEST_CHANNEL)
            val channel = NotificationChannel(TEST_CHANNEL, "VRTUAL Test Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
            return
        }
        val notification = NotificationCompat.Builder(this, TEST_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("V.R.T.U.A.L.")
            .setContentText("Heads-up notification test successful.").setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setSound(null).setAutoCancel(true).build()
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
        val chatOn = chatUrlToggle.isChecked
        val previewOn = previewUrlToggle.isChecked
        getSharedPreferences(ShellResetManager.PREFS, MODE_PRIVATE).edit()
            .putString("chatUrl", chatUrl).putString("previewUrl", previewUrl)
            .putBoolean(ChatForegroundService.KEY_CHAT_URL_ENABLED, chatOn)
            .putBoolean(ChatForegroundService.KEY_PREVIEW_URL_ENABLED, previewOn)
            .apply()

        // A pane is only loaded when its On/Off toggle is checked AND its URL box is not empty.
        val showChat = chatOn && chatUrl.isNotEmpty()
        val showPreview = previewOn && previewUrl.isNotEmpty()
        if (!showChat && !showPreview) {
            Toast.makeText(this, "Turn ON and fill in at least one dashboard URL (Chat or Stream Preview).", Toast.LENGTH_LONG).show()
            return
        }

        dashboard.removeAllViews()
        settingsScroll.visibility = View.GONE
        dashboard.visibility = View.VISIBLE
        enterImmersive()
        splitMode = true

        // One visible pane gets weight 1 of 1 (100% width); two visible panes get weight 1 each (50/50).
        val horizontal = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.BLACK) }
        if (showChat) {
            val chat = createWebView(chatUrl, "chat pop-out")
            webViewChat = chat
            horizontal.addView(chat, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            chat.onResume()
        }
        if (showPreview) {
            val preview = createWebView(previewUrl, "stream preview")
            webViewPreview = preview
            horizontal.addView(preview, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            preview.onResume()
        }
        dashboard.addView(horizontal, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(8, 4, 8, 4)
            setBackgroundColor(0xCC171722.toInt())
        }
        if (showChat && showPreview) {
            val toggle = Button(this).apply {
                text = "Chat Only"
                setOnClickListener {
                    splitMode = !splitMode
                    val preview = webViewPreview
                    if (splitMode) {
                        preview?.visibility = View.VISIBLE
                        preview?.onResume()
                    } else {
                        preview?.visibility = View.GONE
                        preview?.onPause()
                    }
                    text = if (splitMode) "Chat Only" else "Split View"
                }
            }
            bar.addView(toggle)
        }
        val exit = Button(this).apply { text = "Exit Dashboard"; setOnClickListener { closeDashboard() } }
        bar.addView(exit)
        val barParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = 8; marginEnd = 8 }
        dashboard.addView(bar, barParams)
    }

    private fun createWebView(url: String, label: String): WebView {
        return WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Engine-level block: audio can never auto-play without a user gesture (muted video still renders).
            settings.mediaPlaybackRequiresUserGesture = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, pageUrl: String?) {
                    view.evaluateJavascript(MEDIA_SILENCER_JS, null)
                }
            }
            webChromeClient = object : WebChromeClient() {
                // Never let a dashboard page open the Quest microphone (WebRTC / getUserMedia) -> no feedback loop.
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.deny()
                }
            }
            if (url.startsWith("https://") || url.startsWith("http://")) loadUrl(url)
            else loadData("<html><body style='background:#111;color:#ddd;font:18px sans-serif;padding:24px'>Set a valid $label URL in VRTUAL settings.</body></html>", "text/html", "UTF-8")
        }
    }

    private fun enterImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    /** Fully stops every bit of WebView media, then detaches and destroys the view. */
    private fun releaseWebView(web: WebView?) {
        if (web == null) return
        try {
            web.stopLoading()
            web.loadData("", "text/html", "utf-8")
            web.onPause()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        } catch (_: Exception) { }
    }

    private fun closeDashboard() {
        releaseWebView(webViewChat)
        releaseWebView(webViewPreview)
        webViewChat = null
        webViewPreview = null
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
        releaseWebView(webViewChat)
        releaseWebView(webViewPreview)
        webViewChat = null
        webViewPreview = null
        tts?.stop(); tts?.shutdown(); tts = null
        super.onDestroy()
    }

    companion object {
        private const val SHIZUKU_REQUEST = 4101
        private const val NOTIFICATION_REQUEST = 4102
        private const val TEST_CHANNEL = "vrtual_test_silent"
        private const val LEGACY_TEST_CHANNEL = "vrtual_test"

        /**
         * Injected into every dashboard page in onPageFinished. Keeps all <video>/<audio> muted at volume 0,
         * silences play() calls and WebAudio gain nodes, and re-applies on every DOM change.
         */
        private const val MEDIA_SILENCER_JS = """
(function () {
  try {
    if (window.__vrtualSilencer) { window.__vrtualSilencer.sweep(); return; }

    function silence(el) {
      try {
        el.defaultMuted = true;
        el.muted = true;
        el.volume = 0;
      } catch (e) {}
    }

    function sweep() {
      try {
        var nodes = document.querySelectorAll('video,audio');
        for (var i = 0; i < nodes.length; i++) { silence(nodes[i]); }
      } catch (e) {}
    }

    window.__vrtualSilencer = { sweep: sweep };

    // Any element that calls play() (including detached new Audio()) is silenced first.
    try {
      var origPlay = HTMLMediaElement.prototype.play;
      HTMLMediaElement.prototype.play = function () {
        silence(this);
        return origPlay.apply(this, arguments);
      };
    } catch (e) {}

    // WebAudio: every gain node the page creates is pinned to 0.
    try {
      var AC = window.AudioContext || window.webkitAudioContext;
      if (AC && AC.prototype && AC.prototype.createGain) {
        var origCreateGain = AC.prototype.createGain;
        AC.prototype.createGain = function () {
          var node = origCreateGain.apply(this, arguments);
          try {
            var param = node.gain;
            param.value = 0;
            Object.defineProperty(param, 'value', {
              get: function () { return 0; },
              set: function () {},
              configurable: true
            });
            ['setValueAtTime', 'linearRampToValueAtTime', 'exponentialRampToValueAtTime', 'setTargetAtTime', 'setValueCurveAtTime'].forEach(function (name) {
              param[name] = function () { return param; };
            });
          } catch (e) {}
          return node;
        };
      }
    } catch (e) {}

    // If the page tries to unmute or raise the volume, put it back.
    document.addEventListener('volumechange', function (ev) {
      var t = ev.target;
      if (t && (t.tagName === 'VIDEO' || t.tagName === 'AUDIO') && (!t.muted || t.volume !== 0)) { silence(t); }
    }, true);

    // New or changed elements get silenced (throttled so busy chat DOMs stay cheap).
    var scheduled = false;
    function schedule() {
      if (scheduled) return;
      scheduled = true;
      setTimeout(function () { scheduled = false; sweep(); }, 50);
    }
    var observer = new MutationObserver(schedule);
    observer.observe(document.documentElement || document, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['muted', 'src', 'autoplay']
    });

    sweep();
    setInterval(sweep, 1000);
  } catch (e) {}
})();
"""
    }
}
