/*
 * sistole_fw — firmware de referência do ECG "Sístole"
 * Disciplina de Instrumentação Biomédica — UFMG
 *
 * Cadeia analógica: eletrodos -> INA122 -> PB 160 Hz -> notch 60/120 Hz ->
 * bloqueio DC -> ganho + offset (sinal em 0–3,3 V) -> ADC1 do ESP32.
 * Derivação única.
 *
 * Alvo: Arduino-ESP32 core 3.x (ESP32 clássico; compila também p/ S3 e C3).
 *
 * Arquitetura:
 *   timer HW (ISR) --notify--> task de amostragem (core 1) --> ring buffer SPSC
 *   loop(): esvazia o ring -> Serial (texto ou binário) + BLE (notify).
 */

#include <Arduino.h>
#include <atomic>
#include <string.h>
#include <ctype.h>
#include <math.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ============================================================================
// 1. CONFIGURAÇÃO
// ============================================================================

// --- Opções de compilação ---
static constexpr bool SIMULATE = false;                // true: ECG sintético (sem front-end)
static constexpr bool SERIAL_BINARY = false;           // true: quadros binários na Serial
static constexpr bool SERIAL_PLOTTER_ONLY_ADC = false; // true: só "<adc>\n" (Serial Plotter)

// --- Amostragem ---
static constexpr uint16_t FS = 500;            // Hz inicial (250, 500 ou 1000)
static constexpr uint32_t TIMER_HZ = 1000000;  // base de tempo do timer: 1 MHz
static constexpr uint8_t ADC_BITS = 12;
static constexpr const char* VREF_STR = "3.3";

// --- Pinos (use sempre um pino do ADC1: o ADC2 conflita com o rádio) ---
#if CONFIG_IDF_TARGET_ESP32S3
static constexpr int ECG_PIN = 4;   // ADC1_CH3
#elif CONFIG_IDF_TARGET_ESP32C3
static constexpr int ECG_PIN = 3;   // ADC1_CH3
#else
static constexpr int ECG_PIN = 34;  // ADC1_CH6 (ESP32 clássico, só entrada)
#endif

static constexpr int LO_PLUS_PIN = -1;   // lead-off LO+ (-1 = ausente; 1 = eletrodo solto)
static constexpr int LO_MINUS_PIN = -1;  // lead-off LO- (-1 = ausente)
static constexpr int BAT_PIN = -1;       // divisor da bateria (-1 = ausente)
static constexpr float BAT_DIVIDER = 2.0f; // Vbat = Vpino * BAT_DIVIDER (ex.: 100k/100k)

#ifdef LED_BUILTIN
static constexpr int LED_PIN = LED_BUILTIN;
#else
static constexpr int LED_PIN = 2;
#endif

// --- Serial ---
static constexpr uint32_t SERIAL_BAUD = 230400;
static constexpr uint32_t META_PERIOD_MS = 2000;
static constexpr uint32_t BAT_PERIOD_MS = 1000;

// --- Tarefas / buffers ---
static constexpr uint32_t RING_SIZE = 1024;  // potência de 2
static constexpr uint32_t RING_MASK = RING_SIZE - 1;
static constexpr uint32_t SAMPLER_STACK = 4096;
static constexpr UBaseType_t SAMPLER_PRIORITY = 10;  // acima do loop (1)
#if CONFIG_FREERTOS_UNICORE
static constexpr BaseType_t SAMPLER_CORE = 0;        // C3: núcleo único
#else
static constexpr BaseType_t SAMPLER_CORE = 1;        // BLE roda no core 0
#endif

// --- Simulação (72 bpm) ---
static constexpr float SIM_BPM = 72.0f;
static constexpr float SIM_COUNTS_PER_MV = 1241.0f; // = ganho 1000 com Vref 3,3 V (padrão do app)
static constexpr int SIM_BASELINE = 2048;

// ============================================================================
// 2. PROTOCOLO
// ============================================================================

static constexpr const char* FW_VERSION = "sistole-fw 0.1.0";
static constexpr const char* DEVICE_NAME = "Sistole-01";  // ASCII no anúncio BLE

