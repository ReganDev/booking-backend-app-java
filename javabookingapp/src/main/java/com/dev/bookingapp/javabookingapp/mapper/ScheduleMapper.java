package com.dev.bookingapp.javabookingapp.mapper;

import com.dev.bookingapp.javabookingapp.dto.request.ScheduleBreakRequest;
import com.dev.bookingapp.javabookingapp.dto.request.ScheduleRequest;
import com.dev.bookingapp.javabookingapp.dto.response.ScheduleBreakResponse;
import com.dev.bookingapp.javabookingapp.dto.response.ScheduleResponse;
import com.dev.bookingapp.javabookingapp.entity.Schedule;
import com.dev.bookingapp.javabookingapp.entity.ScheduleBreak;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ScheduleMapper {

    @Mapping(target = "businessId", source = "business.id")
    @Mapping(target = "userId", source = "user.id")
    ScheduleResponse toResponse(Schedule schedule);

    ScheduleBreakResponse toBreakResponse(ScheduleBreak scheduleBreak);

    List<ScheduleResponse> toResponseList(List<Schedule> schedules);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "business", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "breaks", ignore = true)
    Schedule toEntity(ScheduleRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "schedule", ignore = true)
    ScheduleBreak toBreakEntity(ScheduleBreakRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "business", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "breaks", ignore = true)
    void updateEntity(ScheduleRequest request, @MappingTarget Schedule schedule);
}
