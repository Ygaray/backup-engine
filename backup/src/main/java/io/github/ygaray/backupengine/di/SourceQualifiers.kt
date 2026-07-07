package io.github.ygaray.backupengine.di

import javax.inject.Qualifier

/**
 * Hilt qualifiers distinguishing the two [io.github.ygaray.backupengine.source.BackupSource] bindings in the
 * `:backup` engine (Phase 17).
 *
 * The LOCAL SAF source stays THE unqualified `BackupSource` (nothing else re-wires and the Phase-15
 * local path is byte-identical); the Drive source is bound with [DriveSource] so
 * [io.github.ygaray.backupengine.BackupRepository]'s second constructor param resolves to it without
 * double-binding the unqualified type.
 *
 * [LocalSource] is provided for symmetry/readability should a future host want to annotate the local
 * binding explicitly; the current wiring keeps local unqualified.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DriveSource

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LocalSource
