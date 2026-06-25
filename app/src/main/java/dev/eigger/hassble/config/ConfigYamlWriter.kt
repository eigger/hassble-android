package dev.eigger.hassble.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration

object ConfigYamlWriter {
    private val yaml = Yaml(
        configuration = YamlConfiguration(
            encodeDefaults = false,
            strictMode = false,
        ),
    )

    fun encode(config: GatewayConfig): String =
        yaml.encodeToString(GatewayConfig.serializer(), config)
}
