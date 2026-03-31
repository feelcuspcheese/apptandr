package com.apptcheck.agent.data

import android.content.Context
import com.apptcheck.agent.model.LogEntry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

/**
 * Centralised Logging Manager following TECHNICAL_SPEC.md section 4.
 * All logs go through this singleton - in-memory buffer + file persistence.
 */
object LogManager {
    private val _logFlow = MutableSharedFlow<LogEntry>()
    val logFlow: SharedFlow<LogEntry> = _logFlow.asSharedFlow()

    private val buffer = mutableListOf<LogEntry>()
    private const val MAX_BUFFER_SIZE = 500
    private lateinit var logFile: File

    fun init(context: Context) {
        logFile = File(context.filesDir, "logs.txt")
    }

    fun addLog(level: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, message)
        synchronized(buffer) {
            buffer.add(entry)
            if (buffer.size > MAX_BUFFER_SIZE) buffer.removeAt(0)
        }
        writeToFile(entry)
        _logFlow.tryEmit(entry)
    }

    private fun writeToFile(entry: LogEntry) {
        try {
            val timestampStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date(entry.timestamp))
            logFile.appendText("$timestampStr [${entry.level}] ${entry.message}\n")
        } catch (e: Exception) {
            // Ignore file write errors
        }
    }

    suspend fun exportLogs(context: Context): android.net.Uri? {
        return try {
            val exportFile = File(context.cacheDir, "export_${System.currentTimeMillis()}.txt")
            if (logFile.exists()) {
                logFile.copyTo(exportFile, overwrite = true)
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    exportFile
                )
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun clearInMemory() {
        synchronized(buffer) { buffer.clear() }
    }

    fun getRecentLogs(count: Int): List<LogEntry> {
        synchronized(buffer) {
            return buffer.takeLast(count)
        }
    }

    fun getAllLogs(): List<LogEntry> {
        synchronized(buffer) {
            return buffer.toList()
        }
    }
}
