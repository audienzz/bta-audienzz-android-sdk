# BTA Audienzz Android SDK

Below The Article (BTA) feed SDK for Android. Embeds the Audienzz BTA recommendation and ad feed in any Android screen — available as a standard `View` or a Jetpack Compose composable.

---

## Requirements

- Android API 24+
- `androidx.appcompat:appcompat`

---

## Installation

Add the SDK module to your project (local module or via your distribution mechanism):

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":sdk"))

    // Optional — Compose wrapper
    implementation(project(":sdk-compose"))
}
```

Add the `INTERNET` permission to your `AndroidManifest.xml` if not already present:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

---

## Setup

### 1. Initialize the SDK

Call `BtaSdk.init()` once, before any `BtaFeedView` is created — typically in your `Application.onCreate()`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BtaSdk.init(context = this, publisherId = "your-publisher-id")
    }
}
```

Register your `Application` class in `AndroidManifest.xml`:

```xml
<application android:name=".MyApp" ...>
```

---

## Usage — View (XML / traditional)

### 2. Add the view to your layout

```xml
<org.audienzz.bta.sdk.BtaFeedView
    android:id="@+id/btaFeedView"
    android:layout_width="match_parent"
    android:layout_height="wrap_content" />
```

Place it at the bottom of your article layout. The view auto-sizes to fit its content.

### 3. Bind, load, and manage the lifecycle

```kotlin
class ArticleActivity : AppCompatActivity() {

    private lateinit var btaFeedView: BtaFeedView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_article)

        btaFeedView = findViewById(R.id.btaFeedView)

        btaFeedView.setListener(object : BtaFeedListener {

            // Return false  → SDK opens the URL in a fullscreen in-app browser.
            // Return true   → you handle navigation yourself; SDK opens nothing.
            override fun onArticleClick(payload: ArticleClickPayload): Boolean {
                return false
            }

            override fun onAdClick(payload: AdClickPayload): Boolean {
                return false
            }

            override fun onFeedLoaded() {
                // Feed widget initialised (recommendations may still be loading).
            }

            override fun onFeedError(error: String) {
                Log.e("BtaFeed", "Feed error: $error")
            }
        })
    }

    override fun onResume() {
        super.onResume()
        btaFeedView.onResume()
        btaFeedView.load(btaFeedId = "your-bta-feed-id")
    }

    override fun onPause() {
        super.onPause()
        btaFeedView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        btaFeedView.destroy()
    }
}
```

---

## Usage — Jetpack Compose

No lifecycle wiring required — the composable handles it automatically.

```kotlin
@Composable
fun ArticleScreen() {
    Column(modifier = Modifier.fillMaxSize()) {

        // Your article content here...

        BtaFeed(
            btaFeedId = "your-bta-feed-id",
            modifier = Modifier.fillMaxWidth(),
            onArticleClick = { payload ->
                false  // false → SDK opens fullscreen browser
            },
            onAdClick = { payload ->
                false
            },
            onFeedLoaded = {
                // Feed initialised
            },
            onFeedError = { error ->
                Log.e("BtaFeed", "Error: $error")
            },
        )
    }
}
```

---

## Click handling

By default, clicking an article or ad opens the destination URL in a fullscreen in-app browser (`BtaWebViewActivity`) with a close button (✕) in the top-left corner. No browser app is opened.

To handle navigation yourself, return `true` from the click callback:

```kotlin
override fun onArticleClick(payload: ArticleClickPayload): Boolean {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(payload.url)))
    return true  // SDK won't open anything
}
```

### Click payload fields

**`ArticleClickPayload`**

| Field | Type | Description |
|-------|------|-------------|
| `btaFeedId` | `String` | Feed ID that triggered the click |
| `index` | `Int` | Zero-based position of the item in the feed |
| `url` | `String` | Destination URL |
| `title` | `String` | Article title |
| `article` | `JSONObject` | Full raw article object from the feed |

**`AdClickPayload`**

| Field | Type | Description |
|-------|------|-------------|
| `btaFeedId` | `String` | Feed ID that triggered the click |
| `index` | `Int` | Zero-based position of the item in the feed |
| `url` | `String` | Destination URL |
| `adUnit` | `JSONObject` | Full raw ad unit object from the feed |

---

## BtaFeedView API reference

| Method | Description |
|--------|-------------|
| `setListener(listener)` | Set or replace the event listener |
| `load(btaFeedId, debug, mockRecommendations)` | Load the feed. Call from `onResume()` |
| `onResume()` | Resume the WebView. Call from `Activity.onResume()` |
| `onPause()` | Pause the WebView. Call from `Activity.onPause()` |
| `destroy()` | Release resources. Call from `Activity.onDestroy()` |

---

## BtaFeed Compose API reference

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `btaFeedId` | `String` | — | Required. Feed identifier |
| `modifier` | `Modifier` | `Modifier` | Standard Compose modifier |
| `debug` | `Boolean` | `false` | Enable feed debug logging. **Do not use in production** |
| `mockRecommendations` | `Boolean` | `false` | Show mock content. **Do not use in production** |
| `onArticleClick` | `(ArticleClickPayload) -> Boolean` | `null` | `null` behaves as returning `false` |
| `onAdClick` | `(AdClickPayload) -> Boolean` | `null` | `null` behaves as returning `false` |
| `onFeedLoaded` | `() -> Unit` | `null` | Called when the feed initialises |
| `onFeedError` | `(String) -> Unit` | `null` | Called on feed initialisation error |

---

## Analytics

The SDK automatically tracks the following events and sends them to the Audienzz analytics pipeline. No integration work is required.

| Event | Trigger |
|-------|---------|
| `btafeed.pageview` | Every `load()` call |
| `btafeed.viewable_impression` | Feed is ≥50% visible on screen |
| `btafeed.article_impression` | Article unit enters the viewport |
| `btafeed.article_click` | Article clicked |
| `btafeed.ad_impression` | Ad unit enters the viewport |
| `btafeed.ad_click` | Ad clicked |

Events are batched and sent in [CloudEvents 1.0](https://cloudevents.io) format. A stable visitor ID is persisted across sessions; a new session ID is generated per app launch.

---

## Debugging

Enable WebView remote debugging to inspect the feed in Chrome DevTools (`chrome://inspect`):

```kotlin
if (BuildConfig.DEBUG) {
    WebView.setWebContentsDebuggingEnabled(true)
}
```

Pass `debug = true` and `mockRecommendations = true` to `load()` / `BtaFeed` to show mock content without a live feed configuration.
