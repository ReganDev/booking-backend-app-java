package com.dev.bookingapp.javabookingapp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Enables @Async, used for best-effort work after booking creation. */
@Configuration
@EnableAsync
public class AsyncConfig {
}
