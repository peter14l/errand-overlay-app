package com.errand.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
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

class ErrandOverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: ComposeView? = null
    private var reasoningLoop: AgentReasoningLoop? = null

    // Overlay state holder variables
    private var currentState = mutableStateOf("Idle")
    private var currentPillText = mutableStateOf("Ready")
    private var activeRequest = mutableStateOf("")

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

        val customLifecycleOwner = MyLifecycleOwner()
        val customViewModelStoreOwner = MyViewModelStoreOwner()

        val currentContext = context
        val composeView = ComposeView(currentContext).apply {
            setViewTreeLifecycleOwner(customLifecycleOwner)
            setViewTreeViewModelStoreOwner(customViewModelStoreOwner)
            setContent {
                OverlayUI(
                    state = currentState.value,
                    pillText = currentPillText.value,
                    onClose = { hideOverlay() },
                    onSubmitRequest = { request ->
                        startAgent(request)
                    },
                    onPause = {
                        stopAgent()
                    }
                )
            }
        }

        windowManager.addView(composeView, params)
        overlayView = composeView
    }

    fun hideOverlay() {
        stopAgent()
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }

    private fun startAgent(request: String) {
        activeRequest.value = request
        currentState.value = "Thinking"
        currentPillText.value = "Thinking..."

        val dotenv = dotenv {
            directory = "/assets"
            filename = "env"
            ignoreIfMalformed = true
            ignoreIfMissing = true
        }
        val geminiKey = dotenv["GEMINI_API_KEY"] ?: ""

        reasoningLoop = AgentReasoningLoop(context).apply {
            setCallback(object : AgentReasoningLoop.Callback {
                override fun onStateChanged(state: String, pillText: String) {
                    currentState.value = state
                    currentPillText.value = pillText
                }

                override fun onError(error: String) {
                    currentState.value = "Idle"
                    currentPillText.value = "Error: $error"
                }

                override fun onTaskComplete() {
                    hideOverlay()
                }
            })
            startTask(request, geminiKey)
        }
    }

    private fun stopAgent() {
        reasoningLoop?.stopTask()
        reasoningLoop = null
        currentState.value = "Idle"
        currentPillText.value = "Ready"
    }
}

@Composable
fun OverlayUI(
    state: String,
    pillText: String,
    onClose: () -> Unit,
    onSubmitRequest: (String) -> Unit,
    onPause: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }

    // Glow colors mapped based on state
    val glowColor = when (state) {
        "Thinking" -> Color(0xFFC0C1FF).copy(alpha = 0.6f)
        "Acting" -> Color(0xFFBCC7DE).copy(alpha = 0.4f)
        "PaymentHandoff" -> Color(0xFFFFB783)
        "Complete" -> Color(0xFFC0C1FF)
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x0B0F10).copy(alpha = 0.3f)) // 30% dim transparency
            .border(
                width = if (glowColor != Color.Transparent) 3.dp else 0.dp,
                brush = Brush.verticalGradient(listOf(glowColor, Color.Transparent)),
                shape = RoundedCornerShape(0.dp)
            )
    ) {
        // Floating Controls Pill at the bottom or middle
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .width(340.dp)
                .background(
                    color = Color(0x1D2022).copy(alpha = 0.85f),
                    shape = RoundedCornerShape(24.dp)
                )
                .border(1.dp, Color(0xFFFFFFFF).copy(alpha = 0.1f), RoundedCornerShape(24.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status Info Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Errand Overlay",
                    color = Color(0xFFE0E3E5),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.SansSerif
                )
                Text(
                    text = "Close",
                    color = Color(0xFFFFB4AB),
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { onClose() }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Input / Pill Content
            if (state == "Idle") {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text("What task should Errand do?", color = Color(0xFF908FA0)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFFE0E3E5),
                        unfocusedTextColor = Color(0xFFE0E3E5),
                        focusedBorderColor = Color(0xFFC0C1FF),
                        unfocusedBorderColor = Color(0xFF464554)
                    )
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { if (inputText.isNotBlank()) onSubmitRequest(inputText) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8083FF)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Execute Task", color = Color(0xFF0D0096))
                }
            } else {
                // Live Status Pill Row
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

                // Pause Control
                Button(
                    onClick = { onPause() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3E495D)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Take Over (Pause)", color = Color(0xFFBCC7DE))
                }
            }
        }
    }
}
