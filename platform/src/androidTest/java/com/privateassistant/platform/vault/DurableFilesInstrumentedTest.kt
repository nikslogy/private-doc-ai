package com.privateassistant.platform.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/** Actual Android descriptor/durability probes using only synthetic test bytes. */
@RunWith(AndroidJUnit4::class)
class DurableFilesInstrumentedTest {
    @Test fun atomicReplacementSyncsDirectoryAndPreservesBytes() = withDirectory { directory ->
        val target = File(directory, "synthetic.bin")
        DurableFiles.atomicWrite(target) { it.write(byteArrayOf(1, 2, 3)) }
        val replacement = ByteArray(8193) { (it * 17).toByte() }
        DurableFiles.atomicWrite(target) { it.write(replacement) }
        assertArrayEquals(replacement, target.readBytes())
        assertFalse(directory.listFiles().orEmpty().any { it.name.startsWith(".atomic-") })
    }

    @Test fun directorySyncRejectsOpenedRegularFile() = withDirectory { directory ->
        DurableFiles.syncDirectory(directory)
        val regularFile = File(directory, "synthetic.bin").apply { writeBytes(byteArrayOf(1)) }
        try {
            DurableFiles.syncDirectory(regularFile)
            fail("A regular file must not satisfy directory synchronization")
        } catch (_: IOException) {
            // The descriptor's inode type must be checked before fsync.
        }
    }

    private fun withDirectory(block: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "durability-probe-${UUID.randomUUID()}")
        check(directory.mkdir())
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
