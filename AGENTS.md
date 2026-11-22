<!-- HC_V3_PROJECT_START -->
## HorizonCam V6 — Astra Extra High + Sol especializados (Medium e Extra High)
- Escopo tecnico: HorizonCam V3 aprovado. Retomar trabalho AI 2.0 local; preservar diff e branch. Nao iniciar projeto novo.
- Regras de roteamento, ownership, skills, testes e limites: `.codex/HORIZONCAM_ROUTING.md` (abrir apenas quando delegacao complexa).
- **Astra xhigh** orquestra, integra e valida. **Sol xhigh** implementam Camera/Video e AI/GPU complexos e completos. **Sol medium** implementam UI/Android Views, QA/CI e manutencao Kotlin de ponta a ponta.
- Nao limitar Sol por tamanho da tarefa. Nunca dois agentes escrevem no mesmo arquivo. ADB, Gradle pesado, benchmarks e capturas S24 com dono unico autorizado.
- Skills do projeto em `.agents/skills`, carregar so a necessaria. Documentacao detalhada fora do contexto base.
- **Uma foto final publica por disparo**; lente fisica confirmada, zoom correto, preview=JPEG, Horizon Lock de roll preservado, estabilizacao adicional e IA com qualidade perceptiva medida.
- Codex precisa provar resultado por diffs/testes/medidas; nao prometer eficiencia ou hardware disponivel sem confirmacao.
<!-- HC_V3_PROJECT_END -->
