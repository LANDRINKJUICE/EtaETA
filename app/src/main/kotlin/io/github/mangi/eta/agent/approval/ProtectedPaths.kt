package io.github.mangi.eta.agent.approval

import java.util.Locale

/**
 * 受保护路径与写入目标判定。
 *
 * 这里是"Android 系统分区与启动链一律只可读、只可复制，写入必须经用户确认"这条首要规则的
 * 静态实现。所有判定都是字符串层面的保守估算，无法解析符号链接与运行期 mount namespace；
 * 拿不准时选择命中（宁可误拦，由用户点"允许"放行）。
 */
internal object ProtectedPaths {
    /** Eta 自身的包名：它自己的私有目录允许自由读写。 */
    const val OWN_PACKAGE = "io.github.mangi.eta"

    private val systemPartitionPrefixes = listOf(
        "/system",
        "/system_ext",
        "/system_root",
        "/system_dlkm",
        "/vendor",
        "/vendor_dlkm",
        "/odm",
        "/odm_dlkm",
        "/product",
        "/oem",
        "/my_product",
        "/my_heytap",
        "/my_stock",
        "/my_bigball",
        "/my_carrier",
        "/my_preload",
        "/my_manifest",
        "/special_preload",
        "/cust",
        "/version",
        "/mnt/vendor",
    )

    private val bootChainPrefixes = listOf(
        "/boot",
        "/init_boot",
        "/vendor_boot",
        "/recovery",
        "/dtbo",
        "/dtb",
        "/vbmeta",
        "/super",
        "/misc",
        "/debug_ramdisk",
        "/first_stage_ramdisk",
        "/metadata",
        "/efs",
        "/persist",
        "/modem",
        "/radio",
        "/dev/block",
        "/dev/block/by-name",
        "/dev/bootimg",
        "/firmware",
        "/apex",
    )

    private val systemDataPrefixes = listOf(
        "/data/system",
        "/data/adb",
        "/data/misc",
        "/data/property",
        "/data/vendor",
        "/data/apex",
        "/data/app",
        "/data/dalvik-cache",
        "/data/backup",
        "/linkerconfig",
        "/metadata",
    )

    /** 大范围删除的保护目标：递归删除这些目录基本等于清空设备数据。 */
    private val broadDeleteTargets = setOf(
        "/",
        "/data",
        "/data/data",
        "/data/user",
        "/data/user/0",
        "/data/user_de",
        "/data/user_de/0",
        "/data/local",
        "/data/media",
        "/data/media/0",
        "/storage",
        "/storage/emulated",
        "/storage/emulated/0",
        "/sdcard",
        "/mnt",
        "/mnt/sdcard",
        "/sdcard0",
        "/external_sd",
        "/system",
        "/vendor",
        // 根下一级目录：Android 上 /etc、/bin、/lib、/sbin 多是 /system 的符号链接，
        // /dev/* 会当场废掉当前会话。旧版靠"任意通配符都算 broad"顺带覆盖；收紧成
        // "父路径受保护才算 broad"后必须显式补回，否则 rm -rf /etc/* 等会漏判成 SAFE。
        "/etc", "/bin", "/lib", "/sbin", "/dev", "/proc", "/sys",
        "/root", "/config", "/cache", "/acct", "/emulated", "/tmp",
    )

    /**
     * 规范化路径：去掉包裹引号、合并重复斜杠、解析 `.` 与 `..`、去掉尾部斜杠。
     * 返回以 `/` 开头的绝对路径；相对路径只做轻量规范化（用于 `~` 之类的形态判断）。
     */
    fun normalize(rawPath: String): String {
        var path = rawPath.trim()
        if (path.isEmpty()) return path
        while (path.length >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
            path = path.substring(1, path.length - 1).trim()
        }
        while (path.length >= 2 && path.startsWith("'") && path.endsWith("'")) {
            path = path.substring(1, path.length - 1).trim()
        }
        if (path.isEmpty()) return path
        val segments = ArrayDeque<String>()
        var index = 0
        while (index < path.length) {
            val start = index
            while (index < path.length && path[index] != '/') index++
            val segment = path.substring(start, index)
            index++
            when (segment) {
                "", "." -> Unit
                ".." -> segments.removeLastOrNull()
                else -> segments.addLast(segment)
            }
        }
        val joined = segments.joinToString("/")
        return when {
            path.startsWith("/") -> "/" + joined
            joined.isEmpty() -> "."
            else -> joined
        }
    }

    private fun stripTrailingSlash(path: String): String =
        if (path.length > 1 && path.endsWith("/")) path.dropLast(1) else path

    private fun matchesPrefix(path: String, prefix: String): Boolean =
        path == prefix || path.startsWith("$prefix/")

