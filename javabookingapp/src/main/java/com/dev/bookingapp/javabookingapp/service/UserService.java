package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.UserRequest;
import com.dev.bookingapp.javabookingapp.dto.response.UserResponse;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.User;
import com.dev.bookingapp.javabookingapp.entity.enums.UserRole;
import com.dev.bookingapp.javabookingapp.exception.ConflictException;
import com.dev.bookingapp.javabookingapp.exception.ForbiddenException;
import com.dev.bookingapp.javabookingapp.exception.ResourceNotFoundException;
import com.dev.bookingapp.javabookingapp.mapper.UserMapper;
import com.dev.bookingapp.javabookingapp.repository.UserRepository;
import com.dev.bookingapp.javabookingapp.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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

        if (request.getRole() != null && request.getRole() != user.getRole()) {
            // ADMIN is the platform role; it can never be granted through
            // business endpoints, or any business user could escalate to it
            if (request.getRole() == UserRole.ADMIN || user.getRole() == UserRole.ADMIN) {
                throw new ForbiddenException("The admin role cannot be assigned or removed here");
            }
            if (!callerIsOwner()) {
                throw new ForbiddenException("Only the business owner can change user roles");
            }
        }

        userMapper.updateEntity(request, user);
        User saved = userRepository.save(user);
        return userMapper.toResponse(saved);
    }

    private boolean callerIsOwner() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getPrincipal() instanceof UserPrincipal principal
                && UserRole.OWNER.name().equals(principal.getRole());
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
