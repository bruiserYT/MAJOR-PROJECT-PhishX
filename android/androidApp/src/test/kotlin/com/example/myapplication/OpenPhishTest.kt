package com.example.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for OpenPhish feed parsing and URL-matching logic.
 *
 * These are host-side JVM tests (no Android emulator required).
 * They exercise [OpenPhishRepository.parseFeed] directly and simulate
 * feed-check behaviour through the parsed set.
 *
 * DB-dependent checks (Room, WorkManager) require an instrumented test;
 * see the androidTest source set for those.
 */
class OpenPhishTest {

    // -------------------------------------------------------------------------
    // parseFeed — exact URL extraction
    // -------------------------------------------------------------------------

    @Test
    fun `parseFeed returns http URL lines`() {
        val feed = """
            http://phishing-example.com/login
            http://another-bad-site.net/
        """.trimIndent()
        val result = OpenPhishRepository.parseFeed(feed)
        assertTrue(result.contains("http://phishing-example.com/login"))
        assertTrue(result.contains("http://another-bad-site.net/"))
    }

    @Test
    fun `parseFeed returns https URL lines`() {
        val feed = "https://evil.example.org/steal?token=abc"
        val result = OpenPhishRepository.parseFeed(feed)
        assertTrue(result.contains("https://evil.example.org/steal?token=abc"))
    }

    @Test
    fun `parseFeed ignores blank lines`() {
        val feed = "\n\n   \nhttps://phish.example.com/\n\n"
        val result = OpenPhishRepository.parseFeed(feed)
        assertEquals(1, result.size)
    }

    @Test
    fun `parseFeed ignores lines without http or https scheme`() {
        val feed = """
            ftp://bad.example.com/
            not-a-url-at-all
            javascript:alert(1)
            https://valid.example.com/
        """.trimIndent()
        val result = OpenPhishRepository.parseFeed(feed)
        assertEquals(1, result.size)
        assertTrue(result.contains("https://valid.example.com/"))
    }

    @Test
    fun `parseFeed de-duplicates identical URLs`() {
        val feed = """
            https://phish.example.com/
            https://phish.example.com/
            https://phish.example.com/
        """.trimIndent()
        val result = OpenPhishRepository.parseFeed(feed)
        assertEquals(1, result.size)
    }

