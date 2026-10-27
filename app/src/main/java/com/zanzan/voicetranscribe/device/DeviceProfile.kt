package com.zanzan.voicetranscribe.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build

data class DeviceProfileInfo(
    val totalRamGB: Double,
    val cores: Int,
    val abi: String,
    val model: String,
    val soc: String,
    val androidVersion: String,
) {
    val tier: Tier
        get() = when {
            totalRamGB >= 8 -> Tier.FLAGSHIP
            totalRamGB >= 4 -> Tier.MAINSTREAM
            else -> Tier.ENTRY
        }

    val recommendationText: String
        get() = when (tier) {
            Tier.FLAGSHIP -> "旗舰级配置：可流畅运行全部模型。推荐「SenseVoice（高精度）+ 中英双语流式」组合，SenseVoice 也可作为流式录音的高精度重转引擎。"
            Tier.MAINSTREAM -> "主流配置：推荐「SenseVoice（高精度）+ 中英双语流式」组合。"
            Tier.ENTRY -> "入门配置：实时转写建议使用轻量中文模型；SenseVoice 仍可离线转写录音与文件。"
        }

    /** 本机适合下载的模型 id 集合 */
    fun recommendedIds(): Set<String> = when (tier) {
        Tier.FLAGSHIP -> setOf("sense-voice", "zipformer-bilingual", "zipformer-zh-2025")
        Tier.MAINSTREAM -> setOf("sense-voice", "zipformer-bilingual")
        Tier.ENTRY -> setOf("sense-voice", "zipformer-zh-14m")
    }

    enum class Tier { FLAGSHIP, MAINSTREAM, ENTRY }
}

object DeviceProfile {
    fun collect(context: Context): DeviceProfileInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return DeviceProfileInfo(
            totalRamGB = mi.totalMem / (1024.0 * 1024.0 * 1024.0),
            cores = Runtime.getRuntime().availableProcessors(),
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            model = "${Build.MANUFACTURER} ${Build.MODEL}".uppercase(),
            soc = Build.HARDWARE,
            androidVersion = "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
        )
    }
}
