package io.github.mangi.eta.ui

import android.content.ClipData
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.ui.app.ArchivedConversationRequest
import io.github.mangi.eta.ui.voice.ACTION_SPEECH_SETTINGS
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class MainActivityLaunchRequestsTest {
    @Test
    fun `重建不重新执行已消费的启动会话`() {
        val originalIntent = Intent(EtaAssistantOverlayService.ACTION_OPEN_CONVERSATION)
            .putExtra(EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY, "old-conversation")
        val pending = MainActivityLaunchRequests.restore(null, originalIntent)
        assertEquals("old-conversation", pending.assistantConversationKey)
        val consumed = pending.copy(assistantConversationKey = null)

        val restored = MainActivityLaunchRequests.restore(savedState(consumed), originalIntent)

        assertEquals(MainActivityLaunchRequests(), restored)
    }

    @Test
    fun `重建保留尚未完成的跳转且不被原始 Intent 覆盖`() {
        val pending = MainActivityLaunchRequests(
            assistantConversationKey = "assistant",
            conversationId = "conversation",
            archivedConversation = ArchivedConversationRequest("source", "archive"),
            speechSettings = true,
            terminal = true,
        )

        val restored = MainActivityLaunchRequests.restore(
            savedState(pending),
            Intent(MainActivity.ACTION_OPEN_CONVERSATION_ID)
                .putExtra(MainActivity.EXTRA_CONVERSATION_ID, "original"),
        )

        assertEquals(pending, restored)
    }

    @Test
    fun `桌面再次进入不产生跳转也不清除尚未完成的请求`() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pending = MainActivityLaunchRequests(conversationId = "conversation")

        assertEquals(MainActivityLaunchRequests(), MainActivityLaunchRequests().accept(launcher))
        assertEquals(pending, pending.accept(launcher))
    }

    @Test
    fun `真正的新 Intent 在恢复后仍能打开目标入口`() {
        val restored = MainActivityLaunchRequests.restore(savedState(MainActivityLaunchRequests()), null)

        assertEquals(
            MainActivityLaunchRequests(terminal = true),
            restored.accept(Intent(MainActivity.ACTION_OPEN_TERMINAL)),
        )
        assertEquals(
            MainActivityLaunchRequests(speechSettings = true),
            restored.accept(Intent(ACTION_SPEECH_SETTINGS)),
        )
        assertEquals(
            MainActivityLaunchRequests(archivedConversation = ArchivedConversationRequest("source", "archive")),
            restored.accept(
                Intent(MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION)
                    .putExtra(MainActivity.EXTRA_HANDOFF_SOURCE, "source")
                    .putExtra(MainActivity.EXTRA_CONVERSATION_KEY, "archive"),
            ),
        )
    }

    @Test
    fun `无效会话参数不会产生跳转`() {
        assertEquals(
            MainActivityLaunchRequests(),
            MainActivityLaunchRequests().accept(
                Intent(MainActivity.ACTION_OPEN_CONVERSATION_ID)
                    .putExtra(MainActivity.EXTRA_CONVERSATION_ID, " "),
            ),
        )
        assertEquals(
            MainActivityLaunchRequests(),
            MainActivityLaunchRequests().accept(
                Intent(MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION)
                    .putExtra(MainActivity.EXTRA_HANDOFF_SOURCE, "source"),
            ),
        )
    }

    @Test
    fun `单文件与多文件分享提取去重URI与附带文本且消费后重建不重放`() {
        val fileUri = Uri.parse("content://provider/files/report.pdf")
        val imageUri = Uri.parse("content://provider/images/photo.png")
        val singleShare = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            putExtra(Intent.EXTRA_TEXT, "帮我总结这份文档")
        }
        val acceptedSingle = MainActivityLaunchRequests.restore(null, singleShare)
        assertEquals(
            IncomingShareRequest(
                uris = listOf(fileUri.toString()),
                mimeType = "application/pdf",
                text = "帮我总结这份文档",
            ),
            acceptedSingle.sharedContent,
        )

        val consumed = acceptedSingle.copy(sharedContent = null)
        assertEquals(
            MainActivityLaunchRequests(),
            MainActivityLaunchRequests.restore(savedState(consumed), singleShare),
        )

        val multiShare = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(fileUri, imageUri))
            clipData = ClipData.newRawUri("shared", fileUri).apply {
                addItem(ClipData.Item(imageUri))
            }
            putExtra(Intent.EXTRA_TEXT, fileUri.toString())
        }
        val acceptedMulti = MainActivityLaunchRequests().accept(multiShare)
        assertEquals(
            IncomingShareRequest(
                uris = listOf(fileUri.toString(), imageUri.toString()),
                mimeType = "*/*",
                text = null,
            ),
            acceptedMulti.sharedContent,
        )
    }

    private fun savedState(requests: MainActivityLaunchRequests): Bundle {
        val state = Bundle().also(requests::saveTo)
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(state)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readBundle(javaClass.classLoader))
        } finally {
            parcel.recycle()
        }
    }

    /** 用官方 RemoteInput.addResultsToIntent 构造结果 Intent，与 getResultsFromIntent 配对、不手搓 clipData。 */
    private fun replyIntent(
        token: String?,
        text: CharSequence,
        nonce: String = io.github.mangi.eta.agent.notify.ReplyToken.newNonce(),
    ): Intent {
        val results = android.os.Bundle().apply {
            putCharSequence(MainActivity.REPLY_RESULT_KEY, text)
        }
        val intent = Intent(MainActivity.ACTION_REPLY_TO_CONVERSATION)
            .putExtra(MainActivity.EXTRA_CONVERSATION_ID, "conv-1")
            .putExtra(MainActivity.EXTRA_NOTIFICATION_ID, 9301)
            .putExtra(MainActivity.EXTRA_REPLY_NONCE, nonce)
            .also { if (token != null) it.putExtra(MainActivity.EXTRA_REPLY_TOKEN, token) }
        android.app.RemoteInput.addResultsToIntent(
            arrayOf(android.app.RemoteInput.Builder(MainActivity.REPLY_RESULT_KEY).build()),
            intent,
            results,
        )
        return intent
    }

    @Test
    fun `通知回复带合法令牌才注入消息`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val token = io.github.mangi.eta.agent.notify.ReplyToken.current(context)
        val result = MainActivityLaunchRequests().accept(replyIntent(token, "  继续吧  "), context)
        assertEquals(
            ConversationReplyRequest("conv-1", "继续吧", 9301),
            result.conversationReply,
        )
    }

    @Test
    fun `通知回复令牌缺失或伪造一律不接受`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // 无令牌
        assertEquals(
            null,
            MainActivityLaunchRequests().accept(replyIntent(null, "注入内容"), context).conversationReply,
        )
        // 错误令牌
        assertEquals(
            null,
            MainActivityLaunchRequests().accept(replyIntent("forged", "注入内容"), context).conversationReply,
        )
    }


    @Test
    fun `同一回复 nonce 第二次被拒不再注入消息`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val token = io.github.mangi.eta.agent.notify.ReplyToken.current(context)
        val nonce = io.github.mangi.eta.agent.notify.ReplyToken.newNonce()
        // 第一次：合法令牌 + 首次 nonce → 注入。
        val first = MainActivityLaunchRequests().accept(replyIntent(token, "只发一次", nonce), context)
        assertEquals(
            ConversationReplyRequest("conv-1", "只发一次", 9301),
            first.conversationReply,
        )
        // 第二次：模拟进程被杀后 system_server 用旧 Intent 重建、再 accept 同一 nonce → 不重复注入。
        val second = MainActivityLaunchRequests().accept(replyIntent(token, "只发一次", nonce), context)
        assertEquals(null, second.conversationReply)
    }

}
