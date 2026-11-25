# Horizon AI RAW-aware da teleobjetiva

Experimento de bancada de 25 de novembro de 2026 a partir do checkpoint `83b91b2`. O protótipo restaura um único mosaico Bayer da tele física `0/6` antes do demosaico. A comparação reaproveita os quatro pares DNG + JPEG do Galaxy S24 SM-S9210, com focal de 7 mm, sem modificar a aplicação. O AHD, o MHC + Wiener + GainMaps e o JPEG pareado foram regenerados com os mesmos pixels de exibição do [experimento RAW ISP anterior](TELE_RAW_ISP.md).

## Arquiteturas avaliadas

| Referência | Entradas, treino e disponibilidade | Decisão |
| --- | --- | --- |
| Unprocessing, Brooks et al. | Desfaz etapas de ISP para gerar exemplos de Bayer sintético. A rede recebe Bayer empacotado e a variância de ruído. Código de treino Apache 2.0 em TensorFlow 1, com termos próprios no adaptador DND. Avaliação e pesos são descritos pelo projeto. | Usar a ideia de condicionar ao ruído com alvos lineares gerados localmente. Código e pesos do projeto ficaram fora do protótipo. [Artigo](https://openaccess.thecvf.com/content_CVPR_2019/html/Brooks_Unprocessing_Images_for_Learned_Raw_Denoising_CVPR_2019_paper.html) · [Código de treino](https://github.com/timothybrooks/unprocessing/blob/master/train.py) · [Adaptador DND](https://github.com/timothybrooks/unprocessing/blob/master/dnd_denoise.py) |
| PMRID, MegEngine | Rede residual de Bayer com convoluções separáveis e normalização K-Sigma. O repositório Apache 2.0 oferece checkpoints em PyTorch e MegEngine e uma base pareada de OPPO Reno 10x. | Executar o checkpoint PyTorch oficial como controle entre sensores. Manter coeficientes do Reno e identificar essa diferença em cada resultado. Nenhum peso de terceiros foi redistribuído. [Artigo](https://www.ecva.net/papers/eccv_2020/papers_ECCV/papers/123510001.pdf) · [Repositório e pesos](https://github.com/MegEngine/PMRID) |
| Noise Modeling in One Hour, Sony Research | Síntese autossupervisionada de ruído RAW de baixa luz a partir de calibração. Repositório MIT com checkpoints e instruções de avaliação em outras câmeras. | Orientar um futuro ensaio com quadro escuro e várias exposições. O conjunto atual contém dois planos claros em ISO próximo, com cobertura insuficiente das fontes de ruído em baixa luz. [Repositório e artigo](https://github.com/SonyResearch/raw_image_denoising) |
| PyNET, ETH Zürich | ISP neural RAW→RGB treinado nos pares do Huawei P20 com uma Canon 5D Mark IV. Pesos disponíveis. O repositório declara CC BY-NC-SA 4.0 e uso acadêmico. | A mudança de câmera, cor e alvo de treino impede usar seus pesos como substituto direto no A/B óptico. Manter balanço de branco, colorimetria e curva explícitos. [Projeto e licença](https://github.com/aiff22/PyNET) · [Artigo](https://openaccess.thecvf.com/content_CVPRW_2020/papers/w31/Ignatov_Replacing_Mobile_Camera_ISP_With_a_Single_Deep_Learning_Model_CVPRW_2020_paper.pdf) |

## Protótipo e dados

- **Referência de treinamento sintética.** 1.024 patches lineares de 64 × 64 gerados por procedimento, com bordas, traços, planos e texturas de diferentes frequências. Cada patch passa por uma suavização óptica sintética, amostragem Bayer GBRG, ruído de fótons Poisson, ruído de leitura gaussiano e quantização conforme o preto e branco declarados no DNG. As realizações de ruído mudam entre lotes. A perda combina erro absoluto e erro de gradiente nas subgrades Bayer, normalizados pelo desvio-padrão esperado.
- **Calibração real restrita ao ruído.** Os dois DNGs do alvo fosco (ISO 557 e 561) fornecem `NoiseProfile` e estatísticas da diferença temporal. Os fatores de variância medido/previsto foram **3,080 / 2,983 / 2,704** em R/G/B. O treino varia independentemente o coeficiente de fótons e a variância de leitura em torno desse ponto. O procedimento usa estatísticas agregadas dos DNGs. Nenhuma foto real entra como alvo limpo de treino. Os dois JPEGs pareados com detalhe (ISO 703 e 709) servem como referências práticas de exibição, sem supervisão de pixels.
- **Rede experimental.** Quatro convoluções locais 3 × 3, 16 canais internos e 6.388 parâmetros. A entrada tem quatro planos Bayer intercalados e quatro mapas de desvio-padrão. A rede estima uma correção RAW limitada a três desvios-padrão, com atenuação próxima à saturação. A variante protegida reduz a correção onde o sinal local está acima do ruído esperado. A variante híbrida usa o Wiener em regiões lisas e mistura até 65% da diferença local de alta frequência entre CNN protegida e Wiener quando os verdes mostram estrutura. As três variantes usam os mesmos pesos. Nenhuma aplica nitidez adicional. A proteção é local e baseada no sinal.
- **Controle PMRID.** O adaptador alinha GBRG→RGGB com uma linha de contexto em cada extremidade, usa a normalização ISO do Oppo com referência em 1600 e desfaz a transformação após a inferência. A restauração de todos os candidatos passa pelos mesmos GainMaps, MHC, matriz de cor do DNG e +1,5 EV da comparação anterior. AHD usa seu processamento LibRaw de referência. O Warp permanece desativado.

A semente de treino é 4181, com 1.600 passos, 12 amostras por lote e AdamW. O alvo sintético de avaliação usa uma carta analítica e um conjunto separado de glifos fixos com rotações e contrastes definidos. Os dois foram excluídos do treino. Cada alvo foi avaliado com três sementes de ruído, sob o perfil declarado e sob o multiplicador da calibração de bancada.

## Resultado de imagem

| Pipeline | Ruído residual A / B | Gradiente de borda A / B | ΔE76 A / B |
| --- | ---: | ---: | ---: |
| JPEG Samsung | 2,73 / 2,72 | 12,20 / 12,67 | — |
| LibRaw AHD | 4,32 / 4,45 | 10,12 / 10,46 | 12,47 / 12,68 |
| MHC + Wiener + GainMaps | **2,80 / 2,86** | 9,57 / 9,84 | 11,39 / 11,47 |
| PMRID com coeficientes do Reno | 4,06 / 4,12 | 10,23 / 10,59 | 11,52 / 11,54 |
| CNN residual | 4,07 / 4,13 | 10,45 / 10,79 | 13,26 / 13,33 |
| CNN protegida | 4,06 / 4,12 | 10,44 / 10,80 | 13,25 / 13,33 |
| CNN + Wiener híbrido | 2,86 / 2,89 | 9,59 / 9,85 | 11,48 / 11,51 |

Ruído e bordas usam a grade registrada de 1280 × 960 e as definições do [RAW ISP de referência](TELE_RAW_ISP.md#comparações). ΔE76 mede a diferença para o JPEG, incluindo o efeito de tom e exposição. A inspeção a 100% comparou o mesmo recorte de 340 × 275, com origem em (47,5%, 39,3%) do quadro A. O JPEG Samsung apresentou letras mais legíveis e menos manchas cromáticas. A CNN protegida e o PMRID deram mais resposta de borda do que o Wiener, com mais granulação no papel. O híbrido ficou visualmente próximo do Wiener e sua diferença de gradiente foi de aproximadamente 0,01 a 0,02 na escala usada. O ensaio cobre dois disparos da mesma cena, além do alvo branco.

A diferença temporal do plano branco confirmou o efeito por fase. Em G/B/R/G, o Wiener ficou em 1,32/1,18/1,34/1,32 vezes a variância de entrada prevista pelo DNG, e o híbrido ficou em 1,32/1,18/1,34/1,32. A CNN protegida ficou em 0,77/1,56/1,48/0,82. A redução foi desigual entre canais nessa câmera. A diferença entre quadros é usada apenas como métrica temporal. Cada saída parte de um disparo.

### Referência sintética com ruído calibrado

| Pipeline | PSNR | Ruído de meios-tons RMS | Linhas | Textura | Excesso na borda P95 | Falsa cor RMS | Dice de traços |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| MHC + Wiener | 29,88 dB | 0,01632 | 0,893 | 0,988 | 0,0591 | 0,0372 | 0,9517 |
| PMRID | 30,12 dB | 0,01742 | 0,828 | 1,048 | 0,0581 | 0,0323 | 0,9591 |
| CNN residual | 31,00 dB | 0,01201 | 0,827 | 0,806 | 0,0442 | 0,0312 | 0,9581 |
| CNN protegida | 30,84 dB | 0,01215 | 0,883 | 0,901 | 0,0543 | 0,0341 | 0,9599 |
| CNN + Wiener híbrido | 30,05 dB | 0,01632 | 0,889 | 0,948 | 0,0568 | 0,0359 | 0,9545 |

Os valores são medianas das três sementes, no espaço linear do alvo conhecido. O Dice mede a interseção dos traços nos glifos sintéticos, com limiares do alvo limpo. Linhas e textura medem resposta de gradiente orientada à referência, com 1 como valor ideal. O excesso na borda mede ultrapassagem dos patamares da borda conhecida e inclui ruído. O sintético testa o demosaico e a restauração com a mesma geometria dos dados limpos, sem ganho óptico ou matriz de cor. Com a escala calibrada, o erro absoluto médio das cores sólidas foi de 0,00038 no Wiener e 0,00076 na CNN protegida. Sob o perfil declarado, o Wiener reteve 0,983 de textura, contra 0,862 da CNN protegida, com ruído RMS de 0,00371 contra 0,00662. Esses controles mostram como a conclusão muda com a escala de ruído e o equilíbrio entre textura real, ruído e cor.

**Decisão.** MHC + Wiener + GainMaps continua como melhor referência para os DNGs medidos. Para pesquisa, a direção mais justificável é um residual Bayer condicionado ao ruído, com proteção estrutural e MHC e cor explícitos. A proteção recuperou parte da textura que a CNN simples apagou no sintético e conserva uma saída comparável ao ISP registrado. O híbrido ficou próximo do Wiener em campo, com ganho pequeno demais para justificar o custo adicional. A evidência atual valida o protótipo e a exportação, sem ganho fotográfico suficiente para integrar o modelo. Uma decisão de qualidade dependeria de referências reais adicionais, especialmente ruído por canal em sombras, texto de vários tamanhos, textura natural, cor calibrada e exposições independentes da calibração.

## Viabilidade Android de bancada

- A exportação FP32 gera um LiteRT de **27.620 bytes** com quatro `CONV_2D` e ReLU incorporada nas três primeiras. Peso SHA-256 `4a3f0fb91a163e229dbe5489c0d45f9432687e6d6b8bc28be21e85af94123190`. Arquivo LiteRT SHA-256 `5071b54120e71c0dae7669efff1cbc4862cb8fba377bcd33a90f558e7ade820b`. O erro máximo de PyTorch para LiteRT CPU foi **1,06 × 10⁻⁷** em unidades RAW lineares em três entradas sintéticas. Cada chamada recebe 136 × 136 × 8 e devolve 136 × 136 × 4. O halo de quatro posições Bayer empacotadas é descartado antes de recompor o quadro. Os mapas de ruído e de proteção ficam fora do grafo.
- Cada bloco tem cerca de **117 milhões de MACs**. Em 10 MP seriam 165 blocos fixos, cerca de 19,3 GMAC, incluindo bordas de contexto. A entrada, a saída e duas ativações intermediárias FP32 somam **3,10 MiB** em um bloco reutilizado. É uma estimativa de buffers explícitos. O total inclui, adicionalmente, o espaço de trabalho do delegate, as cópias e a memória do demosaico. Uma implementação no aparelho precisaria preparar os mapas locais com contexto extra no perímetro e reusar os buffers.
- No Ryzen 7 5700G com XNNPACK e quatro threads, a alocação levou 20,25 ms. A primeira inferência levou **1,74 ms** e as 30 seguintes tiveram mediana de **0,96 ms** e p95 de **1,31 ms** por bloco. No A/B PyTorch de 10 MP, a preparação dos canais mais a rede levou **0,45–0,59 s** entre os dois pares de detalhe. O PMRID integral, incluindo seu adaptador, levou **1,26–1,33 s**. Os tempos excluem decodificação, renderização e publicação.
- A validação no S24 precisa medir a delegação integral, as cópias e a montagem dos blocos. O delegate GPU do LiteRT suporta a família `CONV_2D` e a documentação do Qualcomm QNN inclui o Snapdragon 8 Gen 3. Os operadores e a exportação indicam caminhos viáveis para um ensaio isolado. ADB Wi-Fi ficou indisponível nesta rodada e a validação de bancada foi aceita. Latência, memória total, FP16/INT8, temperatura e energia no aparelho seguem sem medição. Para medir energia, usar execuções repetidas sob as mesmas condições e registrar os trilhos disponíveis pelo Power Profiler/Perfetto ou um medidor externo. [Delegate GPU](https://developers.google.com/edge/litert/android/gpu) · [LiteRT e Qualcomm](https://developers.google.com/edge/litert/android/npu/qualcomm) · [Power Profiler](https://developer.android.com/studio/profile/power-profiler)

## Reprodução e testes

Com Python 3.12 no PowerShell e os quatro pares privados conforme o [laboratório RAW](TELE_RAW_LAB.md), aponte `$flatA`, `$flatB`, `$detailA` e `$detailB` para os diretórios dos pares. Escolha um caminho novo para `$out`, fora do repositório.

```powershell
py -3.12 -m venv ../raw-restore-venv
$python = '../raw-restore-venv/Scripts/python.exe'
& $python -m pip install torch==2.15.0+cpu --index-url https://download.pytorch.org/whl/cpu
& $python -m pip install -r tools/calibration/requirements-tele-raw-restore.txt
$out = '../tele-raw-restoration-run'
New-Item -ItemType Directory -Path $out
git clone https://github.com/MegEngine/PMRID.git "$out/pmrid"
git -C "$out/pmrid" checkout --detach 8ebb9e8e96559881dee957f34243933c5beb77dd
& $python tools/calibration/tele_raw_restore.py --flat-a $flatA --flat-b $flatB --out "$out/training"
& $python tools/calibration/run_tele_raw_restore.py --flat-a $flatA --flat-b $flatB --detail-a $detailA --detail-b $detailB --training "$out/training" --pmrid "$out/pmrid" --out "$out/ab"
& $python tools/calibration/export_tele_raw_restore.py --weights "$out/training/bayer-residual.pt" --out "$out/litert"
$env:HORIZONCAM_TELE_DNG_FIXTURES = (($flatA, $flatB, $detailA, $detailB | ForEach-Object { Join-Path $_ 'source.dng' }) -join [IO.Path]::PathSeparator)
& $python -m unittest discover -s tools/calibration
```

O runner verifica os SHA-256 do código e dos pesos de PMRID antes de carregar o grafo, e valida os manifestos e os originais antes e depois da comparação. Os relatórios completos são `training.json`, `restoration-report.json` e `litert-report.json`. Originais, imagens reveladas e pesos experimentais permanecem nos diretórios de laboratório. A sessão usou PyTorch 2.15.0+cpu, TensorFlow CPU 2.20.0, rawpy 0.28.0, OpenCV 5.0.0 e NumPy 2.5.5.

Passaram **53 testes Python**, incluindo os quatro DNGs reais, o simulador de ruído, o contexto dos blocos, o empacotamento Bayer e os limites do residual. Uma segunda execução do treino no mesmo host reproduziu o SHA-256 dos pesos. A exportação verificou operações, dimensões e paridade FP32. No ambiente anterior do ISP, permaneceram 46 testes válidos e os sete novos foram ignorados por falta da dependência opcional PyTorch. O checkpoint contém exclusivamente ferramentas e documentação de laboratório.
