package io.github.ygaray.backupengine

import io.github.ygaray.backupengine.model.BackupResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scaffold test proving the `:backup` module builds standalone and that [BackupResult] is an
 * exhaustively-matchable sealed type (LIB-02). The `when` below has NO `else` branch — if a new
 * variant/reason is added without a matching arm, this file fails to COMPILE, which is the
 * compile-time completeness guarantee the engine relies on.
 */
class BackupModuleScaffoldTest {

    /** Maps every [BackupResult] shape to a distinct, non-blank label via an exhaustive `when`. */
    private fun label(result: BackupResult): String = when (result) {
        is BackupResult.Success -> "success"
        is BackupResult.Failure -> when (result.reason) {
            BackupResult.Reason.SchemaTooNew -> "schema-too-new"
            BackupResult.Reason.NotAValidBackup -> "not-a-valid-backup"
            BackupResult.Reason.WriteFailed -> "write-failed"
            BackupResult.Reason.FolderUnavailable -> "folder-unavailable"
        }
    }

    @Test
    fun everyBackupResultVariantMapsToADistinctNonBlankLabel() {
        val variants: List<BackupResult> = listOf(
            BackupResult.Success,
            BackupResult.Failure(BackupResult.Reason.SchemaTooNew),
            BackupResult.Failure(BackupResult.Reason.NotAValidBackup),
            BackupResult.Failure(BackupResult.Reason.WriteFailed),
            BackupResult.Failure(BackupResult.Reason.FolderUnavailable),
        )

        val labels = variants.map { label(it) }

        // Every variant produces a non-blank label...
        labels.forEach { assertTrue("label must be non-blank", it.isNotBlank()) }
        // ...and every label is distinct (no two variants collapse to the same string).
        assertEquals("labels must be distinct", labels.size, labels.toSet().size)
        // One concrete anchor so the exhaustiveness is exercised, not just compiled.
        assertEquals("success", label(BackupResult.Success))
    }
}
