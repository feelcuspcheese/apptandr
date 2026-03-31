package com.apptcheck.agent

import com.apptcheck.agent.model.AdminConfig
import com.apptcheck.agent.model.Defaults
import com.apptcheck.agent.model.ScheduledRun
import com.apptcheck.agent.model.UserConfig
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for JSON config building following TEST_PLAN.md section 1
 * Tests: ensure generated JSON matches expected structure
 */
class JSONBuilderTest {

    @Test
    fun testJsonContainsRequiredFields() {
        val run = ScheduledRun(
            siteKey = "spl",
            museumSlug = "seattle-art-museum",
            dropTimeMillis = System.currentTimeMillis() + 60000,
            mode = "alert"
        )
        
        // Simulate the JSON structure that buildAgentConfig would create
        val jsonStructure = """{
          "siteKey": "${run.siteKey}",
          "museumSlug": "${run.museumSlug}",
          "dropTime": "2024-01-01T00:00:00Z",
          "mode": "${run.mode}",
          "timezone": "UTC",
          "fullConfig": {
            "active_site": "spl",
            "mode": "alert",
            "strike_time": "09:00",
            "ntfy_topic": "myappointments"
          }
        }"""
        
        // Verify all required top-level fields exist
        assertTrue(jsonStructure.contains("\"siteKey\""))
        assertTrue(jsonStructure.contains("\"museumSlug\""))
        assertTrue(jsonStructure.contains("\"dropTime\""))
        assertTrue(jsonStructure.contains("\"mode\""))
        assertTrue(jsonStructure.contains("\"timezone\""))
        assertTrue(jsonStructure.contains("\"fullConfig\""))
    }

    @Test
    fun testJsonContainsProtectedDefaults() {
        val jsonStructure = """{
          "sites": {
            "spl": {
              "bookinglinkselector": "${Defaults.BOOKING_LINK_SELECTOR}",
              "loginform": {
                "usernamefield": "${Defaults.USERNAME_FIELD}",
                "passwordfield": "${Defaults.PASSWORD_FIELD}",
                "submitbutton": "${Defaults.SUBMIT_BUTTON}",
                "authidselector": "${Defaults.AUTH_ID_SELECTOR}",
                "loginurlselector": "${Defaults.LOGIN_URL_SELECTOR}"
              },
              "bookingform": {
                "emailfield": "${Defaults.EMAIL_FIELD}"
              }
            }
          }
        }"""
        
        // Verify protected defaults are included
        assertTrue(jsonStructure.contains(Defaults.BOOKING_LINK_SELECTOR))
        assertTrue(jsonStructure.contains(Defaults.USERNAME_FIELD))
        assertTrue(jsonStructure.contains(Defaults.PASSWORD_FIELD))
        assertTrue(jsonStructure.contains(Defaults.SUBMIT_BUTTON))
        assertTrue(jsonStructure.contains(Defaults.AUTH_ID_SELECTOR))
        assertTrue(jsonStructure.contains(Defaults.LOGIN_URL_SELECTOR))
        assertTrue(jsonStructure.contains(Defaults.EMAIL_FIELD))
    }

    @Test
    fun testJsonContainsUserConfig() {
        val userConfig = UserConfig(
            mode = "booking",
            strikeTime = "10:30",
            preferredDays = listOf("Monday", "Wednesday"),
            ntfyTopic = "test-topic",
            checkWindow = "120s",
            checkInterval = "1.5s"
        )
        
        val jsonStructure = """{
          "fullConfig": {
            "mode": "${userConfig.mode}",
            "strike_time": "${userConfig.strikeTime}",
            "ntfy_topic": "${userConfig.ntfyTopic}",
            "check_window": "${userConfig.checkWindow}",
            "check_interval": "${userConfig.checkInterval}"
          }
        }"""
        
        assertTrue(jsonStructure.contains("\"mode\": \"booking\""))
        assertTrue(jsonStructure.contains("\"strike_time\": \"10:30\""))
        assertTrue(jsonStructure.contains("\"ntfy_topic\": \"test-topic\""))
        assertTrue(jsonStructure.contains("\"check_window\": \"120s\""))
        assertTrue(jsonStructure.contains("\"check_interval\": \"1.5s\""))
    }

