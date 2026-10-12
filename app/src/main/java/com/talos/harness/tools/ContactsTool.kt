package com.talos.harness.tools

import android.Manifest
import android.content.pm.PackageManager
import android.provider.ContactsContract
import com.talos.harness.agent.Risk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Searches the phone's contacts.
 *
 * Same permission contract as [CalendarReadTool] and [NotifyTool]: a tool cannot
 * raise a dialog, so when `READ_CONTACTS` is missing it returns an error naming
 * the exact permission instead of pretending the lookup succeeded.
 *
 * A `query` is required on purpose. Dumping the whole address book into the model
 * context would be both useless and a privacy problem; the agent is expected to
 * ask for the name it wants.
 */
class ContactsSearchTool : Tool {

    override val name = "contacts_search"

    override val description = """
        Procura um contato pelo nome e devolve os telefones encontrados.
        O parâmetro 'query' é obrigatório: não existe listagem completa da agenda,
        então diga o nome (ou parte dele) que você procura.
        Requer permissão de leitura de contatos.
    """.trimIndent()

    override val parameters = Schema.obj(
        "query" to Schema.string("Nome ou parte do nome a procurar."),
        "limit" to Schema.integer("Máximo de contatos devolvidos. Padrão 10, máximo 50."),
        required = listOf("query"),
    )

    override val risk = Risk.SAFE

    override fun preview(args: JsonObject): String =
        "Procurar contato por \"${Args.string(args, "query").orEmpty()}\""

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val granted = ctx.app.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return@withContext ToolResult.error(
                "Sem permissão de contatos. Peça ao usuário para liberar " +
                    "READ_CONTACTS em Ajustes do Android › Apps › Talos › Permissões.",
            )
        }

        val query = Args.string(args, "query")?.trim().orEmpty()
        if (query.isEmpty()) {
            return@withContext ToolResult.error(
                "O parâmetro 'query' é obrigatório: a agenda inteira não é listada por privacidade.",
            )
        }
        val limit = (Args.int(args, "limit") ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args_ = arrayOf("%$query%")

        val matches = runCatching {
            ctx.app.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                args_,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                buildList {
                    while (cursor.moveToNext() && size < limit) {
                        val name = cursor.getString(nameIdx).orEmpty()
                        val number = cursor.getString(numberIdx).orEmpty()
                        if (name.isNotBlank() && number.isNotBlank()) add(name to number)
                    }
                }
            } ?: emptyList()
        }.getOrElse {
            return@withContext ToolResult.error("Falha ao consultar contatos: ${it.message}")
        }

        if (matches.isEmpty()) {
            return@withContext ToolResult.ok("Nenhum contato encontrado com \"$query\".")
        }

        // One contact can own several numbers; group so the model sees a person,
        // not a flat list of duplicates.
        val grouped = matches.groupBy({ it.first }, { it.second })
        val body = grouped.entries.joinToString("\n") { (name, numbers) ->
            "• $name — ${numbers.distinct().joinToString(", ")}"
        }

        ToolResult.ok("${grouped.size} contato(s) com \"$query\":\n$body")
    }

    private companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 50
    }
}
