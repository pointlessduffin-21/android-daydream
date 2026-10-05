package com.daydream.standby.data.photos

/** A MediaStore `selection` clause with its bound `selectionArgs`. */
internal class LocalQuery(val selection: String, val selectionArgs: Array<String>)

/** Builds the MediaStore image filter for the "This device" source; pure so it runs on the JVM. */
internal object LocalPhotoQuery {
    /** Bucket (folder) names skipped when the user hasn't picked specific folders. */
    val EXCLUDED_BUCKET_NAMES = listOf("Screenshots", "Screen recordings")

    /** SQLite allows 999 bound parameters on older devices; stay well below that. */
    const val MAX_BUCKET_IDS = 500

    /**
     * With no [bucketIds], everything except [EXCLUDED_BUCKET_NAMES]. Otherwise exactly the given
     * folders (screenshots included if picked). Non-numeric ids are dropped (bucket ids are longs),
     * duplicates collapse, and at most [MAX_BUCKET_IDS] ids are used, in iteration order. If every
     * id is junk, falls back to the default filter rather than matching nothing.
     */
    fun build(
        bucketIds: Collection<String>,
        bucketIdColumn: String,
        bucketNameColumn: String,
    ): LocalQuery {
        val ids = bucketIds.asSequence()
            .map { it.trim() }
            .filter { it.toLongOrNull() != null }
            .distinct()
            .take(MAX_BUCKET_IDS)
            .toList()
        if (ids.isEmpty()) {
            val placeholders = EXCLUDED_BUCKET_NAMES.joinToString(", ") { "?" }
            return LocalQuery(
                "$bucketNameColumn IS NULL OR $bucketNameColumn NOT IN ($placeholders)",
                EXCLUDED_BUCKET_NAMES.toTypedArray(),
            )
        }
        return LocalQuery("$bucketIdColumn IN (${ids.joinToString(", ") { "?" }})", ids.toTypedArray())
    }
}
