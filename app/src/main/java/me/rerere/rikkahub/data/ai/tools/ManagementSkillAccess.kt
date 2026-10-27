package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.data.skills.SkillMetadata
import java.io.File

/** Narrow file-management boundary; permits testing tools without an Android Context. */
internal interface ManagementSkillAccess {
    fun listSkills(): List<SkillMetadata>
    fun findSkill(name: String): SkillMetadata?
    fun getSkillsDir(): File
    fun saveSkill(name: String, content: String): SkillMetadata?
    fun saveSkillFile(name: String, path: String, content: String): Boolean
    suspend fun deleteSkill(skill: SkillMetadata): Boolean
}

internal fun SkillManager.managementAccess() = object : ManagementSkillAccess {
    override fun listSkills() = this@managementAccess.listSkills()
    override fun findSkill(name: String) = this@managementAccess.findSkill(name)
    override fun getSkillsDir() = this@managementAccess.getSkillsDir()
    override fun saveSkill(name: String, content: String) = this@managementAccess.saveSkill(name, content)
    override fun saveSkillFile(name: String, path: String, content: String) = this@managementAccess.saveSkillFile(name, path, content)
    override suspend fun deleteSkill(skill: SkillMetadata) = this@managementAccess.deleteSkill(skill)
}
