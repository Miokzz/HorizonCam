# Pipeline de captura

`MainActivity` controla permissões, modo, estado da câmera e operações pendentes. O seletor de modo abre uma sessão com apenas os casos de uso necessários. Durante a captura JPEG ou a finalização MP4, o app bloqueia alterações que reabririam a câmera.

| Componente | Responsabilidade |
| --- | --- |
| CameraChromeLayout | Reposiciona prévia, disparador e modos ao mudar o tamanho da janela. |
| RearLensManager / ZoomRouter | Enumeram IDs públicos, calculam o campo de visão relativo, encaminham o zoom e identificam opções rejeitadas. |
| CaptureEvidence | Associa o ID ativo informado pelo HAL à sessão e ao frame; descarta dados antigos. |
| PhotoEngine | PreviewView e ImageCapture em 4:3, JPEG 95, MediaStore e rotação EXIF pela CameraX. |
| VideoEngine | Preview e Recorder com viewport comum, MP4, áudio opcional e estabilização conforme a configuração. |
| CaptureLibrary | URI da última mídia, leitura de dimensões, miniaturas e abertura da galeria. |

## Geometria do Horizon Lock

O alvo CameraX do lock é fixo em `Surface.ROTATION_90`. A rotação da janela reposiciona o HUD. As dimensões efetivas de cada superfície determinam a transformação dos pixels. O shader usa a matriz de textura pública de `SurfaceOutput.updateTransformMatrix` para incorporar os ajustes estáticos da câmera. A orientação dinâmica é amostrada uma vez por frame e compartilhada pelas saídas.

No dispositivo com orientação natural de retrato, o sensor de atitude fornece o vetor vertical do mundo nos eixos do aparelho. A conversão para a direção da gravidade usa a mesma convenção para o vetor de rotação, a gravidade e o acelerômetro. O app calcula o ângulo por `atan2(downX, -downY)`. O aparelho girado 90° em sentido anti-horário tem roll de −90°; a correção no quadro de destino é `wrap(-90° - roll)`. O disparo e a rotação da interface preservam essa referência.

Para um buffer de largura W, altura H e aspecto a=W/H, a rotação de vértices em coordenadas normalizadas equivale a uma rotação rígida no espaço de pixels. Os fatores fora da diagonal são −sin(θ)/a e a·sin(θ). Usar o aspecto de entrega no lugar das dimensões da superfície intermediária pode alongar os objetos durante o giro. Um recorte constante de 1,015·√(1+max(a,1/a)²) cobre todos os ângulos; isto reduz o campo de visão e o detalhe disponível.

O histórico interpola amostras quando os relógios são comparáveis e os dados recentes. Com base de tempo desconhecida, usa a orientação disponível e informa essa condição. Quando a lente aponta quase verticalmente, a projeção de gravidade no plano da imagem fica pequena; o diagnóstico distingue perda de referência. O diagnóstico de GPU registra tamanhos de entrada/saída, alvos e falhas.

## Fontes

- [Horizontal Lock no Galaxy S26, Samsung](https://www.samsung.com/us/support/answer/ANS10010423/)
- [Camera2 e câmeras lógicas/físicas](https://developer.android.com/media/camera/camera2/multi-camera)
- [Captura JPEG com CameraX](https://developer.android.com/media/camera/camerax/take-photo)
- [Transformações de SurfaceOutput](https://developer.android.com/reference/androidx/camera/core/SurfaceOutput)
- [Sensores de posição e vetor de rotação](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position)
