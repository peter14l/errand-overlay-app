package com.errand.overlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
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

    // Overlay state (internal — accessed by composable via CompositionLocal)
    internal var currentState = mutableStateOf("Idle")
    internal var currentPillText = mutableStateOf("Ready")
    internal var activeRequest = mutableStateOf("")
    internal var isListening = mutableStateOf(false)
    internal var recognizedText = mutableStateOf("")

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

val LocalOverlayController = compositionLocalOf<ErrandOverlayController> { error("No overlay controller") }

@Composable
fun ErrandOverlayApp() {
    val controller = LocalOverlayController.current
    val state by controller.currentState
    val pillText by controller.currentPillText
    val isListening by controller.isListening
    val recognizedText by controller.recognizedText

    val isActive = state == "Thinking" || state == "Acting" || state == "PaymentHandoff"
    val glowColor = animateColorAsState(
        targetValue = when (state) {
            "Thinking" -> Color(0xFFB4A0FF)
            "Acting" -> Color(0xFF8083FF)
            "PaymentHandoff" -> Color(0xFFFFB783)
            "Complete" -> Color(0xFF80FFB4)
            else -> Color.Transparent
        },
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "glow"
    ).value

    Box(modifier = Modifier.fillMaxSize()) {
        // Animated edge shimmer when active
        if (isActive || state == "Complete") {
            EdgeShimmer(glowColor = glowColor)
        }

        when (state) {
            "Idle" -> IdlePanel(
                isListening = isListening,
                recognizedText = recognizedText,
                onClose = { controller.hideOverlay() },
                onSubmit = { controller.startAgent(it) },
                onStartListening = { cb -> controller.startListening(cb) },
                onStopListening = { controller.stopListening() }
            )
            "Complete" -> {
                CenterStatus(text = pillText, color = glowColor)
            }
            else -> ActivePill(
                state = state,
                pillText = pillText,
                onStop = { controller.stopAgent() },
                onPaymentDone = { controller.resumeAfterPayment() }
            )
        }
    }
}

// ── Edge shimmer ───────────────────────────────────────────────────

@Composable
fun EdgeShimmer(glowColor: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val w = size.width
                val h = size.height
                val edge = 3.dp.toPx()
                val a = 0.7f

                // Top — left to right
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, glowColor.copy(alpha = a), Color.Transparent),
                        startX = phase * w * 2 - w, endX = phase * w * 2
                    ),
                    size = Size(w, edge)
                )
                // Bottom — right to left
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, glowColor.copy(alpha = a * 0.7f), Color.Transparent),
                        startX = (1 - phase) * w * 2 - w, endX = (1 - phase) * w * 2
                    ),
                    size = Size(w, edge),
                    topLeft = Offset(0f, h - edge)
                )
                // Left — top to bottom
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.Transparent, glowColor.copy(alpha = a * 0.85f), Color.Transparent),
                        startY = phase * h * 2 - h, endY = phase * h * 2
                    ),
                    size = Size(edge, h)
                )
                // Right — bottom to top
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.Transparent, glowColor.copy(alpha = a * 0.6f), Color.Transparent),
                        startY = (1 - phase) * h * 2 - h, endY = (1 - phase) * h * 2
                    ),
                    size = Size(edge, h),
                    topLeft = Offset(w - edge, 0f)
                )
            }
    )
}

// ── Idle panel (glassmorphism card) ────────────────────────────────

