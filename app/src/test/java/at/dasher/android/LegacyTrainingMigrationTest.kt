package at.dasher.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Migration of pre-#37 `training/` copies into the engine-owned root file.
 * The old helpers managed `<userDir>/training/training_*.txt` — a location
 * the engine never read (its startup scan walked the data dir only) or
 * wrote (appends go to the user-dir root). DasherCore#86 makes the root
 * file load at startup, so legacy content must land there exactly once.
 */
class LegacyTrainingMigrationTest {

    private fun tempDir(name: String): File =
        Files.createTempDirectory(name).toFile()

    @Test
    fun moves_legacy_file_to_root_when_root_absent() {
        val user = tempDir("user").apply {
            File(this, "training").mkdirs()
            File(this, "training/training_english_GB.txt").writeText("imported corpus")
        }

        DasherEngine.migrateLegacyTrainingDir(user)

        assertEquals("imported corpus", File(user, "training_english_GB.txt").readText())
        assertFalse(File(user, "training").exists())
    }

    @Test
    fun appends_when_root_has_different_content() {
        val user = tempDir("user").apply {
            File(this, "training_english_GB.txt").writeText("v6 accumulated learning\n")
            File(this, "training").mkdirs()
            File(this, "training/training_english_GB.txt").writeText("legacy import")
        }

        DasherEngine.migrateLegacyTrainingDir(user)

        val root = File(user, "training_english_GB.txt").readText()
        assertTrue(root.contains("v6 accumulated learning"))
        assertTrue(root.contains("legacy import"))
        assertFalse(File(user, "training").exists())
    }

    @Test
    fun idempotent_content_already_present_is_not_duplicated() {
        val user = tempDir("user").apply {
            File(this, "training_english_GB.txt").writeText("shared corpus text\n")
            File(this, "training").mkdirs()
            File(this, "training/training_english_GB.txt").writeText("shared corpus text")
        }

        DasherEngine.migrateLegacyTrainingDir(user)
        // Second run (e.g. IME process after the app process) must be a no-op.
        DasherEngine.migrateLegacyTrainingDir(user)

        assertEquals("shared corpus text\n", File(user, "training_english_GB.txt").readText())
        assertFalse(File(user, "training").exists())
    }

    @Test
    fun no_legacy_dir_is_a_noop() {
        val user = tempDir("user").apply {
            File(this, "training_english_GB.txt").writeText("root only")
        }

        DasherEngine.migrateLegacyTrainingDir(user)

        assertEquals("root only", File(user, "training_english_GB.txt").readText())
    }

    @Test
    fun non_training_files_in_legacy_dir_are_untouched() {
        val user = tempDir("user").apply {
            File(this, "training").mkdirs()
            File(this, "training/notes.txt").writeText("keep me")
            File(this, "training/training_english_GB.txt").writeText("legacy")
        }

        DasherEngine.migrateLegacyTrainingDir(user)

        assertEquals("legacy", File(user, "training_english_GB.txt").readText())
        // Dir survives because a non-training file remains.
        assertTrue(File(user, "training/notes.txt").exists())
        assertTrue(File(user, "training").exists())
    }
}
