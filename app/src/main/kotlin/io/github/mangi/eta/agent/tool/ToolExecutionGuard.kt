package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import kotlinx.coroutines.CancellationException

/**
 * 危险操作确认闸门（[io.github.mangi.eta.agent.approval.AgentToolApprovalGate]）的安全边界。
 *
 * 存在的理由：闸门位于 [AgentLocalTools] 真正分发工具之前，是这条路径上唯一"每次工具调用都会
 * 执行的外部逻辑"。它的实现分散在风险判定、会话授权、确认卡等待三层，任何一层抛异常都会被
 * [AgentLocalTools] 最外层的 runCatching 折叠成通用的 `TOOL_ERROR`，而 release 包经过 R8 混淆后
 * 无消息异常的 simpleName 只剩形如 `wg5` 的短标识（安装包 dex 里确有 `Lwg5;` 这类类名），
 * 线上既无法定位、也看不出是"闸门自己坏了"还是"工具坏了"。
 *
 * 约定：
 * - 闸门缺失（子进程 / 单元测试）→ 放行，与既有行为一致；
 * - 闸门正常返回 → 原样透传，不做任何解释或改写；
 * - 取消 / 中断 → **原样上抛**（[AgentRunCancelledException]、[CancellationException]、
 *   [InterruptedException] 优先级高于兜底，否则 Stop 之后工具还会继续跑）；
 * - 闸门自身故障 → fail closed：返回 [ToolExecutionDecision.Reject]，code 固定为
 *   [CODE_GATE_UNAVAILABLE]。既不因故障默认放行危险操作，也不把故障伪装成
 *   "工具不存在 / 参数错误"；错误消息里带上工具名与异常类名，可直接对照 mapping.txt。
 *
 * 为什么只收 [Exception] 与 [LinkageError]：闸门自身的逻辑故障必然是这两类（含 R8 之后才会
 * 出现的 `NoClassDefFoundError` / `ExceptionInInitializerError` 等链接期错误）。其余
 * [Error]（`OutOfMemoryError`、`StackOverflowError`…）属于**进程级**故障，进程本来就该继续往
 * 上崩，把它们在这里折叠成一条"工具被拒绝"的结果只会掩盖真实原因。
 */
internal object ToolExecutionGuard {
    const val CODE_GATE_UNAVAILABLE = "DANGER_GATE_UNAVAILABLE"

    fun check(
        toolName: String,
        gate: (() -> ToolExecutionDecision)?,
    ): ToolExecutionDecision {
        if (gate == null) return ToolExecutionDecision.Allow
        return try {
            gate()
        } catch (cancelled: AgentRunCancelledException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (interrupted: InterruptedException) {
            // 中断状态必须保留给上层，同时别把 InterruptedException 藏在"工具被拒绝"后面。
            throw interrupted
        } catch (failure: Exception) {
            unavailable(toolName, failure)
        } catch (failure: LinkageError) {
            // release 包混淆 / 裁剪之后才会出现的链接期错误：fail closed，但要点名。
            unavailable(toolName, failure)
        }
    }

    private fun unavailable(toolName: String, failure: Throwable): ToolExecutionDecision.Reject =
        ToolExecutionDecision.Reject(
            code = CODE_GATE_UNAVAILABLE,
            message = "危险操作确认闸门不可用，本次调用未执行（工具 $toolName，故障类型 ${failure.javaClass.name}）；" +
                "请重试，或改用只读方式验证。不要假设这次操作已经执行。" +
                "若所有工具都返回本错误码（$CODE_GATE_UNAVAILABLE），说明是闸门自身故障而不是某个工具坏了。",
        )
}
