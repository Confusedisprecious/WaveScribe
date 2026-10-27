package com.zanzan.voicetranscribe.asr

enum class ModelKind(val label: String) {
    STREAMING("实时识别"),
    OFFLINE("离线识别"),
}

enum class Mirror(val label: String, val hint: String) {
    HF_MIRROR("hf-mirror.com 直连", "国内可直连，按文件下载，流量最省"),
    GITHUB("GitHub 整包", "备用源，下载整包后自动解压所需文件"),
}

data class ModelFileSpec(val name: String, val sizeBytes: Long)

data class ModelEntry(
    val id: String,
    val displayName: String,
    val kind: ModelKind,
    val languages: String,
    val sizeText: String,
    val description: String,
    val dirName: String,
    val hfRepo: String,
    val files: List<ModelFileSpec>,
    val tarballUrl: String? = null,
    val tarballSizeBytes: Long = -1L,
    val minRamGB: Double = 0.0,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    fun hfUrl(file: ModelFileSpec): String =
        "https://hf-mirror.com/$hfRepo/resolve/main/${file.name}"
}

object ModelCatalog {

    val models: List<ModelEntry> = listOf(
        ModelEntry(
            id = "sense-voice",
            displayName = "SenseVoice Small",
            kind = ModelKind.OFFLINE,
            languages = "中 / 英 / 日 / 韩 / 粤",
            sizeText = "约 240 MB",
            description = "离线高精度引擎：录音与文件转写用它，输出自带标点与数字规整，速度极快。下载后自动作为离线引擎。",
            dirName = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
            hfRepo = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
            files = listOf(
                ModelFileSpec("model.int8.onnx", 239233841L),
                ModelFileSpec("tokens.txt", 315894L),
            ),
            tarballUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
            tarballSizeBytes = 163002883L,
            minRamGB = 3.0,
        ),
        ModelEntry(
            id = "zipformer-bilingual",
            displayName = "流式 Zipformer 中英双语",
            kind = ModelKind.STREAMING,
            languages = "中 / 英（可混说）",
            sizeText = "约 200 MB",
            description = "实时听写主引擎：边说边出字，自动断句，中英混合场景表现均衡。",
            dirName = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
            hfRepo = "csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
            files = listOf(
                ModelFileSpec("encoder-epoch-99-avg-1.int8.onnx", 181895032L),
                ModelFileSpec("decoder-epoch-99-avg-1.onnx", 13876452L),
                ModelFileSpec("joiner-epoch-99-avg-1.int8.onnx", 3228404L),
                ModelFileSpec("tokens.txt", 56317L),
            ),
            tarballUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2",
            tarballSizeBytes = 511274346L,
            minRamGB = 4.0,
        ),
        ModelEntry(
            id = "zipformer-zh-2025",
            displayName = "流式 Zipformer 中文增强",
            kind = ModelKind.STREAMING,
            languages = "中文",
            sizeText = "约 170 MB",
            description = "2025 年新版中文流式模型，纯中文更准确；不支持中英混说。",
            dirName = "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
            hfRepo = "csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
            files = listOf(
                ModelFileSpec("encoder.int8.onnx", 161141793L),
                ModelFileSpec("decoder.onnx", 5165083L),
                ModelFileSpec("joiner.int8.onnx", 1033416L),
                ModelFileSpec("tokens.txt", 20628L),
            ),
            tarballUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2",
            tarballSizeBytes = -1L,
            minRamGB = 6.0,
        ),
        ModelEntry(
            id = "zipformer-zh-14m",
            displayName = "流式 Zipformer 轻量中文",
            kind = ModelKind.STREAMING,
            languages = "中文",
            sizeText = "约 25 MB",
            description = "仅 14M 参数的极小模型，低内存设备兜底或快速体验实时转写。",
            dirName = "sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
            hfRepo = "csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23",
            files = listOf(
                ModelFileSpec("encoder-epoch-99-avg-1.int8.onnx", 21621684L),
                ModelFileSpec("decoder-epoch-99-avg-1.int8.onnx", 1888682L),
                ModelFileSpec("joiner-epoch-99-avg-1.int8.onnx", 1795562L),
                ModelFileSpec("tokens.txt", 48697L),
            ),
            tarballUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2",
            tarballSizeBytes = -1L,
            minRamGB = 0.0,
        ),
    )

    fun byId(id: String): ModelEntry? = models.firstOrNull { it.id == id }

    fun streamingModels(): List<ModelEntry> = models.filter { it.kind == ModelKind.STREAMING }

    fun offlineModels(): List<ModelEntry> = models.filter { it.kind == ModelKind.OFFLINE }
}
