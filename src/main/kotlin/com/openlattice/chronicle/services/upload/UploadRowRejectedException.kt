package com.openlattice.chronicle.services.upload

/**
 * An upload refused for a row's own content. Its 400 carries the row-level code, the only refusal the phone answers by
 * splitting the batch and quarantining the single row; every other 400 keeps the rows queued.
 */
public class UploadRowRejectedException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** [require] for a rule of one uploaded row (or of the rows' count, which splitting also resolves). */
public inline fun requireUploadRow(value: Boolean, lazyMessage: () -> String) {
    if (!value) throw UploadRowRejectedException(lazyMessage())
}

/**
 * Rows in one device upload: ten times the phone's largest batch (5,000 rows), so a real batch is never refused. The
 * request body limit bounds the memory a batch can take.
 */
public const val MAX_UPLOAD_ROWS: Int = 50_000

/** A batch over [MAX_UPLOAD_ROWS] is refused like a row defect, so the phone splits it. */
public fun requireUploadBatchSize(rows: Int): Unit =
    requireUploadRow(rows <= MAX_UPLOAD_ROWS) { "Upload batch too large: $rows rows (max $MAX_UPLOAD_ROWS)" }
