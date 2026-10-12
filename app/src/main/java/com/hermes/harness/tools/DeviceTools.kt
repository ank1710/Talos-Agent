package com.hermes.harness.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.hermes.harness.agent.Risk
import com.hermes.harness.notifications.HermesNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Posts a notification the user will actually see outside the app. */
class NotifyTool : Tool {

    override val name = "notify"
    override val description = """
        Mostra uma notificação no celular. Use quando a tarefa terminar e o usuário
        puder não estar olhando a tela, ou para avisar de algo que ele precisa saber
        agora. Escreva título curto e corpo direto.
    """.trimIndent()

    override val parameters = Schema.obj(
        "title" to Schema.string("Título curto da notificação."),
        "body" to Schema.string("Texto da notificação, uma ou duas frases."),
        required = listOf("title", "body"),
    )

    override fun preview(args: JsonObject): String =
        "Notificar: ${Args.string(args, "title").orEmpty()} — ${Args.string(args, "body").orEmpty()}"

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val title = Args.string(args, "title")?.trim().orEmpty()
        val body = Args.string(args, "body")?.trim().orEmpty()
        if (title.isEmpty()) return ToolResult.error("Parâmetro 'title' é obrigatório.")

        val posted = HermesNotifications.post(
            context = ctx.app,
            id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            title = title,
            body = body,
        )
        return if (posted) {
            ToolResult.ok("Notificação enviada.")
        } else {
            ToolResult.error(
                "Não consegui notificar: a permissão de notificações está negada. " +
                    "Peça ao usuário para liberar em Ajustes do Android › Apps › Hermes › Notificações.",
            )
        }
    }
}

/** Writes text to the system clipboard so the user can paste it elsewhere. */
class ClipboardWriteTool : Tool {

    override val name = "clipboard_write"
    override val description = "Copia um texto para a área de transferência do celular."

    override val parameters = Schema.obj(
        "text" to Schema.string("Texto a copiar."),
        required = listOf("text"),
    )

    override fun preview(args: JsonObject): String =
        "Copiar ${Args.string(args, "text")?.length ?: 0} caracteres para a área de transferência"

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val text = Args.string(args, "text") ?: return ToolResult.error("Parâmetro 'text' é obrigatório.")
        val clipboard = ctx.app.getSystemService(ClipboardManager::class.java)
            ?: return ToolResult.error("Área de transferência indisponível.")
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText("Hermes", text))
            ToolResult.ok("Copiado (${text.length} caracteres).")
        }.getOrElse { ToolResult.error("Falha ao copiar: ${it.message}") }
    }
}

/**
 * Reads the clipboard. Marked as needing approval: the clipboard routinely holds
 * passwords and one-time codes, so reading it is never silent.
 */
class ClipboardReadTool : Tool {

    override val name = "clipboard_read"
    override val description = """
        Lê o texto que está na área de transferência. O Android só permite isso com o
        app em primeiro plano, então pode falhar se o usuário trocou de app.
    """.trimIndent()

    override val parameters = Schema.obj()

    override val risk = Risk.CAUTION

    override fun preview(args: JsonObject): String = "Ler a área de transferência"

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val clipboard = ctx.app.getSystemService(ClipboardManager::class.java)
            ?: return ToolResult.error("Área de transferência indisponível.")
        if (!clipboard.hasPrimaryClip()) return ToolResult.ok("A área de transferência está vazia.")

        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(ctx.app)
            ?.toString()
            .orEmpty()

        return if (text.isEmpty()) {
            ToolResult.ok("A área de transferência não contém texto.")
        } else {
            ToolResult.ok(text)
        }
    }
}

/** Opens a URL or a deep link, handing off to whatever app handles it. */
class OpenUrlTool : Tool {

    override val name = "open_url"
    override val description = """
        Abre uma URL ou deep link no app apropriado (navegador, Maps, YouTube, etc.).
        Requer esquema completo, por exemplo https://exemplo.com ou geo:0,0?q=...
    """.trimIndent()

    override val parameters = Schema.obj(
        "url" to Schema.string("URL completa, com esquema."),
        required = listOf("url"),
    )

    override val risk = Risk.CAUTION

    override fun preview(args: JsonObject): String = "Abrir ${Args.string(args, "url").orEmpty()}"

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult {
        val url = Args.string(args, "url")?.trim().orEmpty()
        if (url.isEmpty()) return ToolResult.error("Parâmetro 'url' é obrigatório.")
        if (!url.contains(":")) return ToolResult.error("A URL precisa de um esquema, como https://")

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.app.startActivity(intent)
            ToolResult.ok("Aberto: $url")
        } catch (e: Exception) {
            ToolResult.error("Nenhum app conseguiu abrir essa URL (${e.message}).")
        }
    }
}

