# RAW ISP de laboratório da teleobjetiva

Validação em 25 de novembro de 2026, a partir de `e73e533`. O experimento processa um único DNG por vez, fora do aplicativo. O DNG e o JPEG do mesmo disparo permanecem como referências. A captura Android, a Foto automática e o Horizon AI permanecem com os pipelines existentes. A captura pareada e seu contrato de timestamp estão no [laboratório RAW da teleobjetiva](TELE_RAW_LAB.md).

## Dados e reprodução

Foram analisados quatro pares da câmera lógica `0` com as saídas direcionadas à tele física `6` no Galaxy S24 SM-S9210, firmware S9210ZHU6EZJD. Os dois pares de detalhe existentes mostram a mesma cena com papel impresso, sombras e objetos coloridos (ISO 703 e 709). Duas novas capturas via ADB Wi-Fi, com o telefone apoiado e a prévia de 1440 × 1080 ativa, registraram uma superfície branca fosca (ISO 557 e 561). Todos os disparos usaram focal de 7,0 mm, exposição de 29,994 ms, zoom nominal 3× e crop físico de 3648 × 2736. Cada par passou na validação do timestamp do sensor registrado pelo instrumento, do tamanho e do SHA-256 dos originais.

O DNG guarda o mosaico GBRG em UInt16 sem compressão, com preto declarado de 64,33 e branco de 1023. A análise usa a área ativa de 3648 × 2736. O crop recomendado de 8 pixels por margem é metadado do DNG. Os originais, recortes visuais e relatórios por par ficam fora do Git.

