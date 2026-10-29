package com.cpdts.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cpdts.service.UserManagementService;

/**
 * User and company-membership management endpoints.
 *
 * <ul>
 *   <li>GET    /api/v1/companies/{companyId}/users - list the company's
 *       members (requires USER_MANAGE in that company)</li>
 *   <li>POST   /api/v1/companies/{companyId}/users - create a new user with
 *       an ACTIVE membership, or (platform admin only) add an existing user
 *       to this company without touching their credentials</li>
 *   <li>PATCH  /api/v1/companies/{companyId}/users/{userId}/membership -
 *       change the membership role and/or activate/deactivate it</li>
 * </ul>
 *
 * <p>All routes are authenticated and CSRF-protected by the existing filter
 * chain (no SecurityConfig change was needed). Authorization, tenancy and
 * audit rules live in {@link UserManagementService}. Responses never contain
 * password hashes.
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/users")
public class UserManagementController {

    private final UserManagementService service;

    public UserManagementController(UserManagementService service) {
        this.service = service;
    }

    @GetMapping
    public List<MemberDto> list(@PathVariable long companyId) {
        return service.listUsers(companyId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemberDto create(@PathVariable long companyId, @RequestBody CreateUserRequest body) {
        return service.createUser(companyId,
                body == null ? null : body.email(),
                body == null ? null : body.fullName(),
                body == null ? null : body.password(),
                body == null ? null : body.role());
    }

    @PatchMapping("/{userId}/membership")
    public MemberDto updateMembership(@PathVariable long companyId, @PathVariable long userId,
            @RequestBody UpdateMembershipRequest body) {
        return service.updateMembership(companyId, userId,
                body == null ? null : body.role(),
                body == null ? null : body.status());
    }

    // ------------------------------------------------------------------
    // Request/response DTOs (passwords are write-only; hashes never leave)
    // ------------------------------------------------------------------

    /**
     * role is a role CODE (e.g. VIEWER); the PLATFORM_ADMIN code is refused
     * server-side. For an existing email, password must be absent.
     */
    public record CreateUserRequest(String email, String fullName, String password, String role) {
    }

    /** Either field may be omitted; at least one is required. */
    public record UpdateMembershipRequest(String role, String status) {
    }

    /**
     * One membership view. userStatus is the GLOBAL account status (visible
     * for context; not mutable through this API for existing users).
     */
    public record MemberDto(long userId, String email, String fullName, String userStatus,
            String role, String membershipStatus) {
    }
}
