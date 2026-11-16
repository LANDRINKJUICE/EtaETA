package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentToolSchema {
    fun coordinateSpace(): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("enum", JSONArray().put("normalized").put("screen").put("screenshot"))
            .put(
                "description",
                "必填。normalized（推荐）：x、y 取 0–999，相对整块屏幕的比例，看截图时用它，不受图片缩放影响；" +
                    "screen：真实屏幕像素，ui_nodes 的 center/bounds 就是这个坐标；" +
                    "screenshot：最近一次附图的原始像素，仅当你确定看到的是未缩放原图时使用。",
            )

    fun function(
        name: String,
        description: String,
        parameters: JSONObject,
    ): JSONObject {
        // 给所有工具统一注入一个可选「意图」参数：模型调可能触发危险确认卡的工具时，
        // 可用一句简短中文说明「为什么想做它」，闸门会把这句显示在确认卡的命令原文上方。
        // 统一在唯一 schema 入口注入，避免逐个 Catalog 漏掉某个会命中闸门的工具
        // （闸门按命令内容判定，静态列不全）。可选、执行器用 optString 忽略未知参数，
        // 模型不填就退回现状，零副作用。见 AgentToolApprovalGate.check。
        parameters.optJSONObject("properties")?.put(
            "intent",
            JSONObject()
                .put("type", "string")
                // 短文案：每个工具都会注入，写太长会给每次请求平添数千 tokens 噪音。
                // 细节由系统提示词的「危险操作确认」段讲（那里只出现一次）。
                .put("description", "可选：一句话说明目的，会显示在确认卡上。"),
        )
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters),
            )
    }
}
