package com.openminis.app.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexRawToolRetirementTest {
    @Test
    fun `new model requests never expose raw shell or device path file tools`() {
        val names = AgentTools.makeAgentTools(workspaceAvailable = true).mapTo(mutableSetOf()) { it.name }

        assertFalse("不能向新请求暴露原始命令行工具", "shell_execute" in names)
        assertFalse("不能向新请求暴露设备路径读取工具", "file_read" in names)
        assertFalse("不能向新请求暴露设备路径写入工具", "file_write" in names)
        assertFalse("不能向新请求暴露设备路径编辑工具", "file_edit" in names)
        assertTrue("受控工作区读取能力必须保留", "workspace_read" in names)
        assertTrue("受控工作区写入能力必须保留", "workspace_write" in names)
        assertTrue("受控工作区编辑能力必须保留", "workspace_edit" in names)
        assertTrue("受限计算能力必须保留", "workspace_compute" in names)
        assertTrue("图片读取必须改用成果编号而不是被删除", ReadImageTool.NAME in names)
    }

    @Test
    fun `provider schemas do not teach models minis device paths`() {
        val definitions = AgentTools.makeAgentTools(
            workspaceAvailable = true,
            imageGenerationConfigured = true,
        )
        val schemaText = definitions
            .joinToString("\n") { definition ->
                buildString {
                    append(definition.name).append(' ').append(definition.description)
                    definition.parameters.forEach { (name, parameter) ->
                        append(' ').append(name).append(' ').append(parameter.description)
                    }
                }
            }

        assertFalse(schemaText.contains("/var/minis/"))
        assertFalse(schemaText.contains("minis://workspace"))
        assertFalse(schemaText.contains("database", ignoreCase = true))
        val imageTool = definitions.single { it.name == GenerateImageTool.NAME }
        assertTrue(imageTool.parameters.containsKey("reference_artifact_id"))
        assertFalse(imageTool.parameters.containsKey("reference_image_path"))
        val imageReader = definitions.single { it.name == ReadImageTool.NAME }
        assertTrue(imageReader.parameters.containsKey("artifact_id"))
        assertFalse(imageReader.parameters.containsKey("path"))
    }

    @Test
    fun `text only models retain safe image inspection through a configured vision group`() {
        assertFalse(
            AgentTools.makeAgentTools(
                supportsImageInput = false,
                visionGroupConfigured = false,
            ).any { it.name == ReadImageTool.NAME },
        )
        val imageReader = AgentTools.makeAgentTools(
            supportsImageInput = false,
            visionGroupConfigured = true,
        ).single { it.name == ReadImageTool.NAME }
        assertTrue(imageReader.parameters.containsKey("artifact_id"))
        assertFalse(imageReader.parameters.containsKey("path"))
    }
}
