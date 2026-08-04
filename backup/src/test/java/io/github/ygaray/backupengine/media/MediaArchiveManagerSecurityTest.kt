package io.github.ygaray.backupengine.media

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Security proving suite for [MediaArchiveManager] (ENGINE-03, D-03/D-03a). There is no existing
 * test template for this class (Pitfall 5 — SecondBrain's `MediaArchiver.safeExtract()`, the logic
 * being ported, has zero unit test coverage) — every malicious fixture below is built PROGRAMMATICALLY
 * with [ZipOutputStream] inside this test rather than shipped as a binary fixture file.
 *
 * Mirrors this module's established Robolectric conventions (`RestoreSwapInitializerTest`,
 * `BackupRepositoryRestoreCleanupTest`): `@RunWith(RobolectricTestRunner::class)`, scratch dirs under
 * `ApplicationProvider`'s `cacheDir`.
 */
@RunWith(RobolectricTestRunner::class)
class MediaArchiveManagerSecurityTest {

    private lateinit var scratch: File
    private lateinit var manager: MediaArchiveManager

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        scratch = File(ctx.cacheDir, "media-security-${System.nanoTime()}").apply { mkdirs() }
        manager = MediaArchiveManager()
    }

    // --- helpers ---

    private fun newArchive(name: String = "archive.media.zip"): File = File(scratch, name)

    private fun buildZip(archive: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(archive.outputStream()).use { zip ->
            for ((entryName, content) in entries) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(content)
                zip.closeEntry()
            }
        }
    }

    private fun stagingRoot(name: String): File =
        File(scratch, "$name-staged").apply { mkdirs() }

    private fun bytes(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    // --- Task 1: zip-slip ---

    @Test
    fun `zip-slip entry is rejected and writes zero bytes outside the staging root`() = runBlocking {
        val archive = newArchive()
        // Path-traversal entry name under the "album_images" namespace — escapes the staging root.
        buildZip(archive, listOf("album_images/../../../../outside-zipslip-evil.txt" to bytes(16)))
        val staging = stagingRoot("album_images")
        // The would-be escape target, resolved the same way the guard resolves it.
        val escapeTarget = File(staging, "../../../../outside-zipslip-evil.txt").canonicalFile

        val ok = manager.extract(
            archive = archive,
            stagingRoots = mapOf("album_images" to staging),
            maxEntryBytes = 10_000L,
            maxTotalBytes = 100_000L,
        )

        assertFalse("zip-slip archive must be rejected", ok)
        assertFalse("zero bytes must be written outside the staging root", escapeTarget.exists())
    }

    // --- Task 1: zip-bomb (per-entry) ---

    @Test
    fun `entry whose real streamed bytes exceed maxEntryBytes is rejected`() = runBlocking {
        val archive = newArchive()
        // The entry's ACTUAL written bytes (2000) exceed the cap below — not a declared ZipEntry.size lie.
        buildZip(archive, listOf("album_images/big.jpg" to bytes(2000)))
        val staging = stagingRoot("album_images")

        val ok = manager.extract(
            archive = archive,
            stagingRoots = mapOf("album_images" to staging),
            maxEntryBytes = 1000L,
            maxTotalBytes = 100_000L,
        )

        assertFalse("oversized single entry must be rejected", ok)
        assertFalse(
            "no partial file must remain for the rejected entry",
            File(staging, "big.jpg").exists(),
        )
    }

    // --- Task 1: zip-bomb (total) ---

    @Test
    fun `cumulative real streamed bytes exceeding maxTotalBytes rejects the whole archive`() = runBlocking {
        val archive = newArchive()
        // 5 entries of 1000 real bytes each (below the per-entry cap individually) sum to 5000,
        // exceeding a 3000-byte total cap.
        val entries = (1..5).map { "album_images/part-$it.txt" to bytes(1000) }
        buildZip(archive, entries)
        val staging = stagingRoot("album_images")

        val ok = manager.extract(
            archive = archive,
            stagingRoots = mapOf("album_images" to staging),
            maxEntryBytes = 10_000L,
            maxTotalBytes = 3000L,
        )

        assertFalse("total-oversized archive must be rejected", ok)
    }

    // --- Task 1: malformed namespace ---

    @Test
    fun `an entry with no dirName segment is rejected`() = runBlocking {
        val archive = newArchive()
        buildZip(archive, listOf("loose-file-no-namespace.txt" to bytes(16)))
        val staging = stagingRoot("album_images")

        val ok = manager.extract(
            archive = archive,
            stagingRoots = mapOf("album_images" to staging),
            maxEntryBytes = 10_000L,
            maxTotalBytes = 100_000L,
        )

        assertFalse("an entry with no dirName/ prefix must be rejected", ok)
    }

    @Test
    fun `an entry naming an unconfigured directory is rejected`() = runBlocking {
        val archive = newArchive()
        buildZip(archive, listOf("unconfigured_dir/file.txt" to bytes(16)))
        val staging = stagingRoot("album_images")

        val ok = manager.extract(
            archive = archive,
            stagingRoots = mapOf("album_images" to staging), // "unconfigured_dir" is absent
            maxEntryBytes = 10_000L,
            maxTotalBytes = 100_000L,
        )

        assertFalse("an entry naming a dir absent from stagingRoots must be rejected", ok)
        assertTrue(
            "no default-location write must occur for the unconfigured dir",
            staging.listFiles()?.isEmpty() != false,
        )
    }

    // --- Task 1: all-or-nothing cleanup ---

    @Test
    fun `on rejection the whole archive is rejected and partial staging is cleaned up`() = runBlocking {
        val archive = newArchive()
        // A valid entry FIRST, then a zip-slip entry — the valid entry's bytes land in staging
        // before the rejection is discovered; cleanup must remove that partial write.
        buildZip(
            archive,
            listOf(
                "album_images/valid-before-rejection.jpg" to bytes(16),
                "album_images/../../../../outside-evil-2.txt" to bytes(16),
            ),
        )
        val staging = stagingRoot("album_images")

        val ok = manager.extract(
            archive = archive,
            stagingRoots = mapOf("album_images" to staging),
            maxEntryBytes = 10_000L,
            maxTotalBytes = 100_000L,
        )

        assertFalse(ok)
        assertTrue(
            "partial staging must be cleaned up after an all-or-nothing rejection",
            staging.listFiles()?.isEmpty() != false || !staging.exists(),
        )
    }

    // --- Task 2: snapshot/extract round-trip ---

    @Test
    fun `snapshot then extract round-trips a single directory byte-identically`() = runBlocking {
        val sourceDir = File(scratch, "album_images").apply { mkdirs() }
        val nested = File(sourceDir, "card-1").apply { mkdirs() }
        File(sourceDir, "top.jpg").writeBytes(bytes(500))
        File(nested, "nested.jpg").writeBytes(bytes(750))

        val archive = newArchive("snapshot.media.zip")
        val snapshotOk = manager.snapshot(dirs = listOf(sourceDir), dest = archive)
        assertTrue("snapshot must succeed", snapshotOk)

        val staging = stagingRoot("album_images")
        val extractOk = manager.extract(
            archive = archive,
            stagingRoots = mapOf("album_images" to staging),
            maxEntryBytes = 10_000L,
            maxTotalBytes = 100_000L,
        )
        assertTrue("round-trip extract must succeed", extractOk)

        assertEquals(
            "top.jpg must round-trip byte-identically",
            File(sourceDir, "top.jpg").readBytes().toList(),
            File(staging, "top.jpg").readBytes().toList(),
        )
        assertEquals(
            "nested/nested.jpg must round-trip byte-identically",
            File(nested, "nested.jpg").readBytes().toList(),
            File(staging, "card-1/nested.jpg").readBytes().toList(),
        )
    }

    @Test
    fun `snapshot then extract round-trips multiple directories byte-identically`() = runBlocking {
        val albumDir = File(scratch, "album_images").apply { mkdirs() }
        val voiceDir = File(scratch, "voice").apply { mkdirs() }
        File(albumDir, "photo.jpg").writeBytes(bytes(300))
        File(voiceDir, "clip.m4a").writeBytes(bytes(400))

        val archive = newArchive("multi.media.zip")
        assertTrue(manager.snapshot(dirs = listOf(albumDir, voiceDir), dest = archive))

        val albumStaging = stagingRoot("album_images")
        val voiceStaging = stagingRoot("voice")
        val ok = manager.extract(
            archive = archive,
            stagingRoots = mapOf("album_images" to albumStaging, "voice" to voiceStaging),
            maxEntryBytes = 10_000L,
            maxTotalBytes = 100_000L,
        )
        assertTrue(ok)

        assertEquals(
            File(albumDir, "photo.jpg").readBytes().toList(),
            File(albumStaging, "photo.jpg").readBytes().toList(),
        )
        assertEquals(
            File(voiceDir, "clip.m4a").readBytes().toList(),
            File(voiceStaging, "clip.m4a").readBytes().toList(),
        )
    }

    // --- Task 2: name-collision fails loud ---

    @Test
    fun `snapshot fails loud on duplicate directory names instead of silently mis-routing`() = runBlocking {
        val firstAlbum = File(scratch, "shared-name").apply { mkdirs() }
        val secondAlbum = File(scratch, "nested-dupe/shared-name").apply { mkdirs() }
        File(firstAlbum, "a.jpg").writeBytes(bytes(10))
        File(secondAlbum, "b.jpg").writeBytes(bytes(10))

        val archive = newArchive("dupe.media.zip")
        val ok = manager.snapshot(dirs = listOf(firstAlbum, secondAlbum), dest = archive)

        assertFalse("duplicate dir.name across mediaDirectories must fail loud, not mis-route", ok)
    }
}
