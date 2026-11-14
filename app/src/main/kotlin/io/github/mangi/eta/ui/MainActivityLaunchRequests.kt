package io.github.mangi.eta.ui

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.ui.app.ArchivedConversationRequest
import io.github.mangi.eta.ui.voice.ACTION_SPEECH_SETTINGS
import io.github.mangi.eta.ui.navigation.TaskCenterDeepLink

data class IncomingShareRequest(
    val uris: List<String> = emptyList(),
    val mimeType: String? = null,
    val text: String? = null,
)

/** notify_user 通知栏直接回复：把 [text] 作为新消息发进 [conversationId] 对应会话。 */
data class ConversationReplyRequest(
    val conversationId: String,
    val text: String,
    val notificationId: Int = -1,
)

internal data class MainActivityLaunchRequests(
    val assistantConversationKey: String? = null,
    val conversationId: String? = null,
    val archivedConversation: ArchivedConversationRequest? = null,
    val conversationReply: ConversationReplyRequest? = null,
    val sharedContent: IncomingShareRequest? = null,
    val speechSettings: Boolean = false,
    val terminal: Boolean = false,
    val taskCenterTab: String? = null,
) {
    fun accept(intent: Intent?, context: Context? = null): MainActivityLaunchRequests = when (intent?.action) {
        MainActivity.ACTION_OPEN_TERMINAL -> copy(terminal = true)
        TaskCenterDeepLink.ACTION_OPEN_TASK_CENTER -> TaskCenterDeepLink.resolveTab(
            action = intent.action,
            rawTab = intent.getStringExtra(TaskCenterDeepLink.EXTRA_TASK_CENTER_TAB),
        )?.let { tab -> copy(taskCenterTab = tab) } ?: this
        ACTION_SPEECH_SETTINGS -> copy(speechSettings = true)
        MainActivity.ACTION_OPEN_CONVERSATION_ID -> copy(
            conversationId = intent.nonBlankExtra(MainActivity.EXTRA_CONVERSATION_ID),
        )
        MainActivity.ACTION_REPLY_TO_CONVERSATION -> {
            val conversationId = intent.nonBlankExtra(MainActivity.EXTRA_CONVERSATION_ID)
            val text = RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(MainActivity.REPLY_RESULT_KEY)
                ?.toString()?.trim()?.takeIf(String::isNotBlank)
            // 归属校验：令牌不等 / 缺失一律不接受，挡住外部 App 伪造 Intent 注入消息并驱动 run。
            val token = io.github.mangi.eta.agent.notify.ReplyToken
            val trusted = context != null && token.valid(
                context,
                intent.getStringExtra(MainActivity.EXTRA_REPLY_TOKEN),
            )
            // nonce 一次性消费：原子、持久化。二次 accept（含进程被杀重建）命中「已消费」直接丢弃，
            // 从根上关掉「同一条通知回复被发两次」的重放窗口。只在「真的要注入消息」时才消费。
            val fresh = trusted && context != null && conversationId != null && text != null &&
                token.consumeNonce(context, intent.getStringExtra(MainActivity.EXTRA_REPLY_NONCE))
            when {
                // 令牌合法 + nonce 首次 + 有会话 + 有正文：作为一条新消息发进该会话。
                fresh -> copy(
                    conversationReply = ConversationReplyRequest(
                        conversationId = conversationId,
                        text = text,
                        notificationId = intent.getIntExtra(MainActivity.EXTRA_NOTIFICATION_ID, -1),
                    ),
                )
                // 令牌合法但没打正文 / 会话缺失：至少打开那个会话，别停在空白首页（不注入消息）。
                trusted && conversationId != null -> copy(conversationId = conversationId)
                // 伪造 / 非法：什么都不做。
                else -> this
            }
        }
        MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION -> {
            val source = intent.nonBlankExtra(MainActivity.EXTRA_HANDOFF_SOURCE)
            val key = intent.nonBlankExtra(MainActivity.EXTRA_CONVERSATION_KEY)
            copy(
                archivedConversation = if (source != null && key != null) {
                    ArchivedConversationRequest(source, key)
                } else null,
            )
        }
        EtaAssistantOverlayService.ACTION_OPEN_CONVERSATION -> copy(
            assistantConversationKey = intent.nonBlankExtra(EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY),
        )
        Intent.ACTION_SEND,
        Intent.ACTION_SEND_MULTIPLE,
        -> intent.toIncomingShareRequest()?.let { request ->
            copy(sharedContent = request)
        } ?: this
        else -> this
    }

    fun saveTo(outState: Bundle) {
        outState.putBundle(STATE_KEY, Bundle().apply {
            putString("assistant_conversation_key", assistantConversationKey)
            putString("conversation_id", conversationId)
            putString("archive_source", archivedConversation?.source)
            putString("archive_conversation_key", archivedConversation?.conversationKey)
            putString("conversation_reply_id", conversationReply?.conversationId)
            putString("conversation_reply_text", conversationReply?.text)
            putInt("conversation_reply_notification_id", conversationReply?.notificationId ?: -1)
            sharedContent?.let { share ->
                putStringArrayList("share_uris", ArrayList(share.uris))
                putString("share_mime_type", share.mimeType)
                putString("share_text", share.text)
            }
            putBoolean("speech_settings", speechSettings)
            putBoolean("terminal", terminal)
            putString("task_center_tab", taskCenterTab)
        })
    }

    companion object {
        private const val STATE_KEY = "eta_pending_launch_requests"

        fun restore(savedInstanceState: Bundle?, intent: Intent?, context: Context? = null): MainActivityLaunchRequests {
            // 系统可能保留最初的启动 Intent；空的待办状态也必须保存，防止重建后重放已消费的跳转。
            val saved = savedInstanceState?.getBundle(STATE_KEY)
                ?: return MainActivityLaunchRequests().accept(intent, context)
            val source = saved.getString("archive_source")
            val key = saved.getString("archive_conversation_key")
            val shareUris = saved.getStringArrayList("share_uris")
                ?.mapNotNull { it?.takeIf(String::isNotBlank) }
                ?.distinct()
                .orEmpty()
            val shareText = saved.getString("share_text")?.takeIf(String::isNotBlank)
            val shareMimeType = saved.getString("share_mime_type")?.takeIf(String::isNotBlank)
            return MainActivityLaunchRequests(
                assistantConversationKey = saved.getString("assistant_conversation_key"),
                conversationId = saved.getString("conversation_id"),
                archivedConversation = if (source != null && key != null) {
                    ArchivedConversationRequest(source, key)
                } else null,
                conversationReply = saved.getString("conversation_reply_id")
                    ?.takeIf(String::isNotBlank)
                    ?.let { id ->
                        saved.getString("conversation_reply_text")
                            ?.takeIf(String::isNotBlank)
                            ?.let { text ->
                                ConversationReplyRequest(
                                    conversationId = id,
                                    text = text,
                                    notificationId = saved.getInt("conversation_reply_notification_id", -1),
                                )
                            }
                    },
                sharedContent = if (shareUris.isNotEmpty() || shareText != null) {
                    IncomingShareRequest(
                        uris = shareUris,
                        mimeType = shareMimeType,
                        text = shareText,
                    )
                } else null,
                speechSettings = saved.getBoolean("speech_settings"),
                terminal = saved.getBoolean("terminal"),
                taskCenterTab = saved.getString("task_center_tab"),
            )
        }
    }
}

