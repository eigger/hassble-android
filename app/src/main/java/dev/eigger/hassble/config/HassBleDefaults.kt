package dev.eigger.hassble.config

object HassBleDefaults {
    const val CONFIG_FILE = "config.yaml"
    const val TEMPLATES_FILE = "templates.yaml"
    const val DEFAULT_BRANCH = "main"
    const val GIT_CONFIG_URL =
        "https://raw.githubusercontent.com/eigger/hassble-config/main/$CONFIG_FILE"

    const val DEFAULT_LOG_BUFFER_LIMIT = 500
    val LOG_BUFFER_LIMIT_OPTIONS = listOf(100, 500, 1000)
}
