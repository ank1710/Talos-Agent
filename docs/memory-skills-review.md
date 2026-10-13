# Segunda revisão: memória, skills e busca de conversas

Revisão de 12/10/2026. Correções sobre a implementação existente, sem reimplementação.
Foram lidos o relatório anterior, README, on-device-findings, a seção Security review
de review-findings, `../talos-prompt-memoria-skills.md` e `../diario/README.md`.
As referências Kotlin abaixo são relativas a `app/src/main/java/com/talos/harness/`.

**Resultado:** 19 grupos de verificações de persistência/segurança passaram no host;
Markdown e catálogo também passaram. O único build solicitado não chegou a compilar:
Gradle falhou ao carregar `libnative-platform.dylib` no sandbox. Não houve nova tentativa,
instalação nem execução no aparelho. A garantia absoluta do item 3 permanece limitada
pelo shell com autoridade do app, descrito abaixo.

1. **Harness corrigido, mantendo `internal`.**

   `scripts/check-persistent-data.py:71` passa `-Xfriend-paths` apontando para as classes
   do módulo Android. Essa é a associação de amizade que faltava ao módulo de testes.
   Tornar os parsers de `HuggingFaceModelCatalog.kt` públicos ampliaria a API de produção
   só para atender ao teste. `@VisibleForTesting` sozinho documentaria a intenção, mas
   não daria acesso Kotlin entre módulos. Nenhum teste foi apagado.

   A primeira execução após essa correção compilou todos os testes contra o build
   existente: 11 verificações antigas de persistência e 9 de configurações passaram;
   o teste seguinte de configurações falhou em `HttpServer.create` com
   `java.net.SocketException: Operation not permitted`. Não foi repetida essa tentativa
   de abrir socket. Não classifico a suíte completa de configurações como aprovada.

   `scripts/check-persistent-data.py:11` permite selecionar suítes; por padrão continua
   executando todas. `:61` inclui os fontes não visuais alterados em `--source-check`,
   e `:78` imprime claramente o modo. Esse modo usa dependências/classes existentes
   para o restante do app e não valida Compose, ViewModel nem o APK completo.

2. **Declarado pelo usuário versus inferido pelo agente.**

   `store/MemoryStore.kt:6` define as duas classificações. O arquivo guarda
   `memory_kind: user_declared` ou `memory_kind: agent_inferred`, além de `origin`;
   leitura/escrita em `store/PlainTextStore.kt:115` e `:122`. Arquivos antigos continuam
   legíveis, não são regravados na leitura e entram como inferidos (`MemoryStore.kt:32`).
   Valor desconhecido gera erro em vez de promover silenciosamente uma memória.

   `tools/MemoryTool.kt:18` expõe `kind` ao modelo; omissão significa inferido.
   `:33` valida o valor; para declarado, `:37` exige texto literal contido no último
   pedido humano capturado pelo ViewModel (`ui/ChatViewModel.kt:949`). Um argumento
   inventado `userMessage`, saída antiga de ferramenta, busca ou tarefa produzida por
   subagente não substitui essa evidência. O builder propaga o mesmo pedido humano
   para os filhos (`ChatViewModel.kt:353`). A prévia exibe a classificação (`MemoryTool.kt:24`)
   e `list` devolve-a (`:55`).

   `ui/settings/PersistentDataSections.kt:44` destaca quantas entradas são inferidas;
   `:77` mostra o rótulo de cada entrada; `:149` permite revisar a classificação.
   Uma adição manual começa como declarada. Editar uma inferência mantém a classificação
   até o usuário alterá-la explicitamente. O callback chega ao store em
   `ui/ChatViewModel.kt:862`, via `ui/settings/SettingsScreen.kt:193` e o method reference
   já existente em MainActivity.

   O resumo enviado ao modelo mantém a classificação como primeiro campo, inclusive
   no modo compacto (`agent/PersistentContext.kt:31`). A política compartilhada diz
   para nunca atribuir inferências ao usuário (`agent/SystemPrompt.kt:16`).

   **Limite:** correspondência literal prova presença no pedido, não seu significado.
   Citação de terceiros, hipótese ou recorte fora de contexto ainda requer revisão
   humana. O código e a prévia não apresentam essa checagem como prova semântica.
   Aprovações permanentes existentes continuam podendo dispensar a prévia.

