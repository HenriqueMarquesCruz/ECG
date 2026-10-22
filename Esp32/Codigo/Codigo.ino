#include <Arduino.h>
#include "esp_timer.h"

// ButterWorth Band Pass Filter
// Filter Order: 2.0
// Filter pass frequency 1 (-3dB): 0.4Hz
// Filter pass frequency 2 (-3dB): 150.0Hz
// Filter sampling frequency: 450.0Hz

const int analogInputPin = 34;  // ADC input do ESP32
const int pinCallback = 8;

volatile int g_adcSample = 0;
volatile int g_callBack = 0;

// Período de amostragem: 1/450 s = 2222 us
const uint64_t g_timer = 2222;

esp_timer_handle_t timer;

//******************* Filter Coefficients *******************//

const byte ecgModuleFilter_a_bytes[16] =
{
    0xc,0x70,0x1e,0x3f,
    0x5c,0x92,0x76,0x3e,
    0x2f,0xfd,0xfe,0xbf,
    0x69,0xfc,0x7d,0x3f
};

const float* ecgModuleFilter_a =
    (float*) ecgModuleFilter_a_bytes;


const byte ecgModuleFilter_b_bytes[24] =
{
    0xa9,0x19,0xed,0x3e,
    0xa9,0x19,0x6d,0x3f,
    0xa9,0x19,0xed,0x3e,
    0x0,0x0,0x80,0x3f,
    0x0,0x0,0x0,0xc0,
    0x0,0x0,0x80,0x3f
};

const float* ecgModuleFilter_b =
    (float*) ecgModuleFilter_b_bytes;


//******************* Delay Variables *******************//

float ecgModuleFilter_w[4] = {0,0,0,0};


//******************* Filter Update *******************//

float ecgModuleFilter_Update(float x)
{
    float y;
    float w;

    // Section 0

    w = x
        - ecgModuleFilter_w[0] * ecgModuleFilter_a[0]
        - ecgModuleFilter_w[1] * ecgModuleFilter_a[1];

    y = w * ecgModuleFilter_b[0]
        + ecgModuleFilter_w[0] * ecgModuleFilter_b[1]
        + ecgModuleFilter_w[1] * ecgModuleFilter_b[2];

    ecgModuleFilter_w[1] = ecgModuleFilter_w[0];
    ecgModuleFilter_w[0] = w;


    // Section 1

    w = y
        - ecgModuleFilter_w[2] * ecgModuleFilter_a[2]
        - ecgModuleFilter_w[3] * ecgModuleFilter_a[3];

    y = w * ecgModuleFilter_b[3]
        + ecgModuleFilter_w[2] * ecgModuleFilter_b[4]
        + ecgModuleFilter_w[3] * ecgModuleFilter_b[5];

    ecgModuleFilter_w[3] = ecgModuleFilter_w[2];
    ecgModuleFilter_w[2] = w;

    return y;
}


//******************* Timer Callback *******************//

void sampleADC(void* arg)
{
    g_adcSample = analogRead(analogInputPin);

    g_callBack = digitalRead(pinCallback);
}


//******************* Setup *******************//

void setup()
{
    Serial.begin(115200);

    pinMode(analogInputPin, INPUT);
    pinMode(pinCallback, INPUT);

    pinMode(10, INPUT);
    pinMode(11, INPUT);


    // Cria timer do ESP32
    esp_timer_create_args_t timer_args = {};

    timer_args.callback = &sampleADC;
    timer_args.name = "ECG_Sampling";

    esp_timer_create(&timer_args, &timer);

    // Inicia timer a cada 2222 us
    esp_timer_start_periodic(timer, g_timer);
}


//******************* Loop *******************//

float filtrdSignal = 0.0f;

void loop()
{
    int sample;

    // Copia o valor adquirido
    noInterrupts();
    sample = g_adcSample;
    interrupts();

    // Filtra o sinal
    filtrdSignal = ecgModuleFilter_Update((float)sample);

    // Envia para o Serial Plotter
    Serial.println(filtrdSignal);
}void setup() {
  // put your setup code here, to run once:

}

void loop() {
  // put your main code here, to run repeatedly:

}
