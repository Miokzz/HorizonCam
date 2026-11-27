# Restauração, geração e fusão RAW no computador

Em 26/11/2026, a branch de desenvolvimento partiu de `1193b84`. A árvore original em `phase-1/camera` tinha alterações locais. A execução usou a árvore vinculada `HorizonCam-photo-performance`, que já continha a otimização do registro de Foto, testes de cadência e o ensaio de exportação RGB. Originais, pesos, imagens de avaliação e saídas ficaram fora do Git em `../s24-qa/offdevice-lab`.

## Recursos e arquitetura

| Componente | Evidência e decisão |
|---|---|
| Computador | Windows x64, Ryzen 7 5700G, 8 núcleos / 16 threads, 31,9 GiB físicos, 143,5 GiB livres em disco no início. RTX 5060, 8.151 MiB reportados pelo driver 621.01. Um kernel OpenCL executou nessa GPU. PyTorch 2.15.0+cpu e TensorFlow CPU 2.20.0 executaram as redes em CPU. |
| Compiladores | Python 3.12.10, JDK 21, Gradle 9.6.0, SDK Android 36, Build Tools 36.0.0, NDK 27.2.12479018, CMake 3.22.1, MSVC 14.44 x64. |
| Android em uso | Foto automática usa JPEG privado e WorkManager. O ponto de recuperação é um arquivo local, com UUID da captura para resolver a linha MediaStore pendente. O executor serial de inferência retém o delegate por até 45 s e aguarda sua saída antes de devolver buffers ao chamador cancelado. |
| Coordenação | `MainActivity.kt` tem 2.180 linhas no ponto auditado. Permissões, medição, callbacks de abertura, confirmação óptica e ciclo de vida do slider convivem na Activity. A fronteira de extração justificável é a orquestração de sessão com geração e confirmação de quadro. Alterá-la exige repetir reversões e captura imediatamente após a troca. Nenhum destes experimentos demanda essa mudança. |
| Laboratório | A revelação é MHC, Wiener e GainMaps. A entrada da CNN tem quatro planos Bayer e quatro canais dependentes de ruído. Os ensaios residentes conservam FP32. Nos quatro originais, o desvio histórico máximo do residente contra NEON foi de um nível RGB em até 365 de 29.942.784 canais por foto. Com o mesmo Bayer de entrada o RGB coincidiu em 4/4. Não há justificativa fotográfica nesses pares para perseguir igualdade das acumulações internas. |
| Integração futura | Medir suporte e incerteza por região, renderizar o RAW alinhado e usar a imagem de referência, a saída clássica e diferentes forças generativas como alternativas reprocessáveis. Seleção semântica ou união automática de resultados ainda exige um teste cego por categoria e casos em que os modelos discordam. |

Histórico técnico e matriz de candidatos. [Auditoria de fidelidade](TELE_RAW_ISP_FIDELITY.md) · [Arquitetura Android](HORIZON_AI_V3_ARCHITECTURE.md) · [Modelos, pesos e licenças](OFFDEVICE_MODEL_MATRIX.md)

## Observações reais para multiframe e treino

