@file:Suppress("DEPRECATION")

package com.sam.deamon_apk

import android.net.LocalServerSocket
import android.net.LocalSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.ByteString
import java.io.InputStream
import java.util.Random

class ScrcpyBridge(
    private val sendToBackend: (ByteString) -> Unit,
    private val sendText: (String) -> Unit
) {
    private var process: Process? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var serverSocket: LocalServerSocket? = null
    private var videoSocket: LocalSocket? = null
    private var controlSocket: LocalSocket? = null
    private var listenersStarted: Boolean = false

    fun start(bitrate: Int?, maxSize: Int?, maxFps: Int?) {
        // If already running, stop first to allow restart with new params
        if (process != null) {
            stop()
        }
        StatusRepository.setScrcpyStatus("starting")
        
        // Generate unique SCID (positive 31-bit integer formatted as Hex)
        // We limit to 31 bits (0x7FFFFFFF) to avoid NumberFormatException in scrcpy
        // if it uses Integer.parseInt(s, 16) which is signed.
        val randomId = Random().nextInt() and 0x7FFFFFFF
        val scidHex = String.format("%08x", randomId)
        
        // Prepare Server Socket (Listener) synchronously to avoid race condition
        startListener(scidHex)

        val cmd = buildString {
            append("cp /data/data/com.sam.deamon_apk/files/scrcpy-server-v3.3.3 /data/local/tmp/scrcpy-server-v3.3.3 && chmod 755 /data/local/tmp/scrcpy-server-v3.3.3 && CLASSPATH=/data/local/tmp/scrcpy-server-v3.3.3 setsid /system/bin/app_process64 / com.genymobile.scrcpy.Server 3.3.3")
            append(" video_bit_rate=${bitrate ?: 4000000}")
            append(" max_size=${maxSize ?: 1080}")
            append(" max_fps=${maxFps ?: 40}")
            append(" raw_stream=true send_device_meta=false send_frame_meta=false send_dummy_byte=false send_codec_meta=false scid=$scidHex audio=false clipboard_autosync=false")
        }
        
        sendText("{\"type\":\"status\",\"source\":\"apk\",\"level\":\"info\",\"msg\":\"CMD: " + cmd.replace("\"","\\\"") + "\"}")
        StatusRepository.setLastCommand(cmd)
        
        try {
            process = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            
            scope.launch {
                try {
                    val ins = process!!.inputStream
                    val buf = ByteArray(4096)
                    while (isActive) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        val s = String(buf, 0, n)
                        sendText("{\"type\":\"status\",\"source\":\"apk\",\"level\":\"info\",\"msg\":\"" + s.replace("\n","\\n").replace("\"","\\\"") + "\"}")
                        StatusRepository.appendOutput(s)
                    }
                } catch (_: Exception) {}
                StatusRepository.setScrcpyStatus("stopped")
            }
            StatusRepository.setScrcpyStatus("running")
        } catch (e: Exception) {
            sendText("{\"type\":\"status\",\"source\":\"apk\",\"level\":\"error\",\"msg\":\"Process Start Error: " + e.message + "\"}")
            stop()
        }
    }

    fun startListeners() {
        if (listenersStarted) return
        // Default scid if started manually without process (should not happen in normal flow)
        startListener("00000000") 
        listenersStarted = true
    }

    private fun startListener(scid: String) {
        serverJob?.cancel()
        
        // Close existing sockets
        try { serverSocket?.close() } catch(_:Exception){}
        try { videoSocket?.close() } catch(_:Exception){}
        try { controlSocket?.close() } catch(_:Exception){}

        try {
            // Create socket synchronously!
            val socketName = "scrcpy_$scid"
            serverSocket = LocalServerSocket(socketName)
            StatusRepository.setListenerVideoStatus("listening")
            StatusRepository.setListenerControlStatus("listening")
            sendText("{\"type\":\"status\",\"source\":\"apk\",\"level\":\"info\",\"msg\":\"Listening on $socketName\"}")
            
            serverJob = scope.launch {
                try {
                    // Accept connections
                    // 1st: Video (because audio=false)
                    val vSocket = serverSocket!!.accept()
                    videoSocket = vSocket
                    StatusRepository.setListenerVideoStatus("connected")
                    sendText("{\"type\":\"status\",\"source\":\"apk\",\"level\":\"info\",\"msg\":\"Video socket accepted\"}")
                    
                    launch { handleVideo(vSocket) }
                    
                    // 2nd: Control
                    val cSocket = serverSocket!!.accept()
                    controlSocket = cSocket
                    StatusRepository.setListenerControlStatus("connected")
                    sendText("{\"type\":\"status\",\"source\":\"apk\",\"level\":\"info\",\"msg\":\"Control socket accepted\"}")
                    
                    launch { handleControl(cSocket) }
                    
                } catch (e: Exception) {
                     // Only report error if we didn't intentionally close the socket
                     if (listenersStarted) {
                         sendText("{\"type\":\"status\",\"source\":\"apk\",\"level\":\"error\",\"msg\":\"Listener Error: " + e.message + "\"}")
                         StatusRepository.setListenerVideoStatus("error")
                         StatusRepository.setListenerControlStatus("error")
                     }
                }
            }
            listenersStarted = true
        } catch (e: Exception) {
            sendText("{\"type\":\"status\",\"source\":\"apk\",\"level\":\"error\",\"msg\":\"Socket Bind Error: " + e.message + "\"}")
        }
    }

    fun stop() {
        listenersStarted = false
        try { process?.destroy() } catch (_: Exception) {}
        process = null
        try { serverSocket?.close() } catch(_:Exception){}
        serverSocket = null
        try { videoSocket?.close() } catch(_:Exception){}
        videoSocket = null
        try { controlSocket?.close() } catch(_:Exception){}
        controlSocket = null
        serverJob?.cancel()
        serverJob = null
        StatusRepository.setScrcpyStatus("stopped")
    }

    fun sendControl(bytes: ByteArray) {
        try {
             if (controlSocket != null) {
                 val out = controlSocket!!.outputStream
                 out.write(bytes)
             }
        } catch(e: Exception) {
            sendText("{\"type\":\"status\",\"source\":\"apk\",\"level\":\"error\",\"msg\":\"Send Control Error: " + e.message + "\"}")
        }
    }

    // Replacement for deprecated ByteString.of(array, offset, byteCount)
    private fun ByteArray.toByteStringCompat(offset: Int, byteCount: Int): ByteString {
        val copy = ByteArray(byteCount)
        System.arraycopy(this, offset, copy, 0, byteCount)
        return ByteString.of(*copy)
    }

    private fun CoroutineScope.handleControl(socket: LocalSocket) {
        try {
            val input = socket.inputStream
            val headerSize = 5
            val payloadSize = 16 * 1024
            val buffer = ByteArray(headerSize + payloadSize)
            // Pre-fill Channel 1 (Control)
            buffer[0] = 1.toByte()

            while (isActive) {
                val read = input.read(buffer, headerSize, payloadSize)
                if (read <= 0) break
                
                // Write length (Big Endian)
                buffer[1] = (read shr 24).toByte()
                buffer[2] = (read shr 16).toByte()
                buffer[3] = (read shr 8).toByte()
                buffer[4] = read.toByte()

                try {
                    sendToBackend(buffer.toByteStringCompat(0, read + headerSize))
                    StatusRepository.addControl(read)
                } catch (_: Exception) {
                    // Ignore send errors, keep reading
                }
            }
        } catch (e: Exception) {
             if (listenersStarted) sendText("{\"type\":\"status\",\"source\":\"apk\",\"level\":\"error\",\"msg\":\"Control Read Error: " + e.message + "\"}")
        } finally {
             StatusRepository.setListenerControlStatus("disconnected")
        }
    }

    private fun CoroutineScope.handleVideo(socket: LocalSocket) {
        try {
            val ins = socket.inputStream
            val headerSize = 5
            val payloadSize = 64 * 1024
            val readBuf = ByteArray(headerSize + payloadSize)
            // Pre-fill Channel 0 (Video)
            readBuf[0] = 0.toByte()

            var totalRead = 0L
            while (isActive) {
                val n = ins.read(readBuf, headerSize, payloadSize)
                if (n <= 0) break
                
                if (totalRead == 0L) {
                    sendText("{\"type\":\"status\",\"source\":\"apk\",\"level\":\"info\",\"msg\":\"First video bytes received: $n\"}")
                }
                totalRead += n
                
                // Write length (Big Endian)
                readBuf[1] = (n shr 24).toByte()
                readBuf[2] = (n shr 16).toByte()
                readBuf[3] = (n shr 8).toByte()
                readBuf[4] = n.toByte()
                
                try {
                    sendToBackend(readBuf.toByteStringCompat(0, n + headerSize))
                    StatusRepository.addVideo(n)
                } catch (e: Exception) {
                    // Ignore send errors (e.g. broken pipe), keep reading to drain scrcpy
                }
            }
        } catch (e: Exception) {
            if (listenersStarted) {
                sendText("{\"type\":\"status\",\"source\":\"apk\",\"level\":\"error\",\"msg\":\"Video Read: " + e.message + "\"}")
                StatusRepository.appendOutput("\nVideo Read Error: " + e.message)
            }
        } finally {
            StatusRepository.setListenerVideoStatus("disconnected")
        }
    }
}

