package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.BusinessRequest;
import com.dev.bookingapp.javabookingapp.dto.response.BusinessResponse;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.exception.ConflictException;
import com.dev.bookingapp.javabookingapp.exception.ResourceNotFoundException;
import com.dev.bookingapp.javabookingapp.mapper.BusinessMapper;
import com.dev.bookingapp.javabookingapp.repository.BusinessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class BusinessService {

    private final BusinessRepository businessRepository;
    private final BusinessMapper businessMapper;

    private static final Pattern NONLATIN = Pattern.compile("[^\\w-]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s]");

    @Transactional(readOnly = true)
    public List<BusinessResponse> listActive() {
        return businessRepository.findAllByIsActiveTrueOrderByNameAsc()
                .stream()
                .map(businessMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public BusinessResponse getById(UUID id) {
        Business business = businessRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Business", "id", id));
        return businessMapper.toResponse(business);
    }

    @Transactional(readOnly = true)
    public BusinessResponse getBySlug(String slug) {
        Business business = businessRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Business", "slug", slug));
        return businessMapper.toResponse(business);
    }

    @Transactional(readOnly = true)
    public BusinessResponse getActiveBySlug(String slug) {
        Business business = businessRepository.findBySlug(slug)
                .filter(b -> Boolean.TRUE.equals(b.getIsActive()))
                .orElseThrow(() -> new ResourceNotFoundException("Business", "slug", slug));
        return businessMapper.toResponse(business);
    }

    @Transactional(readOnly = true)
    public Business getEntityById(UUID id) {
        return businessRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Business", "id", id));
    }

    @Transactional
    public BusinessResponse create(BusinessRequest request) {
        String slug = request.getSlug();
        if (slug == null || slug.isBlank()) {
            slug = generateSlug(request.getName());
        }

        if (businessRepository.existsBySlug(slug)) {
            throw new ConflictException("A business with this slug already exists");
        }

        Business business = businessMapper.toEntity(request);
        business.setSlug(slug);
        business.setIsActive(true);

        Business saved = businessRepository.save(business);
        return businessMapper.toResponse(saved);
    }

    @Transactional
    public BusinessResponse update(UUID id, BusinessRequest request) {
        Business business = businessRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Business", "id", id));

        if (request.getSlug() != null && !request.getSlug().equals(business.getSlug())) {
            if (businessRepository.existsBySlug(request.getSlug())) {
                throw new ConflictException("A business with this slug already exists");
            }
        }

        businessMapper.updateEntity(request, business);
        Business saved = businessRepository.save(business);
        return businessMapper.toResponse(saved);
    }

    @Transactional
    public void delete(UUID id) {
        if (!businessRepository.existsById(id)) {
            throw new ResourceNotFoundException("Business", "id", id);
        }
        businessRepository.deleteById(id);
    }

    private String generateSlug(String input) {
        String nowhitespace = WHITESPACE.matcher(input).replaceAll("-");
        String normalized = Normalizer.normalize(nowhitespace, Normalizer.Form.NFD);
        String slug = NONLATIN.matcher(normalized).replaceAll("");
        slug = slug.toLowerCase(Locale.ENGLISH).replaceAll("-+", "-");
        slug = slug.replaceAll("^-|-$", "");

        // Ensure uniqueness
        String baseSlug = slug;
        int counter = 1;
        while (businessRepository.existsBySlug(slug)) {
            slug = baseSlug + "-" + counter++;
        }

        return slug;
    }
}
