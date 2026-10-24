package com.example.myapplication

object UrlExtractor {
    private val explicitUrlPattern = Regex(
        """(?i)(?:https?://|www\.)[^\s<>"']+"""
    )

    private val domainPattern = Regex(
        """(?i)\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+(?:com|org|net|edu|gov|io|co|xyz|top|info|biz|me|online|site|app|dev|ru|cn|in|de|jp|uk|br|au|ca|[a-z]{2,})(?:/[^\s<>"']*)?"""
    )

    fun extract(text: String): String? {
        val explicitMatch = explicitUrlPattern.find(text)?.value
        var rawUrl = explicitMatch

        if (rawUrl == null) {
            val domainMatch = domainPattern.find(text)?.value
            if (domainMatch != null) {
                rawUrl = domainMatch
            }
        }

        if (rawUrl == null) return null

        var url = rawUrl.trimEnd('.', ',', '!', '?', ':', ';', ')', ']', '}')

        if (url.startsWith("www.", ignoreCase = true)) {
            url = "https://$url"
        } else if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "https://$url"
        }

        return url
    }
}
