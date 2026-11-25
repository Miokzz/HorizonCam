# Otimização do RAW ISP no Snapdragon 8 Gen 3

Bancada isolada do checkpoint `c7d1d07`, validada em 25 de novembro de 2026 no Galaxy S24 SM-S9210 com o SM8650, Adreno 750 e firmware S9210ZHU6EZJD. O APK de laboratório mantém os caminhos Java e C++ selecionáveis. Os dois carregam o mesmo modelo de 27.620 bytes, SHA-256 `5071b54120e71c0dae7669efff1cbc4862cb8fba377bcd33a90f558e7ade820b`. A captura de produção e a Foto automática estão fora do APK. A origem dos quatro pares e as renderizações AHD, MHC + Wiener + GainMaps, neural e Samsung JPEG seguem registradas nos [ensaios anteriores](TELE_RAW_ON_DEVICE.md).

## Gargalos e implementação

No checkpoint anterior, a GPU relaxada gastou 2.953 ms para produzir 10 MP sRGB. Desses, 349 ms ficaram nas invocações da rede, 605 ms nos mapas, 72 ms na cópia de entrada, 692 ms na remontagem e correção RAW, 164 ms nos GainMaps e 1.082 ms no MHC e sRGB. A repetição A/B abaixo mede novamente a referência Java no mesmo APK.

- **Entrada e mapas de ruído.** O caminho nativo lê por mapeamento direto o mosaico linear GBRG do arquivo, evitando a cópia de 38,1 MiB para o heap Java. Prepara σ em área reutilizável de cerca de 289 KiB, com reflexão 101 na janela 3 × 3 das fases empacotadas. O NEON processa as quatro fases em paralelo. A soma, a divisão e a raiz conservam a ordem das operações. O resultado preenche diretamente o buffer de entrada do interpretador. Os pixels fora do raster ficam a zero no bloco da rede, como no Java.
- **Remontagem.** A rede conserva blocos úteis de 128 × 128 e halo de quatro posições empacotadas. A saída direta é lida no C++ e o residual é limitado a três σ com proteção de saturação. A área útil de cada bloco é gravada uma vez no mosaico completo. Os blocos parciais usam a largura e a altura úteis reais.
- **GainMaps e MHC.** Uma janela circular de cinco linhas limita o intermediário do demosaico a cerca de 71 KiB. As áreas de ruído e de linhas substituem o buffer de quadro inteiro de 38,1 MiB compartilhado entre essas etapas em Java. O ganho bilinear usa os mesmos centros de fotossítios e as quatro tabelas do DNG. Cada linha corrigida é produzida ao entrar na janela. O MHC usa as mesmas equações e reflexão nas bordas. A matriz de cor, a exposição de +1,5 EV e a LUT sRGB de 65.536 entradas são comuns aos dois caminhos.
- **Precisão.** O build ARM64 usa NDK 27.2.12479018, `-O3` e `-ffp-contract=off`. A ordem dos termos nas operações de ponto flutuante foi preservada. O mosaico RAW final continua disponível como float32 assinado e o verificador compara seu SHA-256 e o SHA-256 do RGB do aparelho, por par e backend.

## Protocolo

