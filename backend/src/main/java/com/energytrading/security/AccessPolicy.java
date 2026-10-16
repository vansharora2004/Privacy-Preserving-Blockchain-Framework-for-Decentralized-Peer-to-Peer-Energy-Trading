package com.energytrading.security;

import com.energytrading.model.enums.Role;
import com.energytrading.service.AuditService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Server-side ownership checks for household-specific resources, complementing the role checks
 * declared with {@code @PreAuthorize} on the controllers.
 * <p>
 * Policy: a household may access only its own private data; {@code ROLE_ADMIN} may access any
 * household's private data; other roles get access only where a method explicitly allows them.
 * Denials are audited and return HTTP 403 (identifiers in URLs never grant access by themselves).
 */
@Component
public class AccessPolicy {

    private final AuditService auditService;

    public AccessPolicy(AuditService auditService) {
        this.auditService = auditService;
    }

    public static boolean hasRole(Authentication authentication, Role role) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> role.name().equals(a.getAuthority()));
    }

    public static boolean isOwner(Authentication authentication, String householdCode) {
        return authentication != null && hasRole(authentication, Role.ROLE_HOUSEHOLD)
                && authentication.getName().equals(householdCode);
    }

    /** Allows the owning household and administrators. */
    public void requireOwnerOrAdmin(Authentication authentication, String householdCode, String resource) {
        requireOwnerOr(authentication, householdCode, resource, Role.ROLE_ADMIN);
    }

    /** Allows the owning household and any of {@code roles}; otherwise audits and throws. */
    public void requireOwnerOr(Authentication authentication, String householdCode, String resource, Role... roles) {
        if (isOwner(authentication, householdCode)
                || Arrays.stream(roles).anyMatch(role -> hasRole(authentication, role))) {
            return;
        }
        String actor = authentication != null ? authentication.getName() : "anonymous";
        auditService.record(actor, "ACCESS_DENIED", "DENIED", "resource=" + resource + " target=" + householdCode);
        throw new AccessDeniedException("Not permitted to access this household's private data");
    }
}
