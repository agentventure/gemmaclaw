package org.goldenpass.gemmaclaw

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

class MainActivity : ComponentActivity() {
    private val client = OkHttpClient()
    private lateinit var modelDownloader: ModelDownloader
    private lateinit var gemmaManager: GemmaManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modelDownloader = ModelDownloader(this, client)
        gemmaManager = GemmaManager(this)

        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                MainScreen(modelDownloader, gemmaManager)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        gemmaManager.close()
    }
}

@Composable
fun MainScreen(downloader: ModelDownloader, manager: GemmaManager) {
    var currentScreen by remember { mutableStateOf("selector") }
    val context = LocalContext.current
    val modelsDir = remember { File(context.filesDir, "models") }
    val scope = rememberCoroutineScope()

    if (currentScreen == "selector") {
        ModelSelectorScreen(
            downloader = downloader,
            modelsDir = modelsDir,
            onModelReady = { modelFile ->
                scope.launch {
                    manager.initialize(modelFile)
                    currentScreen = "chat"
                }
            }
        )
    } else {
        ChatScreen(manager)
    }
}

@Composable
fun ModelSelectorScreen(
    downloader: ModelDownloader,
    modelsDir: File,
    onModelReady: (File) -> Unit
) {
    var selectedModel by remember { mutableStateOf(GemmaModel.E2B_IT) }
    var downloadState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Keep screen on during active download
    if (downloadState is DownloadState.Progress) {
        DisposableEffect(Unit) {
            val window = (context as? android.app.Activity)?.window
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Select Gemma 4 Model", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))

        GemmaModel.entries.forEach { model ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(8.dp)
            ) {
                RadioButton(
                    selected = (selectedModel == model),
                    onClick = { selectedModel = model }
                )
                Column {
                    Text(model.modelName, fontWeight = FontWeight.Bold)
                    Text("Size: ${model.sizeBytes / 1_000_000_000.0} GB", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        when (val state = downloadState) {
            is DownloadState.Idle -> {
                Button(onClick = {
                    scope.launch {
                        val targetFile = File(modelsDir, "${selectedModel.name.lowercase()}.litertlm")
                        downloader.downloadModel(selectedModel, targetFile).collect {
                            downloadState = it
                            if (it is DownloadState.Success) {
                                onModelReady(it.file)
                            }
                        }
                    }
                }) {
                    Text("Download & Start")
                }
            }
            is DownloadState.Progress -> {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Text("Downloading: ${(state.progress * 100).toInt()}%")
            }
            is DownloadState.Error -> {
                Text("Error: ${state.message}", color = Color.Red)
                Button(onClick = { downloadState = DownloadState.Idle }) {
                    Text("Retry")
                }
            }
            is DownloadState.Success -> {
                Text("Ready!")
            }
        }
    }
}

data class ChatMessage(val text: String, val isUser: Boolean)

@Composable
fun ChatScreen(manager: GemmaManager) {
    var inputText by remember { mutableStateOf("") }
    val chatMessages = remember { mutableStateListOf<ChatMessage>() }
    var isThinking by remember { mutableStateOf(manager.isThinkingMode) }
    val scope = rememberCoroutineScope()

    var isGenerating by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Thinking Mode", modifier = Modifier.weight(1f))
            Switch(
                checked = isThinking,
                onCheckedChange = {
                    isThinking = it
                    manager.isThinkingMode = it
                },
                enabled = !isGenerating
            )
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(chatMessages) { message ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    Card(
                        modifier = Modifier.padding(8.dp).align(if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart),
                        colors = CardDefaults.cardColors(
                            containerColor = if (message.isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Text(message.text, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Gemma...") },
                enabled = !isGenerating
            )
            IconButton(
                enabled = !isGenerating && inputText.isNotBlank(),
                onClick = {
                    val userText = inputText
                    chatMessages.add(ChatMessage(userText, true))
                    inputText = ""
                    isGenerating = true
                    
                    scope.launch {
                        chatMessages.add(ChatMessage("...", false))
                        val lastIndex = chatMessages.size - 1
                        
                        try {
                            manager.generateResponse(userText).collect { fullResponse ->
                                if (fullResponse.isNotEmpty()) {
                                    chatMessages[lastIndex] = ChatMessage(fullResponse, false)
                                }
                            }
                        } finally {
                            isGenerating = false
                        }
                    }
                }
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Text("Send")
                }
            }
        }
    }
}
