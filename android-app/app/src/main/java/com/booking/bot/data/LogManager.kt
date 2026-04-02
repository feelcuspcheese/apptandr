package com.booking.bot.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

/**
 * LogManager following TECHNICAL_SPEC.md section 7.
 * Provides live logging with in-memory buffer, file persistence, and export functionality.
 */
object LogManager {
    
    private val _logFlow = MutableSharedFlow<LogEntry>(extraBufferCapacity = 100)
    val logFlow: SharedFlow<LogEntry> = _logFlow.asSharedFlow()
    
    private val buffer = mutableListOf<LogEntry>()
    private const val MAX_BUFFER_SIZE = 500
    private lateinit var logFile: File
    
    /**
     * Initialize LogManager with context to set up log file location.
     * Should be called from Application.onCreate().
     */
    fun init(context: Context) {
        logFile = File(context.filesDir, "logs.txt")
        // [FIX (LOG-08)]: Add initial log entry visible in LogsScreen
        addLog("INFO", "App initialized successfully")
    }
    
    /**
     * Add a log entry.
     * Writes to in-memory buffer, file, and emits to logFlow for live UI updates.
     */
    fun addLog(level: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, message)
        synchronized(buffer) {
            buffer.add(entry)
            if (buffer.size > MAX_BUFFER_SIZE) {
                buffer.removeAt(0)
            }
        }
        writeToFile(entry)
        _logFlow.tryEmit(entry)
    }
    
    /**
     * Write log entry to file.
     */
    private fun writeToFile(entry: LogEntry) {
        try {
            logFile.appendText("${entry.timestamp} [${entry.level}] ${entry.message}\n")
        } catch (e: Exception) {
            // Ignore file write errors
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
        synchronized(buffer) { 
            buffer.clear() 
        }
    }
    
    /**
     * Get current buffer contents (for debugging/testing).
     */
    fun getBufferCopy(): List<LogEntry> {
        synchronized(buffer) {
            return buffer.toList()
        }
    }
}
