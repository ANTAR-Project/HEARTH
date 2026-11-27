package com.antar.policyregistry.engine;

import com.antar.policyregistry.model.PolicyRule;

import java.util.*;

public record RuleSnapshot(
    Map<String, List<PolicyRule>> byDomain,
    Map<String, List<PolicyRule>> byDomainRole
) {
    public static final RuleSnapshot EMPTY = new RuleSnapshot(Map.of(), Map.of());

    /** All enabled rules for a domain (kept for compatibility). */
    public List<PolicyRule> rulesForDomain(String domain) {
        return byDomain.getOrDefault(domain.toUpperCase(), List.of());
    }

    /**
     * Enabled rules narrowed to a specific domain + role — primary lookup path
     * used by {@code DecisionEngine} to avoid scanning the full domain list.
     */
    public List<PolicyRule> rulesForDomainRole(String domain, String role) {
        String key = domain.toUpperCase() + ":" + role.toUpperCase();
        return byDomainRole.getOrDefault(key, List.of());
    }

    public static RuleSnapshot from(List<PolicyRule> all) {
        Map<String, List<PolicyRule>> domainMap    = new HashMap<>();
        Map<String, List<PolicyRule>> domainRoleMap = new HashMap<>();

        for (PolicyRule r : all) {
            if (!r.isEnabled()) continue;

            String domain     = r.getDomain().toUpperCase();
            String domainRole = domain + ":" + r.getRole().toUpperCase();

            domainMap   .computeIfAbsent(domain,     k -> new ArrayList<>()).add(r);
            domainRoleMap.computeIfAbsent(domainRole, k -> new ArrayList<>()).add(r);
        }

        return new RuleSnapshot(
            Collections.unmodifiableMap(domainMap),
            Collections.unmodifiableMap(domainRoleMap)
        );
    }
}
