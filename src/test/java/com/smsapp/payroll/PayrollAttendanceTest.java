package com.smsapp.payroll;

import com.smsapp.staff.LeaveRequest;
import com.smsapp.staff.StaffAttendance;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** How each day of a month counts toward salary (see {@link PayrollAttendance}). */
class PayrollAttendanceTest {

    // October 2026: the 1st is a Thursday, so the Sundays are the 4th, 11th, 18th and 25th.
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    private static StaffAttendance mark(int day, String status) {
        StaffAttendance mark = new StaffAttendance();
        mark.setAttendanceDate(OCTOBER.atDay(day));
        mark.setStatus(status);
        return mark;
    }

    private static LeaveRequest leave(int from, int to, String halfDay) {
        LeaveRequest leave = new LeaveRequest();
        leave.setStartDate(OCTOBER.atDay(from));
        leave.setEndDate(OCTOBER.atDay(to));
        leave.setHalfDay(halfDay);
        return leave;
    }

    @Test
    void aFinishedMonthWithNoMarksIsLeaveExceptSundays() {
        PayrollAttendance a = PayrollAttendance.of(OCTOBER, List.of(), List.of(), LocalDate.of(2026, 11, 1));
        assertThat(a.weeklyOffs()).isEqualTo(4);
        assertThat(a.unmarked()).isEqualTo(27);
        assertThat(a.upcoming()).isZero();
        assertThat(a.presentDays()).isEqualByComparingTo("0");
        assertThat(a.leaveDays()).isEqualByComparingTo("27");
        assertThat(a.payableDays()).isEqualByComparingTo("4");
    }

    @Test
    void marksCountByTheirStatus() {
        PayrollAttendance a = PayrollAttendance.of(OCTOBER, List.of(
                mark(1, "PRESENT"), mark(2, "LATE"), mark(3, "ABSENT"), mark(5, "HALF_DAY"),
                mark(6, "HALF_DAY_SECOND_HALF"), mark(7, "HOLIDAY"),
                mark(4, "PRESENT")), // a Sunday that was worked
                List.of(), LocalDate.of(2026, 10, 8));
        assertThat(a.presentFull()).isEqualTo(3);
        assertThat(a.halfDays()).isEqualTo(2);
        assertThat(a.absent()).isEqualTo(1);
        assertThat(a.holidays()).isEqualTo(1);
        assertThat(a.presentDays()).isEqualByComparingTo("4");
        assertThat(a.leaveDays()).isEqualByComparingTo("2"); // 1 absent + 2 halves of a day
        assertThat(a.upcoming()).isEqualTo(24); // the 8th to the 31st
    }

    @Test
    void todayAndLaterDaysAreUpcomingAndNotPaidYet() {
        PayrollAttendance a = PayrollAttendance.of(OCTOBER, List.of(mark(1, "PRESENT")), List.of(), LocalDate.of(2026, 10, 3));
        assertThat(a.upcoming()).isEqualTo(29);
        assertThat(a.unmarked()).isEqualTo(1); // the 2nd
        assertThat(a.payableDays()).isEqualByComparingTo("1");
    }

    @Test
    void approvedLeaveCoversUnmarkedDaysButAHalfDayLeaveDoesNot() {
        PayrollAttendance a = PayrollAttendance.of(OCTOBER, List.of(mark(1, "PRESENT")),
                List.of(leave(2, 3, null), leave(5, 5, "SECOND_HALF")), LocalDate.of(2026, 10, 7));
        assertThat(a.paidLeave()).isEqualTo(2); // the 2nd and 3rd
        assertThat(a.weeklyOffs()).isEqualTo(1); // the 4th
        assertThat(a.unmarked()).isEqualTo(2); // the 5th (half-day leave does not cover it) and the 6th
        // A mark wins over a leave request covering the same day.
        PayrollAttendance marked = PayrollAttendance.of(OCTOBER, List.of(mark(2, "PRESENT")), List.of(leave(2, 3, null)),
                LocalDate.of(2026, 10, 7));
        assertThat(marked.presentFull()).isEqualTo(1);
        assertThat(marked.paidLeave()).isEqualTo(1);
    }
}
