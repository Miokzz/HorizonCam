# Laboratório RAW da teleobjetiva

22/11/2026 · Galaxy S24 SM-S9210 · firmware `S9210ZHU6EZJD` · base `c8d8a23`

## Aquisição pareada

`RawPhotoFieldTest` já tinha comprovado RAW_SENSOR físico em `0/6`. Na prova anterior de RAW com a sessão lógica a 3×, a focal foi de 5,4 mm. A aplicação usa um caminho CameraX separado para RAW no modo avançado. Este laboratório reutiliza `TeleRawPairFieldTest` e a sessão física com saídas PRIVATE, JPEG e RAW. O pedido de captura tem os três alvos na mesma sessão. Os dois buffers devem ter o mesmo `SENSOR_TIMESTAMP` do resultado físico, dimensões coincidentes e evidência de 7 mm. Continua disponível a tentativa sem prévia se a configuração de três saídas for recusada. O teste agora falha se ambas as tentativas fracassarem. [Evidência anterior](HORIZON_AI_V3_ARCHITECTURE.md) · [Timestamp Camera2](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#SENSOR_TIMESTAMP)

O instrumento escreve DNG por `DngCreator` com as características e o resultado físico de `6`, define a orientação do JPEG no TIFF e mantém os bytes originais do JPEG. O `manifest.json` só é publicado no cache privado depois de gravar os dois arquivos. Ele identifica as três leituras correspondentes de timestamp, a sessão e lente, frame, focal, ISO, exposição, zoom nominal, razões Camera2, crop, orientação, dimensões, tamanho e SHA-256. O coletor verifica a conclusão do teste, cada hash e cada tamanho durante a transferência para um diretório recém-criado. O caminho de Foto automática permanece com o processamento existente. [DngCreator](https://developer.android.com/reference/kotlin/android/hardware/camera2/DngCreator)

## Reprodução

Use a variante de campo `com.miokzz.horizoncam.gl.field`, o S24 desbloqueado e apoiado diante de uma cena autorizada com bordas, textura e regiões aproximadamente uniformes. Use caminhos fora do repositório para guardar os pares. Os seguintes comandos são para PowerShell, com SDK Android e ADB disponíveis. Quando a versão de campo já estiver instalada com assinatura compatível, instale apenas a instrumentação atualizada.

```powershell
.\gradlew.bat -PhorizonFieldBuild :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb devices -l

py -3.12 -m venv ../raw-lab-venv
$python = '../raw-lab-venv/Scripts/python.exe'
& $python -m pip install -r tools/calibration/requirements-tele-raw.txt
& $python -m unittest discover -s tools/calibration -v
$pair = & $python tools/calibration/capture_tele_raw.py --out ../raw-tele-lab
if ($LASTEXITCODE -ne 0) { throw 'Captura RAW/JPEG falhou.' }
& $python tools/calibration/compare_tele_raw.py $pair
```

Use `--adb` para um binário fora do PATH e `--serial` para indicar o transporte Wi-Fi listado por `adb devices -l`. A captura exporta `source.dng`, `source.jpg`, `manifest.json` e `instrumentation.txt`. A revelação grava `raw-linear-srgb.tiff`, `raw-display-srgb.png`, `jpeg-display-srgb.png` e `comparison.json` em uma pasta nova. O teste de captura guarda seus arquivos no cache privado e não publica nada na galeria.

O TIFF usa demosaico AHD, balanço de branco da captura, RGB linear com primárias sRGB e 16 bits por canal. Brilho automático, realce de nitidez, denoising e recuperação de realces ficam desativados. O PNG RAW aplica a transferência sRGB e aceita `--exposure-ev` explícito. O JPEG recebe orientação EXIF e interpretação do espaço de cor. O ganho altera apenas o PNG de exibição. Para repetir a comparação com a exposição usada abaixo, execute `compare_tele_raw.py $pair --out "$pair/comparison-plus-1.5ev" --exposure-ev 1.5`. O TIFF linear mantém os mesmos pixels nos dois casos. [API rawpy](https://letmaik.github.io/rawpy/api/rawpy.Params.html)

## Evidência no S24

Foram feitos dois disparos de uma cena interna estática com texto, sombras e objetos coloridos. Ambos configuraram prévia de 1440 × 1080 e dois buffers de 3648 × 2736. O zoom nominal da tele é 3×, o zoom digital pedido e as razões Camera2 retornadas foram 1,0, e o crop físico foi `[0, 0, 3648, 2736]`. Cada manifesto confirmou um único timestamp de sensor para seu RAW, seu JPEG e o resultado Camera2. A exposição dos dois pares foi de 29,994 ms, com focal de 7,0 mm e orientação normal nos originais. A instrumentação forneceu a prova temporal e o host conferiu os SHA-256 depois da cópia e depois da revelação. Os arquivos isolados não expõem o relógio monotônico do sensor para uma verificação independente.

| Par | ISO | Tempo até ambos os buffers | DNG | JPEG |
| --- | ---: | ---: | ---: | ---: |
| A | 703 | 293 ms | 19.989.196 bytes | 4.118.505 bytes |
| B | 709 | 291 ms | 19.989.196 bytes | 4.120.391 bytes |

O DNG 1.4 armazenou Bayer GBRG sem compressão em amostras UInt16. O branco de 1023 separa o limite declarado do sensor da largura de armazenamento. O nível preto no TIFF foi de 64,33. LibRaw e tifffile leram o mesmo mosaico nativo, amostra por amostra, nos dois arquivos. O DNG contém `AsShotNeutral`, matrizes de cor, `NoiseProfile`, quatro opcodes GainMap e um WarpRectilinear. A área ativa é 3648 × 2736 e o crop sugerido é 3632 × 2720 com origem (8, 8). A revelação manteve a área ativa. O efeito exato dos opcodes na revelação ainda precisa de verificação com uma ferramenta compatível e alvos calibrados. [Especificação DNG](https://helpx.adobe.com/camera-raw/desktop/dng-and-file-formats/digital-negative.html) · [Opcodes no LibRaw](https://www.libraw.org/node/2861)

### Comparação

O RAW a 0 EV teve luminância mediana de aproximadamente 52/255 e o JPEG de 90/255. Para aproximar as visualizações, foi medido no primeiro par um ganho mediano de 1,51 EV entre luminâncias lineares em meios-tons quase neutros. Adotou-se +1,5 EV fixo para os dois pares, sem ajuste de cor. O ganho foi aplicado só à visualização. Aproximadamente 0,01% dos pixels RAW de exibição atingiram o limite em algum canal.

As métricas foram medidas na interseção registrada em uma grade JPEG de 1280 × 960. O alinhamento usa pontos de correspondência reservados. O ruído é uma estimativa robusta de alta frequência em áreas de gradiente baixo e inclui textura e efeitos de reamostragem. As bordas foram selecionadas pelo JPEG e medidas nas mesmas posições. Os valores de cor descrevem distância entre os renderings, sem uma carta de cor de referência.

| Par | Erro mediano em pontos reservados | Luminância mediana RAW / JPEG | Contraste P95−P05 RAW / JPEG | Ruído residual RAW / JPEG | Gradiente de borda RAW / JPEG | ΔE76 mediano |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| A | 0,22 px | 88,52 / 90,10 | 149,96 / 164,40 | 4,32 / 2,73 | 10,12 / 12,20 | 12,47 |
| B | 0,21 px | 88,09 / 90,10 | 150,20 / 165,04 | 4,45 / 2,73 | 10,46 / 12,67 | 12,68 |

Os dois pares conservaram 98,5% de área em comum. A inspeção mostrou mais granulação cromática e menor contraste em traços finos no RAW revelado. O JPEG tem superfícies mais uniformes e letras mais definidas nesta cena. O RAW com ganho manteve diferenças de tom e de cor nos objetos coloridos. A comparação descreve a revelação AHD e o JPEG do ISP para os mesmos disparos. Sem referência limpa, não é possível atribuir fidelidade absoluta, quantificar resolução óptica ou separar a ação de cada etapa do ISP.

## Conclusão técnica

O RAW tele é viável como entrada para laboratório e para uma futura Horizon AI. Em ambos os disparos, a saída física preservou o mosaico e os metadados necessários para trabalhar com nível preto e branco, ruído por sinal, balanço de branco e calibração antes da revelação. Para experimentos que dependem do modelo de ruído, preserve o DNG como fonte e prefira estudar os valores lineares do mosaico antes do demosaico ou do ganho de balanço. O TIFF RGB linear é uma referência conveniente para o A/B por imagem. A qualidade e o custo de um futuro processamento RAW dependem de ensaios com várias cenas e exposições, carta de cor e validação das correções ópticas. O tamanho bruto foi cerca de 4,85 vezes o JPEG neste teste.

Build de campo, APK instrumentado, lint, 111 testes JVM e 26 testes de calibração no host passaram. A instrumentação foi instalada com SHA-256 `e0364b0cc116c6ed1635695733f11f57fbac130aed0a1be935d62434054ed45c` e passou nos dois disparos. O host usado foi Python 3.12, rawpy 0.28.0, LibRaw 0.22.2, tifffile 2026.11.16, OpenCV 5.1.0 e Pillow 13.0.0. Os originais, revelações e manifestos de campo não estão no Git.
