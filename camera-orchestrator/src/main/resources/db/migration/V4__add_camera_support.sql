-- Phase 4: Seed CP Plus Camera Device, Entities, and Cross-Protocol Automation

-- Camera Device (CP Plus E28A Wi-Fi Camera on IoT VLAN 30)
INSERT INTO devices (id, name, area_id, protocol, ip_address, firmware_version, is_online) VALUES
    ('cpplus_front_cam', 'CP Plus E28A Wi-Fi Camera', 'front_porch', 'CAMERA', '192.168.30.247', 'CP-EzyKam-v2.1', true);

-- Entities for Camera
INSERT INTO entities (id, name, device_id, area_id, domain, current_state, attributes_json) VALUES
    ('camera.front_porch_cam', 'Front Porch Camera', 'cpplus_front_cam', 'front_porch', 'camera', 'idle',
     '{"protocol":"CAMERA","snapshot_url":"/api/camera/camera.front_porch_cam/snapshot","stream_url":"rtsp://admin:***@192.168.30.247:5543/live/channel0","resolution":"1080p"}'),
    ('binary_sensor.front_porch_cam_motion', 'Front Porch Camera Motion', 'cpplus_front_cam', 'front_porch', 'binary_sensor', 'OFF',
     '{"protocol":"CAMERA","device_class":"motion"}');

-- State History for initial camera states
INSERT INTO state_history (entity_id, state, attributes_json) VALUES
    ('camera.front_porch_cam', 'idle', '{"protocol":"CAMERA","snapshot_url":"/api/camera/camera.front_porch_cam/snapshot"}'),
    ('binary_sensor.front_porch_cam_motion', 'OFF', '{"protocol":"CAMERA","device_class":"motion"}');

-- Cross-Protocol Automation: When CP Plus Camera detects motion on IoT VLAN, trigger BLE Porch Bulb ON
INSERT INTO automation_rules (id, name, description, is_enabled, trigger_entity_id, trigger_state, target_domain, target_service, target_entity_id, action_payload_json) VALUES
    ('rule_camera_motion_porch_light', 'Camera Motion Activates Porch Light', 'Turns on BLE Porch Light when CP Plus Camera detects motion on IoT VLAN', true, 'binary_sensor.front_porch_cam_motion', 'ON', 'light', 'turn_on', 'light.porch_ble_bulb', '{"brightness":100}');
