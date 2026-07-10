package com.errand.overlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.cdimascio.dotenv.dotenv


class MyLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    init {
        registry.currentState = Lifecycle.State.RESUMED
    }
    override val lifecycle: Lifecycle = registry
}

class MyViewModelStoreOwner : ViewModelStoreOwner {
    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore = store
}

class MySavedStateRegistryOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = controller.savedStateRegistry

    init {
        controller.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override val lifecycle: Lifecycle
        get() = registry
}

class ErrandOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: ComposeView? = null
    private var reasoningLoop: AgentReasoningLoop? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val dotenv = dotenv {
        directory = "/assets"
        filename = "env"
        ignoreIfMalformed = true
        ignoreIfMissing = true
    }

    // Overlay state
    private var currentState = mutableStateOf("Idle")
    private var currentPillText = mutableStateOf("Ready")
    private var activeRequest = mutableStateOf("")
    private var isListening = mutableStateOf(false)
    private var recognizedText = mutableStateOf("")

    // Voice
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun showOverlay() {
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowParams = params

        val customLifecycleOwner = MyLifecycleOwner()
        val customViewModelStoreOwner = MyViewModelStoreOwner()
        val customSavedStateRegistryOwner = MySavedStateRegistryOwner()

        val controller = this
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(customLifecycleOwner)
            setViewTreeViewModelStoreOwner(customViewModelStoreOwner)
            setViewTreeSavedStateRegistryOwner(customSavedStateRegistryOwner)
            setContent {
                CompositionLocalProvider(LocalOverlayController provides controller) {
                    ErrandOverlayApp()
                }
            }
        }

        windowManager.addView(composeView, params)
        overlayView = composeView

        // Init TTS
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }

        // Init SpeechRecognizer on main thread
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    fun hideOverlay() {
        stopAgent()
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
        windowParams = null
        currentState.value = "Idle"
        currentPillText.value = "Ready"
        activeRequest.value = ""
    }

    // ── Overlay mode switching (full → minimal) ─────────────────────

    private fun setOverlayMode(minimal: Boolean) {
        val params = windowParams ?: return
        val view = overlayView ?: return
        if (minimal) {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.TOP or Gravity.END
        } else {
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.TOP or Gravity.START
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e("ErrandOverlay", "Failed to update layout", e)
        }
    }

    // ── Voice input ─────────────────────────────────────────────────

    fun startListening(onResult: (String) -> Unit) {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            Toast.makeText(context, "Speech recognition not available", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "Microphone permission required. Grant in Settings.", Toast.LENGTH_LONG).show()
            return
        }

        isListening.value = true
        recognizedText.value = ""

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                isListening.value = false
            }
            override fun onError(error: Int) {
                isListening.value = false
                Log.e("ErrandOverlay", "Speech recognition error: $error")
            }
            override fun onResults(results: Bundle?) {
                isListening.value = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                recognizedText.value = text
                if (text.isNotBlank()) {
                    onResult(text)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                recognizedText.value = matches?.firstOrNull() ?: ""
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.startListening(intent)
    }

    fun stopListening() {
        isListening.value = false
        speechRecognizer?.stopListening()
    }

    fun speak(text: String) {
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "errand_tts")
        }
    }

    // ── Agent lifecycle ─────────────────────────────────────────────

    fun startAgent(request: String) {
        activeRequest.value = request
        currentState.value = "Thinking"
        currentPillText.value = "Thinking..."
        setOverlayMode(false) // full overlay during thinking

        val geminiKey = dotenv["GEMINI_API_KEY"] ?: ""

        reasoningLoop = AgentReasoningLoop(context).apply {
            setCallback(object : AgentReasoningLoop.Callback {
                override fun onStateChanged(state: String, pillText: String) {
                    mainHandler.post {
                        currentState.value = state
                        currentPillText.value = pillText
                        // Recede to minimal pill during Acting
                        if (state == "Acting") {
                            setOverlayMode(true)
                        }
                    }
                }

                override fun onError(error: String) {
                    mainHandler.post {
                        currentState.value = "Idle"
                        currentPillText.value = "Error: $error"
                        setOverlayMode(false)
                        // Auto-clear error after 4s
                        mainHandler.postDelayed({
                            if (currentState.value == "Idle" && currentPillText.value.startsWith("Error")) {
                                currentPillText.value = "Ready"
                            }
                        }, 4000)
                    }
                }

                override fun onTaskComplete() {
                    mainHandler.post {
                        currentState.value = "Idle"
                        currentPillText.value = "Task done!"
                        setOverlayMode(false)
                        mainHandler.postDelayed({
                            currentPillText.value = "Ready"
                        }, 3000)
                    }
                }

                override fun onPaymentHandoff(pillText: String) {
                    mainHandler.post {
                        currentState.value = "PaymentHandoff"
                        currentPillText.value = pillText
                        setOverlayMode(true) // minimal pill, but touchable
                        speak("Payment ready. Please complete payment manually.")
                    }
                }
            })
            startTask(request, geminiKey)
        }
    }

    fun stopAgent() {
        reasoningLoop?.stopTask()
        reasoningLoop = null
        currentState.value = "Idle"
        currentPillText.value = "Ready"
        setOverlayMode(false)
    }

    fun resumeAfterPayment() {
        currentState.value = "Thinking"
        currentPillText.value = "Resuming..."
        setOverlayMode(false)
        reasoningLoop?.resumeAfterPayment()
    }
}

// ── Composable UI ──────────────────────────────────────────────────

