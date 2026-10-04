# Limitações reais e escolhas deliberadas

1. **Paridade com S26 Ultra**: Samsung não publica ISP/OIS/EIS proprietários, feed interno ampliado nem pipeline do Super Steady. Reprodução exata não pode ser garantida com APIs públicas.
2. **Campo de visão**: recorte analítico ~2,07× para 360° significa menor FOV e perda de resolução útil. Só uma fonte ultrawide realmente disponível e calibrada reduz o problema. A CameraX às vezes publica uma câmera lógica, mas oculta as physicalCameraIds individualmente.
3. **Ultra-wide e tele**: `CameraManager` consegue informar a existência de lentes, mas isso NÃO implica poder abrir cada uma como câmera separada. `PublicLensDiscovery` só seleciona ultrawide se o provedor CameraX a expuser como `CameraInfo` independente. A troca física durante REC é proibida quando exigiria rebind.
4. **Metadados de saída**: CameraX/Recorder controla orientação do container. O app a verifica pós-gravação; o comportamento real do firmware S24 TGY precisa ser comprovado com MP4 gravado, inclusive 180°/360°.
5. **Timestamps**: quando a HAL declara fonte de tempo UNKNOWN, sincronização precisa entre sensores e frames não é garantida. O fallback é a última amostra temporal conhecida, identificado no HUD.
6. **HDR/codificação**: pipeline Egl 8 bits SDR, sem gravação HDR10+ e sem opção HEVC forçada. O encoder Recorder seleciona os codecs/profiles disponíveis; não expor seleção falsa.
7. **EIS**: suporte depende de CameraX + HAL + lente + FPS/qualidade. STEADY só funciona se disponível, enquanto H LOCK digital funciona independente da HAL desde que o GL trabalhe.
8. **Estabilização translacional**: deslocamentos de marcha/corrida podem persistir mesmo quando roll está correto. EIS HAL compatível ajuda, mas não reproduz o motor da Samsung.
9. **Frame orientation**: uma saída 16:9 não pode preencher inteira uma UI 9:19.5 sem crop ou barras. A UI utiliza center crop para não distorcer, de forma independente do quadro efetivamente codificado.
10. **Sem teste físico remoto**: CI não verifica qualidade ótica, qualidade de microfone, câmera em background, thermal throttling ou duração longa. Usuário deve executar matriz de testes e enviar logs/MP4 para validação final.
11. **Release**: sem certificado release do proprietário, o produto fornece APK **debug assinado para testes**. Não fingir que um debug APK é release de distribuição.
