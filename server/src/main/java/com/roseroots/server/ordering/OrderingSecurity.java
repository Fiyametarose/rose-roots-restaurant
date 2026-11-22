package com.roseroots.server.ordering;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration(proxyBeanMethods = false)
@Profile("catalog")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class OrderingSecurity {
    record Actor(UUID credentialId, UUID restaurantId, UUID terminalId, String scope, boolean allergyReviewer,
                 boolean prioritySupervisor, UUID staffId, String staffRole, boolean personalSession) {
        Actor(UUID credentialId, UUID restaurantId, UUID terminalId, String scope, boolean allergyReviewer,
              boolean prioritySupervisor) {
            this(credentialId, restaurantId, terminalId, scope, allergyReviewer, prioritySupervisor, null, null, false);
        }
    }

    @Bean
    @Order(20)
    SecurityFilterChain orderingEndpoints(HttpSecurity http, JdbcClient jdbc) throws Exception {
        return http.securityMatcher("/api/v1/device", "/api/v1/sessions", "/api/v1/sessions/**",
                        "/api/v1/receipt-recovery/**", "/api/v1/order-quotes", "/api/v1/wait-estimates", "/api/v1/orders", "/api/v1/orders/**", "/api/v1/assistance", "/api/v1/kitchen/**")
                .authorizeHttpRequests(rules -> rules
                        .requestMatchers(HttpMethod.POST, "/api/v1/kitchen/staff-session")
                        .hasAnyAuthority("KITCHEN", "KITCHEN_WORKSTATION")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/kitchen/staff-session").hasAuthority("PERSONAL_SESSION")
                        .requestMatchers(HttpMethod.POST, "/api/v1/kitchen/orders/{id}/handoff").hasAuthority("PERSONAL_WAITER")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/kitchen/items/{id}/status").hasAuthority("CHEF")
                        .requestMatchers(HttpMethod.POST, "/api/v1/kitchen/items/{id}/ownership")
                        .hasAnyAuthority("CHEF", "PERSONAL_SUPERVISOR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/kitchen/items/{id}/eligible-chefs").hasAuthority("PERSONAL_SUPERVISOR")
                        .requestMatchers("/api/v1/kitchen/items/{id}/allergy-review").hasAuthority("ALLERGY_REVIEW")
                        .requestMatchers("/api/v1/kitchen/orders/{id}/rush", "/api/v1/kitchen/orders/{id}/delayed")
                        .hasAuthority("MANAGE_PRIORITY")
                        .requestMatchers(HttpMethod.GET, "/api/v1/kitchen/orders")
                        .hasAnyAuthority("KITCHEN", "KITCHEN_WORKSTATION", "PERSONAL_WAITER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/kitchen/history")
                        .hasAnyAuthority("KITCHEN", "PERSONAL_WAITER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/kitchen/assistance")
                        .hasAnyAuthority("KITCHEN", "KITCHEN_WORKSTATION")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/kitchen/assistance/{id}")
                        .hasAnyAuthority("KITCHEN", "KITCHEN_WORKSTATION")
                        .requestMatchers("/api/v1/kitchen/**").hasAuthority("KITCHEN")
                        .anyRequest().hasAuthority("TERMINAL"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            problem(response, new OrderingProblem(401, "UNAUTHENTICATED", "A valid bearer credential is required."));
                        })
                        .accessDeniedHandler((request, response, failure) ->
                                problem(response, new OrderingProblem(403, "FORBIDDEN", "Credential scope does not permit this operation."))))
                .addFilterBefore(new BearerFilter(jdbc), AnonymousAuthenticationFilter.class)
                .build();
    }

    private static void problem(HttpServletResponse response, OrderingProblem failure) throws IOException {
        response.setStatus(failure.status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(failure.body().toString());
    }

    private static final class BearerFilter extends OncePerRequestFilter {
        private final JdbcClient jdbc;

        private BearerFilter(JdbcClient jdbc) { this.jdbc = jdbc; }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!request.isSecure() && !(loopback(request.getLocalAddr()) && loopback(request.getRemoteAddr()))) {
                problem(response, new OrderingProblem(403, "FORBIDDEN", "Ordering requires HTTPS."));
                return;
            }
            String header = request.getHeader("Authorization");
            if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String token = header.substring(7);
                if (token.matches("[A-Za-z0-9_-]{43,128}")) {
                    try {
                        boolean personal = token.matches("ks_[A-Za-z0-9_-]{43}");
                        var query = personal ? jdbc.sql("""
                                SELECT c.id, c.restaurant_id, c.terminal_id, c.scope, s.staff_role = 'CHEF' AS allergy_reviewer,
                                    s.staff_role = 'SUPERVISOR' AS priority_supervisor, s.id AS staff_id, s.staff_role
                                FROM kitchen_staff_session k JOIN staff_account s ON s.id = k.staff_id
                                JOIN ordering_credential c ON c.id = s.credential_id
                                JOIN ordering_credential entry ON entry.id = k.entry_credential_id
                                WHERE k.token_sha256 = :hash AND :secure AND k.expires_at > now()
                                    AND s.enabled AND NOT s.password_reset_required
                                    AND c.scope = 'KITCHEN' AND c.revoked_at IS NULL AND c.expires_at > now()
                                    AND entry.restaurant_id = c.restaurant_id AND entry.revoked_at IS NULL
                                    AND entry.expires_at > now()
                                    AND NOT EXISTS (SELECT 1 FROM staff_account disabled
                                        WHERE disabled.credential_id = entry.id AND NOT disabled.enabled)
                                """) : jdbc.sql("""
                                SELECT c.id, c.restaurant_id, c.terminal_id, c.scope,
                                    false AS allergy_reviewer,
                                    COALESCE(s.staff_role = 'SUPERVISOR', false) AS priority_supervisor,
                                    s.id AS staff_id, s.staff_role
                                FROM ordering_credential c LEFT JOIN staff_account s ON s.credential_id = c.id
                                WHERE c.token_sha256 = :hash AND c.revoked_at IS NULL AND c.expires_at > now()
                                    AND COALESCE(s.enabled, true)
                                    AND NOT COALESCE(s.password_reset_required, false)
                                    AND s.staff_role IS DISTINCT FROM 'WAITER'
                                    AND (c.scope = 'KITCHEN_WORKSTATION') = :workstationToken
                                    AND (c.scope <> 'KITCHEN_WORKSTATION' OR (:secure AND EXISTS(
                                        SELECT 1 FROM kitchen_workstation w WHERE w.credential_id = c.id)))
                                    AND (c.scope <> 'TERMINAL' OR EXISTS(SELECT 1 FROM ordering_terminal t
                                        WHERE t.id=c.terminal_id AND t.enabled))
                                """).param("workstationToken", token.matches("ws_[A-Za-z0-9_-]{43}"));
                        query.param("hash", OrderingJson.digest(token)).param("secure", request.isSecure())
                                .query((row, index) -> new Actor(row.getObject("id", UUID.class),
                                        row.getObject("restaurant_id", UUID.class),
                                        row.getObject("terminal_id", UUID.class), row.getString("scope"),
                                        row.getBoolean("allergy_reviewer"), row.getBoolean("priority_supervisor"),
                                        row.getObject("staff_id", UUID.class), row.getString("staff_role"), personal))
                                .optional().ifPresent(actor -> {
                                    var context = SecurityContextHolder.createEmptyContext();
                                    var authorities = new ArrayList<SimpleGrantedAuthority>();
                                    if ("WAITER".equals(actor.staffRole())) {
                                        authorities.add(new SimpleGrantedAuthority("PERSONAL_SESSION"));
                                        authorities.add(new SimpleGrantedAuthority("PERSONAL_WAITER"));
                                    } else {
                                        authorities.add(new SimpleGrantedAuthority(actor.scope()));
                                    }
                                    if ("KITCHEN".equals(actor.scope()) && !"WAITER".equals(actor.staffRole())) {
                                        if (actor.allergyReviewer()) authorities.add(new SimpleGrantedAuthority("ALLERGY_REVIEW"));
                                        if (actor.prioritySupervisor()) authorities.add(new SimpleGrantedAuthority("MANAGE_PRIORITY"));
                                        if (actor.personalSession()) {
                                            authorities.add(new SimpleGrantedAuthority("PERSONAL_SESSION"));
                                            authorities.add(new SimpleGrantedAuthority("CHEF".equals(actor.staffRole())
                                                    ? "CHEF" : "PERSONAL_SUPERVISOR"));
                                        }
                                    }
                                    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                                            actor, null, authorities));
                                    SecurityContextHolder.setContext(context);
                                });
                    } catch (DataAccessException e) {
                        problem(response, new OrderingProblem(503, "SERVICE_UNAVAILABLE", "Ordering credentials are unavailable."));
                        return;
                    }
                }
            }
            chain.doFilter(request, response);
        }

        private static boolean loopback(String address) {
            try {
                return address != null && InetAddress.getByName(address).isLoopbackAddress();
            } catch (UnknownHostException e) {
                return false;
            }
        }
    }
}
