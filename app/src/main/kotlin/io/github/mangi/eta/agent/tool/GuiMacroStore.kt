package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.terminal.TerminalRuntime
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * GUI 宏：命名保存的 gesture_batch 动作序列，支持 {{参数}} 占位。
 * 文件为 JSON：{"name":"...","description":"...","actions":[...]}，与 gesture_batch 的 actions 完全一致。
 */
internal object GuiMacroStore {
    private fun dir(): File = File(TerminalRuntime.userWorkspacePath, "gui-macros")

    private fun file(name: String): File {
        val normalized = name.trim().lowercase()
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

    fun save(name: String, description: String, actions: JSONArray): JSONObject {
        require(actions.length() in 1..100) { "宏动作数需为 1..100" }
        val target = file(name)
        target.parentFile?.mkdirs()
        val doc = JSONObject()
            .put("name", name.trim().lowercase())
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

    fun delete(name: String): Boolean = runCatching { file(name).delete() }.getOrDefault(false)

    /** 递归替换字符串值中的 {{key}}；params 只接受字符串/数字/布尔，值原样字符串化。 */
    fun substitute(actions: JSONArray, params: JSONObject): JSONArray {
        fun render(text: String): String {
            var result = text
            params.keys().forEach { key ->
                result = result.replace("{{$key}}", params.opt(key)?.toString().orEmpty())
            }
            if (result.contains("{{")) {
                val missing = Regex("\\{\\{([^{}]+)}}").find(result)?.groupValues?.get(1)
                require(missing == null) { "缺少宏参数：$missing" }
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
