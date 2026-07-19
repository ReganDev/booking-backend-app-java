package com.dev.bookingapp.javabookingapp.controller;

import com.dev.bookingapp.javabookingapp.dto.request.BusinessRequest;
import com.dev.bookingapp.javabookingapp.dto.request.PhotoDeleteRequest;
import com.dev.bookingapp.javabookingapp.dto.response.BusinessResponse;
import com.dev.bookingapp.javabookingapp.service.BusinessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/businesses")
@RequiredArgsConstructor
public class BusinessController {

    private final BusinessService businessService;

    @GetMapping("/{businessId}")
    public ResponseEntity<BusinessResponse> getById(@PathVariable UUID businessId) {
        return ResponseEntity.ok(businessService.getById(businessId));
    }

    @GetMapping("/slug/{slug}")
    public ResponseEntity<BusinessResponse> getBySlug(@PathVariable String slug) {
        return ResponseEntity.ok(businessService.getBySlug(slug));
    }

    @PutMapping("/{businessId}")
    public ResponseEntity<BusinessResponse> update(
            @PathVariable UUID businessId,
            @Valid @RequestBody BusinessRequest request) {
        return ResponseEntity.ok(businessService.update(businessId, request));
    }

    @PostMapping(
            path = "/{businessId}/photos",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<BusinessResponse> uploadPhotos(
            @PathVariable UUID businessId,
            @RequestPart("files") List<MultipartFile> files) {
        return ResponseEntity.ok(businessService.uploadPhotos(businessId, files));
    }

    @DeleteMapping("/{businessId}/photos")
    public ResponseEntity<BusinessResponse> removePhoto(
            @PathVariable UUID businessId,
            @Valid @RequestBody PhotoDeleteRequest request) {
        return ResponseEntity.ok(businessService.removePhoto(
                businessId,
                request.getPhotoUrl()
        ));
    }
}
