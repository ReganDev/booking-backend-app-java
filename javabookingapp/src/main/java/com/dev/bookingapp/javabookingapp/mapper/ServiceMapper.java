package com.dev.bookingapp.javabookingapp.mapper;

import com.dev.bookingapp.javabookingapp.dto.request.ServiceRequest;
import com.dev.bookingapp.javabookingapp.dto.response.ServiceResponse;
import com.dev.bookingapp.javabookingapp.entity.Service;
import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface ServiceMapper {

    @Mapping(target = "businessId", source = "business.id")
    ServiceResponse toResponse(Service service);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "business", ignore = true)
    @Mapping(target = "staffServices", ignore = true)
    @Mapping(target = "bookings", ignore = true)
    Service toEntity(ServiceRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "business", ignore = true)
    @Mapping(target = "staffServices", ignore = true)
    @Mapping(target = "bookings", ignore = true)
    void updateEntity(ServiceRequest request, @MappingTarget Service service);
}
