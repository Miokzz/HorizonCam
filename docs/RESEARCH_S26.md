# Pesquisa da implementação original S26 Ultra

## Confirmado oficialmente

1. O Horizontal Lock é escolhido no **modo Vídeo**, na seleção do **Super Steady**, oferecendo modo com travamento do horizonte.
   - https://www.samsung.com/us/support/answer/ANS10010423/
   - https://www.samsung.com/us/support/answer/ANS10010343/
2. A estabilização combina informações do giroscópio e acelerômetro para determinar direção da gravidade e manter horizonte durante movimentos e rotação de 360°. A Samsung destaca *ângulo óptico ampliado*, que permite crop com campo de visão menos sacrificado.
   - https://news.samsung.com/us/galaxy-s26-series-keeps-your-video-steady-even-if-your-hands-dont/
   - https://www.samsung.com/br/smartphones/galaxy-s26-ultra/
3. Os exemplos oficiais mostram a câmera e o celular em paisagem na ativação. A Samsung **não publica** as matrizes internas, margens, API física exata, latência nem modelo de EIS.
4. A própria página Samsung Brasil identifica alguns vídeos promocionais como **simulados**. Eles não permitem inferir comportamento exato de cada controle ou dos metadados MP4.
5. O Super Steady reduz tremores; Horizontal Lock adiciona compensação de roll. São operações distintas.

## Observado na referência visual disponível

- Interface escura, visor central e comandos de vídeo que mudam ao gravar.
- Super Steady é acionado por controle dedicado / controles rápidos.
- O horizonte do conteúdo estabilizado não acompanha rigidamente a rotação física do telefone.
- Demonstração fornecida como referência: https://www.youtube.com/watch?v=wQ6FrY-X7cs

## Decisões próprias, NÃO confirmadas como iguais ao Samsung

- Referência `+90°` de roll gravitacional para canvas de vídeo paisagem.
- Recorte digital fixo analítico de ~2,07× na ausência de margem óptica equivalente.
- Leitura do histórico sensorial na hora do frame CameraX, usando REALTIME quando suportado.
- Reprodução da UI com Material + drawables próprios e realocação do HUD por orientação.
- Fallback de estabilização nativa EIS sob compatibilidade, nunca substituindo integração proprietária Super Steady.

Não declarar paridade funcional com S26 antes de gravação verificada em hardware e comparação com cenas equivalentes. Alguns recursos/posições da interface precisam ser validados com mais filmagem real da UI.
