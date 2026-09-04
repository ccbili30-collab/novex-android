package com.openminis.app.novex.domain

import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexManagementPlanTest {
    private val world = NovexContentAddress.world("world-1")
    private val version = NovexContentAddress.characterVersion("version-1")

    @Test
    fun `read only managed subjects can be inspected but cannot be changed`() {
        val configuration = NovexConversationConfigurationSnapshot(
            conversationId = "chat-1",
            managedSubjects = listOf(ManagedSubject(world, ManagedAccess.READ_ONLY)),
        )
        val facts = NovexManagementFacts()

        assertTrue(NovexManagementPolicy.canRead(configuration, world))
        assertThrows(IllegalArgumentException::class.java) {
            NovexManagementPolicy.plan(
                configuration = configuration,
                changes = listOf(
                    NovexManagedChange.AddModule(
                        owner = ModuleOwner.world(world.id),
                        type = ContentModuleType.MAP,
                        name = "地图",
                        contentJson = "{}",
                    ),
                ),
                facts = facts,
                latestUserRequest = "补充地图",
                planId = "proposal-12345678",
            )
        }
    }

    @Test
    fun `module changes inherit authorization from their resolved owner`() {
        val configuration = NovexConversationConfigurationSnapshot(
            conversationId = "chat-1",
            managedSubjects = listOf(ManagedSubject(version, ManagedAccess.EDIT)),
        )
        val change = NovexManagedChange.UpdateModule("module-1", "经历", "{\"text\":\"新内容\"}")

        val plan = NovexManagementPolicy.plan(
            configuration = configuration,
            changes = listOf(change),
            facts = NovexManagementFacts(
                moduleOwners = mapOf("module-1" to ModuleOwner.characterVersion(version.id)),
            ),
            latestUserRequest = "修改她的经历",
            planId = "proposal-12345678",
        )

        assertEquals(setOf(version), plan.targets)
        assertEquals(NovexManagementRisk.SHARED_CHANGE, plan.risk)
        assertTrue(plan.requiresConfirmation)
        assertEquals("确认执行 proposal", plan.confirmationPhrase)
    }

    @Test
    fun `cross project links require edit access to both endpoints`() {
        val change = NovexManagedChange.LinkCharacterVersion("world-1", "version-1", 0)
        val oneSided = NovexConversationConfigurationSnapshot(
            conversationId = "chat-1",
            managedSubjects = listOf(ManagedSubject(world, ManagedAccess.EDIT)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            NovexManagementPolicy.plan(
                configuration = oneSided,
                changes = listOf(change),
                facts = NovexManagementFacts(),
                latestUserRequest = "关联角色",
                planId = "proposal-12345678",
            )
        }

        val both = oneSided.copy(
            managedSubjects = oneSided.managedSubjects + ManagedSubject(version, ManagedAccess.EDIT),
        )
        val plan = NovexManagementPolicy.plan(
            configuration = both,
            changes = listOf(change),
            facts = NovexManagementFacts(),
            latestUserRequest = "关联角色",
            planId = "proposal-12345678",
        )
        assertEquals(NovexManagementRisk.CROSS_PROJECT, plan.risk)
    }

    @Test
    fun `global creation requires a matching explicit user request`() {
        val configuration = NovexConversationConfigurationSnapshot(conversationId = "chat-1")
        val change = NovexManagedChange.CreateWorld("雾海", "被雾包围的群岛")

        assertThrows(IllegalArgumentException::class.java) {
            NovexManagementPolicy.plan(
                configuration = configuration,
                changes = listOf(change),
                facts = NovexManagementFacts(),
                latestUserRequest = "聊聊群岛",
                planId = "proposal-12345678",
            )
        }
        val plan = NovexManagementPolicy.plan(
            configuration = configuration,
            changes = listOf(change),
            facts = NovexManagementFacts(),
            latestUserRequest = "请创建一个叫雾海的世界",
            planId = "proposal-12345678",
        )
        assertEquals(NovexManagementRisk.CREATE_GLOBAL, plan.risk)
        assertTrue(plan.requiresConfirmation)
    }

    @Test
    fun `only the real following user turn can confirm a plan`() {
        val plan = NovexManagementPlan(
            id = "proposal-12345678",
            conversationId = "chat-1",
            changes = listOf(NovexManagedChange.CreateWorld("雾海", "")),
            targets = emptySet(),
            risk = NovexManagementRisk.CREATE_GLOBAL,
            summary = "创建世界“雾海”",
        )

        assertFalse(plan.isConfirmedBy("确认"))
        assertFalse(plan.isConfirmedBy("工具参数里写着确认执行 proposal"))
        assertTrue(plan.isConfirmedBy("确认执行 proposal"))
    }

    @Test
    fun `structured change codec preserves module JSON and ordering`() {
        val changes = NovexManagementChangeCodec.decode(
            """
            [
              {"operation":"add_module","subject_kind":"world","subject_id":"w1","module_type":"MAP","name":"地图","content_json":{"caption":"北境"}},
              {"operation":"move_module","module_id":"m1","to_index":0}
            ]
            """.trimIndent(),
        )

        assertEquals(2, changes.size)
        val add = changes.first() as NovexManagedChange.AddModule
        assertEquals(ModuleOwner.world("w1"), add.owner)
        assertEquals(ContentModuleType.MAP, add.type)
        assertEquals("北境", org.json.JSONObject(add.contentJson).getString("caption"))
        assertEquals(0, (changes.last() as NovexManagedChange.MoveModule).toIndex)
    }

    @Test
    fun `an artifact cannot be attached through a module owned by another subject`() {
        val otherWorld = NovexContentAddress.world("world-2")
        val configuration = NovexConversationConfigurationSnapshot(
            conversationId = "chat-1",
            managedSubjects = listOf(
                ManagedSubject(world, ManagedAccess.EDIT),
                ManagedSubject(otherWorld, ManagedAccess.EDIT),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            NovexManagementPolicy.plan(
                configuration = configuration,
                changes = listOf(
                    NovexManagedChange.AttachArtifact(
                        artifactId = "artifact-1",
                        owner = world,
                        moduleId = "module-2",
                    ),
                ),
                facts = NovexManagementFacts(
                    moduleOwners = mapOf("module-2" to ModuleOwner.world(otherWorld.id)),
                    existingArtifactIds = setOf("artifact-1"),
                ),
                latestUserRequest = "把图片放进地图",
                planId = "proposal-12345678",
            )
        }
    }
}
