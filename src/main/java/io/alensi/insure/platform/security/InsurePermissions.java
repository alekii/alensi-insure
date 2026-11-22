package io.alensi.insure.platform.security;

import java.util.List;

/** Permission codes of application {@code alensi-insure} in the identity catalog. */
public final class InsurePermissions {

    public static final String CUSTOMERS_VIEW = "customers.view";
    public static final String CUSTOMERS_CREATE = "customers.create";
    public static final String CUSTOMERS_UPDATE = "customers.update";
    public static final String PRODUCTS_VIEW = "products.view";
    public static final String PRODUCTS_MANAGE = "products.manage";
    public static final String COVERAGE_MANAGE = "coverage.manage";
    public static final String AUDIT_VIEW = "audit.view";

    /**
     * Every code in {@code catalog/alensi-insure.json} (alensi-identity), for {@code GET /me}. A code
     * missing here is only hidden from the web app; checks never use this list.
     */
    static final List<String> ALL = List.of(
            "customers.view", "customers.create", "customers.update", "customers.documents.view",
            "customers.documents.upload", "documents.sensitive.view",
            "products.view", "products.manage", "coverage.manage",
            "policies.view", "policies.quote", "policies.issue", "policies.renew", "policies.cancel",
            "policies.underwrite",
            "premiums.view", "premiums.adjust", "payments.view", "payments.record",
            "claims.view", "claims.submit", "claims.assess", "claims.approve", "claims.approve-senior",
            "claims.reject", "claims.escalate", "claims.settle", "claims.investigate",
            "workflows.manage", "workflows.act",
            "ai.assist", "ai.recommendations.view", "knowledge.manage",
            "reports.team", "reports.branch", "reports.region", "reports.company", "audit.view");

    private InsurePermissions() {
    }
}
