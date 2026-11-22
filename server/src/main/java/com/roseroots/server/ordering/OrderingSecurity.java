package com.roseroots.server.ordering;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
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
    record Actor(UUID credentialId, UUID restaurantId, UUID terminalId, String scope, boolean allergyReviewer) {}

    @Bean
    @Order(20)
    SecurityFilterChain orderingEndpoints(HttpSecurity http, JdbcClient jdbc) throws Exception {
        return http.securityMatcher("/api/v1/device", "/api/v1/sessions", "/api/v1/sessions/**",
                        "/api/v1/order-quotes", "/api/v1/orders", "/api/v1/orders/**", "/api/v1/assistance", "/api/v1/kitchen/**")
                .authorizeHttpRequests(rules -> rules
                        .requestMatchers("/api/v1/kitchen/items/{id}/allergy-review").hasAuthority("ALLERGY_REVIEW")
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
                        jdbc.sql("""
                                SELECT c.id, c.restaurant_id, c.terminal_id, c.scope,
                                    COALESCE(s.allergy_reviewer, c.allergy_reviewer) AS allergy_reviewer
                                FROM ordering_credential c LEFT JOIN staff_account s ON s.credential_id = c.id
                                WHERE c.token_sha256 = :hash AND c.revoked_at IS NULL AND c.expires_at > now()
                                    AND COALESCE(s.enabled, true)
                                """).param("hash", OrderingJson.digest(token))
                                .query((row, index) -> new Actor(row.getObject("id", UUID.class),
                                        row.getObject("restaurant_id", UUID.class),
                                        row.getObject("terminal_id", UUID.class), row.getString("scope"),
                                        row.getBoolean("allergy_reviewer")))
                                .optional().ifPresent(actor -> {
                                    var context = SecurityContextHolder.createEmptyContext();
                                    var scope = new SimpleGrantedAuthority(actor.scope());
                                    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                                            actor, null, actor.allergyReviewer()
                                                    ? List.of(scope, new SimpleGrantedAuthority("ALLERGY_REVIEW"))
                                                    : List.of(scope)));
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
