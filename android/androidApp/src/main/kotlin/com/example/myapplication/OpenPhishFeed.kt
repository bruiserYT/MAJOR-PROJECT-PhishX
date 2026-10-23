package com.example.myapplication

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

// ---------------------------------------------------------------------------
// Room entities
// ---------------------------------------------------------------------------

/**
 * One row per URL in the OpenPhish community feed.
 * We store the raw URL (not hashed) because we need exact-string lookup.
 * Feed URLs are treated as untrusted public data, never executed or opened.
 */
@Entity(tableName = "openphish_urls")
data class OpenPhishUrl(
    @PrimaryKey
    val url: String
)

/**
 * Single-row metadata table (rowId = 1 always) that records when the feed
 * was last successfully downloaded and stored.
 */
@Entity(tableName = "openphish_meta")
data class OpenPhishMeta(
    @PrimaryKey
    val id: Int = 1,
    /** Epoch-millisecond timestamp of the last successful feed import. */
    val lastUpdatedAt: Long
)

// ---------------------------------------------------------------------------
// DAO
// ---------------------------------------------------------------------------

@Dao
interface OpenPhishDao {

    /** Exact-match lookup for a single URL. Returns true if it is in the feed. */
    @Query("SELECT COUNT(*) > 0 FROM openphish_urls WHERE url = :url")
    suspend fun contains(url: String): Boolean

    /** Replace all stored URLs atomically by first deleting, then inserting. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(urls: List<OpenPhishUrl>)

    @Query("DELETE FROM openphish_urls")
    suspend fun deleteAll()

    /** Returns the number of URLs currently cached in the feed table. */
    @Query("SELECT COUNT(*) FROM openphish_urls")
    suspend fun count(): Int

    // --- metadata ---

    @Query("SELECT * FROM openphish_meta WHERE id = 1")
    suspend fun getMeta(): OpenPhishMeta?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMeta(meta: OpenPhishMeta)
}

// ---------------------------------------------------------------------------
// Feed check result
// ---------------------------------------------------------------------------

/**
 * The three possible outcomes of an OpenPhish feed lookup, as specified in
 * the product requirements.
 */
enum class FeedCheckResult {
    /** URL was found in the OpenPhish feed — strong phishing indicator. */
    PHISHING,

    /** Feed was searched and the URL was not found. */
    NO_FEED_MATCH,

    /**
     * Feed data is unavailable (never downloaded, DB error, etc.) so the
     * check could not be completed.
     */
    CHECK_UNAVAILABLE
}
