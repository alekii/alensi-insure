package io.alensi.insure.policy;

import java.net.URI;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import io.alensi.insure.platform.web.ApiProblem;
import io.alensi.insure.platform.web.PageResponse;
import io.alensi.insure.platform.web.Versions;
import io.alensi.insure.policy.PolicyService.PolicyQuery;
import io.alensi.insure.policy.PolicyViews.PolicySummary;
import io.alensi.insure.policy.PolicyViews.PolicyView;
import io.alensi.insure.policy.PolicyViews.QuoteRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policies")
class PolicyController {

    private static final Set<String> SORTABLE = Set.of("policyNumber", "effectiveDate", "expiryDate", "createdAt");

    private final PolicyService policies;

    PolicyController(PolicyService policies) {
        this.policies = policies;
    }

    @PostMapping("/quotes")
    ResponseEntity<PolicyView> quote(@Valid @RequestBody QuoteRequest request) {
        PolicyView policy = policies.quote(request);
        return ResponseEntity.created(URI.create("/api/v1/policies/" + policy.id()))
                .eTag(Versions.etag(policy.version())).body(policy);
    }

    @GetMapping
    PageResponse<PolicySummary> search(@RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String policyNumber, @RequestParam(required = false) Policy.Status status,
            @RequestParam(required = false) String productCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiringBefore,
            @RequestParam(required = false) String unitCode,
            @PageableDefault(size = 20, sort = "policyNumber") Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE.contains(order.getProperty())) {
                throw ApiProblem.badRequest("invalid-sort", "Invalid sort", "Sort by one of %s.".formatted(SORTABLE));
            }
        }
        var page = policies.search(new PolicyQuery(customerId, policyNumber, status, productCode, expiringBefore,
                unitCode), pageable);
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }

    @GetMapping("/{policyId}")
    ResponseEntity<PolicyView> get(@PathVariable UUID policyId) {
        return withEtag(policies.get(policyId));
    }

    @PostMapping("/{policyId}/accept")
    ResponseEntity<PolicyView> accept(@PathVariable UUID policyId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return withEtag(policies.accept(policyId, ifMatch));
    }

    private static ResponseEntity<PolicyView> withEtag(PolicyView policy) {
        return ResponseEntity.ok().eTag(Versions.etag(policy.version())).body(policy);
    }
}