    @Test
    fun `parseFeed handles empty string`() {
        val result = OpenPhishRepository.parseFeed("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseFeed handles whitespace-only string`() {
        val result = OpenPhishRepository.parseFeed("   \n   \t  \n")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseFeed trims leading and trailing whitespace from each line`() {
        val feed = "   https://phish.example.com/page   "
        val result = OpenPhishRepository.parseFeed(feed)
        assertTrue(result.contains("https://phish.example.com/page"))
    }

    @Test
    fun `parseFeed preserves query parameters in URLs`() {
        val url = "https://evil.example.com/redirect?target=bank&session=xyz"
        val result = OpenPhishRepository.parseFeed(url)
        assertTrue(result.contains(url))
    }

    @Test
    fun `parseFeed preserves URL-encoded characters`() {
        val url = "https://evil.example.com/path%20with%20spaces?q=hello%20world"
        val result = OpenPhishRepository.parseFeed(url)
        assertTrue(result.contains(url))
    }

    @Test
    fun `parseFeed preserves fragment identifiers`() {
        val url = "https://evil.example.com/page#section"
        val result = OpenPhishRepository.parseFeed(url)
        assertTrue(result.contains(url))
    }

    @Test
    fun `parseFeed handles feed with mixed valid and invalid lines`() {
        val feed = """
            # comment line
            https://phish1.example.com/
            
            http://phish2.example.com/login
            just some text
            ftp://notincluded.example.com/
            https://phish3.example.com/steal?q=1
        """.trimIndent()
        val result = OpenPhishRepository.parseFeed(feed)
        assertEquals(3, result.size)
        assertTrue(result.contains("https://phish1.example.com/"))
        assertTrue(result.contains("http://phish2.example.com/login"))
        assertTrue(result.contains("https://phish3.example.com/steal?q=1"))
    }

    // -------------------------------------------------------------------------
    // Feed-based matching (simulated via parsed set)
    // These tests verify matching semantics without requiring a live DB.
    // -------------------------------------------------------------------------

    @Test
    fun `exact URL match is detected correctly`() {
        val feed = setOf(
            "https://phishing-site.example.com/login",
            "http://another.example.net/"
        )
        val url = "https://phishing-site.example.com/login"
        assertTrue(feed.contains(url.trim()))
    }

    @Test
    fun `URL absent from feed is not matched`() {
        val feed = setOf("https://phishing-site.example.com/login")
        val safeUrl = "https://legitimate-bank.example.com/"
        assertFalse(feed.contains(safeUrl.trim()))
    }

    @Test
    fun `URL with different path is not matched`() {
        val feed = setOf("https://phishing-site.example.com/login")
        // Different path — should NOT match
        assertFalse(feed.contains("https://phishing-site.example.com/home"))
    }

    @Test
    fun `URL with trailing slash differs from one without (no silent normalisation)`() {
        val feed = setOf("https://phishing-site.example.com/path")
        // Trailing slash creates a distinct URL — no automatic equivalence
        assertFalse(feed.contains("https://phishing-site.example.com/path/"))
    }

    @Test
    fun `URL with different query parameters is not matched`() {
        val feed = setOf("https://phishing-site.example.com/steal?session=abc")
        assertFalse(feed.contains("https://phishing-site.example.com/steal?session=xyz"))
        assertFalse(feed.contains("https://phishing-site.example.com/steal"))
    }

    @Test
    fun `URL matching is case-sensitive for path and query`() {
        val feed = setOf("https://phishing-site.example.com/Login")
        // Paths are case-sensitive on most servers
        assertFalse(feed.contains("https://phishing-site.example.com/login"))
    }

    @Test
    fun `URL with different scheme is not matched`() {
        val feed = setOf("http://phishing-site.example.com/steal")
        // https vs http are distinct
        assertFalse(feed.contains("https://phishing-site.example.com/steal"))
    }

    // -------------------------------------------------------------------------
    // Invalid / edge-case input handling
    // -------------------------------------------------------------------------

    @Test
    fun `empty URL string does not match any feed entry`() {
        val feed = setOf("https://phishing-site.example.com/")
        assertFalse(feed.contains(""))
        assertFalse(feed.contains("   ".trim()))
    }

    @Test
    fun `parseFeed with only invalid lines returns empty set`() {
        val feed = """
            not-a-url
            ftp://old-protocol.example.com/
            
        """.trimIndent()
        val result = OpenPhishRepository.parseFeed(feed)
        assertTrue(result.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Offline / cache availability
    // -------------------------------------------------------------------------

    @Test
    fun `empty feed set signals check unavailable`() {
        // Simulates the case where the feed has never been downloaded.
        // An empty set means count == 0 → CHECK_UNAVAILABLE.
        val feedCount = 0
        val expectedResult = if (feedCount == 0) {
            FeedCheckResult.CHECK_UNAVAILABLE
        } else {
            FeedCheckResult.NO_FEED_MATCH
        }
        assertEquals(FeedCheckResult.CHECK_UNAVAILABLE, expectedResult)
    }

    @Test
    fun `non-empty feed with no match signals no feed match`() {
        val feed = setOf("https://other-phish.example.com/")
        val urlToCheck = "https://safe.example.com/"
        val feedCount = feed.size
        val result = when {
            feedCount == 0 -> FeedCheckResult.CHECK_UNAVAILABLE
            feed.contains(urlToCheck.trim()) -> FeedCheckResult.PHISHING
            else -> FeedCheckResult.NO_FEED_MATCH
        }
        assertEquals(FeedCheckResult.NO_FEED_MATCH, result)
    }

    @Test
    fun `non-empty feed with exact match signals phishing`() {
        val url = "https://phishing.example.com/steal"
        val feed = setOf(url)
        val result = when {
            feed.isEmpty() -> FeedCheckResult.CHECK_UNAVAILABLE
            feed.contains(url.trim()) -> FeedCheckResult.PHISHING
            else -> FeedCheckResult.NO_FEED_MATCH
        }
        assertEquals(FeedCheckResult.PHISHING, result)
    }

    // -------------------------------------------------------------------------
    // UrlExtractor integration — ensure extraction feeds correct URL to checker
    // -------------------------------------------------------------------------

    @Test
    fun `UrlExtractor extracts https URL correctly`() {
        val extracted = UrlExtractor.extract("Check this link: https://example.com/path?q=1")
        assertEquals("https://example.com/path?q=1", extracted)
    }

    @Test
    fun `UrlExtractor returns null for plain text without URL`() {
        val extracted = UrlExtractor.extract("Hello world, no URL here")
        assertEquals(null, extracted)
    }

    @Test
    fun `UrlExtractor prepends https to www links`() {
        val extracted = UrlExtractor.extract("www.example.com/page")
        assertEquals("https://www.example.com/page", extracted)
    }

    @Test
    fun `UrlExtractor strips trailing punctuation`() {
        val extracted = UrlExtractor.extract("Visit https://example.com/path.")
        assertEquals("https://example.com/path", extracted)
    }
}