    fun isSystemPartition(path: String): Boolean {
        val normalized = stripTrailingSlash(normalize(path))
        return systemPartitionPrefixes.any { matchesPrefix(normalized, it) }
    }

    fun isBootChain(path: String): Boolean {
        val normalized = stripTrailingSlash(normalize(path))
        return bootChainPrefixes.any { matchesPrefix(normalized, it) }
    }

    fun isSystemData(path: String): Boolean {
        val normalized = stripTrailingSlash(normalize(path))
        return systemDataPrefixes.any { matchesPrefix(normalized, it) }
    }

    /**
     * 是否属于「大范围删除」目标。
     *
     * 精确路径直接查 [broadDeleteTargets] 表。含通配符的目标不再一律算 broad：
     * 原实现把 `rm -rf` 工作区深层子目录下的通配符清理、`rm -f *.log` 这类**深层工作区
     * 清理**也判成 HIGH，是日常最频繁的误拦。现在只有当「通配符所在的父路径」本身落在
     * 大范围目录 / 系统分区 / 启动链 / 系统数据里时才算 broad，例如：
     * - `/data/data/` 下带通配符（父 `/data/data` 在 broad 表）→ 仍命中；
     * - 根目录下带通配符（父为根 `/`）→ 仍命中；
     * - `/workspace/.../build/` 下带通配符、相对目录的 `*.log` → 父不在任何保护表 → 放行。
     * 收紧后 ShellCommandAnalyzer 仅剩 broad（父路径受保护）与 unresolvedRange（范围无法静态确认）
     * 两个门槛；浅层通配符不再单独按段数命中，但父路径受保护时由本函数兜住。
     * 系统分区 / 启动链 / 外来应用私有目录的写入判定完全不受影响。
     */
    fun isBroadDeleteTarget(path: String): Boolean {
        val normalized = stripTrailingSlash(normalize(path))
        if (normalized in broadDeleteTargets) return true
        val hasWildcard = normalized.contains('*') || normalized.contains('?') || normalized.contains('[')
        if (!hasWildcard) return false
        // 取第一个含通配符的段之前的父路径；父路径为受保护/大范围目录时才算 broad。
        // 先丢掉 split 产生的开头空段与 "." 段，否则绝对路径的 takeWhile 会在首段空串处立即停，
        // 把 parent 错算成 "/"（会让任意含通配符路径都被误判成 broad）。
        val parent = run {
            val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
            val kept = segments.takeWhile { seg ->
                !seg.contains('*') && !seg.contains('?') && !seg.contains('[')
            }
            val joined = kept.joinToString("/")
            if (normalized.startsWith("/")) if (joined.isEmpty()) "/" else "/$joined" else joined
        }
        if (parent.isEmpty()) return false
        return parent in broadDeleteTargets ||
            isSystemPartition(parent) || isBootChain(parent) || isSystemData(parent)
    }

    /**
     * 其他应用的私有目录：/data/data/<pkg>、/data/user/<n>/<pkg>、/data/user_de/<n>/<pkg>、
     * 以及 Android/data 与 Android/obb 下的应用目录。
     *
     * @return 命中的包名（不是 Eta 自己、也不是被测包为空时返回非空）。
     */
    fun foreignPackageOf(path: String): String? {
        val normalized = normalize(path)
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        fun appPackage(prefix: List<String>): String? {
            if (segments.size <= prefix.size) return null
            val head = segments.take(prefix.size).map { it.lowercase(Locale.ROOT) }
            if (head != prefix.map { it.lowercase(Locale.ROOT) }) return null
            var index = prefix.size
            // /data/user/0/<pkg>、/data/user_de/0/<pkg>：跳过一个用户编号
            if (prefix.last() == "user" || prefix.last() == "user_de") {
                if (segments.size <= index) return null
                if (!segments[index].all { it.isDigit() }) return null
                index++
            }
            return segments.getOrNull(index)
        }

        val candidates = listOf(
            appPackage(listOf("data", "data")),
            appPackage(listOf("data", "user")),
            appPackage(listOf("data", "user_de")),
            appPackage(listOf("data", "media", "0", "Android", "data")),
            appPackage(listOf("data", "media", "0", "Android", "obb")),
            appPackage(listOf("storage", "emulated", "0", "Android", "data")),
            appPackage(listOf("storage", "emulated", "0", "Android", "obb")),
            appPackage(listOf("sdcard", "Android", "data")),
            appPackage(listOf("sdcard", "Android", "obb")),
        )
        val packageName = candidates.firstOrNull { it != null } ?: return null
        if (packageName == OWN_PACKAGE) return null
        if (packageName.isEmpty()) return null
        return packageName
    }
}