static constexpr const char* SERVICE_UUID = "8a3e0001-3c1d-4f2b-9e6a-5f1b2c7d9e10";
static constexpr const char* STREAM_UUID  = "8a3e0002-3c1d-4f2b-9e6a-5f1b2c7d9e10";
static constexpr const char* INFO_UUID    = "8a3e0003-3c1d-4f2b-9e6a-5f1b2c7d9e10";
static constexpr const char* CONTROL_UUID = "8a3e0004-3c1d-4f2b-9e6a-5f1b2c7d9e10";

static constexpr uint16_t BLE_MTU_REQUEST = 247;
static constexpr uint16_t BLE_MTU_DEFAULT = 23;

// Pacote (little-endian):
//   u16 seq (1a amostra) | u8 flags | u8 n | n x u16 adc | [u16 bat_mV se flags.bit2]
static constexpr uint8_t MAX_SAMPLES_PER_PACKET = 20;
static constexpr size_t PACKET_HEADER = 4;
static constexpr size_t PACKET_MAX = PACKET_HEADER + 2 * MAX_SAMPLES_PER_PACKET + 2;
static constexpr uint8_t FLAG_LO_PLUS = 0x01;
static constexpr uint8_t FLAG_LO_MINUS = 0x02;
static constexpr uint8_t FLAG_BATTERY = 0x04;

// Quadro serial binário: 0xA5 0x5A | u8 len | payload | crc8(payload)
static constexpr uint8_t SYNC0 = 0xA5;
static constexpr uint8_t SYNC1 = 0x5A;

static constexpr bool HAS_LEADOFF = (LO_PLUS_PIN >= 0) || (LO_MINUS_PIN >= 0);
static constexpr bool HAS_BATTERY = (BAT_PIN >= 0);

struct Sample {
  uint16_t seq;  // contador de amostras (volta em 65536)
  uint16_t adc;  // leitura bruta 0..4095
  uint8_t lo;    // bit0 LO+, bit1 LO-
};

// Acumulador de amostras para montar um pacote.
struct Packer {
  uint16_t firstSeq;
  uint8_t n;
  uint8_t maxN;
  uint8_t lo;
  uint32_t batSeen;  // última leitura de bateria já enviada
  uint16_t adc[MAX_SAMPLES_PER_PACKET];
};

typedef void (*EmitFn)(Packer&);

// --- Estado compartilhado (atômicos; escrita por um único produtor) ---
static std::atomic<uint16_t> g_fs{FS};        // fs em uso (escrito só pelo loop)
static std::atomic<uint16_t> g_fsReq{FS};     // fs pedida por comando
static std::atomic<uint16_t> g_batMv{0};
static std::atomic<uint32_t> g_batCount{0};   // incrementa a cada nova leitura

static bool fsValid(int hz) { return hz == 250 || hz == 500 || hz == 1000; }

// CRC-8, poly 0x07, init 0x00 (CRC-8/SMBUS; "123456789" -> 0xF4)
static uint8_t crc8(const uint8_t* d, size_t n) {
  uint8_t c = 0;
  while (n--) {
    c ^= *d++;
    for (uint8_t i = 0; i < 8; i++) c = (c & 0x80) ? (uint8_t)((c << 1) ^ 0x07) : (uint8_t)(c << 1);
  }
  return c;
}

// Serializa o pacote em 'out' (>= PACKET_MAX bytes). Retorna o tamanho.
static size_t buildPayload(Packer& p, uint8_t* out) {
  bool withBat = false;
  uint16_t mv = 0;
  if (HAS_BATTERY) {
    uint32_t cnt = g_batCount.load(std::memory_order_acquire);
    if (cnt != 0 && cnt != p.batSeen) {
      withBat = true;
      mv = g_batMv.load(std::memory_order_relaxed);
      p.batSeen = cnt;
    }
  }
  size_t i = 0;
  out[i++] = p.firstSeq & 0xFF;
  out[i++] = p.firstSeq >> 8;
  out[i++] = (p.lo & (FLAG_LO_PLUS | FLAG_LO_MINUS)) | (withBat ? FLAG_BATTERY : 0);
  out[i++] = p.n;
  for (uint8_t k = 0; k < p.n; k++) {
    out[i++] = p.adc[k] & 0xFF;
    out[i++] = p.adc[k] >> 8;
  }
  if (withBat) {
    out[i++] = mv & 0xFF;
    out[i++] = mv >> 8;
  }
  return i;
}

