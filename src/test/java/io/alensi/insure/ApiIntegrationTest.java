package io.alensi.insure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import io.alensi.identity.client.AlensiIdentityClient;
import io.alensi.identity.client.IdentityContext;
import io.alensi.identity.client.PermissionMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * API tests with staff whose grants come from a stubbed identity HTTP client, so the real starter
 * (cache, scopes, fail-closed) runs. Grants are given already expanded to descendant targets, as
 * identity returns them.
 *
 * <p>Test classes share one application context, and so the starter's permission cache: give each
 * class's staff usernames of their own, or one class's grants answer for another's.
 */
public abstract class ApiIntegrationTest extends IntegrationTest {

    @Autowired
    protected MockMvc mvc;

    @MockitoBean
    protected AlensiIdentityClient identity;

    protected void register(Staff... staff) {
        for (Staff member : staff) {
            given(identity.permissions(eq(member.token()), any())).willReturn(new PermissionMap(member.grants()));
            given(identity.context(eq(member.token()), any())).willReturn(
                    new IdentityContext(member.id(), member.username(), member.tenant(), List.of(member.tenant())));
        }
    }

    protected ResultActions call(Staff staff, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(jwt().jwt(token -> token.tokenValue(staff.token())
                .subject(staff.id().toString()))));
    }

    protected ResultActions send(Staff staff, MockHttpServletRequestBuilder request, String body) throws Exception {
        return call(staff, request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected static String field(ResultActions result, String path) throws Exception {
        return String.valueOf((Object) JsonPath.read(result.andReturn().getResponse().getContentAsString(), path));
    }

    protected static String createdId(ResultActions created) throws Exception {
        return field(created.andExpect(status().isCreated()), "$.id");
    }

    protected static String etag(ResultActions result) {
        return result.andReturn().getResponse().getHeader("ETag");
    }

    /** A signed-in staff member in {@code tenant}, with grants as {@code targetType -> target -> permissions}. */
    public record Staff(String username, String tenant, Map<String, Map<String, Set<String>>> grants, UUID id) {

        public Staff(String username, String tenant, Map<String, Map<String, Set<String>>> grants) {
            this(username, tenant, new HashMap<>(grants), UUID.nameUUIDFromBytes(username.getBytes()));
        }

        public String token() {
            return "token-" + username;
        }
    }
}
