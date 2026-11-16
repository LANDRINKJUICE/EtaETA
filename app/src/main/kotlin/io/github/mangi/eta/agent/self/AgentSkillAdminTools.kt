package io.github.mangi.eta.agent.self

import android.content.Context
import io.github.mangi.eta.agent.skill.SkillRuntime
import org.json.JSONArray
import org.json.JSONObject

/** Agent 对已安装 Skills 的启停与卸载；安装仍走既有 skills_install_from_github。 */
internal object AgentSkillAdminTools {

    val TOOL_NAMES = setOf(
        "eta_skill_list",
        "eta_skill_set_enabled",
        "eta_skill_delete",
    )

    fun execute(name: String, args: JSONObject, context: Context): String = try {
        when (name) {
            "eta_skill_list" -> skillList(context)
            "eta_skill_set_enabled" -> skillSetEnabled(args, context)
            "eta_skill_delete" -> skillDelete(args, context)
            else -> error("未知技能管理工具：$name")
        }.toString()
    } catch (failure: Exception) {
        if (failure is InterruptedException || failure is kotlinx.coroutines.CancellationException) throw failure
        JSONObject()
            .put("ok", false)
            .put("code", "SKILL_TOOL_ERROR")
            .put("message", failure.message ?: failure.javaClass.simpleName)
            .toString()
    }

    private fun service(context: Context) = SkillRuntime.createIndexService(context)

    private fun skillList(context: Context): JSONObject {
        val entries = service(context).listSkillsForManagement().filter { it.installed }
        return JSONObject()
            .put("ok", true)
            .put(
                "skills",
                JSONArray().apply {
                    entries.forEach { entry ->
                        put(
                            JSONObject()
                                .put("id", entry.id)
                                .put("name", entry.name)
                                .put("enabled", entry.enabled)
                                .put("description", entry.description),
                        )
                    }
                },
            )
    }

    private fun skillSetEnabled(args: JSONObject, context: Context): JSONObject {
        val entry = service(context).setSkillEnabled(
            args.getString("id"),
            args.getBoolean("enabled"),
        )
        return JSONObject()
            .put("ok", true)
            .put("id", entry.id)
            .put("enabled", entry.enabled)
    }

    private fun skillDelete(args: JSONObject, context: Context): JSONObject {
        val id = args.getString("id")
        val deleted = service(context).deleteSkill(id)
        return JSONObject()
            .put("ok", deleted)
            .put("deleted", if (deleted) id else JSONObject.NULL)
            .put("message", if (deleted) "已卸载" else "未找到可卸载的 skill：$id")
    }
}