// Adiciona uma amostra; chama emit() quando o pacote enche ou quando há
// buraco no seq (amostra perdida), para que seq + índice seja sempre exato.
static void packerPush(Packer& p, const Sample& s, uint8_t maxN, EmitFn emit) {
  if (p.n > 0 && s.seq != (uint16_t)(p.firstSeq + p.n)) {
    emit(p);
    p.n = 0;
  }
  if (p.n == 0) {
    p.firstSeq = s.seq;
    p.maxN = constrain(maxN, 1, MAX_SAMPLES_PER_PACKET);
  }
  p.adc[p.n++] = s.adc;
  p.lo = s.lo;
  if (p.n >= p.maxN) {
    emit(p);
    p.n = 0;
  }
}

// Comandos (BLE Control e Serial): "start", "stop", "fs=<hz>".
// Só altera atômicos — seguro a partir da task do BLE.
static std::atomic<bool> g_bleStreaming{true};

static void handleCommand(const char* src, size_t len) {
  char b[16];
  size_t n = 0;
  for (size_t i = 0; i < len && n < sizeof(b) - 1; i++) {
    char c = src[i];
    if (c == ' ' || c == '\r' || c == '\n' || c == '\t' || c == '\0') continue;
    b[n++] = (char)tolower((unsigned char)c);
  }
  b[n] = '\0';

  if (strcmp(b, "start") == 0) {
    g_bleStreaming.store(true);
  } else if (strcmp(b, "stop") == 0) {
    g_bleStreaming.store(false);
  } else if (strncmp(b, "fs=", 3) == 0) {
    int hz = atoi(b + 3);
    if (fsValid(hz)) g_fsReq.store((uint16_t)hz);
  }
}

// ============================================================================
// 3. AMOSTRAGEM
// ============================================================================

// Ring buffer SPSC sem lock: produtor = task de amostragem, consumidor = loop.
static Sample g_ring[RING_SIZE];
static std::atomic<uint32_t> g_ringHead{0};  // escrito só pelo produtor
static std::atomic<uint32_t> g_ringTail{0};  // escrito só pelo consumidor

static bool ringPush(const Sample& s) {
  uint32_t h = g_ringHead.load(std::memory_order_relaxed);
  uint32_t t = g_ringTail.load(std::memory_order_acquire);
  if (h - t >= RING_SIZE) return false;  // cheio: descarta (vira buraco no seq)
  g_ring[h & RING_MASK] = s;
  g_ringHead.store(h + 1, std::memory_order_release);
  return true;
}

static bool ringPop(Sample& s) {
  uint32_t t = g_ringTail.load(std::memory_order_relaxed);
  uint32_t h = g_ringHead.load(std::memory_order_acquire);
  if (t == h) return false;
  s = g_ring[t & RING_MASK];
  g_ringTail.store(t + 1, std::memory_order_release);
  return true;
}

static TaskHandle_t g_samplerTask = nullptr;
static hw_timer_t* g_timer = nullptr;

// ISR do timer: só acorda a task (analogRead não é seguro em ISR no core 3.x).
static void ARDUINO_ISR_ATTR onSampleTimer() {
  BaseType_t woken = pdFALSE;
  if (g_samplerTask) vTaskNotifyGiveFromISR(g_samplerTask, &woken);
  if (woken == pdTRUE) portYIELD_FROM_ISR();
}

static uint8_t readLeadOff() {
  uint8_t lo = 0;
  if (LO_PLUS_PIN >= 0 && digitalRead((uint8_t)LO_PLUS_PIN)) lo |= FLAG_LO_PLUS;
  if (LO_MINUS_PIN >= 0 && digitalRead((uint8_t)LO_MINUS_PIN)) lo |= FLAG_LO_MINUS;
  return lo;
}

static void readBattery() {
  uint32_t acc = 0;
  for (int i = 0; i < 4; i++) acc += analogReadMilliVolts((uint8_t)BAT_PIN);
  float mv = (acc / 4.0f) * BAT_DIVIDER;
  g_batMv.store((uint16_t)constrain(mv, 0.0f, 65535.0f), std::memory_order_relaxed);
  g_batCount.store(g_batCount.load(std::memory_order_relaxed) + 1, std::memory_order_release);
}

