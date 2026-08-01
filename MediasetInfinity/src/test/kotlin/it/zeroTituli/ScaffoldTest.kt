package it.zeroTituli

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Serve solo a dimostrare che i test JVM del modulo girano: se questo passa, i test
 * delle attività successive hanno dove stare. Si può cancellare quando il modulo ha
 * test veri.
 */
class ScaffoldTest {
    @Test
    fun `i test girano`() {
        assertTrue(true)
    }
}
