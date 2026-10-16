package io.github.clinejj.adguarddebug

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Timeline of everything that happened, kept in memory (newest first) and appended to a file. */
object EventLog {
    enum class Type { NET, PROBE, MARK, INFO }

    data class Event(val time: Long, val type: Type, val title: String, val detail: String)

    private const val MAX_IN_MEMORY = 1500
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private const val KEEP_LINES_ON_TRIM = 4000

    private lateinit var file: File
    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    private val fullFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val shortFmt = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "events.log")
        if (file.exists()) {
            _events.value = runCatching {
                file.readLines().takeLast(MAX_IN_MEMORY).mapNotNull(::decode).reversed()
            }.getOrDefault(emptyList())
        }
    }

    @Synchronized
    fun add(type: Type, title: String, detail: String = "") {
        val e = Event(System.currentTimeMillis(), type, title, detail)
        _events.value = (listOf(e) + _events.value).take(MAX_IN_MEMORY)
        Log.i("AGDebug", "[$type] $title")
        try {
            file.appendText(encode(e) + "\n")
            if (file.length() > MAX_FILE_BYTES) {
                val keep = file.readLines().takeLast(KEEP_LINES_ON_TRIM)
                file.writeText(keep.joinToString("\n", postfix = "\n"))
            }
        } catch (ex: Exception) {
            Log.w("AGDebug", "event log write failed", ex)
        }
    }

    @Synchronized
    fun clear() {
        file.delete()
        _events.value = emptyList()
        add(Type.INFO, "Log cleared")
    }

    /** Whole persisted log, oldest first, human readable. */
    @Synchronized
    fun readable(): String =
        if (!file.exists()) "" else file.readLines().mapNotNull(::decode).joinToString("\n") { format(it) }

    fun format(e: Event): String = buildString {
        append(fullFmt.format(Instant.ofEpochMilli(e.time))).append(" [").append(e.type).append("] ").append(e.title)
        if (e.detail.isNotBlank()) {
            e.detail.trimEnd().lines().forEach { append("\n    ").append(it) }
        }
    }

    fun shortTime(t: Long): String = shortFmt.format(Instant.ofEpochMilli(t))
    fun fullTime(t: Long): String = fullFmt.format(Instant.ofEpochMilli(t))

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

    private fun unesc(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun encode(e: Event) = "${e.time}\t${e.type}\t${esc(e.title)}\t${esc(e.detail)}"

    private fun decode(line: String): Event? {
        val p = line.split('\t', limit = 4)
        if (p.size != 4) return null
        val time = p[0].toLongOrNull() ?: return null
        val type = runCatching { Type.valueOf(p[1]) }.getOrNull() ?: return null
        return Event(time, type, unesc(p[2]), unesc(p[3]))
    }
}
