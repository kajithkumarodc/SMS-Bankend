package com.smsapp.payroll;

import com.smsapp.staff.LeaveRequest;
import com.smsapp.staff.StaffAttendance;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * How each day of a month counts toward a staff member's salary. A day with an attendance mark counts as marked
 * (Present and Late are present; Half Day and Half Day (Second Half) are half present, half leave; Absent is
 * leave; Holiday is paid). A day without a mark counts as:
 * <ul>
 *   <li>upcoming (not paid yet) if it is today or later -- it has not finished, so it is not earned yet;</li>
 *   <li>paid leave if an approved leave request covers it;</li>
 *   <li>a weekly off (paid) if it is a Sunday;</li>
 *   <li>otherwise an <b>unmarked</b> day, which is taken as leave (unpaid).</li>
 * </ul>
 */
record PayrollAttendance(int daysInMonth, int presentFull, int halfDays, int absent, int holidays, int weeklyOffs,
                         int paidLeave, int unmarked, int upcoming) {

    /** The day of the week that is a weekly off, when nothing is marked for it. */
    static final DayOfWeek WEEKLY_OFF = DayOfWeek.SUNDAY;

    static PayrollAttendance of(YearMonth month, Collection<StaffAttendance> marks, Collection<LeaveRequest> approvedLeave,
                                LocalDate today) {
        Map<LocalDate, String> byDate = new HashMap<>();
        marks.forEach(mark -> byDate.put(mark.getAttendanceDate(), mark.getStatus()));
        int presentFull = 0;
        int halfDays = 0;
        int absent = 0;
        int holidays = 0;
        int weeklyOffs = 0;
        int paidLeave = 0;
        int unmarked = 0;
        int upcoming = 0;
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            String status = byDate.get(date);
            if (status != null) {
                switch (status) {
                    case "PRESENT", "LATE" -> presentFull++;
                    case "HALF_DAY", "HALF_DAY_SECOND_HALF" -> halfDays++;
                    case "ABSENT" -> absent++;
                    default -> holidays++; // HOLIDAY
                }
            } else if (!date.isBefore(today)) {
                upcoming++;
            } else if (covers(approvedLeave, date)) {
                paidLeave++;
            } else if (date.getDayOfWeek() == WEEKLY_OFF) {
                weeklyOffs++;
            } else {
                unmarked++;
            }
        }
        return new PayrollAttendance(month.lengthOfMonth(), presentFull, halfDays, absent, holidays, weeklyOffs, paidLeave,
                unmarked, upcoming);
    }

    private static boolean covers(Collection<LeaveRequest> approvedLeave, LocalDate date) {
        // A half-day leave never covers a whole day: the staff member is expected to be marked Half Day for it.
        return approvedLeave.stream().anyMatch(l -> l.getHalfDay() == null
                && !date.isBefore(l.getStartDate()) && !date.isAfter(l.getEndDate()));
    }

    /** Present days: full days present, plus half of each half day. */
    BigDecimal presentDays() {
        return BigDecimal.valueOf(presentFull).add(BigDecimal.valueOf(halfDays).divide(BigDecimal.valueOf(2)));
    }

    /** Days earned so far: every day except leave days and upcoming days. */
    BigDecimal payableDays() {
        return BigDecimal.valueOf(daysInMonth).subtract(leaveDays()).subtract(BigDecimal.valueOf(upcoming));
    }

    /** Unpaid leave days: absent days, unmarked days, and half of each half day. Upcoming days are not leave. */
    BigDecimal leaveDays() {
        return BigDecimal.valueOf(absent + unmarked).add(BigDecimal.valueOf(halfDays).divide(BigDecimal.valueOf(2)));
    }
}