3. **Política: campos de documentos não configuram permissões; há uma ressalva real.**

   `PlainTextStore.kt:96` lê texto e metadados de registro. Nenhuma função desse caminho
   chama SettingsRepository, altera riscos, registra ferramentas ou decide aprovação.
   `tools/ToolRegistry.kt:14` constrói um mapa privado a partir de ferramentas reais;
   `agent/AgentLoop.kt:127` consulta esse mapa e recusa nomes desconhecidos, `:146` lê
   `tool.risk`, `:148` consulta o gate e `:153` impede execução recusada. O gate interativo
   decide pelo risco real (`agent/Approval.kt:39`). Metadados e argumentos como
   `risk=SAFE`, `requiresApproval=false` e `tools=[invented]` não entram nessas decisões.

   As aprovações permanentes vêm de Settings (`ui/ChatViewModel.kt:431`) e são gravadas
   pelo callback de decisão humana (`:558`, `:586`; `store/Settings.kt:295`).
   As listas do agendador continuam constantes em `schedule/ScheduledAgentWorker.kt:341`
   e `:345`; seu gate inspeciona todas as chamadas em `:323`. O worker não foi alterado.
   O teste existente desse gate foi mantido e executado como parte da suíte.

   **Não é possível afirmar “nenhum caminho”, de forma absoluta.** O shell aprovado
   inicia `ProcessBuilder` sob a autoridade do app (`tools/ShellTool.kt:63`). Assim,
   uma instrução maliciosa pode induzir uma proposta de shell que, se aprovada uma vez
   ou coberta por “Sempre”, modifique arquivos privados, incluindo o DataStore
   `talos_settings` (`store/Settings.kt:154`, `:173`, `:217`). Também existe shell no
   opt-in sensível agendado, como já registrado pelo primeiro agente. Isso é um caminho
   indireto preexistente; não foi reproduzido contra dados reais nem fechado aqui.
   Não existe salto direto do envelope de memória/busca/skill para o gate.

   Isolar esse processo com outra identidade/autoridade, ou retirar sua capacidade de
   shell geral, exige uma mudança própria de sandbox/capacidades. Um filtro textual
   de comandos não resolveria. Mantive o shell, o gate e o worker existentes; portanto
   **o requisito absoluto deste item não está integralmente encerrado**.

4. **Histórico não confiável: rastreamento além do envelope e correção nos filhos.**

   `store/ConversationStore.kt:102` seleciona mensagens históricas; `:112` produz trechos,
   sem materializá-los como novas mensagens. `tools/SessionSearchTool.kt:35` devolve dados
   JSON, com `historical_role` apenas como campo em `:38`. `tools/StoredData.kt:9` explicita
   que papéis e falas antigos não são pedidos atuais. O resultado entra como `Role.TOOL`
   em `AgentLoop.kt:177` e continua `role: tool` em `agent/Model.kt:139`, inclusive após
   persistir e reabrir o histórico. Não é analisado como frame de aprovação do gateway.

   O backend remoto serializa o ChatRequest sem converter o corpo da ferramenta em
   instrução (`llm/DeepSeekClient.kt:66`). Seu parser de eventos em `:108` trata frames
   da resposta HTTP, não trechos históricos no request. O backend local converte o
   resultado para `Message.tool` (`local/OnDeviceBackend.kt:568`) e mantém
   `automaticToolCalling = false` (`:266`); a execução passa pelo AgentLoop real.

   **Bug encontrado e corrigido:** o pai podia colocar conteúdo recuperado em `task`
   ou `context` de `spawn_subagent`, e esses campos eram concatenados ao sistema do
   filho. Agora o sistema é fixo (`tools/SubagentTool.kt:327`) e os argumentos são dados
   JSON separados (`:148`). A mensagem de delegação continua no papel user exigido pelo
   fluxo existente, mas a política fixa distingue a proposta do pai de uma nova
   autorização humana. O gate e o registry do filho continuam os do caminho anterior.

   `tests/host/PersistentDataChecks.kt:237` acompanha memória e busca pelo loop real,
   request, tentativa de ferramenta inventada, shell recusado e histórico reaberto;
   os papéis USER/ASSISTANT/TOOL históricos permanecem dentro do resultado. `:305`
   verifica que o mesmo ataque delegado não entra no sistema do filho, conserva
   backend/modelo/credencial capturados e não contorna o gate do pai. O modelo e shell
   dos testes são simulados: isso verifica a estrutura, não imunidade de um LLM.

