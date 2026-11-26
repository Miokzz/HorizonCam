# Fidelidade do RAW ISP residente

Ensaio de 26 de novembro de 2026 a partir de `a68e6bf`. Galaxy S24 SM-S9210, Adreno 750 e firmware S9210ZHU6EZJD. A referência é C++/NEON com o delegate OpenCL do LiteRT 1.4.2. O candidato usa CompiledModel OpenCL 2.4.0 com tensores NHWC. Ambos executaram as quatro convoluções em GPU e reutilizaram os quatro pares RAW/JPEG, metadados, NoiseProfiles, GainMaps e o grafo FP32 de 27.620 bytes. O SHA-256 do grafo é `5071b54120e71c0dae7669efff1cbc4862cb8fba377bcd33a90f558e7ade820b`. Os hashes de entrada coincidiram com os do [ensaio residente anterior](TELE_RAW_ISP_RESIDENT.md).

## Como a comparação isola as etapas

A instrumentação registra a soma 3 × 3 por fase Bayer, o quociente por 9, a variância e o tensor FP32 de 136 × 136 × 8 antes da CNN. Os quatro últimos canais contêm σ × 32, transformação reversível nessa faixa. Em seguida registra a saída bruta 136 × 136 × 4, o residual limitado, o peso de saturação e a correção que será somada ao Bayer. A comparação de ruído exclui posições fora do sensor e a comparação do tensor CNN inclui o preenchimento com zeros. A comparação da correção usa a área útil de cada bloco.

O Bayer restaurado e o resultado RGB de 3648 × 2736 são comparados na íntegra nos quatro pares. Seis regiões de 16 × 16 cobrem quatro cantos, uma borda entre blocos e uma região central. Nelas são registrados o valor corrigido pelo GainMap, o RGB linear do MHC, a saída da matriz e o valor exposto e limitado antes da LUT sRGB.

- A primeira série capturou os blocos 0, 16, 82, 89 e 164 entre os 165 blocos por quadro. A série de controle inclui também o bloco 69, que concentra as altas luzes saturadas dos dois pares de detalhe.
- O replay escreve o tensor NEON na entrada do LiteRT e verifica a leitura de volta antes de invocar a CNN. A saída da CNN é registrada antes de qualquer substituição. O controle adicional escreve a previsão NEON para isolar a correção residual. A última intervenção escreve o Bayer NEON completo antes do kernel de revelação, com a mesma matriz, GainMaps, exposição e LUT.
- Os registros são enfileirados na mesma fila OpenCL usada pelo CompiledModel. As leituras precedem a reutilização de cada buffer. A espera final precede o acesso pelos destinos no host. Os hashes do ensaio original coincidiram com os do checkpoint para cada rota em todos os quatro pares.
- Os caminhos são iniciados em processos separados. Carregar o LiteRT 2.4.0 junto ao interpretador 1.4.2 no mesmo processo fez o wrapper JNI legado resolver seu `run` na biblioteca nova, causando SIGSEGV. A separação em dois processos permitiu usar o mesmo APK e transferir o tensor de referência pelo sandbox da bancada.

## Primeira divergência e correções isoladas

