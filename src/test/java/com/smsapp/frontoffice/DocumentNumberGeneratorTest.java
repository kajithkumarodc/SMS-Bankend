package com.smsapp.frontoffice;

import com.smsapp.academics.AcademicYear;
import com.smsapp.frontoffice.DocumentNumberGenerator.YearChoice;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Which academic year a document number is issued under ({@link DocumentNumberGenerator#resolveYear}). */
class DocumentNumberGeneratorTest {

    private static AcademicYear year(String start, String end) {
        AcademicYear y = new AcademicYear();
        y.setId(UUID.randomUUID());
        y.setName(start.substring(0, 4) + "-" + end.substring(0, 4));
        y.setStartDate(LocalDate.parse(start));
        y.setEndDate(LocalDate.parse(end));
        return y;
    }

    @Test
    void usesTheAcademicYearWhoseDatesIncludeTheDay() {
        AcademicYear y2026 = year("2026-06-01", "2027-04-30");
        YearChoice choice = DocumentNumberGenerator.resolveYear(LocalDate.of(2027, 2, 10), Optional.of(y2026), Optional.of(y2026));
        assertThat(choice.year()).isEqualTo(2026); // Feb 2027 is still in the 2026-2027 year
        assertThat(choice.academicYearId()).isEqualTo(y2026.getId());
    }

    @Test
    void infersANewYearThatHasNotBeenSetUpFromTheSchoolsYearStart() {
        // The reported case: only 2025-2026 (Jun 2025 - Apr 2026) exists, and it's now Sep 2026.
        AcademicYear y2025 = year("2025-06-01", "2026-04-30");
        YearChoice choice = DocumentNumberGenerator.resolveYear(LocalDate.of(2026, 9, 26), Optional.empty(), Optional.of(y2025));
        assertThat(choice.year()).isEqualTo(2026);
        assertThat(choice.academicYearId()).isNull();
    }

    @Test
    void theNewYearStartsExactlyOnTheSchoolsYearStartDay() {
        AcademicYear y2025 = year("2025-06-01", "2026-04-30");
        assertThat(DocumentNumberGenerator.resolveYear(LocalDate.of(2026, 5, 31), Optional.empty(), Optional.of(y2025)).year())
                .isEqualTo(2025); // summer break after 2025-2026 ends, before 2026-2027 starts
        assertThat(DocumentNumberGenerator.resolveYear(LocalDate.of(2026, 6, 1), Optional.empty(), Optional.of(y2025)).year())
                .isEqualTo(2026);
    }

    @Test
    void fallsBackToTheCalendarYearWithNoAcademicYears() {
        assertThat(DocumentNumberGenerator.resolveYear(LocalDate.of(2026, 3, 1), Optional.empty(), Optional.empty()).year())
                .isEqualTo(2026);
    }

    @Test
    void formatsWithAFiveDigitSequence() {
        assertThat(DocumentNumberGenerator.format("DSP", 2026, 1)).isEqualTo("DSP-2026-00001");
        assertThat(DocumentNumberGenerator.format("DSP", 2026, 123456)).isEqualTo("DSP-2026-123456");
    }
}
