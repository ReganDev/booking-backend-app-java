package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.CustomerRequest;
import com.dev.bookingapp.javabookingapp.dto.response.CustomerResponse;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.Customer;
import com.dev.bookingapp.javabookingapp.exception.ConflictException;
import com.dev.bookingapp.javabookingapp.mapper.CustomerMapper;
import com.dev.bookingapp.javabookingapp.repository.CustomerRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the case-insensitive email matching. A business owner booking the same
 * returning customer in twice, typing "Jane@x.com" once and "jane@x.com" the next
 * time, must land on one customer row — UNIQUE(business_id, email) compares bytes
 * and will happily accept both spellings.
 */
@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    private static final UUID BUSINESS_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private BusinessService businessService;

    private CustomerService customerService;
    private Business business;

    @BeforeEach
    void setUp() {
        // The real generated mapper, not a stub: toEntity/updateEntity behaviour
        // (including the null-ignoring update strategy) is part of what's under test.
        CustomerMapper realMapper = Mappers.getMapper(CustomerMapper.class);
        customerService = new CustomerService(customerRepository, realMapper, businessService);

        business = Business.builder().build();
        business.setId(BUSINESS_ID);
    }

    private static CustomerRequest request(String email) {
        CustomerRequest request = new CustomerRequest();
        request.setEmail(email);
        request.setFirstName("Jane");
        request.setLastName("Doe");
        return request;
    }

    private Customer existingJane(String storedEmail) {
        Customer customer = Customer.builder()
                .business(business)
                .email(storedEmail)
                .firstName("Jane")
                .lastName("Doe")
                .build();
        customer.setId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        return customer;
    }

    @Test
    void getOrCreateReturnsExistingCustomerWhenEmailDiffersOnlyByCase() {
        Customer jane = existingJane("jane@example.com");
        when(customerRepository.findByBusinessIdAndEmailIgnoreCase(BUSINESS_ID, "jane@example.com"))
                .thenReturn(Optional.of(jane));

        CustomerResponse response = customerService.getOrCreate(BUSINESS_ID, request("Jane@Example.com"));

        assertThat(response.getId()).isEqualTo(jane.getId());
        // The whole point: no second row for the same person.
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    void getOrCreateTrimsSurroundingWhitespaceBeforeMatching() {
        Customer jane = existingJane("jane@example.com");
        when(customerRepository.findByBusinessIdAndEmailIgnoreCase(BUSINESS_ID, "jane@example.com"))
                .thenReturn(Optional.of(jane));

        CustomerResponse response = customerService.getOrCreate(BUSINESS_ID, request("  Jane@Example.com  "));

        assertThat(response.getId()).isEqualTo(jane.getId());
        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    void getOrCreateCreatesOneCustomerWithNormalisedEmailWhenNoneMatches() {
        when(customerRepository.findByBusinessIdAndEmailIgnoreCase(BUSINESS_ID, "jane@example.com"))
                .thenReturn(Optional.empty());
        when(customerRepository.existsByBusinessIdAndEmailIgnoreCase(BUSINESS_ID, "jane@example.com"))
                .thenReturn(false);
        when(businessService.getEntityById(BUSINESS_ID)).thenReturn(business);
        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        customerService.getOrCreate(BUSINESS_ID, request("Jane@Example.com"));

        ArgumentCaptor<Customer> saved = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).save(saved.capture());
        // Canonical form goes to the DB, so the next lookup — whatever its casing — hits.
        assertThat(saved.getValue().getEmail()).isEqualTo("jane@example.com");
        assertThat(saved.getValue().getBusiness()).isSameAs(business);
    }

    @Test
    void createRejectsCustomerWhoseEmailDiffersOnlyByCase() {
        when(businessService.getEntityById(BUSINESS_ID)).thenReturn(business);
        when(customerRepository.existsByBusinessIdAndEmailIgnoreCase(BUSINESS_ID, "jane@example.com"))
                .thenReturn(true);

        assertThatThrownBy(() -> customerService.create(BUSINESS_ID, request("JANE@EXAMPLE.COM")))
                .isInstanceOf(ConflictException.class);

        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    void updateAcceptsAnEmailThatDiffersFromItsOwnStoredValueOnlyByCase() {
        Customer jane = existingJane("Jane@Example.com");
        when(customerRepository.findByBusinessIdAndId(BUSINESS_ID, jane.getId()))
                .thenReturn(Optional.of(jane));
        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        customerService.update(BUSINESS_ID, jane.getId(), request("jane@example.com"));

        // Re-saving your own address in different casing is not a conflict with yourself,
        // so the taken-email check must never run.
        verify(customerRepository, never())
                .existsByBusinessIdAndEmailIgnoreCase(any(UUID.class), any(String.class));
        assertThat(jane.getEmail()).isEqualTo("jane@example.com");
    }

    @Test
    void updateRejectsAnEmailAlreadyTakenByAnotherCustomerInDifferentCase() {
        Customer jane = existingJane("jane@example.com");
        when(customerRepository.findByBusinessIdAndId(BUSINESS_ID, jane.getId()))
                .thenReturn(Optional.of(jane));
        when(customerRepository.existsByBusinessIdAndEmailIgnoreCase(BUSINESS_ID, "john@example.com"))
                .thenReturn(true);

        assertThatThrownBy(() ->
                customerService.update(BUSINESS_ID, jane.getId(), request("John@Example.com")))
                .isInstanceOf(ConflictException.class);

        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    void updateLeavesStoredEmailAloneWhenRequestOmitsIt() {
        Customer jane = existingJane("jane@example.com");
        when(customerRepository.findByBusinessIdAndId(BUSINESS_ID, jane.getId()))
                .thenReturn(Optional.of(jane));
        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CustomerRequest partial = new CustomerRequest();
        partial.setFirstName("Janet");
        partial.setLastName("Doe");
        // email intentionally null, mirroring a partial update

        customerService.update(BUSINESS_ID, jane.getId(), partial);

        // A null email must not be normalised into "" and written over the real address.
        assertThat(jane.getEmail()).isEqualTo("jane@example.com");
        assertThat(jane.getFirstName()).isEqualTo("Janet");
        verify(customerRepository, never())
                .existsByBusinessIdAndEmailIgnoreCase(eq(BUSINESS_ID), any(String.class));
    }
}
