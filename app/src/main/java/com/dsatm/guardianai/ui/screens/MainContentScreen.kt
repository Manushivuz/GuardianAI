// File: MainContentScreen.kt
package com.dsatm.guardianai.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dsatm.audio_redaction.ui.AudioRedactionScreen
//import com.dsatm.image_redaction.ui.ImageRedactionScreen
import com.dsatm.text_redaction.ui.TextRedactionScreen
import com.dsatm.guardianai.ui.components.ModuleSelectorBar
import com.dsatm.guardianai.ui.components.TopAppBarWithLogo

@Composable
fun MainContentScreen() {
    var selectedModule by remember { mutableStateOf(0) }
    val modules = listOf("Image", "Audio", "Text")

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        TopAppBarWithLogo(onMenuClick = { /* TODO */ })

        ModuleSelectorBar(
            modules = modules,
            selectedIndex = selectedModule,
            onModuleSelected = { selectedModule = it }
        )

        // The content area now correctly fills the remaining space
        Column(
            modifier = Modifier
                // Use weight to make this column take up all the remaining vertical space
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            when (selectedModule) {
//                0 -> ImageRedactionScreen()
                0 -> AudioRedactionScreen()
                1 -> TextRedactionScreen()
            }
        }
    }
}