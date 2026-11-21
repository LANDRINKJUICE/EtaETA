package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal enum class PackageProfileInstallStage {
    CHECKING,
    DOWNLOADING,
    INSTALLING,
    COMPLETE,
}

internal data class PackageProfileInstallProgress(
    val stage: PackageProfileInstallStage,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
)

internal sealed interface PackageProfileInstallResult {
    data object AlreadyReady : PackageProfileInstallResult
    data object EnvironmentNotReady : PackageProfileInstallResult

    /** 依赖的 profile 尚未安装，按依赖链先装它。 */
    data class DependencyMissing(val profileId: String) : PackageProfileInstallResult
    data object Installed : PackageProfileInstallResult
    data class Failed(val stage: PackageProfileInstallStage) : PackageProfileInstallResult
}

internal data class LinuxPackageSpec(
    val packages: List<String> = emptyList(),
    val managedTool: ManagedLinuxTool? = null,
    val setupScript: String? = null,
)

internal data class LinuxPackageProfile(
    val id: String,
    val markerName: String,
    val revision: Int,
    val specs: Map<LinuxDistribution, LinuxPackageSpec>,
    /** 安装前必须就绪的前置 profile。 */
    val dependsOn: LinuxPackageProfile? = null,
) {
    fun spec(distribution: LinuxDistribution): LinuxPackageSpec = requireNotNull(specs[distribution])
}

internal object LinuxPackageProfiles {
    /**
     * pnpm 随 Node 环境一起装。
     *
     * DSH 的插件管理器用 `spawn("pnpm")` 装插件，只认 PATH 里的 `pnpm`，找不到就直接
     * 抛 ENOENT（插件管理器的 pnpm.log 里可看到 `spawn pnpm ENOENT`）。ETA 的
     * Linux 环境原本不带 pnpm，导致「装插件失败」，只能事后手工补。
     *
     * 三步，顺序不能换：
     *
     * 1. **先删旧的入口软链**。重装 Node 会重建 `opt/eta/node/<版本>/`，让上一轮
     *    `npm install -g` 写下的 `/usr/local/bin/pnpm` 变成指向已删除目标的断链。
     *    npm 见到同名文件一律报 `EEXIST` 拒绝写入，于是「看起来在装、实际没装」。
     *    这一步就是修这个坑（v1.1.4 实测踩到过）。
     * 2. 装到 `/usr/local`，落进默认 PATH（`/usr/local/bin` 在首位）；先走国内镜像，
     *    失败回退官方源。
     * 3. 两条源都失败时把原因写到 stderr。**不能用 `|| true` 静默吞掉** ——
     *    那会让 marker 照常写入、界面上「已安装」，而插件安装依旧 ENOENT，
     *    排查成本极高。这里用 `echo`（退出码 0）保证只提示、不阻断 Node 环境就绪。
     */
    private const val PNPM_SETUP_SCRIPT =
        "rm -f /usr/local/bin/pnpm /usr/local/bin/pnpx /usr/local/bin/pn /usr/local/bin/pnx; " +
            "npm install -g --prefix /usr/local --registry=https://registry.npmmirror.com pnpm@12 " +
            "|| npm install -g --prefix /usr/local pnpm@12 " +
            "|| echo '[eta] pnpm install failed: DSH plugin installation will not work' >&2"

    /** DSH 引擎安装脚本：先试国内镜像，失败回退官方源；全局装到 /usr/local。 */
    private const val DSH_INSTALL_SCRIPT =
        "mkdir -p /opt/eta/dsh-home && " +
            "npm install -g --prefix /usr/local --registry=https://registry.npmmirror.com " +
            "@deepseek-ai/dsh@0.2.2-rc.5 || " +
            "npm install -g --prefix /usr/local @deepseek-ai/dsh@0.2.2-rc.5"

    val PYTHON = LinuxPackageProfile(
        id = "python",
        markerName = AlpineEnvironmentPaths.PYTHON_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.PYTHON_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(
                managedTool = ManagedLinuxTool.UV,
                setupScript = """
                    UV_PYTHON_INSTALL_DIR=/opt/eta/python UV_PYTHON_BIN_DIR=/usr/local/bin UV_PYTHON_INSTALL_BIN=1 uv python install --default --force
                """.trimIndent(),
            ),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                managedTool = ManagedLinuxTool.UV,
                setupScript = """
                    UV_PYTHON_INSTALL_DIR=/opt/eta/python UV_PYTHON_BIN_DIR=/usr/local/bin UV_PYTHON_INSTALL_BIN=1 uv python install --default --force
                """.trimIndent(),
            ),
        ),
    )
    val NODE = LinuxPackageProfile(
        id = "node",
        markerName = AlpineEnvironmentPaths.NODE_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.NODE_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(
                packages = listOf("nodejs-current", "npm"),
                setupScript = PNPM_SETUP_SCRIPT,
            ),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                // Node 官方 arm64 二进制链接 libatomic.so.1，归档安装不含系统依赖，需补装。
                packages = listOf("libatomic1"),
                managedTool = ManagedLinuxTool.NODE,
                setupScript = PNPM_SETUP_SCRIPT,
            ),
        ),
    )
    val SSH = LinuxPackageProfile(
        id = "ssh",
        markerName = AlpineEnvironmentPaths.SSH_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.SSH_TOOLS_REVISION,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(
                packages = listOf("openssh"),
                setupScript = "ssh-keygen -A >/dev/null 2>&1 || true",
            ),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(
                packages = listOf("openssh-client", "openssh-server"),
                setupScript = "ssh-keygen -A >/dev/null 2>&1 || true",
            ),
        ),
    )
    /**
     * 官方 DeepSeek Harness 引擎（@deepseek-ai/dsh），npm 分发，运行在 Node profile 之上。
     * 版本锁死：上游 rc 阶段存在破坏性变更，不做 @latest 漂移；
     * 升级 = 改版本号并递增 DSH_TOOLS_REVISION 触发重装。
     */
    val DSH = LinuxPackageProfile(
        id = "dsh",
        markerName = AlpineEnvironmentPaths.DSH_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.DSH_TOOLS_REVISION,
        dependsOn = NODE,
        specs = mapOf(
            LinuxDistribution.ALPINE to LinuxPackageSpec(setupScript = DSH_INSTALL_SCRIPT),
            LinuxDistribution.DEBIAN to LinuxPackageSpec(setupScript = DSH_INSTALL_SCRIPT),
        ),
    )
    val ALL = listOf(PYTHON, NODE, SSH, DSH)
}

