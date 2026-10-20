# Sístole — plano do aplicativo Android

App de visualização do ECG da disciplina EEE025 (Instrumentação Biomédica, UFMG).
Recebe o sinal do ESP32 por **Bluetooth LE** ou por **USB serial (OTG)**, mostra o
traçado em escala real, grava sessões e ajuda a entender e exportar o que foi
gravado. O visual segue o design system **Sístole** feito no Claude Design
(tokens, componentes e 12 telas).

> Uso didático. Não é equipamento médico.

---

## 1. Decisões de arquitetura

| Tema | Escolha | Por quê |
| --- | --- | --- |
| Linguagem / UI | Kotlin + Jetpack Compose (Material 3 só como base, visual próprio) | Nativo Android: acesso direto a BLE e USB Host, Canvas rápido para 500 Hz a 60 fps. |
| BLE | API `android.bluetooth` nativa, GATT com notify + MTU 247 | Sem dependência extra; controle total de MTU e prioridade de conexão. |
| USB serial | [`usb-serial-for-android`](https://github.com/mik3y/usb-serial-for-android) 3.11 | Suporta CP2102, CH340/CH9102, FTDI e CDC-ACM (ESP32-S3 nativo). |
| Fonte simulada | Modo **Demo** com ECG sintético (soma de gaussianas P-QRS-T, HRV, ruído, 60 Hz opcional) | Desenvolver e apresentar sem o hardware. O chip "Demo" deixa claro que o sinal não é real. |
| Processamento | No app, em Kotlin puro (`dsp/`) | Filtros e detecção trocáveis ao vivo sem regravar o firmware. |
| Armazenamento | Uma pasta por sessão: `meta.json` + `samples.bin` (ADC bruto int16) + eventos | Sem banco de dados; bruto preservado para refiltrar e exportar depois. |
| Preferências | `SharedPreferences` exposto como `StateFlow` | Simples, reativo, sem biblioteca. |
| Mínimo / alvo | minSdk 26 (Android 8), target 36, compile 37 | Cobre praticamente todos os celulares da turma. |

### Fluxo de dados

```text
ESP32 ──BLE notify / USB serial / Demo──▶ EcgSource (bytes → amostras ADC + flags)
      ──▶ AcquisitionEngine
            ├─ conversão ADC → mV no eletrodo (ganho do front-end, Vref, offset)
            ├─ FilterChain: passa-altas 0,05/0,5 Hz · passa-baixas 40/150 Hz · notch 60 Hz (+120 Hz)
            ├─ QrsDetector (Pan-Tompkins) → picos R, RR, FC suavizada
            ├─ SignalQuality (lead-off, saturação, potência em 60 Hz, regularidade)
            ├─ AlarmManager (FC fora da faixa, eletrodo solto, conexão perdida, subida de FC)
            ├─ ring buffers para o traçado e para a tendência de 5 min
            └─ SessionRecorder (grava o bruto + eventos em disco)
      ──▶ UI Compose (StateFlow + leitura direta do ring buffer a cada frame)
```

---

## 2. Protocolo ESP32 ↔ app (v1)

Padrão: **500 Hz**, ADC de **12 bits** (0–4095), Vref 3,3 V.

### USB serial — texto (padrão, 230400 baud)

Uma amostra por linha, compatível com o Serial Plotter do Arduino:

```text
<seq>,<adc>,<lo>\n      ex.: 1523,2087,0
<adc>\n                 também aceito (app numera as amostras)
#fs=500;bits=12;vref=3.3;fw=sistole-fw 0.1.0;lo=0\n   metadados (linha começa com #)
#bat=3910\n             bateria em mV
```

- `seq`: contador de amostras 0–65535 (volta a 0). Serve para contar amostras perdidas.
- `lo`: bit0 = LO+, bit1 = LO− (lead-off). 0 = contato bom. Opcional.

### USB serial — binário (opcional)

`0xA5 0x5A | len (u8) | pacote (igual ao BLE) | crc8 (poly 0x07)`.

### Bluetooth LE

| UUID | Tipo | Conteúdo |
| --- | --- | --- |
| `8a3e0001-3c1d-4f2b-9e6a-5f1b2c7d9e10` | Serviço | Sístole ECG |
| `8a3e0002-…` | Notify | Pacote de amostras |
| `8a3e0003-…` | Read | Texto `fs=500;bits=12;vref=3.3;fw=…` |
| `8a3e0004-…` | Write | Comandos de texto: `start`, `stop`, `fs=250` |

Pacote (little-endian): `u16 seq` (índice da 1ª amostra) · `u8 flags` (bit0 LO+, bit1 LO−,
bit2 bateria presente) · `u8 n` · `n × u16` amostras · `u16 bateria_mV` (se bit2).
Com MTU 247 o firmware manda 20 amostras por pacote (25 pacotes/s).

O firmware de referência está em [`../Firmware/sistole_fw/`](../Firmware/sistole_fw/).

---

## 3. Funcionalidades

Legenda: **[D]** está no design · **[+]** acrescentado pela pesquisa · **[I]** voltado à disciplina.

### 3.1 Monitor ao vivo (aba Monitor)
- [D] Traçado em **varredura** (sweep) com lacuna de 16 px, papel milimetrado em **escala real** (usa o DPI físico da tela): 12,5 / 25 / 50 mm/s e 5 / 10 / 20 mm/mV.
- [D] FC grande (`vital-xl`) suavizada pela média dos últimos 8 RR, coração que pulsa no pico R, mín/máx dos últimos 5 min.
- [D] Intervalo RR e RMSSD ao vivo; tendência de FC de 5 min com a faixa de alarme e marcadores.
- [D] Barra de ações: Marcar evento · Congelar · Gravar/Parar · Medir (compasso) · Tela cheia.
- [D] Chip de conexão (BLE/USB/Demo) e cronômetro REC; qualidade do sinal em 4 níveis com palavra.
- [D] Estado "sem sinal": traço tracejado a 35 %, véu vermelho, vitais em "– –" com "última válida · há N s".
- [D] Banner sólido de alarme com "Silenciar" (2 min), nunca "Fechar".
- [+] Bipe opcional a cada batimento e som de alarme.
- [+] Tela sempre ligada durante a aquisição.

### 3.2 Tela cheia em paisagem
- [D] Traçado ocupando a tela, HUD pequeno de FC, coluna de ferramentas à direita.
- [D] **Compasso** (calipers) que encaixa nos picos R e mostra Δt em ms e a FC equivalente; arrastável.
- [D] Pulso de calibração de 1 mV; congelar/retomar; marcar evento.
- [+] Zoom (troca de velocidade e ganho na hora).

### 3.3 Conectar
- [D] **Bluetooth LE**: varredura com radar, filtro "Só Sístole" (UUID do serviço), RSSI em barras, último aparelho usado primeiro.
- [D] **USB serial**: detecção do conversor pelo VID:PID (CP2102, CH340, FTDI, CDC), pedido de permissão USB, baud rate (115200 / 230400 / 921600), formato Texto (CSV) ou Binário.
- [D] Aviso fixo de segurança elétrica: com eletrodos em alguém, celular fora da tomada.
- [D] **Demo**: sinal sintético com FC ajustável, para aula e desenvolvimento.
- [+] Reconexão automática ao último aparelho BLE e ao cabo USB plugado.

### 3.4 Dispositivo → Eletrodos
- [D] Triângulo de Einthoven com R, L, F e N nas cores IEC (alternável para AHA) e a letra sempre visível.
- [D] Estado de contato ao vivo pelo lead-off (LO+/LO−) do firmware; a aresta da derivação medida (DI, DII ou DIII, escolhida no app) fica azul sólida com contato e vermelha tracejada sem; as outras aparecem como não medidas.
- [D] "Ir para o monitor" só habilita com contato.
- [+] Quando o hardware não informa lead-off, o app diz "não informado pelo hardware" e usa saturação do ADC como indício.

### 3.5 Dispositivo → Diagnóstico **[I]**
- [D] Taxa de amostragem **medida** × alvo, amostras perdidas (pelo `seq`), intervalo entre pacotes, bateria do ESP, RSSI.
- [D] ADC bruto de 12 bits sem filtro, % de saturação, LO+/LO−, firmware e protocolo.
- [D] Console com as últimas linhas recebidas e "Salvar log".
- [+] **Espectro (FFT) ao vivo** de 0 a 250 Hz com marcas em 60 e 120 Hz: mostra na hora se o notch analógico de 59–61 / 119–121 Hz está funcionando e quanto ruído de rede sobra.
- [+] **Potência de 60 Hz** (Goertzel) antes e depois do filtro digital, em dB.

### 3.6 Sessões
- [D] Lista agrupada por dia com título, hora, voluntário, derivação, chip de resumo, tira de 3 s, duração, FC média e faixa.
- [D] Filtros: Todas · Com eventos · Esta semana; espaço ocupado no aparelho.
- [+] Filtro "FC fora da faixa", busca por título ou voluntário.
- [+] Renomear e excluir sessão (toque longo).

### 3.7 Revisão da sessão
- [D] Tendência da FC na sessão inteira com eventos; tira do trecho com janela arrastável e reprodução.
- [D] Estatísticas: média, mín–máx, batimentos, SDNN, RMSSD, qualidade (% do tempo com sinal bom).
- [D] Gráfico de **Poincaré** (RRₙ × RRₙ₊₁) com SD1/SD2.
- [D] Eventos do usuário e automáticos ("Subida de FC… Não é diagnóstico.").
- [+] **Batimento médio** **[I]**: média coerente dos batimentos alinhados no pico R. O ruído cai com √N e as ondas P, QRS e T aparecem nítidas, com o resíduo em µV e "Não é diagnóstico".
- [+] pNN50 junto do Poincaré.
- [+] **Refiltrar o sinal bruto** na revisão (da gravação · 0,5–40 Hz · 0,05–150 Hz); a exportação usa o filtro escolhido.

### 3.8 Exportar
- [D] **PDF**: relatório com traçado a 25 mm/s em papel milimetrado, estatísticas e eventos.
- [D] **CSV**: `tempo_s, adc, mv_bruto, mv_filtrado` para MATLAB / Python.
- [D] **EDF+**: abre em EDFbrowser, MNE, PhysioNet; eventos como anotações.
- [D] Sessão inteira ou seleção; incluir ou não o bruto; compartilhar pela folha do Android.

### 3.9 Ajustes
- [D] Prévia ao vivo do traçado no topo.
- [D] Exibição: velocidade e ganho. Filtros: passa-altas 0,05 / 0,5 Hz, passa-baixas 40 / 150 Hz, notch 60 Hz.
- [+] Chave **Filtrar no app** (e "Desligado" em cada filtro) para ver o sinal só com os filtros analógicos do circuito; "Medir offset agora" calibra a linha de base sem o passa-altas digital.
- [D] Alarme de FC com faixa dupla (padrão 50–120), som, bipe por batimento, aviso de eletrodo solto.
- [D] Tema: Automático · Monitor (escuro) · Papel (claro). Manter tela ligada.
- [D] Perfis de voluntários (usados ao gravar).
- [+] **Calibração do front-end** **[I]**: ganho total do circuito (INA122 + estágio de ganho), tensão de offset e taxa de amostragem, para o app mostrar mV reais no eletrodo.
- [D] Sobre e aviso de uso ("Uso didático. Não é equipamento médico."), também na primeira abertura.

### Fora do escopo desta versão
Classificação de arritmias por IA, nuvem/telemedicina, 12 derivações. O app nunca afirma diagnóstico.

---

## 4. Algoritmos

- **Filtros**: biquads IIR (Butterworth de 2ª ordem, fórmulas RBJ) em cascata, causais, recalculados quando fs ou o ajuste mudam. Notch com Q = 30 em 60 Hz e, no modo 150 Hz, também em 120 Hz.
- **QRS (Pan-Tompkins)**: passa-faixa 5–15 Hz → derivada → quadrado → integração em janela de 150 ms → limiares adaptativos (SPKI/NPKI) com período refratário de 200 ms e busca retroativa; o pico é refinado no sinal filtrado. Latência ≈ 200 ms.
- **HRV**: RR filtrados (300–2000 ms, variação < 30 % da média local); SDNN, RMSSD, pNN50, SD1 = √(½·var(ΔRR)), SD2.
- **Qualidade (1–4)**: lead-off → 1; saturação > 5 % → 1; nenhuma batida em 3 s → 1; potência de 60 Hz alta ou RR irregular → 2; senão 3/4 pela razão sinal/ruído.
- **Subida de FC**: FC suavizada sobe > 20 bpm em ≤ 30 s → evento automático.

---

## 5. Status

Todas as funcionalidades das seções 3.1 a 3.9 estão implementadas e foram verificadas no emulador (Android 16)
em modo Demo: conexão, varredura em escala real, FC/RR/RMSSD, tendência, alarme de eletrodo solto, gravação com
eventos, lista de sessões, revisão com batimento médio e Poincaré, exportação em PDF, tela cheia com compasso,
Eletrodos, Diagnóstico com espectro e os dois temas. BLE e USB estão implementados mas ainda precisam ser testados
com o ESP32 real. Ficou fora o histograma de RR.

## 6. Etapas de execução

1. Esqueleto do projeto Gradle, tema com os tokens do design, fontes, ícones próprios, navegação por abas.
2. Fontes de dados: Demo, USB serial, BLE; parsers de protocolo com testes unitários.
3. DSP: filtros, Pan-Tompkins, HRV, qualidade, FFT, com testes unitários sobre o sinal sintético.
4. Monitor + traçado em varredura + tela cheia com compasso.
5. Conectar, Eletrodos, Diagnóstico.
6. Gravação, Sessões, Revisão, Exportar (PDF/CSV/EDF+).
7. Ajustes, alarmes, primeira abertura.
8. Firmware de referência do ESP32.
9. Build, testes, execução no emulador em modo Demo e capturas de tela.

## 7. Referências usadas na pesquisa
- Pan & Tompkins, *A Real-Time QRS Detection Algorithm*, IEEE TBME, 1985.
- Task Force ESC/NASPE, *Heart rate variability: standards of measurement*, 1996.
- Projetos abertos semelhantes: [HeartyPatch](https://heartypatch.protocentral.com/) (ESP32 + HRV), [OpenHRStrap](https://github.com/MilosRasic98/OpenHRStrap) (ESP32 + Pan-Tompkins), [esp32-serial-BLE-ECG](https://github.com/dannyngweekiat/esp32-serial-BLE-ECG/), [ECG-Monitoring-System](https://github.com/dimitriosp01/ECG-Monitoring-System).
- Especificação EDF+ (edfplus.info).
