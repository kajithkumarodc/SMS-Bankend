package com.smsapp.report;

import com.smsapp.report.ReportDtos.AttendanceTrendPoint;
import com.smsapp.report.ReportDtos.ExamPerformancePoint;
import com.smsapp.report.ReportDtos.FeeCollectionReport;
import com.smsapp.report.ReportRepository.CollectionTotals;
import com.smsapp.report.ReportRepository.DailyStatusCount;
import com.smsapp.report.ReportRepository.ExamAverage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private ReportRepository reportRepository;

    private ReportService service() {
        return new ReportService(reportRepository);
    }

    private final UUID tenantId = UUID.randomUUID();

    private static DailyStatusCount row(LocalDate date, String status, long count) {
        return new DailyStatusCount() {
            public LocalDate getDate() {
                return date;
            }

            public String getStatus() {
                return status;
            }

            public long getCount() {
                return count;
            }
        };
    }

    // --- attendance trend --------------------------------------------

    @Test
    void attendanceTrendComputesPercentageFromPresentAbsentLate() {
        LocalDate day = LocalDate.of(2026, 3, 2);
        when(reportRepository.attendanceCountsByDay(any(), any(), any()))
                .thenReturn(List.of(row(day, "PRESENT", 3), row(day, "ABSENT", 1)));

        List<AttendanceTrendPoint> trend = service().attendanceTrend(tenantId, day, day);

        assertThat(trend).hasSize(1);
        AttendanceTrendPoint point = trend.get(0);
        assertThat(point.date()).isEqualTo(day);
        assertThat(point.present()).isEqualTo(3);
        assertThat(point.absent()).isEqualTo(1);
        assertThat(point.late()).isZero();
        assertThat(point.total()).isEqualTo(4);
        assertThat(point.attendancePercentage()).isEqualTo(75.0);
    }

    @Test
    void attendanceTrendCountsLateAsAttended() {
        LocalDate day = LocalDate.of(2026, 3, 2);
        when(reportRepository.attendanceCountsByDay(any(), any(), any()))
                .thenReturn(List.of(row(day, "PRESENT", 2), row(day, "LATE", 1), row(day, "ABSENT", 1)));

        AttendanceTrendPoint point = service().attendanceTrend(tenantId, day, day).get(0);

        assertThat(point.late()).isEqualTo(1);
        assertThat(point.total()).isEqualTo(4);
        assertThat(point.attendancePercentage()).isEqualTo(75.0); // (2 + 1) / 4
    }

    @Test
    void attendanceTrendKeepsOneEntryPerDayInDateOrder() {
        LocalDate d1 = LocalDate.of(2026, 3, 1);
        LocalDate d2 = LocalDate.of(2026, 3, 2);
        when(reportRepository.attendanceCountsByDay(any(), any(), any())).thenReturn(List.of(
                row(d1, "PRESENT", 1), row(d1, "ABSENT", 1),
                row(d2, "PRESENT", 4)));

        List<AttendanceTrendPoint> trend = service().attendanceTrend(tenantId, d1, d2);

        assertThat(trend).extracting(AttendanceTrendPoint::date).containsExactly(d1, d2);
        assertThat(trend.get(0).attendancePercentage()).isEqualTo(50.0);
        assertThat(trend.get(1).attendancePercentage()).isEqualTo(100.0);
    }

    @Test
    void attendanceTrendIsEmptyWhenNothingMarked() {
        when(reportRepository.attendanceCountsByDay(any(), any(), any())).thenReturn(List.of());

        assertThat(service().attendanceTrend(tenantId, LocalDate.now(), LocalDate.now())).isEmpty();
    }

    // --- academic performance --------------------------------------

    @Test
    void academicPerformanceRoundsTheAverageToTwoDecimals() {
        UUID examId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        when(reportRepository.averageMarksByExamForClass(any(), any())).thenReturn(List.of(new ExamAverage() {
            public UUID getExamId() {
                return examId;
            }

            public String getExamName() {
                return "Mid-term";
            }

            public UUID getSubjectId() {
                return subjectId;
            }

            public LocalDate getExamDate() {
                return LocalDate.of(2026, 4, 1);
            }

            public BigDecimal getMaxMarks() {
                return new BigDecimal("100.00");
            }

            public Double getAverageMarks() {
                return 70.666666;
            }

            public long getStudentsGraded() {
                return 3;
            }
        }));

        ExamPerformancePoint point = service().academicPerformance(tenantId, UUID.randomUUID()).get(0);

        assertThat(point.examName()).isEqualTo("Mid-term");
        assertThat(point.averageMarks()).isEqualTo(70.67);
        assertThat(point.studentsGraded()).isEqualTo(3);
    }

    // --- fee collection ------------------------------------------

    @Test
    void feeCollectionComputesOutstandingAsInvoicedMinusCollected() {
        when(reportRepository.collectionTotals(any())).thenReturn(new CollectionTotals() {
            public BigDecimal getInvoiced() {
                return new BigDecimal("10000.00");
            }

            public BigDecimal getCollected() {
                return new BigDecimal("5000.00");
            }
        });
        when(reportRepository.overdueInvoices(any(), any())).thenReturn(List.of());

        FeeCollectionReport report = service().feeCollection(tenantId);

        assertThat(report.totalInvoiced()).isEqualByComparingTo("10000.00");
        assertThat(report.totalCollected()).isEqualByComparingTo("5000.00");
        assertThat(report.outstanding()).isEqualByComparingTo("5000.00");
        assertThat(report.overdueInvoices()).isEmpty();
    }

    @Test
    void feeCollectionTreatsNullSumsAsZeroWhenThereAreNoInvoices() {
        when(reportRepository.collectionTotals(any())).thenReturn(new CollectionTotals() {
            public BigDecimal getInvoiced() {
                return null;
            }

            public BigDecimal getCollected() {
                return null;
            }
        });
        when(reportRepository.overdueInvoices(any(), any())).thenReturn(List.of());

        FeeCollectionReport report = service().feeCollection(tenantId);

        assertThat(report.totalInvoiced()).isEqualByComparingTo("0");
        assertThat(report.totalCollected()).isEqualByComparingTo("0");
        assertThat(report.outstanding()).isEqualByComparingTo("0");
    }
}
