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
import androidx.compose.foundation.text.BasicTextField
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

        val geminiKey = try {
            dotenv["GEMINI_API_KEY"]
        } catch (_: Exception) {
            ""
        }
        if (geminiKey.isBlank()) {
            currentState.value = "Error"
            currentPillText.value = "No API key. Add GEMINI_API_KEY to assets/env."
            return
        }

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
                        currentState.value = "Error"
                        currentPillText.value = error
                        // Keep full overlay so user can read error + press × to dismiss
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

    val glowColor by animateColorAsState(
        targetValue = when (state) {
            "Thinking" -> Color(0xFFB4A0FF)
            "Acting" -> Color(0xFF8083FF)
            "PaymentHandoff" -> Color(0xFFFFB783)
            "Complete" -> Color(0xFF80FFB4)
            else -> Color.Transparent
        },
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "glow"
    )

    val showGlow = state != "Idle"
    val isError = state == "Error"

    Box(modifier = Modifier.fillMaxSize()) {
        // Soft edge glow — the only visual during active states
        if (showGlow) EdgeGlow(glowColor = glowColor)

        when {
            isError -> ErrorOverlay(
                errorText = pillText,
                onDismiss = { controller.stopAgent() }
            )
            state == "Idle" -> IdleBar(
                isListening = isListening,
                recognizedText = recognizedText,
                onClose = { controller.hideOverlay() },
                onSubmit = { controller.startAgent(it) },
                onStartListening = { cb -> controller.startListening(cb) },
                onStopListening = { controller.stopListening() }
            )
            else -> FloatingCapsule(
                state = state,
                pillText = pillText,
                onStop = { controller.stopAgent() },
                onPaymentDone = { controller.resumeAfterPayment() }
            )
        }
    }
}

// ── Soft edge glow ─────────────────────────────────────────────────
// Thick, breathing gradient along each edge. The glow "is" the overlay —
// no panels, no cards, just light emanating from the screen perimeter.

@Composable
fun EdgeGlow(glowColor: Color) {
    val transition = rememberInfiniteTransition(label = "glow")

    // Breathing alpha
    val breathAlpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            tween(2200, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "breath"
    )

    // Traveling phase for shimmer
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(4000, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "phase"
    )

    val glowWidth = 22.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val w = size.width
                val h = size.height
                val gw = glowWidth.toPx()

                // Top edge — glow fading inward
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to glowColor.copy(alpha = breathAlpha),
                        1f to Color.Transparent,
                        startY = 0f, endY = gw
                    ),
                    size = Size(w, gw)
                )
                // Bottom edge
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to glowColor.copy(alpha = breathAlpha * 0.7f),
                        1f to Color.Transparent,
                        startY = 0f, endY = gw
                    ),
                    size = Size(w, gw),
                    topLeft = Offset(0f, h - gw)
                )
                // Left edge
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to glowColor.copy(alpha = breathAlpha * 0.85f),
                        1f to Color.Transparent,
                        startX = 0f, endX = gw
                    ),
                    size = Size(gw, h)
                )
                // Right edge
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to glowColor.copy(alpha = breathAlpha * 0.6f),
                        1f to Color.Transparent,
                        startX = 0f, endX = gw
                    ),
                    size = Size(gw, h),
                    topLeft = Offset(w - gw, 0f)
                )

                // Shimmer hotspot — a bright spot that travels along the perimeter
                // Top edge traveling left→right
                drawCircle(
                    color = glowColor.copy(alpha = breathAlpha * 0.9f),
                    radius = gw * 1.2f,
                    center = Offset(phase * w, gw * 0.5f)
                )
                // Bottom edge traveling right→left
                drawCircle(
                    color = glowColor.copy(alpha = breathAlpha * 0.5f),
                    radius = gw,
                    center = Offset((1 - phase) * w, h - gw * 0.5f)
                )
            }
    )
}

// ── Error overlay — keeps the screen alive so the user can read it ─

@Composable
fun ErrorOverlay(errorText: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Dismiss × top-right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 20.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.3f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Text("\u2715", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        }

        // Error message — centered, no card, just text on transparent bg
        Text(
            text = errorText,
            color = Color(0xFFFF6B6B),
            fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 48.dp),
            lineHeight = 22.sp
        )
    }
}

