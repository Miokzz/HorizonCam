# HorizonCam 2.0

Câmera Android independente em Kotlin. O alvo de validação é o Galaxy S24 SM-S9210 (Snapdragon, firmware TGY). O app identifica as câmeras durante a execução. Usa APIs públicas, mantém as mídias no aparelho e dispensa root.

## Fase 1 · Foto, vídeo e Horizon Lock

- **Foto** abre uma sessão CameraX dedicada, enquadra em 4:3 e salva JPEG em `Pictures/HorizonCam`. Toque para foco e compensação de exposição. A câmera escolhe uma resolução compatível com a sessão.
- **Vídeo** grava MP4 em `Movies/HorizonCam`, com pausa, retomada, cronômetro e microfone opcional. O seletor superior oferece as combinações implementadas e anunciadas pela câmera.
- **Horizon Lock 360°** trabalha em paisagem, inclusive com o telefone em pé. A prévia mostra o quadro 16:9 completo. O controle de estabilização permite voltar ao vídeo convencional. O enquadramento tem um recorte fixo para cobrir o giro.
- Os botões de **zoom** e a pinça consultam os limites da sessão. Em **Ajustes → Escolher câmera traseira** aparecem os candidatos públicos. Se uma combinação for recusada, o app tenta a câmera automática. Durante uma gravação, o zoom permanece na sessão aberta.
- Toque no indicador de estado para consultar os IDs da câmera, a saída física solicitada, o sensor ativo informado pelo HAL, a resolução negociada e o diagnóstico de estabilização. O número de zoom sozinho não comprova a origem óptica.
- A miniatura abre a última foto ou vídeo. Os modos em desenvolvimento ficam desativados.

## Fase 2 · Fotografia avançada

Em **Foto → Ajustes → Formato e resolução**, escolha JPEG rápido, a resolução adicional de JPEG anunciada pela câmera, ou DNG quando disponível. O DNG preserva a área do sensor RAW. O visor 4:3 e o arquivo bruto podem ter enquadramentos diferentes. A disponibilidade depende da combinação de lente e resolução.

**Pro → Ajustes → Controles manuais** oferece os controles aceitos pela sessão. ISO e obturador são definidos em conjunto; foco usa dioptrias e o balanço de branco usa os presets anunciados pelo HAL. **Foto → Ajustes → Verificar HDR da câmera** consulta a extensão HDR pública do fabricante. Quando disponível, ela usa JPEG automático e pode levar mais tempo para disparar.

## Foto automática · 0,6× a 30×

Escolha o enquadramento com a pinça ou toque no indicador de zoom para escolher em décimos. Em **Foto / JPEG**, o disparador guarda a captura em armazenamento privado, libera a câmera e enfileira a revelação local com Real-ESRGAN general-x4v3. A galeria recebe um JPEG. A interface informa quando a inferência é interrompida e o app precisa preservar o JPEG da câmera. A câmera pública determina a menor ampliação disponível. Acima de seu limite o visor e o JPEG aplicam o mesmo recorte central. O metadado do JPEG identifica a estimativa neural.

O processamento trabalha com até 12,6 MP e um modelo que estima textura. Fotos normais preservam uma fração maior do sinal medido; zoom alto e ISO elevado recebem maior contribuição do modelo. Quando há recorte adicional por software, a reconstrução pode usar a saída de 2× ou 4× dentro do limite de memória. Também é possível escolher explicitamente JPEG do sensor ou DNG em **Pro**. Consulte a [origem dos pesos, conversão e métricas](tools/model/README.md).

## Fase 4 · Noturno e multiframe

O modo **Noturno** captura cinco quadros reais e tenta uma exposição mais curta para proteger altas luzes. A fusão estima movimento, alinha os quadros e rejeita regiões incompatíveis. **Foto → Ajustes → Fusão de detalhe** permite testar reconstrução multiframe 2× quando existe diversidade subpixel mensurável. A resolução de trabalho tem lado maior de até 1280 px no Noturno ou 960 px na entrada de super-resolução. Uma sequência gera uma única foto na galeria. Movimentos grandes podem levar ao salvamento de um quadro de referência. No zoom que exige recorte adicional, Foto usa a restauração automática para conservar o campo de visão.

## Laboratório opcional · Horizon AI

**Mais → Horizon AI** abre a última foto JPEG ou permite escolher uma imagem local. O motor executa no telefone o gerador ESRGAN pré-treinado em blocos sobrepostos. **Cena inteira** processa uma versão com até 640 pixels no lado maior. **Detalhe** processa até 256 × 256 pixels nativos ao redor do ponto selecionado. A saída tem quatro vezes a largura e a altura da entrada efetiva.

A tela mostra os blocos concluídos e permite cancelar ou voltar à câmera. O JPEG derivado identifica o processamento nos metadados. Após salvar, alterne entre **Original** e **Horizon AI** para comparar o mesmo enquadramento. Para testes comparativos consulte [Avaliação de zoom](docs/ZOOM_EVALUATION.md) e [modelo e licença](docs/HORIZON_AI.md).

## Fase 5 · Modos de vídeo

A lista de 30/60 fps considera a qualidade e as combinações de prévia e gravação que o CameraX expõe. Ao abrir a sessão o app consulta novamente a combinação completa, incluindo o efeito Horizon Lock ou a estabilização nativa. Quando a faixa é variável, a interface mostra o seu teto. Quando a consulta é inconclusiva a 30 fps, a câmera pode usar a cadência automática. O diagnóstico registra a qualidade selecionada, o recorte e o resultado da leitura dos timestamps do MP4.

## Fase 6 · Recursos e temperatura

Foto libera o disparador após guardar o JPEG privado. O WorkManager serializa as revelações e retoma a fila se o processo for encerrado. Ao detectar temperatura severa, o app deixa o original na fila e tenta novamente depois. Em temperatura crítica, o gravador solicita a finalização do MP4. O renderizador mantém os destinos GL e interpola a orientação sem copiar o histórico a cada frame. Os resultados da câmera dependem do aparelho, firmware, iluminação e movimento.

## Instalar e testar

1. Em [GitHub Actions](https://github.com/Miokzz/HorizonCam/actions/workflows/build-apk.yml), abra a execução correspondente ao commit que deseja testar. Baixe **HorizonCam-S24-debug**, extraia e instale `app-debug.apk`.
2. Autorize a câmera. A permissão de microfone é solicitada ao gravar com som. Use **Vídeo → 360° → Horizon Lock** e comece em FHD/30.
3. Filme uma linha de prateleira e um objeto circular. Gire lentamente de retrato para ambas as paisagens, passe por 180° e complete uma volta. Reproduza o MP4 na galeria e compare o nível e a proporção com a prévia.
4. Em boa luz, registre o ID físico indicado em 0,6×, 1× e 3×; repita em pouca luz. Um valor ausente aparece como “não informado”.

Para compilar use JDK 17+, Android SDK 36 e Gradle 9.6.0. Execute `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. O pipeline verifica a assinatura do APK e publica o código-fonte com o Gradle Wrapper. Preserve a chave de debug para instalar uma atualização sobre outra.

[Arquitetura](docs/ARCHITECTURE.md) · [Validação física](docs/TEST_MATRIX.md) · [Comparação com o S26](docs/RESEARCH_S26.md) · [Assinatura](docs/RELEASE_SIGNING.md)
