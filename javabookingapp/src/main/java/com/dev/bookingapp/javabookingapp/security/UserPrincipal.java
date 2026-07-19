package com.dev.bookingapp.javabookingapp.security;

import com.dev.bookingapp.javabookingapp.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
public class UserPrincipal implements UserDetails {

    private UUID id;
    private UUID businessId;
    private String email;
    private String password;
    private String role;
    private Boolean isActive;
    private Boolean emailVerified;
    private Collection<? extends GrantedAuthority> authorities;

    public static UserPrincipal fromUser(User user) {
        List<GrantedAuthority> authorities = List.of(
                new SimpleGrantedAuthority("ROLE_" + user.getRole().name())
        );

        return UserPrincipal.builder()
                .id(user.getId())
                .businessId(user.getBusiness() != null ? user.getBusiness().getId() : null)
                .email(user.getEmail())
                .password(user.getPasswordHash())
                .role(user.getRole().name())
                .isActive(user.getIsActive())
                .emailVerified(user.getEmailVerified())
                .authorities(authorities)
                .build();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return isActive;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return Boolean.TRUE.equals(isActive)
                && (!"OWNER".equals(role) && !"CUSTOMER".equals(role)
                    || Boolean.TRUE.equals(emailVerified));
    }
}
