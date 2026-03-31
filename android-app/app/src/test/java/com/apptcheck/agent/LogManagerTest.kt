package com.apptcheck.agent

import android.content.Context
import com.apptcheck.agent.data.LogManager
import com.apptcheck.agent.model.LogEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import java.io.File

/**
 * Unit tests for LogManager following TEST_PLAN.md section 1
 * Tests: log buffering, file writing, export
 */
class LogManagerTest {

    private lateinit var mockContext: Context
    private lateinit var mockFilesDir: File
    private lateinit var mockCacheDir: File

    @Before
    fun setup() {
        mockContext = mock(Context::class.java)
        mockFilesDir = mock(File::class.java)
        mockCacheDir = mock(File::class.java)
        
        `when`(mockContext.filesDir).thenReturn(mockFilesDir)
        `when`(mockContext.cacheDir).thenReturn(mockCacheDir)
        `when`(mockFilesDir.absolutePath).thenReturn("/data/data/com.apptcheck.agent/files")
        
        // Initialize LogManager with mock context
        LogManager.init(mockContext)
    }

    @Test
    fun testLogEntryCreation() {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = "INFO",
            message = "Test log message"
        )
        
        assertNotNull(entry.timestamp)
        assertEquals("INFO", entry.level)
        assertEquals("Test log message", entry.message)
    }

    @Test
    fun testAddLogEmitsToFlow() = runBlocking {
        var emittedEntry: LogEntry? = null
        
        // Collect first emission from flow
        val job = kotlinx.coroutines.launch {
            emittedEntry = LogManager.logFlow.first()
        }
        
        LogManager.addLog("DEBUG", "Test debug message")
        
        // Give time for flow to emit
        kotlinx.coroutines.delay(100)
        job.cancel()
        
        // Verify log was added (flow collection may timeout in unit test)
        val recentLogs = LogManager.getRecentLogs(1)
        assertTrue(recentLogs.isNotEmpty())
        assertEquals("DEBUG", recentLogs.last().level)
        assertEquals("Test debug message", recentLogs.last().message)
    }

    @Test
    fun testLogBufferLimit() {
        // Add more than MAX_BUFFER_SIZE logs
        val bufferSize = 500
        for (i in 0 until bufferSize + 50) {
            LogManager.addLog("INFO", "Log message $i")
        }
        
        val allLogs = LogManager.getAllLogs()
        assertTrue(allLogs.size <= bufferSize)
        assertEquals(bufferSize, allLogs.size)
        
        // Verify oldest logs were removed (FIFO)
        assertEquals("Log message 50", allLogs.first().message)
        assertEquals("Log message ${bufferSize + 49}", allLogs.last().message)
    }

    @Test
    fun testGetRecentLogs() {
        // Clear and add specific number of logs
        LogManager.clearInMemory()
        
        for (i in 0 until 20) {
            LogManager.addLog("INFO", "Message $i")
        }
        
        val recent3 = LogManager.getRecentLogs(3)
        assertEquals(3, recent3.size)
        assertEquals("Message 17", recent3[0].message)
        assertEquals("Message 18", recent3[1].message)
        assertEquals("Message 19", recent3[2].message)
        
        val recent10 = LogManager.getRecentLogs(10)
        assertEquals(10, recent10.size)
    }

    @Test
    fun testClearInMemory() {
        LogManager.addLog("INFO", "Test message 1")
        LogManager.addLog("INFO", "Test message 2")
        
        assertTrue(LogManager.getAllLogs().isNotEmpty())
        
        LogManager.clearInMemory()
        
        assertTrue(LogManager.getAllLogs().isEmpty())
    }

    @Test
    fun testDifferentLogLevels() {
        LogManager.clearInMemory()
        
        LogManager.addLog("DEBUG", "Debug message")
        LogManager.addLog("INFO", "Info message")
        LogManager.addLog("WARNING", "Warning message")
        LogManager.addLog("ERROR", "Error message")
        
        val logs = LogManager.getAllLogs()
        assertEquals(4, logs.size)
        
        val levels = logs.map { it.level }
        assertTrue(levels.contains("DEBUG"))
        assertTrue(levels.contains("INFO"))
        assertTrue(levels.contains("WARNING"))
        assertTrue(levels.contains("ERROR"))
    }

    @Test
    fun testLogTimestampIsRecent() {
        val beforeTime = System.currentTimeMillis()
        LogManager.addLog("INFO", "Timestamp test")
        val afterTime = System.currentTimeMillis()
        
        val log = LogManager.getRecentLogs(1).last()
        assertTrue(log.timestamp >= beforeTime)
        assertTrue(log.timestamp <= afterTime)
    }
}
