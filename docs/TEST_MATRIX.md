# Plano e relatório de testes

**Ambiente de CI:** GitHub Actions Ubuntu, JDK 17, SDK 36, Gradle 9.6. Nenhum Galaxy S24/S26 real está conectado a este ambiente.

## Automatizados (CI)

`gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`

Abrange:
- Fronteira de +179/-179 e interpolation temporal.
- Interpolação entre amostras de timestamps e fallback quando câmera não tem timestamp comparável.
- Giro sintético de 360° e conservação do eixo de gravação.
- Confiança de gravidade baixa com predição do giroscópio.
- Crop constante e prova geométrica de cobertura para os quatro vértices em várias rotações.
- Identidade de FrameGeometry das surfaces Preview/Video por timestamp.

O teste com GPU física, HAL e ISP não é simulável pelos testes JVM.

## Procedimento com Galaxy S24 (a executar)

| Código | Procedimento | Aceitação | Executado |
|---|---|---|---|
| A | Inicialize vertical, selecione H LOCK e gire para horizontal e de volta | Preview sem salto, UI legível | **Pendente hardware** |
| B | Rotacione 0°→45°→90°→135°→180°→225°→270°→315°→360° e retorne | Sem inversões, recorte fixo, margens não pretas | **Pendente hardware** |
| C | REC, gire 360°, STOP, abra Galeria | Orientação visível igual à preview, áudio, MP4 íntegro | **Pendente hardware** |
| D | Inicie REC em paisagem, retrato e cabeça para baixo | Sem calibração; saída de H LOCK coerente | **Pendente hardware** |
| E | Caminhe, corra, incline e gire | Distinguir roll lock vs tremor de translação | **Pendente hardware** |
| F | Gravações de 5/10 minutos | Temperatura aceitável, não há ANR, A/V sincronizados | **Pendente hardware** |
| G | Toque AF/AE, AE-slider, flash, 0,6/1/2/3, pause, FHD/UHD, gallery | Ações reais, botões impossíveis desabilitados | **Pendente hardware** |

## Evidências para diagnóstico

Ative indicador longo: pressione e segure **H LOCK**. Mostra `P` (preview conectada), `V` (encoder conectado), `F` frames, `E` erros do GL e `SYNC/LATEST` (sensor frame timestamp comparável ou não), idade estimada do último sensor e modo EIS.

Para registrar em PC:

```powershell
adb logcat -c
adb logcat -s HorizonCamGL HorizonCamMetadata CameraX:* > camera-log.txt
```

Valide arquivo:

```bash
ffprobe -v error -select_streams v:0 -show_entries stream=width,height,avg_frame_rate,side_data_list -show_entries format=duration -of json video.mp4
```

O valor rotation pode estar nos *side data*; largura/altura codificados não são, isoladamente, orientação final de exibição. Não afirmar teste de hardware como aprovado até receber arquivo e logs.
