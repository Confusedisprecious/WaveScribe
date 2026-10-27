package com.zanzan.voicetranscribe.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun CopyShareActions(text: String) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { copyToClipboard(context, text) }) { Text("复制") }
        OutlinedButton(onClick = { shareText(context, text) }) { Text("分享") }
    }
}

fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("transcript", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "分享转写结果"))
}

/** 通过系统播放器播放历史里保存的原始录音 */
fun playAudio(context: Context, path: String) {
    val file = File(path)
    if (!file.exists()) {
        Toast.makeText(context, "录音文件不存在", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "audio/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, "播放原始录音"))
    } catch (e: Exception) {
        Toast.makeText(context, "未找到可播放录音的应用", Toast.LENGTH_SHORT).show()
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    return String.format("%02d:%02d:%02d", totalSec / 3600, (totalSec / 60) % 60, totalSec % 60)
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / 1073741824.0)
    bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / 1048576.0)
    bytes >= 1L shl 10 -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

fun formatSpeed(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"
