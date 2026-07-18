package com.dev.bookingapp.javabookingapp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessAccountResponse {
    private BusinessResponse business;
    private UserResponse owner;
}
