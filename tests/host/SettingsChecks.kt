import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.talos.harness.store.*
import com.talos.harness.llm.DeepSeekClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer
import java.nio.file.Files

private class TestPreferences(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state
    var failWrites = false
    val writes = mutableListOf<Preferences>()
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        if (failWrites) throw IOException("Simulated disk failure")
        val next = transform(state.value)
        state.value = next
        writes += next
        return next
    }
}
private fun legacy(mode: String = "CLOUD", provider: String = "deepseek", url: String = TalosSettings.DEFAULT_BASE_URL) = preferencesOf(
    stringPreferencesKey("model_mode") to mode,
    stringPreferencesKey("provider_id") to provider,
    stringPreferencesKey("base_url") to url,
    stringPreferencesKey("model") to "model-original",
    stringPreferencesKey("api_key") to "old-cloud-secret",
    stringPreferencesKey("gateway_api_key") to "old-pc-secret",
)

fun main() = runBlocking {
    var passed = 0
    suspend fun verify(name: String, block: suspend () -> Unit) { block(); passed++; println("PASS $name") }
    val endpoint = "https://api.openai.com/v1"
    val draft = ConnectionDraft("openai", endpoint, "model-new", "new-cloud-secret", false)
    verify("credential scope binds provider, scheme, host, port, path and mode") {
        val key = credentialScope("openai", endpoint)
        check(key == credentialScope("openai", "https://API.OPENAI.COM:443/v1/"))
        for (url in listOf("http://api.openai.com/v1", "https://api.openai.com:8443/v1", "https://other.invalid/v1", "https://api.openai.com/other")) {
            check(credentialScope("openai", url) != key)
        }
        check(key != credentialScope("custom", endpoint))
        check(key != credentialScope("openai", endpoint, true))
        check(credentialScope("openai", "https://user:secret@api.openai.com/v1").isEmpty())
        check(credentialScope("openai", "$endpoint?key=value").isEmpty())
    }
    verify("retargeting drops draft keys and restores only explicitly bound credentials") {
        check(draft.copy(baseUrl = "https://other.invalid/v1").boundKey.isEmpty())
        check(draft.copy(providerId = "custom").boundKey.isEmpty())
        val moved = draft.retarget("custom", "http://localhost:1234/v1", emptyMap())
        check(moved.boundKey.isEmpty())
        val restored = moved.retarget("openai", endpoint, mapOf(draft.scope to "restored-secret"))
        check(restored.boundKey == "restored-secret")
        check(!restored.toString().contains("restored-secret"))
        check(!TalosSettings(apiKey = "should-not-print").toString().contains("should-not-print"))
    }
    verify("migration binds the active legacy key before changing provider atomically") {
        val data = TestPreferences(legacy())
        val repo = SettingsRepository(data)
        val before = repo.flow.first()
        check(before.effectiveApiKey == "old-cloud-secret")
        check(before.copy(providerId = "openai", baseUrl = endpoint).effectiveApiKey.isEmpty())
        repo.saveConnection(draft)
        val after = repo.flow.first()
        check(after.effectiveApiKey == "new-cloud-secret" && after.providerId == "openai" && after.model == "model-new")
        check(after.credentials[credentialScope("deepseek", TalosSettings.DEFAULT_BASE_URL)] == "old-cloud-secret")
        check(data.writes.size == 1)
        check(after.credentials.values.none { it == "old-pc-secret" })
        check(data.writes.single()[stringPreferencesKey("legacy_unbound_gateway_key")] == "old-pc-secret")
        // A restarted repository sees only the committed, scoped configuration.
        check(SettingsRepository(data).flow.first().effectiveApiKey == after.effectiveApiKey)
    }
    verify("gateway migration never assigns an inactive cloud key to a guessed provider") {
        val data = TestPreferences(legacy("DESKTOP", "custom", "http://127.0.0.1:9876/v1"))
        val repo = SettingsRepository(data)
        check(repo.flow.first().effectiveApiKey == "old-pc-secret")
        repo.setMode(ModelMode.CLOUD)
        check(repo.flow.first().effectiveApiKey.isEmpty())
        check(data.writes.last()[stringPreferencesKey("legacy_unbound_api_key")] == "old-cloud-secret")
        repo.setMode(ModelMode.DESKTOP)
        check(repo.flow.first().baseUrl == "http://127.0.0.1:9876/v1")
        check(repo.flow.first().effectiveApiKey == "old-pc-secret")
    }
    verify("mode switches restore cloud and PC profiles and preserve selected local model") {
        val repo = SettingsRepository(TestPreferences())
        repo.saveConnection(draft)
        repo.setMode(ModelMode.ON_DEVICE, "local-test.litertlm")
        check(repo.flow.first().effectiveApiKey.isEmpty())
        repo.setMode(ModelMode.CLOUD)
        check(repo.flow.first().providerId == "openai" && repo.flow.first().model == "model-new")
        repo.saveConnection(ConnectionDraft("custom", "http://127.0.0.1:9876/v1", "hermes-agent", "pc-new-secret", true))
        repo.setMode(ModelMode.ON_DEVICE)
        check(repo.flow.first().model == "local-test.litertlm")
        repo.setMode(ModelMode.DESKTOP)
        check(repo.flow.first().effectiveApiKey == "pc-new-secret")
        repo.setMode(ModelMode.CLOUD)
        check(repo.flow.first().effectiveApiKey == "new-cloud-secret")
        check(repo.flow.first().baseUrl == endpoint)
    }
    verify("invalid or failed saves do not change the active connection") {
        val data = TestPreferences(legacy())
        val repo = SettingsRepository(data)
        var failed = false
        try { repo.saveConnection(draft.copy(baseUrl = "https://elsewhere.invalid/v1")) } catch (_: IllegalArgumentException) { failed = true }
        check(failed && data.writes.isEmpty())
        data.failWrites = true
        failed = false
        try { repo.saveConnection(draft) } catch (_: IOException) { failed = true }
        check(failed && repo.flow.first().effectiveApiKey == "old-cloud-secret" && data.writes.isEmpty())
    }
    verify("memory policy persists and separates local from remote inclusion") {
        val repo = SettingsRepository(TestPreferences())
        repo.setMemoryPolicy(true, false)
        val settings = repo.flow.first()
        check(!settings.includesMemory)
        check(settings.copy(mode = ModelMode.ON_DEVICE).includesMemory)
        check(!settings.copy(mode = ModelMode.DESKTOP).includesMemory)
        repo.setMemoryPolicy(false, true)
        check(!repo.flow.first().copy(mode = ModelMode.ON_DEVICE).includesMemory)
    }
    verify("approval policy, shell prefixes and web search settings persist and revoke individually") {
        val data = TestPreferences()
        val repo = SettingsRepository(data)
        repo.setApprovalPermissiveness(ApprovalPermissiveness.LOOSE)
        repo.setWebSearch(WebSearchProvider.BRAVE, " brave-test-key ")
        repo.approveShellPrefix("git   status")
        repo.approveShellPrefix("pwd")
        var settings = SettingsRepository(data).flow.first()
        check(settings.approvalPermissiveness == ApprovalPermissiveness.LOOSE)
        check(settings.webSearchProvider == WebSearchProvider.BRAVE && settings.webSearchApiKey == "brave-test-key")
        check(settings.shellCommandPrefixes == setOf("git status", "pwd"))
        repo.revokeShellPrefix("pwd")
        settings = repo.flow.first()
        check(settings.shellCommandPrefixes == setOf("git status"))
        repo.clearApprovals()
        check(repo.flow.first().shellCommandPrefixes.isEmpty())
        check(!TalosSettings(webSearchApiKey = "secret").toString().contains("secret"))
    }
    verify("gateway detection is a parsed endpoint, not a substring") {
        check(isDefaultGateway("http://127.0.0.1:8642/v1"))
        check(!isDefaultGateway("https://elsewhere.invalid/127.0.0.1:8642"))
        check(!isDefaultGateway("http://127.0.0.1:86420/v1"))
    }
    verify("human skill names migrate from old files and survive body-only edits") {
        val root = Files.createTempDirectory("talos-skill-titles").toFile()
        try {
            val store = SkillStore(root)
            store.save("rotina", "Resumo", "Passos", "teste", true, "Minha rotina diária")
            check(store.list().single().displayName == "Minha rotina diária")
            store.save("rotina", "Resumo", "Novos passos", "teste", false)
            check(store.list().single().displayName == "Minha rotina diária")
            val file = root.resolve("skills/rotina.md")
            file.writeText(file.readText().lines().filterNot { it.startsWith("title: ") }.joinToString("\n"))
            check(store.list().single().displayName == "rotina")
            check(skillIdentifier("Revisão da Serafina!") == "revisao-da-serafina")
        } finally { root.deleteRecursively() }
    }
    if (System.getenv("TALOS_SKIP_NETWORK_CHECKS") != "1") verify("settings network checks refuse redirects without forwarding the credential") {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var redirected = 0
        var authenticated = 0
        server.createContext("/v1/models") { exchange ->
            if (exchange.requestHeaders.getFirst("x-api-key") == "fake-test-key") authenticated++
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/other")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/other") { exchange -> redirected++; exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.start()
        try {
            val client = DeepSeekClient()
            val url = "http://127.0.0.1:${server.address.port}/v1"
            check(client.probe("fake-test-key", url, ProviderCatalog.ANTHROPIC) != null)
            check(client.listModels("fake-test-key", url, ProviderCatalog.ANTHROPIC).isEmpty())
            check(authenticated == 2 && redirected == 0)
        } finally { server.stop(0) }
    }
    println("$passed settings checks passed")
}
