package io.github.mangi.eta.ui.screens.browser

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BrowserExternalUrlTest {
    /** WebView 拿到 token 后服务端 303 到 `./`，地址栏只剩干净地址；外部浏览器要的是入口地址。 */
    @Test
    fun tokenizedEntryIsSharedWhileThePageStaysOnItsOrigin() {
        val entry = "http://127.0.0.1:3080/?token=abc123"

        assertEquals(entry, externalBrowserUrl(entry, "http://127.0.0.1:3080/"))
        // 单页应用改路径仍属同一站点，token 依旧有效。
        assertEquals(entry, externalBrowserUrl(entry, "http://127.0.0.1:3080/session/abc"))
    }

    @Test
    fun plainPagesShareTheirOwnAddress() {
        assertEquals(
            "https://example.com/a",
            externalBrowserUrl("https://start.example.com/", "https://example.com/a"),
        )
        assertEquals("https://example.com/a", externalBrowserUrl(null, "https://example.com/a"))
        // 入口地址本身不带 token 时不需要特殊处理。
        assertEquals("https://example.com/b", externalBrowserUrl("https://example.com/a", "https://example.com/b"))
    }

    /** 离开入口站点后不能继续交出旧 token：那条地址指向的是另一个页面。 */
    @Test
    fun tokenIsDroppedOnceThePageLeavesTheOrigin() {
        val entry = "http://127.0.0.1:3080/?token=abc123"

        assertEquals("https://example.com/", externalBrowserUrl(entry, "https://example.com/"))
        assertEquals("http://127.0.0.1:3081/", externalBrowserUrl(entry, "http://127.0.0.1:3081/"))
        assertEquals("https://127.0.0.1:3080/", externalBrowserUrl(entry, "https://127.0.0.1:3080/"))
    }

    @Test
    fun pageWithoutAddressFallsBackToEmpty() {
        assertEquals("", externalBrowserUrl("http://127.0.0.1:3080/?token=abc", ""))
    }
}
