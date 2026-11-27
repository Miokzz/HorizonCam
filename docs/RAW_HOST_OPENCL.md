# Replay do revelador na CPU x64 e na RTX 5060

A revelação a partir do Bayer restaurado foi extraída do JNI para `raw_develop.h`, compartilhado entre o APK de laboratório ARM64 e a DLL de teste x64. A preparação NEON, a rede neural e o código de aquisição continuam nos respectivos módulos. O programa de host carrega o próprio kernel OpenCL do aplicativo de bancada e verifica a saída completa a cada passagem. A mesma LUT sRGB, a matriz de cor e os quatro GainMaps são reconstruídos a partir dos arquivos HRW1. Os quatro Bayer de entrada provêm do snapshot arquivado `native/gpu` do Galaxy S24.

Em 26/11/2026, a NVIDIA RTX 5060 com driver 621.01 e o Ryzen 7 5700G produziram exatamente os quatro SHA-256 RGB arquivados do S24 quando receberam o mesmo Bayer. Cada imagem tem 9.980.928 fotossítios e 29.942.784 bytes RGB. O teste inclui o quadro inteiro, as bordas e todas as fases CFA. Ele começa depois da CNN. A auditoria anterior cobre as diferenças antes desse ponto. [Auditoria da CNN e do ISP](TELE_RAW_ISP_FIDELITY.md)

| Entrada tele | CPU C++ x64, mediana de 3, ms | GPU e leitura RGB, p50 / p95 ms | GPU e leituras Bayer + RGB, p50 / p95 ms |
|---|---:|---:|---:|
| detail-a | 178,8 | 5,49 / 6,74 | 12,03 / 15,00 |
| detail-b | 172,5 | 6,19 / 7,95 | 12,45 / 15,88 |
| flat-a | 191,6 | 5,77 / 7,76 | 12,97 / 15,02 |
| flat-b | 168,4 | 5,26 / 7,65 | 11,75 / 15,95 |

O benchmark alterna a ordem em 12 repetições por variante e por entrada. Os tempos de parede começam no despacho do kernel e terminam após a última leitura. Os buffers são reutilizados. O Bayer já reside na placa ao iniciar cada cronômetro. Seu upload inicial levou de 6,07 a 7,15 ms. Compilar o programa e criar o contexto levou 765 ms. A mediana do kernel ficou em cerca de 0,55 ms. Eliminar a leitura de auditoria economizou uma cópia de 39.923.712 bytes. O custo dessa transferência numa GPU discreta tem de ser medido novamente na memória compartilhada do telefone. O replay exclui CNN, captura, codificação JPEG e MediaStore.

No Developer PowerShell x64 com MSVC e o SDK do Windows, usar Python com NumPy, Pillow, scikit-image e pyopencl. O executável Python precisa enxergar a implementação OpenCL da placa. A biblioteca não usa OpenCV nem um runtime Android. Em Linux, é possível compilar a mesma entrada com C++17, `-O2 -ffp-contract=off -fPIC -shared`, com validação do binário resultante a cargo do replay.

```powershell
cl /nologo /std:c++17 /EHsc /O2 /fp:strict /LD tools/calibration/raw_host_develop.cpp "/Fo$lab/develop.obj" "/Fe:$lab/develop.dll" /link "/IMPLIB:$lab/develop.lib"
& $py tools/calibration/offdevice_opencl.py --library "$lab/develop.dll" --fixtures "$qa/raw-device-bench/fixtures" --reference "$qa/raw-isp-opt-20261125/final/native/gpu" --gpu "RTX 5060" --repeats 12 --out "$lab/opencl-replay"
```

O arquivo `replay.json` registra identificação do driver, hashes dos buffers, bytes transferidos, duração de eventos e tempos de parede de cada repetição. O artefato desta rodada está em `../s24-qa/offdevice-lab/opencl-tested`.
