package app.baseline.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun versions() {
        assertTrue(Updater.isNewer("v0.2.0", "0.1.0"))
        assertTrue(Updater.isNewer("v0.1.1", "0.1.0"))
        assertTrue(Updater.isNewer("v0.10.0", "0.9.9"))
        assertTrue(Updater.isNewer("v0.1.0", "0.1.0-dev"))      // a release beats a dev build of the same number
        assertFalse(Updater.isNewer("v0.1.0", "0.1.0"))
        assertFalse(Updater.isNewer("v0.1.0", "0.2.0"))
        assertFalse(Updater.isNewer("v0.0.57-ci", "0.1.0"))
    }
}