O importador validou três sequências públicas independentes da câmera Google sailfish, com exposição fixa dentro de cada sequência. Mantivemos recortes centrais de 1024 × 768 fotossítios, reposicionados em duas dimensões para igualar a fase GBRG, e os hashes dos DNGs. O mesmo algoritmo e os mesmos limiares foram aplicados às três cenas. A fusão publicada pela Google reutiliza as entradas e permite medir concordância. [HDR+ e licença dos dados](https://hdrplusdata.org/dataset.html) · [Algoritmo, oclusão, sintético e controle negativo S24](OFFLINE_BURST.md)

| Burst da coleção 20171106_subset | Aceitos / disponíveis, incluindo a âncora | RMSE da âncora / fusão com veto / fusão sem veto contra a fusão publicada, Bayer linear |
|---|---:|---|
| 0006_20160722_115157_431 | 4 / 7 | 0,001437 / 0,001068 / 0,001068 |
| 0006_20160727_185921_651 | 3 / 4 | 0,001896 / 0,001558 / 0,001959 |
| 0009_20160702_154921_383 | 7 / 7 | 0,001134 / 0,000549 / 0,000551 |

O treino Noise2Noise usou a primeira sequência para aprender, a segunda para selecionar o passo e a terceira para teste. Cada alvo veio de outra exposição física. Foram aceitos 3, 2 e 6 alvos auxiliares, respectivamente, após concordância do registro direto e inverso. A máscara exclui saturação, bordas sem suporte e mudanças locais elevadas. A interpolação do alvo correlaciona amostras vizinhas e pode atenuar frequências altas. A seleção usa 32 patches fixos por partição de avaliação. O número de cenas independentes determina o alcance deste ensaio. [Noise2Noise](https://proceedings.mlr.press/v80/lehtinen18a.html)

Em 150 passos, semente 1827, dois threads e 1,81 s de treino e validação, o passo selecionado foi 150. O erro quadrático no alvo ruidoso reservado caiu de 3,486 × 10⁻⁶ para 3,451 × 10⁻⁶. A âncora sem rede marcou 3,672 × 10⁻⁶. Essa diferença de aproximadamente 1% contra a rede inicial justifica ampliar o número de cenas. O teste deixa em aberto o efeito sobre detalhe óptico, viés de interpolação e transferência Google → Samsung.

## Fotografias reais com degradação controlada

O manifesto versiona licença, autor, grupo de cena, categoria, SHA do arquivo e do raster orientado. Valida grupos exclusivos por partição e rejeita pixels repetidos inclusive em arquivos com codificação diferente. Os originais das 11 imagens foram divididos em quatro de treino, duas de validação e cinco de teste antes dos recortes. A divisão é deliberadamente pequena. Cada imagem de teste fornece oito patches avaliados. A libélula de André Karwath participa apenas da avaliação. [Fontes de scikit-image](https://scikit-image.org/docs/stable/api/skimage.data.html) · [Foto da libélula, CC BY-SA 2.5](https://commons.wikimedia.org/wiki/File:Sympetrum_flaveolum_-_side_(aka).jpg)

A inversão da curva sRGB, a amostragem GBRG e duas realizações independentes de ruído shot/read geraram os exemplos. Incluímos a quantização do simulador de 10 bits e a escala por cor medida anteriormente com flats da tele. Esses exemplos preservam conteúdo de fotografias, mas a resposta da câmera, o ruído e a origem do Bayer são simulados. A seleção de pesos usou somente o MSE da validação e incluiu o passo zero.

| Controle sobre o sinal linear conhecido | Semente 6421, PSNR / SSIM | Semente 6422, PSNR / SSIM |
|---|---|---|
| Entrada degradada | 34,23 / 0,9187 | 33,98 / 0,9348 |
| Wiener | 37,15 / 0,9569 | 36,27 / 0,9594 |
| CNN original com proteção | 36,44 / 0,9547 | 35,75 / 0,9607 |

O fine-tuning supervisionado e o Noise2Noise sintético não venceram a inicialização na validação em nenhuma das duas sementes em 250 passos. No primeiro ensaio, o erro médio de gradiente em bordas foi menor para a CNN (0,01248) que para o Wiener (0,01403), enquanto o Wiener apresentou menos erro em áreas planas (0,01183 contra 0,01227). Conservar as duas referências preserva esse compromisso. A reexportação do controle escolhido reproduziu o LiteRT FP32 de 27.620 bytes, SHA `5071b54120e71c0dae7669efff1cbc4862cb8fba377bcd33a90f558e7ade820b`. No host, o erro máximo de paridade em sinal linear foi 1,07 × 10⁻⁷.

## Difusão condicional e leitura de texto

Executamos o LDM 4× Open Images da CompVis em FP32, com o U-Net, VQ e scheduler DDIM oficiais. O card do modelo indica Apache-2.0. A revisão fixa é `0b55ddf931a8e3a1b426b3a50ddcf325ff84f668`. O programa verifica os hashes dos pesos e configurações, além dos pixels de entrada por hash do arquivo. Há 168.945.345 parâmetros somados no U-Net e VQ. O modelo consome RGB após a revelação. Guiá-lo diretamente por CFA ou treinar condicionamento RAW seria um experimento adicional. [Card e pesos](https://huggingface.co/CompVis/ldm-super-resolution-4x-openimages) · [Implementação dos autores](https://github.com/CompVis/latent-diffusion) · [LPIPS](https://github.com/richzhang/PerceptualSimilarity)

| Cinco cenas com referência reservada | PSNR médio | SSIM médio | LPIPS Alex médio, menor é melhor | CPU FP32 para entrada 64 × 64, mediana |
|---|---:|---:|---:|---:|
| Bicúbica | 25,17 | 0,7116 | 0,4165 | — |
| DDIM 12 passos | 24,04 | 0,6636 | 0,2038 | 5,90 s |
| DDIM 50 passos | 23,64 | 0,6569 | 0,1934 | 18,92 s |

Cada saída integral usou duas sementes (193 e 817). Cinco recortes públicos incluíram asa de libélula, escrita, grama, pelo e rosto. Os custos acima cobrem o pipeline condicional no Ryzen com quatro threads. A escrita usa entrada 32 × 32. Em um recorte privado de 64 × 64 da revelação MHC do S24, sem referência independente, o ruído de crominância do papel evoluiu para grãos coloridos e as sementes de 50 passos tiveram SSIM entre si de apenas 0,397. Uma segunda série cobriu impressão e um objeto na mesma imagem.

Na asa, a difusão trouxe arestas mais definidas e LPIPS de 0,217 contra 0,346 da bicúbica (50 passos, semente 817). Mudou também a geometria de nervuras finas. No chart com transcrição conhecida de 61 caracteres, o OCR leu o original sem erros. Houve 18 edições na bicúbica, 16 em cada semente de 12 passos, 15 e 16 nas de 50 passos. Letras e números reconstruídos continuam ambíguos. A receita conserva os PNGs de todas as sementes para inspeção. CER usa distância de Levenshtein depois de normalizar somente caixa e espaços. [Tesseract e pesos eng](https://github.com/tesseract-ocr/tessdata_fast/tree/87416418657359cb625c412a48b6e1d6d41c29bd)

A hipótese que merece outro ensaio é condicionar a intensidade ao suporte do burst e usar divergência entre sementes para sinalizar estruturas incertas. A matriz documenta o controle QuickSRNet, o GAN integral e a mistura de 25%. Uma avaliação cega com múltiplos observadores, DISTS, RAW da tele com referência independente e modelos condicionados por semântica continuam pendentes.

## Reprodução no host

Use diretórios novos para cada execução. As imagens e pesos são locais. `$cal` aponta para `../s24-qa/raw-aware-restoration`, `$lab` para `../s24-qa/offdevice-lab`, e `$py` para o Python do laboratório. As dependências base constam em `tools/calibration/requirements-tele-raw-restore.txt`. Para difusão, usamos diffusers 0.35.2, huggingface-hub 0.35.3, LPIPS Alex com backbone ImageNet e os pesos FP32 da revisão acima. Para OCR, Tesseract 5.5.4 via tesserocr. Não é necessário alterar dependências Android.

```powershell
& $py tools/calibration/offdevice_corpus.py prepare --dragonfly "$lab/dragonfly-original.jpg" --out "$lab/scenes"
& $py tools/calibration/offdevice_corpus.py check "$lab/scenes/corpus.json"
& $py tools/calibration/offdevice_n2n.py --manifest "$lab/scenes/corpus.json" --initial "$cal/training/bayer-residual.pt" --calibration "$cal/training/training.json" --seed 6421 --out "$lab/train-6421"
& $py tools/calibration/offdevice_real_pairs.py --train "$burstA/manifest.json" --validation "$burstB/manifest.json" --test "$burstC/manifest.json" --initial "$cal/training/bayer-residual.pt" --out "$lab/real-training"
& $py tools/calibration/export_tele_raw_restore.py --weights "$lab/train-6421/noise2noise.pt" --out "$lab/export"
& $py tools/model/offdevice_text_gate.py chart "$lab/text-chart.png"
Copy-Item tools/model/offdevice_diffusion_cases.json "$lab/cases.json"
& $py tools/model/offdevice_diffusion.py --model "$lab/ldm-weights" --cases "$lab/cases.json" --out "$lab/diffusion" --steps 12 50 --seeds 193 817
& $py tools/model/offdevice_text_gate.py grade "$lab/diffusion/case-06" "$lab/tessdata" "$lab/cer.json"
```

Para obter as três sequências, use a receita de download do [laboratório de burst](OFFLINE_BURST.md) com os três IDs da tabela. A segunda contém quatro payloads e a primeira e a terceira contêm sete. O arquivo de casos permite selecionar apenas cenas públicas quando os originais privados estiverem indisponíveis. A próxima tarefa de bancada é validar o piloto em múltiplos bursts reservados por câmera e ISO, incluindo erro em glifos e estrutura de textura, antes de escolher um especialista para integração.
