package com.antar.authservice.controller;

import com.antar.authservice.dto.*;
import com.antar.authservice.hearth.HearthPrincipal;
import com.antar.authservice.model.User;
import com.antar.authservice.repository.UserRepository;
import com.antar.authservice.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final UserRepository users;
    private final SessionService sessions;
    private final PasswordEncoder encoder;

    public AuthController(
        UserRepository users,
        SessionService sessions,
        PasswordEncoder encoder
    ) {
        this.users = users;
        this.sessions = sessions;
        this.encoder = encoder;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        User u = users.findByUsername(req.username())
            .filter(found -> encoder.matches(req.password(), found.getPasswordHash()))
            .orElseThrow(() -> new AuthException("INVALID_CREDENTIALS", 401));

        if (u.getStatus() != User.UserStatus.ACTIVE)
            throw new AuthException("ACCOUNT_SUSPENDED", 403);
        if (u.getExpiresAt() != null && u.getExpiresAt().isBefore(Instant.now()))
            throw new AuthException("ACCOUNT_EXPIRED", 403);

        // Re-hash to Argon2 if still bcrypt
        if (encoder.upgradeEncoding(u.getPasswordHash())) {
            u.setPasswordHash(encoder.encode(req.password()));
            users.save(u);
        }

        SessionService.TokenPair pair = sessions.create(u, req.deviceId());
        return ResponseEntity.ok(new LoginResponse(pair.accessToken(), pair.refreshToken(), pair.expiresIn()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        SessionService.TokenPair pair = sessions.rotate(req.refreshToken());
        return ResponseEntity.ok(new LoginResponse(pair.accessToken(), pair.refreshToken(), pair.expiresIn()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader("X-Session-Id") String sid) {
        sessions.revoke(UUID.fromString(sid), "LOGOUT");
        return ResponseEntity.noContent().build();
    }

    /**
     * Endpoint where an OWNER can create a RESIDENT or GUEST user.
     * Authorization is enforced by the gateway (policy check + context token).
     * The ContextTokenFilter has already verified the token and stored HearthPrincipal;
     * we just assert the role claim as a defence-in-depth check.
     */
    @PostMapping("/users")
    public ResponseEntity<UserResponse> createUser(
        HttpServletRequest request,
        @Valid @RequestBody CreateUserRequest req
    ) {
        assertOwner(request);

        if (req.role() == User.UserRole.OWNER) {
            throw new AuthException("CANNOT_CREATE_OWNER_ROLE", 400);
        }

        if (users.findByUsername(req.username()).isPresent()) {
            throw new AuthException("USERNAME_ALREADY_EXISTS", 409);
        }

        User u = new User();
        u.setUsername(req.username());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setRole(req.role());
        u.setStatus(User.UserStatus.ACTIVE);
        u.setMustChangePassword(req.mustChangePassword() != null ? req.mustChangePassword() : false);
        u.setExpiresAt(req.expiresAt());

        User saved = users.save(u);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(saved));
    }

    /**
     * Endpoint for an OWNER to list all registered users.
     * Authorization is enforced by the gateway (policy check + context token).
     */
    @GetMapping("/users")
    public ResponseEntity<List<UserResponse>> listUsers(HttpServletRequest request) {
        assertOwner(request);
        List<UserResponse> list = users.findAll().stream()
            .map(UserResponse::from)
            .toList();
        return ResponseEntity.ok(list);
    }

    /**
     * Defence-in-depth role check: the ContextTokenFilter has already verified the
     * gateway-signed context token and stored the HearthPrincipal as a request attribute.
     * We re-assert OWNER here so a misconfigured route can never reach this controller
     * without an authenticated OWNER principal.
     */
    private void assertOwner(HttpServletRequest request) {
        HearthPrincipal principal = (HearthPrincipal) request.getAttribute(HearthPrincipal.ATTR);
        if (principal == null) {
            throw new AuthException("UNAUTHORIZED", 401);
        }
        if (!"OWNER".equals(principal.role())) {
            throw new AuthException("FORBIDDEN_OWNER_REQUIRED", 403);
        }
    }

    public static class AuthException extends RuntimeException {
        private final int status;

        public AuthException(String msg, int status) {
            super(msg);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }
}
