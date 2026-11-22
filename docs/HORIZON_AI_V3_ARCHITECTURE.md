# Horizon AI V3 · auditoria e arquitetura experimental

22/11/2026 · Galaxy S24 SM-S9210 TGY · base `87d92e7`

## Pipeline atual

Foto automática recebe um JPEG SDR já revelado pelo ISP. A sessão lógica Camera2 confirma o sensor antes do disparo. A lente física `0/6` permanece disponível. `AutoPhotos` preserva o primeiro JPEG, enquadra o recorte digital e pode coletar até dois quadros adicionais com o mesmo sensor. ISO e tempo de exposição vêm do EXIF. A fusão faz registro subpixel, compensa exposição e rejeita inconsistências locais. Acima de 10×, o peso dos extras alinhados considera nitidez relativa. Foto automática fixa o primeiro quadro como referência. A super-resolução multiquadro com verificação de diversidade de fase pertence ao modo de fusão explícito.

| Artefato local | Papel | Contrato |
| --- | --- | --- |
| `QuickSRNet-small-x4-128.tflite` | Foto com degradação mais leve | BSD-3-Clause, 136.560 bytes, entrada RGB FP32 128² e saída RGB FP32 512², faixa nominal 0–255, halo de 4 px. |
| `RealESRGAN-context-f16.tflite` | Foto a partir de 4× ou quando a regra detecta maior degradação | BSD-3-Clause, 2.470.028 bytes, mesmos tensores FP32, pesos armazenados em FP16, halo de 34 px. |
| `ESRGAN.tflite` | Laboratório de super-resolução em CPU | MIT, 4.993.712 bytes, entrada 50² e saída 200². |

