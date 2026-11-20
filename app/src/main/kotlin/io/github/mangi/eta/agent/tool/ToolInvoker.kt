package io.github.mangi.eta.agent.tool

import android.content.Context
import android.os.Looper
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.GuiControlCoordinator
import io.github.mangi.eta.core.AgentLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具调用的统一门面：把「目录形状」「名字分发」「结构化结果」「错误编码」收敛到一处。
 *
 * 供 MCP 服务端这类**外部入口**同步执行工具；内部 AgentLoop 仍直接持有
 * [AgentLocalTools]，不经过本类，避免多一层无意义转发。
 *
 * 契约（与 [AgentLocalTools] 完全一致）：
 * - 参数是 JSON 字符串，不是 [JSONObject]；
 * - 返回值是 [AgentModelClient.ToolResult]，其中 `content` 恒为 JSON 字符串；
 * - 错误不抛异常，统一编码成 `{"ok":false,"code":...,"message":...}`；
 * - 只能在工作线程调用（无障碍同步桥在主线程 fail-closed）。
 */
internal class ToolInvoker(
    private val context: Context,
    private val logger: AgentLogger,
    /** 手机界面操作权：外部入口与 App 内 run 共享同一把锁，避免两个 tap 互相打断。 */
    guiControl: GuiControlCoordinator.RunControl? = null,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    /** 串行化：MCP 每个请求一个线程，两个 tap 并发会互相打断，界面类工具必须排队。 */
    private val executionLock = ReentrantLock()
    private val tools = AgentLocalTools(
        context = context.applicationContext,
        logger = logger,
        browserRunId = "",
        guiControl = guiControl,
        rootAvailable = { io.github.mangi.eta.agent.device.RootAccess.isGranted },
        beforeToolExecution = { name ->
            if (AgentToolRequirements.requiresAccessibility(name) &&
                !io.github.mangi.eta.agent.accessibility.AgentAccessibilityService.isAvailable()
            ) {
                ToolExecutionDecision.Reject(
                    code = "ACCESSIBILITY_UNAVAILABLE",
                    message = "无障碍服务未连接，界面类工具暂不可用",
                )
            } else {
                ToolExecutionDecision.Allow
            }
        },
    )

    /** 模型可见的工具目录；外部入口据此生成自己的工具清单。 */
    fun catalog(): JSONArray = AgentToolCatalog.build(
        terminalTools = true,
        browserTools = true,
        deviceDirectTools = true,
        deviceSensitiveReadTools = true,
        deviceSensitiveActionTools = true,
        skillGitHubDiscovery = true,
        skillGitHubInstall = true,
        memoryTools = true,
        capabilities = AgentToolCapabilities.capture(context.applicationContext),
    )

    /**
     * 同步执行一个工具调用。调用方必须已经完成审批判定。
     *
     * @param name 工具名，必须出现在 [catalog] 里，否则返回 `UNKNOWN_TOOL`。
     * @param argumentsJson 模型侧给的参数 JSON；空串按 `{}` 处理。
     */
    fun invoke(name: String, argumentsJson: String): AgentModelClient.ToolResult {
        check(Looper.myLooper() != Looper.getMainLooper()) { "工具执行不能在主线程调用" }
        if (closed.get()) {
            return errorResult("INVOKER_CLOSED", "工具执行器已关闭")
        }
        val call = AgentModelClient.ToolCall(
            id = "mcp-${System.nanoTime()}",
            name = name,
            argumentsJson = argumentsJson.ifBlank { "{}" },
        )
        // 界面类工具串行执行：MCP 每个请求一个线程，并发 tap 会互相打断。
        val serialized = AgentToolRequirements.requiresGuiControl(name)
        return try {
            if (serialized) executionLock.withLock { tools.execute(call) } else tools.execute(call)
        } catch (cancelled: AgentRunCancelledException) {
            logger.warn("mcp_tool_cancelled name=$name")
            errorResult("CANCELLED", "工具执行已取消")
        } catch (failure: Exception) {
            logger.warn("mcp_tool_failed name=$name type=${failure.javaClass.simpleName}")
            errorResult("TOOL_ERROR", failure.message ?: failure.javaClass.simpleName)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { tools.close() }
    }

    private fun errorResult(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject().put("ok", false).put("code", code).put("message", message).toString(),
        )
}
