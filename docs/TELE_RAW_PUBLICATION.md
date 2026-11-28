# ISP RAW até publicação no laboratório

No SM-S9210, a bancada de 26/11/2026 abriu o mesmo modelo FP32, carregou quatro fixtures reais da tele e mediu dez iterações aquecidas da primeira imagem de 9.980.928 pixels. Cada iteração converteu RGB em Bitmap, codificou o JPEG com qualidade 96, publicou uma linha de MediaStore, verificou sua visibilidade e removeu somente essa linha de teste. A captura pelo sensor e a carga da fixture antecedem o cronômetro.

| GPU estrita | ISP p50 / p95 ms | ISP até galeria p50 / p95 ms | Maior PSS amostrado KiB |
|---|---:|---:|---:|
| C++/NEON + delegate GPU | 1006 / 1067 | 3066 / 3117 | 272985 |
| OpenCL NHWC, Bayer + RGB | 280 / 324 | 2358 / 2398 | 464041 |
| OpenCL NHWC, somente RGB | 279 / 290 | 2345 / 2357 | 383281 |
| Somente RGB, ordem invertida | 275 / 292 | 2350 / 2367 | 383063 |
| Bayer + RGB, ordem invertida | 280 / 311 | 2349 / 2378 | 461022 |

As medianas de publicação das duas variantes residentes se sobrepõem ao inverter a ordem. Suprimir a leitura de auditoria de 39.923.712 bytes por imagem reduziu o PSS nas duas séries. Na primeira série somente RGB, conversão para Bitmap ocupou 1824 ms de mediana, compressão 200 ms e publicação 39 ms. A concentração do custo na construção do Bitmap justifica uma comparação isolada desse estágio. A temperatura de bateria registrada nas séries residentes foi 34,7–35,1 °C. O C++/NEON permanece a referência numérica.

Reproduzir com `tools/calibration/raw_device_run.py collect --implementation resident --modes gpu --isp-output rgb --measure-jpeg`, depois com `--isp-output full`. Usar uma pasta nova por série e comparar com `--implementation native`. Evidência privada em `../s24-qa/photo-performance-20261126/raw-{native,full,rgb,full-order2,rgb-order2}`. O cronômetro disparo–galeria da Foto automática está em [medição de produção](PHOTO_PERFORMANCE_S24.md).
