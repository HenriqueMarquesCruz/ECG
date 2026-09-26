# Firmware Sístole (`sistole_fw`)

Firmware de referência do ECG de derivação única (INA122 → filtros → ganho/offset → ADC do ESP32).
Amostra a 500 Hz (padrão), envia pela USB Serial e por BLE para o app.

> **Segurança:** com eletrodos no corpo, alimente o circuito por bateria e use um notebook
> **desconectado da tomada** (ou isolador USB). Nunca ligue o voluntário a um equipamento na rede elétrica.

## Ligações

| Sinal | ESP32 clássico | ESP32-S3 | ESP32-C3 |
|---|---|---|---|
| Saída do estágio ganho/offset (0–3,3 V) | GPIO 34 | GPIO 4 | GPIO 3 |
| GND do front-end | GND | GND | GND |
| LO+ / LO- (opcional) | `LO_PLUS_PIN` / `LO_MINUS_PIN` | idem | idem |
| Bateria via divisor (opcional) | `BAT_PIN` (ADC1) | idem | idem |

- Use sempre pinos do **ADC1** (o ADC2 não funciona junto com o rádio).
- Centralize o sinal em ~1,65 V. Com atenuação de 11 dB o ADC do ESP32 clássico satura por volta de 3,1 V
  e fica não linear acima de ~2,9 V.
- Pinos opcionais ficam em `-1` (ausentes). Lead-off: nível 1 = eletrodo solto.
  Bateria: ajuste `BAT_DIVIDER` (ex.: 100k/100k → `2.0`).
- LED da placa: pisca anunciando BLE, aceso quando conectado.

## Arduino IDE

1. Gerenciador de placas: **esp32 by Espressif Systems, versão 3.x**.
2. Placa: *ESP32 Dev Module* (ou *ESP32S3 Dev Module* / *ESP32C3 Dev Module*).
3. **Partition Scheme: "Huge APP (3MB No OTA)"** — a pilha BLE não cabe na partição padrão de 1,2 MB.
4. S3/C3 com USB nativa: *USB CDC On Boot: Enabled* para a Serial sair pela USB.
5. Abra `sistole_fw/sistole_fw.ino` e grave. Nenhuma biblioteca extra (usa a BLE que vem no core).

Opções no topo do `.ino` (seção CONFIGURAÇÃO):

| Constante | Padrão | Efeito |
|---|---|---|
| `SIMULATE` | `false` | ECG sintético (P-QRS-T, 72 bpm, em torno de 2048), sem precisar do front-end |
| `SERIAL_BINARY` | `false` | Serial em quadros binários em vez de texto |
| `SERIAL_PLOTTER_ONLY_ADC` | `false` | Serial imprime só o valor do ADC (melhor para o Serial Plotter) |
| `FS` | `500` | Taxa inicial (250, 500 ou 1000 Hz) |

## Teste rápido com o Serial Plotter

1. Coloque `SIMULATE = true` e `SERIAL_PLOTTER_ONLY_ADC = true`, grave.
2. *Ferramentas → Serial Plotter*, **230400 baud**. Deve aparecer um ECG limpo a 72 bpm.
3. Volte `SIMULATE = false` e ligue o front-end: o traçado real deve aparecer no mesmo lugar.
4. No modo texto normal (`SERIAL_PLOTTER_ONLY_ADC = false`) o plotter também desenha `seq` (0–65535),
   o que achata o ECG; use o Serial Monitor para ver as linhas cruas.

Pelo Serial Monitor (fim de linha "Nova linha") dá para enviar os mesmos comandos do BLE: `fs=1000`, `start`, `stop`.

Para testar o BLE sem o app: **nRF Connect** (celular) → procure "Sistole-01" → ative notificações na
característica `…0002` e escreva `fs=250` (texto) na `…0004`.

## Protocolo

### Serial, modo texto (padrão) — 230400 baud

```
<seq>,<adc>,<lo>          uma linha por amostra
#fs=500;bits=12;vref=3.3;fw=sistole-fw 0.1.0;lo=0   a cada 2 s (e logo após mudar fs)
#bat=3910                 a cada 1 s, só se houver pino de bateria (mV)
```

`seq`: contador u16 de amostras (volta em 65536; buraco no `seq` = amostra perdida).
`adc`: 0–4095. `lo`: bit0 = LO+, bit1 = LO-. Ignore linhas que começam com `#` ao plotar.

### Serial, modo binário (`SERIAL_BINARY = true`)

```
0xA5 0x5A | len (u8) | payload (len bytes) | crc8
```

`payload` é o mesmo pacote do BLE (20 amostras por quadro). CRC-8 poly 0x07, init 0x00, só sobre o payload
(verificação: `"123456789"` → `0xF4`). Nenhuma linha de texto é enviada nesse modo.

### BLE — nome `Sistole-01`

| Característica | UUID | Propriedades | Conteúdo |
|---|---|---|---|
| Serviço | `8a3e0001-3c1d-4f2b-9e6a-5f1b2c7d9e10` | anunciado | — |
| Stream | `8a3e0002-3c1d-4f2b-9e6a-5f1b2c7d9e10` | NOTIFY | pacotes de amostras |
| Info | `8a3e0003-3c1d-4f2b-9e6a-5f1b2c7d9e10` | READ | `fs=500;bits=12;vref=3.3;fw=sistole-fw 0.1.0;lo=0;bat=0` |
| Controle | `8a3e0004-3c1d-4f2b-9e6a-5f1b2c7d9e10` | WRITE / WRITE_NR | `start`, `stop`, `fs=250\|500\|1000` |

Pacote (little-endian):

| Campo | Tipo | Descrição |
|---|---|---|
| seq | u16 | seq da primeira amostra do pacote |
| flags | u8 | bit0 LO+, bit1 LO- (última amostra); bit2 = campo de bateria presente |
| n | u8 | nº de amostras |
| adc[n] | n × u16 | amostras |
| bat_mV | u16 | só se bit2 (≈ 1× por segundo) |

- `n = min(20, (MTU − 9) / 2)`: MTU 23 → 7 amostras; MTU ≥ 49 → 20. O firmware aceita MTU até 247 —
  peça MTU no app (Android: `requestMtu(247)`).
- Amostras de um pacote são sempre consecutivas: a amostra `i` tem `seq + i`.
- Notificações só saem com cliente conectado, notificações habilitadas e após `start`
  (o streaming já começa ligado a cada conexão; `stop` pausa só o BLE — a Serial continua sempre).
- `fs=` muda a taxa na hora; releia a característica Info (ou a linha `#fs=` na Serial).
- Ao desconectar, o anúncio recomeça sozinho.
