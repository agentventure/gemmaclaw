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
                .setMaxTokens(4096) // Increased for better multi-turn support
                .build()

            engine = LlmInference.createFromOptions(context, options)
            android.util.Log.d("GemmaManager", "Model initialized successfully")
        } catch (e: Exception) {
            android.util.Log.e("GemmaManager", "Failed to initialize model", e)
            throw e // Propagate error to UI
        }
    }

    fun generateResponse(history: List<ChatMessage>): Flow<String> = callbackFlow {
        // Gemma 4 specific dialogue format with multi-turn support
        val promptBuilder = StringBuilder()
        
        // System instruction
        promptBuilder.append("<|turn>system\nYou are a helpful and accurate AI assistant. Answer concisely.<turn|>\n")

        // Map history to Gemma 4 turn format
        for (message in history) {
            val role = if (message.isUser) "user" else "model"
            promptBuilder.append("<|turn>$role\n${message.text}<turn|>\n")
        }

        // Trigger the next model response
        promptBuilder.append("<|turn>model\n")
        if (isThinkingMode) {
            promptBuilder.append("<|channel>thought\n")
        }

        val finalPrompt = promptBuilder.toString()
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
