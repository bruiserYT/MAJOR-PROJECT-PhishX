package com.example.myapplication

object UrlExtractor {
    private val urlPattern = Regex(
        """(?i)(?:https?://|www\.)[^\s<>"']+"""
    )

    fun extract(text: String): String? {
        val match = urlPattern.find(text)?.value ?: return null
        var url = match.trimEnd('.', ',', '!', '?', ':', ';', ')', ']', '}')

        if (url.startsWith("www.", ignoreCase = true)) {
            url = "https://$url"
        }

        return url
    }
}