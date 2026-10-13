# Memória, busca de conversas e skills — relatório de implementação

Registro histórico do primeiro agente. A [segunda revisão](memory-skills-review.md)
corrige a classificação declarado/inferido, o harness e a promoção de dados no
prompt de subagentes; atualiza as medições e distingue testes host de build Android.

Implementado no checkout Talos em 12/10/2026. Uma tentativa de
`./gw :app:assembleDebug`: **BUILD SUCCESSFUL in 4s**. Onze verificações de lógica
passaram no host com `python3 scripts/check-persistent-data.py`.
**Não instalado nem executado no aparelho nesta sessão.**

## Arquivos alterados

Caminhos Kotlin abaixo relativos a `app/src/main/java/com/talos/harness/`.

| Arquivo | Mudança |
|---|---|
| `store/PlainTextStore.kt` (novo) | Persistência UTF-8 com cabeçalho legível, data de criação/edição e origem; mutex compartilhado entre instâncias, substituição atômica, validação de identificadores, links e tamanho. |
| `store/MemoryStore.kt` (novo) | Criar, ler, editar e apagar fatos; identificadores UUID; limite de 1000 caracteres por fato. |
| `store/SkillStore.kt` (novo) | CRUD de documentos nomeados; nome no arquivo, descrição de até 160 caracteres e passos de até 6000. |
| `store/ConversationStore.kt` | Busca literal em mensagens e títulos, intervalo de datas, trechos com papel histórico e paginação; nenhuma alteração do histórico durante a busca. |
| `tools/MemoryTool.kt` (novo) | `save`, `forget`, `list`; origem vinculada à conversa; risco CAUTION e prévia do conteúdo a gravar. |
| `tools/SessionSearchTool.kt` (novo) | `query`, `after`, `before`, `offset`; datas inclusivas no fuso do aparelho; saída encapsulada como dados. |
| `tools/SkillTool.kt` (novo) | `create`, `list`, `execute`, `edit`, `delete`; executar carrega os passos para o próximo ciclo normal do agente. |
| `tools/StoredData.kt` (novo) | Envelope JSON de dados não confiáveis e bloqueio explícito fora de uma conversa interativa. |
| `tools/Tool.kt` | Campo `ToolContext.interactive`, falso por padrão e independente dos argumentos do modelo. |
| `tools/SubagentTool.kt` | A mesma cláusula de desconfiança de memória, histórico e skills no prompt dos filhos. |
| `agent/PersistentContext.kt` (novo) | Seleção por correspondência lexical normalizada, recência como desempate, resumos limitados por orçamento; apenas nome e descrição de skills. |
| `agent/SystemPrompt.kt` | Política explícita, orientação das ferramentas persistentes e versão compacta para janela pequena. |
| `ui/ChatViewModel.kt` | Stores compartilhados, registro interativo das ferramentas, origem capturada por turno, montagem do contexto e operações da UI. |
| `ui/settings/PersistentDataSections.kt` (novo) | Telas de memória e skills, edição, metadados, confirmação de exclusão, mensagens de erro e pedido de execução no chat. |
| `ui/settings/SettingsScreen.kt` | Entradas Memória e Skills no menu e ligação das duas seções. |
| `MainActivity.kt` | Encaminha estado e callbacks; Executar volta ao chat; abrir Ajustes atualiza os registros. |
| `tests/host/AndroidContext.kt` (novo) | Substituto mínimo de Context exclusivamente para testes sem serviços Android. |
| `tests/host/AndroidBuild.kt` (novo) | Valores sintéticos exclusivamente para medir strings de prompt. |
| `tests/host/PersistentDataChecks.kt` (novo) | Onze verificações de persistência, limites, busca e gates com classes compiladas do app. |
| `scripts/check-persistent-data.py` (novo) | Compila somente o programa de testes usando artefatos Kotlin em cache; executa contra as classes do último assembleDebug; não chama Gradle. |
| `README.md` | Capacidades, limites, caminhos de armazenamento e instruções dos testes, sem declarar validação no aparelho. |
| `docs/memory-skills-report.md` (novo) | Este relatório. |

