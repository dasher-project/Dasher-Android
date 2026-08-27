package at.dasher.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Migration of engine-written user state out of the (re-extractable) data
 * directory. Builds before the split shared one directory for bundled data
 * AND user state, so every DATA_VERSION bump wiped `dasher_settings.xml`
 * with the re-extraction — users lost input filter, speed, alphabet on every
 * app update that bumped the DasherCore pin.
 */
class DataInstallerMigrationTest {

    private fun tempDir(name: String): File =
        Files.createTempDirectory(name).toFile()

    @Test
    fun migrates_settings_and_root_training_files() {
        val shared = tempDir("shared").apply {
            // Bundled data layout...
            File(this, "alphabets").mkdirs()
            File(this, "training").mkdirs()
            File(this, "training/bundled.txt").writeText("bundled")
            File(this, ".installed_v1").writeText("marker")
            // ...plus engine-written user state at the root.
            File(this, "dasher_settings.xml").writeText("<settings/>")
            File(this, "training_english_GB.txt").writeText("user delta")
        }
        val user = tempDir("user")

        DataInstaller.migrateUserArtifacts(shared, user)

        assertTrue(File(user, "dasher_settings.xml").exists())
        assertEquals("user delta", File(user, "training_english_GB.txt").readText())
        // Bundled training (inside the training/ subdir) is NOT user state.
        assertFalse(File(user, "bundled.txt").exists())
        // Copies, not moves: originals stay for the subsequent wipe to clean.
        assertTrue(File(shared, "dasher_settings.xml").exists())
    }

    @Test
    fun no_user_state_is_a_noop() {
        val shared = tempDir("shared").apply {
            File(this, "alphabets").mkdirs()
        }
        val user = tempDir("user")

        DataInstaller.migrateUserArtifacts(shared, user)

        assertFalse(File(user, "dasher_settings.xml").exists())
        assertEquals(0, user.listFiles()?.size ?: 0)
    }

    @Test
    fun missing_shared_dir_is_a_noop() {
        val shared = File(tempDir("root"), "does-not-exist")
        val user = tempDir("user")

        DataInstaller.migrateUserArtifacts(shared, user)

        assertEquals(0, user.listFiles()?.size ?: 0)
    }
}
