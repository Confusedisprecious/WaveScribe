package com.zanzan.voicetranscribe.vm

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zanzan.voicetranscribe.asr.EngineHub
import com.zanzan.voicetranscribe.asr.Mirror
import com.zanzan.voicetranscribe.asr.ModelCatalog
import com.zanzan.voicetranscribe.asr.OfflineTranscriber
import com.zanzan.voicetranscribe.asr.StreamingTranscriber
import com.zanzan.voicetranscribe.audio.AudioFileDecoder
import com.zanzan.voicetranscribe.audio.WavUtil
import com.zanzan.voicetranscribe.data.HistoryItem
import com.zanzan.voicetranscribe.data.HistoryStore
import com.zanzan.voicetranscribe.device.DeviceProfile
import com.zanzan.voicetranscribe.device.DeviceProfileInfo
import com.zanzan.voicetranscribe.download.ModelDownloader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DownloadUiState(
    val modelId: String,
    val currentFile: String = "连接中…",
    val overallProgress: Float = 0f,
    val speedBytesPerSec: Long = 0,
    val error: String? = null,
)

data class RecordingState(
    val recording: Boolean = false,
    val elapsedMs: Long = 0,
    val partial: String = "",
    val segments: List<String> = emptyList(),
    val error: String? = null,
    val hasRecording: Boolean = false,
    val retranscribing: Boolean = false,
    val retranscribeProgress: Float = 0f,
    val sourceModel: String = "流式模型",
)

