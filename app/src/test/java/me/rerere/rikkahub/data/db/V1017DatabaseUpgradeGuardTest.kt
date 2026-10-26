package me.rerere.rikkahub.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v1017: user explicitly approved database 31 -> 32; prevent omitting the migration. */
class V1017DatabaseUpgradeGuardTest {
    private fun projectRoot(): File {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(directory, "app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt").isFile) {
                return directory
            }
            directory = directory.parentFile ?: error("Cannot locate project root")
        }
        error("Cannot locate project root")
    }

    private fun source(relative: String): String = File(projectRoot(), relative).readText()

    @Test
    fun `database version is the authorized 32`() {
        val database = source("app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt")
        assertTrue(Regex("version\\s*=\\s*32\\s*,").containsMatchIn(database))
    }

    @Test
    fun `automatic migration from 31 to 32 is explicitly registered`() {
        val database = source("app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt")
        assertTrue(Regex("AutoMigration\\(from\\s*=\\s*31,\\s*to\\s*=\\s*32\\)").containsMatchIn(database))
    }

    @Test
    fun `both old and new schema contracts are retained`() {
        val schemaDirectory = File(projectRoot(), "app/schemas/me.rerere.rikkahub.data.db.AppDatabase")
        assertTrue("Old schema is required for Room migration generation", File(schemaDirectory, "31.json").isFile)
        assertTrue("New schema must not be forgotten", File(schemaDirectory, "32.json").isFile)
        val schema = File(schemaDirectory, "32.json").readText()
        assertTrue(schema.contains("\"columnName\": \"ssh_config\""))
        assertTrue(schema.contains("\"defaultValue\": \"'LOCAL'\""))
    }

    @Test
    fun `old workspace rows get local type and nullable remote settings`() {
        val entity = source("app/src/main/java/me/rerere/rikkahub/data/db/entity/WorkspaceEntity.kt")
        assertTrue(entity.contains("@ColumnInfo(\"type\", defaultValue = \"LOCAL\")"))
        assertTrue(entity.contains("val type: String = WorkspaceType.LOCAL.name"))
        assertTrue(entity.contains("val sshConfig: String? = null"))
    }

    @Test
    fun `agent database stays at version 3`() {
        val agent = source("app/src/main/java/me/rerere/rikkahub/agent/db/AgentDatabase.kt")
        val version = Regex("version\\s*=\\s*(\\d+)").find(agent)?.groupValues?.get(1)
        assertEquals("3", version)
    }
}
