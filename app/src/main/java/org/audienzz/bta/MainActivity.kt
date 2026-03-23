package org.audienzz.bta

import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import org.audienzz.bta.sdk.BtaFeedListener
import org.audienzz.bta.sdk.BtaFeedView
import org.audienzz.bta.sdk.BtaSdk
import org.audienzz.bta.sdk.model.AdClickPayload
import org.audienzz.bta.sdk.model.ArticleClickPayload

class MainActivity : AppCompatActivity() {

    private lateinit var btaFeedView: BtaFeedView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Enable WebView remote debugging (chrome://inspect) for debug builds.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        // Initialise the BTA SDK once per app session.
        // In a real app, move this to Application.onCreate().
        BtaSdk.init(context = this, publisherId = PUBLISHER_ID)

        btaFeedView = findViewById(R.id.btaFeedView)

        // Set the listener once. All callbacks are delivered on the main thread.
        btaFeedView.setListener(object : BtaFeedListener {

            override fun onArticleClick(payload: ArticleClickPayload): Boolean {
                Log.d(TAG, "Article clicked: index=${payload.index} url=${payload.url}")
                return false // Let SDK open in fullscreen WebView
            }

            override fun onAdClick(payload: AdClickPayload): Boolean {
                Log.d(TAG, "Ad clicked: index=${payload.index} url=${payload.url}")
                // Return false to let the SDK open the ad in a fullscreen WebView.
                // Return true here if you want to handle navigation yourself.
                return false
            }

            override fun onFeedLoaded() {
                Log.d(TAG, "BTA feed initialised successfully")
            }

            override fun onFeedError(error: String) {
                Log.e(TAG, "BTA feed error: $error")
            }
        })
    }

    override fun onResume() {
        super.onResume()
        btaFeedView.onResume()

        // Load (or reload) the feed every time the screen is entered.
        // Remove debug = true and mockRecommendations = true in production!
        btaFeedView.load(
            btaFeedId = BTA_FEED_ID,
            debug = true,
            mockRecommendations = true,
        )
    }

    override fun onPause() {
        super.onPause()
        btaFeedView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        btaFeedView.destroy()
    }

    companion object {
        private const val TAG = "BtaExample"

        /** Replace with your real BTA feed ID. */
        private const val BTA_FEED_ID = "92692c82-cb38-4164-b77c-e89d56cb486d"

        /** Replace with your real publisher ID provided by Audienzz. */
        private const val PUBLISHER_ID = "example-publisher-id"
    }
}
