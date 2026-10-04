# HorizonCam · S24

Camera Android nativa independente, dedicada exclusivamente à gravação de vídeo, inspirada no modo Vídeo/Horizontal Lock do Galaxy S26 Ultra. Destino inicial: **Galaxy S24 SM-S9210, Snapdragon 8 Gen 3, Android 16, TGY**.

**Status: release candidate para validação no aparelho.** Compilar e passar nos testes JVM não prova que o resultado visual está idêntico ao S26. Não há S24 conectado ao runner GitHub Actions.

## O que é implementado (de verdade)

- CameraX Camera2 HAL e `VideoCapture<Recorder>` salvando MP4 no MediaStore, áudio opcional.
- OpenGL ES + `CameraEffect(PREVIEW | VIDEO_CAPTURE)`: UMA única transformação geométrica canônica para o frame, aplicada a prévia e codificação.
- Sensores no próprio thread, giroscópio + vetor de rotação/gravid. com filtro complementar; histórico de timestamp e interpolação em cada frame.
- Horizontal Lock relativo à gravidade, com recorte constante para movimentos de até 360° (dentro dos limites físicos).
- Super Steady HAL somente quando a CameraX reporta estabilização simultânea de preview e gravação e aceita a combinação FHD/30. Fallback explicitado quando indisponível.
- Presets FHD/UHD e FPS condicionados a Camera2/CameraX, zoom e descoberta de ultrawide **somente se CameraX de fato a disponibilizar**.
- Pinça para zoom, foco AE/AF por toque, exposição, lanterna, pausa/retomada, cronômetro, áudio, grade e miniatura/reprodução do vídeo.
- A orientação dos controles é independente da matriz de correção dos pixels.
- Instrumentação de erros GPU, contagem de outputs/fps, verificação pós-captura do arquivo e suas dimensões/metadados.

## Como instalar

1. Entre em **Actions → Build HorizonCam APK** e escolha a execução mais recente *verde*.
2. Em **Artifacts**, baixe `HorizonCam-S24-debug`, extraia e instale `app-debug.apk` no S24 (autorize instalação de fonte externa).
3. Conceda câmera e microfone. Inicie o teste em **FHD/30**, com a câmera traseira.
4. O modo **360°** usa uma saída de vídeo paisagem contínua, sem recalibrar ao apertar REC. A UI pode mudar para retrato; isso NÃO deve girar o vídeo codificado.
5. Segure o indicador **H LOCK** para exibir diagnóstico (P/V, contagem de frames, EIS e latência aproximada).

## Compilar no Android Studio

- JDK 17 e Android SDK/Build Tools 36.
- Android Gradle Plugin 9.4 e Gradle 9.6.
- Abra a pasta contendo `settings.gradle.kts`, sincronize e execute `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Não depende de root, Shizuku, permissões de sistema nem modificações do aplicativo Samsung.
- A chave *debug* do GitHub Actions é mantida no cache do pipeline para facilitar atualizações. Se a assinatura for diferente da versão instalada, o Android exige desinstalar antes.

## Documentação

- [Pesquisa factual vs inferida do S26](docs/RESEARCH_S26.md)
- [Arquitetura e matemática de rotação/crop](docs/ARCHITECTURE.md)
- [Plano de testes em hardware](docs/TEST_MATRIX.md)
- [Problemas anteriores e medidas corretivas](docs/BUG_AUDIT.md)
- [Limitações técnicas](docs/KNOWN_LIMITATIONS.md)
- [Assinatura de release](docs/RELEASE_SIGNING.md)

## Aviso sobre fidelidade

A Samsung utiliza Super Steady proprietário e margem óptica. As APIs públicas podem não expor todas as lentes físicas ou os mesmos algoritmos ISP/OIS/EIS. Portanto, **é uma implementação própria inspirada no comportamento**, não uma portabilidade do APK ou código proprietário da Samsung.
