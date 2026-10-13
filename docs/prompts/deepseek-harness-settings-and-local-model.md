# Prompt para o DeepSeek Harness — continuidade do Talos

Você está continuando o app Android Talos neste repositório. Não reinicie a
implementação nem reverta alterações existentes. Comece lendo `README.md`,
`docs/review-findings.md`, `docs/on-device-findings.md`,
`docs/memory-skills-report.md` e `docs/settings-followup-and-local-model.md`.
Inspecione `git status` antes de editar e preserve todo trabalho do usuário.

## Objetivo

Preserve as mudanças de Ajustes, memória, busca e skills já presentes no checkout.
A correção do screenshot, build, 22 checks host e benchmark inicial do Qwen3 já
foram feitos. Continue avaliando velocidade e qualidade conversacional em pt-BR
no Galaxy S24 Ultra, sem tratar o teto medido como taxa exata. O usuário mencionou
rodar “um modelo como o Jev”. No histórico do Creative Factory, Jev é um
adaptador opcional de decisão estruturada (Choice, Score, Noul), não um modelo
de chat nem um gerador.
Confirme se o usuário se refere a esse Jev ou a outra persona/modelo antes de
projetar uma integração; por enquanto, mantenha chat local e decisão estruturada
como papéis separados.

## Estado já implementado

- Modos explícitos Cloud, Desktop/gateway e On-device, com perfis separados.
- Tela de conexão usa rascunho e commit atômico. Provider, endpoint, model ID e
  chave não alteram a conexão ativa durante edição.
- Chaves vinculadas ao destino completo; migração conservadora de valores legados;
  sem enviar chave em redirect.
- Modo local sem chave remota e com seleção/benchmark de bundles `.litertlm`.
- Preferência para desativar memória ou limitar memória ao modo local.
- Diagnósticos, aprovações, memórias e skills reorganizados e com erros de escrita
  recuperáveis. Skills separam nome de exibição do identificador.
- O diff inclui as alterações anteriores de memória, busca de conversas e skills;
  confira também `docs/memory-skills-report.md` e não remova essas mudanças.

## Estado atualizado e próximo trabalho

Atualização de 12/10/2026: o parser de listas numeradas foi corrigido depois
que o screenshot mais recente mostrou o corpo de cada item espremido numa coluna
estreita. A causa era usar o grupo regex do corpo como marcador; agora o marcador
`1.`/`1)` fica separado do texto. A regressão `MarkdownChecks.kt` passa e
`./gw :app:assembleDebug` passou. O check host completo agora tem 22 verificações.

O bundle no-thinking do Qwen3 0.6B já foi baixado da fonte oficial, conferido
contra o tamanho e SHA-256 publicados, e copiado para a pasta privada do app
debug no S24 Ultra. A revisão do repositório é
`6aa2daf8aba4aa456797fb8040b36a3948bcfda7`; o SHA-256 é
`2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139`.
Ele foi selecionado em modo local e passou pelo benchmark do Talos: carregou em
3,3 s, primeiro token em 790 ms, 190 caracteres em 3,1 s no CPU. O app reportou
42,8 tokens/s como teto baseado no KV total, não como taxa exata de geração.
O benchmark descarregou o modelo no final. Não baixe outra cópia nem inclua
pesos no repositório.

Próximos passos: compare a qualidade do Qwen com a Gemma usando uma conversa
nova e os mesmos prompts curtos em pt-BR; registre a saída completa e chamadas
de ferramentas. O benchmark mediu desempenho, não qualidade de conversa. O modo
local e o Qwen ficaram selecionados no aparelho. Mantenha qualquer decisão
estruturada atrás de um adaptador separado, sem permitir que a saída do modelo
altere ferramentas, aprovações ou permissões.

O app já executa modelos `.litertlm` instalados em `getExternalFilesDir("models")`.
Não altere `app/src/main/java/com/talos/harness/llm/Wire.kt`, protocolo remoto,
gates de aprovação ou autoridade de ferramentas para fazer o modelo carregar.
Verifique como seleção, descarregamento do engine e benchmark funcionam antes de
mudar o caminho de inferência.

Medições no telefone (SM8650/Snapdragon 8 Gen 3, Android 16):

