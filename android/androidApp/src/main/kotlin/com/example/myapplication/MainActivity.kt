package com.example.myapplication

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val backgroundColor = Color.rgb(10, 11, 10)
    private val panelColor = Color.rgb(25, 27, 24)
    private val limeColor = Color.rgb(229, 255, 143) // #E5FF8F
    private val softLimeColor = Color.rgb(237, 255, 177) // #EDFFB1
    private val whiteColor = Color.rgb(248, 250, 246)
    private val mutedColor = Color.rgb(164, 170, 160)
    private val dangerColor = Color.rgb(255, 105, 105)

    private var checkedCount = 0
    private var phishingCount = 0
    private var safeCount = 0
    private var reviewCount = 0

    private lateinit var checkedValue: TextView
    private lateinit var phishingValue: TextView
    private lateinit var safeValue: TextView
    private lateinit var reviewValue: TextView
    private lateinit var riskBar: ProgressBar
    private lateinit var riskSummary: TextView
    private lateinit var resultText: TextView
    private lateinit var checkButton: Button
    private lateinit var openPhishResultText: TextView
    private lateinit var feedTimestampText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        CacheCleanupWorker.schedule(this)
        OpenPhishWorker.schedule(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }

        content.addView(createHeader())
        content.addView(createGreeting(), topMargin = 24)
        content.addView(createStatsSection(), topMargin = 22)
        content.addView(createChecker(), topMargin = 24)
        content.addView(createBreakdown(), topMargin = 22)

        // createBreakdown() initializes riskBar and riskSummary.
        updateStats()

        content.addView(createPrivacyNote(), topMargin = 18)

        val scrollView = ScrollView(this).apply {
            setFillViewport(true)
            setBackgroundColor(backgroundColor)
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        setContentView(scrollView)
    }

    private fun createHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.phishx_wordmark)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "PhishX"
        }

        header.addView(
            logo,
            LinearLayout.LayoutParams(dp(190), dp(52))
        )

        val sessionLabel = TextView(this).apply {
            text = "LIVE CHECK"
            textSize = 11f
            setTextColor(limeColor)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = roundedBackground(panelColor, limeColor, 18)
        }

        header.addView(
            sessionLabel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                leftMargin = dp(8)
            }
        )

        return header
    }

    private fun createGreeting(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        section.addView(
            text(
                "Your link security",
                size = 28f,
                color = whiteColor,
                bold = true
            )
        )

        section.addView(
            text(
                "Check a link before you open it.",
                size = 15f,
                color = mutedColor
            ),
            topMargin = 6
        )

        return section
    }

    private fun createStatsSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        section.addView(
            text("SESSION OVERVIEW", 12f, mutedColor, bold = true)
        )

        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        checkedValue = TextView(this)
        phishingValue = TextView(this)

        firstRow.addView(
            metricCard("Links checked", checkedValue, limeColor),
            equalWeightParams()
        )
        firstRow.addView(
            metricCard("Phishing alerts", phishingValue, dangerColor),
            equalWeightParams(left = 10)
        )

        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        safeValue = TextView(this)
        reviewValue = TextView(this)

        secondRow.addView(
            metricCard("Safe results", safeValue, softLimeColor),
            equalWeightParams()
        )
        secondRow.addView(
            metricCard("Needs review", reviewValue, whiteColor),
            equalWeightParams(left = 10)
        )

        section.addView(firstRow, topMargin = 10)
        section.addView(secondRow, topMargin = 10)

        // Do not call updateStats() here: riskBar and riskSummary
        // are initialized later by createBreakdown().
        return section
    }

    private fun metricCard(
        title: String,
        valueView: TextView,
        valueColor: Int
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(14), dp(14))
            background = roundedBackground(panelColor, panelColor, 18)
        }

        card.addView(text(title, 13f, mutedColor))

        valueView.apply {
            text = "0"
            textSize = 27f
            setTextColor(valueColor)
            typeface = Typeface.DEFAULT_BOLD
        }

        card.addView(valueView, topMargin = 10)
        return card
    }

    private fun createChecker(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBackground(panelColor, panelColor, 18)
        }

        section.addView(
            text("CHECK A LINK", 12f, limeColor, bold = true)
        )

        val input = EditText(this).apply {
            hint = "Paste a link or message"
            setHintTextColor(mutedColor)
            setTextColor(whiteColor)
            textSize = 16f
            minLines = 1
            maxLines = 3
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedBackground(backgroundColor, mutedColor, 12)

            intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
                ?.let { setText(it) }
        }

        section.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )

        checkButton = Button(this).apply {
            text = "CHECK LINK"
            textSize = 14f
            setTextColor(backgroundColor)
            typeface = Typeface.DEFAULT_BOLD
            background = roundedBackground(limeColor, limeColor, 12)
            backgroundTintList = null
            isAllCaps = false
        }

        section.addView(
            checkButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                topMargin = dp(12)
            }
        )

        // --- ML model result (existing) ---
        resultText = text(
            "Your result will appear here.",
            size = 14f,
            color = mutedColor
        )
        section.addView(resultText, topMargin = 12)

        // --- OpenPhish feed result ---
        section.addView(
            text("THREAT FEED (OpenPhish)", 11f, mutedColor, bold = true),
            topMargin = 16
        )

        openPhishResultText = text(
            "Feed check will appear here.",
            size = 14f,
            color = mutedColor
        )
        section.addView(openPhishResultText, topMargin = 4)

        feedTimestampText = text(
            "Feed not yet loaded.",
            size = 12f,
            color = mutedColor
        )
        section.addView(feedTimestampText, topMargin = 4)

        // Manual refresh button
        val refreshButton = Button(this).apply {
            text = "Refresh Feed"
            textSize = 12f
            setTextColor(limeColor)
            background = roundedBackground(backgroundColor, limeColor, 10)
            backgroundTintList = null
            isAllCaps = false
        }

        section.addView(
            refreshButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
            ).apply {
                topMargin = dp(8)
            }
        )

        refreshButton.setOnClickListener {
            refreshButton.isEnabled = false
            refreshButton.text = "Refreshing…"
            lifecycleScope.launch {
                val ok = OpenPhishRepository.refreshFeed(this@MainActivity)
                updateFeedTimestamp()
                refreshButton.isEnabled = true
                refreshButton.text = "Refresh Feed"
                if (!ok) {
                    feedTimestampText.text = "Feed refresh failed. Check your connection."
                    feedTimestampText.setTextColor(dangerColor)
                }
            }
        }

        // Initialise timestamp label from whatever is in DB
        lifecycleScope.launch { updateFeedTimestamp() }

        checkButton.setOnClickListener {
            val url = UrlExtractor.extract(input.text.toString())

            if (url == null) {
                showResult("No link found. Paste a URL and try again.", mutedColor)
                return@setOnClickListener
            }

            checkButton.isEnabled = false
            checkButton.text = "CHECKING…"
            showResult("Checking link…", mutedColor)
            showOpenPhishResult(FeedCheckResult.CHECK_UNAVAILABLE, cached = false)

            lifecycleScope.launch {
                // --- Run ML check and OpenPhish check concurrently ---
                val openPhishDeferred = async {
                    OpenPhishRepository.check(this@MainActivity, url)
                }

                try {
                    val dao = PhishXDatabase.get(this@MainActivity).urlVerdicts()
                    val urlHash = hashUrl(url)
                    val validAfter = System.currentTimeMillis() -
                            TimeUnit.HOURS.toMillis(24)

                    val cached = dao.findFresh(urlHash, validAfter)

                    val verdict: String
                    val confidence: Double
                    val fromCache: Boolean

                    if (cached != null) {
                        verdict = cached.verdict
                        confidence = cached.confidence
                        fromCache = true
                    } else {
                        val response = Api.service.predict(PredictRequest(url))
                        verdict = response.verdict
                        confidence = response.confidence
                        fromCache = false

                        dao.save(
                            CachedVerdict(
                                urlHash = urlHash,
                                verdict = verdict,
                                confidence = confidence,
                                checkedAt = System.currentTimeMillis()
                            )
                        )
                    }

                    checkedCount++

                    when {
                        verdict.equals("phishing", ignoreCase = true) ->
                            phishingCount++

                        verdict.equals("safe", ignoreCase = true) ->
                            safeCount++

                        else ->
                            reviewCount++
                    }

                    updateStats()

                    val confidencePercent = (confidence * 100).toInt()
                    val cacheLabel = if (fromCache) " (cached)" else ""
                    val message = when {
                        verdict.equals("phishing", ignoreCase = true) ->
                            "⚠ PHISHING RISK$cacheLabel\nConfidence: $confidencePercent%\n\nDo not enter passwords or payment details."

                        verdict.equals("safe", ignoreCase = true) ->
                            "✓ No phishing detected$cacheLabel\nConfidence: $confidencePercent%"

                        else ->
                            "Review this link carefully$cacheLabel\nResult: $verdict\nConfidence: $confidencePercent%"
                    }

                    val resultColor = if (
                        verdict.equals("phishing", ignoreCase = true)
                    ) {
                        dangerColor
                    } else {
                        limeColor
                    }

                    showResult(message, resultColor)
                } catch (e: Exception) {
                    showResult(
                        "Couldn't verify this link. Check that the backend is running.\n\n${e.javaClass.simpleName}: ${e.message.orEmpty()}",
                        mutedColor
                    )
                } finally {
                    // Display OpenPhish result regardless of ML outcome
                    val feedResult = try {
                        openPhishDeferred.await()
                    } catch (_: Exception) {
                        FeedCheckResult.CHECK_UNAVAILABLE
                    }
                    showOpenPhishResult(feedResult, cached = true)
                    updateFeedTimestamp()

                    checkButton.isEnabled = true
                    checkButton.text = "CHECK LINK"
                }
            }
        }

        return section
    }

    /** Updates [openPhishResultText] to reflect a [FeedCheckResult]. */
    private fun showOpenPhishResult(result: FeedCheckResult, cached: Boolean) {
        val cacheNote = if (cached) " (cached data)" else ""
        val (label, color) = when (result) {
            FeedCheckResult.PHISHING ->
                "🛑 Known Phishing URL" to dangerColor

            FeedCheckResult.NO_FEED_MATCH ->
                "✓ No Feed Match$cacheNote" to limeColor

            FeedCheckResult.CHECK_UNAVAILABLE ->
                "⚠ Check Unavailable — feed not loaded yet" to mutedColor
        }
        openPhishResultText.text = label
        openPhishResultText.setTextColor(color)
    }

    /** Reads the last-updated timestamp from DB and updates [feedTimestampText]. */
    private suspend fun updateFeedTimestamp() {
        val ts = OpenPhishRepository.lastUpdatedAt(this)
        if (ts == null) {
            feedTimestampText.text = "Feed not yet downloaded."
            feedTimestampText.setTextColor(mutedColor)
        } else {
            val formatted = java.text.SimpleDateFormat(
                "dd MMM yyyy, HH:mm", java.util.Locale.getDefault()
            ).format(java.util.Date(ts))
            feedTimestampText.text = "Feed last updated: $formatted"
            feedTimestampText.setTextColor(mutedColor)
        }
    }


    private fun createBreakdown(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = roundedBackground(panelColor, panelColor, 18)
        }

        section.addView(
            text("SESSION RISK BREAKDOWN", 12f, mutedColor, bold = true)
        )

        riskSummary = text(
            "No completed checks yet.",
            size = 14f,
            color = whiteColor
        )
        section.addView(riskSummary, topMargin = 12)

        riskBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(limeColor)
            progressBackgroundTintList = ColorStateList.valueOf(backgroundColor)
        }

        section.addView(
            riskBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(12)
            ).apply {
                topMargin = dp(10)
            }
        )

        return section
    }

    private fun createPrivacyNote(): View {
        return text(
            "Privacy cache: PhishX stores a SHA-256 URL hash, verdict, confidence, and check time—not the original URL.",
            size = 12f,
            color = mutedColor
        )
    }

    private fun updateStats() {
        if (!::checkedValue.isInitialized) return

        checkedValue.text = checkedCount.toString()
        phishingValue.text = phishingCount.toString()
        safeValue.text = safeCount.toString()
        reviewValue.text = reviewCount.toString()

        val phishingPercent = if (checkedCount == 0) {
            0
        } else {
            phishingCount * 100 / checkedCount
        }

        riskBar.progress = phishingPercent
        riskSummary.text = if (checkedCount == 0) {
            "No completed checks yet."
        } else {
            "$phishingPercent% of this session’s results were classified as phishing."
        }
    }

    private fun showResult(message: String, color: Int) {
        resultText.text = message
        resultText.setTextColor(color)
    }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun roundedBackground(
        fillColor: Int,
        strokeColor: Int,
        radiusDp: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), strokeColor)
        }
    }

    private fun equalWeightParams(left: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            if (left > 0) leftMargin = dp(left)
        }
    }

    private fun LinearLayout.addView(view: View, topMargin: Int) {
        addView(
            view,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                this.topMargin = dp(topMargin)
            }
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}