package com.zanzan.voicetranscribe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zanzan.voicetranscribe.ui.FileScreen
import com.zanzan.voicetranscribe.ui.HistoryScreen
import com.zanzan.voicetranscribe.ui.ModelsScreen
import com.zanzan.voicetranscribe.ui.TranscribeScreen
import com.zanzan.voicetranscribe.vm.AppViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                VoiceApp()
            }
        }
    }
}

@Composable
fun VoiceApp(vm: AppViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple("实时转写", Icons.Filled.Mic, 0),
                    Triple("文件转写", Icons.Filled.GraphicEq, 1),
                    Triple("历史", Icons.Filled.History, 2),
                    Triple("模型", Icons.Filled.Equalizer, 3),
                ).forEach { (label, icon, idx) ->
                    NavigationBarItem(
                        selected = tab == idx,
                        onClick = { tab = idx },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> TranscribeScreen(vm, onGoToModels = { tab = 3 })
                1 -> FileScreen(vm, onGoToModels = { tab = 3 })
                2 -> HistoryScreen(vm)
                else -> ModelsScreen(vm)
            }
        }
    }
}