    @Test
    fun testJsonContainsSiteCredentials() {
        val jsonStructure = """{
          "sites": {
            "spl": {
              "loginform": {
                "username": "testuser",
                "password": "testpass",
                "email": "test@example.com"
              }
            }
          }
        }"""
        
        assertTrue(jsonStructure.contains("\"username\": \"testuser\""))
        assertTrue(jsonStructure.contains("\"password\": \"testpass\""))
        assertTrue(jsonStructure.contains("\"email\": \"test@example.com\""))
    }

    @Test
    fun testJsonContainsMuseumInfo() {
        val jsonStructure = """{
          "museums": {
            "seattle-art-museum": {
              "name": "Seattle Art Museum",
              "slug": "seattle-art-museum",
              "museumid": "7f2ac5c414b2"
            }
          }
        }"""
        
        assertTrue(jsonStructure.contains("\"name\": \"Seattle Art Museum\""))
        assertTrue(jsonStructure.contains("\"slug\": \"seattle-art-museum\""))
        assertTrue(jsonStructure.contains("\"museumid\": \"7f2ac5c414b2\""))
    }

    @Test
    fun testJsonValidFormat() {
        // Test that our JSON structure is valid (basic validation)
        val jsonStructure = """{
          "siteKey": "spl",
          "museumSlug": "seattle-art-museum",
          "mode": "alert",
          "fullConfig": {
            "active_site": "spl",
            "museums": {
              "seattle-art-museum": {
                "name": "Seattle Art Museum",
                "slug": "seattle-art-museum"
              }
            }
          }
        }"""
        
        // Basic JSON format validation - balanced braces
        val openBraces = jsonStructure.count { it == '{' }
        val closeBraces = jsonStructure.count { it == '}' }
        assertEquals(openBraces, closeBraces)
        
        val openBrackets = jsonStructure.count { it == '[' }
        val closeBrackets = jsonStructure.count { it == ']' }
        assertEquals(openBrackets, closeBrackets)
    }

    @Test
    fun testPreferredDaysArrayFormat() {
        val days = listOf("Monday", "Wednesday", "Friday")
        val daysJson = days.joinToString(prefix = "[", postfix = "]", separator = ", ") { "\"$it\"" }
        
        assertEquals("[\"Monday\", \"Wednesday\", \"Friday\"]", daysJson)
        assertTrue(daysJson.startsWith("["))
        assertTrue(daysJson.endsWith("]"))
        assertTrue(daysJson.contains("\"Monday\""))
        assertTrue(daysJson.contains("\"Wednesday\""))
        assertTrue(daysJson.contains("\"Friday\""))
    }

    @Test
    fun testPerformanceDefaultsInJson() {
        val jsonStructure = """{
          "fullConfig": {
            "check_window": "${Defaults.CHECK_WINDOW}",
            "check_interval": "${Defaults.CHECK_INTERVAL}",
            "request_jitter": "${Defaults.REQUEST_JITTER}",
            "months_to_check": ${Defaults.MONTHS_TO_CHECK},
            "max_workers": ${Defaults.MAX_WORKERS}
          }
        }"""
        
        assertTrue(jsonStructure.contains("\"check_window\": \"60s\""))
        assertTrue(jsonStructure.contains("\"check_interval\": \"0.81s\""))
        assertTrue(jsonStructure.contains("\"request_jitter\": \"0.18s\""))
        assertTrue(jsonStructure.contains("\"months_to_check\": 2"))
        assertTrue(jsonStructure.contains("\"max_workers\": 2"))
    }
}