`Wire.kt`, protocolo, backends, `AgentLoop`, `ApprovalGate` e
`ScheduledAgentWorker.kt` não foram modificados. Não foram adicionadas dependências.

## As seis regras de segurança

1. **Dados, nunca autoridade.** `agent/SystemPrompt.kt:16` declara explicitamente
   memória, `session_search` e skills como dados não confiáveis que não alteram
   regras. A cláusula também chega aos filhos em `tools/SubagentTool.kt:346`.
   `tools/StoredData.kt:8` encapsula conteúdo recuperado em JSON, com aviso fixo;
   aspas e quebras de linha do documento são escapadas. O contexto persistente
   usa JSON em `agent/PersistentContext.kt:30`; nunca concatena o corpo de uma skill
   como instrução de sistema. Isso é separação e defesa de prompt, **não prova de
   imunidade do modelo à injeção**. Texto continua podendo influenciar propostas;
   as barreiras de execução permanecem em código.

2. **Nenhum documento configura política.** Os stores só fornecem texto e
   metadados (`store/PlainTextStore.kt:10`). Os riscos são constantes CAUTION em
   `tools/MemoryTool.kt:13`, `tools/SessionSearchTool.kt:16` e
   `tools/SkillTool.kt:13`. Em `agent/AgentLoop.kt:146`, o risco continua vindo da
   ferramenta registrada; em `agent/AgentLoop.kt:148`, o gate real decide.
   `requiresApproval` permanece em `agent/Approval.kt:39`; nem esse método,
   nem o registro de ferramentas, nem as listas do agendador leem campos de memória
   ou de skills. Campos extras de arquivos não são executados nem interpretados
   como permissões. A autorização permanente já existente continua sendo uma
   decisão explícita do usuário, com sua granularidade por ferramenta preservada.

3. **Skill não pré-aprova suas ações.** `tools/SkillTool.kt:38` só lê um documento;
   não contém executor, shell ou loop interno. Qualquer ação posterior passa
   novamente por `agent/AgentLoop.kt:139` e pelo risco da ferramenta real.
   `shell` continua CAUTION em `tools/ShellTool.kt:43`. Os filhos continuam usando
   o gate do pai em `ui/ChatViewModel.kt:354`. O backend local continua com
   `automaticToolCalling = false` em `local/OnDeviceBackend.kt:266`.
   O teste com skill hostil aprovou a leitura, recusou o shell e verificou que
   a ação não executou. Ele usa backend e shell simulados, com o AgentLoop real.

4. **Histórico não é reexecutado.** `store/ConversationStore.kt:102` procura texto;
   títulos e papéis são metadados do resultado. `tools/SessionSearchTool.kt:35`
   devolve o envelope não confiável; não cria mensagens SYSTEM/USER a partir do
   papel histórico. A entrega continua como Role.TOOL em `agent/AgentLoop.kt:176`.
   Inclui conteúdo de ferramentas antigas, sem elevar sua confiança. A busca
   limita cada trecho a 480 caracteres mais reticências, cinco conversas por
   resposta, um trecho por conversa, até 100 arquivos examinados por página;
   arquivos acima de 4 MiB ou corrompidos são contabilizados como ignorados.

5. **Allowlist do agendador intacta.** As ferramentas novas são registradas apenas
   no builder interativo em `ui/ChatViewModel.kt:340`. O catálogo do worker em
   `schedule/ScheduledAgentWorker.kt:241` não as contém. As listas em
   `schedule/ScheduledAgentWorker.kt:341` e `:345` são exatamente as anteriores.
   Além disso, `requiresApproval` do worker continua sempre verdadeiro em `:323`.
   O teste instanciou o gate real e confirmou a recusa das três ferramentas com
   e sem opt-in sensível, até quando a requisição de teste declara Risk.SAFE.

