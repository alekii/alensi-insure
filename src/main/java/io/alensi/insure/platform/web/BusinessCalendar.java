package io.alensi.insure.platform.web;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Business dates (effective dates, incident dates) are local dates in the insurers' time zone, not
 * UTC: a policy starting "today" in Nairobi at 01:00 starts on Nairobi's date.
 */
@Component
public class BusinessCalendar {

    private final Clock clock;

    BusinessCalendar(@Value("${insure.business-time-zone:Africa/Nairobi}") ZoneId zone) {
        this.clock = Clock.system(zone);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public Instant now() {
        return clock.instant();
    }
}
