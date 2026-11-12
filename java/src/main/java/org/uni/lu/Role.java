package org.uni.lu;

import javax.ws.rs.core.SecurityContext;

/**
 * The three RBAC roles for the monitor's REST API, ordered from least to most
 * privileged. So ADMIN has the most privilege and using compareTo shows us who has more privilege.
 */
public enum Role {
    OBSERVER,
    OPERATOR,
    ADMIN;

    /** Resolve the strongest role the authenticated caller holds. */
    public static Role of(SecurityContext security) {
        if (security == null) {
            //No security context at all: treat as the weakest role
            return OBSERVER;
        }
        if (security.isUserInRole("admin")) {
            //Our "admin" role, and ONOS's built-in admin group, both land here
            return ADMIN;
        }
        if (security.isUserInRole("operator")) {
            return OPERATOR;
        }
        //Anyone who authenticated but holds neither role can still read
        return OBSERVER;
    }

    /** True when this role is allowed to act where {@code required} is needed. */
    public boolean allows(Role required) {
        return this.compareTo(required) >= 0;
    }

    /** Parse a role name (case-insensitive) to a Role, or null if unknown. */
    public static Role fromName(String name) {
        if (name == null) {
            return null;
        }
        switch (name.trim().toLowerCase()) {
            case "admin":
                return ADMIN;
            case "operator":
                return OPERATOR;
            case "observer":
                return OBSERVER;
            default:
                return null;
        }
    }
}
