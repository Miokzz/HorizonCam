# HorizonCam V3 — checkpoint de desenvolvimento

21 de novembro de 2026 · branch `phase-1/camera` · base publicada antes deste checkpoint `650059833c81ae1c0e530ae266e0b565c4fa1f97`.

Este ponto de retomada reúne o trabalho local da Horizon AI 2.0, as alterações de câmera e publicação, os testes e o Pack V6. As medições físicas abaixo identificam a versão ensaiada. As imagens do S24 e os logs brutos ficam fora do Git.

## IMPLEMENTADO no código preservado

| Área | Alteração |
| --- | --- |
| Foto e galeria | Foto automática, Noturno e fusão guardam uma referência privada. O worker procura a reserva pendente ou já publicada pelo identificador da captura, reaproveita o JPEG preparado e guarda o original em caso de falha. A consulta ao MediaStore distingue resultado vazio de falha de leitura. A fila pode concluir a publicação de um JPEG já preparado mesmo sob restrição térmica. |
| Lentes, zoom e AF | O ID físico solicitado é propagado aos casos de uso de prévia, foto e vídeo. A rota física aguarda evidência de captura do sensor. Quando essa confirmação falta, a seleção tenta outra rota para o mesmo alvo. Em Foto/JPEG, o controle confirmado e o recorte digital residual governam visor e arquivo. Foram acrescentados tratamento de abertura transitória, invalidação de respostas antigas de AF e área de medição menor em tele. |
| Horizon Lock | A prévia aguarda seu primeiro quadro. A política tenta a ultrawide e preserva o cálculo de roll de 360°. Um estimador de movimento em luminância calcula correções horizontais e verticais após o roll e as aplica ao visor e ao encoder. A correção visual cede quando o HAL informa EIS ativo. |
| Horizon AI 2.0 | QuickSRNet-small e Real-ESRGAN general-x4v3 trabalham com blocos de entrada de 128 × 128. A composição candidata limita contraste sem suporte local e ancora o detalhe estimado nos tons medidos. Capturas nativas nítidas podem dispensar inferência. O runtime mede inicialização, invocação e custo por bloco, compara candidatos com referência de CPU e lê a região útil da saída. |
| Ensaios e configuração | Foram preservados o comparador de campo de visão por correspondências, regressões de publicação e reconstrução, o teste instrumentado de paridade e os ajustes do smoke de EXIF e da sobreposição de ANR do Pixel Launcher. O Pack V6 inclui cinco perfis de agente, nove skills e o roteamento do projeto. |

Os modelos de produção, o ESRGAN do laboratório e o modelo de referência da instrumentação já estavam versionados, com seus avisos e licenças. Os SHA-256 dos modelos QuickSRNet-small e Real-ESRGAN usados na captura conferem com os respectivos manifestos em `app/src/main/assets/ai`.

## TESTADO

| Versão e ensaio | Resultado observado |
| --- | --- |
| Estado final do código, testes locais | `:app:testDebugUnitTest` passou em modo offline, com 91 testes de 18 suítes, zero falhas, erros ou ignorados. Os 16 testes Python de `tools/ci` passaram. |
| Ferramentas de calibração durante a sessão | Passaram os nove testes sintéticos do alvo de horizonte e os três do medidor de zoom. O medidor distingue zoom real, ampliação ausente e cena sem textura suficiente. |
| Compilação durante a integração | Uma compilação intermediária encontrou referências Kotlin enquanto a câmera estava em edição. O build de campo subsequente gerou com sucesso o APK de debug e o APK de instrumentação. O build completo e o smoke do commit final ficam pendentes. |
| S24 SM-S9210, Android 17, build de referência desta sessão, antes da correção óptica | Nos JPEGs de 0,6×, 1×, 3× e 30×, o EXIF reportou 5,4 mm. De 1× para 3× o fator medido foi 1,0346 em 2.901 correspondências consistentes, para 3,0 esperado. De 0,6× para 1× foi 0,9923, para 1,667 esperado. Visor e JPEG concordaram nos enquadramentos de 1× e 3×. A falha estava presente mesmo com essa concordância. |
| Publicação no mesmo build de referência | O teste de recuperação de reserva órfã passou no S24, com uma linha pública e bytes idênticos à referência. A sessão produziu um JPEG para cada um dos quatro disparos ópticos. O teste novo de recuperação de uma linha já publicada, com referência privada danificada, ainda requer execução no aparelho. |
| Vídeo e IA no build de referência | O MP4 com lock apresentou 1920 × 1080, rotação 0°, 30,01 fps e zero timestamps de apresentação duplicados em 487 amostras. Três replays do mesmo JPEG de 3060 × 4080, a 1×, mediram 3,98–4,21 s de restauração, mediana de 4,21 s, com QuickSRNet no delegate GPU de precisão estrita solicitada. Esse intervalo exclui captura e galeria. |

## EXPERIMENTAL

O benchmark parcial de host comparou a política inicial desta sessão ao candidato usando as mesmas entradas degradadas. LPIPS menor indica maior proximidade com a referência nesse ensaio. Os números medem o conjunto de recortes usado no desenvolvimento.

| Recorte | Escala | LPIPS inicial | LPIPS candidato |
| --- | --- | --- | --- |
| Vegetação | 4× | 0,7395 | 0,6714 |
| Tijolo | 4× | 0,4253 | 0,3378 |
| Texto | 4× | 0,3198 | 0,2934 |
| Rosto | 4× | 0,2773 | 0,2753 |
| Tijolo | 2× | 0,0763 | 0,0829 |
| Pelo | 4× | 0,3717 | 0,3785 |

Os dois últimos casos regrediram. A avaliação em recortes reservados, a proteção de texto e identidade em cenas reais e o ganho de latência do novo runtime no S24 continuam em aberto.

## PENDENTE

- Confirmar as saídas físicas e a ampliação do candidato em 0,6×, 1×, 3× e 30×. Comparar visor e JPEG por nível. Registrar resultados físicos do HAL, focal EXIF e escala por pixels, inclusive quando a rota lógica assumir.
- Exercitar AF perto/longe e recuperação de abertura sob disputa da câmera. Incorporar o deslocamento do EIS ao foco por toque no Lock e validar continuidade de sensor antes e depois de cada quadro de uma sequência.
- Medir o EIS visual no S24 durante caminhada, pan e giro completo. Verificar campo útil, estabilidade, cadência, compatibilidade da ultrawide e custo de processamento.
- Corrigir a proveniência em `AutoPhotos` e `BurstJobs` quando `tiles == 0`. O candidato pode preservar detalhe nativo enquanto o EXIF e a mensagem ainda indicam restauração neural. Executar a nova comparação CPU/GPU e medir invocação, restauração e disparo até a galeria separadamente.
- Executar a instrumentação de publicação, lint e smoke no checkpoint. No commit-base, o build remoto passou e o smoke falhou com um diálogo do Pixel Launcher sobre a câmera. O teste local reconheceu o diálogo arquivado. O resultado do workflow desta publicação fica para a retomada.

**Próxima tarefa exata.** Instalar o APK de campo deste checkpoint no S24 via ADB sem fio e fotografar o mesmo alvo fixo em 1× e 3×. Comparar o fator por pixels com `tools/calibration/measure_zoom.py --expected 3`, o ID físico informado e a focal EXIF. Registrar se o fator de 1,0346 observado na referência foi corrigido.
