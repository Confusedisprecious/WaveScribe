package com.zanzan.voicetranscribe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zanzan.voicetranscribe.asr.Mirror
import com.zanzan.voicetranscribe.asr.ModelCatalog
import com.zanzan.voicetranscribe.asr.ModelEntry
import com.zanzan.voicetranscribe.asr.ModelKind
import com.zanzan.voicetranscribe.vm.AppViewModel
import com.zanzan.voicetranscribe.vm.DownloadUiState

@Composable
fun ModelsScreen(vm: AppViewModel) {
    val dev = vm.device
    val downloads by vm.downloads.collectAsState()
    val version by vm.modelsVersion.collectAsState()
    val streamingId by vm.streamingModelId.collectAsState()
    val mirror by vm.mirror.collectAsState()
    val recommended = remember(dev) { dev.recommendedIds() }
    val downloaded = remember(version) {
        ModelCatalog.models.associate { it.id to vm.isDownloaded(it.id) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("设备信息") {
            KeyValue("设备", dev.model)
            KeyValue("处理器平台", dev.soc)
            KeyValue("运行内存", String.format("%.1f GB", dev.totalRamGB))
            KeyValue("CPU 核心数", "${dev.cores}")
            KeyValue("ABI", dev.abi)
            KeyValue("系统", dev.androidVersion)
            Text(
                dev.recommendationText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        SectionCard("模型下载源") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mirror.entries.forEach { m ->
                    FilterChip(
                        selected = mirror == m,
                        onClick = { vm.setMirror(m) },
                        label = { Text(m.label) },
                    )
                }
            }
            Text(
                mirror.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ModelCatalog.models.forEach { entry ->
            ModelCard(
                entry = entry,
                downloaded = downloaded[entry.id] == true,
                isActiveStreaming = entry.kind == ModelKind.STREAMING && streamingId == entry.id,
                recommended = entry.id in recommended,
                download = downloads[entry.id],
                onDownload = { vm.downloadModel(entry.id) },
                onDelete = { vm.deleteModel(entry.id) },
                onSetActive = { vm.setStreamingModel(entry.id) },
                onDismissError = { vm.dismissDownloadError(entry.id) },
            )
        }

        SectionCard("手动导入") {
            Text(
                "也可以用电脑把模型文件手动拷贝到下面目录（保持子目录与文件名不变，下载即可识别）：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(vm.modelsRootPath(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ModelCard(
    entry: ModelEntry,
    downloaded: Boolean,
    isActiveStreaming: Boolean,
    recommended: Boolean,
    download: DownloadUiState?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onSetActive: () -> Unit,
    onDismissError: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    entry.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (recommended) {
                    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(
                            "适合本机",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            Text(
                "${entry.kind.label} · ${entry.languages} · ${entry.sizeText}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                download?.error != null -> {
                    Text(
                        "下载失败：${download.error}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            onDismissError()
                            onDownload()
                        }) { Text("重新下载") }
                        TextButton(onClick = onDismissError) { Text("忽略") }
                    }
                }
                download != null -> {
                    Text(
                        "正在下载：${download.currentFile} · ${formatSpeed(download.speedBytesPerSec)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(
                        progress = { download.overallProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${(download.overallProgress.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!downloaded) {
                            Button(onClick = onDownload) { Text("下载") }
                        }
                        if (downloaded && entry.kind == ModelKind.STREAMING) {
                            if (isActiveStreaming) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Text(
                                        "实时引擎",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            } else {
                                TextButton(onClick = onSetActive) { Text("设为实时引擎") }
                            }
                        }
                        if (downloaded) {
                            TextButton(onClick = onDelete) { Text("删除") }
                        }
                    }
                    if (downloaded && entry.kind == ModelKind.OFFLINE) {
                        Text(
                            "已就绪：录音高精度重转与文件转写会自动使用它",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
