package com.errand.overlay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private var overlayController: ErrandOverlayController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlayController = ErrandOverlayController(this)

        setContent {
            HomeScreenUI(
                onOpenOverlay = {
                    if (Settings.canDrawOverlays(this)) {
                        overlayController?.showOverlay()
                    } else {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                },
                onOpenAccessibilitySettings = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                }
            )
        }
    }
}

@Composable
fun HomeScreenUI(
    onOpenOverlay: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    var stepIndex by remember { mutableStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F10))
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "ERRAND",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFC0C1FF),
                fontFamily = FontFamily.SansSerif
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Privacy-First Task Automation Agent",
                fontSize = 14.sp,
                color = Color(0xFF908FA0)
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Minimal Onboarding Carousels / Flow matching screens
            when (stepIndex) {
                0 -> {
                    // Onboarding Value Screen UI
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Delegate tasks, save time",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFE0E3E5)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Errand automates food ordering across Swiggy, Zomato, and Zepto autonomously.",
                            fontSize = 14.sp,
                            color = Color(0xFFC7C4D7),
                            modifier = Modifier.padding(horizontal = 16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { stepIndex = 1 },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8083FF))
                        ) {
                            Text("Next", color = Color(0xFF0D0096))
                        }
                    }
                }
                1 -> {
                    // Onboarding Privacy Screen UI
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Privacy First. Always.",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFE0E3E5)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No user data is stored, sold, or sent to cloud servers. Temporary files are deleted immediately after execution steps.",
                            fontSize = 14.sp,
                            color = Color(0xFFC7C4D7),
                            modifier = Modifier.padding(horizontal = 16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { stepIndex = 2 },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8083FF))
                        ) {
                            Text("Next", color = Color(0xFF0D0096))
                        }
                    }
                }
                2 -> {
                    // Onboarding Access / Service Configuration UI
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Permissions Required",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFE0E3E5)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Enable Overlay and Accessibility Service in Android Settings to allow Errand to run automations.",
                            fontSize = 14.sp,
                            color = Color(0xFFC7C4D7),
                            modifier = Modifier.padding(horizontal = 16.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { onOpenAccessibilitySettings() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3E495D))
                        ) {
                            Text("Enable Accessibility", color = Color(0xFFD8E3FB))
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { onOpenOverlay() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8083FF))
                        ) {
                            Text("Launch Errand Overlay", color = Color(0xFF0D0096))
                        }
                    }
                }
            }
        }
    }
}
