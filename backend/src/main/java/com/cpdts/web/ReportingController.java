package com.cpdts.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cpdts.service.ReportingService;
import com.cpdts.web.InvoiceManagementController.InvoiceSummaryDto;
import com.cpdts.web.PaymentManagementController.PaymentDto;

@RestController
@RequestMapping("/api/v1/companies/{companyId}")
public class ReportingController {
    private final ReportingService service;

    public ReportingController(ReportingService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public Dashboard dashboard(@PathVariable long companyId) {
        return service.dashboard(companyId);
    }

    @GetMapping("/reports/debt/customers")
    public Page<CustomerDebt> customers(@PathVariable long companyId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String q) {
        return service.customers(companyId, page, size, q);
    }

    @GetMapping("/reports/debt/invoices")
    public Page<InvoiceSummaryDto> invoices(@PathVariable long companyId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String q, @RequestParam(required = false) Long customerId,
            @RequestParam(required = false, defaultValue = "false") boolean overdueOnly) {
        return service.invoices(companyId, page, size, q, customerId, overdueOnly);
    }

    @GetMapping("/reports/payments")
    public Page<PaymentDto> payments(@PathVariable long companyId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String q, @RequestParam(required = false) String status,
            @RequestParam(required = false) String method, @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        return service.payments(companyId, page, size, q, status, method, customerId, from, to);
    }

    public record Basis(String kind, Instant observedAt, LocalDate businessDate, String zone,
            String currency, LocalDate from, LocalDate to) { }
    public record Dashboard(Basis basis, long outstandingInvoiceCount, BigDecimal outstandingAmount,
            long overdueInvoiceCount, BigDecimal overdueAmount, long pendingPaymentCount,
            BigDecimal pendingPaymentAmount, BigDecimal verifiedPaymentAmount,
            BigDecimal unallocatedCustomerCredit) { }
    public record CustomerDebt(long customerId, String customerCode, String customerName,
            String customerStatus, BigDecimal outstandingAmount, BigDecimal overdueAmount,
            BigDecimal unallocatedCredit) { }
    public record Page<T>(Basis basis, List<T> items, int page, int size, long totalElements,
            long totalPages) { }
}
