package com.example.myapplication

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class InterceptActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.data ?: run {
            finish()
            return
        }

        val url = uri.toString()
        val message = TextView(this).apply {
            textSize = 18f
            setPadding(40, 60, 40, 40)
            text = "Checking link security…\n\n$url"
        }
        setContentView(message)

        lifecycleScope.launch {
            try {
                // Check local OpenPhish database first (available offline)
                val feedCheckDeferred = async {
                    OpenPhishRepository.check(this@InterceptActivity, url)
                }

                // Call backend API classifier
                val predictDeferred = async {
                    try {
                        Api.service.predict(PredictRequest(url))
                    } catch (_: Exception) {
                        null
                    }
                }

                val feedResult = feedCheckDeferred.await()
                val apiResult = predictDeferred.await()

                val isPhishing = feedResult == FeedCheckResult.PHISHING ||
                        apiResult?.verdict.equals("phishing", ignoreCase = true)

                if (isPhishing) {
                    val detail = if (feedResult == FeedCheckResult.PHISHING) {
                        "⚠ Known Phishing URL detected in threat database."
                    } else {
                        "⚠ ${apiResult?.reason ?: "Suspicious phishing patterns detected."}"
                    }
                    showWarning(uri, detail)
                } else if (apiResult?.verdict.equals("safe", ignoreCase = true)) {
                    openBrowser(uri)
                } else {
                    // Ambiguous or offline with no feed match: prompt user
                    showWarning(uri, "This link could not be verified with high confidence.")
                }
            } catch (_: Exception) {
                showWarning(uri, "Couldn't verify this link security status.")
            }
        }
    }

    private fun showWarning(uri: Uri, warning: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }
        layout.addView(TextView(this).apply {
            textSize = 18f
            text = "$warning\n\nDestination:\n$uri"
        })
        layout.addView(Button(this).apply {
            text = "Go back (Safe)"
            setOnClickListener { finish() }
        })
        layout.addView(Button(this).apply {
            text = "Proceed anyway (Risky)"
            setOnClickListener { openBrowser(uri) }
        })
        setContentView(layout)
    }

    private fun openBrowser(uri: Uri) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri)
                    .setPackage("com.android.chrome")
            )
            finish()
        } catch (_: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                finish()
            } catch (_: Exception) {
                showWarning(uri, "No web browser is installed to open this link.")
            }
        }
    }
}
