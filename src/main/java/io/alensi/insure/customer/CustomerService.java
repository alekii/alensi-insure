package io.alensi.insure.customer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.alensi.identity.client.AlensiAccess;
import io.alensi.identity.client.TargetScope;
import io.alensi.insure.customer.CustomerViews.CustomerCreate;
import io.alensi.insure.customer.CustomerViews.CustomerView;
import io.alensi.insure.platform.audit.AuditLog;
import io.alensi.insure.platform.pii.PiiProtector;
import io.alensi.insure.platform.security.CurrentTenant;
import io.alensi.insure.platform.security.InsurePermissions;
import io.alensi.insure.platform.web.ApiProblem;
import io.alensi.insure.platform.web.Ids;
import io.alensi.insure.platform.web.Versions;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customers are owned by a unit (team or branch). Checks follow authorization.md: load in the
 * tenant, then 404 without {@code customers.view} on the unit (no existence leak), then 403 without
 * the action's permission.
 */
@Service
class CustomerService {

    static final String SUBJECT = "CUSTOMER";
    private static final Set<String> UPDATABLE = Set.of("phone", "email", "address");
    private static final java.util.regex.Pattern E164 = java.util.regex.Pattern.compile(CustomerViews.E164);
    private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final CustomerRepository customers;
    private final AlensiAccess access;
    private final PiiProtector pii;
    private final AuditLog audit;

    CustomerService(CustomerRepository customers, AlensiAccess access, PiiProtector pii, AuditLog audit) {
        this.customers = customers;
        this.access = access;
        this.pii = pii;
        this.audit = audit;
    }

    @Transactional
    CustomerView register(CustomerCreate request) {
        access.require(InsurePermissions.CUSTOMERS_CREATE, request.unitCode());
        boolean individual = request.customerType() == Customer.Type.INDIVIDUAL;
        if (individual && (blank(request.firstName()) || blank(request.lastName()))) {
            throw ApiProblem.badRequest("validation-failed", "Validation failed",
                    "firstName and lastName are required for individuals.");
        }
        if (!individual && blank(request.organisationName())) {
            throw ApiProblem.badRequest("validation-failed", "Validation failed",
                    "organisationName is required for organisations.");
        }
        String tenant = CurrentTenant.require();
        String identifier = individual ? request.nationalId() : request.taxNumber();
        String hash = blank(identifier) ? null : pii.hash(identifier);
        if (hash != null) {
            customers.findByTenantCodeAndNationalIdHash(tenant, hash).ifPresent(existing -> {
                ApiProblem duplicate = ApiProblem.conflict("duplicate-customer", "Duplicate customer",
                        "A customer with this identifier is already registered.");
                // Point to the existing record only if the caller could open it anyway.
                if (access.can(InsurePermissions.CUSTOMERS_VIEW, existing.getUnitCode())) {
                    duplicate.with("existingCustomerId", existing.getId());
                }
                throw duplicate;
            });
        }

        String number = "CUS-%06d".formatted(customers.nextCustomerNumber());
        Customer customer = new Customer(Ids.newId(), number, request.customerType(), request.unitCode());
        customer.name(individual ? request.firstName() : null, individual ? request.lastName() : null,
                individual ? null : request.organisationName());
        customer.identity(individual ? request.dateOfBirth() : null, hash == null ? null : pii.encrypt(identifier),
                hash);
        customer.contact(request.email(), request.phone(), request.address(), request.city(), request.countryCode());
        customer = customers.saveAndFlush(customer);
        audit.record("CUSTOMER_REGISTERED", SUBJECT, customer.getId(), customer.getUnitCode(),
                Map.of("customerNumber", number, "customerType", customer.getCustomerType().name()));
        return view(customer);
    }

    @Transactional(readOnly = true)
    CustomerView get(UUID id) {
        return view(visible(id));
    }

