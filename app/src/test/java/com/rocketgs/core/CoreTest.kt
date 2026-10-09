package com.rocketgs.core

import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the core self test under JUnit (Android Studio: right-click > Run 'CoreTest'). */
class CoreTest {
    @Test fun core() {
        val failures = CoreSelfTest.run()
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
