package dev.eigger.hassble.config

import dev.eigger.hassble.net.GitHubHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ConfigTemplatesLoaderTest {

    private fun loader(): ConfigTemplatesLoader {
        val dir = File(System.getProperty("java.io.tmpdir"), "hassble-templates-test")
        val configLoader = ConfigLoader(dir, ObdPresetStore(emptyMap()))
        return ConfigTemplatesLoader(dir, ConfigTemplates(emptyList()), configLoader)
    }

    @Test
    fun `templates URL is at repo root next to config`() {
        val templatesLoader = loader()
        val url = "https://raw.githubusercontent.com/eigger/hassble-config/main/config.yaml"
        assertEquals(
            "https://raw.githubusercontent.com/eigger/hassble-config/main/templates.yaml",
            templatesLoader.templatesUrlFromConfigUrl(url),
        )
    }

    @Test
    fun buildTemplatesUrl_uses_fixed_filename() {
        assertEquals(
            "https://raw.githubusercontent.com/o/r/dev/templates.yaml",
            GitHubHelper.buildTemplatesUrl("o/r", "dev"),
        )
    }

    @Test
    fun merge_prefers_remote_ids() {
        val remote = ConfigTemplates(
            listOf(ConfigTemplate("a", "A", "", DeviceConfig("a", "A", Source.obd))),
        )
        val bundled = ConfigTemplates(
            listOf(
                ConfigTemplate("a", "Old", "", DeviceConfig("a", "Old", Source.obd)),
                ConfigTemplate("b", "B", "", DeviceConfig("b", "B", Source.obd)),
            ),
        )
        val merged = ConfigTemplates.merge(remote, bundled)
        assertEquals(listOf("a", "b"), merged.all().map { it.id })
        assertEquals("A", merged.find("a")!!.name)
    }
}
