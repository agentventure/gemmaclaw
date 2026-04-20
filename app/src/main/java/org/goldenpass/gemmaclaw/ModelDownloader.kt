package org.goldenpass.gemmaclaw

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

enum class GemmaModel(val modelName: String, val url: String, val sizeBytes: Long) {
    E2B_IT(
        "Gemma 4 E2B-IT",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        3_654_467_584L
    ),
    E4B_IT(
        "Gemma 4 E4B-IT",
        "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        3_654_467_584L
    )
}

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Progress(val progress: Float) : DownloadState()
    data class Success(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class ModelDownloader(private val context: Context, private val client: OkHttpClient) {

    fun downloadModel(model: GemmaModel, targetFile: File): Flow<DownloadState> = flow {
        if (targetFile.exists() && targetFile.length() == model.sizeBytes) {
            emit(DownloadState.Success(targetFile))
            return@flow
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GemmaClaw:DownloadWakeLock")
        val wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GemmaClaw:WifiLock")
        
        try {
            wakeLock.acquire(15 * 60 * 1000L) // 15 mins
            wifiLock.acquire()
            
            var attempt = 0
            var success = false
            
            while (attempt < 3 && !success) {
                try {
                    val request = Request.Builder().url(model.url).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            emit(DownloadState.Error("Failed to download: ${response.code}"))
                            return@flow
                        }

                        val body = response.body ?: throw Exception("Empty response body")
                        val totalBytes = body.contentLength()
                        
                        targetFile.parentFile?.mkdirs()
                        
                        FileOutputStream(targetFile).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var bytesRead: Int
                            var totalRead = 0L
                            var lastEmittedProgress = -1f
                            
                            val inputStream = body.byteStream()
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalRead += bytesRead
                                val progress = totalRead.toFloat() / totalBytes
                                if (progress - lastEmittedProgress >= 0.01f || progress >= 1f) {
                                    emit(DownloadState.Progress(progress))
                                    lastEmittedProgress = progress
                                }
                            }
                        }
                        success = true
                        emit(DownloadState.Success(targetFile))
                    }
                } catch (e: Exception) {
                    attempt++
                    if (attempt >= 3) {
                        emit(DownloadState.Error(e.message ?: "Connection aborted after 3 attempts"))
                    } else {
                        delay(2000) // Wait before retry
                    }
                }
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            if (wifiLock.isHeld) wifiLock.release()
        }
    }.flowOn(Dispatchers.IO)
}
