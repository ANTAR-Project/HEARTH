#!/usr/bin/env python3
"""
Simulated IoT Device for Home Assistant Clone.
Supports Native MQTT, Zigbee2MQTT over MQTT, and BLE Proxy over MQTT.
- MQTT (ESP32): Subscribes to 'devices/{DEVICE_ID}/set', publishes to 'devices/{DEVICE_ID}/telemetry'.
- Zigbee (Zigbee2MQTT): Subscribes to 'zigbee2mqtt/{DEVICE_ID}/set', publishes to 'zigbee2mqtt/{DEVICE_ID}'.
- BLE (BLE Proxy): Subscribes to 'ble/{DEVICE_ID}/write', publishes telemetry to 'ble/{DEVICE_ID}/telemetry'.
"""

import argparse
import json
import os
import random
import sys
import threading
import time


def create_mqtt_client(client_id):
    try:
        import paho.mqtt.client as mqtt
        try:
            return mqtt.Client(
                mqtt.CallbackAPIVersion.VERSION2,
                client_id=client_id,
                protocol=mqtt.MQTTv311
            )
        except AttributeError:
            return mqtt.Client(
                client_id=client_id,
                protocol=mqtt.MQTTv311
            )
    except ImportError:
        print("Error: paho-mqtt not installed. Run: pip install paho-mqtt")
        sys.exit(1)


# --- MQTT (ESP32) Simulator ---
class MqttDeviceSimulator:
    def __init__(self, host, port, device_id, username, password, simulated_type="relay"):
        self.host = host
        self.port = port
        self.device_id = device_id
        self.username = username
        self.password = password
        self.simulated_type = simulated_type

        self.set_topic = f"devices/{device_id}/set"
        self.telemetry_topic = f"devices/{device_id}/telemetry"
        self.status_topic = f"devices/{device_id}/status"
        self.state = "OFF"
        self.temperature = 22.5
        self.humidity = 55.0
        self.running = True

        self.client = create_mqtt_client(f"sim_mqtt_{device_id}_{int(time.time())}")
        if username and password:
            self.client.username_pw_set(username, password)

        self.client.will_set(topic=self.status_topic, payload="offline", qos=1, retain=True)
        self.client.on_connect = self.on_connect
        self.client.on_message = self.on_message
        self.client.on_disconnect = self.on_disconnect

    def on_connect(self, client, userdata, flags, reason_code, properties=None):
        rc = reason_code if isinstance(reason_code, int) else getattr(reason_code, "value", 0)
        if rc == 0:
            print(f"[{self.device_id}] (MQTT) Connected to broker at {self.host}:{self.port}")
            self.client.publish(self.status_topic, payload="online", qos=1, retain=True)
            client.subscribe(self.set_topic)
            self.publish_telemetry()
        else:
            print(f"[{self.device_id}] Failed to connect, return code: {rc}")

    def on_disconnect(self, client, userdata, flags, reason_code, properties=None):
        print(f"[{self.device_id}] Disconnected from MQTT broker (reason: {reason_code})")

    def on_message(self, client, userdata, msg):
        try:
            payload_str = msg.payload.decode("utf-8")
            print(f"\n[{self.device_id}] Command received on {msg.topic}: {payload_str}")
            payload = json.loads(payload_str)
        except Exception as e:
            print(f"[{self.device_id}] Error decoding payload: {e}")
            return

        requested_state = payload.get("state")
        if requested_state in ("ON", "OFF", "TOGGLE"):
            if requested_state == "TOGGLE":
                self.state = "OFF" if self.state == "ON" else "ON"
            else:
                self.state = requested_state

            print(f"[{self.device_id}] >>> GPIO RELAY TOGGLED -> {self.state} <<<")
            time.sleep(0.15)
            self.publish_telemetry(attributes=payload.get("attributes"))

    def publish_telemetry(self, attributes=None):
        if self.simulated_type == "relay":
            entity_id = f"light.{self.device_id}" if "relay" in self.device_id else f"switch.{self.device_id}"
            payload = {
                "device_id": self.device_id,
                "entity_id": entity_id,
                "domain": "light" if "light" in entity_id else "switch",
                "state": self.state,
                "attributes": {
                    "simulated": True,
                    "firmware": "esphome-v2024.6.0",
                    **(attributes or {})
                }
            }
        elif self.simulated_type == "sensor":
            self.temperature += round(random.uniform(-0.3, 0.3), 1)
            self.humidity += round(random.uniform(-0.8, 0.8), 1)
            payload = {
                "device_id": self.device_id,
                "entity_id": f"sensor.{self.device_id}_climate",
                "domain": "sensor",
                "state": f"{self.temperature:.1f}",
                "attributes": {
                    "temperature": round(self.temperature, 1),
                    "humidity": round(self.humidity, 1),
                    "unit_of_measurement": "°C",
                    "simulated": True
                }
            }
        else:
            payload = {
                "device_id": self.device_id,
                "state": self.state,
                "attributes": {"simulated": True}
            }

        msg_bytes = json.dumps(payload).encode("utf-8")
        self.client.publish(self.telemetry_topic, msg_bytes, qos=1)
        print(f"[{self.device_id}] Published telemetry -> {self.telemetry_topic}: {json.dumps(payload)}")

    def start(self):
        print(f"[{self.device_id}] Connecting to {self.host}:{self.port}...")
        self.client.connect(self.host, self.port, keepalive=60)
        self.client.loop_start()

        if self.simulated_type == "sensor":
            def sensor_loop():
                while self.running:
                    time.sleep(10)
                    if self.running:
                        self.publish_telemetry()
            t = threading.Thread(target=sensor_loop, daemon=True)
            t.start()

    def stop(self, graceful=True):
        self.running = False
        if graceful:
            try:
                self.client.publish(self.status_topic, payload="offline", qos=1, retain=True)
                time.sleep(0.1)
            except Exception:
                pass
        self.client.loop_stop()
        self.client.disconnect()