Os modelos e seus hashes conferem com os registros dos assets. O LiteRT 1.4.2 mede paridade em relação à CPU e escolhe entre CPU e GPU no próprio aparelho. QuickSRNet passou com precisão estrita solicitada no S24 e Real-ESRGAN com perda de precisão permitida. QuickSRNet com perda permitida caiu na verificação de paridade. A inferência é seguida de transferência de luminância e croma para as coordenadas do recorte original. A regra de cena usa estatísticas de ruído, luminância e textura. Cor semelhante à pele, gradientes e envelope 3×3 servem de proteção local. Reconhecimento semântico, rostos e OCR ainda estão fora desse caminho de inferência. [Medições Horizon AI](HORIZON_AI.md) · [LiteRT e GPU](https://developers.google.com/edge/litert/android/gpu)

A fila prepara um JPEG privado final, preserva o EXIF e usa a reserva `IS_PENDING` para publicar uma única imagem. A recuperação reencontra a mesma reserva pelo identificador de captura. Falha de GPU permite nova tentativa em CPU. Uma falha de restauração preserva a imagem capturada quando possível ou aplica ampliação bilinear mantendo o recorte, com a origem indicada no EXIF. A PSS registrada após render é uma amostra pontual. Os tempos de disparo, aquisição, fila, fusão, inferência e codificação são registrados separadamente.

### Onde medir a perda de fidelidade

| Ponto | Evidência e ablação |
| --- | --- |
| Aquisição e ISP | O JPEG já passou pela revelação Samsung e guarda os valores SDR resultantes. Compare com RAW da mesma lente, mesma luz e captura controlada. Compare raios e exposição antes de atribuir o resultado ao modelo. |
| Recorte e redução de escala | Em 30× na lógica, somente 306 × 408 valores RGB nativos entraram na reconstrução. A saída 1224 × 1632 registra as dimensões de renderização. As perdas ópticas e de amostragem existem antes da inferência. |
| Registro e fusão | A máscara por movimento pode reduzir suporte em um objeto móvel. Inspecione referência, contagem de quadros, máscara e diversidade de fase nos mesmos quadros. Uma referência já borrada pode continuar borrada mesmo após proteger seu peso. |
| Residual neural | Onde a guia é o original, como nas capturas medidas em 19,9× e 30×, o termo de remoção de alta frequência `y − lowY` vale zero. Teste separadamente o ganho do residual aprendido, a ancoragem tonal e a crominância. A observação de textura de aquarela ainda precisa dessa ablação. |
| Bordas e tiles | Os tiles contribuem apenas com o interior, com contexto por reflexão nas bordas externas. Procure halos, variação em juntas e erro em linhas e glifos nas saídas da rede e da composição. O chart anterior mediu 22,19 dB em 20× e 30× contra 22,50 dB bilinear. Ambos usaram a mesma entrada de 48 × 64. |

## RAW disponível no Galaxy S24

`RawPhotoFieldTest#compareRawDeliveryAcrossPhotoRoutes` capturou buffers RAW_SENSOR privados em sessões de uma saída, verificando timestamp, resolução e focal. Todos os alvos anunciaram capacidade RAW. O ISP já pode aplicar correções de sensor ao RAW entregável pela Camera2. Verificações de calibração, demosaico, DNG e sessões de múltiplas saídas ficam para a prova de RAW pareado. [Formato RAW_SENSOR](https://developer.android.com/reference/android/graphics/ImageFormat#RAW_SENSOR) · [DngCreator](https://developer.android.com/reference/android/hardware/camera2/DngCreator)

| Rota | RAW entregue | Origem observada | Nível branco anunciado | Metadados de ruído |
| --- | --- | --- | --- | --- |
| `0` a 1× | 4080 × 3060 | principal 5, 5,4 mm | 4095 | quatro pares, preto dinâmico |
| `2` direta | 4000 × 3000 | ultrawide, 2,2 mm | 1023 | quatro pares, preto dinâmico ausente |
| `0/5` | 4080 × 3060 | principal física, 5,4 mm | 4095 | quatro pares, preto dinâmico |
| `0/6` | 3648 × 2736 | tele física, 7,0 mm | 1023 | quatro pares, preto dinâmico |
| `0` com pedido de 3× | 4080 × 3060 | principal 5, 5,4 mm | 4095 | quatro pares, preto dinâmico |

O caminho candidato para RAW tele é `0/6`, com a focal verificada em cada sessão. A tele lógica foi provada anteriormente em saídas JPEG. O modo avançado existente usa CameraX RAW quando a capacidade do binding e o mapa físico permitem. A prova acima valida a entrega Camera2, e a sessão combinada `prévia + RAW` e o DNG do produto ainda exigem provas próprias.

## Referências de engenharia

| Fonte primária | Como informa o projeto |
| --- | --- |
| [Luma](https://github.com/DataRohit/luma) · [fusão](https://github.com/DataRohit/luma/blob/main/engine/src/align_merge.cpp) · [RAW/DNG](https://github.com/DataRohit/luma/blob/main/docs/RAW_AND_DNG.md) | Implementação Android aberta com alinhamento em pirâmide, fotometria RAW, rejeição por variância e cobertura subpixel, seguida de demosaico e renderização. O modelo de ruído usa ganho proporcional ao sinal e um termo aditivo. Seu motor e testes são referência de engenharia. A validação indicada é no S26 Ultra. |
| [HDR+](https://research.google/pubs/burst-photography-for-high-dynamic-range-and-low-light-imaging-on-mobile-cameras/) · [Google Handheld Multi-Frame Super-Resolution](https://research.google/pubs/handheld-multi-frame-super-resolution/) | Alinhar e fundir RAW antes de revelar reduz ruído e preserva latitude. Amostras subpixel realmente diferentes podem sustentar reconstrução. A aceitação depende de cobertura e confiança. |
| [AISP](https://github.com/mv-lab/AISP) · [LPIENet](https://arxiv.org/abs/2210.13552) | Referências para learned ISP e restauração RAW e SDR. LPIENet tem tutorial de exportação TFLite e tempos reportados pelos autores em smartphones. Licença de código, licença de pesos, conversão e custo no S24 precisam ser verificados antes da integração. |
| [MFDNet Android](https://github.com/AbdulCantCode/MFDNet-Android) · [NAFNet](https://arxiv.org/abs/2204.04676) | MFDNet traz uma amostra de denoising TFLite e relata latência em CPU. Variação total serve como diagnóstico secundário, pois também cai ao apagar textura. A licença do peso MFDNet ainda precisa ser estabelecida. NAFNet é uma linha de comparação para denoise/deblur de mesma escala. |
| [SAM4IR](https://github.com/WeiLi-THU/SAM4IR) · [artigo](https://arxiv.org/abs/2403.16368) · [MobileNetV3](https://arxiv.org/abs/1905.02244) | SAM4IR destila informação semântica durante o treino para melhorar um restaurador leve. Seus resultados publicados incluem denoise e deblur. MobileNetV3 com decoder leve é candidato a segmentação de baixa resolução. Generalização para recortes 30×, partes de insetos e texto exige dados próprios e teste de confiança. |
| [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN) | Baseline de restauração cega com degradações sintéticas. Artefatos de ISP, compressão e movimento no S24 podem divergir da distribuição de treino. Medir o ganho da saída formatada contra o próprio residual e contra a interpolação. |

Samsung divulga ProVisual, AI ISP e Nightography. O material sobre sensores e zoom do S24 Ultra tem escopo específico. OPPO descreve HyperTone e AI Telescope Zoom. A vivo descreve BlueImage como combinação de sensores, algoritmos e chips, com tele ZEISS nas respectivas linhas. A Huawei anuncia XD Motion e XD Fusion Pro na linha Pura 70. São divulgações oficiais de produto. Os pesos, dados de treino, fusão e interfaces internas desses pipelines permanecem proprietários. A Qualcomm anuncia segmentação em até 12 camadas no ISP Spectra do Snapdragon 8 Gen 3. O teste via Camera2 demonstrou buffers e metadados públicos, e o acesso ao segmentador do ISP ainda é desconhecido para o aplicativo. [Samsung](https://news.samsung.com/global/capture-the-perfect-shot-every-time-with-galaxys-provisual-engine) · [OPPO](https://www.oppo.com/en/newsroom/press/find-x8-series-coloros-15-launch-camera-ai/) · [vivo BlueImage](https://asia-exstatic-vivofs.vivo.com/PSee2l50xoirPK7y/activity/1726036437437/zip/img/vivo_Sustainability_Report_2023.pdf) · [Huawei](https://consumer.huawei.com/cn/support/pura70series/) · [Qualcomm](https://www.qualcomm.com/smartphones/products/8-series/snapdragon-8-gen-3-mobile-platform)

## Arquitetura a comparar

1. Preservar uma referência de captura com sensor, exposição, geometria e codec. Manter JPEG como baseline. Testar RAW da tele em fluxo separado, com normalização por nível preto e branco, perfil de ruído, balanço e matriz de cor verificáveis.
2. Produzir uma imagem registrada e um mapa local de suporte. Usar aceitação de quadros, erro no registro, movimento local e cobertura de fase. Onde a cobertura é insuficiente, renderizar com resolução compatível com a evidência e preservar as bordas observadas.
3. Separar denoising de luminância e crominância do passo de ampliação. Comparar um restaurador de mesma escala antes de adicionar outro modelo. Usar o mesmo decodificador, enquadramento e renderização em todas as variantes.
4. Testar primeiro um controle local do residual atual que protege traços e bordas coerentes. Depois, comparar máscaras semânticas em escala reduzida. Segmentação e detecção podem limitar a intensidade em pessoas, texto, folhagem e estruturas finas. Reconhecimento de objetos deve condicionar pesos e limites da restauração, com saída ancorada nos pixels, sem gerar partes a partir do nome de uma classe.
5. Avaliar um modelo residual compartilhado com pequenas cabeças de denoise e ampliação. Usar segmentação leve ou professores offline para distilação. Validar em pares novos e em sinais ambíguos, incluindo um grupo `desconhecido` em que o modelo reduz sua intervenção.
6. Manter LiteRT GPU como ponto de partida medido. Comparar CPU e GPU com os mesmos tensores, paridade e PSS. Avaliar FP16, INT8 e custo de transferências individualmente. NNAPI está descontinuado desde o Android 15. OpenGL ES e Vulkan são candidatos a operações de imagem quando medições mostrarem que leitura, resampling ou composição limitam a etapa. [Migração NNAPI](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide)

## Comparação e critérios para avançar

- Mesmas entradas da câmera para produção, bilinear, denoising adicional e restauração contextual. Fixar crop, ISO, ampliador e versão dos assets. Repetir a fusão com exatamente o mesmo burst para separar seu custo e benefício da aquisição.
- Medir primeiro unidades com referência independente em 3×, 10×, 20× e 30×, incluindo chart de texto e bordas, superfícies complexas, pelo, inseto e folhagem. Incluir 0,6× e 1× para desempenho, pele e preservação de detalhe nativo.
- Tratar PSNR, SSIM e LPIPS como métricas com referência alinhada. Medir erro de caracteres e deslocamento de bordas, overshoot e undershoot, cor e ruído em patches, e detalhamento de textura. Compare A/B cego em tamanho equivalente para identificar alucinações e ghosting. A câmera Samsung serve de comparador de campo com exposição, distância e FOV registrados.
- Exigir ganho por categoria sem regressão em texto e rostos antes de uma mudança padrão. Medir tempo frio e quente, mediana e P95, pico de memória quando mensurável e status térmico em série. Conferir backend efetivo e um JPEG público por disparo.
- O replay Android atual usa ampliação 1× e ISO 400 por padrão. O passo experimental é admitir 2×/4× medidos e repetir o caminho real no mesmo JPEG. `benchmark_adaptive_transfer.py` fornece triagem de textura e PSNR/SSIM/LPIPS no host, com reimplementação aproximada da composição Android. Seus outros recortes das mesmas fotos servem de controle por recorte. Use cenas novas para avaliação independente.