// ECG sintético: soma de gaussianas P, Q, R, S, T. t em segundos dentro do batimento.
static uint16_t simEcg(float t) {
  struct Wave { float a, mu, sigma; };  // mV, s, s
  static const Wave w[] = {
    { 0.15f, 0.200f, 0.025f},  // P
    {-0.10f, 0.345f, 0.010f},  // Q
    { 1.20f, 0.370f, 0.012f},  // R
    {-0.25f, 0.395f, 0.010f},  // S
    { 0.30f, 0.600f, 0.040f},  // T
  };
  float mv = 0.0f;
  for (const Wave& g : w) {
    float d = (t - g.mu) / g.sigma;
    mv += g.a * expf(-0.5f * d * d);
  }
  int v = SIM_BASELINE + (int)(mv * SIM_COUNTS_PER_MV) + (int)random(-3, 4);
  return (uint16_t)constrain(v, 0, (1 << ADC_BITS) - 1);
}

static void samplerTask(void*) {
  uint16_t seq = 0;
  uint32_t batDiv = 0;
  float simT = 0.0f;
  const float simPeriod = 60.0f / SIM_BPM;

  for (;;) {
    uint32_t ticks = ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
    if (ticks == 0) continue;
    // Ticks perdidos (task atrasada): avança o seq para o app ver o buraco.
    if (ticks > 1) seq += (uint16_t)(ticks - 1);

    uint16_t fs = g_fs.load(std::memory_order_relaxed);
    Sample s;
    s.seq = seq++;
    if (SIMULATE) {
      simT += ticks / (float)fs;
      while (simT >= simPeriod) simT -= simPeriod;
      s.adc = simEcg(simT);
      s.lo = 0;
    } else {
      s.adc = analogRead(ECG_PIN);
      s.lo = readLeadOff();
    }
    ringPush(s);

    // Bateria ~1x/s, lida aqui para todo acesso ao ADC ficar numa só task.
    if (HAS_BATTERY) {
      if (batDiv == 0) readBattery();
      batDiv += ticks;
      if (batDiv >= fs) batDiv = 0;
    }
  }
}

static void samplingSetup() {
  analogReadResolution(ADC_BITS);
  if (!SIMULATE) {
    analogSetPinAttenuation(ECG_PIN, ADC_11db);  // faixa ~0–3,1 V
    (void)analogRead(ECG_PIN);                    // configura o canal antes da task
  }
  if (LO_PLUS_PIN >= 0) pinMode((uint8_t)LO_PLUS_PIN, INPUT);
  if (LO_MINUS_PIN >= 0) pinMode((uint8_t)LO_MINUS_PIN, INPUT);
  if (HAS_BATTERY) {
    analogSetPinAttenuation((uint8_t)BAT_PIN, ADC_11db);
    (void)analogReadMilliVolts((uint8_t)BAT_PIN);
  }

  xTaskCreatePinnedToCore(samplerTask, "sampler", SAMPLER_STACK, nullptr,
                          SAMPLER_PRIORITY, &g_samplerTask, SAMPLER_CORE);

  g_timer = timerBegin(TIMER_HZ);
  if (!g_timer) {
    Serial.println("#err=timer");
    return;
  }
  timerAttachInterrupt(g_timer, &onSampleTimer);
  timerAlarm(g_timer, TIMER_HZ / g_fs.load(), true, 0);  // auto-reload, infinito
}

static void updateInfoChr();  // seção BLE
static bool g_forceMeta = true;

// Aplica fs pedida por comando (chamado só pelo loop).
static void applyPendingFs() {
  uint16_t req = g_fsReq.load();
  if (req == g_fs.load() || !fsValid(req)) return;
  if (g_timer) {
    timerStop(g_timer);
    timerWrite(g_timer, 0);
    timerAlarm(g_timer, TIMER_HZ / req, true, 0);
  }
  g_fs.store(req);
  if (g_timer) timerStart(g_timer);
  updateInfoChr();
  g_forceMeta = true;
}

// ============================================================================
// 4. BLE
// ============================================================================

static BLEServer* g_server = nullptr;
static BLECharacteristic* g_streamChr = nullptr;
static BLECharacteristic* g_infoChr = nullptr;
static BLE2902* g_cccd = nullptr;

static std::atomic<bool> g_connected{false};
static std::atomic<bool> g_advRestart{false};
static std::atomic<uint32_t> g_disconnectMs{0};
static std::atomic<uint32_t> g_connGen{0};  // muda a cada conexão
static std::atomic<uint16_t> g_mtu{BLE_MTU_DEFAULT};

