package com.talos.harness.tools

import android.Manifest
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.talos.harness.agent.Risk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Reads the user's calendar.
 *
 * Permission handling follows the same rule as [NotifyTool]: a tool cannot pop a
 * permission dialog, and it must not pretend to work. When `READ_CALENDAR` is
 * missing it returns an actionable error naming the exact permission, so the user
 * grants it once in Android settings and every later call succeeds.
 */
class CalendarReadTool : Tool {

    override val name = "calendar_read"

    override val description = """
        Lista os próximos compromissos da agenda do usuário, com título, início,
        fim e local. Use para responder "o que eu tenho hoje/amanhã" ou para
        checar se um horário está livre. Requer permissão de leitura de calendário.
    """.trimIndent()

    override val parameters = Schema.obj(
        "days" to Schema.integer("Quantos dias à frente procurar. Padrão 7, máximo 60."),
        "limit" to Schema.integer("Máximo de compromissos devolvidos. Padrão 20."),
        "query" to Schema.string("Filtro opcional por texto no título do compromisso."),
    )

    override val risk = Risk.CAUTION

    override fun preview(args: JsonObject): String =
        "Ler a agenda dos próximos ${Args.int(args, "days") ?: 7} dias"

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val granted = ctx.app.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return@withContext ToolResult.error(
                "Sem permissão de calendário. Peça ao usuário para liberar " +
                    "READ_CALENDAR em Ajustes do Android › Apps › Talos › Permissões.",
            )
        }

        val days = (Args.int(args, "days") ?: DEFAULT_DAYS).coerceIn(1, 60)
        val limit = (Args.int(args, "limit") ?: DEFAULT_LIMIT).coerceIn(1, 100)
        val query = Args.string(args, "query")?.trim()?.takeIf { it.isNotEmpty() }

        val now = System.currentTimeMillis()
        val until = now + TimeUnit.DAYS.toMillis(days.toLong())

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
        )

        var selection = "${CalendarContract.Events.DTSTART} >= ? AND " +
            "${CalendarContract.Events.DTSTART} <= ? AND " +
            "${CalendarContract.Events.DELETED} = 0"
        val selectionArgs = mutableListOf(now.toString(), until.toString())

        if (query != null) {
            selection += " AND ${CalendarContract.Events.TITLE} LIKE ?"
            selectionArgs += "%$query%"
        }

        val rows = runCatching {
            ctx.app.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs.toTypedArray(),
                "${CalendarContract.Events.DTSTART} ASC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events._ID)
                val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                val startIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
                val locIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)
                val allDayIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
                val calIdx = cursor.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_DISPLAY_NAME)

                buildList {
                    while (cursor.moveToNext() && size < limit) {
                        add(
                            Event(
                                id = cursor.getLong(idIdx),
                                title = cursor.getString(titleIdx).orEmpty().ifBlank { "(sem título)" },
                                start = cursor.getLong(startIdx),
                                end = cursor.getLong(endIdx),
                                location = cursor.getString(locIdx),
                                allDay = cursor.getInt(allDayIdx) == 1,
                                calendar = cursor.getString(calIdx),
                            ),
                        )
                    }
                }
            } ?: emptyList()
        }.getOrElse {
            return@withContext ToolResult.error("Falha ao consultar a agenda: ${it.message}")
        }

        if (rows.isEmpty()) {
            return@withContext ToolResult.ok(
                "Nenhum compromisso nos próximos $days dia(s)${query?.let { " com \"$it\" no título" } ?: ""}.",
            )
        }

        val body = rows.joinToString("\n") { event ->
            val when_ = if (event.allDay) {
                "${dayFormat.format(Date(event.start))} (dia inteiro)"
            } else {
                "${dayFormat.format(Date(event.start))} ${timeFormat.format(Date(event.start))}" +
                    "–${timeFormat.format(Date(event.end))}"
            }
            buildString {
                append("• $when_ — ${event.title}")
                event.location?.takeIf { it.isNotBlank() }?.let { append("  @ $it") }
                event.calendar?.takeIf { it.isNotBlank() }?.let { append("  [${it}]") }
            }
        }

        ToolResult.ok("${rows.size} compromisso(s) nos próximos $days dia(s):\n$body")
    }

    private data class Event(
        val id: Long,
        val title: String,
        val start: Long,
        val end: Long,
        val location: String?,
        val allDay: Boolean,
        val calendar: String?,
    )

    private companion object {
        const val DEFAULT_DAYS = 7
        const val DEFAULT_LIMIT = 20

        val dayFormat = SimpleDateFormat("EEE dd/MM", Locale("pt", "BR"))
        val timeFormat = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
    }
}
