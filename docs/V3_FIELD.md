# Validação da câmera e da restauração V3

## Reprodução

Na gravação de tela de 41,64 s do usuário, a câmera Samsung e o HorizonCam apontam para uma torre atrás de persianas. O HorizonCam exibe erro 2 na abertura, entra em “Abrindo câmera…” ao trocar para 3× e alterna o foco entre planos no zoom alto. A gravação documenta o visor. Os arquivos JPEG e a origem física de cada frame exigem medição separada. As imagens e os vídeos do aparelho ficam fora do repositório.

No SM-S9210 TGY conectado, o ADB informou Android 17, API 37. O inventário do HAL inclui câmera traseira lógica 0 com faixa 0,6–10× e IDs físicos 2, 5 e 6. A focal de 5,4 mm e a largura de 8,16 mm da principal, junto de 7,0 mm e 3,648 mm da tele, dão uma razão nominal de campos de aproximadamente 2,90×. Calibração do campo efetivo após correção óptica, recorte da sessão e aspect ratio exige alvo fixo.

## Comportamento entregue para ensaio

* O zoom confirmado e o alvo de uma transição são estados distintos. A confirmação do controle CameraX libera o disparador. Para saída física com zoom fixo, visor e JPEG aplicam o recorte residual uma vez. O limiar nominal de 3× seleciona um candidato tele e o recorte usa sua escala própria.
* Cada sessão registra o tempo da primeira prévia convencional, o zoom confirmado, os IDs solicitados/relatados, o crop e a base de medição de AF/AE. A recuperação de câmera ocupada tem espera e número limitado de tentativas. O foco ao toque reduz a área de medição no recorte digital. O controle de foco distante depende de suporte a foco manual.
* Ao ativar Horizon Lock, o app tenta a ultrawide física. A rejeição da combinação leva à tentativa com a câmera lógica. O sensor registra a cadência realmente entregue e o GL registra as correspondências de timestamps. O nivelamento, o recorte de rotação e a geometria de saída usam a implementação já aprovada. [Detalhes da amostragem](HORIZON_TIMING.md).
* Foto, Noturno e fusão de detalhe guardam primeiro uma referência privada. A fila publica uma única linha do MediaStore depois de concluir o JPEG e o EXIF. Uma recuperação procura a reserva anterior da mesma captura. Abrir o laboratório em Mais e solicitar uma exportação é uma ação independente.

## Motor local

O pipeline antigo usava blocos de 50×50 e limitava a alteração de luminância em toda a foto. O novo mede sinal nativo em uma grade de regiões. QuickSRNet-small atende capturas com degradação leve. O modelo cego general-x4v3 atende degradação maior. Ambos recebem blocos de 128×128 com bordas sobrepostas. A composição usa os interiores válidos de cada bloco e mantém o detalhe medido no enquadramento original. Os modelos pré-treinados foram convertidos localmente. O app compara dois ajustes de CPU e duas configurações do delegate GPU contra uma referência de CPU. O resultado por modelo depende do dispositivo e da versão do sistema.

| Ensaio no S24 | Resultado observado |
|---|---|
| Disparo real 1× no primeiro build da AI 2 | 3060×4080, QuickSRNet em CPU de quatro threads, 80 blocos. 7,44 s na etapa neural, 8,71 s na restauração, 10,43 s do disparo à galeria. PSS ao fim da restauração de 350.506 KiB. |
| Mesmo tamanho, replay V3 de outra captura real 1× | 3060×4080, 80 blocos em QuickSRNet com delegate GPU de precisão estrita. 7,36 s na restauração. O replay exclui a captura e a publicação. |
| Duas regiões de referência em tamanho nativo, dez execuções físicas V3 | QuickSRNet em 1× alterou o PSNR de 26,763 para 26,756 dB no rosto e de 28,473 para 28,470 dB no detalhe. Real-ESRGAN no perfil de zoom alto alcançou aproximadamente 27,39 e 29,10 dB. O zoom foi um parâmetro do ensaio, com entrada idêntica. |

As medidas acima precedem o ajuste de proteção de textura clara. Elas não estabelecem ganho de qualidade universal, latência de disparo da versão final ou equivalência a outro fabricante. Os ensaios do emulador verificam geração de mídia, geometria e EXIF. A comparação óptica de 30× e o tremor durante uma caminhada exigem o aparelho em posição controlada.

## Fontes e ensaios reproduzíveis

* Conversão, licenças e comparação dos modelos em imagens de referência. [QuickSRNet](../tools/model/QUICKSR_FAST.md) · [Real-ESRGAN](../tools/model/README.md) · [paridade da conversão FP16](../tools/model/context-validation.json).
* O ensaio de transferência roda com as dependências do laboratório e produz recortes a 100%, PSNR, SSIM e LPIPS. Use `python tools/model/benchmark_adaptive_transfer.py --out <diretório-local> --large`. Os resultados de fotografias sem referência medem conservação da entrada.
* Critérios para selecionar uma saída física e interpretar dados de captura. [Android Camera2](https://developer.android.com/media/camera/camera2/multi-camera).
* Referências de fotografia computacional e cor que orientaram o projeto. [OPPO Find X9 Pro](https://www.oppo.com/en/newsroom/stories/oppo-find-x9-pro-camera/) · [vivo X300 Pro](https://www.vivo.com/en/products/x300-pro/) · [Huawei Mate X6](https://consumer.huawei.com/en/phones/mate-x6/).