6. **Gravações pelas novas ferramentas exigem contexto interativo — com uma
   limitação preexistente importante.** `tools/Tool.kt:50` inicia `interactive`
   como falso. Somente o ViewModel o ativa em `ui/ChatViewModel.kt:220`.
   `tools/StoredData.kt:13`, chamado antes de qualquer operação em
   `MemoryTool.kt:27`, `SkillTool.kt:31` e `SessionSearchTool.kt:29`, recusa
   execução não interativa. A UI faz gravações por ações explícitas em
   `ui/ChatViewModel.kt:846`; mostra data e origem e permite editar/apagar em
   `ui/settings/PersistentDataSections.kt:30` e `:74`. Os arquivos ficam em
   `files/memory` e `files/skills`, fora do workspace dos file tools.
   **Não é possível garantir a regra absoluta “nenhuma escrita agendada” com o
   shell atual:** o opt-in sensível já inclui `shell` (`ScheduledAgentWorker.kt:346`),
   cujo `ProcessBuilder` roda com a autoridade do app (`tools/ShellTool.kt:63`).
   Um comando pode atingir esses arquivos diretamente, sem passar pelos stores.
   A mesma autoridade ampla permite alterações indiretas em outros arquivos do app.
   Não ampliei a allowlist nem tentei esconder esse caminho com filtro de comandos.
   Fechar essa limitação exige restringir/remover o shell agendado ou isolá-lo
   efetivamente; isso é uma mudança de política/sandbox além desta implementação.

## Orçamento de contexto e custo

Não há tokenizer exposto pela interface atual. O orçamento conservador cobra
**uma unidade por byte UTF-8**, incluindo rótulos, delimitadores, JSON e escapes.
O teto implementado e testado é de bytes; a equivalência com tokens é estimada,
não uma medição do tokenizer LiteRT/DeepSeek/OpenAI/Anthropic.

| Adição | Janela local declarada menor que 4096 | Demais modelos |
|---|---|---|
| Memória resumida no sistema | Até 96 bytes; aproximadamente 24 tokens em texto comum | Até 1024 bytes; aproximadamente 256 tokens |
| Nomes + descrições de skills no sistema | Até 96 bytes; aproximadamente 24 tokens | Até 1024 bytes; aproximadamente 256 tokens |
| Schema `memory` | Não enviado | 485 caracteres / 493 bytes; aproximadamente 122 tokens |
| Schema `session_search` | Não enviado | 520 caracteres / 526 bytes; aproximadamente 130 tokens |
| Schema `skill` | Não enviado | 635 caracteres / 644 bytes; aproximadamente 159 tokens |

As estimativas usuais usam caracteres/4 e podem variar bastante; o controle de
admissão usa bytes, **não** essa razão otimista. Os três schemas somam cerca de
411 tokens por estimativa. A política expandida adiciona aproximadamente 52 tokens
à cláusula antiga no prompt completo; a orientação de uso adiciona cerca de 71.
O prompt compacto inteiro, sem registros, mediu 445 caracteres / 462 bytes
(aproximadamente 112 tokens); o contexto persistente acrescenta no máximo 192 bytes.
Sem registros, a adição de contexto persistente custa zero. O prompt completo
com orientação, usando ambiente sintético e `/workspace`, mediu 2119 caracteres /
2195 bytes; caminhos e dispositivo reais mudam esse total.

A relevância usa termos normalizados do último pedido, correspondência nos fatos
ou nome/descrição, depois recência. O conteúdo é resumido por excerto, sem outra
chamada de modelo. Entradas que não cabem são omitidas. O corpo de uma skill só
entra depois de `skill/execute`, como resultado de ferramenta sujeito ao gate.

