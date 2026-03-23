package org.audienzz.bta

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.audienzz.bta.compose.BtaFeed

/**
 * Example Activity demonstrating the Compose-based BTA feed.
 *
 * In a real app the BtaFeed composable can be dropped into any Composable
 * function — no Activity subclass required.
 */
class BtaComposeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                BtaComposeScreen()
            }
        }
    }

    companion object {
        private const val TAG = "BtaComposeExample"

        /** Same feed ID as the View-based example. Replace with your real ID. */
        internal const val BTA_FEED_ID = "92692c82-cb38-4164-b77c-e89d56cb486d"
    }
}

@Composable
private fun BtaComposeScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Placeholder for the publisher's article body.
        Text(
            text = "This is a sample article.\n\n" +
                "The BTA (Below The Article) feed renders below once the " +
                "SDK has loaded the widget.",
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )

        // Drop BtaFeed anywhere in a Composable — lifecycle is handled automatically.
        // Remove debug = true and mockRecommendations = true in production!
        BtaFeed(
            btaFeedId = BtaComposeActivity.BTA_FEED_ID,
            modifier = Modifier.fillMaxWidth(),
            debug = true,
            mockRecommendations = true,
            onArticleClick = { payload ->
                Log.d("BtaComposeExample", "Article clicked: index=${payload.index} url=${payload.url}")
                false // Let SDK open in fullscreen WebView
            },
            onAdClick = { payload ->
                Log.d("BtaComposeExample", "Ad clicked: index=${payload.index} url=${payload.url}")
                // Return false → SDK opens the ad in a fullscreen WebView.
                // Return true if you want to handle the URL yourself.
                false
            },
            onFeedLoaded = {
                Log.d("BtaComposeExample", "BTA feed initialised successfully (Compose)")
            },
            onFeedError = { error ->
                Log.e("BtaComposeExample", "BTA feed error (Compose): $error")
            },
        )
    }
}
