package com.sam.deamon_apk

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class KeepAliveWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        try {
            // This will restart the service if it was killed
            WebSocketService.start(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.retry()
        }
        return Result.success()
    }
}