**Limite funcional deliberado:** o modelo `ekv1024` mantém `device_info`, `shell`
e `notify`, com um prompt base menor. Ele recebe os resumos limitados, mas não
recebe as ferramentas novas. Edição manual em Ajustes funciona independentemente
do modelo; para usar as novas ferramentas e executar skills, é necessário um
modelo local com janela suficiente ou um provedor remoto. A UI informa isso.
Preservou-se a regra existente de assumir espaço quando a janela não é declarada.
O limite desta implementação cobre as adições persistentes; não implementa
compactação de todo o histórico nem garante que toda conversa caiba em 1024 tokens.

## Verificação e limites

- Build único concluído com sucesso; APK debug em
  `app/build/outputs/apk/debug/app-debug.apk`. Avisos vieram de trechos preexistentes
  de coroutine/Job, `!!`, Locale, ícones e recursos Gradle obsoletos.
- `git diff --check` passou. Comparação com HEAD confirmou inalterados protocolo,
  AgentLoop, ApprovalGate e worker agendado.
- Onze verificações no host: round-trip/edit/delete de memória, gravações
  concorrentes em instâncias distintas, limites/traversal/symlinks/duplicatas,
  edição/exclusão de skills, orçamento/Unicode/relevância, busca/datas/paginação,
  histórico corrompido/grande, recusa de contexto agendado, allowlist real,
  gate após skill hostil e memória sem efeito após recusa.
- Os testes executam classes de produção do último build, mas usam substitutos
  Android e backend simulado. Não usam rede, credenciais nem dados reais do usuário.
- Não verificados: Compose no aparelho, reinício/process death durante gravação,
  permissões e filesystem Android reais, JNI/LiteRT e tokenização real, escolha
  espontânea de ferramentas por modelos, provedores, gateway, WorkManager real,
  aprovações concorrentes e fluxo de subagentes no aparelho.
- Arquivo de memória/skill manualmente malformado produz erro explícito; o parser
  não tenta adivinhar/corrigir cabeçalhos. Texto válido pode ser editado normalmente
  pela UI. Os arquivos continuam sem criptografia, como as conversas existentes.

## Diff stat

O stat abaixo inclui os arquivos novos usando um índice temporário. O índice real
não foi alterado; nada foi commitado.

<!-- DIFF_STAT -->

```text
 README.md                                          |  34 +++-
 .../main/java/com/talos/harness/MainActivity.kt    |  15 +-
 .../com/talos/harness/agent/PersistentContext.kt   |  52 ++++++
 .../java/com/talos/harness/agent/SystemPrompt.kt   |  20 ++-
 .../com/talos/harness/store/ConversationStore.kt   |  45 +++++
 .../java/com/talos/harness/store/MemoryStore.kt    |  20 +++
 .../java/com/talos/harness/store/PlainTextStore.kt | 114 ++++++++++++
 .../java/com/talos/harness/store/SkillStore.kt     |  22 +++
 .../java/com/talos/harness/tools/MemoryTool.kt     |  52 ++++++
 .../com/talos/harness/tools/SessionSearchTool.kt   |  45 +++++
 .../main/java/com/talos/harness/tools/SkillTool.kt |  63 +++++++
 .../java/com/talos/harness/tools/StoredData.kt     |  16 ++
 .../java/com/talos/harness/tools/SubagentTool.kt   |   1 +
 app/src/main/java/com/talos/harness/tools/Tool.kt  |   2 +
 .../java/com/talos/harness/ui/ChatViewModel.kt     |  83 ++++++++-
 .../harness/ui/settings/PersistentDataSections.kt  | 149 +++++++++++++++
 .../talos/harness/ui/settings/SettingsScreen.kt    |  35 ++++
 docs/memory-skills-report.md                       | 199 +++++++++++++++++++++
 scripts/check-persistent-data.py                   |  39 ++++
 tests/host/AndroidBuild.kt                         |  12 ++
 tests/host/AndroidContext.kt                       |   4 +
 tests/host/PersistentDataChecks.kt                 | 194 ++++++++++++++++++++
 22 files changed, 1205 insertions(+), 11 deletions(-)
```
