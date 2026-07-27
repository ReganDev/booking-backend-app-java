package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.ServiceRequest;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.Service;
import com.dev.bookingapp.javabookingapp.mapper.ServiceMapper;
import com.dev.bookingapp.javabookingapp.repository.BookingRepository;
import com.dev.bookingapp.javabookingapp.repository.ServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceServiceTest {

    @Mock
    private ServiceRepository serviceRepository;
    @Mock
    private BusinessService businessService;
    @Mock
    private BookingRepository bookingRepository;

    private ServiceService serviceService;

    @BeforeEach
    void setUp() {
        // Real generated mapper: the null-flag-through-builder behaviour that
        // bypasses @Builder.Default only reproduces via MapStruct's code.
        ServiceMapper realMapper = Mappers.getMapper(ServiceMapper.class);
        serviceService = new ServiceService(serviceRepository, realMapper, businessService, bookingRepository);
    }

    private ServiceRequest baseRequest() {
        ServiceRequest request = new ServiceRequest();
        request.setName("Mobile haircut");
        request.setDurationMinutes(45);
        return request;
    }

    private Service savedService(ArgumentCaptor<Service> captor, ServiceRequest request) {
        UUID businessId = UUID.randomUUID();
        Business business = Business.builder().build();
        when(businessService.getEntityById(businessId)).thenReturn(business);
        when(serviceRepository.existsByBusinessIdAndName(any(), any())).thenReturn(false);
        when(serviceRepository.save(any(Service.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        serviceService.create(businessId, request);
        verify(serviceRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void createDefaultsRequiresCustomerAddressToFalseWhenRequestOmitsIt() {
        Service saved = savedService(ArgumentCaptor.forClass(Service.class), baseRequest());
        assertThat(saved.getRequiresCustomerAddress()).isFalse();
    }

    @Test
    void createPersistsRequiresCustomerAddressWhenSet() {
        ServiceRequest request = baseRequest();
        request.setRequiresCustomerAddress(true);

        Service saved = savedService(ArgumentCaptor.forClass(Service.class), request);
        assertThat(saved.getRequiresCustomerAddress()).isTrue();
    }

    @Test
    void updateWithNullFlagLeavesExistingValueUnchanged() {
        UUID businessId = UUID.randomUUID();
        Business business = Business.builder().build();
        business.setId(businessId);
        Service existing = Service.builder()
                .business(business)
                .name("Mobile haircut")
                .durationMinutes(45)
                .requiresCustomerAddress(true)
                .build();
        UUID serviceId = UUID.randomUUID();
        when(serviceRepository.findById(serviceId)).thenReturn(Optional.of(existing));
        when(serviceRepository.save(any(Service.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ServiceRequest request = baseRequest();
        // requiresCustomerAddress intentionally null — partial update

        serviceService.update(businessId, serviceId, request);

        assertThat(existing.getRequiresCustomerAddress()).isTrue();
    }

    @Test
    void updateCanTurnFlagOff() {
        UUID businessId = UUID.randomUUID();
        Business business = Business.builder().build();
        business.setId(businessId);
        Service existing = Service.builder()
                .business(business)
                .name("Mobile haircut")
                .durationMinutes(45)
                .requiresCustomerAddress(true)
                .build();
        UUID serviceId = UUID.randomUUID();
        when(serviceRepository.findById(serviceId)).thenReturn(Optional.of(existing));
        when(serviceRepository.save(any(Service.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ServiceRequest request = baseRequest();
        request.setRequiresCustomerAddress(false);

        serviceService.update(businessId, serviceId, request);

        assertThat(existing.getRequiresCustomerAddress()).isFalse();
    }
}
