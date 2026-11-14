package io.github.mangi.eta.agent.notify

import android.content.Context
import java.security.SecureRandom

/**
 * notify_user 通知栏「回复」action 的归属校验令牌。
 *
 * `MainActivity` 是 exported=true（`singleTask`），`ACTION_REPLY_TO_CONVERSATION` 又带
 * RemoteInput 结果；仅靠 action + extras，任何同机 App 都能伪造一个 Intent 往用户会话里
 * 注入一条消息并驱动一轮 run（prompt injection / 数据外泄路径）。`PendingIntent.getActivity`
 * 又不能用 exported=false 的广播接收器替代（发送要驱动 UI 会话 run 管线）。
 *
 * 折中：本装机首次使用时生成一个随机令牌存进**应用私有** SharedPreferences，回复 Intent 带上它，
 * [valid] 校验相等才接受。外部 App 读不到本 App 私有存储，也就伪造不出合法回复；我们自己的
 * 通知 PendingIntent 在发通知时取同一份令牌，进程被杀后重建仍是同一个值（持久化），不会让
 * 已发出的通知变成「永远回不来」。
 */
internal object ReplyToken {
    private const val PREFS = "eta_notify_reply"
    private const val KEY = "token"
    private const val CONSUMED_KEY = "consumed_nonce"
    /** 已消费 nonce 只留最近这么多条，避免 prefs 无限增长（每条通知回复都会带一个）。 */
    private const val MAX_CONSUMED = 200

    /** 生成 / 读取同步，避免两个线程同时首次取各生成一份、其中一份写进通知后永远判非法。 */
    @Synchronized
    fun current(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY, null)
        if (!existing.isNullOrBlank()) return existing
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        // 显式无符号化：Byte 为负时 "%02x".format(it.toInt()) 会产出 ffffff.. 让令牌变长且不一致。
        val token = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        prefs.edit().putString(KEY, token).apply()
        return token
    }

    /** Intent 里的令牌是否等于本装机令牌；空 / 不等都判非法。 */
    fun valid(context: Context, candidate: String?): Boolean =
        !candidate.isNullOrBlank() && candidate == current(context)

    /** 给一条回复通知生成一个一次性 nonce（发通知时调用）。 */
    @Synchronized
    fun newNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * 原子消费 [nonce]：返回 true = 首次见到（允许注入消息），false = 已用过 / 为空（丢弃）。
     *
     * 持久化到应用私有 prefs，故「回复成功 → 进程被杀 → system_server 用最初的 PendingIntent
     * 重建 Activity（savedInstanceState==null）」时，即便令牌仍合法、clipData 还在，第二次
     * accept 也会命中「已消费」而不再发一条重复消息。这是关闭重放窗口的关键。
     * 消费时机放在 accept（决定要不要注入那一刻），与「先消费清 state 再投递」一致。
     */
    @Synchronized
    fun consumeNonce(context: Context, nonce: String?): Boolean {
        if (nonce.isNullOrBlank()) return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val consumed = prefs.getStringSet(CONSUMED_KEY, null)?.toMutableSet() ?: mutableSetOf()
        if (nonce in consumed) return false
        consumed.add(nonce)
        // 只留最近若干条，避免长期使用后单调膨胀；旧 nonce 对应的通知早已消失，留着也没意义。
        if (consumed.size > MAX_CONSUMED) {
            val keep = consumed.toList().takeLast(MAX_CONSUMED).toSet()
            consumed.clear()
            consumed.addAll(keep)
        }
        prefs.edit().putStringSet(CONSUMED_KEY, consumed).apply()
        return true
    }
}
