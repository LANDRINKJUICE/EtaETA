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

    fun isBroadDeleteTarget(path: String): Boolean {
        val normalized = stripTrailingSlash(normalize(path))
        if (normalized in broadDeleteTargets) return true
        return normalized.contains('*') || normalized.contains('?') || normalized.contains('[')
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
