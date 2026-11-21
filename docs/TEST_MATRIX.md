# Validação

## Testes automatizados

O workflow de build executa `:app:testDebugUnitTest`, `:app:lintDebug` e `:app:assembleDebug`. Os testes de geometria projetam uma direção mundial conhecida através da montagem do sensor, do buffer intermediário, do shader e da rotação estática de saída. Verificam o nível durante o giro, a distância entre pixels de um círculo e a cobertura dos cantos em buffers com várias proporções. Os testes de lentes verificam roteamento e evidência temporal de IDs físicos.

## Galaxy S24 SM-S9210

| Procedimento | Resultado esperado | Situação |
| --- | --- | --- |
| Abrir Foto sem microfone, focar, compensar exposição e disparar nas duas orientações | JPEG legível na galeria com o enquadramento e a orientação da composição | Pendente |
| Comparar 0,6×, 1× e 3× em luz forte/fraca; pinçar; solicitar lentes individuais | Zoom aplicado, ID efetivamente relatado ou ausência explícita, recuperação de configuração recusada | Pendente |
| Vídeo · 360° · FHD/30. Filmar uma prateleira e um círculo com o telefone em pé. Girar lentamente nos dois sentidos até completar 360°. Repetir iniciando em ambas as paisagens | Linha horizontal sem inversão ao cruzar 180°, círculo continua circular na prévia e ao reproduzir o MP4 | Pendente |
| Pausar/retomar, girar a interface, voltar à galeria e comparar MP4 e prévia | Duração, áudio, pixels e metadados coerentes | Pendente |
| FHD/60 e 4K/30 se anunciados; sessão de 10 minutos | Medir taxa de quadros, atrasos, temperatura e integridade da mídia | Pendente |
| Alternar Foto/Vídeo durante 20 ciclos, sair e voltar ao app | Câmera e GPU recuperam sem travamento ou mídias incompletas | Pendente |

Toque no indicador de estado para consultar a sessão. Para reunir evidência física, salve um MP4 original e registre o trecho correspondente do Logcat com `adb logcat -s HorizonCam HorizonCamGL HorizonCamMedia`. A rotação do contêiner é parte da orientação de reprodução. Para verificar um arquivo use `ffprobe -v error -show_streams -show_format -of json video.mp4`.

## Alvo de geometria

Em um ambiente Python separado, instale as dependências de `tools/calibration/requirements.txt`. O script `tools/calibration/horizon_chart.py` gera o alvo e mede o giro, a inversão e a proporção do círculo em um JPEG ou MP4. A perspectiva pode imitar deformação. Use o alvo nivelado e perpendicular ao eixo óptico e confirme a montagem antes de interpretar o resultado. Mire em todos os marcadores durante a volta. Também é preciso observar ou registrar o giro físico do telefone. Os testes sintéticos estão em `tools/calibration/test_horizon_chart.py`.
