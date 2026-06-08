package com.dev.bookingapp.javabookingapp.controller;

import com.dev.bookingapp.javabookingapp.dto.request.ServiceRequest;
import com.dev.bookingapp.javabookingapp.dto.response.ServiceResponse;
import com.dev.bookingapp.javabookingapp.service.ServiceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/businesses/{businessId}/services")
@RequiredArgsConstructor
public class ServiceController {

    private final ServiceService serviceService;

    @GetMapping
    public ResponseEntity<List<ServiceResponse>> getAll(@PathVariable UUID businessId) {
        return ResponseEntity.ok(serviceService.getAllByBusinessId(businessId));
    }

    @GetMapping("/active")
    public ResponseEntity<List<ServiceResponse>> getActive(@PathVariable UUID businessId) {
        return ResponseEntity.ok(serviceService.getActiveByBusinessId(businessId));
    }

    @GetMapping("/{serviceId}")
    public ResponseEntity<ServiceResponse> getById(
            @PathVariable UUID businessId,
            @PathVariable UUID serviceId) {
        return ResponseEntity.ok(serviceService.getById(businessId, serviceId));
    }

    @PostMapping
    public ResponseEntity<ServiceResponse> create(
            @PathVariable UUID businessId,
            @Valid @RequestBody ServiceRequest request) {
        ServiceResponse created = serviceService.create(businessId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{serviceId}")
    public ResponseEntity<ServiceResponse> update(
            @PathVariable UUID businessId,
            @PathVariable UUID serviceId,
            @Valid @RequestBody ServiceRequest request) {
        return ResponseEntity.ok(serviceService.update(businessId, serviceId, request));
    }

    @DeleteMapping("/{serviceId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID businessId,
            @PathVariable UUID serviceId) {
        serviceService.delete(businessId, serviceId);
        return ResponseEntity.noContent().build();
    }
}
