package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.ScheduleBreakRequest;
import com.dev.bookingapp.javabookingapp.dto.request.ScheduleRequest;
import com.dev.bookingapp.javabookingapp.dto.response.ScheduleResponse;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.Schedule;
import com.dev.bookingapp.javabookingapp.entity.ScheduleBreak;
import com.dev.bookingapp.javabookingapp.entity.User;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.exception.ResourceNotFoundException;
import com.dev.bookingapp.javabookingapp.mapper.ScheduleMapper;
import com.dev.bookingapp.javabookingapp.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final ScheduleMapper scheduleMapper;
    private final BusinessService businessService;
    private final UserService userService;

    @Transactional(readOnly = true)
    public List<ScheduleResponse> getBusinessSchedule(UUID businessId) {
        return scheduleMapper.toResponseList(
                scheduleRepository.findByBusinessIdAndUserIsNull(businessId)
        );
    }

    @Transactional(readOnly = true)
    public List<ScheduleResponse> getStaffSchedule(UUID businessId, UUID userId) {
        return scheduleMapper.toResponseList(
                scheduleRepository.findByBusinessIdAndUserId(businessId, userId)
        );
    }

    @Transactional
    public ScheduleResponse createOrUpdate(UUID businessId, ScheduleRequest request) {
        Business business = businessService.getEntityById(businessId);
        User user = null;

        if (request.getUserId() != null) {
            user = userService.getEntityById(request.getUserId());
            if (!user.getBusiness().getId().equals(businessId)) {
                throw new BadRequestException("User does not belong to this business");
            }
        }

        if (request.getEndTime().isBefore(request.getStartTime()) ||
            request.getEndTime().equals(request.getStartTime())) {
            throw new BadRequestException("End time must be after start time");
        }

        // Find existing schedule for this day
        Schedule schedule;
        if (user == null) {
            schedule = scheduleRepository
                    .findByBusinessIdAndUserIsNullAndDayOfWeek(businessId, request.getDayOfWeek())
                    .orElse(null);
        } else {
            schedule = scheduleRepository
                    .findByBusinessIdAndUserIdAndDayOfWeek(businessId, user.getId(), request.getDayOfWeek())
                    .orElse(null);
        }

        if (schedule == null) {
            schedule = new Schedule();
            schedule.setBusiness(business);
            schedule.setUser(user);
            schedule.setDayOfWeek(request.getDayOfWeek());
        }

        schedule.setStartTime(request.getStartTime());
        schedule.setEndTime(request.getEndTime());
        schedule.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);

        // Handle breaks
        schedule.getBreaks().clear();
        if (request.getBreaks() != null) {
            for (ScheduleBreakRequest breakRequest : request.getBreaks()) {
                if (breakRequest.getEndTime().isBefore(breakRequest.getStartTime())) {
                    throw new BadRequestException("Break end time must be after start time");
                }
                if (breakRequest.getStartTime().isBefore(request.getStartTime()) ||
                    breakRequest.getEndTime().isAfter(request.getEndTime())) {
                    throw new BadRequestException("Breaks must be within schedule hours");
                }

                ScheduleBreak scheduleBreak = scheduleMapper.toBreakEntity(breakRequest);
                scheduleBreak.setSchedule(schedule);
                schedule.getBreaks().add(scheduleBreak);
            }
        }

        Schedule saved = scheduleRepository.save(schedule);
        return scheduleMapper.toResponse(saved);
    }

    @Transactional
    public List<ScheduleResponse> setWeeklySchedule(UUID businessId, UUID userId, List<ScheduleRequest> requests) {
        List<ScheduleResponse> responses = new ArrayList<>();
        for (ScheduleRequest request : requests) {
            request.setUserId(userId);
            responses.add(createOrUpdate(businessId, request));
        }
        return responses;
    }

    @Transactional
    public void deleteSchedule(UUID businessId, UUID scheduleId) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .filter(s -> s.getBusiness().getId().equals(businessId))
                .orElseThrow(() -> new ResourceNotFoundException("Schedule", "id", scheduleId));

        scheduleRepository.delete(schedule);
    }
}