static Packer g_blePk = {};

class ServerCb : public BLEServerCallbacks {
 public:
  void onConnect(BLEServer*) {
    g_mtu.store(BLE_MTU_DEFAULT);
    g_bleStreaming.store(true);
    g_connGen.store(g_connGen.load() + 1);
    g_connected.store(true);
  }
  void onDisconnect(BLEServer*) {
    g_connected.store(false);
    if (g_cccd) g_cccd->setNotifications(false);
    g_disconnectMs.store(millis());
    g_advRestart.store(true);  // reanuncia no loop
  }
#if defined(CONFIG_BLUEDROID_ENABLED)
  void onMtuChanged(BLEServer*, esp_ble_gatts_cb_param_t* param) {
    g_mtu.store(min(param->mtu.mtu, BLE_MTU_REQUEST));  // MTU negociado
  }
#endif
};

class ControlCb : public BLECharacteristicCallbacks {
 public:
  void onWrite(BLECharacteristic* c) {
    auto v = c->getValue();  // String (core 3.x) ou std::string (2.x)
    handleCommand(v.c_str(), v.length());
  }
};

static void updateInfoChr() {
  if (!g_infoChr) return;
  char buf[96];
  int n = snprintf(buf, sizeof(buf), "fs=%u;bits=%u;vref=%s;fw=%s;lo=%d;bat=%d",
                   (unsigned)g_fs.load(), (unsigned)ADC_BITS, VREF_STR, FW_VERSION,
                   HAS_LEADOFF ? 1 : 0, HAS_BATTERY ? 1 : 0);
  g_infoChr->setValue((uint8_t*)buf, (size_t)n);
}

// Amostras por pacote: n = min(20, (mtu - 3 ATT - 4 cabeçalho - 2 bateria) / 2)
static uint8_t bleMaxSamples() {
  int n = ((int)g_mtu.load() - 3 - (int)PACKET_HEADER - 2) / 2;
  return (uint8_t)constrain(n, 1, (int)MAX_SAMPLES_PER_PACKET);
}

static bool bleCanStream() {
  return g_connected.load() && g_bleStreaming.load() && g_cccd && g_cccd->getNotifications();
}

static void bleEmit(Packer& p) {
  uint8_t buf[PACKET_MAX];
  size_t len = buildPayload(p, buf);
  g_streamChr->setValue(buf, len);
  g_streamChr->notify();
}

static void bleFeed(const Sample& s, bool canStream) {
  if (!canStream) {
    g_blePk.n = 0;
    return;
  }
  packerPush(g_blePk, s, bleMaxSamples(), bleEmit);
}

