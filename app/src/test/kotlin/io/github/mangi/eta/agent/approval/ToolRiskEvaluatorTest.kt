package io.github.mangi.eta.agent.approval

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * 危险判定器的规则回归。
 *
 * 每条用例都对应一条用户可见的行为约束：系统分区与启动链只可读、只可复制；写入必须确认；
 * 间接执行与无法静态确认的目标不能漏判；常见只读命令不能被误拦。
 */
class ToolRiskEvaluatorTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(30)

    private fun evaluate(toolName: String, json: String, defaultCwd: String? = null): ToolRiskVerdict =
        AgentToolRiskEvaluator.evaluate(toolName, JSONObject(json), defaultCwd)

    private fun terminal(
        command: String,
        tool: String = "terminal",
        defaultCwd: String? = null,
        sessionId: String? = null,
    ): ToolRiskVerdict = AgentToolRiskEvaluator.evaluate(
        tool,
        JSONObject()
            .put("command", command)
            .also { json -> sessionId?.let { json.put("session_id", it) } },
        defaultCwd,
    )

    private fun assertSafe(command: String, defaultCwd: String? = null) {
        assertEquals("命令应判定为安全：$command", ToolRiskVerdict.Safe, terminal(command, defaultCwd = defaultCwd))
    }

    private fun confirm(command: String, defaultCwd: String? = null, sessionId: String? = null): ToolRiskVerdict.NeedsConfirm {
        val verdict = terminal(command, defaultCwd = defaultCwd, sessionId = sessionId)
        assertTrue("命令应需要确认：$command", verdict is ToolRiskVerdict.NeedsConfirm)
        return verdict as ToolRiskVerdict.NeedsConfirm
    }

    private fun assertCategory(command: String, category: ToolRiskCategory) {
        val verdict = confirm(command)
        assertTrue(
            "命令 $command 应命中 $category，实际 ${verdict.categories}",
            category in verdict.categories,
        )
    }

    // ── 只读放行 ─────────────────────────────────────────────────────

    @Test
    fun readOnlyAccessToSystemPartitionsIsNotBlocked() {
        assertSafe("cat /system/build.prop")
        assertSafe("ls -l /vendor/lib64")
        assertSafe("getprop ro.build.version.release")
        assertSafe("stat /dev/block/by-name/boot")
        assertSafe("sha256sum /system/bin/app_process64")
        assertSafe("grep -r ro.product.model /system/build.prop")
        assertSafe("cat /dev/block/by-name/boot | xxd | head")
        assertSafe("ls /data/data")
        assertSafe("find /system -name '*.prop'")
    }

    @Test
    fun copyingOutOfASystemPartitionIsAllowed() {
        assertSafe("cp /system/build.prop /sdcard/backup/build.prop")
        assertSafe("cp -a /vendor/etc/hosts /data/local/tmp/hosts")
        assertSafe("dd if=/dev/block/by-name/boot of=/sdcard/boot.img")
        assertSafe("mkdir -p /sdcard/backup && cp /system/build.prop /sdcard/backup/")
    }

    @Test
    fun writingToOwnWorkspaceIsAllowed() {
        assertSafe("echo hi > /data/data/io.github.mangi.eta/files/terminal-user/workspace/a.txt")
        assertSafe("rm -rf /data/data/io.github.mangi.eta/files/terminal-user/workspace/build")
        assertSafe("rm -rf /workspace/build")
        assertSafe("echo x >> /data/local/tmp/eta/log.txt")
        assertSafe("rm -rf /sdcard/Download/eta-scratch")
    }

    @Test
    fun relativePathsInsideTheDefaultWorkspaceAreAllowed() {
        val cwd = "/data/data/io.github.mangi.eta/files/terminal-user/workspace"
        assertSafe("mkdir -p build", defaultCwd = cwd)
        assertSafe("rm -rf build", defaultCwd = cwd)
        assertSafe("cp notes.md backup/notes.md", defaultCwd = cwd)
        assertSafe("echo x > out.txt", defaultCwd = cwd)
    }

    @Test
    fun routineCommandsAreNotBlocked() {
        assertSafe("pm list packages")
        assertSafe("settings get global adb_enabled")
        assertSafe("dumpsys battery")
        assertSafe("logcat -d -t 100")
        assertSafe("mount")
        assertSafe("setsid sh -c 'cd /workspace && ./run.sh'")
        assertSafe("mkdir -p /sdcard/Download/eta")
    }

    // ── 系统分区与启动链写入 ─────────────────────────────────────────

    @Test
    fun writingToSystemPartitionRequiresConfirmation() {
        assertCategory("echo x > /system/build.prop", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
        assertCategory("cp /sdcard/build.prop /system/build.prop", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
        assertCategory("mv /sdcard/x /vendor/etc/x", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
        assertCategory("rm -f /system/app/Foo/Foo.apk", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
        assertCategory("touch /product/etc/foo", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
        assertCategory("chmod 644 /system_ext/etc/x", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
        assertCategory("install -m 644 /sdcard/hosts /system/etc/hosts", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
        assertCategory("echo x > /system_dlkm/etc/x", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
        assertCategory("echo x > /vendor_dlkm/etc/x", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
        assertCategory("echo x > /mnt/vendor/x", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
    }

    @Test
    fun writingBootChainRequiresConfirmation() {
        assertCategory("dd if=/sdcard/boot.img of=/dev/block/by-name/boot", ToolRiskCategory.BOOT_CHAIN_WRITE)
        assertCategory("dd if=/sdcard/boot.img of=/boot", ToolRiskCategory.BOOT_CHAIN_WRITE)
        assertCategory("cat x > /init_boot", ToolRiskCategory.BOOT_CHAIN_WRITE)
        assertCategory("cp /sdcard/dtbo.img /dev/block/by-name/dtbo", ToolRiskCategory.BOOT_CHAIN_WRITE)
        assertCategory("touch /recovery", ToolRiskCategory.BOOT_CHAIN_WRITE)
        assertCategory("echo x > /debug_ramdisk/x", ToolRiskCategory.BOOT_CHAIN_WRITE)
        assertCategory("echo x > /first_stage_ramdisk/x", ToolRiskCategory.BOOT_CHAIN_WRITE)
    }

    @Test
    fun ddToRawBlockDeviceAlsoCountsAsFormatter() {
        val verdict = confirm("dd if=/dev/zero of=/dev/block/mmcblk0 bs=1M count=1")
        assertTrue(ToolRiskCategory.DISK_FORMAT in verdict.categories)
        assertEquals(ToolRiskLevel.CRITICAL, verdict.level)
    }

    @Test
    fun ddToDevSinksIsWhitelisted() {
        assertSafe("dd if=/sdcard/boot.img of=/dev/null bs=4k")
        assertSafe("dd if=/dev/zero of=/dev/null count=1")
        assertSafe("head -c 16 /sdcard/x > /dev/urandom")
        assertSafe("echo x > /dev/null")
    }

    @Test
    fun remountingReadWriteRequiresConfirmation() {
        assertCategory("mount -o rw,remount /system", ToolRiskCategory.REMOUNT_READ_WRITE)
        assertCategory("mount -o remount,rw /vendor", ToolRiskCategory.REMOUNT_READ_WRITE)
        assertCategory("remount", ToolRiskCategory.REMOUNT_READ_WRITE)
        assertCategory("su -c \"mount -o rw,remount /system_ext\"", ToolRiskCategory.REMOUNT_READ_WRITE)
    }

    @Test
    fun readOnlyMountOfSystemImageIsAllowed() {
        assertSafe("mount -o ro /dev/block/by-name/system /mnt/system")
    }

    @Test
    fun quotingAndShellWrappersDoNotHideTheWrite() {
        assertCategory("sh -c 'echo x > /system/build.prop'", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
        assertCategory("bash -c \"dd if=/dev/zero of=/dev/block/by-name/boot\"", ToolRiskCategory.BOOT_CHAIN_WRITE)
        assertCategory("dd if=x of=\"/boot/boot.img\"", ToolRiskCategory.BOOT_CHAIN_WRITE)
        assertCategory("echo x > /tmp/x && echo y > /system/build.prop", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
        assertCategory("echo x >> /system/build.prop", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
        assertCategory("printf x | tee /vendor/etc/x", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
    }

    @Test
    fun redirectionOperatorsAreAllRecognized() {
        assertCategory("echo x >| /system/build.prop", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
        assertCategory("echo x &> /system/build.prop", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
        assertCategory("echo x &>> /system/build.prop", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
        assertCategory("echo x 1>&2 2>/dev/null; echo y > /system/build.prop", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
        // 文件描述符复制不能被当成写入目标
        assertSafe("ls -l /system/build.prop >/dev/null 2>&1")
    }

    @Test
    fun commandSubstitutionIsAnalyzedRecursively() {
        assertCategory(
            "target=$(echo /system/build.prop) && echo x > /system/build.prop",
            ToolRiskCategory.SYSTEM_PARTITION_WRITE,
        )
        assertCategory("[ -f /system/x ] && `echo y > /system/x`", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
    }

    // ── B1 间接执行 ──────────────────────────────────────────────────

    @Test
    fun pipedCommandIsAnalyzedInsteadOfOnlyTheShellStage() {
        // printf ... | sh：把前段当脚本内容分析
        assertCategory(
            "printf 'echo x > /system/build.prop\\n' | sh",
            ToolRiskCategory.SYSTEM_PARTITION_WRITE,
        )
        assertCategory(
            "printf '%s\\n' 'dd if=x of=/dev/block/by-name/boot' | bash",
            ToolRiskCategory.BOOT_CHAIN_WRITE,
        )
        assertCategory(
            "echo 'mkdir -p /data/adb/modules/x' | sh -s",
            ToolRiskCategory.SYSTEM_DATA_WRITE,
        )
    }

    @Test
    fun base64DecodedPayloadPipedIntoShellIsIntercepted() {
        // 解码内容无法静态读取，但"解码后送进 shell"本身就必须确认。
        val verdict = confirm("base64 -d /sdcard/evil.b64 | sh")
        assertTrue(
            "应命中间接执行：${verdict.categories}",
            ToolRiskCategory.INDIRECT_EXECUTION in verdict.categories,
        )
    }

    @Test
    fun scriptFilesAndInterpretersAreIndirectExecution() {
        assertCategory("sh /tmp/x.sh", ToolRiskCategory.INDIRECT_EXECUTION)
        assertCategory("bash ./install.sh", ToolRiskCategory.INDIRECT_EXECUTION)
        assertCategory("source /sdcard/env.sh", ToolRiskCategory.INDIRECT_EXECUTION)
        assertCategory(". /sdcard/env.sh", ToolRiskCategory.INDIRECT_EXECUTION)
        assertCategory("eval \"\$CMD\"", ToolRiskCategory.INDIRECT_EXECUTION)
        assertCategory("perl -e 'unlink q(/system/x)'", ToolRiskCategory.INDIRECT_EXECUTION)
        assertCategory("python3 -c \"open('/system/x','w')\"", ToolRiskCategory.INDIRECT_EXECUTION)
        assertCategory("node -e 'require(\"fs\")'", ToolRiskCategory.INDIRECT_EXECUTION)
        assertCategory("awk 'BEGIN{print 1 > \"/system/x\"}'", ToolRiskCategory.INDIRECT_EXECUTION)
        assertCategory("base64 -d /sdcard/x.b64", ToolRiskCategory.INDIRECT_EXECUTION)
        assertCategory("sh", ToolRiskCategory.INDIRECT_EXECUTION)
    }

    @Test
    fun xargsAndFindExecAreIntercepted() {
        assertCategory("echo /data/adb/modules/x | xargs rm", ToolRiskCategory.INDIRECT_EXECUTION)
        assertCategory("echo /data/adb/modules/x | xargs rm -rf", ToolRiskCategory.DESTRUCTIVE_DELETE)
        assertCategory("find /system -name '*.apk' -exec rm {} \\;", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
        assertCategory("find /data/adb -delete", ToolRiskCategory.DESTRUCTIVE_DELETE)
        assertCategory("printf '%s\\n' /data/adb/modules/x | xargs -I{} rm -rf {}", ToolRiskCategory.DESTRUCTIVE_DELETE)
    }

    @Test
    fun findWithoutExecOrDeleteStaysReadOnly() {
        assertSafe("find /data/adb/modules -maxdepth 1 -type d")
        assertSafe("find /system -name build.prop")
    }

    // ── B2 变量与相对路径 ────────────────────────────────────────────

    @Test
    fun variableAssignmentsAreResolvedBeforeJudging() {
        assertCategory(
            "P=/data/adb/modules/x; mkdir -p \"\$P\"",
            ToolRiskCategory.SYSTEM_DATA_WRITE,
        )
        assertCategory(
            "P=/dev/block/by-name/boot; dd if=/sdcard/boot.img of=\$P",
            ToolRiskCategory.BOOT_CHAIN_WRITE,
        )
        assertCategory(
            "export T=/system/etc/hosts; cp /sdcard/hosts \$T",
            ToolRiskCategory.SYSTEM_PARTITION_WRITE,
        )
        // 变量替换后落到工作区里：仍然安全
        assertSafe(
            "P=/data/data/io.github.mangi.eta/files/terminal-user/workspace/x; mkdir -p \$P",
        )
    }

    @Test
    fun unresolvedVariablesAreNeverTreatedAsSafe() {
        assertCategory("mkdir -p \$UNKNOWN_DIR/x", ToolRiskCategory.UNRESOLVED_TARGET)
        assertCategory("rm -rf \${DIR:-/tmp}/x", ToolRiskCategory.UNRESOLVED_TARGET)
        assertCategory("dd if=x of=\$TARGET", ToolRiskCategory.UNRESOLVED_TARGET)
        assertCategory("cp /sdcard/x \"\$(cat /tmp/p)/y\"", ToolRiskCategory.UNRESOLVED_TARGET)
    }

    @Test
    fun cdIsTrackedForRelativeTargets() {
        assertCategory(
            "cd /data/adb/modules && echo y > x/service.sh",
            ToolRiskCategory.SYSTEM_DATA_WRITE,
        )
        assertCategory(
            "cd /system/etc && touch hosts",
            ToolRiskCategory.SYSTEM_PARTITION_WRITE,
        )
        // 相对路径但方向是工作区：安全
        assertSafe(
            "cd /data/data/io.github.mangi.eta/files/terminal-user/workspace && rm -rf build",
        )
    }

    @Test
    fun unknownWorkingDirectoryMakesRelativeWritesUnresolved() {
        val verdict = confirm("mkdir -p some/dir")
        assertTrue(ToolRiskCategory.UNRESOLVED_TARGET in verdict.categories)
        assertEquals(ToolRiskLevel.MEDIUM, verdict.level)
    }

    @Test
    fun persistentSessionEscalatesUnresolvedTargets() {
        val withSession = confirm("mkdir -p \$DIR/x", sessionId = "session-1")
        assertEquals(ToolRiskLevel.HIGH, withSession.level)
        assertTrue(
            withSession.findings.any { it.category == ToolRiskCategory.UNRESOLVED_TARGET && it.level == ToolRiskLevel.HIGH },
        )
        // 同一命令在一次性调用里只是 MEDIUM
        assertEquals(ToolRiskLevel.MEDIUM, confirm("mkdir -p \$DIR/x").level)
    }

    // ── 其他危险类别 ─────────────────────────────────────────────────

    @Test
    fun broadRecursiveDeleteRequiresConfirmation() {
        assertCategory("rm -rf /data", ToolRiskCategory.DESTRUCTIVE_DELETE)
        assertCategory("rm -rf /sdcard/", ToolRiskCategory.DESTRUCTIVE_DELETE)
        assertCategory("rm -rf /data/data/*", ToolRiskCategory.DESTRUCTIVE_DELETE)
        assertCategory("rm -rf /", ToolRiskCategory.DESTRUCTIVE_DELETE)
        assertCategory("rm -rf /data/user/0/com.tencent.mobileqq", ToolRiskCategory.FOREIGN_APP_DATA_WRITE)
    }

    @Test
    fun mvSourceIsJudgedAsWriteToo() {
        assertCategory("mv /system/etc/hosts /tmp/", ToolRiskCategory.SYSTEM_PARTITION_WRITE)
        assertCategory("mv /sdcard/x /data/data/com.tencent.mobileqq/files/x", ToolRiskCategory.FOREIGN_APP_DATA_WRITE)
        assertSafe("mv /sdcard/a.txt /sdcard/b.txt")
    }

    @Test
    fun formattingRequiresConfirmation() {
        assertCategory("mkfs.ext4 /dev/block/by-name/userdata", ToolRiskCategory.DISK_FORMAT)
        assertCategory("fdisk /dev/block/mmcblk0", ToolRiskCategory.DISK_FORMAT)
        assertCategory("wipefs -a /dev/block/sda", ToolRiskCategory.DISK_FORMAT)
        assertCategory("fastboot flash boot /sdcard/boot.img", ToolRiskCategory.BOOT_CHAIN_WRITE)
    }

    @Test
    fun magiskPartitionOperationsRequireConfirmation() {
        assertCategory("magisk --install-module /sdcard/mod.zip", ToolRiskCategory.SYSTEM_DATA_WRITE)
        assertCategory("magisk --install /sdcard/boot.img", ToolRiskCategory.BOOT_CHAIN_WRITE)
        assertCategory("magiskboot unpack /sdcard/boot.img", ToolRiskCategory.BOOT_CHAIN_WRITE)
        assertSafe("magisk -v")
    }

    @Test
    fun persistentPropertiesRequireConfirmation() {
        assertCategory("setprop persist.sys.foo 1", ToolRiskCategory.PRIVILEGED_SYSTEM_COMMAND)
        assertCategory("resetprop ro.debuggable 1", ToolRiskCategory.PRIVILEGED_SYSTEM_COMMAND)
        assertSafe("getprop persist.sys.foo")
    }

    @Test
    fun packageMutationRequiresConfirmation() {
        assertCategory("pm uninstall com.android.settings", ToolRiskCategory.SYSTEM_APP_MUTATION)
        assertCategory("pm disable-user --user 0 com.android.bluetooth", ToolRiskCategory.SYSTEM_APP_MUTATION)
        assertCategory("cmd package clear com.tencent.mobileqq", ToolRiskCategory.SYSTEM_APP_MUTATION)
        assertSafe("pm path com.tencent.mobileqq")
    }

    @Test
    fun sensitiveSettingsRequireConfirmation() {
        assertCategory("settings put global adb_enabled 1", ToolRiskCategory.SENSITIVE_SETTING_WRITE)
        assertCategory("settings put secure enabled_accessibility_services x", ToolRiskCategory.SENSITIVE_SETTING_WRITE)
        assertCategory("settings put global http_proxy 10.0.0.1:8080", ToolRiskCategory.SENSITIVE_SETTING_WRITE)
        assertSafe("settings put system screen_brightness 120")
        assertSafe("settings get secure android_id")
    }

    @Test
    fun foreignAppPrivateDirectoriesRequireConfirmation() {
        assertCategory(
            "echo x > /data/data/com.tencent.mobileqq/files/prefs.xml",
            ToolRiskCategory.FOREIGN_APP_DATA_WRITE,
        )
        assertCategory(
            "rm -f /data/user/0/com.tencent.mm/databases/EnMicroMsg.db",
            ToolRiskCategory.FOREIGN_APP_DATA_WRITE,
        )
        assertCategory(
            "chmod 777 /data/data/com.android.chrome/cache",
            ToolRiskCategory.FOREIGN_APP_DATA_WRITE,
        )
    }

    @Test
    fun systemDataWritesRequireConfirmation() {
        assertCategory("sqlite3 /data/system/users/0/settings_secure.xml", ToolRiskCategory.SYSTEM_DATA_WRITE)
        assertCategory("mkdir -p /data/adb/modules/foo", ToolRiskCategory.SYSTEM_DATA_WRITE)
    }

    @Test
    fun unknownCommandsTouchingProtectedPathsFallBackToConfirmation() {
        val verdict = confirm("busybox sh -c \"echo x > /system/build.prop\"")
        assertTrue(ToolRiskCategory.SYSTEM_PARTITION_WRITE in verdict.categories)
        val custom = confirm("my_tool --write /system/build.prop")
        assertTrue(ToolRiskCategory.SYSTEM_PARTITION_WRITE in custom.categories)
        assertEquals(ToolRiskLevel.MEDIUM, custom.level)
    }

    // ── 非 terminal 工具 ────────────────────────────────────────────

    @Test
    fun fileToolsTreatThePathAsWriteTarget() {
        val blocked = evaluate("write_file", """{"path":"/system/etc/hosts","content":"x"}""")
        assertTrue(blocked is ToolRiskVerdict.NeedsConfirm)
        assertEquals(
            ToolRiskCategory.SYSTEM_PARTITION_WRITE,
            (blocked as ToolRiskVerdict.NeedsConfirm).categories.first(),
        )
        val boot = evaluate("edit_file", """{"path":"/boot/boot.img","old_text":"a","new_text":"b"}""")
        assertEquals(
            ToolRiskCategory.BOOT_CHAIN_WRITE,
            (boot as ToolRiskVerdict.NeedsConfirm).categories.first(),
        )
        val safe = evaluate("write_file", """{"path":"/sdcard/Download/note.txt","content":"x"}""")
        assertEquals(ToolRiskVerdict.Safe, safe)
        val readTool = evaluate("read_file", """{"path":"/system/build.prop"}""")
        assertEquals(ToolRiskVerdict.Safe, readTool)
    }

    @Test
    fun fileToolRelativePathUsesCwd() {
        val workspace = "/data/data/io.github.mangi.eta/files/terminal-user/workspace"
        assertEquals(
            ToolRiskVerdict.Safe,
            evaluate("write_file", """{"path":"notes/a.txt","content":"x"}""", defaultCwd = workspace),
        )
        // 相对路径 + 指向受保护目录的 cwd → 必须拦截
        val blocked = evaluate(
            "write_file",
            """{"path":"hosts","content":"x"}""",
            defaultCwd = "/system/etc",
        )
        assertEquals(
            ToolRiskCategory.SYSTEM_PARTITION_WRITE,
            (blocked as ToolRiskVerdict.NeedsConfirm).categories.first(),
        )
        // 显式 cwd 优先于默认值
        val explicit = evaluate(
            "write_file",
            """{"path":"hosts","content":"x","cwd":"/vendor/etc"}""",
            defaultCwd = workspace,
        )
        assertEquals(
            ToolRiskCategory.SYSTEM_PARTITION_WRITE,
            (explicit as ToolRiskVerdict.NeedsConfirm).categories.first(),
        )
    }

    @Test
    fun fileToolRelativePathWithoutCwdIsUnresolved() {
        val verdict = evaluate("write_file", """{"path":"notes/a.txt","content":"x"}""")
        assertTrue(verdict is ToolRiskVerdict.NeedsConfirm)
        assertEquals(
            ToolRiskCategory.UNRESOLVED_TARGET,
            (verdict as ToolRiskVerdict.NeedsConfirm).categories.first(),
        )
    }

    @Test
    fun setSettingToolOnlyBlocksSensitiveKeys() {
        val secure = evaluate("set_setting", """{"namespace":"secure","key":"enabled_accessibility_services","value":"x"}""")
        assertTrue(secure is ToolRiskVerdict.NeedsConfirm)
        val adb = evaluate("set_setting", """{"namespace":"global","key":"adb_enabled","value":"1"}""")
        assertTrue(adb is ToolRiskVerdict.NeedsConfirm)
        val brightness = evaluate("set_setting", """{"namespace":"system","key":"screen_brightness","value":"120"}""")
        assertEquals(ToolRiskVerdict.Safe, brightness)
    }

    @Test
    fun appStateControlBlocksFreezingAndSystemForceStop() {
        val freeze = evaluate("app_state_control", """{"package_name":"com.tencent.mobileqq","action":"freeze"}""")
        assertTrue(freeze is ToolRiskVerdict.NeedsConfirm)
        val systemStop = evaluate("app_state_control", """{"package_name":"com.android.phone","action":"force_stop"}""")
        assertTrue(systemStop is ToolRiskVerdict.NeedsConfirm)
        val userStop = evaluate("app_state_control", """{"package_name":"com.tencent.mobileqq","action":"force_stop"}""")
        assertEquals(ToolRiskVerdict.Safe, userStop)
    }

    @Test
    fun blankCommandAndUnknownToolsAreSafe() {
        assertEquals(ToolRiskVerdict.Safe, terminal(""))
        assertEquals(ToolRiskVerdict.Safe, terminal("   "))
        assertEquals(ToolRiskVerdict.Safe, evaluate("get_current_context", "{}"))
        assertFalse(terminal("ls /").toString().contains("NeedsConfirm"))
    }


    // ── 安全审查 payload 逐条回归 ─────────────────────────────────────

    @Test
    fun reviewPayloadIndirectExecutionIsNeverSafe() {
        // B1 原文 payload
        val payloads = listOf(
            "printf 'echo x > /system/build.prop' | sh",
            "sh /tmp/x.sh",
            "echo /data/adb/modules/x | xargs rm -rf",
            "find /data/adb -name '*.sh' -exec rm {} \\;",
            "base64 -d /sdcard/payload.b64 | sh",
        )
        payloads.forEach { payload ->
            val verdict = terminal(payload)
            assertTrue("payload 不能判 Safe：$payload", verdict is ToolRiskVerdict.NeedsConfirm)
        }
        // 管道送进 shell 时，能静态读到的内容要按写判定升级
        val piped = confirm("printf 'dd if=x of=/dev/block/by-name/boot' | sh")
        assertTrue(ToolRiskCategory.BOOT_CHAIN_WRITE in piped.categories)
    }

    @Test
    fun reviewPayloadVariableAndRelativeWritesAreIntercepted() {
        // B2 原文 payload
        assertCategory(
            "P=/data/adb/modules/x; mkdir -p \$P",
            ToolRiskCategory.SYSTEM_DATA_WRITE,
        )
        assertCategory(
            "cd /data/adb/modules && echo y > x/service.sh",
            ToolRiskCategory.SYSTEM_DATA_WRITE,
        )
        assertCategory(
            "P=/dev/block/by-name/boot; dd if=/sdcard/boot.img of=\$P",
            ToolRiskCategory.BOOT_CHAIN_WRITE,
        )
        // 持久会话里同样的 session_id 调用必须是 HIGH（必拦）
        val sessionVerdict = terminal(
            "mkdir -p \$P/service.d",
            sessionId = "session-1",
        )
        assertTrue(sessionVerdict is ToolRiskVerdict.NeedsConfirm)
        assertEquals(ToolRiskLevel.HIGH, (sessionVerdict as ToolRiskVerdict.NeedsConfirm).level)
    }

    @Test
    fun reviewPayloadAbsoluteMagiskModulePathStaysBlocked() {
        // /data/adb/modules 下的脚本会被 Magisk 在开机阶段执行，属于启动链风险面
        assertCategory("echo y > /data/adb/modules/x/service.sh", ToolRiskCategory.SYSTEM_DATA_WRITE)
        assertCategory("chmod 755 /data/adb/service.d/boot.sh", ToolRiskCategory.SYSTEM_DATA_WRITE)
    }

    @Test
    fun toolRiskGateUsesTerminalJobAndDaemonCommandsToo() {
        for (tool in listOf("terminal_job", "terminal_daemon")) {
            val verdict = terminal("dd if=x of=/dev/block/by-name/boot", tool = tool)
            assertTrue("$tool 应命中启动链写入", verdict is ToolRiskVerdict.NeedsConfirm)
        }
    }

    @Test
    fun `creating a scheduled task needs confirmation`() {
        // 审查 M3①：创建自动任务把指令持久化进系统并无人值守执行，必须让用户看到内容。
        val verdict = AgentToolRiskEvaluator.evaluate(
            "schedule_task",
            JSONObject()
                .put("action", "create")
                .put("title", "夜间整理")
                .put("prompt", "每晚清理下载目录并汇报")
                .put(
                    "schedule",
                    JSONObject().put("kind", "daily").put("hour", 23).put("minute", 30),
                ),
        )
        assertTrue(verdict is ToolRiskVerdict.NeedsConfirm)
        verdict as ToolRiskVerdict.NeedsConfirm
        assertEquals(ToolRiskLevel.MEDIUM, verdict.level)
        assertEquals(listOf(ToolRiskCategory.AGENT_SCHEDULED_TASK_CREATE), verdict.categories)
        assertEquals("夜间整理", verdict.subject)
    }

    @Test
    fun `reading or deleting scheduled tasks does not need confirmation`() {
        listOf("list", "enable", "disable", "delete", "run_now").forEach { action ->
            assertEquals(
                "action=$action must stay safe",
                ToolRiskVerdict.Safe,
                AgentToolRiskEvaluator.evaluate("schedule_task", JSONObject().put("action", action).put("id", "x")),
            )
        }
    }

    @Test
    fun `schedule create without prompt is not gated`() {
        // prompt 为空时工具自己会拒绝；判定器不制造额外噪音。
        assertEquals(
            ToolRiskVerdict.Safe,
            AgentToolRiskEvaluator.evaluate("schedule_task", JSONObject().put("action", "create")),
        )
    }
}
