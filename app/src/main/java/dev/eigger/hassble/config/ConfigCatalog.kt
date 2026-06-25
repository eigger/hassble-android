package dev.eigger.hassble.config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable

@Serializable
data class ConfigCatalogFile(
    val templates: List<ConfigTemplate> = emptyList(),
)

@Serializable
data class ConfigTemplate(
    val id: String,
    val name: String,
    val description: String = "",
    val device: DeviceConfig,
)

class ConfigCatalog(private val templates: List<ConfigTemplate>) {

    fun all(): List<ConfigTemplate> = templates

    fun find(id: String): ConfigTemplate? = templates.firstOrNull { it.id == id }

    companion object {
        fun fromYaml(text: String): ConfigCatalog =
            ConfigCatalog(Yaml.default.decodeFromString(ConfigCatalogFile.serializer(), text).templates)

        fun fromAssets(context: android.content.Context): ConfigCatalog =
            fromYaml(context.assets.open("config_catalog.yaml").bufferedReader().readText())
    }
}