Os valores Bayer de entrada, a reflexão das bordas e a ordem da soma dos nove termos coincidiram nas amostras. A **divisão FP32 por 9** foi a primeira diferença real. A discrepância máxima no quociente foi `5,96 × 10⁻⁸` incluindo o bloco de altas luzes. Mesmo quando a variância coincidia, a raiz do OpenCL ainda produzia valores distintos de σ. A Adreno deste S24 não anuncia `CL_FP_CORRECTLY_ROUNDED_DIVIDE_SQRT`. A precisão FP32 por si só permite diferenças de arredondamento de divisão e raiz no OpenCL. [Especificação OpenCL C](https://registry.khronos.org/OpenCL/specs/unified/html/OpenCL_C.html#numerical-compliance)

O código da bancada mantém a contração implícita desligada nas operações do ISP. O experimento usa FMA explícito só para estimar o erro residual do quociente ou do quadrado da raiz.

| Ajuste cumulativo | Divisões /9 diferentes | Variâncias diferentes | Valores de σ diferentes | Valores diferentes na entrada da CNN |
| --- | ---: | ---: | ---: | ---: |
| `stock` | 117.540 | 87.712 | 100.269 | 100.269 |
| `divide9` | 0 | 0 | 56.288 | 56.288 |
| `noise` | 0 | 0 | 0 | 0 |

Contagem nos cinco blocos originais de cada um dos quatro pares. `divide9` multiplica pelo recíproco e corrige o resultado com o resíduo de `fma(-q, 9, soma)`. `noise` acrescenta o refinamento da raiz pelo resíduo de `fma(-raiz, raiz, variância)`. Esse A/B verificou os valores cobertos pelo NoiseProfile destes quatro DNGs. A execução auditada de ambos os métodos continua fora do pipeline de produção.

## Diferença remanescente na CNN

O replay confirmou que **a saída da CNN diverge com a entrada bit a bit idêntica**. Em cada par, em torno de 359 a 361 mil dos 369.920 valores amostrados da previsão mudaram. O maior desvio nesses cinco blocos foi `2,98 × 10⁻⁶` na previsão normalizada em unidades de σ. Incluindo as altas luzes, o máximo foi `3,04 × 10⁻⁶`. As duas rodadas de replay residente tiveram previsões idênticas entre si nos seis blocos por par. Esse resultado independe da divisão e da raiz do ISP.

O replay registra no OpenCL exatamente os oito canais produzidos no NEON. O sinal Bayer e a ordem dos canais coincidiram desde o primeiro ensaio. Os requisitos do LiteRT distinguem o NHWC externo das conversões internas do delegate. A comparação separa o cálculo da CNN do preparo do tensor e da revelação, mas não expõe as ativações internas das quatro convoluções do grafo. Assim, o efeito está isolado na execução do delegate, sem evidência para atribuí-lo a uma instrução FMA específica, a uma ordem de acumulação ou a um kernel gerado. [Backend OpenCL do LiteRT 2.4.0](https://github.com/google-ai-edge/LiteRT/blob/v2.4.0/ml_drift_delegate/delegate/gpu_backend_opencl_litert.cc)

## Bayer restaurado e revelação

| Par | Máximo Bayer `stock` | Máximo Bayer `noise` | Valores RGB alterados `stock` → `noise` |
| --- | ---: | ---: | ---: |
| `detail-a` | 1,19 × 10⁻⁷ | 1,19 × 10⁻⁷ | 359 → 341 |
| `detail-b` | 1,19 × 10⁻⁷ | 7,45 × 10⁻⁸ | 359 → 339 |
| `flat-a` | 5,22 × 10⁻⁸ | 5,96 × 10⁻⁸ | 339 → 317 |
| `flat-b` | 5,96 × 10⁻⁸ | 5,96 × 10⁻⁸ | 339 → 365 |

Cada quadro tem 29.942.784 valores de canal RGB. Em todas as imagens e variantes da tabela, o desvio máximo RGB foi de um nível de 8 bits e o p95 foi zero. As duas rotas residentes ainda diferiram do NEON nos hashes RAW e RGB completos.

Com o mesmo Bayer restaurado no replay, GainMaps, MHC, matriz de cor e exposição foram idênticos nos pontos registrados. O SHA-256 RGB foi idêntico no quadro inteiro em 4/4 pares. A comparação inclui a reflexão nas bordas, a apresentação da matriz e o arredondamento para a LUT.

No bloco 69 houve 15 correções atenuadas e 125 pesos nulos em `detail-a`, e 8 atenuações e 123 pesos nulos em `detail-b`. Os pesos bateram bit a bit nas duas rotas. Ao usar a mesma previsão bruta e σ, o residual limitado, o peso e a correção antes da soma também coincidiram nos 24 blocos auditados.

## Latência sem a instrumentação de fidelidade

A primeira comparação em processos separados usou dez imagens aquecidas e três inicializações por perfil. Em uma segunda série, a ordem foi alternada em seis processos novos, com dez imagens por processo. Em todas as séries o nível térmico do Android permaneceu em 0, com bateria a 29,0 °C.

| Série | NHWC `stock` p50 / p95, ms | NHWC `noise` p50 / p95, ms |
| --- | ---: | ---: |
| Inicial, tela apagada | 132,2 / 138,6 | 290,5 / 314,3 |
| Alternada 1, tela apagada | 298,2 / 707,4 | 412,2 / 503,0 |
| Alternada 2, tela ativa | 180,8 / 250,0 | 260,8 / 280,6 |
| Alternada 3, tela ativa | 254,3 / 286,1 | 151,6 / 156,5 |

O kernel de preparo teve mediana de 1,9–2,0 ms no `stock` e 5,7 ms no `noise`. A latência integral variou entre processos e a magnitude dessa oscilação superou o custo adicional do preparo. Um marcador OpenCL chegou a produzir uma janela de inferência incompatível com a duração total. A tabela usa o relógio monotônico do host e exclui os snapshots. O ensaio não demonstrou preservação reproduzível dos 123 ms do checkpoint com a correção.

**Decisão.** A variante `noise` fica opcional no laboratório. Ela iguala a entrada da CNN nos blocos medidos, com diferenças RGB de até um nível que não constituem igualdade bit a bit. C++/NEON permanece a referência. Para investigar a diferença restante sem mudar os pesos, o próximo passo é instrumentar as acumulações por convolução nos dois delegates ou encontrar uma ligação GPU ao delegate legado que preserve a mesma implementação da CNN.

## Reprodução

Prepare o SDK LiteRT 2.4.0, o APK e os quatro arquivos privados conforme o [protocolo residente](TELE_RAW_ISP_RESIDENT.md#reprodução). Use um diretório de saída novo fora do repositório. O nome de execução no sandbox precisa ser único. `stock` reproduz o código original, `divide9` muda apenas o quociente e `noise` inclui o refinamento da raiz.

```powershell
& $adb shell am force-stop com.miokzz.rawbench
& $adb shell am instrument -w -r -e mode gpu -e fidelity true -e implementation native -e run fid-native-reference com.miokzz.rawbench/.RawBench
foreach ($variant in @('stock', 'divide9', 'noise')) {
    & $adb shell am force-stop com.miokzz.rawbench
    & $adb shell am instrument -w -r -e mode gpu -e fidelity true -e implementation resident -e ispMath $variant -e run "fid-$variant" -e reference fid-native-reference com.miokzz.rawbench/.RawBench
    & $python tools/calibration/raw_fidelity.py fetch --adb $adb --run "fid-$variant" --reference fid-native-reference --out "$out/$variant"
    & $python tools/calibration/raw_fidelity.py compare --input "$out/$variant" --output "$out/$variant.json"
}
foreach ($variant in @('stock', 'noise')) {
    & $python tools/calibration/raw_device_run.py --adb $adb collect --implementation resident --isp-math $variant --modes gpu --pairs detail-a --out "$out/benchmark/$variant" --cold 3 --repeats 10 --energy-seconds 0
}
```

`fetch` e `compare` aceitam `--traces-only` para inspecionar intermediários e hashes sem transferir os planos completos. A bancada guarda ensaios e evidências privadas em `../s24-qa/raw-fidelity-20261126`.

Passaram `assembleDebug`, `lintDebug`, quatro testes JVM com e sem o SDK opcional e 64 testes Python com DNGs privados. O APK da série de seis blocos tem SHA-256 `66675fe30b020df49cb7b8b236bcf797335bd50d37c7510c44d987e71068901d`. O teste de fumaça do APK final reproduziu, para `detail-a`, os hashes Bayer, RGB e RGB de replay do ensaio. SHA-256 do APK final `4cf08a49a3edac198db39b42f01b51c9c16a6b8c17c0dadac5daba4bc85a3162`.
