-- Phase 1 & 2: Initial Seed Data for Demo Fleet

-- Areas
INSERT INTO areas (id, name, floor, icon) VALUES
    ('living_room', 'Living Room', 'Ground Floor', 'sofa'),
    ('kitchen', 'Kitchen', 'Ground Floor', 'utensils'),
    ('bedroom_primary', 'Primary Bedroom', 'First Floor', 'bed'),
    ('front_porch', 'Front Porch', 'Outdoor', 'home');

-- Devices
INSERT INTO devices (id, name, area_id, protocol, ip_address, mqtt_topic_prefix, firmware_version, is_online) VALUES
    ('esp32_relay1', 'Living Room Relay Board', 'living_room', 'MQTT', '192.168.30.21', 'devices/esp32_relay1', 'esphome-v2024.6.0', true),
    ('esp32_sensor1', 'Living Room Climate Monitor', 'living_room', 'MQTT', '192.168.30.22', 'devices/esp32_sensor1', 'esphome-v2024.6.0', true),
    ('esp32_kitchen_switch', 'Kitchen Light Switch', 'kitchen', 'MQTT', '192.168.30.23', 'devices/esp32_kitchen_switch', 'esphome-v2024.6.0', true);

-- Entities
INSERT INTO entities (id, name, device_id, area_id, domain, current_state, attributes_json) VALUES
    ('light.living_room', 'Living Room Ceiling Light', 'esp32_relay1', 'living_room', 'light', 'OFF', '{"friendly_name":"Living Room Light","supported_features":["turn_on","turn_off","toggle"]}'),
    ('switch.living_room_fan', 'Living Room Ceiling Fan', 'esp32_relay1', 'living_room', 'switch', 'OFF', '{"friendly_name":"Living Room Fan","supported_features":["turn_on","turn_off","toggle"]}'),
    ('sensor.living_room_climate', 'Living Room Climate Sensor', 'esp32_sensor1', 'living_room', 'sensor', '22.5', '{"temperature":22.5,"humidity":55.0,"unit_of_measurement":"°C"}'),
    ('light.kitchen_main', 'Kitchen Main Light', 'esp32_kitchen_switch', 'kitchen', 'light', 'OFF', '{"friendly_name":"Kitchen Main Light","supported_features":["turn_on","turn_off","toggle"]}');

-- Initial State History
INSERT INTO state_history (entity_id, state, attributes_json) VALUES
    ('light.living_room', 'OFF', '{"friendly_name":"Living Room Light"}'),
    ('switch.living_room_fan', 'OFF', '{"friendly_name":"Living Room Fan"}'),
    ('sensor.living_room_climate', '22.5', '{"temperature":22.5,"humidity":55.0,"unit_of_measurement":"°C"}'),
    ('light.kitchen_main', 'OFF', '{"friendly_name":"Kitchen Main Light"}');

-- Sample Automation Rule: If living room temperature exceeds 28°C, turn on living room fan
INSERT INTO automation_rules (id, name, description, is_enabled, trigger_entity_id, trigger_state, target_domain, target_service, target_entity_id, action_payload_json) VALUES
    ('rule_auto_fan', 'Auto Turn On Living Room Fan', 'Turns on the ceiling fan when climate sensor changes to high state', true, 'sensor.living_room_climate', '28.0', 'switch', 'turn_on', 'switch.living_room_fan', '{"state":"ON"}');