- `gemma-4-E2B-it.litertlm`, 2.4 GB: 35.5 tok/s em CPU; resposta geral e chamada
  de ferramenta já testadas.
- `mobile-actions_q8_ekv1024.litertlm`, 271 MB: 78.8 tok/s em CPU, mas é ajustado
  para ações e não deve substituir um assistente de conversa.
- `qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm`, 347 MB: carregamento 3,3 s,
  primeiro token 790 ms, 42,8 tok/s de teto no CPU. Qualidade em pt-BR, seleção
  de ferramentas e comportamento com contexto curto ainda não foram comparados.

A candidata open-source `litert-community/Qwen3-0.6B-int4` já carregou e gerou
no telefone. O arquivo no-think q4 tem aprox. 329 MiB, licença Apache-2.0 e o
model card reporta cerca de 2x mais velocidade em CPU que a variante com
thinking. Ele limita o KV context a 1280 tokens; isso reduz o catálogo local e
exige conversas curtas. Compare também
com `Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`: aprox. 329 MB, 4096 tokens,
template Qwen3; pode levar mais tempo por ativar thinking. O Talos usa
LiteRT-LM 0.18.0, compatível com os requisitos publicados dessas variantes.
Como segunda referência, `litert-community/Gemma3-1B-IT` tem bundle generalista
q4/EKV4096 de aprox. 584 MB, mas exige aceitar os termos Gemma; chame-o de
open-weight, não Apache/open-source permissivo. Qwen2.5-1.5B fica em prioridade
menor: há relato recente de erro de template no Google AI Edge Gallery; use-o
somente como sinal para testar no Talos, sem generalizar entre apps.
Fontes primárias:

- https://huggingface.co/litert-community/Gemma3-1B-IT/tree/main
- https://huggingface.co/litert-community/Qwen3-0.6B-int4
- https://huggingface.co/litert-community/Qwen3-0.6B
- https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/tree/main
- https://github.com/google-ai-edge/gallery/issues/1317

Não baixe ou inclua centenas de megabytes no repositório. Para qualquer fluxo de
download no app, use apenas arquivo `.litertlm` publicado por fonte oficial,
mostre tamanho/licença/fonte e espaço necessário, ofereça Wi-Fi, progresso,
cancelamento e retomada, valide tamanho/hash quando publicado e só então faça
rename atômico para a pasta de modelos. Nunca substitua modelo escolhido ou
apague pesos sem ação explícita. Se isso sair do escopo desta rodada, deixe um
plano concreto e instruções de instalação manual na tela.

No S24 Ultra, compare com o modelo atual usando os mesmos prompts em pt-BR e
registre modelo/commit, arquivo e tamanho, backend escolhido, tempo até primeiro
token, velocidade de geração, saída completa, qualidade de resposta e chamadas de
ferramenta corretas. Meça depois de inicializar o engine separadamente do tempo
de carregamento. Talos/LiteRT não expõe sempre uma divisão confiável entre
prefill e geração; marque qualquer aproximação. Não coloque número estimado na UI
como medição. Não selecione o candidato como padrão sem um teste comparável.

Se “Jev” significar uma persona, mantenha instruções de estilo/decisão num prompt
curto editável e independente dos pesos. Avalie se um modelo 1B consegue seguir
essa persona e o português do usuário; reduza contexto adicional e comprimento da
resposta se isso ajudar, sem apagar histórico ou reduzir guardrails. Modelo menor
não recebe mais permissões e nenhuma resposta do modelo pode alterar o gate.

## Verificação e entrega

- Corrija falhas encontradas e não edite arquivos de protocolo congelados.
- Rode `git diff --check`, depois os testes host descritos no README. Compile o
  app uma vez; registre o resultado literal do build. Se o build falhar por
  ambiente, pare e reporte o erro, sem repetir tentativas que não mudem as causas.
- Faça teste no aparelho apenas se ele estiver disponível e diferencie no resumo
  o que foi compilado, simulado, medido no telefone e ainda está pendente.
- Atualize a documentação e este estado de handoff com nomes de arquivos,
  comandos e resultados reais. Não faça commit, deploy ou publicação.
