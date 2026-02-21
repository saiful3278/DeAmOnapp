package com.sam.deamon_apk

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object StatusRepository {
    private val _webSocketStatus = MutableStateFlow("disconnected")
    private val _scrcpyStatus = MutableStateFlow("stopped")
    private val _deviceId = MutableStateFlow("")
    private val _lastError = MutableStateFlow("")
    private val _reconnects = MutableStateFlow(0)
    private val _videoPackets = MutableStateFlow(0)
    private val _videoBytes = MutableStateFlow(0L)
    private val _controlPackets = MutableStateFlow(0)
    private val _controlBytes = MutableStateFlow(0L)
    private val _listenerVideoStatus = MutableStateFlow("idle")
    private val _listenerControlStatus = MutableStateFlow("idle")
    private val _lastCommand = MutableStateFlow("")
    private val _scrcpyOutput = MutableStateFlow("")

    val webSocketStatus: StateFlow<String> = _webSocketStatus
    val scrcpyStatus: StateFlow<String> = _scrcpyStatus
    val deviceId: StateFlow<String> = _deviceId
    val lastError: StateFlow<String> = _lastError
    val reconnects: StateFlow<Int> = _reconnects
    val videoPackets: StateFlow<Int> = _videoPackets
    val videoBytes: StateFlow<Long> = _videoBytes
    val controlPackets: StateFlow<Int> = _controlPackets
    val controlBytes: StateFlow<Long> = _controlBytes
    val listenerVideoStatus: StateFlow<String> = _listenerVideoStatus
    val listenerControlStatus: StateFlow<String> = _listenerControlStatus
    val lastCommand: StateFlow<String> = _lastCommand
    val scrcpyOutput: StateFlow<String> = _scrcpyOutput

    fun setWebSocketStatus(status: String) {
        _webSocketStatus.value = status
    }

    fun setScrcpyStatus(status: String) {
        _scrcpyStatus.value = status
    }

    fun setDeviceId(id: String) {
        _deviceId.value = id
    }

    fun setLastError(err: String) {
        _lastError.value = err
    }

    fun incReconnect() {
        _reconnects.value = _reconnects.value + 1
    }

    fun addVideo(bytes: Int) {
        _videoPackets.value = _videoPackets.value + 1
        _videoBytes.value = _videoBytes.value + bytes
    }

    fun addControl(bytes: Int) {
        _controlPackets.value = _controlPackets.value + 1
        _controlBytes.value = _controlBytes.value + bytes
    }

    fun setListenerVideoStatus(status: String) {
        _listenerVideoStatus.value = status
    }

    fun setListenerControlStatus(status: String) {
        _listenerControlStatus.value = status
    }

    fun setLastCommand(cmd: String) {
        _lastCommand.value = cmd
    }

    fun appendOutput(text: String) {
        val current = _scrcpyOutput.value
        val next = if (current.length > 5000) current.takeLast(4000) + text else current + text
        _scrcpyOutput.value = next
    }
}

