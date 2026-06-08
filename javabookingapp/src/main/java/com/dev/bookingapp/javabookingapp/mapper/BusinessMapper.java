package com.dev.bookingapp.javabookingapp.mapper;

import com.dev.bookingapp.javabookingapp.dto.request.BusinessRequest;
import com.dev.bookingapp.javabookingapp.dto.response.BusinessResponse;
import com.dev.bookingapp.javabookingapp.entity.Business;
import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface BusinessMapper {

    BusinessResponse toResponse(Business business);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "users", ignore = true)
    @Mapping(target = "services", ignore = true)
    @Mapping(target = "schedules", ignore = true)
    @Mapping(target = "customers", ignore = true)
    @Mapping(target = "bookings", ignore = true)
    @Mapping(target = "blockedTimes", ignore = true)
    @Mapping(target = "isActive", ignore = true)
    Business toEntity(BusinessRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "users", ignore = true)
    @Mapping(target = "services", ignore = true)
    @Mapping(target = "schedules", ignore = true)
    @Mapping(target = "customers", ignore = true)
    @Mapping(target = "bookings", ignore = true)
    @Mapping(target = "blockedTimes", ignore = true)
    @Mapping(target = "isActive", ignore = true)
    void updateEntity(BusinessRequest request, @MappingTarget Business business);
}
