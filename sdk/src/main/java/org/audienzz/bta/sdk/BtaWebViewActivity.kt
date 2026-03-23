package org.audienzz.bta.sdk

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

/**
 * Fullscreen WebView Activity that opens when an ad or article is clicked and the publisher's
 * [BtaFeedListener] returns `false` (or no listener is set).
 *
 * A close button (✕) in the top-left corner dismisses the Activity.
 * Back navigation: navigates back in WebView history; if no history remains, closes the Activity.
 */
class BtaWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        // Toolbar with close button in the top-left corner.
        val toolbar = Toolbar(this).apply {
            setNavigationIcon(R.drawable.bta_ic_close)
            setNavigationContentDescription("Close")
        }
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        webView = WebView(this).also { wv ->
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }

            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = false // Allow all navigation inside the fullscreen WebView

                override fun onPageFinished(view: WebView, url: String) {
                    // Mirror page title in the toolbar, like iOS does.
                    if (!view.title.isNullOrBlank()) {
                        supportActionBar?.title = view.title
                    }
                }
            }

            wv.webChromeClient = WebChromeClient()
        }

        // Handle back press: go back in WebView history or finish the Activity.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(webView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
        }

        setContentView(root)
        webView.loadUrl(url)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.stopLoading()
        webView.destroy()
    }

    companion object {
        private const val EXTRA_URL = "org.audienzz.bta.sdk.EXTRA_URL"

        internal fun createIntent(context: Context, url: String): Intent =
            Intent(context, BtaWebViewActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
            }
    }
}
