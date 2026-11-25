-- ============================================================================
-- ANTAR policy_rules: schema + seed (69 rules = 58 ALLOW + 11 DENY)
-- Matches antar_policy_rules.xlsx, sheet "policy_rules", row for row, in order.
--
-- Conventions
--   rule_name  = lower(domain.resource_type.action); NOT unique (unique key is below)
--   scope OWN    = caller's own workspace / resource assigned to the caller
--   scope SHARED = the shared workspace
--   scope ANY    = another subject's resource, narrowed by resource_qualifier (target role)
--   resource_qualifier NULL = any qualifier
--   conditions {"ownership":"SELF"} = only resources the caller created in that scope
--   Evaluation: DENY (priority 100) beats ALLOW; no matching rule = deny.
--   Lifecycle actions beyond CRUD: PROVISION, ARCHIVE, PURGE, CANCEL_PURGE
-- Requires PostgreSQL 15+ (UNIQUE NULLS NOT DISTINCT).
-- ============================================================================

CREATE TYPE policy_effect AS ENUM ('ALLOW', 'DENY');
CREATE TYPE policy_scope AS ENUM ('OWN', 'SHARED', 'ANY');

CREATE TABLE policy_rules
(
    id                 UUID PRIMARY KEY       DEFAULT gen_random_uuid(),
    rule_name          TEXT          NOT NULL,
    domain             TEXT          NOT NULL,
    role               TEXT          NOT NULL,
    action             TEXT          NOT NULL,
    resource_type      TEXT          NOT NULL,
    resource_qualifier TEXT,
    scope              policy_scope  NOT NULL DEFAULT 'OWN',
    effect             policy_effect NOT NULL DEFAULT 'ALLOW',
    conditions         JSONB         NOT NULL DEFAULT '{}',
    priority           INT           NOT NULL DEFAULT 0,
    enabled            BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by         UUID,
    UNIQUE NULLS NOT DISTINCT (domain, role, action, resource_type, resource_qualifier, scope, effect)
);

CREATE TABLE policy_meta
(
    version BIGINT NOT NULL
);
INSERT INTO policy_meta
VALUES (1);