// ── Idle bar — transparent screen, floating input at bottom ────────
// No dim background. No card. Just a minimal glass bar the user can
// type into or tap the mic on. Feels like the phone itself is listening.

@Composable
fun IdleBar(
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

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        // Close button — top-right, ultra minimal
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 20.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.3f))
                .clickable { onClose() },
            contentAlignment = Alignment.Center
        ) {
            Text("\u2715", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
        }

        // Floating input bar
        Row(
            modifier = Modifier
                .padding(start = 20.dp, end = 20.dp, bottom = 36.dp)
                .fillMaxWidth()
                .height(56.dp)
                .shadow(16.dp, RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(28.dp))
                .padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Text input — transparent, no borders
            androidx.compose.foundation.text.BasicTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                textStyle = LocalTextStyle.current.copy(
                    color = Color.White,
                    fontSize = 15.sp
                ),
                cursorBrush = Brush.verticalGradient(
                    listOf(Color(0xFFB4A0FF), Color(0xFFB4A0FF))
                ),
                decorationBox = { innerTextField ->
                    Box {
                        if (inputText.isEmpty()) {
                            Text(
                                "Tell Errand what to do...",
                                color = Color.White.copy(alpha = 0.3f),
                                fontSize = 15.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )

            // Mic circle
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isListening) Color(0xFFFF5252).copy(alpha = 0.7f)
                        else Color.White.copy(alpha = 0.1f)
                    )
                    .clickable {
                        if (isListening) onStopListening()
                        else onStartListening { text -> inputText = text }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isListening) {
                    val t = rememberInfiniteTransition(label = "mic")
                    val a by t.animateFloat(
                        0.4f, 1f,
                        infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                        label = "a"
                    )
                    Text("\u23FA", color = Color.White.copy(alpha = a), fontSize = 16.sp)
                } else {
                    Text("\u23FA", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
                }
            }

            // Send arrow — only when text present
            if (inputText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF8083FF))
                        .clickable { onSubmit(inputText) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("\u25B6", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

// ── Floating capsule — the only UI during active states ────────────
// Tiny pill at the very bottom. No panel, no card. Just waveform +
// text + stop. The edge glow does all the visual heavy lifting.

@Composable
fun FloatingCapsule(
    state: String,
    pillText: String,
    onStop: () -> Unit,
    onPaymentDone: () -> Unit
) {
    val accent = when (state) {
        "Acting" -> Color(0xFF8083FF)
        "Thinking" -> Color(0xFFB4A0FF)
        "PaymentHandoff" -> Color(0xFFFFB783)
        else -> Color.Gray
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            modifier = Modifier
                .padding(bottom = 28.dp)
                .height(44.dp)
                .shadow(16.dp, RoundedCornerShape(22.dp))
                .clip(RoundedCornerShape(22.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(start = 14.dp, end = 6.dp, top = 0.dp, bottom = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Mini waveform
            MiniWaveform(color = accent, isActive = state != "Complete")

            Text(
                text = pillText,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier.widthIn(max = 160.dp)
            )

            if (state == "PaymentHandoff") {
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(Color(0xFF80FFB4).copy(alpha = 0.85f))
                        .clickable { onPaymentDone() }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Done", color = Color(0xFF0D0096), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // Stop — tiny ×
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable { onStop() },
                contentAlignment = Alignment.Center
            ) {
                Text("\u2715", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
            }
        }
    }
}

// ── Mini waveform ──────────────────────────────────────────────────

@Composable
fun MiniWaveform(color: Color, isActive: Boolean) {
    val t = rememberInfiniteTransition(label = "wf")
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(18.dp)
    ) {
        repeat(4) { i ->
            val h by t.animateFloat(
                initialValue = 3f,
                targetValue = if (isActive) (5 + (i % 3) * 4).toFloat() else 3f,
                animationSpec = infiniteRepeatable(
                    tween(250 + i * 60, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse
                ),
                label = "b$i"
            )
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(if (isActive) color else color.copy(alpha = 0.3f))
            )
        }
    }
}
