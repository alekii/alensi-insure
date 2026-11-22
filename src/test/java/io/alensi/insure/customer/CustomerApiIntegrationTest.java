package io.alensi.insure.customer;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import io.alensi.identity.client.AlensiIdentityClient;
import io.alensi.identity.client.AlensiIdentityException;
import io.alensi.identity.client.IdentityContext;
import io.alensi.identity.client.PermissionMap;
import io.alensi.insure.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Customers through the API with the default identity roles (authorization.md, "Role Walkthrough").
 * Identity is mocked at the HTTP client, so the real starter (cache, scopes, fail-closed) runs.
 */
class CustomerApiIntegrationTest extends IntegrationTest {

    static final String T1 = "JUB-KE-NRB-NWS-T1";
    static final String NWS = "JUB-KE-NRB-NWS";
    static final String WLD = "JUB-KE-NRB-WLD";
    static final Set<String> AGENT_PERMISSIONS = Set.of("customers.view", "customers.create", "customers.update");

    static final Staff AGENT = new Staff("agent", "JUB", Map.of("team", Map.of(T1, AGENT_PERMISSIONS)));
    static final Staff NWS_MANAGER = new Staff("nws.manager", "JUB", Map.of(
            "branch", Map.of(NWS, AGENT_PERMISSIONS), "team", Map.of(T1, AGENT_PERMISSIONS)));
    static final Staff WLD_MANAGER = new Staff("wld.manager", "JUB", Map.of("branch", Map.of(WLD, AGENT_PERMISSIONS)));
    static final Staff AUDITOR = new Staff("auditor", "JUB", Map.of(
            "company", Map.of("JUB", Set.of("customers.view", "audit.view")),
            "branch", Map.of(NWS, Set.of("customers.view", "audit.view"), WLD, Set.of("customers.view", "audit.view")),
            "team", Map.of(T1, Set.of("customers.view", "audit.view"))));
    static final Staff MADISON_AGENT = new Staff("mad.agent", "MAD",
            Map.of("team", Map.of("MAD-KE-NRB-T1", AGENT_PERMISSIONS)));

    @Autowired
    MockMvc mvc;

    @MockitoBean
    AlensiIdentityClient identity;

    @BeforeEach
    void identity() {
        for (Staff staff : List.of(AGENT, NWS_MANAGER, WLD_MANAGER, AUDITOR, MADISON_AGENT)) {
            given(identity.permissions(eq(staff.token()), any())).willReturn(new PermissionMap(staff.grants()));
            given(identity.context(eq(staff.token()), any())).willReturn(
                    new IdentityContext(staff.id(), staff.username(), staff.tenant(), List.of(staff.tenant())));
        }
    }

