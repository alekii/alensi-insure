package io.alensi.insure.product;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

/** Every lookup names the tenant; row-level security checks it again in the database. */
interface ProductRepository extends Repository<Product, UUID> {

    Product saveAndFlush(Product product);

    Optional<Product> findByTenantCodeAndId(String tenantCode, UUID id);

    boolean existsByTenantCodeAndCode(String tenantCode, String code);

    List<Product> findByTenantCodeOrderByCode(String tenantCode);

    List<Product> findByTenantCodeAndStatusOrderByCode(String tenantCode, Product.Status status);
}