Prepare o ambiente do [laboratório RAW](TELE_RAW_LAB.md#reprodução). Use duas pastas de capturas do mesmo alvo fosco, com enquadramento e iluminação constantes, para estimar a variância temporal. Use pastas separadas para as cenas de detalhe. No PowerShell, a partir do repositório:

```powershell
$python = '../raw-lab-venv/Scripts/python.exe'
$flatA = '<pasta do primeiro par do alvo branco>'
$flatB = '<pasta do segundo par do alvo branco>'
$detailA = '<pasta do primeiro par com textura>'
$detailB = '<pasta do segundo par com textura>'
& $python tools/calibration/tele_raw_isp.py $flatA --out ../tele-isp-run/flat-a --flat-field --flat-repeat $flatB
& $python tools/calibration/tele_raw_isp.py $flatB --out ../tele-isp-run/flat-b --flat-field
& $python tools/calibration/tele_raw_isp.py $detailA --out ../tele-isp-run/detail-a --noise-calibration ../tele-isp-run/flat-a/isp-report.json --export-linear malvar-gain
& $python tools/calibration/tele_raw_isp.py $detailB --out ../tele-isp-run/detail-b --noise-calibration ../tele-isp-run/flat-a/isp-report.json
```

Cada comando exige um destino novo e grava `isp-report.json`, PNGs sRGB das variantes e do JPEG pareado e, opcionalmente, um TIFF RGB linear float32. O TIFF mantém valores negativos, valores acima de 1 e a resolução de 3648 × 2736. A leitura independente do TIFF validou o formato e os valores finitos. O ganho de exibição de +1,5 EV vem da comparação AHD anterior e foi mantido em todos os pares e variantes. O TIFF fica sem esse ganho e sem a curva de exibição.

## Arquitetura experimental

- **Normalização e ruído.** Subtrai o nível preto e normaliza pelo intervalo entre preto e branco, preservando amostras negativas. Um filtro Wiener local de 5 × 5 trabalha em cada subgrade CFA antes do ganho óptico. A variância esperada usa `s × max(sinal, 0) + o` com os coeficientes DNG do canal. O filtro preserva as amostras próximas da saturação. A variante direcional ajusta a estimativa local a partir de quatro direções. [Modelo de ruído dependente do sinal](https://openaccess.thecvf.com/content_CVPR_2019/html/Brooks_Unprocessing_Images_for_Learned_Raw_Denoising_CVPR_2019_paper.html)
- **Demosaico.** AHD e DCB do LibRaw servem de referência, com nitidez e redução de ruído adicionais desativadas. A comparação própria usa bilinear e Malvar–He–Cutler (MHC), mantendo a amostra conhecida do CFA e interpolando os canais ausentes pelos filtros do artigo. O ensaio testa as variantes com a mesma exposição de exibição. [AHD](https://doi.org/10.1109/TIP.2004.838691) · [LibRaw DCB](https://www.libraw.org/node/35) · [Malvar–He–Cutler](https://www.microsoft.com/en-us/research/wp-content/uploads/2016/02/Demosaicing_ICASSP04.pdf) · [Referência IPOL](https://www.ipol.im/pub/art/2011/g_mhcd/article.pdf)
- **Cor e tom.** Usa `AsShotNeutral`, calibração dual D65 e Standard A, `ColorMatrix` e `ForwardMatrix`, com interpolação pelo inverso da temperatura aproximada. Converte para RGB linear sRGB via XYZ e adaptação cromática Bradford. Os PNGs aplicam a transferência sRGB e incluem perfil ICC. Uma curva opcional comprime as altas luzes por luminância depois do ganho de exibição, com o mesmo fator nos três canais. Ela preserva a gradação disponível em dados lineares, enquanto a saturação do sensor limita os dados recuperáveis. [Especificação Adobe DNG](https://helpx.adobe.com/camera-raw/desktop/dng-and-file-formats/digital-negative.html) · [Parâmetros rawpy](https://letmaik.github.io/rawpy/api/rawpy.Params.html)
- **Óptica.** Aplica separadamente os quatro `GainMap` de 13 × 17 às fases do CFA antes do demosaico. A ablação geométrica aplica `WarpRectilinear` após o demosaico, com remapeamento inverso e interpolação bilinear. O diagnóstico registra os opcodes e rejeita uma instrução obrigatória que ele não reconheça. [Especificação Adobe DNG](https://helpx.adobe.com/camera-raw/desktop/dng-and-file-formats/digital-negative.html) · [Suporte a estágios DNG no LibRaw](https://www.libraw.org/docs/API-datastruct.html)

## Comparações

As métricas dos pares de detalhe foram medidas na região comum registrada em uma grade de 1280 × 960. Ruído é o estimador MAD de alta frequência em áreas de gradiente baixo. Bordas usa o gradiente mediano nas posições selecionadas pelo JPEG. Textura, contraste tonal e reamostragem também afetam essas métricas. ΔE76 é a distância entre renderings pareados. A fidelidade absoluta de cor requer um alvo calibrado.

| Variante | Ruído A / B | Bordas A / B | ΔE76 A / B | Erro de registro A / B |
| --- | ---: | ---: | ---: | ---: |
| JPEG Samsung | 2,73 / 2,72 | 12,20 / 12,67 | — | — |
| LibRaw AHD | 4,32 / 4,45 | 10,12 / 10,46 | 12,47 / 12,68 | 0,22 / 0,21 px |
| LibRaw DCB | 4,24 / 4,28 | 10,07 / 10,40 | 13,21 / 13,31 | 0,22 / 0,21 px |
| MHC sem redução de ruído | 4,39 / 4,39 | 9,82 / 10,14 | 14,07 / 14,09 | 0,20 / 0,19 px |
| MHC, Wiener e GainMaps | 2,80 / 2,86 | 9,57 / 9,84 | 11,39 / 11,47 | 0,21 / 0,20 px |
| MHC, Wiener direcional e GainMaps | 3,21 / 3,22 | 9,83 / 10,12 | 12,14 / 12,15 | 0,20 / 0,19 px |
| MHC, Wiener, GainMaps e warp | 1,85 / 1,85 | 9,11 / 9,31 | 10,22 / 10,18 | 0,36 / 0,40 px |
| MHC, Wiener recalibrado e GainMaps | 1,08 / 1,08 | 8,45 / 8,62 | 8,57 / 8,58 | 0,30 / 0,27 px |

Nos recortes dos dois pares, MHC com Wiener e GainMaps reduziu a granulação visível em papel e sombras em relação a AHD/DCB. A cena manteve os traços mais legíveis na versão Samsung, com contraste local maior e menos granulação nas superfícies. A variante recalibrada suavizou letras e contornos finos. As cores e a exposição também diferiram do JPEG. Por exemplo, no alvo fosco, o centro mediano foi RGB 167/166/160 com a revelação recomendada, contra 155/154/150 e 156/155/151 nos JPEGs. O alvo fosco serve para comparar uniformidade, com geometria e bordas excluídas do relatório.

A referência sintética parte de um alvo linear conhecido e usa uma aproximação gaussiana da variância de ruído de fótons e de leitura, segundo o perfil declarado do verde da captura e três sementes fixas. Os índices de linhas e textura medem a resposta relativa à referência limpa, com 1 como resposta nominal. O ensaio avalia demosaico e filtro sem balanço de branco ou óptica, com as demais métricas no JSON. Ele mostra o custo em textura mesmo quando o PSNR sobe com o fator de ruído mais alto.

| Variante | PSNR mediano | Linhas | Textura | Ruído RMS de meios-tons |
| --- | ---: | ---: | ---: | ---: |
| Bilinear | 28,85 dB | 0,601 | 0,831 | 0,01127 |
| MHC | 31,13 dB | 0,911 | 1,056 | 0,01414 |
| MHC e Wiener | 32,93 dB | 0,892 | 0,983 | 0,00362 |
| MHC e Wiener direcional | 32,86 dB | 0,900 | 1,007 | 0,00378 |
| MHC e Wiener recalibrado | 33,05 dB | 0,857 | 0,839 | 0,00292 |

## Correções ópticas e limites

- **GainMaps.** A excursão espacial P90−P10 do alvo branco, medida em cada fase CFA, caiu de 0,70–0,74 EV para 0,257–0,263 EV. A intensidade nos cantos passou de 47–53% para 77–89% do centro. O gradiente residual inclui a iluminação do alvo. Esse dado demonstra redução de queda de luz em campo.
- **Warp.** A malha de amostragem indicou deslocamento máximo de aproximadamente 5,5 pixels, jacobiano mínimo de 0,988 e nenhuma amostra da malha fora da área ativa. O remapeamento reduziu a resposta das bordas e aumentou o erro de registro em relação ao JPEG nos dois pares de detalhe. Seu benefício geométrico depende de validação com uma grade calibrada.
- **Variância.** A variância temporal foi estimada a partir da diferença de duas exposições independentes do plano branco, com normalização local, sem combinação de imagens. Os multiplicadores medianos medido/previsto foram 2,98, 2,70, 3,08 e 2,99 nas fases G/B/R/G. Usar esses fatores em outra cena foi uma ablação. O resultado cobre um par branco em uma faixa estreita de ISO e exposição. Generalizar a calibração exigiria mais níveis de luz e mais repetições.
- **Altas luzes e sombras.** A saturação declarada cobriu cerca de 0,0012% dos fotossítios na cena de detalhe. A curva foi testada com rampas sintéticas crescentes, e os arquivos lineares mantiveram valores assinados sem corte de gamut. Ainda faltam cenas com realces extensos, faixa dinâmica medida e reprodução de cor calibrada.

## Resultado e testes

A melhor base de laboratório entre as alternativas testadas foi `malvar-gain`, que combina MHC, Wiener com o `NoiseProfile` declarado e GainMaps, com o warp desativado. Ela reduziu em cerca de 35% o ruído residual dos dois pares de detalhe frente a AHD, manteve a maior parte da resposta de borda e melhorou a uniformidade do alvo branco. O JPEG Samsung conservou a liderança em legibilidade e contraste local nesses disparos. A etapa valida a utilidade do RAW individual para experimentação antes da conversão em JPEG. Ela deixa em aberto a superioridade visual em outras cenas, a calibração absoluta e o custo de uma implementação no celular.

Passaram o build de campo, o build instrumentado, o lint e a tarefa JVM, com os binários Android existentes reaproveitados pelo Gradle. Os 46 testes Python passaram com os quatro DNGs reais fornecidos via `HORIZONCAM_TELE_DNG_FIXTURES`, incluindo validações de óptica, integridade e revelação. A instrumentação de captura já validada passou nos dois disparos novos via Wi-Fi. O host usou Python 3.12, rawpy 0.28.0, LibRaw 0.22.2, NumPy 2.5.5, tifffile 2026.11.16, OpenCV 5.1.0 e Pillow 13.0.0.
