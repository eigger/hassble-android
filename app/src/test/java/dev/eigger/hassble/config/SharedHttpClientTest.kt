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

    @Test
    fun `All network helpers share connectionPool and dispatcher with ConfigLoader sharedHttpClient`() {
        val shared = ConfigLoader.sharedHttpClient

        val authClient = dev.eigger.hassble.net.HaAuthHelper::class.java
            .getDeclaredField("client").apply { isAccessible = true }
            .get(dev.eigger.hassble.net.HaAuthHelper) as okhttp3.OkHttpClient
        val ghClient = dev.eigger.hassble.net.GitHubHelper::class.java
            .getDeclaredField("client").apply { isAccessible = true }
            .get(dev.eigger.hassble.net.GitHubHelper) as okhttp3.OkHttpClient
        val updateClient = dev.eigger.hassble.net.UpdateChecker::class.java
            .getDeclaredField("client").apply { isAccessible = true }
            .get(dev.eigger.hassble.net.UpdateChecker) as okhttp3.OkHttpClient
        val testerClient = dev.eigger.hassble.net.HaConnectionTester::class.java
            .getDeclaredField("http").apply { isAccessible = true }
            .get(dev.eigger.hassble.net.HaConnectionTester) as okhttp3.OkHttpClient

        assertSame(shared, authClient)
        assertSame(shared, ghClient)
        assertSame(shared, updateClient)
        assertSame(shared.connectionPool, testerClient.connectionPool)
        assertSame(shared.dispatcher, testerClient.dispatcher)
    }
}
