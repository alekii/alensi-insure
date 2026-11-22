package io.alensi.insure.product;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import io.alensi.insure.platform.web.Versions;
import io.alensi.insure.product.ProductViews.CoverageConfiguration;
import io.alensi.insure.product.ProductViews.EligibilitySpec;
import io.alensi.insure.product.ProductViews.ProductCreate;
import io.alensi.insure.product.ProductViews.ProductView;
import io.alensi.insure.product.ProductViews.Publish;
import io.alensi.insure.product.ProductViews.Rating;
import io.alensi.insure.product.ProductViews.Terms;
import io.alensi.insure.product.ProductViews.VersionCreate;
import io.alensi.insure.product.ProductViews.VersionView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
class ProductController {

    private static final String VERSION = "/{productId}/versions/{versionNumber}";

    private final ProductService products;

    ProductController(ProductService products) {
        this.products = products;
    }

    @PostMapping
    ResponseEntity<ProductView> create(@Valid @RequestBody ProductCreate request) {
        ProductView product = products.create(request);
        return ResponseEntity.created(URI.create("/api/v1/products/" + product.id()))
                .eTag(Versions.etag(product.version())).body(product);
    }

    @GetMapping
    List<ProductView> list(@RequestParam(required = false) Product.Status status) {
        return products.list(status);
    }

    @GetMapping("/{productId}")
    ProductView get(@PathVariable UUID productId) {
        return products.get(productId);
    }

    @PostMapping("/{productId}/versions")
    ResponseEntity<VersionView> createVersion(@PathVariable UUID productId,
            @RequestBody(required = false) VersionCreate request) {
        VersionView version = products.createVersion(productId, request == null ? null : request.copyFromVersion());
        return ResponseEntity.created(URI.create("/api/v1/products/%s/versions/%d"
                .formatted(productId, version.versionNumber()))).eTag(Versions.etag(version.version())).body(version);
    }

    @GetMapping(VERSION)
    ResponseEntity<VersionView> getVersion(@PathVariable UUID productId, @PathVariable int versionNumber) {
        return withEtag(products.getVersion(productId, versionNumber));
    }

    @PutMapping(VERSION + "/terms")
    ResponseEntity<VersionView> replaceTerms(@PathVariable UUID productId, @PathVariable int versionNumber,
            @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestBody Terms terms) {
        return withEtag(products.replaceTerms(productId, versionNumber, ifMatch, terms));
    }

    @PutMapping(VERSION + "/coverages")
    ResponseEntity<VersionView> replaceCoverages(@PathVariable UUID productId, @PathVariable int versionNumber,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody CoverageConfiguration coverages) {
        return withEtag(products.replaceCoverages(productId, versionNumber, ifMatch, coverages));
    }

    @PutMapping(VERSION + "/eligibility")
    ResponseEntity<VersionView> replaceEligibility(@PathVariable UUID productId, @PathVariable int versionNumber,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody List<EligibilitySpec> rules) {
        return withEtag(products.replaceEligibility(productId, versionNumber, ifMatch, rules));
    }

    @PutMapping(VERSION + "/rating")
    ResponseEntity<VersionView> replaceRating(@PathVariable UUID productId, @PathVariable int versionNumber,
            @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestBody Rating rating) {
        return withEtag(products.replaceRating(productId, versionNumber, ifMatch, rating));
    }

    @PostMapping(VERSION + "/publish")
    ResponseEntity<VersionView> publish(@PathVariable UUID productId, @PathVariable int versionNumber,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) Publish request) {
        return withEtag(products.publish(productId, versionNumber, ifMatch, request));
    }

    @PostMapping(VERSION + "/withdraw")
    ResponseEntity<VersionView> withdraw(@PathVariable UUID productId, @PathVariable int versionNumber,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return withEtag(products.withdraw(productId, versionNumber, ifMatch));
    }

    private static ResponseEntity<VersionView> withEtag(VersionView version) {
        return ResponseEntity.ok().eTag(Versions.etag(version.version())).body(version);
    }
}
