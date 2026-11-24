package com.antar.policyregistry.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "policy_rules")
public class PolicyRule {

    @Id
    private UUID id;

    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    @Column(nullable = false)
    private String domain;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private String action;

    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(name = "resource_qualifier")
    private String resourceQualifier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PolicyScope scope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PolicyEffect effect;

    /**
     * JSONB conditions column — matches the {@code conditions} column in {@code policy_rules}.
     * Example values from the seed:
     *   {@code {"ownership":"SELF"}}  — caller must own the resource
     *   {@code {"trigger":"LOGIN"}}   — only evaluated on LOGIN events (CANCEL_PURGE)
     *   {@code {"cooling_period_days":15,"cancelled_by":"TARGET_LOGIN"}} — PURGE lifecycle
     *   {@code {"requires_hearth_intent":true,"assigned_resources_only":true}} — SERVICE rules
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String conditions = "{}";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private int priority = 0;

    public UUID getId() {
        return id;
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getDomain() {
        return domain;
    }

    public String getRole() {
        return role;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceQualifier() {
        return resourceQualifier;
    }

    public PolicyScope getScope() {
        return scope;
    }

    public PolicyEffect getEffect() {
        return effect;
    }

    public String getConditions() {
        return conditions;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPriority() {
        return priority;
    }
}

