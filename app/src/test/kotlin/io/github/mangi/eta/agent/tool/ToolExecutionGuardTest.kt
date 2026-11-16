package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 回归测试：危险操作闸门自身故障时，工具调用必须失败得"有名字"。
 *
 * 现场：升级到 feature/agent-intelligence-rounds 后，terminal 工具在 environment=linux / android
 * 下全部返回 `TOOL_ERROR wg5`（连 `echo ok` 都失败）。`wg5` 在源码里根本搜不到——它是 release 包
 * 经 R8 混淆后的类名（安装包 dex 里确实存在 `Lwg5;` 这类三字符类名）。根因链路是：
 * 闸门在工具分发之前执行 → 闸门抛出一个无 message 的异常 → AgentLocalTools 的外层 runCatching
 * 退化成 `message ?: javaClass.simpleName` → 线上只剩一个混淆短标识，既定位不了、也看不出是
 * 闸门坏了而不是工具坏了。
 *
 * 这组用例钉住修复后的两条不变量：
 * 1. 闸门缺失或正常返回时，决策原样透传（行为与修复前完全一致）；
 * 2. 闸门抛异常（含 Error，例如 NoClassDefFoundError）时，必须 fail closed 成固定 code
 *    `DANGER_GATE_UNAVAILABLE`，消息里带工具名与异常类名；取消仍然上抛。
 */
class ToolExecutionGuardTest {

    @Test
    fun `no gate means allow`() {
        assertEquals(ToolExecutionDecision.Allow, ToolExecutionGuard.check("terminal", null))
    }

    @Test
    fun `allow is passed through`() {
        val decision = ToolExecutionGuard.check("terminal") { ToolExecutionDecision.Allow }
        assertEquals(ToolExecutionDecision.Allow, decision)
    }

    @Test
    fun `reject is passed through unchanged`() {
        val original = ToolExecutionDecision.Reject(code = "USER_DENIED", message = "用户拒绝执行这次操作")
        val decision = ToolExecutionGuard.check("terminal") { original }
        assertEquals(original, decision)
    }

    @Test
    fun `gate exception fails closed with a searchable code`() {
        val decision = ToolExecutionGuard.check("terminal") {
            // 模拟现场形态：无 message 的异常在 release 包里只剩混淆类名。
            throw IllegalStateException()
        }
        val reject = decision as? ToolExecutionDecision.Reject
            ?: fail("闸门故障必须 fail closed，不能放行：$decision").let { return }
        assertEquals(ToolExecutionGuard.CODE_GATE_UNAVAILABLE, reject.code)
        assertTrue(
            "错误消息必须点名工具，实际：${reject.message}",
            reject.message.contains("terminal"),
        )
        assertTrue(
            "错误消息必须带完整类名（可对照 mapping.txt），实际：${reject.message}",
            reject.message.contains(IllegalStateException::class.java.name),
        )
    }

    @Test
    fun `gate error fails closed instead of escaping the call chain`() {
        val decision = ToolExecutionGuard.check("read_file") {
            // release 包混淆/裁剪后才会出现的链接期错误：Error，不是 Exception。
            throw NoClassDefFoundError("Lwg5;")
        }
        val reject = decision as? ToolExecutionDecision.Reject
            ?: fail("Error 也必须被收口，实际：$decision").let { return }
        assertEquals(ToolExecutionGuard.CODE_GATE_UNAVAILABLE, reject.code)
        assertTrue(reject.message.contains("read_file"))
        assertTrue(reject.message.contains(NoClassDefFoundError::class.java.name))
    }

    @Test
    fun `cancellation still propagates`() {
        try {
            ToolExecutionGuard.check("terminal") { throw AgentRunCancelledException() }
            fail("取消必须上抛，不能被闸门兜底吞掉")
        } catch (expected: AgentRunCancelledException) {
            // 预期路径
        }
    }

    @Test
    fun `coroutine cancellation and thread interruption propagate too`() {
        // 协程取消（Stop）与线程中断都不是"闸门坏了"，绝不能被折叠成一次被拒绝的调用。
        try {
            ToolExecutionGuard.check("terminal") { throw kotlinx.coroutines.CancellationException("cancelled") }
            fail("CancellationException 必须上抛")
        } catch (expected: kotlinx.coroutines.CancellationException) {
            // 预期路径
        }
        try {
            ToolExecutionGuard.check("terminal") { throw InterruptedException("interrupted") }
            fail("InterruptedException 必须上抛")
        } catch (expected: InterruptedException) {
            // 预期路径
        }
    }

    @Test
    fun `process level errors are not swallowed as a rejected call`() {
        // 捕获范围只到 Exception + LinkageError：OOM 这类进程级故障必须继续往上崩，
        // 一旦在这里变成"工具被拒绝"，线上就再也看不到真实原因。
        try {
            ToolExecutionGuard.check("terminal") { throw OutOfMemoryError("heap") }
            fail("进程级 Error 不能被收口成拒绝结果")
        } catch (expected: OutOfMemoryError) {
            // 预期路径
        }
    }

    @Test
    fun `gate failure message explains the all-tools symptom`() {
        val decision = ToolExecutionGuard.check("terminal") { throw IllegalStateException("boom") }
        val reject = decision as? ToolExecutionDecision.Reject
            ?: fail("闸门故障必须 fail closed，实际：$decision").let { return }
        assertTrue(
            "消息要能让线上区分闸门坏了与某个工具坏了，实际：${reject.message}",
            reject.message.contains(ToolExecutionGuard.CODE_GATE_UNAVAILABLE),
        )
        assertTrue(reject.message.contains("所有工具"))
    }
}
