package com.dev.bookingapp.javabookingapp.mapper;

import com.dev.bookingapp.javabookingapp.dto.request.BookingRequest;
import com.dev.bookingapp.javabookingapp.dto.response.BookingResponse;
import com.dev.bookingapp.javabookingapp.entity.Booking;
import com.dev.bookingapp.javabookingapp.entity.Customer;
import com.dev.bookingapp.javabookingapp.entity.Service;
import com.dev.bookingapp.javabookingapp.entity.User;
import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface BookingMapper {

    @Mapping(target = "businessId", source = "business.id")
    @Mapping(target = "customer", source = "customer", qualifiedByName = "toCustomerInfo")
    @Mapping(target = "service", source = "service", qualifiedByName = "toServiceInfo")
    @Mapping(target = "staff", source = "staff", qualifiedByName = "toStaffInfo")
    BookingResponse toResponse(Booking booking);

    @Named("toCustomerInfo")
    default BookingResponse.CustomerInfo toCustomerInfo(Customer customer) {
        if (customer == null) return null;
        return BookingResponse.CustomerInfo.builder()
                .id(customer.getId())
                .firstName(customer.getFirstName())
                .lastName(customer.getLastName())
                .email(customer.getEmail())
                .phone(customer.getPhone())
                .build();
    }

    @Named("toServiceInfo")
    default BookingResponse.ServiceInfo toServiceInfo(Service service) {
        if (service == null) return null;
        return BookingResponse.ServiceInfo.builder()
                .id(service.getId())
                .name(service.getName())
                .durationMinutes(service.getDurationMinutes())
                .price(service.getPrice())
                .color(service.getColor())
                .build();
    }

    @Named("toStaffInfo")
    default BookingResponse.StaffInfo toStaffInfo(User staff) {
        if (staff == null) return null;
        return BookingResponse.StaffInfo.builder()
                .id(staff.getId())
                .firstName(staff.getFirstName())
                .lastName(staff.getLastName())
                .fullName(staff.getFullName())
                .build();
    }

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "business", ignore = true)
    @Mapping(target = "customer", ignore = true)
    @Mapping(target = "service", ignore = true)
    @Mapping(target = "staff", ignore = true)
    @Mapping(target = "endDatetime", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "price", ignore = true)
    @Mapping(target = "cancelledAt", ignore = true)
    @Mapping(target = "cancellationReason", ignore = true)
    Booking toEntity(BookingRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "business", ignore = true)
    @Mapping(target = "customer", ignore = true)
    @Mapping(target = "service", ignore = true)
    @Mapping(target = "staff", ignore = true)
    @Mapping(target = "endDatetime", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "price", ignore = true)
    @Mapping(target = "cancelledAt", ignore = true)
    @Mapping(target = "cancellationReason", ignore = true)
    void updateEntity(BookingRequest request, @MappingTarget Booking booking);
}
