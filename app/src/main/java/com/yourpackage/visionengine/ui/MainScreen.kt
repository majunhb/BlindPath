package com.yourpackage.visionengine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MainScreen(onModeChanged: (String) -> Unit) {
    var currentMode by remember { mutableStateOf("obstacle_mode") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (currentMode == "obstacle_mode") "当前：AI避障模式" else "当前：步行导航模式",
            fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White,
            modifier = Modifier.semantics { contentDescription = "当前处于${if (currentMode == "obstacle_mode") "AI避障" else "步行导航"}模式" }
        )

        Button(
            onClick = { 
                currentMode = if (currentMode == "obstacle_mode") "navigation_mode" else "obstacle_mode"
                onModeChanged(currentMode)
            },
            modifier = Modifier.fillMaxWidth().height(200.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100))
        ) { Text("切换模式", fontSize = 40.sp, color = Color.White) }
        
        Button(
            onClick = { /* TODO: 触发SOS紧急求助 */ },
            modifier = Modifier.fillMaxWidth().height(120.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
        ) { Text("紧急求助", fontSize = 36.sp, color = Color.White) }
    }
}
