package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.terminal.TerminalRuntime
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * GUI 宏：命名保存的 gesture_batch 动作序列，支持 {{参数}} 占位。
 * 文件为 JSON：{"name":"...","description":"...","actions":[...]}，与 gesture_batch 的 actions 完全一致。
 */
internal object GuiMacroStore {
    private fun dir(): File = File(TerminalRuntime.userWorkspacePath, "gui-macros")

    private fun file(name: String): File {
        val normalized = name.trim().lowercase(Locale.ROOT)
        require(normalized.matches(Regex("[a-z0-9_-]{1,64}"))) { "宏名仅允许小写字母、数字、下划线、连字符" }
        val root = dir().canonicalFile
        val target = File(root, "$normalized.json").canonicalFile
        require(target.parentFile == root) { "非法宏路径" }
        return target
    }

    fun list(): JSONArray {
        val files = dir().listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name } ?: emptyList()
        return JSONArray().apply {
            files.forEach { file ->
                val doc = runCatching { JSONObject(file.readText()) }.getOrNull()
                put(
                    JSONObject()
                        .put("name", file.nameWithoutExtension)
                        .put("description", doc?.optString("description").orEmpty())
                        .put("actions", doc?.optJSONArray("actions")?.length() ?: 0),
                )
            }
        }
    }

    const val MAX_ACTIONS = 50

    fun save(name: String, description: String, actions: JSONArray, overwrite: Boolean = false): JSONObject {
        require(actions.length() in 1..MAX_ACTIONS) { "宏动作数需为 1..$MAX_ACTIONS" }
        val target = file(name)
        require(overwrite || !target.exists()) { "宏已存在；确认覆盖时传 overwrite=true" }
        target.parentFile?.mkdirs()
        val doc = JSONObject()
            .put("name", name.trim().lowercase(Locale.ROOT))
            .put("description", description)
            .put("actions", actions)
        target.writeText(doc.toString(2))
        return JSONObject().put("ok", true).put("name", doc.getString("name"))
            .put("path", target.absolutePath).put("actions", actions.length())
    }

    fun load(name: String): JSONObject? {
        val target = file(name)
        if (!target.isFile) return null
        return runCatching { JSONObject(target.readText()) }.getOrNull()
    }

    fun delete(name: String): Boolean = file(name).delete()

    /** 递归替换字符串值中的 {{key}}；缺失参数在替换前检测，显式 null 视为缺失。 */
    fun substitute(actions: JSONArray, params: JSONObject): JSONArray {
        fun render(text: String): String {
            val required = Regex("\\{\\{([^{}]+)}}").findAll(text).map { it.groupValues[1].trim() }.toSet()
            required.forEach { key ->
                require(params.has(key) && !params.isNull(key)) { "缺少宏参数：$key" }
            }
            var result = text
            required.forEach { key ->
                result = result.replace("{{$key}}", params.opt(key)?.toString().orEmpty())
            }
            return result
        }
        fun walk(value: Any?): Any? = when (value) {
            is String -> render(value)
            is JSONObject -> {
                val out = JSONObject()
                value.keys().forEach { key -> out.put(key, walk(value.opt(key))) }
                out
            }
            is JSONArray -> JSONArray().apply {
                for (i in 0 until value.length()) put(walk(value.opt(i)))
            }
            else -> value
        }
        return walk(actions) as JSONArray
    }
}
