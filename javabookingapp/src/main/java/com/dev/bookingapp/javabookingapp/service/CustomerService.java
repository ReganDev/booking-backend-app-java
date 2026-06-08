package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.CustomerRequest;
import com.dev.bookingapp.javabookingapp.dto.response.CustomerResponse;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.Customer;
import com.dev.bookingapp.javabookingapp.exception.ConflictException;
import com.dev.bookingapp.javabookingapp.exception.ResourceNotFoundException;
import com.dev.bookingapp.javabookingapp.mapper.CustomerMapper;
import com.dev.bookingapp.javabookingapp.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;
    private final BusinessService businessService;

    @Transactional(readOnly = true)
    public CustomerResponse getById(UUID businessId, UUID customerId) {
        Customer customer = customerRepository.findByBusinessIdAndId(businessId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", "id", customerId));
        return customerMapper.toResponse(customer);
    }

    @Transactional(readOnly = true)
    public Customer getEntityById(UUID customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", "id", customerId));
    }

    @Transactional(readOnly = true)
    public Page<CustomerResponse> getAllByBusinessId(UUID businessId, Pageable pageable) {
        return customerRepository.findByBusinessId(businessId, pageable)
                .map(customerMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<CustomerResponse> search(UUID businessId, String query, Pageable pageable) {
        return customerRepository.searchByBusinessId(businessId, query, pageable)
                .map(customerMapper::toResponse);
    }

    @Transactional
    public CustomerResponse create(UUID businessId, CustomerRequest request) {
        Business business = businessService.getEntityById(businessId);

        if (customerRepository.existsByBusinessIdAndEmail(businessId, request.getEmail())) {
            throw new ConflictException("A customer with this email already exists");
        }

        Customer customer = customerMapper.toEntity(request);
        customer.setBusiness(business);

        Customer saved = customerRepository.save(customer);
        return customerMapper.toResponse(saved);
    }

    @Transactional
    public CustomerResponse getOrCreate(UUID businessId, CustomerRequest request) {
        return customerRepository.findByBusinessIdAndEmail(businessId, request.getEmail())
                .map(customerMapper::toResponse)
                .orElseGet(() -> create(businessId, request));
    }

    @Transactional
    public CustomerResponse update(UUID businessId, UUID customerId, CustomerRequest request) {
        Customer customer = customerRepository.findByBusinessIdAndId(businessId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", "id", customerId));

        if (request.getEmail() != null && !request.getEmail().equals(customer.getEmail())) {
            if (customerRepository.existsByBusinessIdAndEmail(businessId, request.getEmail())) {
                throw new ConflictException("A customer with this email already exists");
            }
        }

        customerMapper.updateEntity(request, customer);
        Customer saved = customerRepository.save(customer);
        return customerMapper.toResponse(saved);
    }

    @Transactional
    public void delete(UUID businessId, UUID customerId) {
        Customer customer = customerRepository.findByBusinessIdAndId(businessId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", "id", customerId));

        customerRepository.delete(customer);
    }
}