static void bleSetup() {
  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setMTU(BLE_MTU_REQUEST);

  g_server = BLEDevice::createServer();
  g_server->setCallbacks(new ServerCb());

  BLEService* svc = g_server->createService(SERVICE_UUID);

  g_streamChr = svc->createCharacteristic(STREAM_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  g_cccd = new BLE2902();
  g_streamChr->addDescriptor(g_cccd);

  g_infoChr = svc->createCharacteristic(INFO_UUID, BLECharacteristic::PROPERTY_READ);
  updateInfoChr();

  BLECharacteristic* ctrl = svc->createCharacteristic(
      CONTROL_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  ctrl->setCallbacks(new ControlCb());

  svc->start();

  // UUID do serviço no anúncio (filtro do app); nome vai na scan response.
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
}

static void blePeriodic(uint32_t now) {
  // Nova conexão: descarta pacote parcial da conexão anterior.
  static uint32_t seenGen = 0;
  uint32_t gen = g_connGen.load();
  if (gen != seenGen) {
    seenGen = gen;
    g_blePk.n = 0;
  }

  // Reanuncia ~300 ms após desconectar (sem bloquear).
  if (g_advRestart.load() && !g_connected.load() &&
      (int32_t)(now - g_disconnectMs.load()) >= 300) {
    g_advRestart.store(false);
    BLEDevice::startAdvertising();
  }

#if !defined(CONFIG_BLUEDROID_ENABLED)
  // NimBLE: sem onMtuChanged compatível; consulta o MTU periodicamente.
  static uint32_t lastMtuPoll = 0;
  if (g_connected.load() && now - lastMtuPoll >= 500) {
    lastMtuPoll = now;
    uint16_t m = g_server->getPeerMTU(g_server->getConnId());
    if (m >= BLE_MTU_DEFAULT) g_mtu.store(m);
  }
#endif
}

// ============================================================================
// 5. SERIAL
// ============================================================================

static Packer g_serPk = {};

// Escreve só se couber inteiro no buffer de TX (nunca bloqueia o loop).
static void serialWriteIfRoom(const uint8_t* d, size_t n) {
  if (Serial.availableForWrite() >= (int)n) Serial.write(d, n);
}

static void serialEmitBinary(Packer& p) {
  uint8_t f[3 + PACKET_MAX + 1];
  f[0] = SYNC0;
  f[1] = SYNC1;
  size_t len = buildPayload(p, f + 3);
  f[2] = (uint8_t)len;
  f[3 + len] = crc8(f + 3, len);
  serialWriteIfRoom(f, len + 4);
}

static void serialFeed(const Sample& s) {
  if (SERIAL_BINARY) {
    packerPush(g_serPk, s, MAX_SAMPLES_PER_PACKET, serialEmitBinary);
    return;
  }
  char line[24];
  int n;
  if (SERIAL_PLOTTER_ONLY_ADC)
    n = snprintf(line, sizeof(line), "%u\n", (unsigned)s.adc);
  else
    n = snprintf(line, sizeof(line), "%u,%u,%u\n", (unsigned)s.seq, (unsigned)s.adc, (unsigned)s.lo);
  serialWriteIfRoom((const uint8_t*)line, (size_t)n);
}

// Metadados em modo texto: "#fs=...;..." a cada 2 s e "#bat=<mV>" a cada 1 s.
static void serialPeriodic(uint32_t now) {
  if (SERIAL_BINARY || SERIAL_PLOTTER_ONLY_ADC) return;
  static uint32_t lastMeta = 0, lastBat = 0;
  char buf[80];

  if (g_forceMeta || now - lastMeta >= META_PERIOD_MS) {
    g_forceMeta = false;
    lastMeta = now;
    int n = snprintf(buf, sizeof(buf), "#fs=%u;bits=%u;vref=%s;fw=%s;lo=%d\n",
                     (unsigned)g_fs.load(), (unsigned)ADC_BITS, VREF_STR, FW_VERSION, HAS_LEADOFF ? 1 : 0);
    serialWriteIfRoom((const uint8_t*)buf, (size_t)n);
  }
  if (HAS_BATTERY && now - lastBat >= BAT_PERIOD_MS && g_batCount.load() != 0) {
    lastBat = now;
    int n = snprintf(buf, sizeof(buf), "#bat=%u\n", (unsigned)g_batMv.load());
    serialWriteIfRoom((const uint8_t*)buf, (size_t)n);
  }
}

// Aceita os mesmos comandos do BLE pela Serial (uma linha por comando).
static void serialReadCommands() {
  static char buf[24];
  static size_t len = 0;
  while (Serial.available() > 0) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (len) handleCommand(buf, len);
      len = 0;
    } else if (len < sizeof(buf)) {
      buf[len++] = c;
    }
  }
}

// ============================================================================
// 6. SETUP / LOOP
// ============================================================================

static void ledUpdate(uint32_t now) {
  static int last = -1;
  int on = g_connected.load() ? 1 : (int)((now / 250) & 1);  // pisca 2 Hz anunciando
  if (on != last) {
    digitalWrite(LED_PIN, on ? HIGH : LOW);
    last = on;
  }
}

void setup() {
#if !ARDUINO_USB_CDC_ON_BOOT
  Serial.setTxBufferSize(2048);  // UART: buffer de TX (antes do begin)
#endif
  Serial.begin(SERIAL_BAUD);
  pinMode(LED_PIN, OUTPUT);

  bleSetup();
  samplingSetup();
}

void loop() {
  uint32_t now = millis();

  applyPendingFs();
  blePeriodic(now);

  bool canStream = bleCanStream();
  Sample s;
  for (int budget = 256; budget > 0 && ringPop(s); budget--) {
    serialFeed(s);        // Serial: sempre
    bleFeed(s, canStream);  // BLE: só conectado + inscrito + "start"
  }

  serialPeriodic(now);
  serialReadCommands();
  ledUpdate(now);

  delay(1);  // cede CPU (evita WDT do idle no C3); o ring absorve o atraso
}