/** Battery, storage, network and screen — the state a phone agent must know. */
class DeviceInfoTool : Tool {

    override val name = "device_info"
    override val description = """
        Devolve o estado do aparelho: bateria (nível, carregando, temperatura),
        armazenamento livre, tipo de conexão, tela, tempo ligado e versão do Android.
        Consulte isto antes de sugerir qualquer coisa que dependa de bateria ou rede.
    """.trimIndent()

    override val parameters = Schema.obj()

    override fun preview(args: JsonObject): String = "Ler o estado do aparelho"

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val context = ctx.app
        val lines = mutableListOf<String>()

        // Battery — queried as a sticky broadcast, which needs no receiver.
        runCatching {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (battery != null) {
                val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val pct = if (scale > 0) (level * 100f / scale).roundToInt() else level
                val status = when (battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "carregando"
                    BatteryManager.BATTERY_STATUS_FULL -> "cheia"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "descarregando"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "parada"
                    else -> "desconhecido"
                }
                val tempC = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
                lines += "Bateria: $pct% ($status), ${String.format(Locale.US, "%.1f", tempC)}°C"
            }
        }

        // Storage.
        runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            val freeGb = stat.availableBytes / 1_000_000_000.0
            val totalGb = stat.totalBytes / 1_000_000_000.0
            lines += "Armazenamento: ${String.format(Locale.US, "%.1f", freeGb)} GB livres de " +
                "${String.format(Locale.US, "%.1f", totalGb)} GB"
        }

        // Network.
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val transport = when {
                caps == null -> "sem conexão"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "dados móveis"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "outro"
            }
            val metered = if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true) {
                "não medida"
            } else {
                "medida"
            }
            lines += "Rede: $transport ($metered)"
        }

        // Screen.
        runCatching {
            val metrics = context.resources.displayMetrics
            lines += "Tela: ${metrics.widthPixels}×${metrics.heightPixels} @ ${metrics.densityDpi}dpi"
        }

        // Runtime.
        runCatching {
            val up = SystemClock.elapsedRealtime()
            val h = TimeUnit.MILLISECONDS.toHours(up)
            val m = TimeUnit.MILLISECONDS.toMinutes(up) % 60
            lines += "Ligado há: ${h}h ${m}min"
        }

        lines += "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"
        lines += "App em primeiro plano agora."

        ToolResult.ok(lines.joinToString("\n"))
    }
}

/** Lets the agent see what is installed, which is often the first step of a task. */
class ListAppsTool : Tool {

    override val name = "list_apps"
    override val description = """
        Lista os aplicativos instalados no aparelho (nome e pacote). Use o filtro para
        procurar algo específico em vez de despejar a lista inteira.
    """.trimIndent()

    override val parameters = Schema.obj(
        "filter" to Schema.string("Texto a procurar no nome ou no pacote. Opcional."),
        "include_system" to Schema.boolean("Incluir apps de sistema. Padrão false."),
    )

    override fun preview(args: JsonObject): String =
        "Listar apps instalados${Args.string(args, "filter")?.let { " contendo \"$it\"" } ?: ""}"

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val filter = Args.string(args, "filter")?.lowercase()?.trim()
        val includeSystem = Args.bool(args, "include_system") == true

        val apps = runCatching {
            val pm = ctx.app.packageManager
            val flags = android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS
            @Suppress("DEPRECATION")
            val installed = pm.getInstalledApplications(flags)
            installed.asSequence()
                .filter { includeSystem || (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { info ->
                    val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
                    label to info.packageName
                }
                .filter { (label, pkg) ->
                    filter == null || label.lowercase().contains(filter) || pkg.lowercase().contains(filter)
                }
                .sortedBy { it.first.lowercase() }
                .toList()
        }.getOrElse { return@withContext ToolResult.error("Falha ao listar apps: ${it.message}") }

        if (apps.isEmpty()) {
            return@withContext ToolResult.ok("Nenhum app encontrado com esse filtro.")
        }

        val shown = apps.take(MAX_RESULTS)
        val body = shown.joinToString("\n") { (label, pkg) -> "$label  ($pkg)" }
        val more = if (apps.size > shown.size) "\n… e mais ${apps.size - shown.size} apps." else ""
        ToolResult.ok("${apps.size} apps encontrados:\n$body$more")
    }

    private companion object {
        const val MAX_RESULTS = 80
    }
}
