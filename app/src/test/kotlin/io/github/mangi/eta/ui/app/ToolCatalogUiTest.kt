package io.github.mangi.eta.ui.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.TravelExplore
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.toolDisplayNameResource
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.components.iconForTool
import io.github.mangi.eta.ui.model.projectToolGroups
import io.github.mangi.eta.ui.model.toolCardRequirement
import io.github.mangi.eta.ui.screens.mcp.collapseDescriptionPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ToolCatalogUiTest {
    @Test
    fun everyRuntimeToolAndDisplayedCardHasASpecificIcon() {
        val tools = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            memoryTools = true,
            capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
        )
        val runtimeNames = (0 until tools.length()).map {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        }
        val cardIds = buildToolsState(RuntimeEnvironment.getApplication()).groups
            .flatMap { it.tools }.map { it.id }
        (runtimeNames + cardIds).distinct().forEach { name ->
            assertNotEquals(name, Icons.Rounded.Build, iconForTool(name))
        }
    }

    /**
     * 能力页覆盖断言：模型目录里的每个运行时工具，要么在能力页有一张卡，要么在
     * 白名单里显式声明"不进能力页"的理由。
     *
     * 没有这条断言时，"登记了 schema 却忘了加能力页卡片"全绿——功能模型能用、用户却
     * 看不见也管不了。这里把约束做成红灯，等价于通知层 NotificationIdRegistry 的
     * "登记表 + 撞段单测"。比较前先按 Prefs.canonicalToolSwitchId 归一：tap /
     * long_press_element / scroll_element / wait / wait_for_package 这类运行时变体都
     * 映射到能力页的主卡（tap_area / long_press / scroll / wait_for_text），不归一必误报。
     */
    @Test
    fun everyRuntimeToolHasACapabilityCardOrAnExplicitExemption() {
        // 当前所有 flag 显式写 true、不依赖默认值；新增 flag 时必须同步本处，否则其下
        // 工具不会进入 runtimeNames、会静默逃过本断言。capabilities 保持"最多工具"方向。
        val tools = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceDirectTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            memoryTools = true,
            memoryWritable = true,
            selfTools = true,
            conversationTools = true,
            proactiveNotify = true,
            jobWakeup = true,
            localWebSearch = true,
            capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
            disabledTools = emptySet(),
        )
        val runtimeNames = toolNames(tools)
        val cardIds = buildToolsState(RuntimeEnvironment.getApplication()).groups
            .flatMap { it.tools }.map { it.id }

        // 白名单：不进能力页卡片的运行时工具，逐个写明理由。
        // 纪律：只准列"另有独立页面 / 纯只读无用户可管状态"的；把"顺手漏的 UI"塞进来
        // 会让断言失去意义。这 6 个 skills_* 同属技能域、由 AppRoute.Skills 技能管理页
        // 承接（列表、启停、导入 zip、卸载、重装），都是对"已安装技能"的查询或由 Agent
        // 在对话里发起的安装/发现动作，没有需要用户在能力页逐条开关的独立状态；
        // 在能力页再放一组卡会重复 UI，且扰乱 canonical 折叠后的卡片计数语义。
        // 注：能力页目前确实没有为 skills_* 提供逐工具开关，这是已知待补项（见当轮交接）。
        val capabilityPageExemptions = mapOf(
            "skills_list" to "查询已安装技能；由 AppRoute.Skills 管理页以列表呈现，无逐工具开关",
            "skills_read" to "读取已安装技能正文（SKILL.md）；纯只读，无用户可管状态",
            "skills_read_resource" to "读取技能附带资源；纯只读，无用户可管状态",
            "skills_list_curated" to "浏览可安装技能目录；由 Agent 在对话中发起，无独立开关",
            "skills_inspect_github" to "查看 GitHub 仓库里的技能；由 Agent 在对话中发起，无独立开关",
            "skills_install_from_github" to "安装技能；结果落在 AppRoute.Skills 管理页，由该页统一启停/卸载",
        )
        assertTrue(
            "白名单每个名字都必须有非空理由",
            capabilityPageExemptions.values.none { it.isBlank() },
        )
        fun canonical(name: String) = Prefs.canonicalToolSwitchId(name)
        // 白名单只准登记主开关名本名：登记变体名（如 tap→tap_area）会连带抑制其主卡的缺卡检查。
        assertTrue(
            "白名单只准登记主开关名本名：${capabilityPageExemptions.keys.filter { canonical(it) != it }}",
            capabilityPageExemptions.keys.all { canonical(it) == it },
        )
        val canonicalRuntime = runtimeNames.mapTo(linkedSetOf()) { canonical(it) }
        val canonicalCards = cardIds.mapTo(linkedSetOf()) { canonical(it) }
        // 白名单键也归一，与两边对称：将来若登记的是变体名，不会因未归一而误判为 stale。
        val exemptionIds = capabilityPageExemptions.keys.mapTo(linkedSetOf()) { canonical(it) }

        // 核心：登记了工具、能力页却没有卡片 → 红灯（除非在白名单里显式声明理由）。
        val uncovered = canonicalRuntime - canonicalCards - exemptionIds
        assertTrue(
            "能力页缺少这些运行时工具的卡片；请补 UI 卡，或在白名单里显式声明理由并写清归属：$uncovered",
            uncovered.isEmpty(),
        )
        // 白名单不许膨胀/过时：每个名字必须确实是"模型目录里有、能力页没有"的工具。
        val stale = exemptionIds - canonicalRuntime
        assertTrue("白名单里这些名字已不在运行时目录，请清理：$stale", stale.isEmpty())
        val superfluous = exemptionIds.intersect(canonicalCards)
        assertTrue("白名单里这些名字其实已有能力页卡片，请从白名单移除：$superfluous", superfluous.isEmpty())
    }

    private fun toolNames(tools: org.json.JSONArray): List<String> =
        (0 until tools.length()).map {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        }

    @Test
    fun hostedSearchNamesAndDynamicMcpNamesUseTheirToolIcons() {
        assertEquals(Icons.Rounded.TravelExplore, iconForTool("网页搜索"))
        assertEquals(iconForTool("网页搜索"), iconForTool("web_search"))
        assertEquals(Icons.Rounded.Language, iconForTool("browser_use"))
        assertEquals(Icons.AutoMirrored.Rounded.Article, iconForTool("fetch_url"))
        assertEquals(Icons.Rounded.Extension, iconForTool("mcp_server_search_012345"))
        assertEquals(Icons.Rounded.Build, iconForTool("unknown_tool"))
    }

    @Test
    fun publicWebCardsHaveNamesAndStayInTheWebGroup() {
        val web = buildToolsState(RuntimeEnvironment.getApplication()).groups.first { it.id == "web" }
        assertTrue(web.tools.map { it.id }.containsAll(listOf("web_search", "fetch_url", "browser_use")))
        assertEquals(R.string.tool_web_search, toolDisplayNameResource("web_search"))
        assertEquals(R.string.tool_fetch_url, toolDisplayNameResource("fetch_url"))
    }

    @Test
    fun terminalToolsExposeSeparateLifecyclesInTheSameGroup() {
        val terminal = buildToolsState(RuntimeEnvironment.getApplication()).groups.first { it.id == "terminal" }
        assertTrue(terminal.tools.map { it.id }.containsAll(listOf("terminal", "terminal_job", "terminal_daemon")))
        assertEquals(R.string.tool_terminal_job, toolDisplayNameResource("terminal_job"))
        assertEquals(R.string.tool_terminal_daemon, toolDisplayNameResource("terminal_daemon"))
    }

    @Test
    fun personalQueriesAndNativeOperationsHaveSeparateGroups() {
        val group = buildToolsState(RuntimeEnvironment.getApplication()).groups.first { it.id == "personal_data" }
        assertTrue(group.tools.any { it.id == "search_bills" })
        assertTrue(group.tools.none { it.id == "personal_context" || it.id == "create_note" })
        val operations = buildToolsState(RuntimeEnvironment.getApplication()).groups.first { it.id == "device_direct" }
        assertTrue(operations.tools.any { it.id == "set_flashlight" })
        assertTrue(operations.tools.any { it.id == "create_calendar_event" })
        assertEquals(RootRequirement.REQUIRED, toolCardRequirement("search_bills").rootRequirement)
        assertTrue(toolCardRequirement("search_bills").colorOs)
    }

    @Test
    fun everyDisplayedCardHasExplicitMetadataAndKeepsItsOriginalOrder() {
        val groups = buildToolsState(RuntimeEnvironment.getApplication()).groups
        val allCards = groups.flatMap { it.tools }
        allCards.forEach { toolCardRequirement(it.id) }
        assertEquals(allCards.size, allCards.map { it.id }.distinct().size)
        assertEquals(groups, projectToolGroups(groups, true, false, false))
        assertEquals(groups, projectToolGroups(groups, false, true, true))

        val ordinaryCards = projectToolGroups(groups, false, false, false).flatMap { it.tools }
        assertTrue(ordinaryCards.isNotEmpty())
        assertEquals(allCards.filter { card ->
            val requirement = toolCardRequirement(card.id)
            requirement.rootRequirement != RootRequirement.REQUIRED && !requirement.colorOs
        }, ordinaryCards)
    }

    @Test
    fun disabledToolsAreOmittedFromModelCatalogAndPersistInPrefs() {
        val context = RuntimeEnvironment.getApplication()
        Prefs.initLocal(context)
        Prefs.setToolsEnabled(listOf("read_sms_code", "tap_area"), false)
        assertFalse(Prefs.isToolEnabled("read_sms_code"))
        assertFalse(Prefs.isToolEnabled("tap"))
        assertFalse(Prefs.isToolEnabled("tap_area"))
        assertTrue(Prefs.isToolEnabled("list_alarms"))

        val tools = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceDirectTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
            disabledTools = Prefs.disabledTools(),
        )
        val names = (0 until tools.length()).map {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        }.toSet()
        assertFalse("read_sms_code" in names)
        assertFalse("tap" in names)
        assertFalse("tap_area" in names)
        assertTrue("list_alarms" in names)
        assertTrue("tap_element" in names)

        Prefs.setToolsEnabled(listOf("read_sms_code", "tap_area"), true)
        assertTrue(Prefs.isToolEnabled("read_sms_code"))
        assertTrue(Prefs.isToolEnabled("tap"))
    }

    @Test
    fun mcpDescriptionPreviewCollapsesHeadingsAndBulletLines() {
        val raw = """
            # Search Tool
            - First capability
            * Second capability
        """.trimIndent()
        assertEquals(
            "Search Tool · First capability · Second capability",
            collapseDescriptionPreview(raw),
        )
    }
}
