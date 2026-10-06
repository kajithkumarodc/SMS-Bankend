package com.smsapp.dashboard;

import com.smsapp.dashboard.SuperAdminDashboard.DayPoint;
import com.smsapp.dashboard.SuperAdminDashboard.Item;
import com.smsapp.dashboard.SuperAdminDashboard.Money;
import com.smsapp.dashboard.SuperAdminDashboard.MonthPoint;
import com.smsapp.dashboard.SuperAdminDashboard.Overview;
import com.smsapp.dashboard.SuperAdminDashboard.People;
import com.smsapp.dashboard.SuperAdminDashboard.Ratio;
import com.smsapp.dashboard.SuperAdminDashboard.Slice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class SuperAdminDashboardService {

    private static final String COLLECTED = "case when type = 'PAYMENT' then amount else -amount end";

    private final JdbcTemplate jdbc;

    public SuperAdminDashboardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public SuperAdminDashboard build() {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = monthStart.plusMonths(1);
        int sessionStartYear = today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
        LocalDate sessionStart = LocalDate.of(sessionStartYear, 4, 1);
        LocalDate sessionEnd = sessionStart.plusYears(1);

        BigDecimal monthFees = money("select coalesce(sum(" + COLLECTED + "), 0) from fee_payments "
                + "where payment_date >= ? and payment_date < ?", monthStart, monthEnd);
        BigDecimal monthExpenses = money("select coalesce(sum(amount), 0) from expenses "
                + "where expense_date >= ? and expense_date < ?", monthStart, monthEnd);
        BigDecimal outstanding = money("select coalesce(sum(net_amount - paid_amount), 0) from invoices "
                + "where status <> 'PAID'");

        long students = count("select count(*) from students where status = 'ACTIVE'");
        long studentsPresent = count("select count(distinct a.student_id) from attendance_records a "
                + "join students s on s.id = a.student_id where s.status = 'ACTIVE' and a.date = ? "
                + "and a.status in ('PRESENT','LATE','HALF_DAY')", today);
        long activeStaff = count("select count(*) from staff_profiles where status = 'ACTIVE'");
        long staffPresent = count("select count(distinct staff_profile_id) from staff_attendance "
                + "where attendance_date = ? and status in ('PRESENT','LATE','HALF_DAY','HALF_DAY_SECOND_HALF')", today);

        long invoices = count("select count(*) from invoices");
        long unpaidInvoices = count("select count(*) from invoices where status <> 'PAID'");
        long leaves = count("select count(*) from leave_requests");
        long approvedLeaves = count("select count(*) from leave_requests where status = 'APPROVED'");
        long onLeaveToday = count("select count(*) from leave_requests where status = 'APPROVED' "
                + "and ? between start_date and end_date", today);
        long enquiries = count("select count(*) from admission_enquiries where not archived");
        long won = count("select count(*) from admission_enquiries where not archived and status = 'WON'");

        List<Ratio> ratios = List.of(
                new Ratio("fees-awaiting", "Fees awaiting payment", unpaidInvoices, invoices),
                new Ratio("leave-approved", "Staff approved leave", approvedLeaves, leaves),
                new Ratio("staff-on-leave", "Staff on leave today", onLeaveToday, activeStaff),
                new Ratio("leads", "Converted leads", won, enquiries),
                new Ratio("staff-present", "Staff present today", staffPresent, activeStaff),
                new Ratio("student-present", "Students present today", studentsPresent, students));

        return new SuperAdminDashboard(
                monthStart.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + today.getYear(),
                sessionStartYear + "-" + String.valueOf(sessionStartYear + 1).substring(2),
                new Money(monthFees, monthExpenses, outstanding),
                new People(students, studentsPresent, activeStaff),
                ratios,
                monthDaily(monthStart, monthEnd),
                sessionMonthly(sessionStart, sessionEnd),
                slices("select h.name, sum(e.amount) from expenses e join expense_heads h on h.id = e.expense_head_id "
                        + "where e.expense_date >= ? and e.expense_date < ? group by h.name order by 2 desc",
                        monthStart, monthEnd),
                overview(invoices, "select status, count(*) from invoices group by status",
                        new String[][] {{"PENDING", "Unpaid"}, {"PARTIALLY_PAID", "Partial"}, {"PAID", "Paid"}}),
                overview(enquiries, "select status, count(*) from admission_enquiries where not archived group by status",
                        new String[][] {{"ACTIVE", "Active"}, {"WON", "Won"}, {"PASSIVE", "Passive"},
                                {"LOST", "Lost"}, {"DEAD", "Dead"}}),
                libraryOverview(today),
                studentAttendanceOverview(today),
                slices("select r.name, count(ur.user_id) from roles r left join user_roles ur on ur.role_id = r.id "
                        + "group by r.name order by r.name"));
    }

    private List<DayPoint> monthDaily(LocalDate from, LocalDate to) {
        Map<Integer, BigDecimal> fees = byKey("select extract(day from payment_date)::int, sum(" + COLLECTED
                + ") from fee_payments where payment_date >= ? and payment_date < ? group by 1", from, to);
        Map<Integer, BigDecimal> expenses = byKey("select extract(day from expense_date)::int, sum(amount) "
                + "from expenses where expense_date >= ? and expense_date < ? group by 1", from, to);
        List<DayPoint> out = new ArrayList<>();
        for (int d = 1; d <= from.lengthOfMonth(); d++) {
            out.add(new DayPoint(d, fees.getOrDefault(d, BigDecimal.ZERO), expenses.getOrDefault(d, BigDecimal.ZERO)));
        }
        return out;
    }

    private List<MonthPoint> sessionMonthly(LocalDate from, LocalDate to) {
        Map<Integer, BigDecimal> fees = byKey("select extract(year from payment_date)::int * 100 + "
                + "extract(month from payment_date)::int, sum(" + COLLECTED + ") from fee_payments "
                + "where payment_date >= ? and payment_date < ? group by 1", from, to);
        Map<Integer, BigDecimal> expenses = byKey("select extract(year from expense_date)::int * 100 + "
                + "extract(month from expense_date)::int, sum(amount) from expenses "
                + "where expense_date >= ? and expense_date < ? group by 1", from, to);
        List<MonthPoint> out = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            LocalDate m = from.plusMonths(i);
            int key = m.getYear() * 100 + m.getMonthValue();
            out.add(new MonthPoint(m.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    fees.getOrDefault(key, BigDecimal.ZERO), expenses.getOrDefault(key, BigDecimal.ZERO)));
        }
        return out;
    }

    private Overview libraryOverview(LocalDate today) {
        long issued = count("select count(*) from book_loans where returned_date is null");
        long overdue = count("select count(*) from book_loans where returned_date is null and due_date < ?", today);
        long returned = count("select count(*) from book_loans where returned_date is not null");
        long available = count("select coalesce(sum(available_copies), 0) from library_books");
        long copies = count("select coalesce(sum(total_copies), 0) from library_books");
        return new Overview(issued + returned, List.of(
                new Item("issued", "Issued", issued),
                new Item("overdue", "Overdue", overdue),
                new Item("returned", "Returned", returned),
                new Item("available", "Copies available (of " + copies + ")", available)));
    }

    private Overview studentAttendanceOverview(LocalDate today) {
        long marked = count("select count(*) from attendance_records where date = ?", today);
        Map<String, Long> counts = new HashMap<>();
        jdbc.query("select status, count(*) from attendance_records where date = ? group by status",
                rs -> {
                    counts.put(rs.getString(1), rs.getLong(2));
                }, today);
        return overviewOf(marked, counts, new String[][] {{"PRESENT", "Present"}, {"LATE", "Late"},
                {"ABSENT", "Absent"}, {"HALF_DAY", "Half day"}});
    }

    /** Builds an overview with a fixed, ordered set of categories (missing ones count 0). */
    private Overview overview(long total, String sql, String[][] categories) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query(sql, rs -> {
            counts.put(rs.getString(1), rs.getLong(2));
        });
        return overviewOf(total, counts, categories);
    }

    private static Overview overviewOf(long total, Map<String, Long> counts, String[][] categories) {
        List<Item> items = new ArrayList<>();
        for (String[] c : categories) {
            items.add(new Item(c[0], c[1], counts.getOrDefault(c[0], 0L)));
        }
        return new Overview(total, items);
    }

    private List<Slice> slices(String sql, Object... args) {
        return jdbc.query(sql, (rs, i) -> new Slice(rs.getString(1), rs.getBigDecimal(2)), args);
    }

    private Map<Integer, BigDecimal> byKey(String sql, Object... args) {
        Map<Integer, BigDecimal> out = new HashMap<>();
        jdbc.query(sql, rs -> {
            out.put(rs.getInt(1), rs.getBigDecimal(2));
        }, args);
        return out;
    }

    private BigDecimal money(String sql, Object... args) {
        BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class, args);
        return v == null ? BigDecimal.ZERO : v;
    }

    private long count(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0 : v;
    }
}
