# Arquitetura: orientação, GPU e gravação

## Fontes e pipelines

- `camera/CameraCapabilities`: enumera capacidades CameraX/Camera2 visíveis sem permissões privilegiadas. Não confunde `physicalCameraIds` com câmeras independentes publicadas pela HAL.
- `camera/PublicLensDiscovery`: procura ultrawide apenas entre as `availableCameraInfos` que a CameraX permite selecionar. Não garante tele ou 0,6× em firmware TGY.
- `sensors/RollFusion`: complementa velocidade angular do giroscópio com gravidade extraída de `TYPE_GAME_ROTATION_VECTOR`/rotation vector ou gravidade/acelerômetro. Ângulo desenrolado evita salto na fronteira ±180°; se a projeção gravitacional fica degenerada, continua previsão pela velocidade angular.
- `sensors/OrientationHistory`: amostras monotônicas dos `SensorEvent.timestamp`. Interpolação temporal com tratamento de timebase desconhecida, sem depender da frequência de atualização do HUD.
- `stabilization/AngleMath` + `HorizonState`: gera uma **FrameGeometry** (ângulo, crop constante, confiança no timestamp) única para cada timestamp.
- `HorizonSurfaceProcessor`: recebe a textura OES da câmera e realiza uma única transformação canônica; desenha os mesmos frames processados nas surfaces de Preview e VideoCapture, com as matrizes estáticas de `SurfaceOutput.updateTransformMatrix` específicas de cada surface.
- `EglCore` / `OesRenderer`: gerência GL/EGL e apresentação de quadros; `Recorder` cuida de codificar vídeo e muxar com áudio; saída é `MediaStore`.
- `HorizonPreviewTextureView`: aplica somente **center crop de apresentação** para preencher a tela de proporções diferentes, sem reaplicar giro sensor/display. A área da tela em retrato não consegue apresentar o 16:9 completo sem barras ou corte; preferimos corte apenas visual, não alteramos o vídeo.

## Espaços de coordenadas

1. **Dispositivo/sensores**: eixos Android X à direita, Y para cima, Z para fora da tela; roll gravitacional com `atan2(gx, -gy)`.
2. **Câmera física**: sensor HAL com `SENSOR_ORIENTATION`, `LENS_FACING` e possibilidade de crop/espelhamento.
3. **CameraX buffer**: textura externa OES, `SurfaceTexture.getTransformMatrix`.
4. **Frame normalizado**: matriz estática composta por `SurfaceOutput.updateTransformMatrix`, mais matriz de roll/crop calculada em `HorizonState`.
5. **Encoder**: `VideoCapture` com `targetRotation` estável em Horizontal Lock, metadata conferida no MP4 final.
6. **HUD**: views/layout do Android, sem entrar no buffer gravado.

## Matriz

Para um canvas com aspecto `a = largura / altura`, escala `z` e correção `θ`, a transformação dos vértices normalizados é:

```
[ z*cos θ       -z*sin θ/a ]
[ z*a*sin θ      z*cos θ   ]
```

O requisito exato para cobrir todos os cantos sem área preta durante rotação `θ` é

```
z >= max(|cos θ| + a*|sin θ|, |cos θ| + |sin θ|/a)
```

Para todas as rotações e `a >= 1`, o mínimo seguro fixo é `sqrt(1+a²)`. Em 16:9 o máximo geométrico equivale a ~2,04×; com margem 1,5%, ~2,07×. **Isso reduz detalhe e FOV**; não equivale ao pipeline óptico do S26.

## Timestamps

- `SensorEvent.timestamp` e um dispositivo Camera2 com `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` usam a base de tempo `SystemClock.elapsedRealtimeNanos()`.
- Se Camera2 reportar `UNKNOWN` (ou se o timestamp for muito distante da série sensorial), o motor marca `LATEST` e usa última amostra, sem afirmar sincronização precisa.
- `SurfaceTexture.timestamp` é consultado antes de gerar FrameGeometry. O GL usa a mesma instância geométrica para todas as saídas do frame, evitando deriva entre prévia e vídeo por leituras em instantes distintos.

## Estabilização

- **OFF**: sem rotação/crop software nem EIS.
- **STEADY**: solicita estabilização HAL em preview + video **somente se suportada**; usa identidade no shader.
- **HORIZONTAL_LOCK**: compensação contínua 360° por GPU; tenta adicionar EIS nativo apenas em combinações compatíveis. Se falhar, permanece com roll lock em GPU e reporta limitação. **EIS nativo não equivale ao Super Steady proprietário.**

## Ciclo de vida e falhas

- Sensores num HandlerThread; GL em HandlerThread; UI no main.
- Entrada OES e cada superfície de saída só são criadas/fechadas em GL. Exceções de uma saída encerram aquela surface e são registradas, sem matar imediatamente o restante do processamento.
- `MediaStoreOutputOptions` salva em `Movies/HorizonCam`, incluindo áudio se autorizado. `Finalize` gera URI, carrega miniatura e confere largura/altura/rotation por `MediaMetadataRetriever`.

## Fontes técnicas

- https://developer.android.com/reference/androidx/camera/core/SurfaceOutput
- https://developer.android.com/reference/androidx/camera/core/SurfaceProcessor
- https://developer.android.com/reference/android/hardware/camera2/CameraMetadata
- https://developer.android.com/media/camera/camerax/video-capture
