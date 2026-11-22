package com.roseroots.server.admin;

import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
class AdminSecurity {
    static final String PREFIX = "/api/v1/admin";
    static final String MANAGER = "MANAGER";
    static final String LOGIN = PREFIX + "/login";
    static final String CSRF = PREFIX + "/csrf";
    static final String LOGOUT = PREFIX + "/logout";
    static final String DASHBOARD = PREFIX + "/dashboard";

    @Bean
    @Order(10)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain adminEndpoints(HttpSecurity http, ObjectProvider<StaffAccounts> accounts) throws Exception {
        return http.securityMatcher(PREFIX + "/**")
                .authorizeHttpRequests(rules -> rules
                        .requestMatchers(HttpMethod.GET, CSRF).permitAll()
                        .requestMatchers(HttpMethod.POST, LOGIN).permitAll()
                        .anyRequest().access((authentication, context) -> {
                            var principal = authentication.get();
                            boolean manager = principal != null && principal.getAuthorities().stream()
                                    .anyMatch(role -> role.getAuthority().equals("ROLE_" + MANAGER));
                            var staff = accounts.getIfAvailable();
                            return new AuthorizationDecision(manager && (staff == null || staff.manager(principal.getName())
                                    .map(org.springframework.security.core.userdetails.UserDetails::isEnabled).orElse(true)));
                        }))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) -> response.setStatus(401))
                        .accessDeniedHandler((request, response, failure) -> response.setStatus(403)))
                .formLogin(form -> form.loginPage(LOGIN).loginProcessingUrl(LOGIN)
                        .successHandler((request, response, user) -> {
                            if (user.getAuthorities().stream()
                                    .noneMatch(role -> role.getAuthority().equals("ROLE_" + MANAGER))) {
                                request.getSession().invalidate();
                                response.setStatus(403);
                                return;
                            }
                            response.setStatus(204);
                        })
                        .failureHandler((request, response, failure) -> response.setStatus(401)))
                .logout(logout -> logout.logoutUrl(LOGOUT)
                        .logoutSuccessHandler((request, response, user) -> response.setStatus(204)))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(PasswordEncoder.class)
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnMissingBean(UserDetailsService.class)
    UserDetailsService configuredAccounts(Environment environment, ObjectProvider<StaffAccounts> accounts) {
        var users = new InMemoryUserDetailsManager();
        addAccount(users, environment, "manager", MANAGER);
        addAccount(users, environment, "kitchen", "KITCHEN_STAFF");
        return username -> {
            if (users.userExists(username)) return users.loadUserByUsername(username);
            var staff = accounts.getIfAvailable();
            if (staff != null) {
                var manager = staff.manager(username.strip().toLowerCase(Locale.ROOT));
                if (manager.isPresent()) return manager.get();
            }
            return users.loadUserByUsername(username);
        };
    }

    private static void addAccount(InMemoryUserDetailsManager users, Environment environment,
                                   String account, String role) {
        String prefix = "rose.admin." + account;
        String username = environment.getProperty(prefix + ".username", "");
        String hash = environment.getProperty(prefix + ".password-hash", "");
        if (username.isBlank() && hash.isBlank()) {
            return;
        }
        if (username.isBlank() || !hash.matches("\\$2[aby]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}")) {
            throw new IllegalArgumentException("Configure a username and BCrypt hash for " + account);
        }
        users.createUser(User.withUsername(username).password(hash).roles(role).build());
    }
}
