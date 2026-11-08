package com.trackstudio.artracker

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionBundleExporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun refusesToExportWhenNoCompletedCaptureExists() {
        val sessions = File(temporaryFolder.root, "sessions").apply { mkdirs() }
        val output = File(temporaryFolder.root, "exports")

        val error = try {
            SessionBundleExporter.createLatest(sessions, output)
            null
        } catch (expected: IllegalStateException) {
            expected.message
        }

        assertTrue(error?.contains("No completed capture") == true)
        assertTrue(!output.exists())
    }
}
