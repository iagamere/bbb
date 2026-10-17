package com.ps4.builder.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val tag: String,          // build-<run number>
    val runNumber: Long,
    val time: String,         // ISO time from status.json (or local time if missing)
    val ok: Boolean,
    val version: String,
    val elfName: String,      // original name, e.g. ezremote-server-1.2.3.elf ("" if failed)
    val elfFile: String,      // file name inside filesDir/elf ("" if none)
    val elfSize: Long,
    val zipName: String,
    val runUrl: String
)

class HistoryStore(private val filesDir: File) {
    private val file = File(filesDir, "history.json")

    fun elfPath(e: HistoryEntry): File? =
        if (e.elfFile.isBlank()) null else File(filesDir, "elf/${e.elfFile}").takeIf { it.isFile }

    fun elfTarget(name: String): File = File(filesDir, "elf").apply { mkdirs() }.let { File(it, name) }
    fun logDir(tag: String): File = File(filesDir, "logs/$tag").apply { mkdirs() }

    fun load(): List<HistoryEntry> = try {
        if (!file.isFile) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    tag = o.getString("tag"), runNumber = o.optLong("runNumber"),
                    time = o.optString("time"), ok = o.optBoolean("ok"),
                    version = o.optString("version"), elfName = o.optString("elfName"),
                    elfFile = o.optString("elfFile"), elfSize = o.optLong("elfSize"),
                    zipName = o.optString("zipName"), runUrl = o.optString("runUrl")
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("tag", it.tag); put("runNumber", it.runNumber); put("time", it.time)
                put("ok", it.ok); put("version", it.version); put("elfName", it.elfName)
                put("elfFile", it.elfFile); put("elfSize", it.elfSize); put("zipName", it.zipName)
                put("runUrl", it.runUrl)
            })
        }
        file.writeText(arr.toString())
    }
}
