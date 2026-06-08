package com.dev.bookingapp.javabookingapp.service;

import com.dev.bookingapp.javabookingapp.dto.request.LoginRequest;
import com.dev.bookingapp.javabookingapp.dto.request.RefreshTokenRequest;
import com.dev.bookingapp.javabookingapp.dto.request.RegisterRequest;
import com.dev.bookingapp.javabookingapp.dto.response.AuthResponse;
import com.dev.bookingapp.javabookingapp.dto.response.BusinessResponse;
import com.dev.bookingapp.javabookingapp.dto.response.UserResponse;
import com.dev.bookingapp.javabookingapp.entity.Business;
import com.dev.bookingapp.javabookingapp.entity.RefreshToken;
import com.dev.bookingapp.javabookingapp.entity.User;
import com.dev.bookingapp.javabookingapp.entity.enums.UserRole;
import com.dev.bookingapp.javabookingapp.exception.BadRequestException;
import com.dev.bookingapp.javabookingapp.exception.ConflictException;
import com.dev.bookingapp.javabookingapp.exception.UnauthorizedException;
import com.dev.bookingapp.javabookingapp.mapper.BusinessMapper;
import com.dev.bookingapp.javabookingapp.mapper.UserMapper;
import com.dev.bookingapp.javabookingapp.repository.BusinessRepository;
import com.dev.bookingapp.javabookingapp.repository.RefreshTokenRepository;
import com.dev.bookingapp.javabookingapp.repository.UserRepository;
import com.dev.bookingapp.javabookingapp.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final UserMapper userMapper;
    private final BusinessMapper businessMapper;

    private static final Pattern NONLATIN = Pattern.compile("[^\\w-]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s]");

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Check if email already exists
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new ConflictException("Email is already registered");
        }

        // Create business
        String slug = generateSlug(request.getBusinessName());
        Business business = Business.builder()
                .name(request.getBusinessName())
                .slug(slug)
                .email(request.getEmail())
                .timezone(request.getTimezone() != null ? request.getTimezone() : "United Kingdom/Belfast")
                .currency(request.getCurrency() != null ? request.getCurrency() : "GBP")
                .isActive(true)
                .build();

        business = businessRepository.save(business);

        // Create owner user
        User user = User.builder()
                .business(business)
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .role(UserRole.OWNER)
                .isActive(true)
                .emailVerified(false)
                .acceptsBookings(true)
                .build();

        user = userRepository.save(user);

        return generateAuthResponse(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (!user.getIsActive()) {
            throw new UnauthorizedException("Account is deactivated");
        }

        // Update last login
        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.save(user);

        return generateAuthResponse(user);
    }

    @Transactional
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        String tokenHash = hashToken(request.getRefreshToken());

        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));

        if (!storedToken.isValid()) {
            throw new UnauthorizedException("Refresh token is expired or revoked");
        }

        User user = storedToken.getUser();

        // Revoke old token
        storedToken.setRevokedAt(OffsetDateTime.now());
        refreshTokenRepository.save(storedToken);

        return generateAuthResponse(user);
    }

    @Transactional
    public void logout(String refreshToken) {
        String tokenHash = hashToken(refreshToken);
        refreshTokenRepository.findByTokenHash(tokenHash)
                .ifPresent(token -> {
                    token.setRevokedAt(OffsetDateTime.now());
                    refreshTokenRepository.save(token);
                });
    }

    @Transactional
    public void logoutAll(User user) {
        refreshTokenRepository.revokeAllByUserId(user.getId(), OffsetDateTime.now());
    }

    private AuthResponse generateAuthResponse(User user) {
        String accessToken = jwtService.generateAccessToken(
                user.getId(),
                user.getBusiness().getId(),
                user.getEmail(),
                user.getRole().name()
        );

        String refreshToken = jwtService.generateRefreshToken(user.getId());

        // Store refresh token
        RefreshToken storedToken = RefreshToken.builder()
                .user(user)
                .tokenHash(hashToken(refreshToken))
                .expiresAt(OffsetDateTime.now().plusSeconds(jwtService.getRefreshTokenExpirationMs() / 1000))
                .build();
        refreshTokenRepository.save(storedToken);

        UserResponse userResponse = userMapper.toResponse(user);
        BusinessResponse businessResponse = businessMapper.toResponse(user.getBusiness());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtService.getAccessTokenExpirationMs() / 1000)
                .user(userResponse)
                .business(businessResponse)
                .build();
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String generateSlug(String input) {
        String nowhitespace = WHITESPACE.matcher(input).replaceAll("-");
        String normalized = Normalizer.normalize(nowhitespace, Normalizer.Form.NFD);
        String slug = NONLATIN.matcher(normalized).replaceAll("");
        slug = slug.toLowerCase(Locale.ENGLISH).replaceAll("-+", "-");
        slug = slug.replaceAll("^-|-$", "");

        String baseSlug = slug;
        int counter = 1;
        while (businessRepository.existsBySlug(slug)) {
            slug = baseSlug + "-" + counter++;
        }

        return slug;
    }
}
