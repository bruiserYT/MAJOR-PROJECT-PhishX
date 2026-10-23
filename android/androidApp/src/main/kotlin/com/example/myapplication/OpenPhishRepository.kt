package com.example.myapplication

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads, parses, and stores the OpenPhish community feed, and performs
 * URL lookups against the cached copy.
 *
 * The feed format is plain text: one raw URL per line.
 * Feed data is treated as untrusted input — URLs are never opened or executed.
 */
object OpenPhishRepository {

    private const val FEED_URL = "https://openphish.com/feed.txt"
    private const val TAG = "OpenPhishRepository"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // -------------------------------------------------------------------------
    // Feed refresh
    // -------------------------------------------------------------------------

    /**
     * Downloads the OpenPhish feed over HTTPS, parses each valid URL line,
     * stores results in Room, and records the update timestamp.
     *
     * @return true on success, false on any error.
     */
    suspend fun refreshFeed(context: Context): Boolean {
        val rawText = try {
            fetchFeedText()
        } catch (e: IOException) {
            Log.w(TAG, "Network error fetching feed: ${e.javaClass.simpleName}")
            return false
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error fetching feed: ${e.javaClass.simpleName}")
            return false
        }

        if (rawText.isBlank()) {
            Log.w(TAG, "Feed response was empty")
            return false
        }

        val urls = parseFeed(rawText)
        if (urls.isEmpty()) {
            Log.w(TAG, "No valid URLs parsed from feed")
            return false
        }

        return try {
            val dao = PhishXDatabase.get(context).openPhish()
            dao.deleteAll()
            dao.insertAll(urls.map { OpenPhishUrl(it) })
            dao.saveMeta(OpenPhishMeta(lastUpdatedAt = System.currentTimeMillis()))
            Log.i(TAG, "Feed refreshed: ${urls.size} URLs stored")
            true
        } catch (e: Exception) {
            Log.w(TAG, "DB error storing feed: ${e.javaClass.simpleName}")
            false
        }
    }

    // -------------------------------------------------------------------------
    // URL download
    // -------------------------------------------------------------------------

    /**
     * Performs the HTTPS GET and returns the response body as a String.
     * Throws IOException on network failure or non-200 HTTP status.
     */
    @Throws(IOException::class)
    private fun fetchFeedText(): String {
        val request = Request.Builder()
            .url(FEED_URL)
            .header("User-Agent", "PhishX-Android/1.0")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} from OpenPhish feed")
            }
            return response.body?.string()
                ?: throw IOException("Empty body in OpenPhish response")
        }
    }

    // -------------------------------------------------------------------------
    // Feed parsing
    // -------------------------------------------------------------------------

    /**
     * Parses the plain-text feed.  Each non-blank line that starts with
     * "http://" or "https://" is accepted as a feed URL.
     * Lines are trimmed but otherwise preserved exactly; we do not strip
     * query parameters or other security-relevant components.
     *
     * Duplicate lines are de-duplicated.
     */
    internal fun parseFeed(rawText: String): Set<String> {
        return rawText
            .lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.startsWith("http://", ignoreCase = true) ||
                        line.startsWith("https://", ignoreCase = true)
            }
            .toHashSet()          // de-duplicate
    }

    // -------------------------------------------------------------------------
    // URL check
    // -------------------------------------------------------------------------

    /**
     * Checks whether [url] appears in the cached OpenPhish feed.
     *
     * Normalisation: trailing whitespace is stripped, but no other
     * transformation is applied so that security-relevant components
     * (query parameters, paths, ports) are preserved for accurate matching.
     *
     * Returns:
     * - [FeedCheckResult.PHISHING] – exact match found.
     * - [FeedCheckResult.NO_FEED_MATCH] – feed was searched, no match.
     * - [FeedCheckResult.CHECK_UNAVAILABLE] – feed is empty / not yet loaded.
     */
    suspend fun check(context: Context, url: String): FeedCheckResult {
        return try {
            val dao = PhishXDatabase.get(context).openPhish()
            val count = dao.count()
            if (count == 0) return FeedCheckResult.CHECK_UNAVAILABLE

            val normalised = url.trim()
            if (dao.contains(normalised)) {
                FeedCheckResult.PHISHING
            } else {
                FeedCheckResult.NO_FEED_MATCH
            }
        } catch (e: Exception) {
            Log.w(TAG, "DB error during feed check: ${e.javaClass.simpleName}")
            FeedCheckResult.CHECK_UNAVAILABLE
        }
    }

    /**
     * Returns the last successful feed update timestamp, or null if the
     * feed has never been downloaded.
     */
    suspend fun lastUpdatedAt(context: Context): Long? {
        return try {
            PhishXDatabase.get(context).openPhish().getMeta()?.lastUpdatedAt
        } catch (_: Exception) {
            null
        }
    }
}
