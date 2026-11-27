# Fusão RAW Bayer em rajada no host

`tools/calibration/offline_burst.py` importa uma sequência RAW de exposição fixa, registra os quadros e produz um mosaico Bayer linear no enquadramento do quadro de referência. Cada fotossítio tem um desvio-padrão estimado e uma contagem efetiva de quadros. A ferramenta roda no computador e grava os resultados em um diretório novo. Os ensaios abaixo usam um alvo sintético, um recorte simulado a partir de um disparo S24 e uma rajada real do conjunto público HDR+.

## Execução

Em PowerShell, na raiz do repositório, com Python 3.12:

```powershell
$lab = '../s24-qa/offdevice-lab/burst'
py -3.12 -m venv "$lab/venv"
$python = "$lab/venv/Scripts/python.exe"
& $python -m pip install -r tools/calibration/requirements-tele-raw.txt
& $python -m unittest discover -s tools/calibration -p test_offline_burst.py -v
& $python tools/calibration/offline_burst.py benchmark --seed 20261126 --out "$lab/synthetic"
& $python tools/calibration/offline_burst.py fuse "$lab/synthetic/manifest.json" --out "$lab/synthetic-replay"
```

`fuse` recebe JSON versão 1. `reference` é o índice de base zero. Os caminhos `source` são relativos ao manifesto ou absolutos. Exemplo para duas capturas da mesma sessão da teleobjetiva:

```json
{
  "schemaVersion": 1,
  "kind": "capture",
  "cfa": "GBRG",
  "reference": 0,
  "provenance": { "captureSessionId": "session-exemplo" },
  "frames": [{ "source": "pares/primeiro" }, { "source": "pares/segundo" }]
}
```

O manifesto recebe de 2 a 16 quadros. Em `capture`, cada diretório segue o [contrato RAW/JPEG da teleobjetiva](TELE_RAW_LAB.md). O importador verifica os hashes dos pares, a fase GBRG e a geometria no DNG. Exige dispositivo, firmware, rota física 0/6, focal, ISO, exposição, zoom, orientação e crop iguais, além de timestamps e números de quadro distintos e crescentes, dentro de dois segundos. O identificador de sessão é declarado por quem fornece a captura. A validação depende dos timestamps do sensor registrados nos manifestos dos pares.

`synthetic`, `real-derived-simulation` e `hdrplus-capture` usam mosaicos NPY `float32`, com dimensão par, valores finitos e SHA‑256 por quadro. O caso HDR+ preserva também os hashes dos DNGs de origem. O importador usa o `NoiseProfile` RGB de cada DNG em `capture` e o perfil RGB declarado no manifesto adaptado nos outros casos. Em ambos, `noiseVarianceScaleRgb` pode declarar uma calibração multiplicativa de variância, com padrão `[1, 1, 1]`. A ordenação GBRG contém dois verdes amostrados em fases independentes, com os mesmos coeficientes de ruído.

As saídas de `fuse` são:

| Arquivo | Conteúdo |
| --- | --- |
| `fused-gbrg.npy` | Mosaico float32 na grade original, antes do ganho óptico e da orientação de exibição |
| `uncertainty-std.npy` | Desvio-padrão modelado por fotossítio em unidades lineares normalizadas, com NaN no realce protegido |
| `effective-frames.npy` | Contagem efetiva de Kish, `(Σw)² / Σ(w²)` |
| `fused-srgb.png` | Prévia com demosaico Malvar–He–Cutler e ganho `--display-ev` explícito |
| `burst-report.json` | Proveniência, hashes, deslocamentos, escores por bloco, cobertura, rejeições e tempo no host |

## Registro, fusão e incerteza

