package com.dev.bookingapp.javabookingapp.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PhotoDeleteRequest {

    @NotBlank(message = "Photo URL is required")
    private String photoUrl;
}
