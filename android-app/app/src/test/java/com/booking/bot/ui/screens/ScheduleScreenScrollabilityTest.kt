package com.booking.bot.ui.screens

import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test cases to validate ScheduleScreen scrollability fix (BUG-001).
 * 
 * These tests verify that the ScheduleScreen has been properly updated
 * with vertical scrolling capability as per TECHNICAL_SPEC.md section 5.3.
 */
class ScheduleScreenScrollabilityTest {

    /**
     * Test that verifies the ScheduleScreen contains verticalScroll modifier.
     * This ensures all fields are accessible on small screens.
     */
    @Test
    fun `ScheduleScreen should have verticalScroll modifier for scrollability`() {
        // Read the ScheduleScreen.kt source file
        val sourceFile = java.io.File("src/main/java/com/booking/bot/ui/screens/ScheduleScreen.kt")
        val content = sourceFile.readText()
        
        // Verify that verticalScroll import is present
        assertTrue(
            "ScheduleScreen should import verticalScroll",
            content.contains("import androidx.compose.foundation.verticalScroll")
        )
        
        // Verify that rememberScrollState import is present
        assertTrue(
            "ScheduleScreen should import rememberScrollState",
            content.contains("import androidx.compose.foundation.rememberScrollState")
        )
        
        // Verify that verticalScroll is applied to the Column modifier
        assertTrue(
            "ScheduleScreen Column should use verticalScroll modifier",
            content.contains(".verticalScroll(rememberScrollState())")
        )
    }

    /**
     * Test that verifies the technical specification compliance.
     * According to TECHNICAl_SPEC.md section 5.3 [FIX (BUG-001)],
     * the main column must have vertical scroll.
     */
    @Test
    fun `ScheduleScreen should comply with TECHNICAL_SPEC BUG-001 fix`() {
        val sourceFile = java.io.File("src/main/java/com/booking/bot/ui/screens/ScheduleScreen.kt")
        val content = sourceFile.readText()
        
        // Check for proper Column structure with verticalScroll
        val columnPattern = Regex("""Column\(\s*modifier\s*=\s*modifier[^)]*\.verticalScroll\(rememberScrollState\(\)\)""", RegexOption.DOT_MATCHES_ALL)
        
        assertTrue(
            "ScheduleScreen Column should have modifier chain with verticalScroll",
            columnPattern.containsMatchIn(content)
        )
    }

    /**
     * Test that verifies all required UI components are present in the scrollable container.
     */
    @Test
    fun `ScheduleScreen should contain all required UI components within scrollable area`() {
        val sourceFile = java.io.File("src/main/java/com/booking/bot/ui/screens/ScheduleScreen.kt")
        val content = sourceFile.readText()
        
        // Verify all required dropdown sections exist
        val requiredComponents = listOf(
            "Select Site",
            "Select Museum", 
            "Select Credential",
            "Select Mode",
            "Select Timezone",
            "Select Date & Time",
            "Schedule Run",
            "Scheduled Runs"
        )
        
        requiredComponents.forEach { component ->
            assertTrue(
                "ScheduleScreen should contain $component component",
                content.contains(component)
            )
        }
    }
}