# --- Zigbee2MQTT Simulator ---
class Zigbee2MqttDeviceSimulator:
    def __init__(self, host, port, device_id, username=None, password=None, simulated_type="relay"):
        self.host = host
        self.port = port
        self.device_id = device_id
        self.username = username
        self.password = password
        self.simulated_type = simulated_type

        self.set_topic = f"zigbee2mqtt/{device_id}/set"
        self.state_topic = f"zigbee2mqtt/{device_id}"
        self.avail_topic = f"zigbee2mqtt/{device_id}/availability"
        self.state = "OFF"
        self.brightness = 254
        self.temperature = 22.0
        self.humidity = 50.0
        self.linkquality = 135
        self.battery = 88
        self.running = True

        self.client = create_mqtt_client(f"sim_z2m_{device_id}_{int(time.time())}")
        if username and password:
            self.client.username_pw_set(username, password)

        self.client.will_set(topic=self.avail_topic, payload="offline", qos=1, retain=True)
        self.client.on_connect = self.on_connect
        self.client.on_message = self.on_message
        self.client.on_disconnect = self.on_disconnect

    def on_connect(self, client, userdata, flags, reason_code, properties=None):
        rc = reason_code if isinstance(reason_code, int) else getattr(reason_code, "value", 0)
        if rc == 0:
            print(f"[{self.device_id}] (Zigbee2MQTT) Connected to broker at {self.host}:{self.port}")
            self.client.publish(self.avail_topic, payload="online", qos=1, retain=True)
            client.subscribe(self.set_topic)
            print(f"[{self.device_id}] (Zigbee2MQTT) Subscribed to {self.set_topic}")
            self.publish_state()
        else:
            print(f"[{self.device_id}] Failed to connect, return code: {rc}")

    def on_disconnect(self, client, userdata, flags, reason_code, properties=None):
        print(f"[{self.device_id}] Disconnected from broker (reason: {reason_code})")

    def on_message(self, client, userdata, msg):
        try:
            payload_str = msg.payload.decode("utf-8")
            print(f"\n[{self.device_id}] (Zigbee2MQTT) Command received on {msg.topic}: {payload_str}")
            payload = json.loads(payload_str)
        except Exception as e:
            print(f"[{self.device_id}] Error decoding payload: {e}")
            return

        if "state" in payload:
            req = payload["state"].upper()
            if req == "TOGGLE":
                self.state = "OFF" if self.state == "ON" else "ON"
            elif req in ("ON", "OFF"):
                self.state = req

        if "brightness" in payload:
            self.brightness = int(payload["brightness"])
            if self.brightness > 0:
                self.state = "ON"

        print(f"[{self.device_id}] (Zigbee2MQTT) >>> State updated to: {self.state} (brightness={self.brightness}) <<<")
        time.sleep(0.1)
        self.publish_state()

    def publish_state(self):
        payload = {
            "linkquality": self.linkquality,
            "battery": self.battery
        }

        if self.simulated_type == "relay":
            payload["state"] = self.state
            if self.brightness is not None:
                payload["brightness"] = self.brightness
        elif self.simulated_type == "sensor":
            self.temperature += round(random.uniform(-0.2, 0.2), 1)
            self.humidity += round(random.uniform(-0.5, 0.5), 1)
            payload["temperature"] = round(self.temperature, 1)
            payload["humidity"] = round(self.humidity, 1)
        elif self.simulated_type == "motion":
            payload["occupancy"] = random.choice([True, False])

        msg_bytes = json.dumps(payload).encode("utf-8")
        self.client.publish(self.state_topic, msg_bytes, qos=1)
        print(f"[{self.device_id}] (Zigbee2MQTT) Published state -> {self.state_topic}: {json.dumps(payload)}")

    def start(self):
        print(f"[{self.device_id}] Starting Zigbee2MQTT simulation...")
        self.client.connect(self.host, self.port, keepalive=60)
        self.client.loop_start()

        if self.simulated_type in ("sensor", "motion"):
            def loop():
                while self.running:
                    time.sleep(8)
                    if self.running:
                        self.publish_state()
            t = threading.Thread(target=loop, daemon=True)
            t.start()

    def stop(self, graceful=True):
        self.running = False
        if graceful:
            try:
                self.client.publish(self.avail_topic, payload="offline", qos=1, retain=True)
            except Exception:
                pass
        self.client.loop_stop()
        self.client.disconnect()


