package org.goldenpass.gemmaclaw

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

class GemmaManager(private val context: Context) {

    private var engine: LlmInference? = null
    var isThinkingMode: Boolean = false

    suspend fun initialize(modelFile: File) = withContext(Dispatchers.IO) {
        android.util.Log.d("GemmaManager", "Initializing model: ${modelFile.absolutePath}")
        close()

        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(1024)
                .build()

            engine = LlmInference.createFromOptions(context, options)
            android.util.Log.d("GemmaManager", "Model initialized successfully")
        } catch (e: Exception) {
            android.util.Log.e("GemmaManager", "Failed to initialize model", e)
        }
    }

    fun generateResponse(prompt: String): Flow<String> = callbackFlow {
        // Standard Gemma turn templates
        val finalPrompt = if (isThinkingMode) {
            "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n<|think|>\n"
        } else {
            "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n"
        }

        android.util.Log.d("GemmaManager", "Starting inference for prompt: $finalPrompt")

        val inference = engine
        if (inference == null) {
            android.util.Log.e("GemmaManager", "Engine is null!")
            close()
            return@callbackFlow
        }

        try {
            // Ensure the initiation of inference is explicitly off the main thread
            // although callbackFlow + flowOn(Dispatchers.IO) should handle this.
            inference.generateResponseAsync(finalPrompt) { result, done ->
                trySend(result)
                if (done) {
                    channel.close()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("GemmaManager", "Error during generateResponseAsync", e)
            close()
        }

        awaitClose {
            android.util.Log.d("GemmaManager", "Flow closed")
        }
    }.flowOn(Dispatchers.IO)

    fun close() {
        engine?.close()
        engine = null
    }
}
