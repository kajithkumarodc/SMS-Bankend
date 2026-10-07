package com.smsapp.frontoffice;

import com.smsapp.academics.AcademicYear;
import com.smsapp.academics.AcademicYearRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Optional;
import java.util.UUID;

/**
 * Sequential, per-academic-year document numbers such as {@code DSP-2026-00001}. {@code YYYY} is the start
 * year of the academic year that today falls in, and the sequence restarts at 00001 when a new academic year
 * begins -- automatically, from the date, not from whichever year happens to be flagged "current" in Settings
 * (a flag that can be left pointing at a year that has already ended). See {@link #resolveYear}.
 *
 * <p>Each number comes from one atomic upsert on {@code document_number_counters} (V34), so concurrent saves
 * never get the same number. It must run inside the caller's transaction: if the save rolls back, the
 * increment does too, and no number is skipped.
 */
@Component
public class DocumentNumberGenerator {

    @PersistenceContext
    private EntityManager entityManager;

    private final AcademicYearRepository academicYearRepository;

    public DocumentNumberGenerator(AcademicYearRepository academicYearRepository) {
        this.academicYearRepository = academicYearRepository;
    }

    /** The issued number, plus the academic year record it falls in (null when that year isn't set up yet). */
    public record IssuedNumber(String value, UUID academicYearId) {
    }

    /** Which year goes in the number, and the matching academic year record if one exists. */
    record YearChoice(int year, UUID academicYearId) {
    }

    /** The next number in {@code series} for the academic year today falls in. */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedNumber next(String series) {
        return next(series, LocalDate.now());
    }

    /** The next number in {@code series} for the academic year {@code asOf} falls in. */
    @Transactional(propagation = Propagation.MANDATORY)
    public IssuedNumber next(String series, LocalDate asOf) {
        YearChoice choice = resolveYear(asOf,
                academicYearRepository.findFirstByStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(asOf, asOf),
                academicYearRepository.findFirstByOrderByStartDateDesc());
        Number value = (Number) entityManager.createNativeQuery(
                        "INSERT INTO document_number_counters (series, year, last_value) VALUES (:series, :year, 1) "
                                + "ON CONFLICT (series, year) DO UPDATE "
                                + "SET last_value = document_number_counters.last_value + 1 RETURNING last_value")
                .setParameter("series", series)
                .setParameter("year", choice.year())
                .getSingleResult();
        return new IssuedNumber(format(series, choice.year(), value.intValue()), choice.academicYearId());
    }

    /**
     * The academic year {@code date} belongs to:
     * <ol>
     *   <li>the academic year whose start/end dates include it, if one has been set up;</li>
     *   <li>otherwise, inferred from the school's year-start day (taken from the latest academic year on record):
     *       e.g. years starting 1 June put 26 Sep 2026 in 2026 and 15 May 2026 in 2025 -- so a year that hasn't
     *       been created in Settings yet is still numbered correctly;</li>
     *   <li>otherwise (no academic years at all), the calendar year.</li>
     * </ol>
     */
    static YearChoice resolveYear(LocalDate date, Optional<AcademicYear> containing, Optional<AcademicYear> latest) {
        if (containing.isPresent()) {
            return new YearChoice(containing.get().getStartDate().getYear(), containing.get().getId());
        }
        if (latest.isPresent()) {
            MonthDay yearStart = MonthDay.from(latest.get().getStartDate());
            int year = MonthDay.from(date).isBefore(yearStart) ? date.getYear() - 1 : date.getYear();
            return new YearChoice(year, null);
        }
        return new YearChoice(date.getYear(), null);
    }

    /** {@code DSP}, 2026, 1 -> {@code DSP-2026-00001}. Past 99999 the counter simply gets wider. */
    static String format(String series, int year, int value) {
        return String.format("%s-%d-%05d", series, year, value);
    }
}