1. Subtrai o preto por fase e normaliza para o branco do DNG. O sinal RAW pode ser negativo. A média das duas grades verdes fornece a imagem de registro. A correlação de fase inicializa ECC em quatro regiões sobrepostas. Para aceitar o quadro, a maioria das regiões confiáveis precisa concordar sobre uma translação com tolerância de 0,65 fotossítio. O caso com apenas duas regiões pede confirmação global. Os deslocamentos em `dxdy` são da posição no quadro de referência para a posição de amostragem na fonte, em fotossítios. [Correlação de fase OpenCV](https://docs.opencv.org/doc/doxygen/html/d7/df3/group__imgproc__motion.html) · [Registro ECC OpenCV](https://docs.opencv.org/3.4.10/dc/d6b/group__video__track.html)
2. Divide o deslocamento por dois para amostrar cada subgrade de cor separadamente com bilinear. A variância de uma amostra na grade original segue `s × max(sinal, 0) + o`. O sinal local vem da média na grade da referência. A variância do quadro alinhado multiplica o ruído esperado pela soma dos quadrados dos pesos bilineares e acrescenta uma penalidade de modelagem pela inclinação e curvatura locais. [Especificação DNG](https://helpx.adobe.com/camera-raw/desktop/dng-and-file-formats/digital-negative.html)
3. Cada candidato recebe peso proporcional à confiança de registro e de movimento e inversamente proporcional à variância modelada. O teste de movimento compara o resíduo com a variância da diferença. As quatro fases contribuem para o veto em vizinhanças pequenas. A fusão descarta amostras auxiliares que exigiriam extrapolação na borda ou que tocam valores próximos da saturação. Fotossítios sem suporte válido e realces da referência a partir de 0,98 reproduzem a referência exatamente.
4. Soma `w² × variância` e divide o desvio-padrão resultante pela soma dos pesos. Esse cálculo é condicional aos pesos e à translação estimados. Em `capture`, a prévia reutiliza os GainMaps, a matriz de cor e a orientação da referência. Em HDR+, usa o balanço de branco e a matriz `rgb2rgb.txt` publicados para essa sequência. As comparações Bayer ficam antes do ganho óptico. Os dois tipos de prévia usam a mesma implementação MHC existente.

## Ensaio controlado

O ensaio totalmente sintético usa oito quadros, três sementes cobertas pelos testes, translações fracionárias conhecidas, ruído gaussiano com variância dependente do sinal, uma região que aparece e outra que desaparece depois da referência. A geração usa Lanczos sobre a cena RGB antes da amostragem Bayer. A coluna sem veto mantém o mesmo registro, o ruído modelado e a proteção de realce, com o veto de movimento desativado. A tabela usa a semente `20261126` e unidades lineares normalizadas. A métrica de detalhe é a projeção do gradiente sobre o gradiente conhecido, com valor nominal 1.

| Métrica | Referência | Com veto | Sem veto |
| --- | ---: | ---: | ---: |
| RMSE Bayer estático | 0,020632 | 0,010925 | 0,011873 |
| RMSE Bayer na entrada da oclusão | 0,022814 | 0,022814 | 0,220776 |
| RMSE Bayer na saída da oclusão | 0,021170 | 0,021170 | 0,344700 |
| Ruído RMS RGB nos meios-tons planos | 0,014631 | 0,003444 | 0,003444 |
| Ganho de borda / linhas / textura | 0,941 / 1,022 / 1,057 | 0,898 / 0,896 / 0,895 | 0,898 / 0,890 / 0,884 |
| PSNR RGB | 30,81 dB | 32,89 dB | 23,70 dB |

Os sete quadros auxiliares foram aceitos, com erro de translação máximo de 0,112 fotossítio. A mediana de quadros efetivos foi 7,00 nas posições estáticas válidas e 1,00 em ambas as regiões ocluídas. O RMSE Bayer medido no meio-tom plano foi 0,003558 para desvio previsto de 0,003499. Na região estática mais ampla, que inclui estrutura, foram 0,010494 e 0,008248. A incerteza subestima o erro nessa segunda medição, que inclui viés de interpolação.

O ensaio derivado do S24 usa apenas `detail-a`, par `cd53923e-e99e-4d0c-86d6-bd870aff46f3`, DNG SHA‑256 `eff199aa4f1c7d39a6162c9aae68951f23b9708cfc2c2740934a45f8d91e25c5`. O recorte Bayer 576 × 384 na origem `(2016, 768)` passa pelo Wiener e MHC existentes para criar o alvo da simulação. Os ganhos de variância R/G/B 3,080/2,983/2,704 vêm do relatório plano anterior. A primeira execução com recorte central e a execução acima com recorte de maior contraste rejeitaram os sete auxiliares sintéticos com oclusão, por insuficiência de regiões confiáveis. O recorte de maior contraste obteve ECC global 0,526–0,535 e manteve o RMSE de 0,018036 e a referência pixel a pixel. O alvo é derivado de uma única captura já ruidosa. O teste de importação com os quatro pares S24 independentes recusou a sequência pela mudança de parâmetros antes de criar a saída.

```powershell
$rows = Get-Content ../s24-qa/raw-device-bench/fixtures/fixtures.json -Raw | ConvertFrom-Json
$detail = ($rows | Where-Object label -eq 'detail-a').originalPair
& $python tools/calibration/offline_burst.py benchmark --source-pair $detail --source-origin 2016 768 `
    --flat-calibration ../s24-qa/tele-isp-final/flat-a/isp-report.json --display-ev 1.5 --out "$lab/s24-derived"
```

## Rajada pública HDR+

A sequência pública `0006_20160722_115157_431` contém sete DNGs consecutivos do Google Pixel `sailfish`, ISO 51 e 0,419 ms nos quadros de entrada, com referência `N000`. O adaptador decodifica os tiles JPEG sem perda via LibRaw e lê o preto fracionário, o branco, a orientação e o `NoiseProfile` via TIFF. A origem `(1513, 1134)` do recorte de 1024 × 768 muda a indexação BGGR para GBRG sem reamostrar. O mesmo recorte é aplicado à área ativa de `merged.dng` da versão `20171023`, respeitando os oito pixels mascarados no topo do DNG publicado. A comparação numérica usa margem interna de 24 fotossítios. Dataset sob CC-BY-SA, de Hasinoff, Sharlet, Geiss, Adams, Barron, Kainz, Chen e Levoy, ACM TOG/SIGGRAPH Asia 2016. [Dataset, licença e artigo](https://www.hdrplusdata.org/dataset.html)

```powershell
$id = '0006_20160722_115157_431'
$inputDir = "$lab/hdrplus-$id-inputs"
$burstPath = "20171106_subset/bursts/$id"
$resultPath = "20171106_subset/results_20171023/$id"
$bucket = 'https://storage.googleapis.com/hdrplusdata'
New-Item -ItemType Directory -Path $inputDir
foreach ($i in 0..6) {
    $name = 'payload_N{0:D3}.dng' -f $i
    Invoke-WebRequest "$bucket/$burstPath/$name" -OutFile "$inputDir/$name"
}
Invoke-WebRequest "$bucket/$burstPath/rgb2rgb.txt" -OutFile "$inputDir/rgb2rgb.txt"
foreach ($name in 'reference_frame.txt', 'merged.dng') {
    Invoke-WebRequest "$bucket/$resultPath/$name" -OutFile "$inputDir/$name"
}
& $python tools/calibration/offline_burst.py hdrplus $inputDir --dataset-path $burstPath `
    --display-ev 1.5 --out "$lab/hdrplus-run"
```

`N001` a `N003` passaram no registro. Os três quadros seguintes falharam na concordância da translação entre regiões. A mediana de quadros efetivos foi 3,44. O maior erro de ciclo entre os registros direto e inverso, para auxiliares aceitos, foi 0,00516 fotossítio. Essa medida verifica consistência do registro, sem posição absoluta conhecida. O RMSE Bayer em relação à fusão publicada foi 0,0014366 para a referência, 0,0010676 para a fusão com veto e 0,0010680 para a fusão sem veto. A redução de 25,7% mede concordância com uma fusão produzida a partir do mesmo burst. O conjunto oferece uma referência de processamento e composição e deixa a imagem da cena sem ruído desconhecida. O efeito pequeno do veto nesse recorte real é distinto do contraste observado no ensaio com oclusão controlada.

| Original | SHA-256 |
| --- | --- |
| `payload_N000.dng` | `9744f7c309c7aa278d470e497126ae58da439910f46c4c09f3021b76586689d9` |
| `payload_N001.dng` | `1addfecd85941998c716234bebdeff49b0c4db200aeb80890a42d8cdbe229eb0` |
| `payload_N002.dng` | `6d71dce885e463ff93d44161e527ef4b54ec6d753de8e95c6355b9a5e8731a03` |
| `payload_N003.dng` | `75f232264bb06d866c977e277955ec2571e910f6dfe0c9c770c4979ebfff3999` |
| `payload_N004.dng` | `147c4faa3df15b1891ff1a31400926a98c5c6c641ece9803d2ec13b6eb652513` |
| `payload_N005.dng` | `512e5cf2e1992d45aa35c1d1aaf7973053282255c1d7db7c63f6138fe442b02a` |
| `payload_N006.dng` | `fd659e755429a620b0a127ff3087afca452a391ef666ac6ddc5028e81487a34a` |
| `merged.dng` | `f47ea23e0b2ffcfba8dd8fa42e3d8fefeea87cae0854215aad55163a9f92e1cd` |
| `reference_frame.txt` | `9a271f2a916b0b6ee6cecb2426f0b3206ef074578be55d9bc94f6f3fe3ab86aa` |
| `rgb2rgb.txt` | `0dcdb0315edf307cb8073ec9093edd517c4a1977a981d43b57ab8bcf63a16836` |

## Verificação e limites

Passaram seis testes novos e dez testes existentes do ISP no Windows com Python 3.12.10 e as dependências fixadas do laboratório. Executar `fuse` novamente no manifesto HDR+ produziu os três NPYs com SHA-256 idêntico. Os pixels da prévia PNG também coincidiram. Os bytes do arquivo PNG variaram no campo de data de criação do perfil ICC gerado pelo Pillow. Os artefatos estão em `../s24-qa/offdevice-lab/burst/{synthetic-final-evidence,s24-detail-a-crop2016x768,hdrplus-final-evidence}`. Uma execução no host levou cerca de 258 ms para o sintético e 463 ms para o recorte HDR+ durante leitura dos mosaicos, registro e fusão. A decodificação inicial dos DNGs públicos, checagem de hashes, comparação e gravação ficam fora desse intervalo.

O modelo cobre apenas translação global e rejeição local por resíduo. Rotação, paralaxe, rolling shutter, variação fotométrica, sombras que mudam e regiões sem textura podem reduzir a cobertura ou fazer o sistema conservar a referência. A fusão mantém a resolução original, preserva as altas luzes do quadro de referência e tem perda medida de textura fina. A incerteza não inclui correlações entre fotossítios, luz variável nem viés sistemático do ISP. Falta validar com rajadas RAW tele reais do S24, mais cenas públicas e capturas de movimento com referência independente. A validação se restringe ao computador e aos recortes informados.
