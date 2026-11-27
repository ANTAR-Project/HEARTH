-- Phase 1: Database Schema Migration for Home Assistant Modular Monolith

CREATE TABLE IF NOT EXISTS areas (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    floor VARCHAR(64),
    icon VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS devices (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    area_id VARCHAR(64) REFERENCES areas(id) ON DELETE SET NULL,
    protocol VARCHAR(32) NOT NULL DEFAULT 'MQTT',
    ip_address VARCHAR(64),
    mac_address VARCHAR(64),
    mqtt_topic_prefix VARCHAR(128),
    firmware_version VARCHAR(64),
    is_online BOOLEAN NOT NULL DEFAULT FALSE,
    last_seen TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS entities (
    id VARCHAR(128) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    device_id VARCHAR(64) REFERENCES devices(id) ON DELETE CASCADE,
    area_id VARCHAR(64) REFERENCES areas(id) ON DELETE SET NULL,
    domain VARCHAR(32) NOT NULL,
    current_state VARCHAR(128) NOT NULL DEFAULT 'unavailable',
    attributes_json TEXT,
    last_changed TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS state_history (
    id BIGSERIAL PRIMARY KEY,
    entity_id VARCHAR(128) NOT NULL,
    state VARCHAR(128) NOT NULL,
    attributes_json TEXT,
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_state_history_entity_time ON state_history (entity_id, recorded_at DESC);
CREATE INDEX IF NOT EXISTS idx_entities_domain ON entities (domain);
CREATE INDEX IF NOT EXISTS idx_entities_area ON entities (area_id);
CREATE INDEX IF NOT EXISTS idx_devices_area ON devices (area_id);

CREATE TABLE IF NOT EXISTS automation_rules (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    trigger_entity_id VARCHAR(128),
    trigger_state VARCHAR(128),
    target_domain VARCHAR(32) NOT NULL,
    target_service VARCHAR(32) NOT NULL,
    target_entity_id VARCHAR(128) NOT NULL,
    action_payload_json TEXT,
    last_triggered TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
