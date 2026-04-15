package org.goldenpass.gemmaclaw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

enum class GemmaModel(val modelName: String, val url: String, val sizeBytes: Long) {
    E2B_IT("Gemma 4 E2B-IT", "http://10.0.2.2:8000/gemma-4-e2b.litertlm", 3_654_467_584L),
    E4B_IT("Gemma 4 E4B-IT", "http://10.0.2.2:8000/gemma-4-e4b.litertlm", 3_654_467_584L)
}

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Progress(val progress: Float) : DownloadState()
    data class Success(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class ModelDownloader(private val client: OkHttpClient) {

    fun downloadModel(model: GemmaModel, targetFile: File): Flow<DownloadState> = flow {
        if (targetFile.exists() && targetFile.length() == model.sizeBytes) {
            emit(DownloadState.Success(targetFile))
            return@flow
        }

        val request = Request.Builder().url(model.url).build()
        try {
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
                emit(DownloadState.Success(targetFile))
            }
        } catch (e: Exception) {
            emit(DownloadState.Error(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)
}
