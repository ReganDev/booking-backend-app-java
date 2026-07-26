package com.dev.bookingapp.javabookingapp.entity;

import com.dev.bookingapp.javabookingapp.entity.enums.RecurrenceFrequency;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * A standing appointment. The occurrences themselves are real {@link Booking}
 * rows pointing back here; this row only records the rule that produced them.
 */
@Entity
@Table(name = "booking_series")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class BookingSeries extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_id", nullable = false)
    private Service service;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id")
    private User staff;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private RecurrenceFrequency frequency;

    /**
     * How many occurrences were asked for. Bookings actually created can be
     * fewer, because occurrences that clashed with an existing booking are
     * skipped rather than failing the whole series.
     */
    @Column(name = "occurrence_count", nullable = false)
    private Integer occurrenceCount;

    @Column(name = "interval_weeks", nullable = false)
    private Integer intervalWeeks;

    @Column(name = "interval_months", nullable = false)
    private Integer intervalMonths;

    @Column(name = "first_start_datetime", nullable = false)
    private OffsetDateTime firstStartDatetime;
}
