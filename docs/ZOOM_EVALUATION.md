# Como comparar o zoom da Horizon AI

A referência de produto é o Pro Zoom do Pixel 10 Pro, anunciado pelo Google até 100×. O fator oferecido por cada câmera do S24 vem das APIs públicas. O campo de visão da captura, o número de pixels enviados ao modelo e o fator da super-resolução são medidas distintas.

## Galaxy S24 TGY · troca de lentes em 22/11/2026

No SM-S9210 via ADB Wi-Fi, o ensaio incluiu toques rápidos nos presets e um arraste no controle contínuo. Dos 549 quadros gravados em 1080 × 2340, nenhum teve o centro da prévia quase preto. A prévia voltou a ficar ao vivo em 0,65–1,26 s nas trocas individuais observadas e em 1,90 s quando o arraste atravessou mais de uma lente. A leitura do último quadro levou 30–59 ms por troca. O app mantém esse quadro durante a abertura e usa uma transição de 90 ms após confirmar a nova prévia.

A câmera independente `2` entregou o campo da ultrawide em 0,6×. A opção de abrir o mesmo sensor como `0/2` reportou um recorte de 1,397× no HAL e um campo equivalente a aproximadamente 0,83×. A rota automática preferiu a câmera independente. O código rejeita uma saída física quando o campo já vem mais fechado do que o solicitado, pois um recorte adicional não recupera o campo de visão.

### Resposta do controle e substituição de sessão

