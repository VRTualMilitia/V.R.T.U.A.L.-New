package com.vrtualmilitia.vrtual

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChatForegroundService : Service(), TextToSpeech.OnInitListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var triedDefaultTts = false
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var twitchSocket: WebSocket? = null
    private var lastYoutubeMessage = ""
    private val headsUpCounter = AtomicInteger(0)
    private val twitchNameRegex = Regex("display-name=([^;\\s]*)")

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        initializeTts()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Chat relay is running"))
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
            tts = TextToSpeech(this) { fallback ->
                ttsReady = fallback == TextToSpeech.SUCCESS
                if (ttsReady) tts?.language = Locale.US
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopRelay(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
            ACTION_TEST -> speak("V R T U A L text to speech test. Audio relay is ready.")
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val twitch = intent?.getStringExtra("twitch") ?: prefs.getString("twitch", "vrtualmilitia") ?: "vrtualmilitia"
        val video = intent?.getStringExtra("youtubeVideo") ?: prefs.getString("youtubeVideo", "") ?: ""
        val key = intent?.getStringExtra("youtubeKey") ?: prefs.getString("youtubeKey", "") ?: ""
        val relay = intent?.getStringExtra("tiktokRelay") ?: prefs.getString("tiktokRelay", "") ?: ""
        val username = intent?.getStringExtra("tiktokUsername") ?: prefs.getString("tiktokUsername", "vrtualmilitia") ?: "vrtualmilitia"
        prefs.edit().putString("twitch", twitch).putString("youtubeVideo", video).putString("youtubeKey", key).putString("tiktokRelay", relay).putString("tiktokUsername", username).apply()
        connectTwitch(twitch)
        if (video.isNotBlank() && key.isNotBlank()) startYoutube(video, key)
        if (relay.isNotBlank()) startTikTokRelay(relay, username)
        return START_STICKY
    }

    /** Alert toggles are read live from the same SharedPreferences file MainActivity writes to. */
    private fun alertPrefs() = getSharedPreferences(ShellResetManager.PREFS, MODE_PRIVATE)
    private fun ttsEnabled() = alertPrefs().getBoolean(KEY_TTS_ENABLED, true)
    private fun headsUpEnabled() = alertPrefs().getBoolean(KEY_HEADS_UP_ENABLED, true)

    private fun connectTwitch(channelInput: String) {
        twitchSocket?.close(1000, "reconnect")
        val channel = channelInput.trim().removePrefix("#").lowercase().ifBlank { "vrtualmilitia" }
        val request = Request.Builder().url("wss://irc-ws.chat.twitch.tv:443").build()
        twitchSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
                webSocket.send("PASS SCHMOOPIIE")
                webSocket.send("NICK justinfan${(10000..99999).random()}")
                webSocket.send("JOIN #$channel")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                // One websocket frame can carry several IRC lines; handle each chat message on its own.
                for (line in text.split("\r\n", "\n")) {
                    if (line.isBlank()) continue
                    if (line.startsWith("PING")) { webSocket.send(line.replace("PING", "PONG")); continue }
                    if (!line.contains(" PRIVMSG ")) continue
                    val message = line.substringAfter(" PRIVMSG ", "").substringAfter(" :", "").trim()
                    if (message.isBlank() || message.startsWith("/")) continue
                    val author = twitchNameRegex.find(line)?.groupValues?.get(1)
                    onChatMessage("Twitch", author, message, message)
                }
            }
        })
    }

    private fun startYoutube(videoInput: String, key: String) = scope.launch {
        val videoId = Regex("(?:v=|youtu\\.be/|live/)([A-Za-z0-9_-]{6,})").find(videoInput)?.groupValues?.get(1) ?: videoInput.trim()
        var liveChatId = ""
        while (isActive && liveChatId.isBlank()) {
            try {
                val body = getJson("https://www.googleapis.com/youtube/v3/videos?part=liveStreamingDetails&id=$videoId&key=$key")
                liveChatId = body.optJSONArray("items")?.optJSONObject(0)?.optJSONObject("liveStreamingDetails")?.optString("activeLiveChatId", "") ?: ""
            } catch (_: Exception) { }
            if (liveChatId.isBlank()) delay(15000)
        }
        var pageToken = ""
        while (isActive && liveChatId.isNotBlank()) {
            try {
                val url = "https://www.googleapis.com/youtube/v3/liveChat/messages?liveChatId=$liveChatId&part=snippet,authorDetails&maxResults=200&key=$key" + if (pageToken.isNotBlank()) "&pageToken=$pageToken" else ""
                val json = getJson(url)
                val items = json.optJSONArray("items")
                if (items != null) for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val snippet = item.optJSONObject("snippet")
                    val author = item.optJSONObject("authorDetails")?.optString("displayName", "Viewer") ?: "Viewer"
                    val msg = snippet?.optString("displayMessage", "") ?: ""
                    val id = item.optString("id")
                    if (msg.isNotBlank() && id != lastYoutubeMessage) { lastYoutubeMessage = id; onChatMessage("YouTube", author, msg, "$author says $msg") }
                }
                pageToken = json.optString("nextPageToken", pageToken)
                delay(json.optLong("pollingIntervalMillis", 5000L).coerceIn(1000L, 30000L))
            } catch (_: Exception) { delay(10000) }
        }
    }

    private fun startTikTokRelay(url: String, username: String) {
        if (url.startsWith("ws://") || url.startsWith("wss://")) {
            client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(JSONObject().put("type", "subscribe").put("username", username).toString()) }
                override fun onMessage(webSocket: WebSocket, text: String) = handleRelayMessage(text)
            })
        } else if (url.startsWith("https://") || url.startsWith("http://")) scope.launch {
            while (isActive) {
                try { val req = Request.Builder().url(url).header("X-TikTok-Username", username).build(); client.newCall(req).execute().use { if (it.isSuccessful) handleRelayMessage(it.body?.string().orEmpty()) } }
                catch (_: Exception) { }
                delay(5000)
            }
        }
    }

    private fun handleRelayMessage(text: String) {
        try {
            val json = JSONObject(text)
            val message = json.optString("message", json.optString("text", ""))
            val user = json.optString("username", "TikTok viewer")
            if (message.isNotBlank()) onChatMessage("TikTok", user, message, "$user says $message")
        } catch (_: Exception) { if (text.isNotBlank()) onChatMessage("TikTok", null, text.trim(), text) }
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response -> if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}"); return JSONObject(response.body?.string().orEmpty()) }
    }

    /**
     * Single entry point for every live chat message (Twitch, YouTube, TikTok).
     * The heads-up banner and the spoken TTS are gated independently by their own toggles.
     */
    private fun onChatMessage(platform: String, author: String?, message: String, spoken: String) {
        if (message.isBlank()) return
        showHeadsUp(platform, author, message)
        speak(spoken)
    }

    /**
     * Posts a silent, high-priority banner. Each message uses its own rotating ID (2000..2019) so Quest 3
     * shows a fresh floating banner every time instead of silently updating one notification.
     */
    private fun showHeadsUp(platform: String, author: String?, message: String) {
        if (!headsUpEnabled()) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val title = if (author.isNullOrBlank()) "$platform chat" else "$platform: $author"
        val body = message.take(300)
        val notification = NotificationCompat.Builder(this, HEADS_UP_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSound(null)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setTimeoutAfter(HEADS_UP_TIMEOUT_MS)
            .setWhen(System.currentTimeMillis())
            .build()
        val id = HEADS_UP_BASE_ID + ((headsUpCounter.getAndIncrement() and 0x7FFFFFFF) % HEADS_UP_SLOTS)
        try { manager.notify(id, notification) } catch (_: Exception) { }
    }

    private fun speak(text: String) {
        if (!ttsEnabled()) return
        if (!ttsReady || text.isBlank()) return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).setOnAudioFocusChangeListener { }.build()
            focusRequest = request
            audioManager?.requestAudioFocus(request)
            tts?.setAudioAttributes(attrs)
            tts?.speak(text.take(350), TextToSpeech.QUEUE_ADD, null, "vrtual-${System.nanoTime()}")
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            tts?.speak(text.take(350), TextToSpeech.QUEUE_ADD, null)
        }
    }

    private fun stopRelay() {
        twitchSocket?.close(1000, "stopped"); twitchSocket = null
        scope.coroutineContext.cancelChildren()
        tts?.stop()
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "VRTUAL Chat Relay", NotificationManager.IMPORTANCE_HIGH))
            // Separate silent channel for per-message banners: IMPORTANCE_HIGH shows the floating banner, no sound so it never chimes over TTS.
            val headsUpChannel = NotificationChannel(HEADS_UP_CHANNEL_ID, "VRTUAL Chat Heads-Up", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Silent floating banners for incoming live chat messages"
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
            }
            manager.createNotificationChannel(headsUpChannel)
        }
    }
    private fun notification(message: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("V.R.T.U.A.L. Chat Relay").setContentText(message)
        .setPriority(NotificationCompat.PRIORITY_HIGH).setOngoing(true)
        .addAction(0, "Stop", PendingIntent.getService(this, 2, Intent(this, ChatForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()

    override fun onDestroy() { stopRelay(); scope.cancel(); tts?.shutdown(); tts = null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val PREFS = "vrtual_settings"; const val CHANNEL_ID = "vrtual_chat"; const val NOTIFICATION_ID = 4201
        const val ACTION_STOP = "com.vrtualmilitia.vrtual.STOP"; const val ACTION_TEST = "com.vrtualmilitia.vrtual.TEST"

        // Toggle keys shared with MainActivity (all default to ON).
        const val KEY_TTS_ENABLED = "ttsEnabled"
        const val KEY_HEADS_UP_ENABLED = "headsUpEnabled"
        const val KEY_CHAT_URL_ENABLED = "chatUrlEnabled"
        const val KEY_PREVIEW_URL_ENABLED = "previewUrlEnabled"

        // Heads-up banners: silent channel + rotating IDs 2000..2019.
        const val HEADS_UP_CHANNEL_ID = "vrtual_chat_headsup"
        const val HEADS_UP_BASE_ID = 2000
        const val HEADS_UP_SLOTS = 20
        const val HEADS_UP_TIMEOUT_MS = 10_000L
    }
}