@Composable
fun IdlePanel(
    isListening: Boolean,
    recognizedText: String,
    onClose: () -> Unit,
    onSubmit: (String) -> Unit,
    onStartListening: (onResult: (String) -> Unit) -> Unit,
    onStopListening: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }

    LaunchedEffect(recognizedText) {
        if (recognizedText.isNotBlank()) inputText = recognizedText
    }

    // Dim background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
    ) {
        // Centered glassmorphism card
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .width(340.dp)
                .shadow(24.dp, RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(28.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ERRAND",
                    color = Color(0xFFB4A0FF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                )
                Text(
                    text = "\u2715",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 16.sp,
                    modifier = Modifier.clickable { onClose() }
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Prompt
            Text(
                text = "What can Errand do for you?",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Light
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Glass input + mic
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Order pizza from Swiggy...", color = Color.White.copy(alpha = 0.3f)) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White.copy(alpha = 0.8f),
                        cursorColor = Color(0xFFB4A0FF),
                        focusedBorderColor = Color(0xFFB4A0FF).copy(alpha = 0.5f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                    ),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                    maxLines = 3
                )
                // Mic — glassmorphism circle
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (isListening) Color(0xFFFF5252).copy(alpha = 0.8f)
                            else Color.White.copy(alpha = 0.08f)
                        )
                        .border(
                            1.dp,
                            if (isListening) Color(0xFFFF5252).copy(alpha = 0.6f)
                            else Color.White.copy(alpha = 0.15f),
                            CircleShape
                        )
                        .clickable {
                            if (isListening) onStopListening()
                            else onStartListening { text -> inputText = text }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isListening) {
                        val infiniteTransition = rememberInfiniteTransition(label = "mic")
                        val pulse by infiniteTransition.animateFloat(
                            initialValue = 0.4f, targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                tween(600, easing = FastOutSlowInEasing),
                                RepeatMode.Reverse
                            ), label = "pulse"
                        )
                        Text("\u23FA", color = Color.White.copy(alpha = pulse), fontSize = 20.sp)
                    } else {
                        Text("\u23FA", color = Color.White.copy(alpha = 0.6f), fontSize = 20.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Execute button — gradient glass
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF8083FF), Color(0xFFB4A0FF))
                        )
                    )
                    .clickable(enabled = inputText.isNotBlank()) { onSubmit(inputText) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Execute",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

// ── Active pill (Thinking / Acting / PaymentHandoff) ───────────────

@Composable
fun ActivePill(
    state: String,
    pillText: String,
    onStop: () -> Unit,
    onPaymentDone: () -> Unit
) {
    val isActive = state == "Acting" || state == "Thinking"
    val accentColor = when (state) {
        "Acting" -> Color(0xFF8083FF)
        "Thinking" -> Color(0xFFB4A0FF)
        "PaymentHandoff" -> Color(0xFFFFB783)
        else -> Color.Gray
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Glassmorphism pill
        Row(
            modifier = Modifier
                .padding(bottom = 56.dp)
                .shadow(12.dp, RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Waveform
            WaveformIndicator(color = accentColor, isActive = isActive)

            // Status text
            Text(
                text = pillText,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.widthIn(max = 180.dp)
            )

            if (state == "PaymentHandoff") {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF80FFB4).copy(alpha = 0.9f))
                        .clickable { onPaymentDone() }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        "I'm Done",
                        color = Color(0xFF0D0096),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Stop
            Text(
                "\u2715",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 14.sp,
                modifier = Modifier.clickable { onStop() }
            )
        }
    }
}

// ── Waveform indicator ─────────────────────────────────────────────

@Composable
fun WaveformIndicator(color: Color, isActive: Boolean) {
    val barCount = 5
    val infiniteTransition = rememberInfiniteTransition(label = "wave")

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(20.dp)
    ) {
        repeat(barCount) { index ->
            val targetHeight = if (isActive) (6 + (index % 3) * 5).toFloat() else 3f
            val animHeight by infiniteTransition.animateFloat(
                initialValue = 3f,
                targetValue = targetHeight,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 280 + index * 70,
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar$index"
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(animHeight.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isActive) color else color.copy(alpha = 0.4f))
            )
        }
    }
}

// ── Center status (Complete) ───────────────────────────────────────

@Composable
fun CenterStatus(text: String, color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "complete")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ), label = "alpha"
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = color.copy(alpha = alpha),
            fontSize = 20.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 1.sp
        )
    }
}
