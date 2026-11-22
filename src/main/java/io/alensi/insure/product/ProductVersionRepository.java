package io.alensi.insure.product;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

interface ProductVersionRepository extends Repository<ProductVersion, UUID> {

    ProductVersion saveAndFlush(ProductVersion version);

    /** Writes changes to managed versions (and their replaced children) now, so the new ETag is known. */
    void flush();

    Optional<ProductVersion> findByTenantCodeAndProductIdAndVersionNumber(String tenantCode, UUID productId,
            int versionNumber);

    List<ProductVersion> findByTenantCodeAndProductIdInOrderByVersionNumber(String tenantCode,
            Collection<UUID> productIds);

    List<ProductVersion> findByTenantCodeAndProductIdAndStatus(String tenantCode, UUID productId,
            ProductVersion.Status status);

    @Query("""
            select coalesce(max(v.versionNumber), 0) from ProductVersion v
            where v.tenantCode = :tenant and v.productId = :productId""")
    int lastVersionNumber(@Param("tenant") String tenantCode, @Param("productId") UUID productId);
}
