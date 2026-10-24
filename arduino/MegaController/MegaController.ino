/*
 * Arduino Mega 2560 - Bluetooth (HC-05) + WiFi (ESP8266 AT) controller
 * کتابخانه لازم: "DHT sensor library" (Adafruit) + "Adafruit Unified Sensor"
 *
 * پروتکل (متنی، هر خط با \n):
 *   Arduino -> App :  DATA;T=25.0;H=40.0;CO=12.3;D=35.2;R=0101
 *   App -> Arduino :  R,1,1   (کانال 1 روشن)   R,1,0 (خاموش)   R,ALL,0   PING
 */
#include <DHT.h>

// ---------- پایه‌ها ----------
#define DHT_PIN   22
#define DHT_TYPE  DHT11
#define TRIG_PIN  24
#define ECHO_PIN  25
#define MQ7_PIN   A0
const uint8_t RELAY_PIN[4] = {30, 31, 32, 33};
const bool RELAY_ACTIVE_LOW = true;   // اکثر ماژول‌های رله 4 کاناله Active-LOW هستند

// ---------- تنظیمات ----------
const char     AP_SSID[]  = "MegaControl";
const char     AP_PASS[]  = "12345678";
const uint16_t TCP_PORT   = 8080;
const unsigned long SEND_MS = 1500;
const float MQ7_RL = 10.0;   // kΩ مقاومت بار روی برد ماژول (معمولاً 10k)
const float MQ7_R0 = 10.0;   // kΩ مقدار Rs در هوای پاک؛ باید کالیبره شود

DHT dht(DHT_PIN, DHT_TYPE);
bool relayOn[4] = {false, false, false, false};
bool wifiClient = false;
String btBuf, wifiBuf;
unsigned long lastSend = 0;

// ---------- رله ----------
void setRelay(uint8_t i, bool on) {
  relayOn[i] = on;
  digitalWrite(RELAY_PIN[i], (on != RELAY_ACTIVE_LOW) ? HIGH : LOW);
}

// ---------- سنسورها ----------
float readDistance() {
  digitalWrite(TRIG_PIN, LOW);  delayMicroseconds(2);
  digitalWrite(TRIG_PIN, HIGH); delayMicroseconds(10);
  digitalWrite(TRIG_PIN, LOW);
  unsigned long d = pulseIn(ECHO_PIN, HIGH, 30000UL);
  if (d == 0) return NAN;
  return d * 0.0343f / 2.0f;
}

float readCO() {
  long sum = 0;
  for (int i = 0; i < 10; i++) { sum += analogRead(MQ7_PIN); delay(2); }
  float raw = sum / 10.0f;
  if (raw < 2) return NAN;
  float v  = raw * 5.0f / 1023.0f;
  float rs = (5.0f - v) / v * MQ7_RL;
  float ratio = rs / MQ7_R0;
  return 99.042f * pow(ratio, -1.518f);   // منحنی تقریبی دیتاشیت MQ-7
}

void addVal(String &s, const char *k, float v, uint8_t dec) {
  s += ';'; s += k; s += '=';
  if (isnan(v)) s += "ERR"; else s += String(v, dec);
}

// ---------- ارسال ----------
void espSend(const String &s) {
  Serial2.print("AT+CIPSEND=0,"); Serial2.println(s.length());
  unsigned long t = millis();
  while (millis() - t < 300) { if (Serial2.available() && Serial2.read() == '>') break; }
  Serial2.print(s);
}

void sendData() {
  String s = "DATA";
  addVal(s, "T",  dht.readTemperature(), 1);
  addVal(s, "H",  dht.readHumidity(), 0);
  addVal(s, "CO", readCO(), 1);
  addVal(s, "D",  readDistance(), 1);
  s += ";R=";
  for (uint8_t i = 0; i < 4; i++) s += relayOn[i] ? '1' : '0';
  Serial1.println(s);                 // بلوتوث
  if (wifiClient) espSend(s + "\n");  // WiFi
  Serial.println(s);                  // مانیتور سریال USB
}

