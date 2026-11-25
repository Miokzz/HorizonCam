# Horizon AI RAW no Galaxy S24

Bancada isolada do checkpoint `0712e96`, validada em 25 de novembro de 2026 no Galaxy S24 SM-S9210 (SM8650, firmware S9210ZHU6EZJD). O APK tem o próprio pacote `com.miokzz.rawbench`, sem acesso à câmera ou à galeria. A entrada vem exclusivamente dos quatro pares já validados do [laboratório RAW](TELE_RAW_LAB.md). O [ISP clássico MHC + Wiener + GainMaps](TELE_RAW_ISP.md) e o JPEG Samsung pareado são as referências de imagem.

## Contrato do modelo e da execução

| Grafo | Bytes | Entrada → saída | Operadores |
| --- | ---: | --- | --- |
| FP32 canônico, SHA-256 `5071b54120e71c0dae7669efff1cbc4862cb8fba377bcd33a90f558e7ade820b` | 27.620 | 1 × 136 × 136 × 8 → 1 × 136 × 136 × 4, float32 | 4 × CONV_2D v1, ReLU fundida nas três primeiras |
| INT8 de controle, SHA-256 `4a9f2e4be20c94c614b9ed8a6df04412a266f0f4e21decb7148ad75c86a07ce7` | 10.248 | Mesmas dimensões, int8 na entrada e na saída | 4 × CONV_2D v3 |

Os pesos PyTorch têm 6.388 parâmetros e SHA-256 `4a3f0fb91a163e229dbe5489c0d45f9432687e6d6b8bc28be21e85af94123190`. O checkpoint anterior verificou erro máximo de **1,06 × 10⁻⁷** entre PyTorch e LiteRT CPU no espaço RAW linear para três entradas sintéticas. A bancada Android verifica o SHA-256 do grafo antes de abrir o interpretador. A conversão INT8 é um artefato separado, calibrado em 64 blocos Bayer procedurais com semente 9101. O grafo canônico é o mesmo para CPU, GPU e QNN. [Experimento de origem](TELE_RAW_AWARE_RESTORATION.md)

O host valida cada manifesto e DNG antes de empacotar o mosaico linear GBRG, perfil de ruído calibrado, matrizes e GainMaps. O S24 monta mapas de desvio-padrão por fase CFA, invoca a rede em 165 blocos de 128 × 128 em coordenadas empacotadas, descarta o halo de quatro posições e remonta os fotossítios. O residual tem limite de três desvios-padrão e atenuação na saturação. Depois, o dispositivo aplica os GainMaps, demosaico MHC, matriz de cor do DNG e conversão para sRGB com +1,5 EV. A leitura do DNG em TIFF e a orientação dos quatro arquivos pertencem à preparação. A bancada recebe todos com orientação normal e mede o sRGB de 3648 × 2736 em RAM. Escrita de artefatos, transporte ADB e codificação JPEG ficam fora da latência de processamento.

