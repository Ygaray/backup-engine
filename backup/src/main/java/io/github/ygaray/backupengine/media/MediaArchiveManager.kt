package io.github.ygaray.backupengine.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Snapshots a set of media directories into one namespaced zip and extracts one back, with
 * zip-slip and zip-bomb defense (ENGINE-03, D-03/D-03a).
 *
 * ### Namespace convention (Pattern 5, RESEARCH.md)
 * Each configured directory is namespaced by its own [File.name] as the top-level zip-entry
 * segment: `"${dir.name}/${relativePath}"`. This lets one archive cover multiple
 * `mediaDirectories`. **Directory-name uniqueness across the input list is a required
 * constraint** — [snapshot] fails loud (`false`) on a collision rather than silently
 * mis-routing entries into the wrong directory.
 *
 * ### Security contract (ported from SecondBrain's `MediaArchiver.safeExtract()`)
 *  1. **Zip-slip guard** (D-03a, ported VERBATIM) — an entry whose resolved [File.canonicalPath]
 *     does not begin with `stagingRoot.canonicalPath + File.separator` is rejected. The trailing
 *     separator is REQUIRED — omitting it is the classic containment-check bypass (a sibling
 *     directory sharing the base path as a string prefix would otherwise pass).
 *  2. **Per-entry + cumulative-total byte budgets** (D-03) — both decremented from bytes
 *     ACTUALLY read during the streaming copy, never from the attacker-controlled
 *     [ZipEntry.getSize].
 *  3. **All-or-nothing** (a deliberate strengthening over the ported original's per-entry-skip
 *     behavior) — any single rejected entry rejects the WHOLE archive and cleans up the partial
 *     staging directories. Safe because restore staging always happens into a throwaway sibling
 *     directory; nothing user-visible is touched until a caller finalizes the swap.
 *  4. **Non-throwing boundary** — both [snapshot] and [extract] wrap their bodies in
 *     `runCatching`; a malformed/truncated archive or an I/O error never throws across the
 *     boundary, it surfaces as a `false` return.
 */
class MediaArchiveManager {

    /**
     * Write every file under each of [dirs] into [dest] as one zip, with each entry named
     * `"${dir.name}/${relativePath}"`.
     *
     * @return `true` on success; `false` if any [dirs] share the same [File.name] (fail loud —
     *   never silently mis-route, see class KDoc) or an I/O error occurs.
     */
    suspend fun snapshot(dirs: List<File>, dest: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val names = dirs.map { it.name }
            require(names.size == names.toSet().size) {
                "mediaDirectories must have unique File.name values across the input list; got: $names"
            }
            ZipOutputStream(dest.outputStream()).use { zip ->
                for (dir in dirs) {
                    if (!dir.exists() || !dir.isDirectory) continue
                    writeDirectory(zip, dir, dir)
                }
            }
            true
        }.getOrElse {
            dest.delete()
            false
        }
    }

    private fun writeDirectory(zip: ZipOutputStream, root: File, dir: File) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            when {
                child.isDirectory -> writeDirectory(zip, root, child)
                child.isFile -> {
                    val relativePath = child.relativeTo(root).path.replace(File.separatorChar, '/')
                    zip.putNextEntry(ZipEntry("${root.name}/$relativePath"))
                    child.inputStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            zip.write(buffer, 0, read)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Extract [archive]'s entries, routing each into [stagingRoots] by its top-level namespace
     * segment (`"<dirName>/<relativePath>"`).
     *
     * @param stagingRoots dirName -> the staging root to extract that directory's entries into.
     * @param maxEntryBytes per-entry decompressed-size cap, decremented from actual streamed bytes.
     * @param maxTotalBytes cumulative decompressed-size cap across all entries in this archive.
     * @return `true` if every entry extracted successfully; `false` on ANY rejected entry (the
     *   whole archive is rejected and partial staging is cleaned up) or malformed/truncated archive.
     * @throws IllegalArgumentException if `maxEntryBytes > maxTotalBytes` (an unsatisfiable cap
     *   configuration — fail loud on the obvious misconfiguration).
     */
    suspend fun extract(
        archive: File,
        stagingRoots: Map<String, File>,
        maxEntryBytes: Long,
        maxTotalBytes: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        require(maxEntryBytes <= maxTotalBytes) {
            "maxEntryBytes ($maxEntryBytes) must be <= maxTotalBytes ($maxTotalBytes)"
        }
        runCatching {
            var remaining = maxTotalBytes
            var allOk = true
            ZipInputStream(archive.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (allOk && entry != null) {
                    if (!entry.isDirectory) {
                        val consumed = extractOneEntry(zip, entry.name, stagingRoots, maxEntryBytes, remaining)
                        if (consumed == null) {
                            allOk = false
                        } else {
                            remaining -= consumed
                        }
                    }
                    zip.closeEntry()
                    entry = if (allOk) zip.nextEntry else null
                }
            }
            if (!allOk) cleanupStaging(stagingRoots)
            allOk
        }.getOrElse {
            cleanupStaging(stagingRoots)
            false
        }
    }

    private fun cleanupStaging(stagingRoots: Map<String, File>) {
        for (root in stagingRoots.values) {
            root.deleteRecursively()
        }
    }

    /** @return bytes actually streamed on success, or `null` if the entry was rejected. */
    private fun extractOneEntry(
        zip: ZipInputStream,
        entryName: String,
        stagingRoots: Map<String, File>,
        maxEntryBytes: Long,
        budgetRemaining: Long,
    ): Long? {
        // Entry namespace is "<dirName>/<relativePath>" (Pattern 5) — reject if malformed or the
        // named directory isn't configured; never guess, never write to a default location.
        val segments = entryName.split('/', limit = 2)
        if (segments.size != 2) return null
        val (dirName, relativePath) = segments
        val stagingRoot = stagingRoots[dirName] ?: return null

        // ZIP-SLIP GUARD — ported VERBATIM from SecondBrain MediaArchiver.kt:95-106 (D-03a).
        val target = File(stagingRoot, relativePath)
        val canonicalTarget = try {
            target.canonicalPath
        } catch (_: IOException) {
            return null
        }
        val canonicalBase = stagingRoot.canonicalFile.canonicalPath + File.separator
        if (!canonicalTarget.startsWith(canonicalBase)) {
            // Entry resolves outside the staging root — zip-slip attempt; reject, write nothing.
            return null
        }

        target.parentFile?.mkdirs()
        var entryBytesRead = 0L
        var remaining = budgetRemaining
        var rejected = false
        target.outputStream().use { out ->
            val buffer = ByteArray(BUFFER_SIZE)
            var read: Int
            while (zip.read(buffer).also { read = it } != -1) {
                entryBytesRead += read
                if (entryBytesRead > maxEntryBytes) { // D-03 per-entry cap, from ACTUAL bytes read
                    rejected = true
                    break
                }
                remaining -= read
                if (remaining < 0) { // D-03 cumulative-total cap, from ACTUAL bytes read
                    rejected = true
                    break
                }
                out.write(buffer, 0, read)
            }
        }
        if (rejected) {
            target.delete()
            return null
        }
        return entryBytesRead
    }

    companion object {
        private const val BUFFER_SIZE = 8 * 1024 // 8 KiB copy buffer
    }
}
