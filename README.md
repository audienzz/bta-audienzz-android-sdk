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
        BtaSdk.init(context = this, companyId = "your-publisher-id")
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
        btaFeedView.load(
            btaFeedId = "your-bta-feed-id",
            pageUrl = "https://your-site.com/the-article", // canonical URL of the hosting page
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
            pageUrl = "https://your-site.com/the-article",
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

## Refreshing content

If your app refreshes page content on navigation (including back navigation) and you want
fresh recommendations, call `reload()`. Unlike `load()`, it does **not** collapse the feed
height to 0 first — the current height is kept and adjusts smoothly once the new content is
measured, so there is no blank flash or layout jump.

```kotlin
btaFeedView.reload()   // replays the last load() parameters with fresh recommendations
```

> Note: the recommendation system currently serves fresh content on a **3-hour refresh
> interval**, so calling `reload()` more often than that may return the same items.

In Compose, change the `reloadToken` parameter to any new value to trigger the same in-place refresh:

```kotlin
var reloadToken by remember { mutableStateOf(0) }
BtaFeed(btaFeedId = "…", pageUrl = "…", reloadToken = reloadToken)
// later: reloadToken++   → refreshes in place
```

---

## Loading state

During the initial `load()`, the feed reserves a small height and shows a centered loading
spinner instead of appearing at zero height. Once content arrives it resizes to fit; if the
feed errors or returns nothing (or a timeout elapses), it collapses so there is no empty space.

To show **your own** placeholder instead, disable the built-in one with
`isLoadingHolderEnabled = false` and use `onFeedLoaded` / `onFeedError` to hide yours:

```kotlin
btaFeedView.load(
    btaFeedId = "your-bta-feed-id",
    pageUrl = "https://your-site.com/the-article",
    isLoadingHolderEnabled = false, // SDK stays at 0 height until content arrives
)
// show your own placeholder now; hide it in onFeedLoaded() / onFeedError()
```

In Compose, pass `isLoadingHolderEnabled = false` to `BtaFeed`.

---

## Orientation changes

The feed height recalculates **automatically** when the view's width changes (e.g. device
rotation) — you don't need to call `load()` or `reload()`. Avoid calling `reload()` on rotation:
it re-fetches recommendations and briefly holds the previous height, which can look wrong on
the new orientation.

---

## Custom fonts

The feed renders inside a Shadow DOM, and Android's WebView ignores `@font-face` rules declared
inside a shadow root — so custom fonts would otherwise fall back to the system font. The SDK
injects the font stylesheets at the **document level**, where they register globally and apply
inside the feed. The **standard AdConsole fonts load by default**, so you don't need to change
anything.

To use different fonts, pass your own `fontStyleUrls`; to disable injection, pass an empty list:

```kotlin
btaFeedView.load(
    btaFeedId = "your-bta-feed-id",
    pageUrl = "https://your-site.com/the-article",
    fontStyleUrls = listOf(
        "https://cdn.adnz.co/business-click-fonts/YourFont/stylesheet.css",
    ),
    // or fontStyleUrls = emptyList() to disable
)
```

In Compose, pass `fontStyleUrls = listOf(...)` (or `emptyList()`) to `BtaFeed`.

---

## BtaFeedView API reference

| Method | Description |
|--------|-------------|
| `setListener(listener)` | Set or replace the event listener |
| `load(btaFeedId, pageUrl, debug, mockRecommendations, isDarkMode, isLoadingHolderEnabled, fontStyleUrls)` | Load the feed. Call from `onResume()` |
| `reload()` | Refresh content in place without collapsing the height. Replays the last `load()` params |
| `onResume()` | Resume the WebView. Call from `Activity.onResume()` |
| `onPause()` | Pause the WebView. Call from `Activity.onPause()` |
| `destroy()` | Release resources. Call from `Activity.onDestroy()` |

---

## BtaFeed Compose API reference

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `btaFeedId` | `String` | — | Required. Feed identifier |
| `pageUrl` | `String` | — | Required. Canonical URL of the hosting page (used for contextual recommendations) |
| `modifier` | `Modifier` | `Modifier` | Standard Compose modifier |
| `debug` | `Boolean` | `false` | Enable feed debug logging. **Do not use in production** |
| `mockRecommendations` | `Boolean` | `false` | Show mock content. **Do not use in production** |
| `isDarkMode` | `Boolean?` | `null` | `true` forces dark, `false` forces light, `null` auto-detects from the system |
| `reloadToken` | `Any?` | `null` | Change to a new value to refresh content in place without collapsing the height |
| `isLoadingHolderEnabled` | `Boolean` | `true` | Show the SDK's loading spinner (reserved height) on initial load; `false` to use your own placeholder |
| `fontStyleUrls` | `List<String>` | standard AdConsole fonts | Font stylesheet URLs injected at the document level so custom fonts render inside the feed (needed on Android); pass `emptyList()` to disable |
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
