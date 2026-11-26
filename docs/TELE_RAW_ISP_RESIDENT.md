# RAW ISP residente na Adreno 750

Ensaio de 26 de novembro de 2026, a partir de `956213d`, no Galaxy S24 SM-S9210 com Snapdragon 8 Gen 3 e firmware S9210ZHU6EZJD. O APK `com.miokzz.rawbench` reusa os quatro pares reais da tele física `0/6` e o grafo FP32 de 27.620 bytes, SHA-256 `5071b54120e71c0dae7669efff1cbc4862cb8fba377bcd33a90f558e7ade820b`. O modelo, a preparação dos DNGs e a referência Samsung JPEG estão descritos no [ensaio on-device](TELE_RAW_ON_DEVICE.md). A rota residente é exclusiva da bancada.

## Arquiteturas medidas

- `java` e `native` conservam o LiteRT 1.4.2 com GPU estrita. A referência C++/NEON prepara os mapas de ruído no CPU e aplica GainMaps e MHC com uma janela de cinco linhas. [Baseline C++/NEON](TELE_RAW_ISP_OPTIMIZED.md)
- `resident` usa LiteRT CompiledModel 2.4.0, FP32 e OpenCL. Os kernels próprios escrevem no `cl_mem` de entrada e leem o de saída do LiteRT. Ambos têm o mesmo `cl_context` da aplicação. O buffer exposto como `OpenClBufferPacked` nesse modo aceita NHWC contíguo. O delegate realiza a conversão para seu layout interno na GPU. A investigação descartou a interpretação inicial de que esse tipo já fosse PHWC4. [Requisitos de buffers OpenCL no LiteRT 2.4.0](https://github.com/google-ai-edge/LiteRT/blob/v2.4.0/ml_drift_delegate/delegate/gpu_backend_opencl_litert.cc)
- `resident-direct` habilita tensores externos e seleciona armazenamento linear. O LiteRT então permite passar diretamente os planos PHWC4. Forçar o armazenamento linear também afeta a seleção de kernels do delegate, portanto o A/B mede o efeito combinado da configuração e da eliminação das conversões. Com o armazenamento padrão no modo externo, o S24 anunciou textura OpenCL como entrada. [Opções GPU do LiteRT](https://github.com/google-ai-edge/LiteRT/blob/v2.4.0/litert/c/options/litert_gpu_options.h)

As duas rotas residentes preservam o mosaico GBRG, o halo de quatro posições Bayer da rede, a reflexão 101 na janela de ruído, o limite de três σ e a atenuação do residual nas altas luzes. Um kernel funde GainMaps bilineares nos centros dos fotossítios, MHC com halo de dois pixels, matriz de cor, +1,5 EV e a mesma LUT sRGB. O kernel mantém em memória local uma janela de 20 × 20 para um grupo de 16 × 16. Um buffer de entrada e um de saída são reutilizados para os 165 blocos. Os buffers intermediários permanecem no contexto OpenCL durante o quadro.

## Protocolo e latência

Os pacotes de entrada do aparelho foram comparados por SHA-256 com os arquivos locais. Cada rota completou o processamento dos quatro pares, dez repetições aquecidas em `detail-a` e três inicializações em processos novos. Cada inicialização GPU mostrou quatro convoluções delegadas em uma partição e zero nós no CPU. A tela esteve ativa e o nível térmico do Android foi 0 durante as medições principais. As séries rodaram em sequência, com agendamento normal e sem fixar frequências. A latência de quadro mede a preparação até RAW e RGB em RAM, incluindo a espera pelo resultado. O carregamento do arquivo, o cálculo dos hashes e o ADB ficam fora desse intervalo.

| Rota | 10 MP aquecido p50 / p95, ms | Inicialização p50 / p95, ms | Primeira imagem p50 / p95, ms | RSS de pico / PSS máx. amostrado, MiB | Bateria |
| --- | ---: | ---: | ---: | ---: | ---: |
| Java + GPU | 3.145,7 / 3.280,3 | 154,1 / 156,8 | 3.392,1 / 3.450,0 | 421,7 / 227,6 | 29,4 °C |
| C++/NEON + GPU | 653,6 / 825,5 | 185,8 / 186,6 | 1.057,9 / 1.081,2 | 345,5 / 269,7 | 29,4 °C |
| OpenCL residente NHWC | **122,5 / 244,2** | 273,2 / 303,1 | 368,9 / 379,6 | 449,0 / 388,8 | 30,2 °C |
| OpenCL residente PHWC4 direto | 292,6 / 310,1 | 288,2 / 298,6 | 422,1 / 423,1 | 450,8 / 392,0 | 30,2 °C |

O caminho NHWC reduziu a mediana do quadro em 81% e o p95 em 70% frente ao NEON na rodada principal. Em contrapartida, o RSS de pico subiu cerca de 104 MiB e o PSS máximo amostrado subiu 119 MiB. O LiteRT e a aplicação contabilizam 111.945.416 bytes lógicos em `cl_mem` expostos à bancada, incluindo entrada, saída, intermediários, mapas, LUT e tensores reutilizados. Esse valor não descreve todos os buffers do delegate nem o pico físico da GPU. O S24 não forneceu atribuição separada de memória ou energia do acelerador neste ensaio.

Em uma repetição independente, o NHWC obteve 269,5 / 295,5 ms (p50 / p95 em 12 quadros aquecidos). A mediana do NHWC variou entre as rodadas, mas permaneceu ao menos 58% abaixo da referência NEON medida nesta etapa. Foram concluídas mais 69 imagens em 20,29 s sob carga contínua, com bateria a 30,2 °C e nível térmico 0. Os quatro hashes RAW e os quatro hashes RGB foram idênticos entre as duas rodadas residentes. O intervalo de carga é curto para atestar comportamento térmico sustentado.

| Etapa, mediana por quadro em ms | Java | C++/NEON | NHWC residente | PHWC4 residente |
| --- | ---: | ---: | ---: | ---: |
| Preparação do mapa de ruído | 603,0 | 64,0 | 1,9 | 1,8 |
| Entrada do interpretador por blocos | 83,4 | 17,3 | fundida à preparação | fundida à preparação |
| `Invoke` nativo ou janela GPU da rede | 398,5 | 328,1 | 84,7 | 203,7 |
| Remontagem e correção RAW | 809,3 | 21,6 | 2,9 | 2,5 |
| GainMaps | 164,9 | 29,9 | fundido ao MHC | fundido ao MHC |
| MHC e sRGB | 1.066,8 | 179,6 | 8,6 | 8,6 |
| Upload explícito medido na fila | — | — | 0,10 | 0,08 |
| Readback explícito medido na fila | — | — | 5,4 | 13,6 |
| Submissão no host | — | — | 8,4 | 9,4 |
| Espera final no host | — | — | 110,6 | 276,5 |

Nos caminhos residentes, a janela da rede é medida da conclusão da preparação até o marcador depois do LiteRT. Inclui programação na fila e conversões GPU do caminho NHWC. As durações dos comandos e a espera da CPU se sobrepõem. Os totais não são obtidos somando linhas da tabela. A primeira imagem e os valores frios são descritivos de três amostras.

## Transferências e sincronização

Em cada quadro a aplicação enfileira dois uploads explícitos, de 39.923.712 bytes do mosaico e de 156.820 bytes dos parâmetros. Há três readbacks, do RAW float32 para auditoria (39.923.712 bytes), do RGB (29.942.784 bytes) e de quatro bytes da verificação de valores finitos. Não há upload ou readback CPU por bloco. A LUT é carregada uma vez na inicialização. Os tempos são os dos comandos OpenCL. O driver pode mapear ou mover páginas fora dos eventos observados, de modo que a contagem mede operações da aplicação e o compartilhamento com o LiteRT, sem demonstrar zero-copy físico de ponta a ponta.

`LiteRtRunCompiledModelAsync` foi solicitado em todos os blocos. No S24 retornou `async=false` em 165 de 165 blocos, sem evento de conclusão do delegate. O código oficial do LiteRT 2.4.0 suprime esse evento em Adreno e PowerVR nesse caminho. A bancada passa sua fila OpenCL ordenada ao LiteRT, usa marcadores dessa fila para medir a execução e aguarda o último readback antes de devolver os buffers ao Java. Essas evidências se limitam à fila compartilhada. A alocação Android aceita `AHardwareBuffer` BLOB para dados GPU, mas `LiteRtEnvironmentSupportsAhwbClInterop` retornou `false`. Os requisitos de entrada e saída também omitiram AHB. [Implementação da cerca OpenCL](https://github.com/google-ai-edge/LiteRT/blob/v2.4.0/ml_drift_delegate/delegate/gpu_backend_opencl_litert.cc) · [Guia GPU do LiteRT](https://developers.google.com/edge/litert/next/gpu)

O inventário `cmd gpu vkjson` da Adreno 750 expôs Vulkan 1.3.128, `VK_ANDROID_external_memory_android_hardware_buffer` e `VK_KHR_external_semaphore_fd`. Esse inventário comprova a capacidade declarada de um lado da integração. Falta uma importação OpenCL compatível com o LiteRT avaliado e uma cerca compartilhada validada. Vulkan permaneceu fora dos benchmarks de latência e de paridade. [Especificação Khronos de AHardwareBuffer no Vulkan](https://docs.vulkan.org/refpages/latest/refpages/source/VK_ANDROID_external_memory_android_hardware_buffer.html)

## Fidelidade e decisão

Java e C++/NEON tiveram RAW e RGB idênticos por SHA-256 nos quatro pares, repetindo o resultado anterior. As duas rotas residentes divergiram dos SHA-256 de RAW e RGB nos quatro pares. Os desvios são pequenos em escala RAW linear normalizada.

| Par | NHWC RAW p95 / máximo | PHWC4 RAW p95 / máximo | RGB residente igual por SHA-256 |
| --- | ---: | ---: | --- |
| `detail-a` | 1,49 × 10⁻⁸ / 1,19 × 10⁻⁷ | 7,45 × 10⁻⁹ / 5,96 × 10⁻⁸ | Não nas duas rotas |
| `detail-b` | 1,49 × 10⁻⁸ / 1,19 × 10⁻⁷ | 7,45 × 10⁻⁹ / 5,96 × 10⁻⁸ | Não nas duas rotas |
| `flat-a` | 1,49 × 10⁻⁸ / 5,22 × 10⁻⁸ | 7,45 × 10⁻⁹ / 3,73 × 10⁻⁸ | Não nas duas rotas |
| `flat-b` | 1,49 × 10⁻⁸ / 5,96 × 10⁻⁸ | 7,45 × 10⁻⁹ / 4,47 × 10⁻⁸ | Não nas duas rotas |

Em `detail-a`, cujo RGB completo é exportado, o p95 da diferença RGB foi zero e o máximo foi um nível de 8 bits. Foram 359 de 29.942.784 valores de canal alterados em NHWC e 264 em PHWC4. Nas outras três cenas o ensaio guardou o hash RGB, sem comparação por pixel. A rede continuou FP32. O driver não anunciou divisão e raiz corretamente arredondadas em FP32. O ensaio não atribui os desvios a uma operação específica, pois o delegate e a execução do ISP mudaram juntos.

**C++/NEON com GPU estrita permanece a referência adotada.** O ganho de latência do caminho residente justifica preservar o protótipo, mas o requisito de igualdade RAW/RGB ainda está em aberto e a memória subiu. A próxima investigação é comparar, para um mesmo bloco, os mapas de ruído e o residual antes da remontagem e renderizar o mesmo RAW nos dois ISPs. Isso localiza os primeiros desvios sem alterar o grafo.

## Reprodução

A configuração `rawResidentSdk` pertence apenas ao módulo de bancada. Prepare fora do Git um diretório `$sdk` com os checkouts e binários abaixo. A pasta `runtime/arm64-v8a` recebe apenas `jni/arm64-v8a/libLiteRt.so` do AAR principal e `jni/arm64-v8a/libLiteRtClGlAccelerator.so` do AAR GPU. `CMake` gera o cabeçalho de configuração a partir do checkout. O APK continua carregando o LiteRT 1.4.2 das dependências Java para os controles.

| Material | Versão e verificação |
| --- | --- |
| `$sdk/LiteRT` | [v2.4.0](https://github.com/google-ai-edge/LiteRT/tree/v2.4.0), commit `14b37ee5489894161548390f448c4a68c94f4ce5` |
| `$sdk/OpenCL-Headers` | [v2024.10.24](https://github.com/KhronosGroup/OpenCL-Headers/tree/v2024.10.24), commit `4ea6df132107e3b4b9407f903204b5522fdffcd6` |
| AAR principal | [litert-2.4.0.aar](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert/2.4.0/litert-2.4.0.aar), SHA-256 `13926c98f35417b6fe4f104f780ab869747b4a69f08a91ccd224013c6bf71615` |
| AAR GPU | [litert-gpu-2.4.0.aar](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert-gpu/2.4.0/litert-gpu-2.4.0.aar), SHA-256 `9bf935660d3ba33c45e43345fe77b7f4cc0d8f63ec8e007e45994863254385b2` |

Use os quatro pacotes privados de [reprodução do ensaio anterior](TELE_RAW_ON_DEVICE.md#reprodução). Defina `$python`, `$adb`, `$fixtures`, `$model` e `$out` como no [protocolo NEON](TELE_RAW_ISP_OPTIMIZED.md#reprodução). Com NDK 27.2.12479018 e CMake 3.22.1 disponíveis, execute:

```powershell
.\gradlew.bat -p tools/calibration/raw_device "-PrawResidentSdk=$sdk" assembleDebug lintDebug testDebugUnitTest
$apk = (Get-ChildItem tools/calibration/raw_device/build/outputs/apk/debug -Filter '*.apk' -File).FullName
& $adb devices -l
& $python tools/calibration/raw_device_run.py --adb $adb install --apk $apk --model $model --fixtures $fixtures
foreach ($route in @('native', 'resident', 'java', 'resident-direct')) {
    & $python tools/calibration/raw_device_run.py --adb $adb collect --implementation $route --out "$out/$route" --modes gpu --repeats 10 --cold 3 --energy-seconds 0
}
& $python tools/calibration/raw_device_io.py compare --fixtures "$fixtures/fixtures.json" --runs $out --output "$out/java-neon-parity.json" --modes gpu
foreach ($route in @('resident', 'resident-direct')) {
    & $python tools/calibration/raw_device_io.py compare --fixtures "$fixtures/fixtures.json" --runs $out --output "$out/$route-parity.json" --modes gpu --reference native --candidate $route
}
```

Passaram `assembleDebug`, `lintDebug` e quatro testes JVM com e sem o SDK opcional, além de 61 testes Python com os DNGs reais. O APK da série residente tem SHA-256 `a92fd3bf5c6f69285afd1573baf3e674e406a349b11446ba81480dd1a1717c04`. Os logs, RAW/RGB exportados, hashes de entrada, APK validado e inventário Vulkan estão na bancada privada `../s24-qa/raw-isp-resident-20261125`.
