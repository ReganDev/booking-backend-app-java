package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.UserRequest;
import com.dev.bookingapp.javabookingapp.dto.response.UserResponse;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.User;
import com.dev.bookingapp.javabookingapp.exception.ConflictException;
import com.dev.bookingapp.javabookingapp.exception.ResourceNotFoundException;
import com.dev.bookingapp.javabookingapp.mapper.UserMapper;
import com.dev.bookingapp.javabookingapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final BusinessService businessService;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public UserResponse getById(UUID businessId, UUID userId) {
        User user = userRepository.findById(userId)
                .filter(u -> u.getBusiness().getId().equals(businessId))
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        return userMapper.toResponse(user);
    }

    @Transactional(readOnly = true)
    public User getEntityById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
    }

    @Transactional(readOnly = true)
    public List<UserResponse> getAllByBusinessId(UUID businessId) {
        return userRepository.findByBusinessIdAndIsActiveTrue(businessId)
                .stream()
                .map(userMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UserResponse> getStaffByBusinessId(UUID businessId) {
        return userRepository.findActiveStaffByBusinessId(businessId)
                .stream()
                .map(userMapper::toResponse)
                .toList();
    }

    @Transactional
    public UserResponse create(UUID businessId, UserRequest request, String password) {
        Business business = businessService.getEntityById(businessId);

        if (userRepository.existsByBusinessIdAndEmail(businessId, request.getEmail())) {
            throw new ConflictException("A user with this email already exists in this business");
        }

        User user = userMapper.toEntity(request);
        user.setBusiness(business);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setIsActive(true);
        user.setEmailVerified(false);

        User saved = userRepository.save(user);
        return userMapper.toResponse(saved);
    }

    @Transactional
    public UserResponse update(UUID businessId, UUID userId, UserRequest request) {
        User user = userRepository.findById(userId)
                .filter(u -> u.getBusiness().getId().equals(businessId))
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        if (request.getEmail() != null && !request.getEmail().equals(user.getEmail())) {
            if (userRepository.existsByBusinessIdAndEmail(businessId, request.getEmail())) {
                throw new ConflictException("A user with this email already exists in this business");
            }
        }

        userMapper.updateEntity(request, user);
        User saved = userRepository.save(user);
        return userMapper.toResponse(saved);
    }

    @Transactional
    public void deactivate(UUID businessId, UUID userId) {
        User user = userRepository.findById(userId)
                .filter(u -> u.getBusiness().getId().equals(businessId))
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        user.setIsActive(false);
        userRepository.save(user);
    }
}