    @Transactional(readOnly = true)
    Page<CustomerView> search(CustomerQuery query, Pageable pageable) {
        TargetScope scope = access.scope(InsurePermissions.CUSTOMERS_VIEW);
        if (scope.isEmpty() || (query.unitCode() != null && !scope.includes(query.unitCode()))) {
            return Page.empty(pageable);
        }
        String tenant = CurrentTenant.require();
        String hash = blank(query.nationalId()) ? null : pii.hash(query.nationalId());
        Specification<Customer> filter = (root, q, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("tenantCode"), tenant));
            if (!scope.all()) {
                where.add(root.get("unitCode").in(scope.targets()));
            }
            if (query.unitCode() != null) {
                where.add(cb.equal(root.get("unitCode"), query.unitCode()));
            }
            if (query.customerNumber() != null) {
                where.add(cb.equal(root.get("customerNumber"), query.customerNumber()));
            }
            if (query.phone() != null) {
                where.add(cb.equal(root.get("phone"), query.phone()));
            }
            if (hash != null) {
                where.add(cb.equal(root.get("nationalIdHash"), hash));
            }
            if (!blank(query.name())) {
                String pattern = "%" + query.name().toLowerCase().replace("\\", "\\\\").replace("%", "\\%")
                        .replace("_", "\\_") + "%";
                where.add(cb.or(
                        cb.like(cb.lower(root.get("firstName")), pattern, '\\'),
                        cb.like(cb.lower(root.get("lastName")), pattern, '\\'),
                        cb.like(cb.lower(root.get("organizationName")), pattern, '\\')));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        return customers.findAll(filter, pageable).map(this::view);
    }

    /** JSON Merge Patch of contact details: {@code null} clears a field, absent leaves it. */
    @Transactional
    CustomerView update(UUID id, String ifMatch, Map<String, Object> patch) {
        Customer customer = visible(id);
        access.require(InsurePermissions.CUSTOMERS_UPDATE, customer.getUnitCode());
        Versions.requireMatch(ifMatch, customer.getVersion());
        for (String field : patch.keySet()) {
            if (!UPDATABLE.contains(field)) {
                throw ApiProblem.badRequest("validation-failed", "Validation failed",
                        "'%s' can't be changed here; updatable fields are %s.".formatted(field, UPDATABLE));
            }
        }
        Map<String, Object> changed = new LinkedHashMap<>();
        if (patch.containsKey("phone")) {
            String phone = text(patch, "phone", E164, "must be E.164, e.g. +254712345678");
            customer.setPhone(phone);
            changed.put("phone", "changed");
        }
        if (patch.containsKey("email")) {
            String email = text(patch, "email", EMAIL, "must be an email address");
            customer.setEmail(email);
            changed.put("email", "changed");
        }
        if (patch.containsKey("address")) {
            customer.setAddress(text(patch, "address", null, null));
            changed.put("address", "changed");
        }
        customer = customers.saveAndFlush(customer);
        audit.record("CUSTOMER_UPDATED", SUBJECT, customer.getId(), customer.getUnitCode(), changed);
        return view(customer);
    }

    /** Policies and claims arrive in later modules; until then nothing blocks a deactivation. */
    @Transactional
    CustomerView deactivate(UUID id, String ifMatch) {
        Customer customer = visible(id);
        access.require(InsurePermissions.CUSTOMERS_UPDATE, customer.getUnitCode());
        Versions.requireMatch(ifMatch, customer.getVersion());
        if (customer.getStatus() == Customer.Status.INACTIVE) {
            throw ApiProblem.conflict("invalid-state", "Invalid state", "The customer is already inactive.");
        }
        customer.deactivate();
        customer = customers.saveAndFlush(customer);
        audit.record("CUSTOMER_DEACTIVATED", SUBJECT, customer.getId(), customer.getUnitCode(), Map.of());
        return view(customer);
    }

    private Customer visible(UUID id) {
        return customers.findByTenantCodeAndId(CurrentTenant.require(), id)
                .filter(customer -> access.can(InsurePermissions.CUSTOMERS_VIEW, customer.getUnitCode()))
                .orElseThrow(() -> ApiProblem.notFound("Customer"));
    }

    private CustomerView view(Customer customer) {
        return new CustomerView(customer.getId(), customer.getTenantCode(), customer.getUnitCode(),
                customer.getCustomerNumber(), customer.getCustomerType(), customer.getStatus(),
                customer.displayName(), customer.getDateOfBirth(), pii.mask(customer.getNationalIdEncrypted()),
                customer.getPhone(), customer.getEmail(), customer.getAddress(), customer.getCity(),
                customer.getCountryCode(), customer.getVersion());
    }

    private static String text(Map<String, Object> patch, String field, java.util.regex.Pattern format,
            String message) {
        Object value = patch.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || (format != null && !format.matcher(text).matches())) {
            throw ApiProblem.badRequest("validation-failed", "Validation failed",
                    "%s %s.".formatted(field, message == null ? "must be text" : message));
        }
        return text;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    record CustomerQuery(String customerNumber, String name, String phone, String nationalId, String unitCode) {
    }
}
