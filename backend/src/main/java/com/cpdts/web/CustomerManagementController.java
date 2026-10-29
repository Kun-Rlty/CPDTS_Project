package com.cpdts.web;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cpdts.service.CustomerManagementService;

/**
 * Company-scoped customer endpoints (PRD FR-03). No DELETE route exists:
 * customers are deactivated, never deleted (the V2 database trigger enforces
 * this as well).
 *
 * <ul>
 *   <li>GET   /api/v1/companies/{companyId}/customers - bounded paginated
 *       list/search (RECORD_VIEW)</li>
 *   <li>POST  /api/v1/companies/{companyId}/customers - create with a
 *       server-generated customer code (CUSTOMER_MANAGE)</li>
 *   <li>GET   /api/v1/companies/{companyId}/customers/{customerId} - detail
 *       (RECORD_VIEW)</li>
 *   <li>PATCH /api/v1/companies/{companyId}/customers/{customerId} - update
 *       fields and/or activate/deactivate (CUSTOMER_MANAGE)</li>
 * </ul>
 *
 * <p>All routes are authenticated and CSRF-protected by the existing filter
 * chain. Authorization, company scoping, validation, code generation and
 * audit live in {@link CustomerManagementService}. Request DTOs deliberately
 * contain NO id/code/company fields: client-supplied identifiers are never
 * read (unknown JSON fields are ignored).
 */
@RestController
@RequestMapping("/api/v1/companies/{companyId}/customers")
public class CustomerManagementController {

    private final CustomerManagementService service;

    public CustomerManagementController(CustomerManagementService service) {
        this.service = service;
    }

    @GetMapping
    public CustomerPage list(@PathVariable long companyId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status) {
        return service.listCustomers(companyId, page, size, q, status);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerDto create(@PathVariable long companyId,
            @RequestBody CreateCustomerRequest body) {
        return service.createCustomer(companyId, body);
    }

    @GetMapping("/{customerId}")
    public CustomerDto detail(@PathVariable long companyId, @PathVariable long customerId) {
        return service.getCustomer(companyId, customerId);
    }

    @PatchMapping("/{customerId}")
    public CustomerDto update(@PathVariable long companyId, @PathVariable long customerId,
            @RequestBody UpdateCustomerRequest body) {
        return service.updateCustomer(companyId, customerId, body);
    }

    // ------------------------------------------------------------------
    // Request/response DTOs
    // ------------------------------------------------------------------

    /**
     * customerCode is intentionally absent: codes are generated server-side
     * from the company's number sequence and a client can never choose (or
     * forge) one, nor the owning company.
     */
    public record CreateCustomerRequest(String displayName, String phone, String email,
            String address, String notes) {
    }

    /**
     * Every field optional, but at least one must be present. Null means
     * "leave unchanged" (including phone/email/address/notes, which are
     * nullable in V2 - send an empty string to clear them).
     */
    public record UpdateCustomerRequest(String displayName, String phone, String email,
            String address, String notes, String status) {
    }

    /** One customer as exposed over the API (all company-scoped fields). */
    public record CustomerDto(long id, String customerCode, String displayName, String phone,
            String email, String address, String notes, String status, Instant createdAt,
            Instant updatedAt) {
    }

    /**
     * One bounded page. totalElements/totalPages are computed server-side
     * from the company-scoped query, never trusted from the client.
     */
    public record CustomerPage(List<CustomerDto> items, int page, int size, long totalElements,
            int totalPages) {
    }
}
