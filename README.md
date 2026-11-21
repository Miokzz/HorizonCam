# HorizonCam 2.0

Câmera Android independente em Kotlin. O alvo de validação é o Galaxy S24 SM-S9210 (Snapdragon, firmware TGY). O app identifica as câmeras durante a execução. Usa APIs públicas, mantém as mídias no aparelho e dispensa root.

## Fase 1 · Foto, vídeo e Horizon Lock

- **Foto** abre uma sessão CameraX dedicada, enquadra em 4:3 e salva JPEG em `Pictures/HorizonCam`. Toque para foco e compensação de exposição. A câmera escolhe uma resolução compatível com a sessão.
- **Vídeo** grava MP4 em `Movies/HorizonCam`, com pausa, retomada, cronômetro e microfone opcional. O seletor superior oferece as combinações implementadas e anunciadas pela câmera.
- **Horizon Lock 360°** trabalha em paisagem, inclusive com o telefone em pé. A prévia mostra o quadro 16:9 completo. O controle de estabilização permite voltar ao vídeo convencional. O enquadramento tem um recorte fixo para cobrir o giro.
- Os botões de **zoom** e a pinça consultam os limites da sessão. Em **Ajustes → Escolher câmera traseira** aparecem os candidatos públicos. Se uma combinação for recusada, o app tenta a câmera automática. Durante uma gravação, o zoom permanece na sessão aberta.
- Toque no indicador de estado para consultar os IDs da câmera, a saída física solicitada, o sensor ativo informado pelo HAL, a resolução negociada e o diagnóstico de estabilização. O número de zoom sozinho não comprova a origem óptica.
- A miniatura abre a última foto ou vídeo. Os modos em desenvolvimento ficam desativados.

## Instalar e testar

1. Em [GitHub Actions](https://github.com/Miokzz/HorizonCam/actions/workflows/build-apk.yml), abra a execução correspondente ao commit que deseja testar. Baixe **HorizonCam-S24-debug**, extraia e instale `app-debug.apk`.
2. Autorize a câmera. A permissão de microfone é solicitada ao gravar com som. Use **Vídeo → 360° → Horizon Lock** e comece em FHD/30.
3. Filme uma linha de prateleira e um objeto circular. Gire lentamente de retrato para ambas as paisagens, passe por 180° e complete uma volta. Reproduza o MP4 na galeria e compare o nível e a proporção com a prévia.
4. Em boa luz, registre o ID físico indicado em 0,6×, 1× e 3×; repita em pouca luz. Um valor ausente aparece como “não informado”.

Para compilar use JDK 17+, Android SDK 36 e Gradle 9.6.0. Execute `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. O pipeline verifica a assinatura do APK e publica o código-fonte com o Gradle Wrapper. Preserve a chave de debug para instalar uma atualização sobre outra.

[Arquitetura](docs/ARCHITECTURE.md) · [Validação física](docs/TEST_MATRIX.md) · [Comparação com o S26](docs/RESEARCH_S26.md) · [Assinatura](docs/RELEASE_SIGNING.md)
