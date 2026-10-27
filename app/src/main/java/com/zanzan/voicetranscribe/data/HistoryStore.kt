package com.zanzan.voicetranscribe.data

import android.content.Context
import org.json.JSONObject
import java.io.File

data class HistoryItem(
    val id: String,
    val title: String,
    val text: String,
    val createdAt: Long,
    val durationMs: Long,
    val source: String,
    val model: String,
    val audioPath: String = "",
)

object HistoryStore {

    private fun dir(context: Context): File =
        File(context.filesDir, "history").apply { mkdirs() }

    fun get(context: Context, id: String): HistoryItem? {
        val f = File(dir(context), "$id.json")
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            HistoryItem(
                id = f.nameWithoutExtension,
                title = o.getString("title"),
                text = o.getString("text"),
                createdAt = o.getLong("createdAt"),
                durationMs = o.getLong("durationMs"),
                source = o.getString("source"),
                model = o.getString("model"),
                audioPath = o.optString("audioPath", ""),
            )
        }.getOrNull()
    }

    fun list(context: Context): List<HistoryItem> =
        dir(context).listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { f -> get(context, f.nameWithoutExtension) }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    fun save(context: Context, item: HistoryItem) {
        val o = JSONObject()
            .put("title", item.title)
            .put("text", item.text)
            .put("createdAt", item.createdAt)
            .put("durationMs", item.durationMs)
            .put("source", item.source)
            .put("model", item.model)
            .put("audioPath", item.audioPath)
        File(dir(context), "${item.id}.json").writeText(o.toString(2))
    }

    fun newId(): String = "t_${System.currentTimeMillis()}"

    fun delete(context: Context, id: String) {
        File(dir(context), "$id.json").delete()
    }
}