// ---------- دستورات ----------
void handleCommand(String c) {
  c.trim();
  if (c == "PING") { sendData(); return; }
  if (!c.startsWith("R,")) return;
  int comma = c.indexOf(',', 2);
  if (comma < 0) return;
  String ch = c.substring(2, comma);
  bool on = c.substring(comma + 1).toInt() == 1;
  if (ch == "ALL") {
    for (uint8_t i = 0; i < 4; i++) setRelay(i, on);
  } else {
    int n = ch.toInt();
    if (n >= 1 && n <= 4) setRelay(n - 1, on);
  }
  sendData();
}

void handleWifiLine(String l) {
  if (l.startsWith("+IPD,")) {
    wifiClient = true;   // اگر پیام CONNECT را از دست دادیم، با اولین دستور هم متصل حساب می‌شود
    int p = l.indexOf(':');
    if (p > 0) handleCommand(l.substring(p + 1));
  } else if (l.indexOf("CLOSED") >= 0) {
    wifiClient = false;
  } else if (l.indexOf("CONNECT") >= 0) {
    wifiClient = true;
  }
}

void pollBt() {
  while (Serial1.available()) {
    char ch = Serial1.read();
    if (ch == '\n') { handleCommand(btBuf); btBuf = ""; }
    else if (ch != '\r' && btBuf.length() < 64) btBuf += ch;
  }
}

void pollWifi() {
  while (Serial2.available()) {
    char ch = Serial2.read();
    if (ch == '\n') { handleWifiLine(wifiBuf); wifiBuf = ""; }
    else if (ch != '\r' && wifiBuf.length() < 96) wifiBuf += ch;
  }
}

// ---------- ESP8266 ----------
// دستور را می‌فرستد، تا wait میلی‌ثانیه جواب را جمع می‌کند و در Serial Monitor چاپ می‌کند
String espCmd(const String &cmd, unsigned wait = 1500) {
  while (Serial2.available()) Serial2.read();
  Serial2.print(cmd);
  Serial2.print("\r\n");
  String r;
  unsigned long t = millis();
  while (millis() - t < wait) {
    while (Serial2.available() && r.length() < 300) r += (char)Serial2.read();
  }
  Serial.print(F(">> ")); Serial.println(cmd);
  Serial.println(r);
  return r;
}

// باود درست ESP را پیدا می‌کند
bool espDetect() {
  const long bauds[] = {115200, 9600};
  for (uint8_t i = 0; i < 2; i++) {
    Serial2.begin(bauds[i]);
    delay(100);
    String r = espCmd("AT", 800);
    if (r.indexOf("OK") >= 0) {
      Serial.print(F("ESP baud = ")); Serial.println(bauds[i]);
      return true;
    }
    Serial2.end();
  }
  return false;
}

void espStep(const String &cmd, unsigned wait = 1500) {
  String r = espCmd(cmd, wait);
  if (r.indexOf("OK") < 0 && r.indexOf("no change") < 0) {
    Serial.print(F("!!! FAILED: ")); Serial.println(cmd);
  }
}

void espInit() {
  espStep("AT+RST", 4000);
  espStep("AT+CWMODE=2");   // Access Point: گوشی مستقیم به ESP وصل می‌شود
  espStep(String("AT+CWSAP=\"") + AP_SSID + "\",\"" + AP_PASS + "\",5,3", 2500);
  espStep("AT+CIPMUX=1");
  espStep(String("AT+CIPSERVER=1,") + TCP_PORT);
  espCmd("AT+CIFSR", 1000);
}

void setup() {
  for (uint8_t i = 0; i < 4; i++) {          // اول سطح خاموش، بعد خروجی (بدون جرقه)
    digitalWrite(RELAY_PIN[i], RELAY_ACTIVE_LOW ? HIGH : LOW);
    pinMode(RELAY_PIN[i], OUTPUT);
  }
  pinMode(TRIG_PIN, OUTPUT);
  pinMode(ECHO_PIN, INPUT);
  Serial.begin(115200);
  Serial1.begin(9600);      // HC-05  (TX1=18, RX1=19)
  dht.begin();
  Serial.println(F("Starting ESP8266..."));
  if (espDetect()) espInit();   // ESP8266 روی TX2=16, RX2=17
  else Serial.println(F("!!! ESP8266 NOT RESPONDING: wiring / power / TX-RX swap"));
  Serial.println(F("Ready"));
}

void loop() {
  pollBt();
  pollWifi();
  if (millis() - lastSend >= SEND_MS) {
    lastSend = millis();
    sendData();
  }
}
