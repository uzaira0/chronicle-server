package com.openlattice.chronicle.configuration

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadEdgeDirectoryTest {
    @Test fun missingTmpdirUsesAnAbsoluteProjectBuildDirectoryForDocker() {
        val directory = uploadEdgeDirectory(emptyMap())
        try {
            assertTrue(directory.isAbsolute)
            assertEquals(Path.of("build").toAbsolutePath(), directory.parent)
        } finally { Files.delete(directory) }
    }

    @Test fun exportedTmpdirIsPreserved() {
        val root = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "edge-parent-")
        val directory = uploadEdgeDirectory(mapOf("TMPDIR" to root.toString()))
        try { assertEquals(root, directory.parent) } finally {
            Files.delete(directory)
            Files.delete(root)
        }
    }
}
