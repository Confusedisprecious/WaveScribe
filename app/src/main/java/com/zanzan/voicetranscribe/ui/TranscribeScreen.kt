package com.zanzan.voicetranscribe.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.zanzan.voicetranscribe.vm.AppViewModel

@Composable
fun TranscribeScreen(vm: AppViewModel, onGoToModels: () -> Unit) {
    val rec by vm.recording.collectAsState()
    val streamingId by vm.streamingModelId.collectAsState()
    val version by vm.modelsVersion.collectAsState()
    val context = LocalContext.current

    val streamingEntry = remember(streamingId, version) { vm.hub.availableStreamingEntry() }
    val offlineReady = remember(version) { vm.hub.hasOfflineModel() }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.toggleRecording()
        } else {
            android.widget.Toast.makeText(context, "需要录音权限才能实时转写", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val scroll = rememberScrollState()
    LaunchedEffect(rec.segments.size, rec.partial.length) {
        if (scroll.maxValue > 0) scroll.animateScrollTo(scroll.maxValue)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("识别引擎") {
            if (streamingEntry == null) {
                Text("尚未下载实时识别模型，无法开始听写。")
                Button(onClick = onGoToModels) { Text("去模型页下载") }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        streamingEntry.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    AssistChip(onClick = {}, label = { Text(streamingEntry.languages) })
                }
                Text(
                    if (offlineReady) "高精度重转：已就绪（SenseVoice）"
                    else "下载 SenseVoice 后，可对录音一键高精度重转",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(formatDuration(rec.elapsedMs), style = MaterialTheme.typography.headlineMedium)
                FilledIconButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            vm.toggleRecording()
                        } else {
                            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.size(88.dp),
                    shape = CircleShape,
                    colors = if (rec.recording) {
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    } else {
                        IconButtonDefaults.filledIconButtonColors()
                    },
                ) {
                    Icon(
                        if (rec.recording) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                }
                Text(
                    if (rec.recording) "正在录音，点击停止" else "点击开始实时转写",
                    style = MaterialTheme.typography.bodyMedium,
                )
                rec.error?.let { err ->
                    Text(
                        when (err) {
                            "no_streaming_model" -> "没有可用的实时模型，请到「模型」页下载"
                            "no_offline_model" -> "SenseVoice 未下载，无法高精度重转"
                            "mic_init_failed" -> "麦克风初始化失败，请检查权限后重试"
                            else -> err
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (rec.segments.isNotEmpty() || rec.partial.isNotBlank()) {
            SectionCard("转写结果") {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rec.segments.forEach { s -> Text(s) }
                        if (rec.partial.isNotBlank()) {
                            Text(
                                rec.partial,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            )
                        }
                    }
                }
            }
        }

        if (!rec.recording && rec.segments.isNotEmpty()) {
            SectionCard("本次录音（${formatDuration(rec.elapsedMs)} · ${rec.sourceModel}）") {
                Text(
                    "录音与转写结果已自动保存到「历史」，可到历史页播放原始录音。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (offlineReady) {
                        Button(
                            onClick = { vm.retranscribeWithOffline() },
                            enabled = !rec.retranscribing && rec.hasRecording,
                        ) {
                            Text(
                                if (rec.retranscribing) "重转中 ${(rec.retranscribeProgress * 100).toInt()}%"
                                else "高精度重转"
                            )
                        }
                    }
                    TextButton(onClick = { vm.clearTranscript() }) { Text("清空") }
                }
                if (rec.retranscribing) {
                    LinearProgressIndicator(
                        progress = { rec.retranscribeProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