internal fun linuxPackageProfileReady(rootfs: File, profile: LinuxPackageProfile): Boolean {
    val marker = File(rootfs, profile.markerName)
    if (!marker.isFile) return false
    return marker.useLines { lines ->
        lines.any { line -> line.trim() == "profile=${profile.revision}" }
    }
}

/** 为当前选中的发行版按需安装单个工具 profile；成功后只写对应完成标记。 */
internal class LinuxPackageProfileInstaller(
    private val context: Context,
    private val distribution: LinuxDistribution,
    private val profile: LinuxPackageProfile,
) {
    private val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
    private val managedToolInstaller = PinnedLinuxToolInstaller(context)

    fun isReady(): Boolean = linuxPackageProfileReady(rootfs, profile)

    suspend fun install(
        onProgress: suspend (PackageProfileInstallProgress) -> Unit = {},
    ): PackageProfileInstallResult {
        installMutex.lock()
        return try {
            installLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    private suspend fun installLocked(
        onProgress: suspend (PackageProfileInstallProgress) -> Unit,
    ): PackageProfileInstallResult = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext PackageProfileInstallResult.AlreadyReady
        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.CHECKING))
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath) ||
            !File(rootfs, AlpineEnvironmentPaths.COMMON_TOOLS_MARKER).isFile
        ) {
            return@withContext PackageProfileInstallResult.EnvironmentNotReady
        }
        profile.dependsOn?.let { dependency ->
            if (!linuxPackageProfileReady(rootfs, dependency)) {
                return@withContext PackageProfileInstallResult.DependencyMissing(dependency.id)
            }
        }

        val spec = profile.spec(distribution)
        spec.managedTool?.let { tool ->
            val installed = managedToolInstaller.install(
                tool = tool,
                distribution = distribution,
                rootfs = rootfs,
            ) { downloadedBytes, totalBytes ->
                onProgress(
                    PackageProfileInstallProgress(
                        stage = PackageProfileInstallStage.DOWNLOADING,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                    ),
                )
            }
            if (!installed) {
                return@withContext PackageProfileInstallResult.Failed(
                    PackageProfileInstallStage.DOWNLOADING,
                )
            }
        }

        val packageHelper = when (distribution) {
            LinuxDistribution.ALPINE -> "/usr/local/bin/eta-apk"
            LinuxDistribution.DEBIAN -> "/usr/local/bin/eta-apt"
        }
        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.INSTALLING))
        if (spec.packages.isNotEmpty()) {
            val installResult = InstallerShellRunner.run(
                command = "$packageHelper install ${spec.packages.joinToString(" ")}",
                timeoutSeconds = INSTALL_TIMEOUT_SECONDS,
                environment = distribution.terminalEnvironment,
                linuxRootfsPath = rootfs.absolutePath,
            )
            if (installResult.exitCode != 0) {
                return@withContext PackageProfileInstallResult.Failed(
                    PackageProfileInstallStage.INSTALLING,
                )
            }
        }

        val activateCommand = buildString {
            append("set -e\n")
            spec.setupScript?.let { script -> append(script).append('\n') }
            append("cat > /").append(profile.markerName).append(" <<'ETA_PROFILE_EOF'\n")
            append("profile=").append(profile.revision).append('\n')
            append("ETA_PROFILE_EOF\n")
            append("chmod 0644 /").append(profile.markerName).append(" || exit 71")
        }
        val result = InstallerShellRunner.run(
            command = activateCommand,
            timeoutSeconds = INSTALL_TIMEOUT_SECONDS,
            environment = distribution.terminalEnvironment,
            linuxRootfsPath = rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "Package profile action=activate distribution=${distribution.wireName} profile=${profile.id} " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        if (result.exitCode != 0) {
            return@withContext PackageProfileInstallResult.Failed(PackageProfileInstallStage.INSTALLING)
        }

        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.COMPLETE))
        PackageProfileInstallResult.Installed
    }

    companion object {
        private const val INSTALL_TIMEOUT_SECONDS = 600L
        private val installMutex = Mutex()
    }
}
