package io.alensi.insure.customer;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.alensi.insure.customer.CustomerService.CustomerQuery;
import io.alensi.insure.customer.CustomerViews.CustomerCreate;
import io.alensi.insure.customer.CustomerViews.CustomerView;
import io.alensi.insure.platform.web.ApiProblem;
import io.alensi.insure.platform.web.PageResponse;
import io.alensi.insure.platform.web.Versions;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers")
class CustomerController {

    private static final Set<String> SORTABLE = Set.of("customerNumber", "lastName", "createdAt", "updatedAt");

    private final CustomerService customers;

    CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @PostMapping
    ResponseEntity<CustomerView> register(@Valid @RequestBody CustomerCreate request) {
        CustomerView customer = customers.register(request);
        return ResponseEntity.created(URI.create("/api/v1/customers/" + customer.id()))
                .eTag(Versions.etag(customer.version()))
                .body(customer);
    }

    @GetMapping
    PageResponse<CustomerView> search(@RequestParam(required = false) String customerNumber,
            @RequestParam(required = false) String name, @RequestParam(required = false) String phone,
            @RequestParam(required = false) String nationalId, @RequestParam(required = false) String unitCode,
            @PageableDefault(size = 20, sort = "customerNumber") Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE.contains(order.getProperty())) {
                throw ApiProblem.badRequest("invalid-sort", "Invalid sort",
                        "Sort by one of %s.".formatted(SORTABLE));
            }
        }
        var page = customers.search(new CustomerQuery(customerNumber, name, phone, nationalId, unitCode), pageable);
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }

    @GetMapping("/{id}")
    ResponseEntity<CustomerView> get(@PathVariable UUID id) {
        return withEtag(customers.get(id));
    }

    @PatchMapping(path = "/{id}", consumes = "application/merge-patch+json")
    ResponseEntity<CustomerView> update(@PathVariable UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody Map<String, Object> patch) {
        return withEtag(customers.update(id, ifMatch, patch));
    }

    @PostMapping("/{id}/deactivate")
    ResponseEntity<CustomerView> deactivate(@PathVariable UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return withEtag(customers.deactivate(id, ifMatch));
    }

    private static ResponseEntity<CustomerView> withEtag(CustomerView customer) {
        return ResponseEntity.ok().eTag(Versions.etag(customer.version())).body(customer);
    }
}
