-- Phase 3: Seed BLE and Zigbee Fleet & Demo Entities

-- BLE Devices
INSERT INTO devices (id, name, area_id, protocol, mac_address, firmware_version, is_online) VALUES
    ('ble_bedroom_climate', 'Primary Bedroom BLE Sensor', 'bedroom_primary', 'BLE', 'A4:C1:38:12:34:56', 'bthome-v2', true),
    ('ble_porch_bulb', 'Front Porch BLE Bulb', 'front_porch', 'BLE', 'A4:C1:38:AA:BB:CC', 'ble-mesh-v1', true);

-- Zigbee Devices
INSERT INTO devices (id, name, area_id, protocol, mac_address, firmware_version, is_online) VALUES
    ('zigbee_living_room_plug', 'Living Room Zigbee Smart Plug', 'living_room', 'ZIGBEE', '00:15:8D:00:01:2A:3B:4C', 'zcl-v3', true),
    ('zigbee_kitchen_motion', 'Kitchen Zigbee Motion Sensor', 'kitchen', 'ZIGBEE', '00:15:8D:00:02:3B:4C:5D', 'zcl-v3', true),
    ('zigbee_accent_light', 'Living Room Zigbee Lamp', 'living_room', 'ZIGBEE', '00:15:8D:00:03:4C:5D:6E', 'zcl-v3', true);

-- Entities for BLE
INSERT INTO entities (id, name, device_id, area_id, domain, current_state, attributes_json) VALUES
    ('sensor.bedroom_ble_temp', 'Bedroom BLE Temperature', 'ble_bedroom_climate', 'bedroom_primary', 'sensor', '21.0', '{"temperature":21.0,"unit_of_measurement":"°C","protocol":"BLE"}'),
    ('sensor.bedroom_ble_humidity', 'Bedroom BLE Humidity', 'ble_bedroom_climate', 'bedroom_primary', 'sensor', '48.0', '{"humidity":48.0,"unit_of_measurement":"%","protocol":"BLE"}'),
    ('light.porch_ble_bulb', 'Front Porch BLE Light', 'ble_porch_bulb', 'front_porch', 'light', 'OFF', '{"protocol":"BLE","friendly_name":"Front Porch BLE Light"}');

-- Entities for Zigbee
INSERT INTO entities (id, name, device_id, area_id, domain, current_state, attributes_json) VALUES
    ('switch.living_room_zigbee_plug', 'Living Room Zigbee Plug', 'zigbee_living_room_plug', 'living_room', 'switch', 'OFF', '{"protocol":"ZIGBEE","friendly_name":"Living Room Zigbee Plug"}'),
    ('binary_sensor.kitchen_zigbee_motion', 'Kitchen Zigbee Motion', 'zigbee_kitchen_motion', 'kitchen', 'binary_sensor', 'OFF', '{"protocol":"ZIGBEE","device_class":"motion"}'),
    ('light.living_room_accent', 'Living Room Zigbee Accent Lamp', 'zigbee_accent_light', 'living_room', 'light', 'OFF', '{"protocol":"ZIGBEE","brightness":254}');

-- State History for new entities
INSERT INTO state_history (entity_id, state, attributes_json) VALUES
    ('sensor.bedroom_ble_temp', '21.0', '{"temperature":21.0,"unit_of_measurement":"°C","protocol":"BLE"}'),
    ('sensor.bedroom_ble_humidity', '48.0', '{"humidity":48.0,"unit_of_measurement":"%","protocol":"BLE"}'),
    ('light.porch_ble_bulb', 'OFF', '{"protocol":"BLE"}'),
    ('switch.living_room_zigbee_plug', 'OFF', '{"protocol":"ZIGBEE"}'),
    ('binary_sensor.kitchen_zigbee_motion', 'OFF', '{"protocol":"ZIGBEE","device_class":"motion"}'),
    ('light.living_room_accent', 'OFF', '{"protocol":"ZIGBEE","brightness":254}');

-- Automation: Motion in kitchen detected by Zigbee sensor turns on main kitchen light
INSERT INTO automation_rules (id, name, description, is_enabled, trigger_entity_id, trigger_state, target_domain, target_service, target_entity_id, action_payload_json) VALUES
    ('rule_zigbee_motion_light', 'Kitchen Zigbee Motion Light', 'Turns on kitchen main light when Zigbee motion is detected', true, 'binary_sensor.kitchen_zigbee_motion', 'ON', 'light', 'turn_on', 'light.kitchen_main', '{"state":"ON"}');
