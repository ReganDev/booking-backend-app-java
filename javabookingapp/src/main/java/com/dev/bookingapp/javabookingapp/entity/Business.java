package com.dev.bookingapp.javabookingapp.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "businesses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class Business extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private String email;

    private String phone;

    // Address fields
    @Column(name = "address_line1")
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    private String city;

    private String state;

    @Column(name = "postal_code")
    private String postalCode;

    @Builder.Default
    private String country = "United Kingdom";

    // Settings
    @Builder.Default
    @Column(nullable = false)
    private String timezone = "Europe/London";

    @Builder.Default
    @Column(nullable = false, length = 3)
    private String currency = "GBP";

    @Column(name = "logo_url")
    private String logoUrl;

    // Booking settings
    @Builder.Default
    @Column(name = "booking_advance_days")
    private Integer bookingAdvanceDays = 30;

    @Builder.Default
    @Column(name = "booking_notice_hours")
    private Integer bookingNoticeHours = 24;

    @Builder.Default
    @Column(name = "cancellation_notice_hours")
    private Integer cancellationNoticeHours = 24;

    @Builder.Default
    @Column(name = "slot_duration_minutes")
    private Integer slotDurationMinutes = 30;

    @Builder.Default
    @Column(name = "buffer_minutes")
    private Integer bufferMinutes = 0;

    @Builder.Default
    @Column(name = "is_active")
    private Boolean isActive = true;

    // Relationships
    @OneToMany(mappedBy = "business", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<User> users = new ArrayList<>();

    @OneToMany(mappedBy = "business", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Service> services = new ArrayList<>();

    @OneToMany(mappedBy = "business", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Schedule> schedules = new ArrayList<>();

    @OneToMany(mappedBy = "business", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Customer> customers = new ArrayList<>();

    @OneToMany(mappedBy = "business", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Booking> bookings = new ArrayList<>();

    @OneToMany(mappedBy = "business", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<BlockedTime> blockedTimes = new ArrayList<>();
}
