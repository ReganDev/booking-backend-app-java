package com.dev.bookingapp.javabookingapp.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantAccessInterceptorTest {

    private final TenantAccessInterceptor interceptor = new TenantAccessInterceptor();

    private final UUID ownBusinessId = UUID.randomUUID();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(UUID businessId) {
        UserPrincipal principal = UserPrincipal.builder()
                .id(UUID.randomUUID())
                .businessId(businessId)
                .email("owner@example.com")
                .role("OWNER")
                .isActive(true)
                .authorities(List.of())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private MockHttpServletRequest requestFor(String businessId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/api/v1/businesses/" + businessId + "/bookings");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("businessId", businessId));
        return request;
    }

    @Test
    void allowsAnonymousRequestsThrough() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(requestFor(UUID.randomUUID().toString()), response, new Object());

        assertThat(allowed).isTrue();
    }

    @Test
    void allowsAccessToTheCallersOwnBusiness() throws Exception {
        authenticateAs(ownBusinessId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(requestFor(ownBusinessId.toString()), response, new Object());

        assertThat(allowed).isTrue();
    }

    @Test
    void blocksAccessToAnotherBusinessWith403() throws Exception {
        authenticateAs(ownBusinessId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(requestFor(UUID.randomUUID().toString()), response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void allowsRequestsWithoutABusinessIdPathVariable() throws Exception {
        authenticateAs(ownBusinessId);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/businesses/slug/some-salon");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("slug", "some-salon"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }
}
