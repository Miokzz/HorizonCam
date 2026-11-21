# Limitações de captura

- **S24 TGY.** A lista de câmeras, combinações de FPS e disponibilidade da teleobjetiva dependem do que o firmware expõe a apps de terceiros. A câmera padrão pode trocar de sensor conforme luz e distância. O diagnóstico diferencia ID solicitado de ID ativo relatado pelo HAL.
- **Horizon Lock.** A estabilização compensa o giro em torno do eixo óptico. A margem para um giro completo custa aproximadamente 2,07× em um buffer 16:9. O deslocamento vertical ao caminhar, o movimento dos objetos, o desfoque de exposição e o rolling shutter podem permanecer. Apontar a câmera para o chão/teto reduz a referência de gravidade. O quadro completo em paisagem fica centralizado ao segurar o telefone em retrato.
- **Tempo e sensores.** O diagnóstico indica quando há interpolação confiável ou uso da última leitura. O atraso ótico precisa ser medido no aparelho.
- **Vídeo.** O caminho de gravação é SDR. O app pede EIS no modo de estabilização nativa quando preview e encoder declaram compatibilidade. O modo 360° utiliza correção própria por GPU. A seleção de FPS é uma solicitação, e o arquivo gravado permite medir a taxa efetiva.
- **Imagem.** Uma resolução anunciada para o sensor pode estar indisponível na sessão CameraX escolhida. A ampliação digital não certifica uso da tele.
- **Distribuição.** Os arquivos publicados pelo workflow de testes usam assinatura de debug. A validação do S24 exige exemplares de mídia e diagnóstico do firmware usado.