private fun Intent.toIncomingShareRequest(): IncomingShareRequest? {
    val streamUris = when (action) {
        Intent.ACTION_SEND -> listOfNotNull(
            IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java),
        )
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
            this,
            Intent.EXTRA_STREAM,
            Uri::class.java,
        ).orEmpty()
        else -> emptyList()
    }
    val clipUris = buildList {
        val data = clipData ?: return@buildList
        for (index in 0 until data.itemCount) {
            data.getItemAt(index)?.uri?.let(::add)
        }
    }
    val uris = (streamUris + clipUris)
        .mapNotNull { uri -> uri.toString().takeIf(String::isNotBlank) }
        .distinct()
    val rawText = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()
        ?.takeIf(String::isNotBlank)
        ?: if (uris.isEmpty()) {
            clipData?.let { data ->
                buildList {
                    for (index in 0 until data.itemCount) {
                        data.getItemAt(index)?.text?.toString()?.trim()
                            ?.takeIf(String::isNotBlank)
                            ?.let(::add)
                    }
                }.joinToString("\n").takeIf(String::isNotBlank)
            }
        } else {
            null
        }
    val text = rawText?.takeUnless { it in uris }
    if (uris.isEmpty() && text == null) return null
    return IncomingShareRequest(
        uris = uris,
        mimeType = type?.takeIf(String::isNotBlank),
        text = text,
    )
}

private fun Intent.nonBlankExtra(key: String): String? = getStringExtra(key)?.takeIf(String::isNotBlank)
