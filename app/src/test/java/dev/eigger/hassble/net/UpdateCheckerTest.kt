package dev.eigger.hassble.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `patch and minor bumps are newer`() {
        assertTrue(UpdateChecker.isNewer("1.5.1", "1.5.0"))
        assertTrue(UpdateChecker.isNewer("1.6.0", "1.5.9"))
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun `same or older is not newer`() {
        assertFalse(UpdateChecker.isNewer("1.5.0", "1.5.0"))
        assertFalse(UpdateChecker.isNewer("1.4.9", "1.5.0"))
        assertFalse(UpdateChecker.isNewer("1.5.0", "1.5.1"))
    }

    @Test
    fun `v prefix is ignored`() {
        assertTrue(UpdateChecker.isNewer("v1.5.1", "1.5.0"))
        assertFalse(UpdateChecker.isNewer("v1.5.0", "1.5.0"))
    }

    @Test
    fun `missing components count as zero`() {
        assertFalse(UpdateChecker.isNewer("1.6", "1.6.0"))
        assertTrue(UpdateChecker.isNewer("1.6", "1.5.9"))
        assertTrue(UpdateChecker.isNewer("1.6.1", "1.6"))
    }

    @Test
    fun `two digit segments compare numerically not lexically`() {
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.0"))
        assertFalse(UpdateChecker.isNewer("1.9.0", "1.10.0"))
    }

    @Test
    fun `prerelease of the same version is older`() {
        assertFalse(UpdateChecker.isNewer("1.6.0-rc1", "1.6.0"))
        assertTrue(UpdateChecker.isNewer("1.6.0", "1.6.0-rc1"))
        assertTrue(UpdateChecker.isNewer("1.6.0-rc1", "1.5.0"))
    }

    @Test
    fun `garbage tags do not report an update`() {
        assertFalse(UpdateChecker.isNewer("", "1.5.0"))
        assertFalse(UpdateChecker.isNewer("nightly", "1.5.0"))
    }
}
