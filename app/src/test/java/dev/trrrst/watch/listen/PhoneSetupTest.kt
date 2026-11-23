package dev.trrrst.watch.listen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSetupTest {
    private fun state(opt: Boolean?, exact: Boolean = true, xiaomi: Boolean = true) =
        PhoneSetup.State(batteryOptimised = opt, exactAlarms = exact, isXiaomi = xiaomi)

    @Test fun xiaomiWithOptimisationOnGetsAllSteps() {
        val steps = PhoneSetup.steps(state(opt = true))
        assertTrue(steps[0].startsWith("Autostart"))
        assertTrue(steps.any { it.contains("No restrictions") })
        assertTrue(steps.any { it.startsWith("Battery optimisation") })
        assertTrue(steps.any { it.startsWith("Recents") })
        assertTrue(steps.last().contains("Test the wake-up"))
    }

    @Test fun optimisationOffDropsThatStep() {
        val steps = PhoneSetup.steps(state(opt = false))
        assertFalse(steps.any { it.startsWith("Battery optimisation") })
        // Autostart can't be checked, so it's always there on Xiaomi.
        assertTrue(steps[0].startsWith("Autostart"))
    }

    @Test fun unknownOptimisationKeepsTheStep() {
        assertTrue(PhoneSetup.steps(state(opt = null)).any { it.startsWith("Battery optimisation") })
    }

    @Test fun otherPhonesGetNoMiuiSteps() {
        val steps = PhoneSetup.steps(state(opt = true, xiaomi = false))
        assertFalse(steps.any { it.startsWith("Autostart") || it.startsWith("Recents") })
        assertTrue(steps[0].startsWith("Background"))
    }

    @Test fun statusLines() {
        assertEquals("Battery optimisation: off. Good.", PhoneSetup.batteryLine(state(opt = false)))
        assertTrue(PhoneSetup.batteryLine(state(opt = true)).contains("on"))
        assertTrue(PhoneSetup.alarmLine(state(opt = false)).startsWith("Alarms: on time"))
        assertTrue(PhoneSetup.alarmLine(state(opt = false, exact = false)).contains("late"))
    }

    @Test fun noRawErrorsOrHexInAdvice() {
        val all = listOf(true, false, null).flatMap { o ->
            listOf(true, false).flatMap { x -> PhoneSetup.steps(state(o, xiaomi = x)) + PhoneSetup.batteryLine(state(o)) }
        }
        assertTrue(all.none { it.contains("Exception") || it.contains("0x") })
    }

    @Test fun recognisesXiaomiBrands() {
        assertTrue(PhoneSetup.isXiaomi("Xiaomi", "Xiaomi"))
        assertTrue(PhoneSetup.isXiaomi("Xiaomi", "Redmi"))
        assertTrue(PhoneSetup.isXiaomi(null, "POCO"))
        assertFalse(PhoneSetup.isXiaomi("Google", "google"))
        assertFalse(PhoneSetup.isXiaomi(null, null))
    }
}
