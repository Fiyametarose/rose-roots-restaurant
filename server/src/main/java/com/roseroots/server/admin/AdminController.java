package com.roseroots.server.admin;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AdminController {
    private final ObjectProvider<DashboardSource> source;

    AdminController(ObjectProvider<DashboardSource> source) {
        this.source = source;
    }

    @GetMapping(value = AdminSecurity.CSRF, produces = MediaType.TEXT_PLAIN_VALUE)
    String csrf(CsrfToken token) {
        return token.getToken();
    }

    @GetMapping(AdminSecurity.DASHBOARD)
    DashboardSnapshot dashboard() {
        DashboardSource connected = source.getIfAvailable();
        return connected == null ? new DashboardSnapshot(null, null, null, null, null, null, null)
                : connected.snapshot();
    }
}
