package com.dev.bookingapp.javabookingapp.config;

import com.dev.bookingapp.javabookingapp.security.TenantAccessInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final TenantAccessInterceptor tenantAccessInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantAccessInterceptor)
                .addPathPatterns("/api/v1/businesses/**");
    }
}
