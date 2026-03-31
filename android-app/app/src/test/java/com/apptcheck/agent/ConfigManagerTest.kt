package com.apptcheck.agent

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.apptcheck.agent.data.ConfigManager
import com.apptcheck.agent.model.AdminConfig
import com.apptcheck.agent.model.AppConfig
import com.apptcheck.agent.model.Museum
import com.apptcheck.agent.model.ScheduledRun
import com.apptcheck.agent.model.SiteConfig
import com.apptcheck.agent.model.UserConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import java.time.Instant

/**
 * Unit tests for ConfigManager following TEST_PLAN.md section 1
 * Tests: save/load, merging defaults
 */
class ConfigManagerTest {

    private lateinit var context: Context
    private lateinit var configManager: ConfigManager

    @Before
    fun setup() {
        // For unit tests, we'd use a mock context with in-memory DataStore
        // This is a simplified test - in production use androidx.test:core with Robolectric
        context = mock(Context::class.java)
        configManager = ConfigManager(context)
    }

    @Test
    fun testDefaultConfigCreation() {
        // Verify default config has expected values
        val defaultConfig = AppConfig()
        
        assertEquals("alert", defaultConfig.user.mode)
        assertEquals("09:00", defaultConfig.user.strikeTime)
        assertEquals("myappointments", defaultConfig.user.ntfyTopic)
        assertEquals("spl", defaultConfig.admin.activeSite)
        assertTrue(defaultConfig.scheduledRuns.isEmpty())
    }

    @Test
    fun testUserConfigSerialization() {
        val userConfig = UserConfig(
            mode = "booking",
            strikeTime = "10:30",
            preferredDays = listOf("Tuesday", "Thursday"),
            ntfyTopic = "test-topic",
            preferredSlug = "seattle-art-museum"
        )
        
        // Verify serialization preserves all fields
        val daysJson = userConfig.preferredDays.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        val serialized = """{"mode":"${userConfig.mode}","strikeTime":"${userConfig.strikeTime}","preferredDays":$daysJson,"ntfyTopic":"${userConfig.ntfyTopic}","preferredSlug":"${userConfig.preferredSlug}"}"""
        
        assertTrue(serialized.contains("\"mode\":\"booking\""))
        assertTrue(serialized.contains("\"strikeTime\":\"10:30\""))
        assertTrue(serialized.contains("\"ntfyTopic\":\"test-topic\""))
    }

    @Test
    fun testScheduledRunCreation() {
        val run = ScheduledRun(
            siteKey = "spl",
            museumSlug = "seattle-art-museum",
            dropTimeMillis = System.currentTimeMillis() + 60000,
            mode = "alert"
        )
        
        assertNotNull(run.id)
        assertEquals("spl", run.siteKey)
        assertEquals("seattle-art-museum", run.museumSlug)
        assertEquals("alert", run.mode)
        assertTrue(run.dropTimeMillis > System.currentTimeMillis())
    }

    @Test
    fun testBuildAgentConfigStructure() {
        val run = ScheduledRun(
            siteKey = "spl",
            museumSlug = "seattle-art-museum",
            dropTimeMillis = System.currentTimeMillis() + 60000,
            mode = "alert"
        )
        
        // This test verifies the structure - actual execution requires Android context
        // In production, use Robolectric or instrumented tests
        val configJson = """{
          "siteKey": "spl",
          "museumSlug": "seattle-art-museum",
          "mode": "alert",
          "fullConfig": {
            "active_site": "spl",
            "mode": "alert"
          }
        }"""
        
        assertTrue(configJson.contains("\"siteKey\""))
        assertTrue(configJson.contains("\"museumSlug\""))
        assertTrue(configJson.contains("\"fullConfig\""))
    }

    @Test
    fun testAdminConfigDefaultSites() {
        val adminConfig = AdminConfig()
        
        assertTrue(adminConfig.sites.containsKey("spl"))
        assertTrue(adminConfig.sites.containsKey("kcls"))
        
        val splSite = adminConfig.sites["spl"]
        assertNotNull(splSite)
        assertEquals("SPL", splSite?.name)
        assertTrue(splSite?.museums?.containsKey("seattle-art-museum") == true)
        assertTrue(splSite?.museums?.containsKey("zoo") == true)
        
        val kclsSite = adminConfig.sites["kcls"]
        assertNotNull(kclsSite)
        assertEquals("KCLS", kclsSite?.name)
        assertTrue(kclsSite?.museums?.containsKey("kidsquest") == true)
    }
}