-- ── OWNER: 22 rows (22 ALLOW, 0 DENY) ──────────────────────────────
INSERT INTO policy_rules (rule_name, domain, role, action, resource_type, resource_qualifier, scope, effect, conditions, priority)
VALUES ('nas.workspace.list', 'NAS', 'OWNER', 'LIST', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('nas.workspace.read', 'NAS', 'OWNER', 'READ', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('nas.workspace.create', 'NAS', 'OWNER', 'CREATE', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('nas.workspace.update', 'NAS', 'OWNER', 'UPDATE', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('nas.workspace.delete', 'NAS', 'OWNER', 'DELETE', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('nas.workspace.provision', 'NAS', 'OWNER', 'PROVISION', 'WORKSPACE', 'GUEST', 'ANY', 'ALLOW', '{}', 0),
       ('nas.workspace.list', 'NAS', 'OWNER', 'LIST', 'WORKSPACE', 'GUEST', 'ANY', 'ALLOW', '{}', 0),
       ('nas.workspace.read', 'NAS', 'OWNER', 'READ', 'WORKSPACE', 'GUEST', 'ANY', 'ALLOW', '{}', 0),
       ('nas.workspace.create', 'NAS', 'OWNER', 'CREATE', 'WORKSPACE', 'GUEST', 'ANY', 'ALLOW', '{}', 0),
       ('nas.workspace.update', 'NAS', 'OWNER', 'UPDATE', 'WORKSPACE', 'GUEST', 'ANY', 'ALLOW', '{}', 0),
       ('nas.workspace.delete', 'NAS', 'OWNER', 'DELETE', 'WORKSPACE', 'GUEST', 'ANY', 'ALLOW', '{}', 0),
       ('nas.workspace.archive', 'NAS', 'OWNER', 'ARCHIVE', 'WORKSPACE', 'RESIDENT', 'ANY', 'ALLOW', '{}', 0),
       ('nas.workspace.purge', 'NAS', 'OWNER', 'PURGE', 'WORKSPACE', 'RESIDENT', 'ANY', 'ALLOW', '{"cooling_period_days":15,"cancelled_by":"TARGET_LOGIN"}', 0),
       ('nas.workspace.list', 'NAS', 'OWNER', 'LIST', 'WORKSPACE', NULL, 'SHARED', 'ALLOW', '{}', 0),
       ('nas.workspace.read', 'NAS', 'OWNER', 'READ', 'WORKSPACE', NULL, 'SHARED', 'ALLOW', '{}', 0),
       ('nas.workspace.delete', 'NAS', 'OWNER', 'DELETE', 'WORKSPACE', NULL, 'SHARED', 'ALLOW', '{}', 0),
       ('nas.workspace.create', 'NAS', 'OWNER', 'CREATE', 'WORKSPACE', NULL, 'SHARED', 'ALLOW', '{"ownership":"SELF"}', 0),
       ('nas.workspace.update', 'NAS', 'OWNER', 'UPDATE', 'WORKSPACE', NULL, 'SHARED', 'ALLOW', '{"ownership":"SELF"}', 0),
       ('user.account.create', 'USER', 'OWNER', 'CREATE', 'ACCOUNT', 'RESIDENT', 'ANY', 'ALLOW', '{}', 0),
       ('user.account.delete', 'USER', 'OWNER', 'DELETE', 'ACCOUNT', 'RESIDENT', 'ANY', 'ALLOW', '{}', 0),
       ('user.account.create', 'USER', 'OWNER', 'CREATE', 'ACCOUNT', 'GUEST', 'ANY', 'ALLOW', '{}', 0),
       ('user.account.delete', 'USER', 'OWNER', 'DELETE', 'ACCOUNT', 'GUEST', 'ANY', 'ALLOW', '{}', 0),
       ('element.element.read', 'ELEMENT', 'OWNER', 'READ', 'ELEMENT', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('element.element.control', 'ELEMENT', 'OWNER', 'CONTROL', 'ELEMENT', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('element.camera.read', 'ELEMENT', 'OWNER', 'READ', 'ELEMENT', 'CAMERA', 'ANY', 'ALLOW', '{}', 0),
       ('element.camera.control', 'ELEMENT', 'OWNER', 'CONTROL', 'ELEMENT', 'CAMERA', 'ANY', 'ALLOW', '{}', 0),
       ('element.camera.stream', 'ELEMENT', 'OWNER', 'STREAM', 'ELEMENT', 'CAMERA', 'ANY', 'ALLOW', '{}', 0);

-- ── RESIDENT: 22 rows (16 ALLOW, 6 DENY) ──────────────────────────────
INSERT INTO policy_rules (rule_name, domain, role, action, resource_type, resource_qualifier, scope, effect, conditions, priority)
VALUES ('nas.workspace.list', 'NAS', 'RESIDENT', 'LIST', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('nas.workspace.read', 'NAS', 'RESIDENT', 'READ', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('nas.workspace.create', 'NAS', 'RESIDENT', 'CREATE', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('nas.workspace.update', 'NAS', 'RESIDENT', 'UPDATE', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('nas.workspace.delete', 'NAS', 'RESIDENT', 'DELETE', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('nas.workspace.archive', 'NAS', 'RESIDENT', 'ARCHIVE', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('nas.workspace.cancel_purge', 'NAS', 'RESIDENT', 'CANCEL_PURGE', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{"trigger":"LOGIN"}', 0),
       ('nas.workspace.list', 'NAS', 'RESIDENT', 'LIST', 'WORKSPACE', NULL, 'SHARED', 'ALLOW', '{}', 0),
       ('nas.workspace.read', 'NAS', 'RESIDENT', 'READ', 'WORKSPACE', NULL, 'SHARED', 'ALLOW', '{}', 0),
       ('nas.workspace.create', 'NAS', 'RESIDENT', 'CREATE', 'WORKSPACE', NULL, 'SHARED', 'ALLOW', '{"ownership":"SELF"}', 0),
       ('nas.workspace.update', 'NAS', 'RESIDENT', 'UPDATE', 'WORKSPACE', NULL, 'SHARED', 'ALLOW', '{"ownership":"SELF"}', 0),
       ('nas.workspace.delete', 'NAS', 'RESIDENT', 'DELETE', 'WORKSPACE', NULL, 'SHARED', 'ALLOW', '{"ownership":"SELF"}', 0),
       ('user.account.create', 'USER', 'RESIDENT', 'CREATE', 'ACCOUNT', 'RESIDENT', 'ANY', 'ALLOW', '{}', 0),
       ('user.account.delete', 'USER', 'RESIDENT', 'DELETE', 'ACCOUNT', 'RESIDENT', 'ANY', 'ALLOW', '{}', 0),
       ('user.account.create', 'USER', 'RESIDENT', 'CREATE', 'ACCOUNT', 'GUEST', 'ANY', 'ALLOW', '{}', 0),
       ('user.account.delete', 'USER', 'RESIDENT', 'DELETE', 'ACCOUNT', 'GUEST', 'ANY', 'ALLOW', '{}', 0),
       ('nas.workspace.provision', 'NAS', 'RESIDENT', 'PROVISION', 'WORKSPACE', 'GUEST', 'ANY', 'DENY', '{}', 100),
       ('nas.workspace.list', 'NAS', 'RESIDENT', 'LIST', 'WORKSPACE', 'GUEST', 'ANY', 'DENY', '{}', 100),
       ('nas.workspace.read', 'NAS', 'RESIDENT', 'READ', 'WORKSPACE', 'GUEST', 'ANY', 'DENY', '{}', 100),
       ('nas.workspace.create', 'NAS', 'RESIDENT', 'CREATE', 'WORKSPACE', 'GUEST', 'ANY', 'DENY', '{}', 100),
       ('nas.workspace.update', 'NAS', 'RESIDENT', 'UPDATE', 'WORKSPACE', 'GUEST', 'ANY', 'DENY', '{}', 100),
       ('nas.workspace.delete', 'NAS', 'RESIDENT', 'DELETE', 'WORKSPACE', 'GUEST', 'ANY', 'DENY', '{}', 100),
       ('element.element.read', 'ELEMENT', 'RESIDENT', 'READ', 'ELEMENT', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('element.element.control', 'ELEMENT', 'RESIDENT', 'CONTROL', 'ELEMENT', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('element.camera.read', 'ELEMENT', 'RESIDENT', 'READ', 'ELEMENT', 'CAMERA', 'ANY', 'ALLOW', '{}', 0),
       ('element.camera.control', 'ELEMENT', 'RESIDENT', 'CONTROL', 'ELEMENT', 'CAMERA', 'ANY', 'ALLOW', '{}', 0),
       ('element.camera.stream', 'ELEMENT', 'RESIDENT', 'STREAM', 'ELEMENT', 'CAMERA', 'ANY', 'ALLOW', '{}', 0);

-- ── GUEST: 15 rows (10 ALLOW, 5 DENY) ──────────────────────────────
INSERT INTO policy_rules (rule_name, domain, role, action, resource_type, resource_qualifier, scope, effect, conditions, priority)
VALUES ('nas.workspace.list', 'NAS', 'GUEST', 'LIST', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('nas.workspace.read', 'NAS', 'GUEST', 'READ', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('nas.workspace.create', 'NAS', 'GUEST', 'CREATE', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('nas.workspace.update', 'NAS', 'GUEST', 'UPDATE', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('nas.workspace.delete', 'NAS', 'GUEST', 'DELETE', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('nas.workspace.list', 'NAS', 'GUEST', 'LIST', 'WORKSPACE', NULL, 'SHARED', 'ALLOW', '{}', 0),
       ('nas.workspace.read', 'NAS', 'GUEST', 'READ', 'WORKSPACE', NULL, 'SHARED', 'ALLOW', '{}', 0),
       ('nas.workspace.create', 'NAS', 'GUEST', 'CREATE', 'WORKSPACE', NULL, 'SHARED', 'ALLOW', '{"ownership":"SELF"}', 0),
       ('nas.workspace.update', 'NAS', 'GUEST', 'UPDATE', 'WORKSPACE', NULL, 'SHARED', 'ALLOW', '{"ownership":"SELF"}', 0),
       ('nas.workspace.delete', 'NAS', 'GUEST', 'DELETE', 'WORKSPACE', NULL, 'SHARED', 'ALLOW', '{"ownership":"SELF"}', 0),
       ('nas.workspace.provision', 'NAS', 'GUEST', 'PROVISION', 'WORKSPACE', NULL, 'OWN', 'DENY', '{}', 100),
       ('user.account.create', 'USER', 'GUEST', 'CREATE', 'ACCOUNT', 'RESIDENT', 'ANY', 'DENY', '{}', 100),
       ('user.account.delete', 'USER', 'GUEST', 'DELETE', 'ACCOUNT', 'RESIDENT', 'ANY', 'DENY', '{}', 100),
       ('user.account.create', 'USER', 'GUEST', 'CREATE', 'ACCOUNT', 'GUEST', 'ANY', 'DENY', '{}', 100),
       ('user.account.delete', 'USER', 'GUEST', 'DELETE', 'ACCOUNT', 'GUEST', 'ANY', 'DENY', '{}', 100),
       ('element.element.read', 'ELEMENT', 'GUEST', 'READ', 'ELEMENT', NULL, 'OWN', 'ALLOW', '{}', 0),
       ('element.camera.read', 'ELEMENT', 'GUEST', 'READ', 'ELEMENT', 'CAMERA', 'ANY', 'ALLOW', '{}', 0),
       ('element.camera.control', 'ELEMENT', 'GUEST', 'CONTROL', 'ELEMENT', 'CAMERA', 'ANY', 'DENY', '{}', 100),
       ('element.camera.stream', 'ELEMENT', 'GUEST', 'STREAM', 'ELEMENT', 'CAMERA', 'ANY', 'DENY', '{}', 100);

-- ── SERVICE: 10 rows (10 ALLOW, 0 DENY) ──────────────────────────────
INSERT INTO policy_rules (rule_name, domain, role, action, resource_type, resource_qualifier, scope, effect, conditions, priority)
VALUES ('nas.workspace.list', 'NAS', 'SERVICE', 'LIST', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{"requires_hearth_intent":true,"assigned_resources_only":true}', 0),
       ('nas.workspace.read', 'NAS', 'SERVICE', 'READ', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{"requires_hearth_intent":true,"assigned_resources_only":true}', 0),
       ('nas.workspace.create', 'NAS', 'SERVICE', 'CREATE', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{"requires_hearth_intent":true,"assigned_resources_only":true}', 0),
       ('nas.workspace.update', 'NAS', 'SERVICE', 'UPDATE', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{"requires_hearth_intent":true,"assigned_resources_only":true}', 0),
       ('nas.workspace.delete', 'NAS', 'SERVICE', 'DELETE', 'WORKSPACE', NULL, 'OWN', 'ALLOW', '{"requires_hearth_intent":true,"assigned_resources_only":true}', 0),
       ('nas.media.read', 'NAS', 'SERVICE', 'READ', 'MEDIA', NULL, 'OWN', 'ALLOW', '{"requires_hearth_intent":true,"assigned_resources_only":true}', 0),
       ('nas.media.stream', 'NAS', 'SERVICE', 'STREAM', 'MEDIA', NULL, 'OWN', 'ALLOW', '{"requires_hearth_intent":true,"assigned_resources_only":true}', 0),
       ('element.element.read', 'ELEMENT', 'SERVICE', 'READ', 'ELEMENT', NULL, 'OWN', 'ALLOW', '{"requires_hearth_intent":true,"assigned_resources_only":true}', 0),
       ('element.element.control', 'ELEMENT', 'SERVICE', 'CONTROL', 'ELEMENT', NULL, 'OWN', 'ALLOW', '{"requires_hearth_intent":true,"assigned_resources_only":true}', 0),
       ('element.element.stream', 'ELEMENT', 'SERVICE', 'STREAM', 'ELEMENT', NULL, 'OWN', 'ALLOW', '{"requires_hearth_intent":true,"assigned_resources_only":true}', 0);

-- ── Sanity check ──────────────────────────────────────────────────────────
-- Expected: OWNER 22/0 | RESIDENT 16/6 | GUEST 10/5 | SERVICE 10/0 | total 69
-- SELECT role, effect, count(*) FROM policy_rules GROUP BY 1, 2 ORDER BY 1, 2;
-- SELECT count(*) FROM policy_rules;  -- 69

-- ── OPTIONAL (sheet "optional_rules"): carried-over rules NOT in the written spec ──
-- Uncomment to add. Not counted in the 69.
-- INSERT INTO policy_rules (rule_name, domain, role, action, resource_type, resource_qualifier, scope, effect, conditions, priority)
-- VALUES ('nas.media.stream', 'NAS', 'RESIDENT', 'STREAM', 'MEDIA', NULL, 'ANY', 'ALLOW', '{}', 0),
--        ('element.device.read', 'ELEMENT', 'RESIDENT', 'READ', 'ELEMENT', NULL, 'ANY', 'ALLOW', '{}', 0),
--        ('element.device.control', 'ELEMENT', 'RESIDENT', 'CONTROL', 'ELEMENT', NULL, 'ANY', 'ALLOW', '{}', 0),
--        ('element.camera.stream', 'ELEMENT', 'RESIDENT', 'STREAM', 'ELEMENT', 'CAMERA', 'ANY', 'ALLOW', '{}', 0),
--        ('element.device.read', 'ELEMENT', 'GUEST', 'READ', 'ELEMENT', NULL, 'ANY', 'ALLOW', '{}', 0),
--        ('element.camera.guest.deny', 'ELEMENT', 'GUEST', 'STREAM', 'ELEMENT', 'CAMERA', 'ANY', 'DENY', '{}', 100);