No ensaio seguinte, o CameraX substituiu o `SessionConfig` de Foto. A configuração levou 13–35 ms. No mesmo padrão de arraste rápido, houve uma troca física após a última movimentação, e o tempo entre guardar o quadro e voltar à prévia confirmada caiu de 1,90 para 0,83 s. A ultrawide independente levou até 1,34 s nessa rodada. A gravação de 602 quadros teve zero centros quase pretos. A tele foi verificada novamente no disparador após a troca, com focal de 7,0 mm no JPEG. [Substituição de SessionConfig](https://developer.android.com/reference/androidx/camera/lifecycle/ProcessCameraProvider)

O slider usa décimos. Passagens lentas próximas a um inteiro mantêm o valor por até 500 ms, e um movimento mais amplo libera a retenção. A prévia acompanha o arraste usando pixels ao vivo da lente disponível. Ao pedir um quadro mais aberto do que ela fornece, a ampliação aguarda a lente mais ampla. O disparo aguarda a confirmação da nova sessão. Na gravação do arraste de 3× a 7×, a escala medida no visor passou por 1,20×, 1,33×, 1,63×, 1,90× e 2,23× antes de chegar à escala final de 2,33× em relação ao início.

### Escala e enquadramento na mesma cena

Ensaio em retrato pelo disparador normal, com o APK de campo de SHA-256 `a0c51eb8c580d93b2b8a628a71245ead8d6095a4a394af5d06dbfd6c87ffc175`. O resíduo é o recorte digital adicional reportado pelo app. A escala é a razão entre os JPEGs de dois níveis consecutivos, estimada por correspondências de imagem. A última coluna conta as correspondências geométricas consistentes entre prévia e JPEG no mesmo nível.

| Zoom | Saída da câmera | Focal EXIF | Resíduo | JPEG na orientação exibida | Escala ante o nível anterior | Prévia/JPEG |
| --- | --- | --- | --- | --- | --- | --- |
| 0,6× | 2 | 2,2 mm | 1,000 | 3000 × 4000 | — | 1260 |
| 1× | 0/5 | 5,4 mm | 1,000 | 3060 × 4080 | 1,647× | 927 |
| 3× | 0/6 | 7,0 mm | 1,035 | 2644 × 3526 | 2,946× | 969 |
| 5× | 0/6 | 7,0 mm | 1,724 | 1587 × 2116 | 1,668× | 723 |
| 10× | 0/6 | 7,0 mm | 3,449 | 793 × 1058 | 2,003× | 190 |
| 20× | 0/6 | 7,0 mm | 6,897 | 397 × 529 | 1,997× | 57 |
| 30× | 0/6 | 7,0 mm | 10,346 | 264 × 353 | 1,504× | 50 |

A ampliação acumulada de 1× a 30×, multiplicando as razões adjacentes, foi 29,56×. O maior desvio observado entre os cantos correspondentes da prévia e do JPEG foi 0,58% da dimensão do quadro. A ultrawide usou foco fixo. Nos outros seis níveis, a câmera confirmou o foco por toque. A exposição ficou entre 19,9 e 20,0 ms, com ISO 72–151 na cena ensaiada.

O log registrou sete publicações e a consulta direta ao MediaStore confirmou sete JPEGs distintos com `IS_PENDING=0`. Uma checagem agregada do script por listagem de arquivos retornou erro após a última captura. A conferência das sete entradas foi refeita no MediaStore e nos logs do worker. Os JPEGs do ensaio seguiram pelo fallback da câmera após a seleção do backend neural falhar. Em 30×, a resolução observada foi a do recorte efetivo, 264 × 353.

## Troca de lentes e comandos concorrentes em 22/11/2026

O modo Foto JPEG mantém uma única intenção de zoom e confirma o recorte na sessão vigente. Durante um arraste, a prévia pode recortar o fluxo atual e o disparo aguarda a confirmação da lente e do recorte solicitados. Na desmontagem da sessão, a interface identifica o último quadro retido. No SM-S9210, o modo automático usa a saída lógica 0 no intervalo de 1× a 2,9×, com o sensor principal 5 confirmado pelo HAL, a ultrawide direta 2 abaixo de 1× e a saída física 0/6 a partir de 3×.

- **Build e testes.** APK de campo, APK de teste, lint e 107 testes JVM passaram. Três testes JVM exercitam confirmação obsoleta, substituição da sessão e cancelamento da espera do arraste.
- **Galaxy S24 por ADB Wi-Fi.** No ensaio, sete trocas de preset confirmaram os sensores 2, 5 e 6. Uma solicitação de 8,2× durante a abertura da tele foi aplicada sem confirmar o 3× intermediário. A sequência 3× → 1× → 0,6×, iniciada durante a abertura da tele, confirmou o destino de 0,6×. Quatro arrastes rápidos de 180 ms entre os extremos chegaram a 0,6× ou 30×, cada um com uma única solicitação de troca de lente.
- **Disparo e prévia.** O comando de disparo foi registrado 78 ms após a confirmação da tele. Houve um JPEG final de 2644 × 3526 com focal EXIF de 7,0 mm. Na cena estática, 241 correspondências geométricas entre prévia e JPEG deram erro mediano de reprojeção de 0,91 px. A gravação da prévia teve 1.485 quadros, com zero centros quase pretos no limiar testado.
- **Pendente no HAL.** O provedor de câmera da Samsung reiniciou duas vezes no ensaio ao sair da tele física. A recuperação mantém a última intenção e espera a câmera reaparecer também no CameraX. Os presets levaram de 0,70 a 3,50 s, e os arrastes de 0,75 a 2,95 s, medidos do envio do comando ADB à confirmação. O maior atraso ainda é perceptível.
- **Isolamento em Camera2.** Quatro ciclos de prévia `0/6` → `0` fecharam o `CameraDevice` antes de soltar as superfícies e aguardaram `onClosed`. Os metadados confirmaram 7,0 mm na tele e 5,4 mm na principal. O fechamento da tele levou 157–167 ms e o da principal, 274–339 ms. O teste de instrumentação passou em 7,913 s. Não houve reinício do HAL no intervalo do teste. O ensaio usou prévia e superfície JPEG configurada, sem disparar fotos.
- **Retomada.** Integrar o fechamento direto em uma sessão separada para Foto JPEG na tele `0/6` e conservar CameraX nas demais rotas. Repetir no S24 as trocas 3× → 1× e 3× → 0,6× com captura pelo fluxo normal antes de avançar para EIS ou multicâmera simultânea.

### Implementação candidata para a tele

A rota automática Foto JPEG 0/6 do SM-S9210 usa uma sessão Camera2 com prévia 4:3. As demais rotas continuam no CameraX. O handoff espera a disponibilidade do dispositivo ao sair do CameraX e `onClosed` ao sair do Camera2. O JPEG privado usa a mesma fila existente de recorte, restauração e publicação. A câmera só libera captura após quadro real e focal física confirmados. Se o caminho nativo falhar, a saída física anterior continua disponível pelo CameraX.

Build de campo, lint, 107 testes JVM e três testes instrumentados passaram. A instrumentação repetiu quatro ciclos de fechamento Camera2 e validou publicação única, EXIF e limpeza do arquivo parcial por falha. A tela bloqueada do S24 interrompeu o ensaio de interface. Restam alternância, arrastes, reversão, disparos consecutivos, retomada e geometria prévia/JPEG na integração. O ensaio isolado permanece distinto da validação do app.

## Protocolo de comparação física

1. Use o S24 em um suporte e um motivo plano com linhas finas, letras aleatórias legíveis a curta distância, amostras de cor e textura de tecido. Inclua uma segunda cena com movimento. Repita em luz natural e pouca luz.
2. Registre o original JPEG, o zoom relativo e o ID físico reportado em cada tentativa. Faça três disparos por combinação. Inclua 1×, 3×, um zoom intermediário e o máximo realmente aceito. Uma opção sem ID confirmado fica identificada como tal.
3. Para o motivo plano, fotografe também uma referência próxima, de frente e bem iluminada. Alinhe os recortes antes de medir. Para cenas com profundidade, mudanças de perspectiva limitam a comparação pixel a pixel.
4. Compare o mesmo campo de visão na mesma resolução de exibição. Inclua original ampliado por bicúbica, Horizon AI e a referência. Examine contornos duplicados, halos, falsos caracteres, cor e textura. Use a legibilidade das letras conhecidas para identificar invenções do modelo.
5. Meça tempo por bloco, tempo total, memória, aquecimento e cancelamento com o aplicativo em uso. Registre modelo do telefone, firmware e resolução de entrada e saída. Somente uma comparação adicional com imagens equivalentes do Pixel permite atribuir paridade entre os aparelhos.

A tela **Mais → Horizon AI** conserva o JPEG original e permite alternar entre original e processado. **Cena inteira** redimensiona a entrada com limite explícito; **Detalhe** usa um recorte de pixels nativos escolhido no visor. A ampliação 4× descreve as dimensões de saída do modelo.

[Pro Zoom do Pixel 10 Pro, Google](https://store.google.com/us/magazine/pixel-phone-comparison?hl=en-US)
