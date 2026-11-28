# Fusão da Foto automática em 30×

Em 26/11/2026, a teleobjetiva do SM-S9210 entregou três JPEGs por disparo, com focal de 7 mm. A Foto automática usou os mesmos três quadros, janela de busca, interpolação e restauração Real-ESRGAN nas duas versões. O caminho rápido da busca inteira evita interpolar amostras que já caem no grid. A busca fracionária reutiliza coordenadas e pesos.

| Medida no aparelho | Antes p50 / p95 (s) | Depois p50 / p95 (s) |
|---|---:|---:|
| Disparo até galeria, processo frio, 3 fotos | 10,648 / 10,748 | 6,545 / 6,569 |
| Disparo até galeria, aquecido, 8 fotos | 9,649 / 12,181 | 6,045 / 6,471 |
| Fusão, processo frio | 6,892 / 6,955 | 2,822 / 2,836 |
| Fusão, aquecida | 7,185 / 9,482 | 3,362 / 3,631 |
| Replay de fusão, entradas idênticas, 10 aquecidas | 7,019 / 7,121 | 3,313 / 3,322 |

A série usa o instante do disparo após abrir a prévia. Cada disparo gerou uma foto visível de 1056 × 1412 pixels a partir de um recorte nativo de 264 × 353. A bateria mediu 30,7–36,5 °C antes e 34,3–37,4 °C depois, com estado térmico Android 0 nas duas séries. São capturas vivas em instantes distintos. O p95 frio de três observações descreve somente essa amostra.

## Controle de qualidade com as mesmas entradas

O replay em instrumentação usa os mesmos três JPEGs privados da câmera. O SHA-256 do raster fundido foi `51712efef46a9112bb63b3f5205060448b0a039a1b33ba24c059642e2a3af790` nas duas versões. A publicação completa também gerou o mesmo SHA-256 de arquivo JPEG, `dd9fc54841da2c29c3f1fbf66627bad93c7956ab705241ef8198e49152b9a11f`. Essa igualdade inclui a codificação e os metadados desta foto. O gravador EXIF quantizou a exposição do original nas duas versões.

Para repetir, usar `photo_field_performance.py --adb <adb> --serial <serial> --cold 3 --warm 8 --out <diretório-novo>` no APK field. Para o controle de entrada, reter um disparo com `--cold 1 --warm 0 --retain-burst` e usar os métodos de replay de `PhotoFusionPerformanceTest` nas duas revisões com os mesmos arquivos. Os relatórios privados ficam em `../s24-qa/photo-performance-20261126/{production-before,production-after}`, `fusion-{baseline,optimized}.json` e `{baseline,optimized}-report.json`. A captura de DNGs pareados usa uma sessão própria de laboratório.
