package io.github.mangi.eta.agent.dsh

import io.github.mangi.eta.agent.terminal.InstallerShellRunner
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 引擎 profile 的 Eta 扩展层。
 *
 * 不改引擎自己维护的 `profiles/<name>/cordis.patch.yml`（网页端 Settings 会重写它），
 * 而是写一份独立的 overlay 文件，启动时用 `--patch` 叠在 profile 之上：
 * 位置在 profile 层之后、home patch 之前，既能生效又不会和引擎的写入互相覆盖。
 *
 * 内容只有一条：把 Eta 的 MCP 工具服务端注册成 `mcp__eta__*` 工具。
 */
internal object DshProfileOverlay {

    const val OVERLAY_PATH = "/opt/eta/dsh-home/eta-overlay.yml"

    /** overlay 版本：内容变化时递增，触发重写。 */
    private const val OVERLAY_REVISION = 1

    private const val MARKER_PATH = "/opt/eta/dsh-home/.eta-overlay-revision"

    /** 幂等写入 overlay；已是最新版本时不做任何事。返回是否可用。 */
    suspend fun ensure(
        environment: TerminalEnvironment,
        linuxRootfsPath: String,
        mcpPort: Int = DEFAULT_MCP_PORT,
    ): Boolean {
        val command = buildString {
            append("set -e\n")
            append("mkdir -p /opt/eta/dsh-home\n")
            // 版本未变则跳过，避免每次启动都改文件。
            append("if grep -qx 'revision=").append(OVERLAY_REVISION)
                .append("' ").append(MARKER_PATH).append(" 2>/dev/null; then exit 0; fi\n")
            append("cat > ").append(OVERLAY_PATH).append(" <<'ETA_DSH_OVERLAY_EOF'\n")
            append(overlayBody(mcpPort))
            append("ETA_DSH_OVERLAY_EOF\n")
            append("printf 'revision=").append(OVERLAY_REVISION).append("\\n' > ").append(MARKER_PATH).append("\n")
            append("chmod 0644 ").append(OVERLAY_PATH).append(" ").append(MARKER_PATH).append(" || true\n")
        }
        return try {
            val result = InstallerShellRunner.run(
                command = command,
                timeoutSeconds = OVERLAY_TIMEOUT_SECONDS,
                environment = environment,
                linuxRootfsPath = linuxRootfsPath,
            )
            val ok = result.exitCode == 0
            if (!ok) {
                AndroidAgentLogger.warn(
                    "dsh_overlay_write_failed exit=${result.exitCode} output=${result.output.take(200)}",
                )
            }
            ok
        } catch (failure: Exception) {
            AndroidAgentLogger.warn("dsh_overlay_write_failed type=${failure.javaClass.simpleName}")
            false
        }
    }

    /** 覆盖层内容：顶层 `insert` 追加一条独立 id 的 mcp-client 条目。
     *  非 insert 形式会被当成"按 id 覆盖已有条目"，引擎找不到 id 就整条丢弃。 */
    private fun overlayBody(mcpPort: Int): String = buildString {
        append("- insert:\n")
        append("    - id: eta-tools-mcp\n")
        append("      name: '@deepseek-ai/dsh-mcp-client'\n")
        append("      config:\n")
        append("        serverName: eta\n")
        append("        transport: streamable-http\n")
        append("        url: http://127.0.0.1:").append(mcpPort).append("/\n")
    }

    const val DEFAULT_MCP_PORT = 8790
    private const val OVERLAY_TIMEOUT_SECONDS = 60L
}
