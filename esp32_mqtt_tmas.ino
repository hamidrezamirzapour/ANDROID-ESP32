/*
 * TMAS - ESP32 MQTT Publisher for MicroTesla Dashboard
 * Sends Temperature Data to HiveMQ Public Broker
 * Topic: microtesla/tmas/data
 * Payload JSON: {"s1": 25.10, "s2": 24.80, "s3": 26.20}
 */

#include <WiFi.h>
#include <PubSubClient.h>

const char* ssid = "YOUR_WIFI_SSID";
const char* password = "YOUR_WIFI_PASSWORD";

const char* mqtt_server = "broker.hivemq.com";
const int mqtt_port = 1883;
const char* mqtt_topic = "microtesla/tmas/data";

WiFiClient espClient;
PubSubClient client(espClient);

unsigned long lastMsg = 0;

void setup_wifi() {
  delay(100);
  Serial.print("Connecting to WiFi: ");
  Serial.println(ssid);
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) { delay(500); Serial.print("."); }
  Serial.println("\nWiFi Connected! Local IP: ");
  Serial.println(WiFi.localIP());
}

void reconnect() {
  while (!client.connected()) {
    Serial.print("Attempting MQTT connection...");
    String clientId = "ESP32_TMAS_" + String(random(0xffff), HEX);
    if (client.connect(clientId.c_str())) {
      Serial.println("connected!");
    } else {
      Serial.print("failed, rc=");
      Serial.print(client.state());
      Serial.println(" retrying in 2 seconds...");
      delay(2000);
    }
  }
}

void setup() {
  Serial.begin(115200);
  setup_wifi();
  client.setServer(mqtt_server, mqtt_port);
}

void loop() {
  if (!client.connected()) { reconnect(); }
  client.loop();
  unsigned long now = millis();
  if (now - lastMsg > 1000) {
    lastMsg = now;
    float s1 = 25.00 + ((random(-50, 50)) / 100.0);
    float s2 = 24.50 + ((random(-40, 40)) / 100.0);
    float s3 = 26.10 + ((random(-60, 60)) / 100.0);
    char payload[128];
    snprintf(payload, sizeof(payload), "{\"s1\":%.2f,\"s2\":%.2f,\"s3\":%.2f}", s1, s2, s3);
    Serial.print("Publishing: ");
    Serial.println(payload);
    client.publish(mqtt_topic, payload);
  }
}