# --- BLE Proxy Simulator ---
class BleProxyDeviceSimulator:
    def __init__(self, host, port, device_id, username=None, password=None, simulated_type="sensor"):
        self.host = host
        self.port = port
        self.device_id = device_id
        self.username = username
        self.password = password
        self.simulated_type = simulated_type

        self.write_topic = f"ble/{device_id}/write"
        self.telemetry_topic = f"ble/{device_id}/telemetry"
        self.status_topic = f"ble/{device_id}/status"
        self.mac_address = "A4:C1:38:99:88:77"
        self.temperature = 21.0
        self.humidity = 48.0
        self.state = "OFF"
        self.battery = 94
        self.running = True

        self.client = create_mqtt_client(f"sim_ble_proxy_{device_id}_{int(time.time())}")
        if username and password:
            self.client.username_pw_set(username, password)

        self.client.will_set(topic=self.status_topic, payload="offline", qos=1, retain=True)
        self.client.on_connect = self.on_connect
        self.client.on_message = self.on_message
        self.client.on_disconnect = self.on_disconnect

    def on_connect(self, client, userdata, flags, reason_code, properties=None):
        rc = reason_code if isinstance(reason_code, int) else getattr(reason_code, "value", 0)
        if rc == 0:
            print(f"[{self.device_id}] (BLE Proxy) Connected to broker at {self.host}:{self.port}")
            self.client.publish(self.status_topic, payload="online", qos=1, retain=True)
            client.subscribe(self.write_topic)
            print(f"[{self.device_id}] (BLE Proxy) Subscribed to {self.write_topic}")
            self.publish_telemetry()
        else:
            print(f"[{self.device_id}] Failed to connect, return code: {rc}")

    def on_disconnect(self, client, userdata, flags, reason_code, properties=None):
        print(f"[{self.device_id}] Disconnected from broker (reason: {reason_code})")

    def on_message(self, client, userdata, msg):
        try:
            payload_str = msg.payload.decode("utf-8")
            print(f"\n[{self.device_id}] (BLE Proxy) Write command received on {msg.topic}: {payload_str}")
            payload = json.loads(payload_str)
        except Exception as e:
            print(f"[{self.device_id}] Error decoding payload: {e}")
            return

        # Perform simulated GATT write
        requested_state = payload.get("state")
        if requested_state:
            if requested_state.upper() == "TOGGLE":
                self.state = "OFF" if self.state == "ON" else "ON"
            else:
                self.state = requested_state.upper()

        print(f"[{self.device_id}] (BLE Proxy) >>> Executed GATT write to {payload.get('characteristic_uuid')} -> State: {self.state} <<<")
        time.sleep(0.1)
        # Notify backend of new verified state
        self.publish_telemetry()

    def publish_telemetry(self):
        if self.simulated_type == "sensor":
            self.temperature += round(random.uniform(-0.2, 0.2), 1)
            self.humidity += round(random.uniform(-0.5, 0.5), 1)
            payload = {
                "device_id": self.device_id,
                "mac_address": self.mac_address,
                "device_name": f"BLE Sensor {self.device_id}",
                "rssi": random.randint(-70, -58),
                "battery": self.battery,
                "data": {
                    "temperature": round(self.temperature, 1),
                    "humidity": round(self.humidity, 1)
                }
            }
        else:
            payload = {
                "device_id": self.device_id,
                "mac_address": self.mac_address,
                "device_name": f"BLE Bulb {self.device_id}",
                "rssi": -62,
                "state": self.state,
                "domain": "light",
                "battery": 100
            }

        msg_bytes = json.dumps(payload).encode("utf-8")
        self.client.publish(self.telemetry_topic, msg_bytes, qos=1)
        print(f"[{self.device_id}] (BLE Proxy) Published telemetry -> {self.telemetry_topic}: {json.dumps(payload)}")

    def start(self):
        print(f"[{self.device_id}] Starting BLE Proxy simulation...")
        self.client.connect(self.host, self.port, keepalive=60)
        self.client.loop_start()

        if self.simulated_type == "sensor":
            def loop():
                while self.running:
                    time.sleep(8)
                    if self.running:
                        self.publish_telemetry()
            t = threading.Thread(target=loop, daemon=True)
            t.start()

    def stop(self, graceful=True):
        self.running = False
        if graceful:
            try:
                self.client.publish(self.status_topic, payload="offline", qos=1, retain=True)
            except Exception:
                pass
        self.client.loop_stop()
        self.client.disconnect()


