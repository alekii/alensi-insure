package io.alensi.insure.platform.security;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Starts every transaction by telling PostgreSQL the tenant, so row-level security filters every
 * statement in it (ADR-0009). {@code set_config(..., true)} is transaction-local, so the setting
 * can't leak to the next transaction on a pooled connection, and the tenant is a bind parameter.
 *
 * <p>Without a tenant nothing is set: the policies then match no rows, so such a transaction sees
 * nothing and can write nothing.
 */
class TenantTransactionManager extends JpaTransactionManager {

    TenantTransactionManager(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        String tenant = CurrentTenant.get();
        if (tenant == null) {
            return;
        }
        EntityManager entityManager = ((EntityManagerHolder) TransactionSynchronizationManager
                .getResource(obtainEntityManagerFactory())).getEntityManager();
        try {
            entityManager.createNativeQuery("select set_config('app.tenant_code', :tenant, true)")
                    .setParameter("tenant", tenant)
                    .getSingleResult();
        } catch (RuntimeException ex) {
            EntityTransaction tx = entityManager.getTransaction();
            if (tx.isActive()) {
                tx.rollback();
            }
            doCleanupAfterCompletion(transaction);
            throw new CannotCreateTransactionException("Could not set the transaction's tenant", ex);
        }
    }
}
