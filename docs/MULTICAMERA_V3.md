# Zoom contínuo multicâmera no Galaxy S24

22/11/2026. Investigação iniciada depois da validação de Foto JPEG no checkpoint `4604c70`. Hardware de campo Samsung Galaxy S24 SM-S9210, Snapdragon 8 Gen 3, firmware TGY.

## Referências técnicas

- Camera2 expõe uma câmera lógica por `CameraDevice` e aceita saídas por sensor em `SessionConfiguration` e `OutputConfiguration.setPhysicalCameraId()`. A regra de compatibilidade cobre uma substituição específica de saída lógica YUV ou RAW por um par físico equivalente. Outros formatos, terceira saída, JPEG, encoder, fps e EIS exigem provas independentes. O teste de configuração e os resultados por sensor têm papéis distintos. [API multicâmera Android](https://developer.android.com/media/camera/camera2/multi-camera) · [AOSP HAL multicâmera](https://source.android.com/docs/core/camera/multi-camera)
- `CONTROL_ZOOM_RATIO` e `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` mostram o comportamento do fluxo lógico. Resultados físicos e focal verificam saídas fixas. `getConcurrentCameraIds()` mede dispositivos que podem abrir juntos e tem escopo diferente das saídas de um único dispositivo lógico. [CaptureRequest](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_ZOOM_RATIO) · [CaptureResult](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) · [Streaming concorrente](https://source.android.com/docs/core/camera/concurrent-streaming)
- A OPPO demonstrou um módulo mecânico de 85–200 mm equivalentes em 2021. No Find X8 Ultra, divulgou dois módulos periscópicos fixos e um sensor True Chroma. A referência orienta o objetivo visual do HorizonCam, que depende das três focais fixas do S24. A Apple expõe pontos de transição de lentes e regras de seleção por condição de cena, como distância de foco e luz. [Módulo OPPO](https://www.oppo.com/in/newsroom/press/oppo-future-imaging-technology-launch/) · [Find X8 Ultra](https://www.oppo.com/en/newsroom/stories/oppo-find-x8-ultra-rewriting-photography-rules/) · [AVFoundation](https://developer.apple.com/documentation/avfoundation/avcapturedevice/primaryconstituentdeviceswitchingbehavior-swift.enum)
- A Qualcomm descreve três ISPs no Snapdragon 8 Gen 3 e remete às especificações do fabricante. A capacidade do silício precisa ser verificada na API pública do firmware. Pesquisas de zoom suave e fusão híbrida separam alinhamento, cor e oclusão. Um plano fixo é um ponto de partida para calibração. Controle de paralaxe e consistência temporal exigem cenas em várias profundidades. [Snapdragon 8 Gen 3](https://www.qualcomm.com/smartphones/products/8-series/snapdragon-8-gen-3-mobile-platform) · [Dual-Camera Smooth Zoom](https://arxiv.org/abs/2404.04908) · [Efficient Hybrid Zoom](https://arxiv.org/abs/2401.01461)

## Evidência de campo

A rota atual usa `2` para 0,6×, `0` com sensor 5 confirmado para a principal e `0/6` para a tele real. A saída fixa `0/2` já apresentou 1,397× de recorte, equivalente a cerca de 0,83×. O zoom lógico contínuo e as saídas físicas simultâneas são hipóteses diferentes. [Calibração e troca](ZOOM_EVALUATION.md)

O ensaio `MultiCameraFieldTest` rodou por ADB Wi-Fi e passou em 11,056 s. Nenhuma imagem foi salva. O firmware expôs os dispositivos 0, 1, 2 e 3. As combinações de dispositivos anunciadas foram 0+1 e 0+3. A traseira lógica 0 listou os membros 2/5/6, intervalo de zoom nativo 0,6–10× e sincronismo `APPROXIMATE`. Zoom total de 30× ainda precisaria de recorte complementar. O mapa YUV individual da lógica anuncia duração mínima de 16,67 ms em FHD e 33,33 ms em 4K. Esses valores descrevem uma saída e não provam fps com encoder ou EIS.

Em uma sessão lógica com uma saída YUV 1440 × 1080, alterar `CONTROL_ZOOM_RATIO` confirmou 12 quadros por nível. A cadência medida ficou em 30 fps, e a focal e o ID ativo seguiram a mudança. A relação de FOV precisa de verificação por pixels neste caminho.

| Solicitado e reportado | Sensor HAL | Focal | Cadência |
| --- | --- | --- | --- |
| 0,6× | 2 | 2,2 mm | 30,00 fps |
| 1× | 5 | 5,4 mm | 30,00 fps |
| 3× | 6 | 7,0 mm | 30,01 fps |

A consulta aceitou todas as combinações abaixo em YUV 1440 × 1080. O teste exigiu correspondência entre os timestamps do buffer e do resultado físico para atribuir a imagem à lente.

| Saídas físicas | Buffers recebidos | Evidência de origem |
| --- | --- | --- |
| 2 + 5 | 6 + 6 | Ambos confirmados em seis pedidos a 30,01 fps. Timestamps iguais nos pedidos medidos. |
| 5 + 6 | 37 + 38 | Tele confirmada em 38 quadros. A origem do fluxo 5 ficou sem confirmação. |
| 2 + 6 | 36 + 36 | Ultrawide confirmada em 36 quadros. A origem do fluxo 6 ficou sem confirmação. |
| 2 + 5 + 6 | 25 + 24 + 24 | 2 e 5 confirmados em 24 quadros. A origem do fluxo 6 ficou sem confirmação. |

A combinação 2+5 repetiu o recorte físico de 1,397× na ultrawide. Assim, o par pode cobrir uma região de sobreposição, mas o recorte observado não alcança sozinho o campo de 0,6×. O valor de skew 0 no par indica timestamps iguais, sem provar fase de exposição. Nos três cenários restantes, buffers recebidos e consultas positivas deixaram a simultaneidade física completa sem evidência suficiente.

### Foto e vídeo com as superfícies de cada modo

As duas provas instrumentadas passaram por ADB Wi-Fi em 27,727 s. As imagens e a saída AVC foram descartadas em memória.

Em Foto, uma abertura de `0` e uma sessão com YUV 1440 × 1080 e JPEG 4080 × 3060 confirmaram 2,2 mm, 5,4 mm e 7,0 mm no EXIF de 0,6×, 1× e 3×. O crop reportado coincidiu nos pares prévia/JPEG do mesmo timestamp. A correlação normalizada de luminância foi 0,983, 0,979 e 0,983. A melhor solução da busca em escala e deslocamento foi a identidade na grade usada. Cada JPEG saiu em 192–198 ms após o pedido de teste. As dimensões do JPEG lógico representam a saída do HAL. O ensaio não comparou resolução óptica, nitidez ou alcance total contra a rota física, e o zoom nativo publicado terminou em 10×.

Em Vídeo, uma superfície de prévia PRIV 1280 × 720 e uma entrada real do MediaCodec AVC `c2.qti.avc.encoder` funcionaram nas quatro configurações. Cada configuração manteve uma única sessão durante 0,6×/1×/3×, confirmou os sensores 2/5/6 e manteve PTS monotônicos. O primeiro resultado com zoom aplicado nas duas trocas ocorreu em 112–233 ms. Esse tempo mede metadados do sensor, sem incluir toda a latência até a tela.

| Configuração solicitada | Cadência AVC da sessão | Evidência e limite |
| --- | --- | --- |
| FHD/30 | 29,94 fps | Três sensores, sem parada acima de 750 ms. |
| FHD/60 | 59,42 fps | Três sensores. Houve intervalos de até 41,71 ms na prévia em 3×. |
| 4K/30 | 30,03 fps | Três sensores, sem parada acima de 750 ms. |
| 4K/60 | 58,66 fps | Três sensores. Em 3×, o AVC mediu 57,45 fps e teve intervalos de até 33,32 ms. |

A prova de vídeo usou AVC SDR, sem áudio, EIS, Horizon Lock ou arquivo final. Ela demonstra viabilidade do caminho lógico em períodos curtos. A produção ainda precisa validar sincronismo de áudio, qualidade visual, estabilização, cadência sustentada, consumo e temperatura.

## Prova de alinhamento fora do aplicativo

`tools/calibration/compare_lens_transition.py` comparou os JPEGs originais da calibração existente, na mesma cena estática, com largura normalizada em 960 px. O ajuste da homografia e o teste geométrico usaram pontos diferentes. O ajuste simples de cor por canal e a avaliação usaram blocos alternados. Nenhum pixel da cena foi incluído no Git.

| Par | Erro mediano do recorte central | Após homografia mediana / P95 | ΔE76 mediano antes → depois do ajuste de cor |
| --- | --- | --- | --- |
| Ultrawide → principal | 27,29 px | 1,00 / 2,63 px | 5,17 → 2,12 |
| Principal → tele | 16,86 px | 1,45 / 3,88 px | 2,85 → 2,45 |

A homografia concordou com 89% e 63% dos pontos reservados, cobrindo 48% e 30% do quadro por área convexa, respectivamente. O resultado mede o plano dominante e uma correção de cor exploratória em sRGB. Ele deixa movimento, paralaxe em profundidades diferentes, AF, AE, AWB e custo por quadro em aberto.

Para reproduzir com um alvo plano consentido, use `python tools/calibration/compare_lens_transition.py WIDE.jpg NARROW.jpg --zoom-ratio RAZAO_MEDIDA`. Use a razão de FOV já medida para comparar o recorte central com o registro.

## Arquiteturas candidatas

| Arquitetura | Vantagem | Prova exigida |
| --- | --- | --- |
| Uma sessão lógica por modo | Preserva a cadência da sessão e delega troca ao HAL | IDs físicos e FOV corretos com superfícies de prévia, captura ou encoder do modo real |
| Pares físicos adjacentes em um dispositivo | Permite comparar dois quadros ao vivo e estimar diferença de cor, geometria e fase | Suporte a cada par, cobertura nos limites 0,6×/1× e 1×/3×, estabilidade e folga térmica |
| Rotas com dispositivo fechado entre trocas | Mantém os sensores comprovados e JPEG único atualmente | Continua como fallback. O quadro retido é indicado na UI e fica fora da captura e da gravação |

Foto pode usar um segundo fluxo de baixa resolução para alinhar a prévia, mantendo o JPEG de resolução completa na lente confirmada. Uma futura fusão requer amostras sincronizadas, coerência de cor e confiança contra oclusão. Vídeo requer superfície persistente do encoder, PTS monotônicos, áudio contínuo e recorte no mesmo espaço usado pelo EIS e Horizon Lock.

A primeira composição experimental deve aproveitar OpenGL e as superfícies existentes. Aplicar correções com as texturas na GPU evita copiar cada par para a CPU. Duas entradas YUV 4:2:0 em 1080p60 representam aproximadamente 373 MB/s de payload sem padding nem conversão. Vulkan merece comparação se o perfil medir gargalo de GPU ou cópias que o pipeline OpenGL não possa evitar. O caminho Vulkan requer importação e suporte efetivo do formato de buffer. [SurfaceTexture no OpenGL ES](https://developer.android.com/reference/android/graphics/SurfaceTexture) · [Importação de AHardwareBuffer no Vulkan](https://docs.vulkan.org/refpages/latest/refpages/source/VK_ANDROID_external_memory_android_hardware_buffer.html)

Na rota lógica, `CONTROL_SETTINGS_OVERRIDE` com `ZOOM` é uma opção de latência a medir somente se o dispositivo anunciar o valor e devolver evidência por frame. O pedido mais recente continua prevalecendo, e o disparo depende de confirmação do campo aplicado. [Camera2 settings override](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_SETTINGS_OVERRIDE)

## Arquitetura escolhida para prototipagem

Começar por uma sessão lógica persistente separada por modo. Em Foto, usar prévia e JPEG confirmados e manter o processamento privado e a publicação atuais. Em Vídeo, manter o encoder na sessão durante a troca e medir áudio e EIS separadamente. Validar primeiro o campo de 0,6× contra a câmera direta e o recorte total até 30×. Se qualquer patamar perder focal, campo ou cadência, usar a rota já validada daquele modo.

Experimentar a sobreposição de 2+5 apenas perto da fronteira de 1×, caso a prova de movimento demonstre ganho qualificado. Experimentos com 5+6 exigem confirmar os dois fluxos e a fase óptica. A medição atual favorece a sessão lógica para a primeira implementação experimental e mantém a troca física validada como fallback do aplicativo.

## Critérios para a próxima prova

- Medir FOV a cada fronteira por resolução, proporção e estabilização. Meta inicial de erro ≤3%. Conferir prévia e JPEG com alvo distante.
- Usar alvo a 0,5 m, segundo plano a 2 m e fundo acima de 10 m. Medir P95 de deslocamento da região principal. Meta inicial ≤1% da diagonal. Com baixa confiança ou oclusão, apresentar um fluxo real por quadro para evitar contornos duplicados.
- Medir fase de exposição com LED codificado ou objeto em movimento. Para um alvo a 500 px/s e erro permitido de 2 px, o desvio máximo é 4 ms. Timestamps de um mesmo pedido podem coincidir independentemente da fase óptica. [Calibração e tipo de sincronismo](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics#LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE)
- Comparar exposição em cinza linear, cor em carta SDR e estabilidade do foco com o mesmo alvo. Metas iniciais ≤0,25 EV de degrau e ΔE00 mediano ≤3. O ΔE76 usado na prova de conceito tem outra fórmula. [CIEDE2000](https://www.cie.co.at/publications/colorimetry-part-6-ciede2000-colour-difference-formula-1)
- Medir P95/P99 de latência, cadência e quadros descartados ou duplicados, mais memória e temperatura. O orçamento total por quadro é 33,3 ms a 30 fps e 16,7 ms a 60 fps. Validar FHD e 4K separadamente, com áudio e estabilização quando disponíveis.