    @Test
    void anAgentRegistersCustomersInTheirTeamOnly() throws Exception {
        send(AGENT, post("/api/v1/customers"), individual(T1, "12 345-678"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.customerNumber", startsWith("CUS-")))
                .andExpect(jsonPath("$.tenantCode").value("JUB"))
                .andExpect(jsonPath("$.unitCode").value(T1))
                .andExpect(jsonPath("$.displayName").value("Wanjiru Kamau"))
                .andExpect(jsonPath("$.nationalIdMasked").value("****5678"))
                .andExpect(jsonPath("$.nationalId").doesNotExist());

        send(AGENT, post("/api/v1/customers"), individual(WLD, unique()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://alensi.io/problems/forbidden"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void managersSeeTheirBranchAndItsTeamsButNotOtherBranches() throws Exception {
        String inTeam = id(send(AGENT, post("/api/v1/customers"), individual(T1, unique())));
        String inWestlands = id(send(WLD_MANAGER, post("/api/v1/customers"), individual(WLD, unique())));

        call(NWS_MANAGER, get("/api/v1/customers/" + inTeam)).andExpect(status().isOk());
        call(NWS_MANAGER, get("/api/v1/customers/" + inWestlands))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://alensi.io/problems/not-found"));
        call(NWS_MANAGER, get("/api/v1/customers?size=100"))
                .andExpect(jsonPath("$.content[*].id", hasItem(inTeam)))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(inWestlands))))
                .andExpect(jsonPath("$.content[*].unitCode", everyItem(org.hamcrest.Matchers.in(List.of(NWS, T1)))));
        // Narrowing to a unit outside the caller's scope returns nothing rather than an error.
        call(NWS_MANAGER, get("/api/v1/customers?unitCode=" + WLD)).andExpect(jsonPath("$.totalElements").value(0));
        // The agent's team is below the branch: the agent can't see branch-level customers.
        call(AGENT, get("/api/v1/customers/" + inWestlands)).andExpect(status().isNotFound());
    }

    @Test
    void anotherInsurersCustomerDoesNotExist() throws Exception {
        String jubilee = id(send(AGENT, post("/api/v1/customers"), individual(T1, unique())));
        call(MADISON_AGENT, get("/api/v1/customers/" + jubilee)).andExpect(status().isNotFound());
        call(MADISON_AGENT, get("/api/v1/customers?size=100"))
                .andExpect(jsonPath("$.content[*].tenantCode", everyItem(is("MAD"))));
    }

    @Test
    void duplicatesAreRefusedAndOnlyRevealedToThoseWhoCanSeeThem() throws Exception {
        String nationalId = unique();
        String existing = id(send(AGENT, post("/api/v1/customers"), individual(T1, nationalId)));

        send(NWS_MANAGER, post("/api/v1/customers"), individual(NWS, " " + nationalId.toLowerCase() + " "))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://alensi.io/problems/duplicate-customer"))
                .andExpect(jsonPath("$.existingCustomerId").value(existing));
        send(WLD_MANAGER, post("/api/v1/customers"), individual(WLD, nationalId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.existingCustomerId").doesNotExist());

        // Another insurer may have the same person as a customer.
        send(MADISON_AGENT, post("/api/v1/customers"), individual("MAD-KE-NRB-T1", nationalId))
                .andExpect(status().isCreated());
    }

    @Test
    void searchFindsByNationalIdAndName() throws Exception {
        String nationalId = unique();
        String created = id(send(AGENT, post("/api/v1/customers"), individual(T1, nationalId)));
        call(AGENT, get("/api/v1/customers").param("nationalId", nationalId))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(created));
        call(AGENT, get("/api/v1/customers").param("name", "wanJIRU").param("size", "100"))
                .andExpect(jsonPath("$.content[*].id", hasItem(created)));
        call(AGENT, get("/api/v1/customers").param("sort", "nationalIdHash,asc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatesNeedThePermissionAndTheCurrentVersion() throws Exception {
        String id = id(send(AGENT, post("/api/v1/customers"), individual(T1, unique())));
        String path = "/api/v1/customers/" + id;

        mergePatch(AUDITOR, path, "\"0\"", "{\"phone\": \"+254711000111\"}").andExpect(status().isForbidden());
        mergePatch(AGENT, path, null, "{\"phone\": \"+254711000111\"}").andExpect(status().isPreconditionRequired());
        mergePatch(AGENT, path, "\"7\"", "{\"phone\": \"+254711000111\"}").andExpect(status().isPreconditionFailed());
        mergePatch(AGENT, path, "\"0\"", "{\"unitCode\": \"" + WLD + "\"}").andExpect(status().isBadRequest());
        mergePatch(AGENT, path, "\"0\"", "{\"phone\": \"0711\"}").andExpect(status().isBadRequest());

        mergePatch(AGENT, path, "\"0\"", "{\"phone\": \"+254711000111\", \"email\": null}")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.phone").value("+254711000111"))
                .andExpect(jsonPath("$.email").doesNotExist());

        call(AGENT, post(path + "/deactivate").header("If-Match", "\"1\"")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
        call(AGENT, post(path + "/deactivate").header("If-Match", "\"2\"")).andExpect(status().isConflict());
    }

    @Test
    void actionsAreAuditedWithoutPersonalData() throws Exception {
        String id = id(send(AGENT, post("/api/v1/customers"), individual(T1, "AUD-99887766")));
        String events = call(AUDITOR, get("/api/v1/audit-events").param("subjectId", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("CUSTOMER_REGISTERED"))
                .andExpect(jsonPath("$.content[0].actorId").value(AGENT.id().toString()))
                .andExpect(jsonPath("$.content[0].unitCode").value(T1))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(events).doesNotContain("99887766").doesNotContain("Wanjiru");

        // No audit.view: an empty trail, not an error.
        call(AGENT, get("/api/v1/audit-events").param("subjectId", id)).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void validationErrorsNameTheFields() throws Exception {
        send(AGENT, post("/api/v1/customers"), """
                {"customerType": "INDIVIDUAL", "unitCode": "%s", "firstName": "A", "lastName": "B",
                 "phone": "0712", "countryCode": "kenya"}""".formatted(T1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://alensi.io/problems/validation-failed"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("phone")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("countryCode")));
    }

    @Test
    void theTenantMustBeChosenAndAllowed() throws Exception {
        Staff both = new Staff("both", null, Map.of());
        given(identity.context(eq(both.token()), isNull()))
                .willReturn(new IdentityContext(both.id(), "both", null, List.of("JUB", "MAD")));
        given(identity.context(eq(both.token()), eq("KEN")))
                .willThrow(new AccessDeniedException("not a member"));
        given(identity.context(eq(both.token()), eq("NOPE")))
                .willThrow(new AlensiIdentityException(404, "Tenant 'NOPE' not found", null));

        call(both, get("/api/v1/me")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://alensi.io/problems/tenant-required"));
        call(both, get("/api/v1/me").header("X-Alensi-Tenant", "KEN")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://alensi.io/problems/tenant-not-allowed"));
        // An unknown tenant looks exactly like one the caller doesn't belong to.
        call(both, get("/api/v1/me").header("X-Alensi-Tenant", "NOPE")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://alensi.io/problems/tenant-not-allowed"));
    }

    @Test
    void meListsTheCallersPermissionsInTheTenant() throws Exception {
        call(AUDITOR, get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantCode").value("JUB"))
                .andExpect(jsonPath("$.displayName").value("auditor"))
                .andExpect(jsonPath("$.permissions", hasItem("audit.view")))
                .andExpect(jsonPath("$.permissions", not(hasItem("customers.create"))));
    }

    @Test
    void whenIdentityIsDownRequestsFailClosed() throws Exception {
        Staff unlucky = new Staff("unlucky", "JUB", Map.of());
        given(identity.context(eq(unlucky.token()), any())).willThrow(new AlensiIdentityException(502, "down", null));
        call(unlucky, get("/api/v1/customers")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("https://alensi.io/problems/dependency-unavailable"));
    }

    @Test
    void theCorrelationIdComesFromTraceparent() throws Exception {
        call(AGENT, get("/api/v1/me").header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
                .andExpect(header().string("X-Correlation-Id", "4bf92f3577b34da6a3ce929d0e0e4736"));
    }

    private ResultActions mergePatch(Staff staff, String path, String ifMatch, String body) throws Exception {
        MockHttpServletRequestBuilder request = patch(path).contentType("application/merge-patch+json").content(body);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return call(staff, request);
    }

    private static String individual(String unit, String nationalId) {
        return """
                {"customerType": "INDIVIDUAL", "unitCode": "%s", "firstName": "Wanjiru", "lastName": "Kamau",
                 "dateOfBirth": "1990-04-12", "nationalId": "%s", "phone": "+254712345678",
                 "email": "wanjiru@example.com", "countryCode": "KE"}""".formatted(unit, nationalId);
    }

    private static String unique() {
        return String.valueOf(10_000_000 + (long) (Math.random() * 89_999_999));
    }

    private static String id(ResultActions created) throws Exception {
        return JsonPath.read(created.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),
                "$.id");
    }

    private ResultActions call(Staff staff, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(jwt().jwt(token -> token.tokenValue(staff.token())
                .subject(staff.id().toString()).claim("preferred_username", staff.username()))));
    }

    private ResultActions send(Staff staff, MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(staff, request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    record Staff(String username, String tenant, Map<String, Map<String, Set<String>>> grants, UUID id) {

        Staff(String username, String tenant, Map<String, Map<String, Set<String>>> grants) {
            this(username, tenant, new HashMap<>(grants), UUID.nameUUIDFromBytes(username.getBytes()));
        }

        String token() {
            return "token-" + username;
        }
    }
}
