package com.booking.bot.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * LogManager following TECHNICAL_SPEC.md section 7.
 * Provides live logging with in-memory buffer, file persistence, and export functionality.
 *
 * THREADING CONTRACT (all public methods are safe to call from ANY thread):
 *  - in-memory buffer  → guarded by synchronized(buffer)
 *  - file writes       → serialised on a single-threaded IO dispatcher (writeScope)
 *                        so disk I/O NEVER blocks the caller (main thread, Go callback
 *                        goroutine threads, DataStore threads, etc.)
 *  - SharedFlow emit   → tryEmit is thread-safe
 */
object LogManager {

    private val _logFlow = MutableSharedFlow<LogEntry>(extraBufferCapacity = 100)
    val logFlow: SharedFlow<LogEntry> = _logFlow.asSharedFlow()

    private val buffer = mutableListOf<LogEntry>()
    private const val MAX_BUFFER_SIZE = 500
    private lateinit var logFile: File

    // -------------------------------------------------------------------------
    // FIX (Bug 1b): A single-thread-parallel IO scope serialises ALL file writes.
    // -------------------------------------------------------------------------
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writeScope = CoroutineScope(
        Dispatchers.IO.limitedParallelism(1) + SupervisorJob()
    )

    /**
     * Initialize LogManager with context to set up log file location.
     * Should be called from Application.onCreate().
     */
    fun init(context: Context) {
        logFile = File(context.filesDir, "logs.txt")
        addLog("INFO", "App initialised – log system ready")
    }

    /**
     * Add a log entry. Thread-safe; never blocks the caller.
     * Writes to in-memory buffer (synchronised), emits to logFlow (lock-free),
     * and schedules an async file write (serialised on writeScope).
     */
    fun addLog(level: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, message)

        // 1. In-memory buffer — guarded by lock (fast, no I/O)
        synchronized(buffer) {
            buffer.add(entry)
            if (buffer.size > MAX_BUFFER_SIZE) buffer.removeAt(0)
        }

        // 2. Async file write — never blocks the caller thread
        writeScope.launch { writeToFile(entry) }

        // 3. Live UI feed — tryEmit is non-blocking and thread-safe
        _logFlow.tryEmit(entry)
    }

    /**
     * Write log entry to file (runs on writeScope — single-threaded IO).
     * Private: only called from writeScope.launch{} above.
     */
    private fun writeToFile(entry: LogEntry) {
        try {
            logFile.appendText("${entry.timestamp} [${entry.level}] ${entry.message}\n")
        } catch (e: Exception) {
            // File write failures are non-fatal; entry is already in buffer + flow.
        }
    }

    /**
     * Export logs to a shareable file.
     * Returns a Uri that can be used with share sheet.
     */
    suspend fun exportLogs(context: Context): Uri {
        val exportFile = File(context.cacheDir, "export_${System.currentTimeMillis()}.txt")
        logFile.copyTo(exportFile, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exportFile)
    }

    /**
     * Clear in-memory buffer only (file persists).
     */
    fun clearInMemory() {
        synchronized(buffer) { buffer.clear() }
    }

    /**
     * Returns a copy of the current in-memory buffer.
     * Used by LogsScreen to display existing logs on open (section 5.4).
     */
    fun getCurrentLogs(): List<LogEntry> = synchronized(buffer) { buffer.toList() }
}
