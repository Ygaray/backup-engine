package io.github.ygaray.backupengine.model

/**
 * A fixed, non-fatal marker that media archiving failed on an otherwise-successful backup
 * (ENGINE-01, D-02).
 *
 * Carried on a [BackupResult.Success] / [DriveBackupResult.Success] when the DB backup succeeded
 * but the paired media archive (snapshot or upload) failed. Mirrors [PruneWarning] exactly: it is
 * deliberately a value-free `data object` and MUST NOT carry the failure's exception, message, or
 * any wire/stack text — only the fact that media archiving did not complete (T-15-11, ASVS V7).
 * The ViewModel maps its mere presence to fixed copy; nothing from the exception ever reaches
 * the UI.
 */
data object MediaWarning
