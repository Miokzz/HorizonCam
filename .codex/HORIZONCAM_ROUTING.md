# HorizonCam V6 — roteamento inteligente Sol Medium / Sol Extra High + Astra Extra High

## Modelos e autonomia
- **Astra `gpt-6.2-astra` / `xhigh`** = orquestrador. Decide arquitetura transversal, atribui ownership, aceita entregas e integra. Nao reimplementar o que o Sol ja testou.
- **Sol `xhigh` — `hc_optics_video_sol`**: CameraX/Camera2, lentes fisicas 0.6/1/3x, FOV 30x, AF, Horizon Lock, OpenGL, EIS. **Owner completo**, inclusive tarefas pesadas/multiarquivo, quando delegado.
- **Sol `xhigh` — `hc_neural_qa_sol`**: Horizon AI, fusao multiframe, GPU/LiteRT, qualidade, MediaStore, benchmarks. **Owner completo**, inclusive tarefas pesadas/multiarquivo, quando delegado.
- **Sol `medium` — `hc_android_ui_sol`**: Android Views/UX, estados de zoom/foco, galeria, layouts e acessibilidade; pode desenvolver features completas.
- **Sol `medium` — `hc_qa_ci_sol`**: testes unitarios/instrumentados, CI, gradle, smoke/EXIF, verificacao e triagem; pode consertar pipeline inteiro.
- **Sol `medium` — `hc_kotlin_maint_sol`**: lifecycle, memoria, callbacks, Worker, docs, testes e manutencao Kotlin; pode implementar tarefas completas no seu dominio.
- `max_concurrent_threads_per_session = 2` e **teto**, nao obrigacao. Nao subir effort/model automaticamente por impulso.

## Roteador custo-beneficio sem piorar engenharia
1. **Astra direto**: pergunta curta ou microtarefa cujo overhead de delegacao custa mais. Skills apenas quando gatilho de dominio evidente.
2. **Sol Medium OWNER (padrao para trabalho delimitado de UI/QA/manutencao)**: pode assumir tarefas completas e longas de sua area, sem limite arbitrario de arquivos. Astra fornece objetivos, ownership, testes e criterios, recebe entrega e revisa pontos relevantes.
3. **Sol Extra High OWNER (padrao para Camera/AI intricados)**: assume componente inteiro, codigo, experimentos e testes ate criterios cumpridos. Nao limitar tamanho ou obrigar a parar em etapa pequena. Astra apenas integra e decide contratos transversais.
4. **Escalar por dificuldade verificada**: se Medium nao consegue devido a Camera2/GPU/algoritmo, encaminhar ao `hc_optics_video_sol` ou `hc_neural_qa_sol` com o resumo, diff e logs relevantes. **Nao iniciar duas vezes o mesmo trabalho.**
5. **Paralelizar quando compensa**: dois subagentes para componentes separados sem arquivos/shared resource simultaneos. Medium QA independente pode revisar apos dono concluir; nao usar dois agentes para opinioes repetidas.
6. **Nao criar agentes-filhos**, loop de reviews ou chain de skills por rotina. Uma skill de dominio + opcional metodologia por tarefa, sem ler as nove sempre.
7. **Economia mensuravel**: reportar tipo de delegacao, resultados, retrabalho, tempo e qualidade; percentuais de creditos dependem da conta/modelo/tamanho de tarefas, nao sao garantias.

## Contrato curto para delegacoes
**OBJETIVO** | **OWNER/ARQUIVOS OU MODULO** | **CRITERIOS DE ACEITE** | **BASELINE OU LOG RELEVANTE** | **PERMISSAO ADB/GRADLE** | **RESULTADO ESPERADO**.
- Se a tarefa for implementacao, `IMPLEMENT_END_TO_END` como padrao; `READ_ONLY` apenas quando pedir auditoria. Subagentes sao capazes e podem projetar/escrever/testar tudo dentro da missao.
- Paths compartilhados (`MainActivity.kt`, `app/build.gradle.kts`, Manifest, AGENTS/.codex) exigem dono unico por fase ou integracao Astra. Sem dois escritores no mesmo arquivo.
- Para ADB, Camera2 real, benchmark GPU ou Gradle pesado, **reserva exclusiva**. Sol pode usar ADB quando Astra autorizar explicitamente, sem tocar dados pessoais nem burlar bloqueio. Sem apagar app/dados/galeria. Nunca `git reset --hard`/`git clean`.
- A branch e os experiments locais AI 2.0 prevalecem sobre estado remoto antigo. Conferir `git status` antes, criar checkpoints seguros, sem publicar/merge sem autorizacao ja definida.
- Confirmar modelo efetivo: se runtime nao suportar `gpt-6.2-sol` medium/xhigh, nao fazer fallback silencioso para Astra caro. Avisar ou operar no Astra sem delegacao.

## Aceite do HorizonCam V3
- **Uma foto por disparo**: unico JPEG final publico, entrada privada temporaria, retry idempotente, fallback unico se rede neural falhar.
- **Optica verdadeira**: zoom total sempre relativo a 1x, 0.6 ultrawide / 1–<3 principal / 3–30 tele preferencial quando HAL permite; medir FOV de 30x, sensor fisico ativo, preview vs JPEG.
- **Horizon Lock**: algoritmo de roll 360 aprovado preservado, ultrawide preferencial, EIS independente para tremor translacional; nada de distorcao ou supor OIS no ultrawide.
- **Horizon AI**: resultados perceptiveis, avaliar textura real e artefatos em texto/rostos, GPU realmente utilizada, tempo inferencia vs shutter-gallery claramente separados.
- **Bugs conhecidos**: `Abrindo camera...`, zoom aparente 1/2/3x quase igual, persiana competindo no AF, erro camera 2; falha antiga smoke `AI JPEG must describe actual input/output dimensions`.

## Skills selecionadas (carregamento sob demanda)
- Optica Camera2: `.agents/skills/lens-zoom-forensics/SKILL.md`
- Horizon Lock: `.agents/skills/horizon-lock-stabilization/SKILL.md`
- AI: `.agents/skills/horizon-ai-evaluation/SKILL.md` + `.agents/skills/photographic-quality-gates/SKILL.md` **apenas quando avaliando resultados**
- ADB: `.agents/skills/s24-adb-validation/SKILL.md`
- Lifecycle: `.agents/skills/android-kotlin-lifecycle/SKILL.md`
- Depuracao por causa: `.agents/skills/systematic-camera-debug/SKILL.md`
- UI Views: `.agents/skills/camera-ui-views/SKILL.md`
- CI: `.agents/skills/android-ci-evidence/SKILL.md`
- **Nao instalar automaticamente bibliotecas aleatorias da internet**. Fontes e sugestoes em `SKILLS_FONTES_PESQUISA.md` na raiz do pack.
