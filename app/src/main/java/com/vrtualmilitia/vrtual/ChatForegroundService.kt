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
                if (text.startsWith("PING")) { webSocket.send(text.replace("PING", "PONG")); return }
                val message = text.substringAfter(" PRIVMSG ", "").substringAfter(" :", "").trim()
                if (message.isNotBlank() && !message.startsWith("/")) speak(message)
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
                    if (msg.isNotBlank() && id != lastYoutubeMessage) { lastYoutubeMessage = id; speak("$author says $msg") }
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
            if (message.isNotBlank()) speak("$user says $message")
        } catch (_: Exception) { if (text.isNotBlank()) speak(text) }
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response -> if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}"); return JSONObject(response.body?.string().orEmpty()) }
    }

    private fun speak(text: String) {
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
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "VRTUAL Chat Relay", NotificationManager.IMPORTANCE_HIGH))
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
    }
}
