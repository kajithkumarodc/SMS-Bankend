package com.smsapp.dashboard;

import java.math.BigDecimal;
import java.util.List;

/** Super Admin dashboard payload: headline KPIs, trends and module overviews, all computed server-side. */
public record SuperAdminDashboard(
        String monthLabel,
        String sessionLabel,
        Money money,
        People people,
        List<Ratio> ratios,
        List<DayPoint> monthDaily,
        List<MonthPoint> sessionMonthly,
        List<Slice> expenseByHead,
        Overview fees,
        Overview enquiries,
        Overview library,
        Overview studentAttendance,
        List<Slice> usersByRole) {

    public record Money(BigDecimal monthFees, BigDecimal monthExpenses, BigDecimal totalOutstanding) {
    }

    public record People(long students, long studentsPresentToday, long activeStaff) {
    }

    /** A "value out of total" KPI, e.g. 4 of 6 invoices awaiting payment. */
    public record Ratio(String key, String label, long value, long total) {
    }

    public record DayPoint(int day, BigDecimal fees, BigDecimal expenses) {
    }

    public record MonthPoint(String month, BigDecimal fees, BigDecimal expenses) {
    }

    public record Slice(String name, BigDecimal value) {
    }

    /** A titled list of labelled counts; the frontend derives percentages from {@code total}. */
    public record Overview(long total, List<Item> items) {
    }

    public record Item(String key, String label, long count) {
    }
}
