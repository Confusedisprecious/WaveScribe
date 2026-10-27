package com.zanzan.voicetranscribe.asr

import android.app.Application
import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 管理模型目录、下载状态与识别器的创建/复用。
 * 所有方法应在 IO 线程调用（识别器构造涉及模型加载）。
 */
class EngineHub(private val app: Application) {

    val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val modelsRoot: File =
        File(app.getExternalFilesDir(null) ?: app.filesDir, "models").apply { mkdirs() }

    private val _streamingModelId = MutableStateFlow(
        prefs.getString(KEY_STREAMING_MODEL, null) ?: autoPickStreamingId()
    )
    val streamingModelId: StateFlow<String?> = _streamingModelId

    private var onlineRecognizer: OnlineRecognizer? = null
    private var onlineLoadedId: String? = null
    private var offlineRecognizer: OfflineRecognizer? = null

    fun dir(entry: ModelEntry): File = File(modelsRoot, entry.dirName)

    fun isDownloaded(entry: ModelEntry): Boolean = entry.files.all { f ->
        File(dir(entry), f.name).length() >= f.sizeBytes * 0.995
    }

    fun isDownloaded(id: String): Boolean = ModelCatalog.byId(id)?.let { isDownloaded(it) } == true

    fun availableStreamingEntry(): ModelEntry? {
        val sel = _streamingModelId.value
        if (sel != null) {
            ModelCatalog.byId(sel)?.let { if (isDownloaded(it)) return it }
        }
        return ModelCatalog.streamingModels().firstOrNull { isDownloaded(it) }
    }

    fun hasOfflineModel(): Boolean = ModelCatalog.offlineModels().any { isDownloaded(it) }

    private fun autoPickStreamingId(): String? =
        ModelCatalog.streamingModels().firstOrNull { isDownloaded(it) }?.id

    fun setStreamingModel(id: String) {
        prefs.edit().putString(KEY_STREAMING_MODEL, id).apply()
        _streamingModelId.value = id
    }

    @Synchronized
    fun getOnlineRecognizer(): OnlineRecognizer? {
        val entry = availableStreamingEntry() ?: return null
        if (onlineRecognizer != null && onlineLoadedId == entry.id) return onlineRecognizer
        onlineRecognizer?.release()
        onlineRecognizer = null

        val d = dir(entry).absolutePath
        val transducer = when (entry.id) {
            "zipformer-bilingual" -> OnlineTransducerModelConfig(
                encoder = "$d/encoder-epoch-99-avg-1.int8.onnx",
                decoder = "$d/decoder-epoch-99-avg-1.onnx",
                joiner = "$d/joiner-epoch-99-avg-1.int8.onnx",
            )
            "zipformer-zh-2025" -> OnlineTransducerModelConfig(
                encoder = "$d/encoder.int8.onnx",
                decoder = "$d/decoder.onnx",
                joiner = "$d/joiner.int8.onnx",
            )
            "zipformer-zh-14m" -> OnlineTransducerModelConfig(
                encoder = "$d/encoder-epoch-99-avg-1.int8.onnx",
                decoder = "$d/decoder-epoch-99-avg-1.int8.onnx",
                joiner = "$d/joiner-epoch-99-avg-1.int8.onnx",
            )
            else -> return null
        }
        val cfg = OnlineRecognizerConfig(
            featConfig = FeatureConfig(),
            modelConfig = OnlineModelConfig(
                transducer = transducer,
                tokens = "$d/tokens.txt",
                numThreads = 2,
                modelType = if (entry.id == "zipformer-zh-2025") "zipformer2" else "zipformer",
            ),
            enableEndpoint = true,
            endpointConfig = EndpointConfig(),
        )
        return try {
            OnlineRecognizer(config = cfg).also {
                onlineRecognizer = it
                onlineLoadedId = entry.id
            }
        } catch (t: Throwable) {
            null
        }
    }

    @Synchronized
    fun getOfflineRecognizer(): OfflineRecognizer? {
        val entry = ModelCatalog.offlineModels().firstOrNull { isDownloaded(it) } ?: return null
        offlineRecognizer?.let { return it }

        val d = dir(entry).absolutePath
        val cfg = OfflineRecognizerConfig(
            featConfig = FeatureConfig(),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "$d/model.int8.onnx",
                    language = "", // 自动检测
                    useInverseTextNormalization = true,
                ),
                tokens = "$d/tokens.txt",
                numThreads = max(2, min(4, Runtime.getRuntime().availableProcessors() / 2)),
                modelType = "sense-voice",
            ),
        )
        return try {
            OfflineRecognizer(config = cfg).also { offlineRecognizer = it }
        } catch (t: Throwable) {
            null
        }
    }

    /** 模型文件变化后调用，丢弃缓存的识别器 */
    fun invalidate() {
        onlineRecognizer?.release()
        onlineRecognizer = null
        onlineLoadedId = null
        offlineRecognizer?.release()
        offlineRecognizer = null
        val sel = _streamingModelId.value
        if (sel != null) {
            val e = ModelCatalog.byId(sel)
            if (e == null || !isDownloaded(e)) {
                _streamingModelId.value = autoPickStreamingId()
            }
        }
    }

    fun deleteModel(entry: ModelEntry) {
        dir(entry).deleteRecursively()
        invalidate()
    }

    companion object {
        private const val KEY_STREAMING_MODEL = "streaming_model"
    }
}