@Composable
fun ErrandOverlayApp() {
    val controller = LocalOverlayController.current

    val state by controller.currentState
    val pillText by controller.currentPillText
    val isListening by controller.isListening
    val recognizedText by controller.recognizedText

    val isMinimal = state == "Acting" || state == "PaymentHandoff"

    if (isMinimal) {
        MinimalPill(
            state = state,
            pillText = pillText,
            onStop = { controller.stopAgent() },
            onPaymentDone = { controller.resumeAfterPayment() }
        )
    } else {
        FullOverlay(
            state = state,
            pillText = pillText,
            isListening = isListening,
            recognizedText = recognizedText,
            onClose = { controller.hideOverlay() },
            onSubmitRequest = { controller.startAgent(it) },
            onPause = { controller.stopAgent() },
            onStartListening = { onResult -> controller.startListening(onResult) },
            onStopListening = { controller.stopListening() }
        )
    }
}

// CompositionLocal to access the controller from composables
val LocalOverlayController = compositionLocalOf<ErrandOverlayController> { error("No overlay controller") }

// ── Full overlay (Idle / Thinking / Complete) ──────────────────────

@Composable
fun FullOverlay(
    state: String,
    pillText: String,
    isListening: Boolean,
    recognizedText: String,
    onClose: () -> Unit,
    onSubmitRequest: (String) -> Unit,
    onPause: () -> Unit,
    onStartListening: (onResult: (String) -> Unit) -> Unit,
    onStopListening: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }

    // Sync recognized text into input
    LaunchedEffect(recognizedText) {
        if (recognizedText.isNotBlank()) {
            inputText = recognizedText
        }
    }

    val glowColor = when (state) {
        "Thinking" -> Color(0xFFC0C1FF).copy(alpha = 0.6f)
        "Complete" -> Color(0xFF80FFB4)
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F10).copy(alpha = if (state == "Idle") 0.85f else 0.3f))
            .border(
                width = if (glowColor != Color.Transparent) 3.dp else 0.dp,
                brush = Brush.verticalGradient(listOf(glowColor, Color.Transparent)),
                shape = RoundedCornerShape(0.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .width(340.dp)
                .background(
                    color = Color(0xFF1D2022).copy(alpha = 0.95f),
                    shape = RoundedCornerShape(24.dp)
                )
                .border(1.dp, Color(0xFFFFFFFF).copy(alpha = 0.1f), RoundedCornerShape(24.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ERRAND",
                    color = Color(0xFFC0C1FF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state != "Idle") {
                        Text(
                            text = "Stop",
                            color = Color(0xFFFFB4AB),
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { onPause() }
                        )
                    }
                    Text(
                        text = "Close",
                        color = Color(0xFFFFB4AB),
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { onClose() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when (state) {
                "Idle" -> {
                    // Text input + mic
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            label = { Text("What should Errand do?", color = Color(0xFF908FA0)) },
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color(0xFFE0E3E5),
                                unfocusedTextColor = Color(0xFFE0E3E5),
                                focusedBorderColor = Color(0xFFC0C1FF),
                                unfocusedBorderColor = Color(0xFF464554)
                            ),
                            maxLines = 3
                        )
                        // Mic button
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (isListening) Color(0xFFFF5252) else Color(0xFF3E495D))
                                .clickable {
                                    if (isListening) {
                                        onStopListening()
                                    } else {
                                        onStartListening { text -> inputText = text }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isListening) {
                                // Animated pulsing mic
                                val infiniteTransition = rememberInfiniteTransition(label = "mic")
                                val alpha by infiniteTransition.animateFloat(
                                    initialValue = 0.4f, targetValue = 1f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(600, easing = FastOutSlowInEasing),
                                        repeatMode = RepeatMode.Reverse
                                    ), label = "micAlpha"
                                )
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Stop listening",
                                    tint = Color.White.copy(alpha = alpha),
                                    modifier = Modifier.size(24.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Start listening",
                                    tint = Color(0xFFBCC7DE),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (inputText.isNotBlank()) onSubmitRequest(inputText)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8083FF)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Execute Task", color = Color(0xFF0D0096))
                    }
                }

                "Thinking" -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color(0xFFC0C1FF),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = pillText,
                            color = Color(0xFFE0E3E5),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onPause,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3E495D)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Take Over (Pause)", color = Color(0xFFBCC7DE))
                    }
                }

                else -> {
                    // Complete / other states
                    Text(
                        text = pillText,
                        color = Color(0xFFC0C1FF),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// ── Minimal pill (Acting / PaymentHandoff) ─────────────────────────

@Composable
fun MinimalPill(
    state: String,
    pillText: String,
    onStop: () -> Unit,
    onPaymentDone: () -> Unit
) {
    val borderColor = when (state) {
        "Acting" -> Color(0xFFC0C1FF).copy(alpha = 0.5f)
        "PaymentHandoff" -> Color(0xFFFFB783)
        else -> Color.Transparent
    }

    // Floating pill — top-right corner, no full-screen touch-consuming box
    // With FLAG_NOT_TOUCH_MODAL, touches on transparent areas pass through to the app below.
    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 48.dp, end = 16.dp)
                .shadow(8.dp, RoundedCornerShape(20.dp))
                .background(
                    color = Color(0xFF1D2022).copy(alpha = 0.95f),
                    shape = RoundedCornerShape(20.dp)
                )
                .border(1.dp, borderColor, RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val dotColor = when (state) {
                "Acting" -> Color(0xFFC0C1FF)
                "PaymentHandoff" -> Color(0xFFFFB783)
                else -> Color.Gray
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )

            Text(
                text = pillText,
                color = Color(0xFFE0E3E5),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )

            if (state == "PaymentHandoff") {
                Button(
                    onClick = onPaymentDone,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF80FFB4)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("I'm Done", color = Color(0xFF0D0096), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Text(
                text = "×",
                color = Color(0xFFFFB4AB),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onStop() }
            )
        }
    }
}
