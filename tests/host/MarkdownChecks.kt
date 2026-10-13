import com.talos.harness.ui.markdown.parseOrderedListLine

fun main() {
    val api = parseOrderedListLine("1. **API (rápido, funciona agora).** O tweet fala de acesso grátis.")
    check(api?.number == 1)
    check(api.delimiter == '.')
    check(api.text == "**API (rápido, funciona agora).** O tweet fala de acesso grátis.")

    val local = parseOrderedListLine("  10) Local no celular")
    check(local?.number == 10 && local.delimiter == ')' && local.text == "Local no celular")
    check(parseOrderedListLine("1.no whitespace") == null)

    println("PASS ordered Markdown list keeps a short marker and the full body")
}
