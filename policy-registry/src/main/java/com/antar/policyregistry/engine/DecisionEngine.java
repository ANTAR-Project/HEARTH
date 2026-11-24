package com.antar.policyregistry.engine;

import com.antar.grpc.policy.DecideRequest;
import com.antar.grpc.policy.DecideResponse;
import com.antar.policyregistry.model.PolicyEffect;
import com.antar.policyregistry.model.PolicyRule;
import com.antar.policyregistry.model.PolicyScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class DecisionEngine {
    private static final Logger log = LoggerFactory.getLogger(DecisionEngine.class);
    private final AtomicReference<RuleSnapshot> snapshot = new AtomicReference<>(RuleSnapshot.EMPTY);

    public void setSnapshot(RuleSnapshot s) {
        snapshot.set(s);
    }

    public RuleSnapshot getSnapshot() {
        return snapshot.get();
    }

    public DecideResponse decide(DecideRequest decideRequest) {
        String domain = decideRequest.getDomain();
        String role = decideRequest.getSubject().getRole();
        String action = decideRequest.getAction();
        String resType = decideRequest.getResource().getType();
        String qualifier = decideRequest.getResource().getQualifier();

        // Determine the effective scope from the namespace/qualifier context.
        // OWN:    caller's own resource (default when namespace is not SHARED and qualifier not set to a target role)
        // SHARED: shared workspace (namespace == "SHARED")
        // ANY:    cross-user access (qualifier carries the target role)
        final String effectiveNamespace = decideRequest.getNamespace();
        final boolean isShared = "SHARED".equalsIgnoreCase(effectiveNamespace)
            || "shared".equalsIgnoreCase(decideRequest.getResource().getId());
        final boolean isCrossUser = qualifier != null && !qualifier.isEmpty()
            && !qualifier.equalsIgnoreCase(role);  // targeting another role

        List<PolicyRule> matching = snapshot.get()
            .rulesForDomainRole(domain, role)
            .stream()
            .filter(r -> r.getAction().equalsIgnoreCase(action))
            .filter(r -> r.getResourceType().equalsIgnoreCase(resType))
            .filter(r -> r.getResourceQualifier() == null
                || r.getResourceQualifier().equalsIgnoreCase(qualifier))
            .filter(r -> {
                // Scope must match the effective access context
                return switch (r.getScope()) {
                    case OWN    -> !isShared && !isCrossUser;
                    case SHARED -> isShared;
                    case ANY    -> isCrossUser;
                };
            })
            .toList();

        DecideResponse response;
        if (matching.isEmpty()) {
            response = DecideResponse.newBuilder()
                .setEffect("DENY")
                .setReasonCode("NO_MATCHING_RULE")
                .build();
        } else if (matching.stream().anyMatch(r -> r.getEffect() == PolicyEffect.DENY)) {
            response = DecideResponse.newBuilder()
                .setEffect("DENY")
                .setReasonCode("EXPLICIT_DENY")
                .build();
        } else {
            PolicyRule best = matching.stream()
                .filter(r -> r.getEffect() == PolicyEffect.ALLOW)
                .max(Comparator.comparingInt(PolicyRule::getPriority)
                    .thenComparing(r -> r.getResourceQualifier() != null)
                    .thenComparing(r -> scopeBreadth(r.getScope())))
                .orElse(null);

            if (best == null) {
                response = DecideResponse.newBuilder()
                    .setEffect("DENY")
                    .setReasonCode("NO_ALLOW_RULE")
                    .build();
            } else if (best.getConditions() != null && best.getConditions().contains("\"ownership\":\"SELF\"")
                    && decideRequest.getResource().getId() != null
                    && !decideRequest.getResource().getId().isEmpty()
                    && !decideRequest.getResource().getId().equalsIgnoreCase(decideRequest.getSubject().getUsername())) {
                // ownership:SELF condition — the caller must own the specific resource
                response = DecideResponse.newBuilder()
                    .setEffect("DENY")
                    .setReasonCode("OWNERSHIP_SELF_REQUIRED")
                    .build();
            } else {
                response = DecideResponse.newBuilder()
                    .setEffect("ALLOW")
                    .setScope(best.getScope().name())
                    .setReasonCode("RULE:" + best.getRuleName())
                    .build();
            }
        }

        log.info("================================================================================");
        log.info("[POLICY_REGISTRY_INTENT_DECISION] Evaluated Intent Decision");
        log.info("  Intent / Rule : {}", decideRequest.getRuleName().isEmpty() ? "N/A" : decideRequest.getRuleName());
        log.info("  Domain        : {}", domain);
        log.info("  Subject       : {} (Role: {}, UserId: {})", decideRequest.getSubject().getUsername(), role, decideRequest.getSubject().getUserId());
        log.info("  Action        : {}", action);
        log.info("  Resource      : Type={}, Id={}, Qualifier={}", resType, decideRequest.getResource().getId(), qualifier);
        log.info("  Namespace     : {}", decideRequest.getNamespace());
        log.info("  Decision      : Effect={}, Scope={}, ReasonCode={}", response.getEffect(), response.getScope(), response.getReasonCode());
        log.info("================================================================================");

        return response;
    }

    private int scopeBreadth(PolicyScope scope) {
        return switch (scope) {
            case OWN -> 0;
            case SHARED -> 1;
            case ANY -> 2;
        };
    }
}
