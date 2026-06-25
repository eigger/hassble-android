package dev.eigger.hassble.config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable

@Serializable
data class ConfigTemplatesFile(
    val templates: List<ConfigTemplate> = emptyList(),
)

@Serializable
data class ConfigTemplate(
    val id: String,
    val name: String,
    val description: String = "",
    val device: DeviceConfig,
)

class ConfigTemplates(private val templates: List<ConfigTemplate>) {

    fun all(): List<ConfigTemplate> = templates

    fun find(id: String): ConfigTemplate? = templates.firstOrNull { it.id == id }

    companion object {
        fun fromYaml(text: String): ConfigTemplates =
            ConfigTemplates(Yaml.default.decodeFromString(ConfigTemplatesFile.serializer(), text).templates)

        fun fromAssets(context: android.content.Context): ConfigTemplates =
            fromYaml(context.assets.open("templates.yaml").bufferedReader().readText())

        /** Git 템플릿이 우선, id가 없는 내장 템플릿만 보충 */
        fun merge(primary: ConfigTemplates, fallback: ConfigTemplates): ConfigTemplates {
            val ids = primary.templates.map { it.id }.toSet()
            val merged = primary.templates + fallback.templates.filter { it.id !in ids }
            return ConfigTemplates(merged)
        }
    }
}
