package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.BusinessRequest;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.mapper.BusinessMapper;
import com.dev.bookingapp.javabookingapp.repository.BusinessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessServiceTest {

    @Mock
    private BusinessRepository businessRepository;
    @Mock
    private SupabaseStorageService storageService;

    private BusinessService businessService;

    @BeforeEach
    void setUp() {
        // Use the real generated mapper, not a hand-rolled stub: the bug
        // only reproduces via MapStruct's actual generated code, which
        // calls business.autoConfirmBookings(request.getAutoConfirmBookings())
        // on the builder. A null there is an explicit "set to null", which
        // bypasses @Builder.Default entirely — `new Business()` would not
        // reproduce this because the field initializer still runs there.
        BusinessMapper realMapper = Mappers.getMapper(BusinessMapper.class);
        businessService = new BusinessService(businessRepository, realMapper, storageService);
    }

    @Test
    void createDefaultsAutoConfirmBookingsToTrueWhenRequestOmitsIt() {
        BusinessRequest request = new BusinessRequest();
        request.setName("Absolutely Fabulous Hair and Beauty");
        request.setEmail("salon@example.com");
        // autoConfirmBookings intentionally left null, mirroring a client
        // that doesn't send the flag at all.

        when(businessRepository.existsBySlug(any())).thenReturn(false);
        when(businessRepository.save(any(Business.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        businessService.create(request);

        ArgumentCaptor<Business> captor = ArgumentCaptor.forClass(Business.class);
        verify(businessRepository).save(captor.capture());
        Business saved = captor.getValue();

        assertThat(saved.getAutoConfirmBookings()).isTrue();
    }
}
