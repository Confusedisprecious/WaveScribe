package com.zanzan.voicetranscribe.ui

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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zanzan.voicetranscribe.vm.AppViewModel
import com.zanzan.voicetranscribe.vm.FileJobState

@Composable
fun FileScreen(vm: AppViewModel, onGoToModels: () -> Unit) {
    val job by vm.fileJob.collectAsState()
    val version by vm.modelsVersion.collectAsState()
    val offlineReady = remember(version) { vm.hub.hasOfflineModel() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.onFilePicked(uri)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("文件转写") {
            Text(
                "选择手机里的音频文件，用 SenseVoice 在本地离线转写。支持 mp3 / m4a / aac / wav / ogg / amr 等。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch("audio/*") }) {
                    Text(if (job.fileName.isBlank()) "选择音频文件" else "重新选择")
                }
                if (job.fileName.isNotBlank() && job.phase == FileJobState.Phase.READY && offlineReady) {
                    Button(onClick = { vm.startFileTranscription() }) { Text("开始转写") }
                }
            }
            if (job.fileName.isNotBlank()) {
                Text(job.fileName, style = MaterialTheme.typography.titleSmall)
                if (job.durationMs > 0) {
                    Text(
                        "时长 ${formatDuration(job.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!offlineReady) {
                Text("需要先下载 SenseVoice 离线模型", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onGoToModels) { Text("去模型页下载") }
            }
        }

        when (job.phase) {
            FileJobState.Phase.DECODING -> SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(job.progressText)
                }
            }

            FileJobState.Phase.TRANSCRIBING -> SectionCard("转写中") {
                LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                Text(job.progressText, style = MaterialTheme.typography.bodySmall)
            }

            FileJobState.Phase.DONE -> SectionCard("转写结果") {
                SelectionContainer {
                    Text(job.text.ifBlank { "（未识别到内容）" })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CopyShareActions(job.text)
                    TextButton(onClick = { vm.resetFileJob() }) { Text("清空") }
                }
            }

            FileJobState.Phase.FAILED -> SectionCard {
                Text("出错了：${job.error ?: "未知错误"}", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    vm.resetFileJob()
                    picker.launch("audio/*")
                }) { Text("重新选择文件") }
            }

            else -> {}
        }
    }
}