data class FileJobState(
    val phase: Phase = Phase.IDLE,
    val fileName: String = "",
    val durationMs: Long = 0,
    val progress: Float = 0f,
    val progressText: String = "",
    val text: String = "",
    val error: String? = null,
) {
    enum class Phase { IDLE, READY, DECODING, TRANSCRIBING, DONE, FAILED }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val device: DeviceProfileInfo = DeviceProfile.collect(app)
    val hub = EngineHub(app)

    private val prefs = hub.prefs

    val mirror = MutableStateFlow(
        runCatching { Mirror.valueOf(prefs.getString(KEY_MIRROR, Mirror.HF_MIRROR.name)!!) }
            .getOrDefault(Mirror.HF_MIRROR)
    )

    val history = MutableStateFlow(HistoryStore.list(app))

    val recording = MutableStateFlow(RecordingState())

    val fileJob = MutableStateFlow(FileJobState())

    val downloads = MutableStateFlow<Map<String, DownloadUiState>>(emptyMap())

    val modelsVersion = MutableStateFlow(0)

    /** 当前实时引擎使用的流式模型 id */
    val streamingModelId: StateFlow<String?> = hub.streamingModelId

    private var transcriber: StreamingTranscriber? = null
    private var sessionPcmFile: File? = null
    private var sessionReady = false
    private var elapsedJob: Job? = null
    private var recordStartMs = 0L

    /** 每次录音停止后自动保存的历史条目 id（用于重转后更新、清空时删除） */
    @Volatile
    private var sessionHistoryId: String? = null
    private var sessionSaveJob: Job? = null

    private var pendingUri: Uri? = null

    private fun app() = getApplication<Application>()

    fun isDownloaded(id: String): Boolean = hub.isDownloaded(id)

    fun modelsRootPath(): String = hub.modelsRoot.absolutePath

    private fun audioStoreDir(): File =
        File(app().getExternalFilesDir(null) ?: app().filesDir, "audio").apply { mkdirs() }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").ifBlank { "audio" }

    // ---------------- 实时转写 ----------------

    fun toggleRecording() {
        if (recording.value.recording) stopRecording() else startRecording()
    }

    private fun startRecording() = viewModelScope.launch {
        if (recording.value.recording) return@launch
        recording.value = RecordingState(recording = true, sourceModel = "流式模型")
        val recognizer = withContext(Dispatchers.IO) { hub.getOnlineRecognizer() }
        if (recognizer == null) {
            recording.value = recording.value.copy(recording = false, error = "no_streaming_model")
            return@launch
        }
        val pcm = File(app().cacheDir, "session.pcm")
        pcm.delete()
        sessionPcmFile = pcm
        sessionReady = false
        sessionHistoryId = null

        transcriber = StreamingTranscriber(
            onPartial = { t -> recording.update { it.copy(partial = t) } },
            onSegment = { t -> recording.update { it.copy(segments = it.segments + t, partial = "") } },
            onError = { msg -> recording.update { it.copy(recording = false, error = msg) } },
            pcmFile = pcm,
        )
        recordStartMs = System.currentTimeMillis()
        elapsedJob = launch {
            while (isActive) {
                recording.update { it.copy(elapsedMs = System.currentTimeMillis() - recordStartMs) }
                delay(250)
            }
        }
        val started = transcriber!!.start(recognizer)
        if (!started) {
            elapsedJob?.cancel()
            transcriber = null
            recording.value = recording.value.copy(recording = false, error = "mic_init_failed")
        }
    }

    fun stopRecording() {
        if (!recording.value.recording) return
        val finalElapsed = System.currentTimeMillis() - recordStartMs
        elapsedJob?.cancel()
        elapsedJob = null
        recording.update { it.copy(recording = false, elapsedMs = finalElapsed) }
        val t = transcriber
        transcriber = null
        val pcm = sessionPcmFile

        sessionSaveJob = viewModelScope.launch(Dispatchers.IO) {
            t?.stop()
            if (pcm != null && pcm.length() > 0) {
                val wav = File(app().cacheDir, "session.wav")
                WavUtil.pcmToWav(pcm, wav)

                // 自动保存：历史记录 + 原始录音一份
                val id = HistoryStore.newId()
                sessionHistoryId = id
                val audioFile = File(audioStoreDir(), "hist_$id.wav")
                runCatching { wav.copyTo(audioFile, overwrite = true) }

                val rec = recording.value
                val text = rec.segments.joinToString("\n")
                val item = HistoryItem(
                    id = id,
                    title = rec.segments.firstOrNull()?.take(16) ?: "语音录音",
                    text = text,
                    createdAt = System.currentTimeMillis(),
                    durationMs = rec.elapsedMs,
                    source = "实时转写",
                    model = rec.sourceModel,
                    audioPath = if (audioFile.exists()) audioFile.absolutePath else "",
                )
                HistoryStore.save(app(), item)
                history.value = HistoryStore.list(app())
                recording.update { it.copy(hasRecording = it.segments.isNotEmpty() || it.partial.isNotBlank()) }
            }
        }
    }

    fun clearTranscript() {
        if (recording.value.recording) stopRecording()
        sessionSaveJob?.cancel()
        sessionSaveJob = null
        viewModelScope.launch(Dispatchers.IO) {
            sessionPcmFile?.delete()
            File(app().cacheDir, "session.wav").delete()
            val id = sessionHistoryId
            if (id != null) {
                HistoryStore.get(app(), id)?.audioPath?.let { runCatching { File(it).delete() } }
                HistoryStore.delete(app(), id)
                history.value = HistoryStore.list(app())
            }
        }
        sessionPcmFile = null
        sessionHistoryId = null
        sessionReady = false
        recording.value = RecordingState()
    }

    fun retranscribeWithOffline() {
        val pcm = sessionPcmFile ?: return
        if (!sessionReady || recording.value.retranscribing) return
        viewModelScope.launch {
            recording.update { it.copy(retranscribing = true, retranscribeProgress = 0f, error = null) }
            try {
                val samples = withContext(Dispatchers.IO) { WavUtil.readPcmSamplesLE(pcm) }
                val recognizer = withContext(Dispatchers.IO) { hub.getOfflineRecognizer() }
                if (recognizer == null) {
                    recording.update { it.copy(retranscribing = false, error = "no_offline_model") }
                    return@launch
                }
                val text = OfflineTranscriber.transcribe(recognizer, samples) { done, total ->
                    recording.update { it.copy(retranscribeProgress = done.toFloat() / total) }
                }
                val segs = text.split('\n').filter { it.isNotBlank() }
                recording.update {
                    it.copy(
                        retranscribing = false,
                        retranscribeProgress = 1f,
                        segments = segs,
                        partial = "",
                        sourceModel = "SenseVoice 高精度",
                    )
                }
                // 同步更新已自动保存的历史条目
                withContext(Dispatchers.IO) {
                    val id = sessionHistoryId
                    if (id != null) {
                        val item = HistoryStore.get(app(), id)
                        if (item != null) {
                            HistoryStore.save(
                                app(),
                                item.copy(
                                    text = segs.joinToString("\n"),
                                    source = "实时转写",
                                    model = "SenseVoice 高精度",
                                ),
                            )
                            history.value = HistoryStore.list(app())
                        }
                    }
                }
            } catch (e: Exception) {
                recording.update { it.copy(retranscribing = false, error = e.message ?: "重转失败") }
            }
        }
    }

    // ---------------- 文件转写 ----------------

    fun onFilePicked(uri: Uri) {
        pendingUri = uri
        fileJob.value = FileJobState(phase = FileJobState.Phase.READY, fileName = queryDisplayName(uri))
    }

    fun startFileTranscription() {
        val uri = pendingUri ?: return
        if (fileJob.value.phase == FileJobState.Phase.DECODING ||
            fileJob.value.phase == FileJobState.Phase.TRANSCRIBING
        ) return
        viewModelScope.launch {
            var storedAudio: File? = null
            try {
                val name = fileJob.value.fileName.ifBlank { queryDisplayName(uri) }

                // 1) 先把原始文件持久化到音频目录（无论转写成败都会在成功时进入历史）
                val audioFile = withContext(Dispatchers.IO) {
                    val f = File(
                        audioStoreDir(),
                        "hist_${HistoryStore.newId()}_${sanitizeFileName(name)}",
                    )
                    AudioFileDecoder.copyUriToFile(app(), uri, f)
                    f
                }
                storedAudio = audioFile

                // 2) 用临时副本解码
                fileJob.update {
                    it.copy(phase = FileJobState.Phase.DECODING, progressText = "解码音频中…", error = null)
                }
                val samples = withContext(Dispatchers.IO) {
                    val tmp = File(app().cacheDir, "import_${System.currentTimeMillis()}.bin")
                    try {
                        audioFile.copyTo(tmp, overwrite = true)
                        AudioFileDecoder.decodeTo16kMono(tmp)
                    } finally {
                        tmp.delete()
                    }
                }
                if (samples.isEmpty()) throw Exception("解码结果为空，可能是不支持的格式")
                val durationMs = samples.size.toLong() * 1000L / 16000L
                fileJob.update { it.copy(durationMs = durationMs) }

                val recognizer = withContext(Dispatchers.IO) { hub.getOfflineRecognizer() }
                if (recognizer == null) {
                    fileJob.update { it.copy(phase = FileJobState.Phase.FAILED, error = "no_offline_model") }
                    return@launch
                }
                fileJob.update {
                    it.copy(phase = FileJobState.Phase.TRANSCRIBING, progress = 0f, progressText = "准备转写…")
                }
                val text = OfflineTranscriber.transcribe(recognizer, samples) { done, total ->
                    fileJob.update {
                        it.copy(progress = done.toFloat() / total, progressText = "转写中 $done / $total 段")
                    }
                }
                fileJob.update { it.copy(phase = FileJobState.Phase.DONE, text = text, progress = 1f) }

                // 3) 保存历史（含原始录音）
                saveHistory(
                    title = name,
                    text = text,
                    durationMs = durationMs,
                    source = "文件转写",
                    model = "SenseVoice",
                    audioPath = audioFile.absolutePath,
                )
            } catch (e: Exception) {
                // 失败时清理已落盘的原始文件，避免留下孤儿文件
                storedAudio?.let { runCatching { it.delete() } }
                val msg = when (e.message) {
                    "no_offline_model" -> "SenseVoice 模型未下载"
                    null -> "处理失败"
                    else -> e.message
                }
                fileJob.update { it.copy(phase = FileJobState.Phase.FAILED, error = msg) }
            }
        }
    }

    fun resetFileJob() {
        pendingUri = null
        fileJob.value = FileJobState()
    }

    private fun queryDisplayName(uri: Uri): String {
        return try {
            app().contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "audio_${System.currentTimeMillis()}"
        } catch (_: Exception) {
            "audio_${System.currentTimeMillis()}"
        }
    }

    // ---------------- 模型管理 ----------------

    fun setMirror(m: Mirror) {
        mirror.value = m
        prefs.edit().putString(KEY_MIRROR, m.name).apply()
    }

    fun downloadModel(id: String) {
        val entry = ModelCatalog.byId(id) ?: return
        if (downloads.value.containsKey(id)) return
        viewModelScope.launch {
            downloads.update { it + (id to DownloadUiState(modelId = id)) }
            try {
                ModelDownloader.download(entry, hub.modelsRoot, mirror.value) { p ->
                    downloads.update {
                        it + (id to DownloadUiState(
                            modelId = id,
                            currentFile = p.currentFile,
                            overallProgress = p.overallProgress,
                            speedBytesPerSec = p.speedBytesPerSec,
                        ))
                    }
                }
                hub.invalidate()
                modelsVersion.update { v -> v + 1 }
                downloads.update { it - id }
            } catch (e: Exception) {
                downloads.update {
                    it + (id to DownloadUiState(modelId = id, error = e.message ?: "下载失败"))
                }
            }
        }
    }

    fun dismissDownloadError(id: String) {
        downloads.update { it - id }
    }

    fun deleteModel(id: String) {
        val entry = ModelCatalog.byId(id) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            hub.deleteModel(entry)
            modelsVersion.update { v -> v + 1 }
        }
    }

    fun setStreamingModel(id: String) {
        hub.setStreamingModel(id)
        modelsVersion.update { v -> v + 1 }
    }

    // ---------------- 历史 ----------------

    fun saveHistory(
        title: String,
        text: String,
        durationMs: Long,
        source: String,
        model: String,
        audioPath: String = "",
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            HistoryStore.save(
                app(),
                HistoryItem(
                    id = HistoryStore.newId(),
                    title = title,
                    text = text,
                    createdAt = System.currentTimeMillis(),
                    durationMs = durationMs,
                    source = source,
                    model = model,
                    audioPath = audioPath,
                ),
            )
            history.value = HistoryStore.list(app())
        }
    }

    fun deleteHistory(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            HistoryStore.get(app(), id)?.audioPath?.let { runCatching { File(it).delete() } }
            HistoryStore.delete(app(), id)
            history.value = HistoryStore.list(app())
        }
    }

    companion object {
        private const val KEY_MIRROR = "download_mirror"
    }
}