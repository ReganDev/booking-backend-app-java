package com.dev.bookingapp.javabookingapp.mapper;

import com.dev.bookingapp.javabookingapp.dto.request.UserRequest;
import com.dev.bookingapp.javabookingapp.dto.response.UserResponse;
import com.dev.bookingapp.javabookingapp.entity.User;
import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "businessId", source = "business.id")
    @Mapping(target = "fullName", expression = "java(user.getFullName())")
    UserResponse toResponse(User user);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "business", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "isActive", ignore = true)
    @Mapping(target = "emailVerified", ignore = true)
    @Mapping(target = "lastLoginAt", ignore = true)
    @Mapping(target = "staffServices", ignore = true)
    @Mapping(target = "schedules", ignore = true)
    @Mapping(target = "bookings", ignore = true)
    @Mapping(target = "refreshTokens", ignore = true)
    User toEntity(UserRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "business", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "isActive", ignore = true)
    @Mapping(target = "emailVerified", ignore = true)
    @Mapping(target = "lastLoginAt", ignore = true)
    @Mapping(target = "staffServices", ignore = true)
    @Mapping(target = "schedules", ignore = true)
    @Mapping(target = "bookings", ignore = true)
    @Mapping(target = "refreshTokens", ignore = true)
    void updateEntity(UserRequest request, @MappingTarget User user);
}