def main():
    parser = argparse.ArgumentParser(description="Simulate IoT devices over Native MQTT, Zigbee2MQTT, or BLE Proxy")
    parser.add_argument("--protocol", choices=["mqtt", "ble", "zigbee"], default="mqtt", help="Protocol to simulate")
    parser.add_argument("--host", default="localhost", help="MQTT Broker host (default: localhost)")
    parser.add_argument("--port", type=int, default=1883, help="MQTT Broker port (default: 1883)")
    parser.add_argument("--device", default=None, help="Device ID")
    parser.add_argument("--user", default=None, help="MQTT Username")
    parser.add_argument("--password", default=None, help="MQTT Password")
    parser.add_argument("--type", choices=["relay", "sensor", "motion"], default="relay", help="Device type")
    parser.add_argument("--simulate-crash-after", type=int, default=0, help="Simulate sudden crash after N seconds (MQTT)")

    args = parser.parse_args()

    user = args.user if args.user else (args.device if args.device else "ha_app")
    password = args.password if args.password else "changeme"

    if args.protocol == "mqtt":
        device_id = args.device if args.device else "esp32_relay1"
        sim = MqttDeviceSimulator(args.host, args.port, device_id, user, password, simulated_type=args.type)
    elif args.protocol == "zigbee":
        device_id = args.device if args.device else "zigbee_living_room_plug"
        sim = Zigbee2MqttDeviceSimulator(args.host, args.port, device_id, user, password, simulated_type=args.type)
    elif args.protocol == "ble":
        device_id = args.device if args.device else "ble_porch_bulb"
        sim = BleProxyDeviceSimulator(args.host, args.port, device_id, user, password, simulated_type=args.type)

    sim.start()
    print(f"\n--- Simulator running for '{device_id}' ({args.protocol} - {args.type}). Press Ctrl+C to exit. ---\n")
    try:
        elapsed = 0
        while True:
            time.sleep(1)
            elapsed += 1
            if args.simulate_crash_after > 0 and elapsed >= args.simulate_crash_after:
                print(f"\n⚡ Sudden ungraceful crash simulated for '{device_id}'!")
                os._exit(1)
    except KeyboardInterrupt:
        sim.stop(graceful=True)


if __name__ == "__main__":
    main()
