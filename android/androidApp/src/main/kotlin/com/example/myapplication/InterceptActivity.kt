package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class InterceptActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.data ?: run {
            finish()
            return
        }

        val message = TextView(this).apply {
            textSize = 20f
            text = "Checking link…\n$uri"
        }
        setContentView(message)

        lifecycleScope.launch {
            try {
                val verdict = Api.service.predict(PredictRequest(uri.toString()))
                if (verdict.verdict.equals("phishing", ignoreCase = true)) {
                    showWarning(uri, "⚠ This link may be phishing.")
                } else {
                    openChrome(uri)
                }
            } catch (_: Exception) {
                showWarning(uri, "Couldn't verify this link.")
            }
        }
    }

    private fun showWarning(uri: Uri, warning: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        layout.addView(TextView(this).apply {
            textSize = 20f
            text = "$warning\n\n$uri"
        })
        layout.addView(Button(this).apply {
            text = "Go back"
            setOnClickListener { finish() }
        })
        layout.addView(Button(this).apply {
            text = "Proceed anyway"
            setOnClickListener { openChrome(uri) }
        })
        setContentView(layout)
    }

    private fun openChrome(uri: Uri) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri)
                    .setPackage("com.android.chrome")
            )
            finish()
        } catch (_: Exception) {
            showWarning(uri, "Chrome isn't available to open this link.")
        }
    }
}