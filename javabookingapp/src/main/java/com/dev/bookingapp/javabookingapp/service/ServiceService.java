package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.ServiceRequest;
import com.dev.bookingapp.javabookingapp.dto.response.ServiceResponse;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.exception.ConflictException;
import com.dev.bookingapp.javabookingapp.exception.ResourceNotFoundException;
import com.dev.bookingapp.javabookingapp.mapper.ServiceMapper;
import com.dev.bookingapp.javabookingapp.repository.ServiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class ServiceService {

    private final ServiceRepository serviceRepository;
    private final ServiceMapper serviceMapper;
    private final BusinessService businessService;

    @Transactional(readOnly = true)
    public ServiceResponse getById(UUID businessId, UUID serviceId) {
        com.dev.bookingapp.javabookingapp.entity.Service service = serviceRepository.findById(serviceId)
                .filter(s -> s.getBusiness().getId().equals(businessId))
                .orElseThrow(() -> new ResourceNotFoundException("Service", "id", serviceId));
        return serviceMapper.toResponse(service);
    }

    @Transactional(readOnly = true)
    public com.dev.bookingapp.javabookingapp.entity.Service getEntityById(UUID serviceId) {
        return serviceRepository.findById(serviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Service", "id", serviceId));
    }

    @Transactional(readOnly = true)
    public List<ServiceResponse> getAllByBusinessId(UUID businessId) {
        return serviceRepository.findByBusinessIdOrderByDisplayOrderAsc(businessId)
                .stream()
                .map(serviceMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ServiceResponse> getActiveByBusinessId(UUID businessId) {
        return serviceRepository.findByBusinessIdAndIsActiveTrueOrderByDisplayOrderAsc(businessId)
                .stream()
                .map(serviceMapper::toResponse)
                .toList();
    }

    @Transactional
    public ServiceResponse create(UUID businessId, ServiceRequest request) {
        Business business = businessService.getEntityById(businessId);

        if (serviceRepository.existsByBusinessIdAndName(businessId, request.getName())) {
            throw new ConflictException("A service with this name already exists");
        }

        com.dev.bookingapp.javabookingapp.entity.Service service = serviceMapper.toEntity(request);
        service.setBusiness(business);
        if (service.getIsActive() == null) {
            service.setIsActive(true);
        }

        com.dev.bookingapp.javabookingapp.entity.Service saved = serviceRepository.save(service);
        return serviceMapper.toResponse(saved);
    }

    @Transactional
    public ServiceResponse update(UUID businessId, UUID serviceId, ServiceRequest request) {
        com.dev.bookingapp.javabookingapp.entity.Service service = serviceRepository.findById(serviceId)
                .filter(s -> s.getBusiness().getId().equals(businessId))
                .orElseThrow(() -> new ResourceNotFoundException("Service", "id", serviceId));

        if (request.getName() != null && !request.getName().equals(service.getName())) {
            if (serviceRepository.existsByBusinessIdAndName(businessId, request.getName())) {
                throw new ConflictException("A service with this name already exists");
            }
        }

        serviceMapper.updateEntity(request, service);
        com.dev.bookingapp.javabookingapp.entity.Service saved = serviceRepository.save(service);
        return serviceMapper.toResponse(saved);
    }

    @Transactional
    public void delete(UUID businessId, UUID serviceId) {
        com.dev.bookingapp.javabookingapp.entity.Service service = serviceRepository.findById(serviceId)
                .filter(s -> s.getBusiness().getId().equals(businessId))
                .orElseThrow(() -> new ResourceNotFoundException("Service", "id", serviceId));

        serviceRepository.delete(service);
    }
}
