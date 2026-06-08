package com.dev.bookingapp.javabookingapp.controller;

import com.dev.bookingapp.javabookingapp.dto.request.CustomerRequest;
import com.dev.bookingapp.javabookingapp.dto.response.CustomerResponse;
import com.dev.bookingapp.javabookingapp.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/businesses/{businessId}/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @GetMapping
    public ResponseEntity<Page<CustomerResponse>> getAll(
            @PathVariable UUID businessId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(customerService.getAllByBusinessId(businessId, pageable));
    }

    @GetMapping("/search")
    public ResponseEntity<Page<CustomerResponse>> search(
            @PathVariable UUID businessId,
            @RequestParam String query,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(customerService.search(businessId, query, pageable));
    }

    @GetMapping("/{customerId}")
    public ResponseEntity<CustomerResponse> getById(
            @PathVariable UUID businessId,
            @PathVariable UUID customerId) {
        return ResponseEntity.ok(customerService.getById(businessId, customerId));
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> create(
            @PathVariable UUID businessId,
            @Valid @RequestBody CustomerRequest request) {
        CustomerResponse created = customerService.create(businessId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{customerId}")
    public ResponseEntity<CustomerResponse> update(
            @PathVariable UUID businessId,
            @PathVariable UUID customerId,
            @Valid @RequestBody CustomerRequest request) {
        return ResponseEntity.ok(customerService.update(businessId, customerId, request));
    }

    @DeleteMapping("/{customerId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID businessId,
            @PathVariable UUID customerId) {
        customerService.delete(businessId, customerId);
        return ResponseEntity.noContent().build();
    }
}
