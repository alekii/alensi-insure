package io.alensi.insure.platform.security;

import java.util.List;

import io.alensi.identity.client.AlensiAccess;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller in this tenant, for the web app to show or hide actions. Not an authorization decision. */
@RestController
class MeController {

    private final AlensiAccess access;

    MeController(AlensiAccess access) {
        this.access = access;
    }

    @GetMapping("/api/v1/me")
    CurrentUserView me() {
        List<String> permissions = InsurePermissions.ALL.stream().filter(access::can).toList();
        // Lightweight tokens carry no names, so the display name comes from identity.
        return new CurrentUserView(CurrentUser.id().toString(), access.context().username(), CurrentTenant.require(),
                permissions);
    }

    record CurrentUserView(String userId, String displayName, String tenantCode, List<String> permissions) {
    }
}
