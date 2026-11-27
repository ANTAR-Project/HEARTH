-- Phase 5: Clean up all simulated dummy devices and mock entities
-- Keep ONLY real hardware: CP Plus Camera (cpplus_front_cam) and its real sensors

-- 1. Remove mock automation rules
DELETE FROM automation_rules WHERE id IN ('rule_auto_fan', 'rule_zigbee_motion_light', 'rule_camera_motion_porch_light');

-- 2. Remove mock state history
DELETE FROM state_history WHERE entity_id NOT IN ('camera.front_porch_cam', 'binary_sensor.front_porch_cam_motion');

-- 3. Remove mock entities
DELETE FROM entities WHERE id NOT IN ('camera.front_porch_cam', 'binary_sensor.front_porch_cam_motion');

-- 4. Remove mock devices
DELETE FROM devices WHERE id != 'cpplus_front_cam';

-- 5. Add real production automation rule: Camera Motion Triggers TrueNAS Recording
INSERT INTO automation_rules (id, name, description, is_enabled, trigger_entity_id, trigger_state, target_domain, target_service, target_entity_id, action_payload_json)
VALUES (
    'rule_motion_truenas_recording',
    'Motion-Triggered TrueNAS Surveillance Recording',
    'Automatically records a 15-second clip to TrueNAS SMB storage when camera motion is detected',
    true,
    'binary_sensor.front_porch_cam_motion',
    'ON',
    'camera',
    'record',
    'camera.front_porch_cam',
    '{"duration": 15}'
) ON CONFLICT (id) DO NOTHING;
