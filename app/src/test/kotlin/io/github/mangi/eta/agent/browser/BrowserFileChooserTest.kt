package io.github.mangi.eta.agent.browser

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 网页 `<input type=file>` 的接管：不悬挂、不串台、取消能释放页面。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BrowserFileChooserTest {
    private val key = "test-file-chooser"
    private val session get() = BrowserSessionRegistry.get(key)

    @After
    fun tearDown() = BrowserSessionRegistry.requestRemove(key)

    /** 离屏（Agent 自己开网页）没有可见页面：返回 true 但立刻回 null，不能挂着。 */
    @Test
    fun withoutVisiblePageTheRequestIsReleasedImmediately() {
        val callback = RecordingCallback()

        assertTrue(session.dispatchFileChooser(callback, null))
        assertNull(callback.results.single())
    }

    @Test
    fun acceptedRequestHandsThePickedFilesBack() {
        val launched = mutableListOf<Intent>()
        val completions = mutableListOf<(Array<Uri>?) -> Unit>()
        session.setFileChooser { intent, onResult ->
            launched += intent
            completions += onResult
            true
        }

        val callback = RecordingCallback()
        assertTrue(session.dispatchFileChooser(callback, null))
        assertEquals(Intent.ACTION_GET_CONTENT, launched.single().action)
        // 选择器还没返回前，页面收不到任何结果。
        assertTrue(callback.results.isEmpty())

        val picked = arrayOf(Uri.parse("content://media/external/images/1"))
        completions.single()(picked)
        assertSame(picked, callback.results.single())
    }

    /** WebView 只允许一个未决选择：第二次点击必须先作废第一次，否则页面永久卡住。 */
    @Test
    fun aSecondRequestSupersedesTheFirst() {
        session.setFileChooser { _, _ -> true }

        val first = RecordingCallback()
        val second = RecordingCallback()
        session.dispatchFileChooser(first, null)
        session.dispatchFileChooser(second, null)

        assertNull(first.results.single())
        assertTrue(second.results.isEmpty())
    }

    /** 选择器起不来（没有可用 Activity）时也要把页面放回可用状态。 */
    @Test
    fun aDeclinedLaunchStillReleasesThePage() {
        session.setFileChooser { _, _ -> false }

        val callback = RecordingCallback()
        assertTrue(session.dispatchFileChooser(callback, null))
        assertNull(callback.results.single())
    }

    @Test
    fun cancellingReleasesThePendingCallback() {
        session.setFileChooser { _, _ -> true }

        val callback = RecordingCallback()
        session.dispatchFileChooser(callback, null)
        session.cancelPendingFileChooser()

        assertNull(callback.results.single())
    }

    private class RecordingCallback : ValueCallback<Array<Uri>> {
        val results = mutableListOf<Array<Uri>?>()

        override fun onReceiveValue(value: Array<Uri>?) {
            results += value
        }
    }
}
