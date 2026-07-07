package io.github.ygaray.backupengine.model

/**
 * An immutable descriptor of one listed backup file (LIB-02, RESEARCH structure line 212).
 *
 * Deliberately free of `:app` types and Android UI types: [ref] is an opaque handle (e.g. a SAF
 * document-URI string) the owning [io.github.ygaray.backupengine.source.BackupSource] knows how to resolve
 * back to the underlying file. Keeping it opaque lets the restore list stay source-agnostic (local
 * SAF today, cloud later) without leaking a `:backup` implementation type into `:app` (LIB-01).
 */
data class BackupRef(
    /** Opaque, source-owned handle to the backup file (e.g. a SAF document-URI string). */
    val ref: String,
    /** Human-readable file name for display in the restore list. */
    val name: String,
    /** Creation/modification time in epoch milliseconds, for sorting newest-first. */
    val timestampMillis: Long,
    /** File size in bytes, for display and sanity checks. */
    val sizeBytes: Long,
)
