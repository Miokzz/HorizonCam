# Auditoria das versões anteriores

Evidências da conversa: gravações `55971.mp4`, `55972.mp4`, `56037.mp4`, `56038.mp4` e capturas de HUD foram analisadas durante os ciclos de depuração. O vídeo exportado anterior foi encontrado como retrato em um teste; isso não permite inferir o resultado da nova implementação sem hardware.

| Falha antiga | Origem detectada | Correção implementada |
|---|---|---|
| Prévia girando, gravação corrigida | Uma saída `SurfaceOutput` substituía a outra ou recebia transform diferente | Manter todas as surfaces e uma transformação canônica por frame |
| Atualizar app sem substituir | Assinaturas debug diferentes por runner | Cache de keystore debug CI |
| Crop oscilando | Fator depende do ângulo | Crop fixo analítico para todas as orientações |
| Recalibração na REC | Reset de referência | Referência absoluta da gravidade; REC não recalibra |
| Salto em ±180° | Ângulos envolvidos sem unwrap | Fusão e histórico *unwrapped* |
| Saltos de orientação | Rebind da câmera e mudanças de `targetRotation` por HUD | H LOCK mantém saída física constante; HUD é independente |
| Arquivo vertical inesperado | Mudança dinâmica `targetRotation` em VideoCapture | Orientação estável no modo de lock; confirmação via MediaMetadataRetriever |
| Prévia comprimida em retângulo | `TextureView` com ajuste contain em retrato | Center crop de apresentação sem rotação adicional |
| Visor/encoder em momentos diferentes | Última leitura de sensor no momento de cada desenho | Interpolação por timestamp e única `FrameGeometry` |
| App travando em Surface perdida | Exceção GL escapava do looper | Isolamento de falha por saída e diagnóstico |
| Controles de qualidade fictícios | Exposição de 60/UHD mesmo sem consulta | Capabilities Camera2/CameraX, desabilitar combos não demonstrados |
| 0,6× inexistente | Assume câmera física acessível | Descoberta apenas entre CameraInfos efetivamente publicadas |

O teste após estas correções continua sendo um trabalho de validação em dispositivo, não algo considerado automaticamente solucionado.