5. **Orçamento: medição de bytes, estimativa explícita de tokens.**

   `agent/PersistentContext.kt:15` mantém tetos separados: 96 bytes por seção no modo
   compacto, 1024 nas demais janelas. Incluem JSON, classificação, escapes e delimitadores;
   o corpo da skill não entra. `:43` corta por pontos de código para não partir um par
   UTF-16. Não há tokenizer comum exposto; bytes são a unidade de admissão e chars/4
   é somente uma aproximação, inadequada como garantia de caber na janela inteira.

   | Medição no host | Compacto | Completo |
   |---|---:|---:|
   | Teto memória + catálogo de skills | 192 bytes | 2048 bytes |
   | Fixture comum: adição real | 106 caracteres / 108 bytes | 619 caracteres / 621 bytes |
   | Estimativa chars/4 dessa fixture | 27 tokens | 155 tokens |
   | Fixture saturada, 200 memórias e 200 skills | 188 caracteres / 192 bytes | 2044 caracteres / 2048 bytes |
   | Estimativa chars/4 da fixture saturada | 47 tokens | 511 tokens |
   | Sistema sem registros, ambiente sintético | 534 caracteres / 553 bytes | 2208 caracteres / 2286 bytes |

   Sem registros, a adição persistente é zero. O sistema compacto com o teto inteiro
   pode chegar a 745 bytes; isso não inclui schemas, histórico, template do modelo ou
   resposta. A nova orientação sobre classificação acrescentou 89 caracteres / 91 bytes
   ao sistema anterior (cerca de 23 tokens pela mesma aproximação).

   Schema completo: memory 678 caracteres / 689 bytes (~170 tokens); session_search
   520 / 526 (~130); skill 722 / 734 (~181). O pequeno modelo recebe **zero** bytes desses
   três schemas, como antes. O schema memory cresceu 193 caracteres / 196 bytes.

   A seleção agora tem uma função compartilhada testável (`PersistentContext.kt:19`):
   local + janela declarada positiva abaixo de 4096 seleciona compacto; sem janela
   declarada mantém a decisão anterior de usar completo. A UI (`ChatViewModel.kt:164`),
   o catálogo (`:316`) e o prompt do turno (`:971`, `:980`) usam a mesma função.
   Testes incluem ekv1024, ekv1280, 4095, 4096, janela ausente e modelo remoto com
   nome ekv1024. Isso confirma a ligação no código e a decisão da função no host,
   **não** uma execução nova de LiteRT no telefone.

Comando final executado, com exit code 0:

```sh
python3 scripts/check-persistent-data.py --source-check --suite persistent --suite markdown --suite catalog
```

Resultado: **19/19 grupos** de persistência, incluindo os 11 anteriores; **1 grupo**
Markdown; **1 suíte** de catálogo com suas asserções de filtros/metadados/integridade.
Não somo asserções individuais a grupos para inflar o número. Log em
`.logs/memory-review-host-checks.log`. A execução padrão contra classes de assembleDebug
precisa de um build novo pela sessão principal; aqui não houve APK novo.

`./gw :app:assembleDebug` foi tentado **uma vez**, exit code 1, sem stacktrace/retry:

```text
Gradle could not start your build.
> Could not initialize native services.
   > Failed to load native library 'libnative-platform.dylib' for Mac OS X aarch64.
```

Log em `.logs/memory-review-build.log`. `git diff --check` passou. Comparação com HEAD
confirmou sem alterações em Wire.kt, protocolo, AgentLoop, Approval.kt, worker e ambos
os backends. Nenhuma dependência ou permissão Android nova.

Não verificado: compilação completa de Compose/ViewModel, UI no aparelho, tokenização
real em LiteRT ou provedores, classificação espontânea por modelos reais, gateway,
WorkManager, subagentes no telefone, reinício/morte de processo ou concorrência real
das aprovações. A verificação anterior no aparelho informada pelo usuário continua
válida como evidência da versão anterior; não foi repetida nem atribuída a este patch.

`git diff --stat` dos arquivos rastreados (o relatório novo acima é adicional):

```text
 README.md                                          |  15 ++
 .../com/talos/harness/agent/PersistentContext.kt     |  12 +-
 .../java/com/talos/harness/agent/SystemPrompt.kt     |   3 +-
 .../java/com/talos/harness/store/MemoryStore.kt      |  26 ++-
 .../java/com/talos/harness/store/PlainTextStore.kt   |  12 +-
 .../java/com/talos/harness/tools/MemoryTool.kt       |  19 +-
 .../java/com/talos/harness/tools/StoredData.kt       |   2 +-
 .../java/com/talos/harness/tools/SubagentTool.kt     |  21 +--
 .../java/com/talos/harness/ui/ChatViewModel.kt       |  59 ++-----
 .../harness/ui/settings/PersistentDataSections.kt  |  27 ++-
 .../talos/harness/ui/settings/SettingsScreen.kt    |   3 +-
 docs/memory-skills-report.md                       |   4 +
 scripts/check-persistent-data.py                   |  18 +-
 tests/host/PersistentDataChecks.kt                 | 193 ++++++++++++++++++++-
 14 files changed, 329 insertions(+), 85 deletions(-)
```