CPU usa LiteRT 1.4.2 com XNNPACK habilitado e quatro threads. GPU usa o delegate LiteRT da mesma versão com perda de precisão desabilitada ou permitida. O segundo modo permite FP16 no backend e mantém IO FP32. O QNN 2.34.0 solicita HTP FP16 e declara `libcdsprpc.so`, exposta na lista de bibliotecas públicas do firmware. O APK contém a referência Maven ao SDK Qualcomm. As bibliotecas da Qualcomm e os APKs de bancada ficam fora do Git. [Delegate GPU](https://developers.google.com/edge/litert/android/gpu) · [QNN no LiteRT](https://developers.google.com/edge/litert/android/npu/qualcomm) · [Bibliotecas nativas compartilhadas Android](https://developer.android.com/guide/topics/manifest/uses-native-library-element)

## Amostragem

- Três processos novos por modo, com primeira inferência, primeira imagem, inicialização do delegate, criação do interpretador e alocação. O teste força a parada apenas do próprio pacote de bancada. Cache de biblioteca do sistema e disco podem persistir.
- Dez quadros aquecidos de **9.980.928 fotossítios** no primeiro par de detalhe. O p95 usa interpolação linear. A soma das invocações mede o tempo dentro de `Interpreter.run`, incluindo as transferências síncronas do runtime. As cópias e a montagem da aplicação têm cronômetros próprios.
- Outros três pares passam por uma revelação e são avaliados junto ao primeiro, com a mesma exposição, enquadramento e modelo de cor. A comparação numérica absoluta de formato usa a saída FP32 CPU no aparelho. Os JPEGs Samsung são referência prática, sem um RAW limpo real para calcular PSNR absoluto.
- Memória de processo usa `VmHWM` (RSS pico) e PSS amostrado após cada quadro, sem isolar buffers da GPU/DSP. Sessões de carga e ociosidade amostram corrente, tensão, carga, temperatura de bateria e nível térmico Android a cada 200 ms. O procedimento usa o contador de energia quando disponível e a variação do contador de carga multiplicada pela tensão média como alternativa. Isso mede a descarga do **aparelho inteiro**. A energia de subsistemas exige uma leitura atribuível aos trilhos. [Android BatteryManager](https://developer.android.com/reference/android/os/BatteryManager) · [Power Profiler](https://developer.android.com/studio/profile/power-profiler)
- Para recomendar uma redução de precisão, a triagem exige p95 até um nível do sensor no RAW (1/958,67 no espaço normalizado), até um nível sRGB na imagem e inspeção de letras e contornos em escala nativa. As diferenças para MHC + Wiener e JPEG são avaliadas mesmo quando os candidatos seguem de perto a saída CPU.

## Resultados no aparelho

### Delegação, latência e memória

Os logs do LiteRT registraram quatro operações no OpenCL e quatro no Qualcomm HTP, em uma partição cada para cada processo. Os resultados do plano de execução e a ausência de nós de CPU se repetiram nas três partidas por modo acelerado. O CPU executou suas quatro convoluções via XNNPACK. O primeiro ensaio QNN falhou ao carregar `libcdsprpc.so`. Após declarar a biblioteca pública do firmware no manifesto da bancada, o QNN HTP compilou o grafo FP32 e concluiu as inferências com a opção FP16. As medições da tabela usam o APK final.

| Modo | Nós acelerador / CPU | Inicialização total ms p50 / p95 | Alocação de tensores e buffers ms p50 / p95 | Primeira imagem ms p50 / p95 | Imagem aquecida ms p50 / p95 | RSS pico / PSS amostrado MiB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| CPU XNNPACK FP32 | 0 / 4 | 16,18 / 26,58 | 0,51 / 0,56 | 3617 / 3621 | 3307 / 3329 | 400 / 215 |
| GPU OpenCL estrita | 4 / 0 | 167,24 / 261,41 | 0,85 / 0,88 | 3167 / 3279 | 3163 / 3369 | 423 / 230 |
| GPU OpenCL com FP16 permitido | 4 / 0 | 241,45 / 248,97 | 0,96 / 0,98 | 3257 / 3287 | **2953 / 3144** | 423 / 215 |
| QNN HTP FP16 | 4 / 0 | 1146,41 / 1192,29 | 1,10 / 1,37 | 3705 / 3976 | 3793 / 3871 | 444 / 223 |
| CPU XNNPACK INT8 | 0 / 4 | 14,65 / 22,20 | 0,46 / 0,50 | 6043 / 10916 | 5704 / 5830 | 396 / 174 |

A primeira imagem exclui a criação do motor e a leitura do pacote RAW, cronometradas à parte. A inicialização inclui as alocações em Java e contém a criação do delegate, do interpretador e a compilação do grafo. O relatório local registra esses tempos separadamente. As medianas de primeira invocação foram 6,96 ms na CPU FP32, 11,36 ms na GPU estrita, 13,62 ms na GPU relaxada, 7,53 ms no HTP e 2,52 ms na CPU INT8.

| Modo | Invocação por bloco p50 / p95, ms | Soma das invocações por imagem p50 / p95, ms | Mapas de ruído p50 / p95 | Cópia de entrada p50 / p95 | Remontagem e correção RAW p50 / p95 | GainMaps p50 / p95 | MHC e sRGB p50 / p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| CPU FP32 | 5,39 / 5,47 | 890 / 899 | 608 / 612 | 56 / 57 | 509 / 518 | 153 / 153 | 1092 / 1093 |
| GPU estrita | 2,72 / 3,43 | 418 / 467 | 606 / 606 | 83 / 96 | 808 / 943 | 170 / 177 | 1085 / 1085 |
| GPU relaxada | 2,32 / 3,00 | 349 / 415 | 605 / 607 | 72 / 81 | 692 / 790 | 164 / 174 | 1082 / 1084 |
| QNN HTP FP16 | 3,67 / 4,15 | 618 / 628 | 606 / 607 | 123 / 130 | 1182 / 1246 | 176 / 182 | 1085 / 1086 |
| CPU INT8 | 1,71 / 1,80 | 284 / 286 | 608 / 608 | 2320 / 2323 | 1253 / 1257 | 153 / 160 | 1085 / 1212 |

Os tempos de estágio estão em ms. A GPU relaxada reduziu em cerca de 61% o tempo de rede por imagem e em **10,7%** a latência total mediana frente à CPU FP32. O HTP acelerou a rede em cerca de 31%, porém levou mais tempo no quadro inteiro nesta ordem de ensaio. Em todos os modos acelerados, a CPU ainda executa mapas, cópias, correção de fotossítios e ISP. No INT8, o tempo da cópia de entrada inclui quantização e a remontagem inclui desquantização. A bancada mede processamento em lote na implementação Java de referência, com uma única ordem de ensaio e carga do sistema não controlada.

### Precisão e imagem

| Modo | Maior p95 RAW nos quatro pares | Maior p95 sRGB / máximo absoluto | Dentro dos limites numéricos |
| --- | ---: | ---: | ---: |
| GPU estrita | 1,49 × 10⁻⁸ | 0 / 1 | 4 de 4 |
| GPU com FP16 permitido | 8,19 × 10⁻⁵ | 1 / 10 | 4 de 4 |
| QNN HTP FP16 | 3,43 × 10⁻⁵ | 0 / 5 | 4 de 4 |
| CPU INT8 | 1,51 × 10⁻³ | 5 / 62 | 0 de 4 |

O contraste numérico usa as diferenças absolutas em relação à CPU FP32 Android. As quatro saídas sRGB CPU também reproduziram a bancada PyTorch anterior com p95 zero e máximo de um nível. A implementação MHC, GainMaps e sRGB no Android apresentou p95 zero e máximo de um nível frente à revelação dos mesmos mosaicos no host no primeiro par. Os logs confirmam as partições GPU. A precisão relaxada do OpenCL permite FP16, sem expor a precisão de cada operação interna.

Na foto real, a rede FP32 na CPU ficou em ruído residual **4,07 / 4,13** e gradiente de borda **10,45 / 10,79** nos pares A/B. O MHC + Wiener ficou em **2,80 / 2,86** e **9,57 / 9,84**. O JPEG Samsung apresentou **2,73 / 2,72** de ruído e **12,20 / 12,67** de borda. A diferença de cor mediana ΔE76 para o JPEG foi **13,26 / 13,33** na rede e **11,39 / 11,47** no Wiener. Maior gradiente pode acompanhar granulação residual. Nos recortes nativos de 340 × 275, o JPEG manteve melhor separação dos traços finos. O Wiener deixou superfícies coloridas mais uniformes. A granulação cromática permaneceu nas saídas neurais, inclusive nos dois planos brancos. Os dois pares de detalhe mostram a mesma cena, sem referência calibrada de cor ou HDR.

Os recortes CPU FP32, GPU estrita, GPU relaxada e QNN mostraram o mesmo padrão de letras e contornos nos pontos inspecionados. Nos planos brancos, a mediana RGB das renderizações neurais ficou aproximadamente em 167/166/161 e a do Wiener em 167/166/160. O INT8 ultrapassou a tolerância RAW em todos os pares e chegou a cinco níveis sRGB no p95 de detalhe. O ruído residual medido no INT8 subiu para 4,12 / 4,25, com ΔE76 de 13,34 / 13,55. O experimento descarta essa quantização como candidata.

### Descarga da bateria e temperatura

| Modo | Temperatura de bateria observada | Descarga em repouso e duração | Descarga em carga e duração | Quadros em carga |
| --- | ---: | ---: | ---: | ---: |
| CPU FP32 | 28,0–29,9 °C | 49,7 J em 20,0 s | 100,0 J em 23,1 s | 7 |
| GPU estrita | 29,9–31,1 °C | 149,8 J em 20,0 s | 49,9 J em 21,8 s | 7 |
| GPU relaxada | 31,1–31,6 °C | 149,1 J em 20,0 s | 49,7 J em 20,7 s | 7 |
| QNN HTP FP16 | 28,0 °C | Nenhum incremento observado em 20,0 s | 100,2 J em 22,9 s | 6 |
| CPU INT8 | 31,6–32,2 °C | Nenhum incremento observado em 20,0 s | 98,3 J em 22,8 s | 4 |

Não houve indicação de mitigação térmica pelo Android nas janelas medidas (`thermalStatus=0`). A bateria aqueceu ao longo da série. Os ensaios de carga duraram aproximadamente 20 s e a temperatura medida é a da bateria.

A sonda Perfetto de 3,5 s obteve 15 registros `BatteryCounters` e um pacote `PowerRails` sem descritores ou amostras de energia. O `BatteryManager` também retornou o contador direto de energia como indisponível. A tabela usa apenas a variação da carga em µAh e a tensão de bateria média. O contador avançou em degraus de 3.685 µAh, cerca de 50 J por degrau. O valor instantâneo de corrente retornado divergiu da escala indicada pelo contador e foi guardado como leitura bruta. A tela permaneceu interativa nas janelas FP32 e estava apagada nas janelas INT8. Com degraus desse tamanho, oscilação entre janelas e tela em estados diferentes, a medição é insuficiente para ordenar CPU, GPU e HTP por consumo ou isolar a energia da rede. [Perfetto, medição de bateria e trilhos](https://perfetto.dev/docs/data-sources/battery-counters)

### Decisão

O mesmo grafo mostrou execução integral na GPU e no HTP do S24. A GPU estrita deu a paridade mais próxima da CPU. Permitir FP16 na GPU produziu o menor tempo total mediano nesta bancada, com p95 de um nível sRGB nos quatro pares. O HTP é funcional e tem boa paridade, com inicialização mais cara e maior latência integral medida. MHC + Wiener + GainMaps continua sendo a referência fotográfica mais equilibrada para os pares estudados. Os dados sustentam manter a rede no laboratório e usar os custos de preparação, cópia e renderização na avaliação futura.

## Reprodução

Use o Python de restauração descrito no [laboratório anterior](TELE_RAW_AWARE_RESTORATION.md#reprodução-e-testes). Aponte `$detailA`, `$detailB`, `$flatA` e `$flatB` para os diretórios dos pares, `$previous` para a bancada de `0712e96`, `$adb` para o executável ADB e `$out` para um diretório novo fora do Git. A opção `-PrawQnnDir=<diretório>` permite usar os AARs oficiais 2.34.0 em um build offline. Sem essa opção, o Gradle resolve as dependências oficiais no Maven Central.

```powershell
New-Item -ItemType Directory -Path $out
.gradlew.bat -p tools/calibration/raw_device assembleDebug lintDebug testDebugUnitTest
$apk = (Get-ChildItem tools/calibration/raw_device/build/outputs/apk/debug -Filter '*.apk' -File).FullName
$weights = "$previous/training/bayer-residual.pt"
$canonical = "$previous/litert/bayer-residual-fp32.tflite"
& $python tools/calibration/quantize_tele_raw_device.py --weights $weights --out "$out/int8.tflite"
& $python tools/calibration/raw_device_io.py prepare --detail-a $detailA --detail-b $detailB --flat-a $flatA --flat-b $flatB --model $canonical --training "$previous/training/training.json" --out "$out/fixtures"
& $adb devices -l
& $python tools/calibration/raw_device_run.py --adb $adb install --apk $apk --model $canonical --int8 "$out/int8.tflite" --fixtures "$out/fixtures"
& $python tools/calibration/raw_device_run.py --adb $adb collect --out "$out/runs" --modes qnn cpu gpu gpu-fp16 int8
& $python tools/calibration/raw_device_run.py report --runs "$out/runs" --output "$out/performance.json"
& $python tools/calibration/raw_device_io.py score --fixtures "$out/fixtures/fixtures.json" --baseline "$previous/validated" --runs "$out/runs" --output "$out/quality.json" --modes cpu gpu gpu-fp16 int8 qnn
```

Selecione um ADB Wi-Fi específico com `--serial` antes do subcomando quando houver vários dispositivos. O coletor copia os arquivos para dentro do sandbox do APK e confere seus hashes. Os diretórios por execução contêm tempos por invocação, log restrito ao PID da bancada, mosaicos lineares resultantes e o RGB do primeiro par para verificar o ISP Android. `quality.json` traz a diferença RAW e RGB, alinhamento, ruído, bordas e diferenças de cor. Os arquivos originais permanecem nos diretórios de laboratório.

**Verificação.** Passaram `assembleDebug`, `lintDebug`, os quatro testes JVM da bancada e os 58 testes Python, com os quatro DNGs de referência habilitados. Os cinco modos completaram 10 quadros aquecidos, três partidas em processos separados, as janelas de bateria e a revelação dos quatro pares. O APK usado tem SHA-256 `7574ebd26ee12ace03d365270a5ee5d8bea671d18716c87fcb2bbb9385c953b1`.