- Mesmo APK, grafo, quatro arquivos de entrada verificados por SHA-256 e quatro backends. A ordem dentro de cada par Java/C++ foi alternada entre backends. O foco são CPU FP32, OpenCL estrito, OpenCL com FP16 permitido e HTP FP16.
- Três processos novos por caminho para inicialização e primeira imagem. Dez repetições aquecidas no par de detalhe A (9.980.928 fotossítios). Os outros três pares são processados e conferidos integralmente. O ensaio registra RSS pico, PSS amostrado e temperatura da bateria. São tempos de bancada com o agendamento normal do Android, sem fixar frequência ou afinidade.
- A latência completa vai dos mapas de ruído até o RGB em RAM. Leitura do arquivo, gravação de evidência e transferência ADB são externas ao cronômetro. A preparação, a cópia de entrada, a remontagem, os GainMaps e o demosaico têm cronômetros separados. A referência e o candidato também registram a duração de `Invoke` nativo reportada pelo LiteRT. A diferença entre o tempo de `Interpreter.run` e esse intervalo mede o trabalho externo ao `Invoke`, incluindo marshalling e cópias da ponte Java. As esperas e as transferências internas do delegate seguem dentro de `Invoke`. [Implementação do cronômetro LiteRT](https://github.com/tensorflow/tensorflow/blob/master/tensorflow/lite/java/src/main/java/org/tensorflow/lite/NativeInterpreterWrapper.java)

## Resultados A/B de 10 MP

A primeira versão C++ deixou a divisão e a raiz escalares, conforme a inspeção do binário. Com a tela apagada nos dois caminhos, ela reduziu a mediana da GPU relaxada de 2.999 para 1.234 ms. A comparação escalar da GPU estrita teve mudança no estado de tela. A última rodada usou a versão NEON com a tela ativa nas oito séries abaixo. O binário final usa `fdiv` e `fsqrt` vetoriais de quatro posições. O experimento mede o efeito do pipeline completo, incluindo o NEON, e conserva as duas rodadas localmente.

| Backend | Java p50 / p95, ms | C++/NEON p50 / p95, ms | Redução da mediana | Bateria Java → NEON |
| --- | ---: | ---: | ---: | ---: |
| CPU FP32 | 3292 / 3297 | 1124 / 1162 | 65,9% | 33,9 → 33,9 °C |
| GPU estrita | 3095 / 3188 | **651 / 856** | **79,0%** | 32,1 → 32,9 °C |
| GPU com FP16 permitido | 2990 / 3139 | 939 / 1005 | 68,6% | 32,9 → 32,9 °C |
| QNN HTP FP16 | 3764 / 3834 | 1412 / 1502 | 62,5% | 34,2 → 33,9 °C |

Cada processo acelerado confirmou quatro convoluções delegadas em uma partição e zero no CPU. O nível térmico Android permaneceu em 0 nas repetições. A temperatura da bateria variou de 32,1 a 34,2 °C na série final. Os backends foram executados em momentos diferentes, e a amostragem é curta para avaliar carga sustentada.

| Backend | Inicialização Java → NEON, ms p50 / p95 | Carregamento do primeiro arquivo Java → NEON, ms p50 | Primeira imagem Java → NEON, ms p50 / p95 |
| --- | ---: | ---: | ---: |
| CPU FP32 | 17 / 23 → 39 / 45 | 30 → 10 | 3633 / 3634 → 1177 / 1181 |
| GPU estrita | 175 / 263 → 184 / 217 | 28 → 6 | 3354 / 3452 → 1125 / 1129 |
| GPU relaxada | 238 / 354 → 255 / 263 | 25 → 6 | 3237 / 3262 → 980 / 988 |
| QNN HTP FP16 | 1184 / 1206 → 1034 / 1359 | 24 → 6 | 3929 / 3991 → 1449 / 1537 |

O carregamento Java inclui a cópia do mosaico para o heap. A abertura nativa mapeia o arquivo, e a primeira imagem pode incluir faltas de página à medida que lê os dados. Inicialização, carregamento e primeira imagem são cronometrados separadamente.

| Etapa, mediana em ms | GPU estrita Java → NEON | GPU relaxada Java → NEON |
| --- | ---: | ---: |
| Preparação do mapa de ruído | 606,3 → 50,4 | 606,1 → 154,8 |
| Preenchimento e cópia de entrada | 79,5 → 12,9 | 75,3 → 38,3 |
| `Invoke` nativo, incluindo sincronização interna | 384,1 → 339,9 | 341,6 → 414,9 |
| Trabalho na ponte fora do `Invoke` | 14,5 → 17,2 | 13,9 → 42,1 |
| Remontagem e correção dos fotossítios | 771,3 → 19,2 | 716,1 → 55,4 |
| GainMaps | 165,7 → 28,7 | 162,5 → 30,8 |
| MHC e sRGB | 1066,0 → 176,0 | 1066,6 → 188,4 |

O ganho vem sobretudo das etapas do ISP e da remontagem. A latência do quadro inteiro caiu também em modos com maior tempo em `Invoke`, como GPU relaxada e HTP. O tempo da ponte inclui cópia e outras ações do interpretador. A decomposição entre execução e espera internas permanece indisponível.

| Backend | RSS pico Java → NEON | PSS máximo durante repetições Java → NEON | PSS máximo da série Java → NEON |
| --- | ---: | ---: | ---: |
| CPU FP32 | 402 → 322 MiB | 181 → 142 MiB | 181 → 254 MiB |
| GPU estrita | 421 → 318 MiB | 218 → 176 MiB | 231 → 264 MiB |
| GPU relaxada | 422 → 345 MiB | 217 → 179 MiB | 217 → 268 MiB |
| QNN HTP FP16 | 435 → 365 MiB | 213 → 181 MiB | 222 → 262 MiB |

O RSS de pico e o PSS das repetições de um par diminuíram. O PSS no fim da passagem pelos quatro arquivos aumentou no nativo. O caminho usa mapeamentos gerenciados pela JVM, que podem permanecer residentes até a coleta. O PSS é agregado por processo. A memória interna do acelerador permanece sem medição.

## Qualidade e energia

Nos 16 casos, quatro pares em cada backend, **os mosaicos float32 e o RGB do aparelho foram idênticos byte a byte** entre Java e NEON. Os 16 mosaicos também coincidiram com os arquivos do checkpoint `c7d1d07`. Frente à CPU FP32, o maior p95 RAW entre os quatro pares foi de 1,49 × 10⁻⁸ na GPU estrita, 8,19 × 10⁻⁵ na GPU relaxada e 3,43 × 10⁻⁵ no HTP. Os maiores p95 sRGB foram respectivamente 0, 1 e 0 nível. Os três modos passaram a tolerância original nos quatro pares.

O A/B de imagem conserva o MHC + Wiener + GainMaps e o JPEG Samsung como referências. Nos pares com texto, o Wiener ainda controla melhor a granulação da cena. O JPEG conserva maior legibilidade nas letras finas. O ganho do NEON é de latência e movimentação de dados.

Em uma execução separada da GPU relaxada otimizada, o Perfetto registrou 33 pacotes de bateria em 8 s. O pacote `PowerRails` continha zero descritores e zero amostras de energia. O contador bruto de bateria segue sem resolução suficiente para comparar backends nessas janelas. A energia por backend segue sem estimativa confiável. [Fonte de dados de bateria e trilhos do Perfetto](https://perfetto.dev/docs/data-sources/battery-counters)

## Execução contínua na GPU

O S24 expôs a Adreno 750 e a API Vulkan 1.3.128 pelo `cmd gpu vkjson`. O driver anunciou `VK_ANDROID_external_memory_android_hardware_buffer` e `VK_KHR_external_semaphore_fd`. A bancada usa o OpenCL confirmado pelo log do delegate. Os buffers diretos eliminam as cópias para arrays intermediários Java. A interface do interpretador ainda movimenta 165 blocos com 136 × 136 × (8 + 4) valores FP32, cerca de 140 MiB de entrada e saída lógica por quadro. O tráfego físico do barramento e a sincronização interna do delegate ficaram fora da medição separada. A interoperabilidade entre essas APIs ficou sem validação no aparelho.

O LiteRT CompiledModel documenta buffers residentes OpenCL/OpenGL e execução assíncrona. Uma avaliação dessa rota precisaria medir as cópias restantes, alocar mapas, halos e saídas no mesmo contexto do delegate, validar os GainMaps e o MHC no dispositivo e repetir a auditoria de delegação e de precisão estrita. Um renderizador Vulkan separado ainda precisaria provar a interoperabilidade com a rede. O A/B desta etapa mantém o runtime fixo para atribuir seus ganhos ao ISP e à movimentação de dados no host. [Interoperabilidade e zero-copy no LiteRT](https://developers.google.com/edge/litert/next/gpu)

## Reprodução

Use os pacotes privados preparados pelo [benchmark anterior](TELE_RAW_ON_DEVICE.md#reprodução). Defina `$python`, `$adb`, `$fixtures` (diretório com `fixtures.json` e os quatro `.rawbench`), `$model` (LiteRT FP32 original), `$previous` (diretório de renderizações do ensaio RAW-aware) e `$out` (diretório local novo).

```powershell
sdkmanager 'ndk;27.2.12479018' 'cmake;3.22.1'
.\gradlew.bat -p tools/calibration/raw_device assembleDebug lintDebug testDebugUnitTest
$apk = (Get-ChildItem tools/calibration/raw_device/build/outputs/apk/debug -Filter '*.apk' -File).FullName
& $adb devices -l
& $python tools/calibration/raw_device_run.py --adb $adb install --apk $apk --model $model --fixtures $fixtures
& $python tools/calibration/raw_device_run.py --adb $adb collect --implementation both --out "$out/ab" --modes gpu gpu-fp16 cpu qnn --repeats 10 --cold 3 --energy-seconds 0
& $python tools/calibration/raw_device_io.py compare --fixtures "$fixtures/fixtures.json" --runs "$out/ab" --output "$out/parity.json" --modes gpu gpu-fp16 cpu qnn
& $python tools/calibration/raw_device_io.py score --fixtures "$fixtures/fixtures.json" --baseline $previous --runs "$out/ab/native" --output "$out/quality.json" --modes cpu gpu gpu-fp16 qnn
```

Quando o sandbox da bancada já contém o grafo e os quatro pacotes, o passo de instalação pode usar apenas `adb install -r` com a assinatura de debug compatível. A bancada preserva os arquivos originais e grava os resultados em diretórios de execução novos. O relatório de desempenho pode ser refeito offline com `raw_device_run.py report --runs "$out/ab/native" --output "$out/performance-native.json" --modes gpu gpu-fp16 cpu qnn`, e o mesmo comando para `ab/java`.

Passaram `assembleDebug`, `lintDebug`, 4 testes JVM da bancada e 59 testes Python com os DNGs reais habilitados. O APK da rodada final tem SHA-256 `45382deafd014000868634a98ca0ab6a11c263061711daa958c9a287b0bdfcd7`.
