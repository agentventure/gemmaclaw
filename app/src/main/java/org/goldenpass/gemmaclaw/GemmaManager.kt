package org.goldenpass.gemmaclaw

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                .setMaxTokens(2048)
                .build()

            engine = LlmInference.createFromOptions(context, options)
            android.util.Log.d("GemmaManager", "Model initialized successfully")
        } catch (e: Exception) {
            android.util.Log.e("GemmaManager", "Failed to initialize model", e)
        }
    }

    fun generateResponse(prompt: String): Flow<String> = callbackFlow {
        // Gemma 4 specific dialogue format
        val finalPrompt = if (isThinkingMode) {
            "<|turn>user\n$prompt<turn|>\n<|turn>model\n<|channel>thought\n"
        } else {
            "<|turn>user\n$prompt<turn|>\n<|turn>model\n"
        }

        android.util.Log.d("GemmaManager", "Starting inference with prompt:\n$finalPrompt")

        val fullResponseAccumulator = StringBuilder()
        val inference = engine
        if (inference == null) {
            trySend("Error: Model not initialized")
            channel.close()
            return@callbackFlow
        }

        try {
            inference.generateResponseAsync(finalPrompt) { result, done ->
                // MediaPipe behavior varies: handle both cumulative and incremental results.
                val currentTextSoFar = fullResponseAccumulator.toString()
                
                if (result.startsWith(currentTextSoFar) && currentTextSoFar.isNotEmpty()) {
                    // Cumulative result: replace with the new full string
                    fullResponseAccumulator.setLength(0)
                    fullResponseAccumulator.append(result)
                } else {
                    // Incremental result: append the new chunk
                    fullResponseAccumulator.append(result)
                }

                var processingText = fullResponseAccumulator.toString()
                var shouldStop = false

                // 1. Intercept Stop Tokens (including literal hallucinations)
                val stopTokens = listOf(
                    "<turn|>", 
                    "<end_of_turn>", 
                    "<|im_end|>", 
                    "user\n", 
                    "model\n",
                    "end of turn"
                )
                
                for (token in stopTokens) {
                    val index = processingText.indexOf(token)
                    if (index != -1) {
                        processingText = processingText.substring(0, index)
                        shouldStop = true
                    }
                }

                // 2. Hide Thinking Channel
                val thoughtStart = "<|channel>thought"
                val thoughtEnd = "<channel|>"
                val thoughtEndIdx = processingText.indexOf(thoughtEnd)
                
                val displayResult = if (thoughtEndIdx != -1) {
                    // Extract text after the thought block
                    processingText.substring(thoughtEndIdx + thoughtEnd.length)
                } else if (processingText.contains(thoughtStart)) {
                    // Hide thoughts while in progress
                    "Thinking..."
                } else {
                    processingText
                }

                // 3. Final sanitization
                val cleanedResult = displayResult
                    .replace("<|turn>model", "")
                    .replace("<|turn>user", "")
                    .trim()

                if (cleanedResult.isNotEmpty() || done) {
                    trySend(cleanedResult)
                }

                if (done || shouldStop) {
                    channel.close()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("GemmaManager", "Error in inference", e)
            trySend("Error: ${e.message}")
            channel.close()
        }

        awaitClose { }
    }.flowOn(Dispatchers.IO)

    fun close() {
        engine?.close()
        engine = null
    }
}
