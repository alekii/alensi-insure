package io.alensi.insure.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.alensi.insure.IntegrationTest;
import io.alensi.insure.platform.security.CurrentTenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** ADR-0009: the database refuses cross-tenant reads and writes even when a query forgets the filter. */
class RowLevelSecurityTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void everyTenantTableHasForcedRowLevelSecurity() {
        List<Map<String, Object>> tables = jdbc.queryForList("""
                select n.nspname || '.' || c.relname as name, c.relrowsecurity, c.relforcerowsecurity
                from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                join pg_attribute a on a.attrelid = c.oid and a.attname = 'tenant_code' and not a.attisdropped
                where c.relkind in ('r', 'p') and n.nspname <> 'messaging'
                """);
        assertThat(tables).extracting(table -> table.get("name"))
                .contains("customer.customer", "audit.audit_event");
        assertThat(tables).allSatisfy(table -> {
            assertThat(table.get("relrowsecurity")).as("RLS enabled on %s", table.get("name")).isEqualTo(true);
            assertThat(table.get("relforcerowsecurity")).as("RLS forced on %s", table.get("name")).isEqualTo(true);
        });
    }

    @Test
    void theApplicationRoleCannotBypassRowLevelSecurity() {
        Map<String, Object> role = jdbc.queryForMap(
                "select current_user as name, rolsuper, rolbypassrls from pg_roles where rolname = current_user");
        assertThat(role).containsEntry("name", "insure_app").containsEntry("rolsuper", false)
                .containsEntry("rolbypassrls", false);
    }

    @Test
    void aQueryWithoutATenantFilterOnlySeesTheCurrentTenant() {
        UUID jubilee = insertCustomer("JUB", "JUB-KE-NRB-NWS");
        UUID madison = insertCustomer("MAD", "MAD-KE-NRB");

        List<UUID> seenByJubilee = CurrentTenant.call("JUB",
                () -> tx.execute(status -> jdbc.queryForList("select id from customer.customer", UUID.class)));
        assertThat(seenByJubilee).contains(jubilee).doesNotContain(madison);

        // Even by ID, another tenant's row doesn't exist.
        Integer found = CurrentTenant.call("JUB", () -> tx.execute(status -> jdbc.queryForObject(
                "select count(*) from customer.customer where id = ?", Integer.class, madison)));
        assertThat(found).isZero();
    }

    @Test
    void writesIntoAnotherTenantAreRejected() {
        assertThatThrownBy(() -> CurrentTenant.run("JUB", () -> tx.executeWithoutResult(status -> jdbc.update(
                insertSql(), UUID.randomUUID(), "MAD", "CUS-X-" + UUID.randomUUID().toString().substring(0, 8),
                "MAD-KE-NRB"))))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("violates row-level security policy");

        UUID madison = insertCustomer("MAD", "MAD-KE-NRB");
        int updated = CurrentTenant.call("JUB", () -> tx.execute(status -> jdbc.update(
                "update customer.customer set phone = '+254700000000' where id = ?", madison)));
        assertThat(updated).isZero();
    }

    @Test
    void withoutATenantNothingIsVisibleOrWritable() {
        insertCustomer("JUB", "JUB-KE-NRB-NWS");
        Integer visible = tx.execute(status -> jdbc.queryForObject("select count(*) from customer.customer",
                Integer.class));
        assertThat(visible).isZero();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update(insertSql(), UUID.randomUUID(), "JUB",
                "CUS-Y-" + UUID.randomUUID().toString().substring(0, 8), "JUB-KE-NRB-NWS")))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void theTenantDoesNotLeakToTheNextTransactionOnTheConnection() {
        insertCustomer("JUB", "JUB-KE-NRB-NWS");
        for (int i = 0; i < 5; i++) {
            CurrentTenant.run("JUB", () -> tx.execute(status -> jdbc.queryForObject("select 1", Integer.class)));
            Integer visible = tx.execute(status -> jdbc.queryForObject("select count(*) from customer.customer",
                    Integer.class));
            assertThat(visible).isZero();
        }
    }

    @Test
    void auditEventsAreAppendOnly() {
        assertThatThrownBy(() -> CurrentTenant.run("JUB", () -> tx.executeWithoutResult(
                status -> jdbc.update("update audit.audit_event set action = 'X'"))))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied for table audit_event");
    }

    private UUID insertCustomer(String tenant, String unit) {
        UUID id = UUID.randomUUID();
        CurrentTenant.run(tenant, () -> tx.executeWithoutResult(status -> jdbc.update(insertSql(), id, tenant,
                "CUS-T-" + id.toString().substring(0, 8), unit)));
        return id;
    }

    private static String insertSql() {
        return """
                insert into customer.customer (id, tenant_code, customer_number, customer_type, organization_name,
                    unit_code, status, version, created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, 'ORGANIZATION', 'Test Ltd', ?, 'ACTIVE', 0, now(), now(), gen_random_uuid(),
                    gen_random_uuid())
                """;
    }
}
