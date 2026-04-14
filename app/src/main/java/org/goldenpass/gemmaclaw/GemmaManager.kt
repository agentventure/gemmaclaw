package org.goldenpass.gemmaclaw

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File

class GemmaManager(private val context: Context) {

    private var engine: LlmInference? = null
    var isThinkingMode: Boolean = false

    fun initialize(modelFile: File) {
        close()

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(2048)
            .build()

        engine = LlmInference.createFromOptions(context, options)
    }

    fun generateResponse(prompt: String): Flow<String> = callbackFlow {
        val finalPrompt = if (isThinkingMode) {
            "<|think|>\n$prompt"
        } else {
            prompt
        }

        val inference = engine ?: throw IllegalStateException("Gemma engine not initialized")

        inference.generateResponseAsync(finalPrompt) { result, done ->
            android.util.Log.d("GemmaManager", "Chunk: $result, Done: $done")
            trySend(result)
            if (done) {
                channel.close()
            }
        }

        awaitClose {
            // No specific cleanup needed for the listener in this API version
        }
    }

    fun close() {
        engine?.close()
        engine = null
    }
}
