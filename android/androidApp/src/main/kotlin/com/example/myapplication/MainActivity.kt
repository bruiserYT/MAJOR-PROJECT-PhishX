package com.example.myapplication

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val input = EditText(this).apply {
            hint = "Paste a link or message"
            minLines = 3
            setText(intent.getStringExtra(android.content.Intent.EXTRA_TEXT).orEmpty())
        }
        val result = TextView(this).apply {
            text = "Paste a link, then tap Check."
            textSize = 18f
        }
        val checkButton = Button(this).apply { text = "Check" }

        checkButton.setOnClickListener {
            val url = UrlExtractor.extract(input.text.toString())
            if (url == null) {
                result.text = "No link found. Paste a URL and try again."
                return@setOnClickListener
            }

            checkButton.isEnabled = false
            result.text = "Checking…"

            lifecycleScope.launch {
                try {
                    val response = Api.service.predict(PredictRequest(url))
                    result.text =
                        if (response.verdict.equals("phishing", ignoreCase = true)) {
                            "⚠ Phishing risk (${response.confidence})\n$url"
                        } else {
                            "No phishing detected (${response.confidence})\n$url"
                        }
                } catch (e: Exception) {
            result.text = "Error: ${e.javaClass.simpleName}: ${e.message}"
                }finally {
                    checkButton.isEnabled = true
                }
            }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 48, 32, 32)
                addView(
                    input,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                addView(checkButton)
                addView(result)
            }
        )
    }
}