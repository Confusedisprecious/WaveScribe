package com.zanzan.voicetranscribe.download

import com.zanzan.voicetranscribe.asr.Mirror
import com.zanzan.voicetranscribe.asr.ModelEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class DownloadProgress(
    val currentFile: String,
    val fileProgress: Float,
    val overallProgress: Float,
    val speedBytesPerSec: Long,
)

object ModelDownloader {

    class DownloadException(msg: String) : Exception(msg)

    suspend fun download(
        entry: ModelEntry,
        rootDir: File,
        mirror: Mirror,
        onProgress: (DownloadProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val destDir = File(rootDir, entry.dirName).apply { mkdirs() }
        val overallTotal = entry.totalBytes
        var overallDone = entry.files.sumOf { f ->
            File(destDir, f.name).takeIf { it.length() >= f.sizeBytes }?.length() ?: 0L
        }

        when (mirror) {
            Mirror.HF_MIRROR -> {
                for (f in entry.files) {
                    val dest = File(destDir, f.name)
                    if (dest.length() >= f.sizeBytes) continue
                    val already = overallDone
                    downloadToFile(entry.hfUrl(f), dest, f.sizeBytes) { done, speed ->
                        onProgress(
                            DownloadProgress(
                                currentFile = f.name,
                                fileProgress = (done.toDouble() / f.sizeBytes).toFloat(),
                                overallProgress = ((already + done).toDouble() / overallTotal).toFloat(),
                                speedBytesPerSec = speed,
                            )
                        )
                    }
                    overallDone += f.sizeBytes
                }
            }

            Mirror.GITHUB -> {
                val tarUrl = entry.tarballUrl
                    ?: throw DownloadException("该模型没有整包下载源")
                val tarFile = File(rootDir, "${entry.id}.tar.bz2")
                val existing = if (tarFile.exists()) tarFile.length() else 0L
                val estimated = if (entry.tarballSizeBytes > 0) entry.tarballSizeBytes else existing
                downloadToFile(tarUrl, tarFile, -1L) { done, speed ->
                    val frac = if (estimated > 0) (existing + done).toDouble() / (estimated + existing) else 0.0
                    onProgress(
                        DownloadProgress(
                            currentFile = "整包下载（解压自动完成）",
                            fileProgress = frac.toFloat(),
                            overallProgress = frac.toFloat(),
                            speedBytesPerSec = speed,
                        )
                    )
                }
                extractTarBz2(tarFile, destDir, entry.files.map { it.name }.toSet())
                tarFile.delete()
            }
        }

        entry.files.forEach { f ->
            val p = File(destDir, f.name)
            if (p.length() < f.sizeBytes * 0.995) {
                throw DownloadException("校验失败：${f.name} 不完整，请重试")
            }
        }
        destDir
    }

    private fun extractTarBz2(tarFile: File, destDir: File, neededNames: Set<String>) {
        try {
            TarArchiveInputStream(BZip2CompressorInputStream(FileInputStream(tarFile), true)).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    if (entry.isDirectory) continue
                    val base = entry.name.substringAfterLast('/')
                    if (base in neededNames) {
                        File(destDir, base).outputStream().use { tar.copyTo(it) }
                    }
                }
            }
        } catch (e: Exception) {
            throw DownloadException("解压失败: ${e.message}")
        }
    }

    /** 支持断点续传的下载；expectedSize 仅用于校验（<=0 表示未知） */
    private fun downloadToFile(
        urlStr: String,
        dest: File,
        expectedSize: Long,
        onBytes: (done: Long, speed: Long) -> Unit,
    ) {
        val partFile = File(dest.absolutePath + ".part")
        var attempt = 0
        var conn: HttpURLConnection? = null
        while (true) {
            var done = if (partFile.exists()) partFile.length() else 0L
            var lastReport = System.currentTimeMillis()
            var lastDone = done
            try {
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android) VoiceTranscribe/1.0")
                    if (done > 0) setRequestProperty("Range", "bytes=$done-")
                }
                val code = conn.responseCode
                if (code != 200 && code != 206) {
                    throw DownloadException("HTTP $code")
                }
                // 服务器不支持续传时从头下
                val resuming = code == 206 && done > 0
                if (!resuming) {
                    done = 0
                    if (partFile.exists()) partFile.delete()
                }
                conn.inputStream.use { input ->
                    FileOutputStream(partFile, resuming).use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= 400) {
                                onBytes(done, (done - lastDone) * 1000 / (now - lastReport + 1))
                                lastReport = now
                                lastDone = done
                            }
                        }
                    }
                }
                if (expectedSize > 0 && done < expectedSize * 0.995) {
                    throw DownloadException("下载不完整")
                }
                if (!partFile.renameTo(dest)) {
                    throw DownloadException("无法保存文件")
                }
                return
            } catch (e: Exception) {
                attempt++
                if (attempt >= 4) {
                    throw DownloadException("${e.message ?: "网络错误"}（已重试 3 次，请切换下载源或检查网络）")
                }
                try { Thread.sleep(1500L * attempt) } catch (_: InterruptedException) {}
            } finally {
                conn?.disconnect()
                conn = null
            }
        }
    }
}
