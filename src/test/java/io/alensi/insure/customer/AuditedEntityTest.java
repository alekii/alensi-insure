package io.alensi.insure.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.alensi.insure.ApiIntegrationTest;
import io.alensi.insure.platform.security.CurrentTenant;
import io.alensi.insure.platform.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The base entity classes stamp tenant and audit columns; nobody sets them by hand. */
class AuditedEntityTest extends ApiIntegrationTest {

    static final Set<String> CUSTOMERS = Set.of("customers.view", "customers.create", "customers.update");
    static final Staff CREATOR = new Staff("stamp.creator", "JUB", Map.of("team", Map.of("JUB-KE-NRB-NWS-T1", CUSTOMERS)));
    static final Staff EDITOR = new Staff("stamp.editor", "JUB", Map.of("team", Map.of("JUB-KE-NRB-NWS-T1", CUSTOMERS)));

    @Autowired
    CustomerRepository customers;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void identity() {
        register(CREATOR, EDITOR);
    }

    @Test
    void tenantAndAuthorsAreStampedOnInsertAndUpdate() throws Exception {
        String id = createdId(send(CREATOR, post("/api/v1/customers"), """
                {"customerType": "ORGANIZATION", "unitCode": "JUB-KE-NRB-NWS-T1", "organisationName": "Stamp Ltd"}"""));
        Map<String, Object> created = row(id);
        assertThat(created).containsEntry("tenant_code", "JUB").containsEntry("created_by", CREATOR.id())
                .containsEntry("updated_by", CREATOR.id()).containsEntry("version", 0L);

        call(EDITOR, patch("/api/v1/customers/" + id).header("If-Match", "\"0\"")
                .contentType("application/merge-patch+json").content("{\"phone\": \"+254700111222\"}"))
                .andExpect(status().isOk());
        Map<String, Object> updated = row(id);
        assertThat(updated).containsEntry("tenant_code", "JUB").containsEntry("created_by", CREATOR.id())
                .containsEntry("updated_by", EDITOR.id()).containsEntry("version", 1L)
                .containsEntry("created_at", created.get("created_at"));
        assertThat((java.sql.Timestamp) updated.get("updated_at")).isAfter((java.sql.Timestamp) created.get("updated_at"));
    }

    @Test
    void anEntityCantBeWrittenWhileActingInAnotherTenant() throws Exception {
        UUID id = UUID.fromString(createdId(send(CREATOR, post("/api/v1/customers"), """
                {"customerType": "ORGANIZATION", "unitCode": "JUB-KE-NRB-NWS-T1", "organisationName": "Guard Ltd"}""")));
        assertThatThrownBy(() -> CurrentTenant.run("JUB", () -> tx.executeWithoutResult(status -> {
            Customer customer = customers.findByTenantCodeAndId("JUB", id).orElseThrow();
            customer.setPhone("+254700999888");
            CurrentTenant.run("MAD", customers::flush);
        }))).hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("of tenant JUB can't be written while acting in tenant MAD");
    }

    @Test
    void workWithoutASignedInUserIsStampedAsTheSystem() {
        UUID id = CurrentTenant.call("JUB", () -> tx.execute(status -> {
            Customer customer = new Customer(UUID.randomUUID(), "CUS-SYS-" + UUID.randomUUID().toString().substring(0, 6),
                    Customer.Type.ORGANIZATION, "JUB-KE-NRB-NWS-T1");
            customer.name(null, null, "System Ltd");
            return customers.saveAndFlush(customer).getId();
        }));
        assertThat(row(id.toString())).containsEntry("created_by", CurrentUser.SYSTEM);
    }

    private Map<String, Object> row(String id) {
        return CurrentTenant.call("JUB", () -> tx.execute(status -> jdbc.queryForMap(
                "select tenant_code, created_by, updated_by, created_at, updated_at, version from customer.customer where id = ?",
                UUID.fromString(id))));
    }
}
