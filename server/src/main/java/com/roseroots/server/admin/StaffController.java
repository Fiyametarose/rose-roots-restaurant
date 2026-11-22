package com.roseroots.server.admin;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("catalog")
@RequestMapping(AdminSecurity.PREFIX + "/staff")
class StaffController {
    private final StaffAccounts accounts;

    StaffController(StaffAccounts accounts) { this.accounts = accounts; }

    @GetMapping
    ResponseEntity<List<StaffAccounts.Account>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.list());
    }

    @PostMapping
    ResponseEntity<StaffAccounts.Created> create(@RequestBody StaffAccounts.Create input, Principal manager,
                                                 HttpServletRequest request) {
        if (!request.isSecure()) return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(accounts.create(input, manager.getName()));
    }

    @PatchMapping("/{id}")
    ResponseEntity<StaffAccounts.Account> update(@PathVariable("id") UUID id, @RequestBody StaffAccounts.Update input,
                                                 Principal manager, HttpServletRequest request) {
        if (input.password() != null && !request.isSecure()) {
            return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).build();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.update(id, input, manager.getName()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> rejected(ResponseStatusException failure) {
        String reason = failure.getReason() == null ? "Staff account change rejected." : failure.getReason();
        return ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore())
                .body(Map.of("message", reason));
    }
}
