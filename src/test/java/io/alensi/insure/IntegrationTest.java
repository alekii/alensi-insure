package io.alensi.insure;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * One PostgreSQL (with pgvector, as in production) for all integration tests, set up with the same
 * role script as docker compose: migrations run as {@code insure_owner}, the application as
 * {@code insure_app}, which is neither owner nor superuser, so row-level security really applies.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withCopyFileToContainer(MountableFile.forHostPath("docker/postgres/init/01-roles.sql"),
                    "/docker-entrypoint-initdb.d/01-roles.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("insure.pii.encryption-key", () -> "dGVzdC1vbmx5LWVuY3J5cHRpb24ta2V5LTMyLWJ5dGU=");
        registry.add("insure.pii.hash-key", () -> "dGVzdC1vbmx5LWxvb2t1cC1oYXNoLWtleS0zMi1ieXQ=");
    }
}
