package org.audienzz.bta.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.audienzz.bta.sdk.BtaFeedListener
import org.audienzz.bta.sdk.BtaFeedView
import org.audienzz.bta.sdk.model.AdClickPayload
import org.audienzz.bta.sdk.model.ArticleClickPayload

/**
 * Jetpack Compose wrapper for the BTA (Below The Article) feed.
 *
 * Internally hosts a [BtaFeedView] via [AndroidView] and wires it to the
 * host Activity/Fragment lifecycle automatically via [DisposableEffect].
 *
 * ## Basic usage
 *
 * ```kotlin
 * // Application.onCreate() — initialise SDK once, same as View-based usage
 * BtaSdk.init(context = this, publisherId = "your-publisher-id")
 *
 * // Inside any Composable
 * BtaFeed(
 *     btaFeedId = "your-bta-feed-id",
 *     modifier = Modifier.fillMaxWidth(),
 *     onArticleClick = { payload -> false }, // false → SDK opens fullscreen WebView
 *     onAdClick = { payload -> false }, // false → SDK opens fullscreen WebView
 * )
 * ```
 *
 * @param btaFeedId           The feed identifier provided by Audienzz.
 * @param modifier            Applied to the underlying [AndroidView].
 * @param debug               Enable feed debug logging (**do not use in production**).
 * @param mockRecommendations Show mock recommendations instead of real ones
 *                            (**do not use in production**).
 * @param onArticleClick      Called when the user taps an article. Return `true` to handle
 *                            navigation yourself; return `false` (default) to let the SDK
 *                            open the URL in a fullscreen WebView.
 * @param onAdClick           Called when the user taps an ad. Return `true` to handle
 *                            navigation yourself; return `false` (default) to let the
 *                            SDK open the URL in a fullscreen WebView.
 * @param onFeedLoaded        Called once the feed widget has initialised successfully.
 * @param onFeedError         Called when the feed encounters an error.
 */
@Composable
fun BtaFeed(
    btaFeedId: String,
    modifier: Modifier = Modifier,
    debug: Boolean = false,
    mockRecommendations: Boolean = false,
    onArticleClick: ((ArticleClickPayload) -> Boolean)? = null,
    onAdClick: ((AdClickPayload) -> Boolean)? = null,
    onFeedLoaded: (() -> Unit)? = null,
    onFeedError: ((String) -> Unit)? = null,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // Wrap every callback parameter in rememberUpdatedState so that the
    // LifecycleEventObserver closure always reads the *latest* lambda without
    // needing to be re-registered every recomposition.
    val currentBtaFeedId by rememberUpdatedState(btaFeedId)
    val currentDebug by rememberUpdatedState(debug)
    val currentMockRecs by rememberUpdatedState(mockRecommendations)
    val currentOnArticleClick by rememberUpdatedState(onArticleClick)
    val currentOnAdClick by rememberUpdatedState(onAdClick)
    val currentOnFeedLoaded by rememberUpdatedState(onFeedLoaded)
    val currentOnFeedError by rememberUpdatedState(onFeedError)

    // Stable reference to the view so the DisposableEffect closure can access it
    // after AndroidView's factory has run (they execute on different frames).
    val viewRef = remember { mutableStateOf<BtaFeedView?>(null) }

    AndroidView(
        factory = { ctx ->
            BtaFeedView(ctx).also { viewRef.value = it }
        },
        modifier = modifier,
    )

    // LifecycleRegistry replays events to newly added observers, so if the
    // composable enters composition on an already-RESUMED screen, ON_RESUME
    // fires immediately and load() is triggered automatically.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val view = viewRef.value ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    view.setListener(object : BtaFeedListener {
                        override fun onArticleClick(payload: ArticleClickPayload): Boolean =
                            currentOnArticleClick?.invoke(payload) ?: false

                        override fun onAdClick(payload: AdClickPayload): Boolean =
                            currentOnAdClick?.invoke(payload) ?: false

                        override fun onFeedLoaded() {
                            currentOnFeedLoaded?.invoke()
                        }

                        override fun onFeedError(error: String) {
                            currentOnFeedError?.invoke(error)
                        }
                    })
                    view.onResume()
                    view.load(currentBtaFeedId, currentDebug, currentMockRecs)
                }
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewRef.value?.destroy()
        }
    }
}
