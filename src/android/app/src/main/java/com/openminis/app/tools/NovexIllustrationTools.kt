package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam

object NovexIllustrationTools {
    const val INSPECT = "inspect_story_images"
    const val SELECT = "select_story_image"
    val names = setOf(INSPECT, SELECT)
    fun definitions() = listOf(
        AgentToolDefinition(INSPECT, "分页查看当前对话已采用、允许展示的模块图片。返回短名称、说明及图片编号，不读取图片像素。关闭的世界书、管理区未采用的图片不在此范围。", mapOf("offset" to AgentToolParam("integer", "从第几项开始，默认 0"), "limit" to AgentToolParam("integer", "每页数量，1 到 50，默认 30")), required = emptyList()),
        AgentToolDefinition(SELECT, "为本次完成的回答选择一张已有剧情插图。先查看可用图片，再按语境选编号；不用拼接网址或在正文写内部编号。仅选择、不推进剧情；回答正常结束后展示并随消息保存，中断不展示。", mapOf(
            "image_id" to AgentToolParam("string", "查看可用图片返回的编号")), required = listOf("image_id")),
    )
}
