package com.smsapp.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * The school's own wall clock.
 *
 * <p>{@code LocalDate.now()} resolves against the JVM's default zone, which on a
 * deployment platform (Railway, Heroku, Cloud Run) is UTC -- not where the school is.
 * For a school in IST that makes the server's "today" the previous calendar day
 * between 00:00 and 05:30 local time, so a teacher marking morning attendance was
 * told "Attendance date cannot be in the future" for a date that is plainly today.
 * Every date the domain reasons about -- attendance days, fee due dates, loan
 * issue/return dates, admission dates -- is a date in the school's calendar, so it
 * must be read off this clock rather than the JVM's.
 *
 * <p>Bound from application.yml: {@code app.timezone <- APP_TIMEZONE}. Defaults to
 * {@code Asia/Kolkata}; a deployment serving a school elsewhere overrides it.
 * A per-school column can replace this single value later without changing any
 * caller, which is why callers ask this type for the date instead of a {@link ZoneId}.
 */
@Component
public class SchoolClock {

    private final ZoneId zone;

    public SchoolClock(@Value("${app.timezone:Asia/Kolkata}") String timezone) {
        this.zone = (timezone == null || timezone.isBlank())
                ? ZoneId.of("Asia/Kolkata")
                : ZoneId.of(timezone);
    }

    /** Today in the school's calendar. */
    public LocalDate today() {
        return LocalDate.now(zone);
    }

    /** The current instant, offset for the school's zone. */
    public OffsetDateTime now() {
        return OffsetDateTime.now(zone);
    }

    /** The school's zone, for callers that must bucket timestamps by local day. */
    public ZoneId zone() {
        return zone;
    }
}
