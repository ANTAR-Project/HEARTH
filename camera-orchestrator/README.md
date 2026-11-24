# Home Assistant Modular Monolith (Spring Boot Backend)

> 📖 **Comprehensive System Documentation**: See [PROJECT_DOCUMENTATION.md](file:///d:/AN_TAR/ELEMENTS/Prototype/PROJECT_DOCUMENTATION.md) for the complete end-to-end technical manual, network topology, protocol adapters, camera media pipelines, TrueNAS integration architecture, and operational runbook.

A high-performance **modular monolith** Home Assistant clone backend built with Java 21+, Spring Boot 3.4, Spring Integration MQTT, Caffeine in-memory state caching, PostgreSQL/TimescaleDB time-series persistence, and real-time WebSocket state streaming.

---

## 🏗️ Architecture Overview

The backend runs as a single unified JVM process without unnecessary microservice overhead:

```
┌─────────────────────────────────────────────────────────────┐
│                 Spring Boot Modular Monolith                │
│                                                             │
│  ┌───────────────────────┐        ┌──────────────────────┐  │
│  │      api-gateway      │◄──────►│    React Frontend    │  │
│  │ (REST + /ws/states)   │        │     (VLAN 40)        │  │
│  └───────────┬───────────┘        └──────────────────────┘  │
│              │                                              │
│  ┌───────────▼───────────┐        ┌──────────────────────┐  │
│  │   service-dispatcher  │───────►│     mqtt-adapter     │  │
│  └───────────────────────┘        └──────────┬───────────┘  │
│              │                               │ MQTT         │
│  ┌───────────▼───────────┐                   ▼              │
│  │      state-engine     │        ┌──────────────────────┐  │
│  │ (Caffeine Cache + DB) │        │   Mosquitto Broker   │  │
│  └───────────┬───────────┘        │     (VLAN 10)        │  │
│              │                    └──────────┬───────────┘  │
│  ┌───────────▼───────────┐                   │              │
│  │   automation-engine   │                   ▼              │
│  │  (Event-driven rules) │        ┌──────────────────────┐  │
│  └───────────────────────┘        │ ESP32 / Relays / Sens│  │
│              │                    │     (VLAN 30)        │  │
│  ┌───────────▼───────────┐        └──────────────────────┘  │
│  │ PostgreSQL/Timescale  │                                  │
│  └───────────────────────┘                                  │
└─────────────────────────────────────────────────────────────┘
```

---

## 🚀 Quick Start

### Option A: Run Fully Containerized Unified Stack (Recommended)
Builds and starts the complete unified stack — including the Spring Boot backend, PostgreSQL/TimescaleDB, Mosquitto, go2rtc, and the entire TrueNAS Orchestrator video pipeline (Redis, Kafka, Zookeeper, nas-orchestrator, video-service, encoding-service, streaming-service) — on a single shared Docker network (`antar-network`):

```bash
docker compose up -d
```
*To stop the entire stack:*
```bash
docker compose down
```
Access the dashboard at `http://localhost:8080/dashboard`.

### Option B: Run Backend Locally (Hybrid Development)
1. Start infrastructure containers in Docker:
   ```bash
   docker compose up -d postgres mosquitto camera-media-service
   ```
2. Run Spring Boot application with Maven:
   ```bash
   ./mvnw spring-boot:run
   ```
   *(Or `mvn spring-boot:run`)*

By default, the `dev` profile uses in-memory H2 with PostgreSQL syntax and pre-seeded demo entities for instant offline development. Set `SPRING_PROFILES_ACTIVE=default` to connect directly to PostgreSQL/TimescaleDB on port 5432.

---

## 🧪 Testing with Simulated ESP32 Devices

Run the Python simulator to simulate physical relays and sensors communicating via MQTT:

```bash
# Install paho-mqtt
pip install paho-mqtt

# Simulate an ESP32 relay (light.living_room)
python simulate_device.py --device esp32_relay1 --type relay

# In another terminal: Simulate a temperature/humidity sensor
python simulate_device.py --device esp32_sensor1 --type sensor
```

---

## 🖥️ Web Dashboards

- **IoT Hub**: `http://localhost:8080/iot` (or `/`): Dedicated dashboard for physical IoT hardware. Features live CP PLUS camera streaming (low-latency WebRTC WHEP & HLS), PTZ controls, snapshot refresh, on-demand TrueNAS recording trigger, and real-time WebSocket telemetry.
- **TrueNAS Vault**: `http://localhost:8080/nas`: Dedicated storage manager for TrueNAS SCALE SMB shares. Contains separate directories for `camera` (surveillance clips) and `media` (user files, images, PDFs, videos). Supports drag-and-drop upload, inline previews (lightbox/PDF reader), adaptive HLS video streaming, attachment downloads, and remote deletion.
- **Protocol Adapters**: `http://localhost:8080/adapters`: Multi-protocol monitoring for MQTT, Bluetooth Low Energy (BLE), and Zigbee mesh adapters.

---

## 🔌 API Reference

### REST Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/states` | List all current entity states |
| `GET` | `/api/states/{entityId}` | Get single entity state |
| `GET` | `/api/devices` | List registered physical IoT devices |
| `GET` | `/api/camera/recordings` | List surveillance video recordings in TrueNAS |
| `DELETE` | `/api/camera/recordings/{recordingId}` | Delete surveillance clip from TrueNAS SMB |
| `GET` | `/api/nas/files?folder={media\|camera}` | List files/folders in TrueNAS Vault |
| `POST` | `/api/nas/upload` | Upload file to TrueNAS Vault (optional autoEncode) |
| `DELETE` | `/api/nas/files?path={path}` | Delete file from TrueNAS SMB vault |
| `GET` | `/api/nas/preview?path={path}` | Inline file preview (images, PDF, video, audio) |
| `GET` | `/api/nas/download?path={path}` | Download file as attachment from TrueNAS |
| `POST` | `/api/services/{domain}/{service}` | Call domain service (`camera/record_clip`, `light/turn_on`) |
| `GET` | `/api/automations` | List automation rules |
| `PUT` | `/api/automations/{id}/toggle` | Enable/disable automation rule |
| `GET` | `/api/adapters` | Protocol adapters overview (MQTT, BLE, Zigbee) |
| `GET` | `/api/adapters/ble/devices` | List discovered BLE devices |
| `POST` | `/api/adapters/ble/advertisement` | Ingest BLE advertisement/beacon payload |
| `GET` | `/api/adapters/zigbee/devices` | List paired Zigbee mesh nodes |
| `POST` | `/api/adapters/zigbee/report` | Ingest Zigbee ZCL attribute report |
| `POST` | `/api/adapters/zigbee/permit-join` | Toggle Zigbee network pairing mode |

#### Example Service Calls
```bash
# Call MQTT Light
curl -X POST http://localhost:8080/api/services/light/turn_on \
  -H "Content-Type: application/json" \
  -d '{"entity_id": "light.living_room"}'

# Call BLE Light
curl -X POST http://localhost:8080/api/services/light/turn_on \
  -H "Content-Type: application/json" \
  -d '{"entity_id": "light.porch_ble_bulb"}'

# Call Zigbee Smart Plug
curl -X POST http://localhost:8080/api/services/switch/toggle \
  -H "Content-Type: application/json" \
  -d '{"entity_id": "switch.living_room_zigbee_plug"}'
```

### Multi-Protocol Device Simulator
```bash
# Simulate an ESP32 relay (MQTT)
python simulate_device.py --protocol mqtt --device esp32_relay1 --type relay

# Simulate a BLE Temperature/Humidity sensor beacon
python simulate_device.py --protocol ble --device ble_sensor_01 --type sensor

# Simulate a Zigbee smart plug or motion sensor
python simulate_device.py --protocol zigbee --device zigbee_motion_01 --type motion
```

### Real-Time WebSocket
Connect to `ws://localhost:8080/ws/states`:
- **On Handshake**: Receives full snapshot `{ "type": "snapshot", "states": [...] }`
- **On State Change**: Broadcasts delta `{ "type": "state_changed", "event": { "entity_id": "light.living_room", "old_state": "OFF", "new_state": "ON" } }`
- **Call Service via WebSocket**:
  ```json
  {
    "type": "call_service",
    "domain": "light",
    "service": "turn_on",
    "entity_id": "light.living_room"
  }
  ```

---

## 📂 Project Structure

```
d:/AN_TAR/ELEMENTS/Prototype/
├── docker-compose.yml              # TimescaleDB, Mosquitto, go2rtc stack
├── mosquitto/config/               # Mosquitto configs & ACL definitions
├── simulate_device.py              # Python IoT device simulator (MQTT, BLE, Zigbee)
├── pom.xml                         # Spring Boot 3.4.x configuration
└── src/
    ├── main/
    │   ├── java/com/homeapp/
    │   │   ├── HomeAssistantApplication.java
    │   │   ├── config/             # CacheConfig, MqttConfig, BleConfig, ZigbeeConfig, WebSocketConfig
    │   │   ├── domain/             # JPA Entities (Area, Device, EntityState, History, Rules) & DTOs
    │   │   ├── stateengine/        # Caffeine cache + DB persistence + StateChangedEvent
    │   │   ├── dispatcher/         # ProtocolAdapter SPI & ServiceDispatcher (routes MQTT, BLE, Zigbee)
    │   │   ├── mqttadapter/        # MqttInboundHandler & MqttOutboundService
    │   │   ├── bleadapter/         # BleInboundHandler, BleOutboundService, BleAdapterService
    │   │   ├── zigbeeadapter/      # ZigbeeInboundHandler, ZigbeeOutboundService, ZigbeeAdapterService
    │   │   ├── automation/         # AutomationEngineService (event-driven rule evaluator)
    │   │   └── gateway/            # StateController, ServiceController, AdapterController, StateWebSocketHandler
    │   └── resources/
    │       ├── application.yml     # Application configuration
    │       ├── application-dev.yml # Standalone dev profile
    │       └── db/migration/       # Flyway database migrations (V1 schema, V2 seed, V3 BLE/Zigbee seed)
    └── test/                       # Comprehensive Spring Boot, BLE, Zigbee & Integration tests
```
