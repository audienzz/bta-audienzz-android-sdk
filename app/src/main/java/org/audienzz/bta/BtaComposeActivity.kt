package org.audienzz.bta

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
        internal const val BTA_FEED_ID = "5b0a5694-de8d-4ddb-8bd4-a871bb55f09b"

        /** Replace with the canonical URL of the article page hosting this feed. */
        internal const val PAGE_URL = "https://www.example.com/article/sample-article"
    }
}

@Composable
private fun BtaComposeScreen() {
    // Change this token to refresh the feed in place (no height jump). The FAB below
    // increments it, mirroring `btaFeedView.reload()` on the View-based API.
    var reloadToken by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
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

            BtaFeed(
                btaFeedId = BtaComposeActivity.BTA_FEED_ID,
                pageUrl = BtaComposeActivity.PAGE_URL,
                modifier = Modifier.fillMaxWidth(),
                reloadToken = reloadToken,
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

        // Floating button: refresh recommendations in place via reloadToken.
        FloatingActionButton(
            onClick = { reloadToken++ },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Text(text = "↻", style = MaterialTheme.typography.headlineSmall)
        }
    }
}
