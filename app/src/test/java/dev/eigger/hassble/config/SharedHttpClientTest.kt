package dev.eigger.hassble.config

import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.lang.reflect.Field

class SharedHttpClientTest {

    @Test
    fun `ConfigLoader and ConfigTemplatesLoader share the same OkHttpClient instance`() {
        assertSame(
            "ConfigLoader and ConfigTemplatesLoader must share the identical OkHttpClient instance",
            ConfigLoader.sharedHttpClient,
            ConfigTemplatesLoader.sharedHttpClient,
        )
    }

    @Test
    fun `ConfigLoader and ConfigTemplatesLoader default constructors use sharedHttpClient`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "shared-http-test")
        val configLoader = ConfigLoader(dir, ObdPresetStore(emptyMap()))
        val templatesLoader = ConfigTemplatesLoader(dir, ConfigTemplates(emptyList()), configLoader)

        val configLoaderHttpField: Field = ConfigLoader::class.java.getDeclaredField("http").apply { isAccessible = true }
        val templatesLoaderHttpField: Field = ConfigTemplatesLoader::class.java.getDeclaredField("http").apply { isAccessible = true }

        val loaderClient = configLoaderHttpField.get(configLoader)
        val templatesClient = templatesLoaderHttpField.get(templatesLoader)

        assertSame("ConfigLoader default http should be sharedHttpClient", ConfigLoader.sharedHttpClient, loaderClient)
        assertSame("ConfigTemplatesLoader default http should be sharedHttpClient", ConfigLoader.sharedHttpClient, templatesClient)
    }
}
