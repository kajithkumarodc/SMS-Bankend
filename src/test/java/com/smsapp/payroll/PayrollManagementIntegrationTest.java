package com.smsapp.payroll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Human Resource > Payroll against a real PostgreSQL instance: generating a month's payroll, editing earnings,
 * deductions and tax, paying, reverting, the attendance summary, and PAYROLL_* permission gating.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PayrollManagementIntegrationTest {

    private static final String ADMIN = "pm-admin@school.example";
    private static final String PRINCIPAL = "pm-principal@school.example";
    private static final String CLERK = "pm-clerk@school.example";
    private static final String TEACHER = "pm-teacher@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final YearMonth NOW = YearMonth.now();
    private static final int MONTH = NOW.getMonthValue();
    private static final int YEAR = NOW.getYear();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID teacherRoleId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
    }

    @BeforeEach
    void seed() throws SQLException {
        try (Connection connection = connect(); Statement st = connection.createStatement()) {
            st.execute("TRUNCATE users, roles, permissions, user_roles, role_permissions, staff_profiles, staff_attendance, "
                    + "payroll_records, leave_requests, departments, designations, schools CASCADE");
            st.execute("INSERT INTO schools (id, name) VALUES (gen_random_uuid(), 'Test School')");
            UUID adminRole = seedUser(st, ADMIN, "SCHOOL_ADMIN");
            UUID principalRole = seedUser(st, PRINCIPAL, "PRINCIPAL");
            UUID clerkRole = seedUser(st, CLERK, "ACCOUNTANT");
            teacherRoleId = seedUser(st, TEACHER, "TEACHER");
            // Same grants V22 makes (other test classes truncate roles/permissions).
            grant(st, adminRole, "STAFF_VIEW", "STAFF_CREATE", "PAYROLL_VIEW", "PAYROLL_CREATE", "PAYROLL_APPROVE");
            grant(st, principalRole, "PAYROLL_VIEW", "PAYROLL_APPROVE");
            grant(st, clerkRole, "PAYROLL_VIEW", "PAYROLL_CREATE");
        }
    }

    private UUID seedUser(Statement st, String email, String role) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, email, password_hash, full_name) VALUES ('" + userId + "', '" + email
                + "', '" + passwordEncoder.encode("secret") + "', '" + email + "')");
        st.execute("INSERT INTO roles (id, name) VALUES ('" + roleId + "', '" + role + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id) VALUES ('" + userId + "', '" + roleId + "')");
        return roleId;
    }

    private static void grant(Statement st, UUID roleId, String... permissions) throws SQLException {
        for (String name : permissions) {
            st.execute("INSERT INTO permissions (id, name) VALUES (gen_random_uuid(), '" + name + "') ON CONFLICT DO NOTHING");
            st.execute("INSERT INTO role_permissions (role_id, permission_id) SELECT '" + roleId + "', id FROM permissions "
                    + "WHERE name = '" + name + "'");
        }
    }

    private Cookie login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    /**
     * Adds a staff member with every day of the current month marked present, so their salary is the full basic
     * salary whatever day the tests run on (a day with no mark counts as leave, and upcoming days are not paid yet).
     */
    private JsonNode addStaff(Cookie admin, String staffId, String name, String salary) throws Exception {
        JsonNode staff = addUnmarkedStaff(admin, staffId, name, salary);
        try (Connection connection = connect(); Statement st = connection.createStatement()) {
            for (LocalDate day = NOW.atDay(1); !day.isAfter(NOW.atEndOfMonth()); day = day.plusDays(1)) {
                st.execute("INSERT INTO staff_attendance (staff_profile_id, attendance_date, status) VALUES ('"
                        + staff.get("id").asText() + "', '" + day + "', 'PRESENT')");
            }
        }
        return staff;
    }

    /** Days of the month before today that nothing covers: not marked, not approved leave, not a Sunday. */
    private static int expectedUnmarked(YearMonth month, java.util.Set<LocalDate> marked, java.util.Set<LocalDate> approvedLeave) {
        int count = 0;
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            if (date.isBefore(LocalDate.now()) && !marked.contains(date) && !approvedLeave.contains(date)
                    && date.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) {
                count++;
            }
        }
        return count;
    }

    private JsonNode addUnmarkedStaff(Cookie admin, String staffId, String name, String salary) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("staffId", staffId);
        body.put("roleId", teacherRoleId);
        body.put("firstName", name);
        body.put("email", staffId + "@school.example");
        body.put("gender", "Male");
        body.put("dateOfBirth", "1990-04-12");
        body.put("panNumber", "ABCDE1234F");
        body.put("basicSalary", salary);
        String response = mockMvc.perform(post("/api/v1/staff-members").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("staff");
    }

    private ResultActions generate(Cookie caller, String staffProfileId, int month, int year) throws Exception {
        return mockMvc.perform(post("/api/v1/payroll/generate").cookie(caller).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("staffProfileId", staffProfileId, "month", month, "year", year))));
    }

    private String generateNow(Cookie caller, String staffProfileId) throws Exception {
        String response = generate(caller, staffProfileId, MONTH, YEAR).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(response).get("id").asText();
    }

    private ResultActions update(Cookie caller, String id, List<Map<String, Object>> earnings,
                                 List<Map<String, Object>> deductions, Object tax) throws Exception {
        return mockMvc.perform(put("/api/v1/payroll/" + id).cookie(caller).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("earnings", earnings, "deductions", deductions, "tax", tax))));
    }

    private static Map<String, Object> line(String type, Object amount) {
        return Map.of("type", type, "amount", amount);
    }

    private ResultActions pay(Cookie caller, String id, String mode, String date) throws Exception {
        return mockMvc.perform(post("/api/v1/payroll/" + id + "/pay").cookie(caller).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("paymentMode", mode, "paymentDate", date, "note", "Month end"))));
    }

    @Test
    void theStaffListShowsWhoseMonthIsGenerated() throws Exception {
        Cookie admin = login(ADMIN);
        String shivam = addStaff(admin, "9002", "Shivam", "21000").get("id").asText();
        addStaff(admin, "9000", "Joe", "30000");

        mockMvc.perform(get("/api/v1/payroll").cookie(admin).param("roleId", teacherRoleId.toString())
                        .param("month", String.valueOf(MONTH)).param("year", String.valueOf(YEAR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].staffId").value("9000"))
                .andExpect(jsonPath("$[0].status").value("NOT_GENERATED"))
                .andExpect(jsonPath("$[0].payrollId").doesNotExist());

        generateNow(admin, shivam);
        mockMvc.perform(get("/api/v1/payroll").cookie(admin).param("roleId", teacherRoleId.toString())
                        .param("month", String.valueOf(MONTH)).param("year", String.valueOf(YEAR)))
                .andExpect(jsonPath("$[1].staffId").value("9002"))
                .andExpect(jsonPath("$[1].status").value("GENERATED"))
                .andExpect(jsonPath("$[1].netSalary").value(21000.0));
        // Another month is untouched.
        YearMonth earlier = NOW.minusMonths(1);
        mockMvc.perform(get("/api/v1/payroll").cookie(admin).param("roleId", teacherRoleId.toString())
                        .param("month", String.valueOf(earlier.getMonthValue())).param("year", String.valueOf(earlier.getYear())))
                .andExpect(jsonPath("$[1].status").value("NOT_GENERATED"));
        mockMvc.perform(get("/api/v1/payroll").cookie(admin).param("roleId", teacherRoleId.toString())
                .param("month", "13").param("year", String.valueOf(YEAR))).andExpect(status().isBadRequest());
    }

    @Test
    void generateUsesTheProfileSalaryAndRefusesDuplicatesFutureMonthsAndUnknownStaff() throws Exception {
        Cookie admin = login(ADMIN);
        String shivam = addStaff(admin, "9002", "Shivam", "21000.50").get("id").asText();

        String response = generate(admin, shivam, MONTH, YEAR).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("GENERATED"))
                .andExpect(jsonPath("$.basicSalary").value(21000.5))
                .andExpect(jsonPath("$.netSalary").value(21000.5))
                .andExpect(jsonPath("$.earnings.length()").value(0))
                .andExpect(jsonPath("$.staff.fullName").value("Shivam"))
                .andExpect(jsonPath("$.schoolName").value("Test School"))
                .andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(response).get("attendance")).hasSize(3);

        generate(admin, shivam, MONTH, YEAR).andExpect(status().isConflict());
        YearMonth next = NOW.plusMonths(1);
        generate(admin, shivam, next.getMonthValue(), next.getYear()).andExpect(status().isBadRequest());
        generate(admin, UUID.randomUUID().toString(), MONTH, YEAR).andExpect(status().isNotFound());
        generate(admin, shivam, 0, YEAR).andExpect(status().isBadRequest());
    }

    @Test
    void editingRecalculatesTheNetSalaryFromEarningsDeductionsAndTax() throws Exception {
        Cookie admin = login(ADMIN);
        String id = generateNow(admin, addStaff(admin, "9002", "Shivam", "21000").get("id").asText());

        update(admin, id, List.of(line("Bonus", 1500), line("Travel Allowance", "500.25")), List.of(line("Late fine", 200)), "300.50")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.earningTotal").value(2000.25))
                .andExpect(jsonPath("$.deductionTotal").value(200.0))
                .andExpect(jsonPath("$.grossSalary").value(23000.25))
                .andExpect(jsonPath("$.tax").value(300.5))
                .andExpect(jsonPath("$.netSalary").value(22499.75))
                .andExpect(jsonPath("$.earnings[1].type").value("Travel Allowance"));
        mockMvc.perform(get("/api/v1/payroll/" + id).cookie(admin))
                .andExpect(jsonPath("$.earnings.length()").value(2))
                .andExpect(jsonPath("$.earnings[0].type").value("Bonus"))
                .andExpect(jsonPath("$.deductions[0].amount").value(200.0))
                .andExpect(jsonPath("$.netSalary").value(22499.75));

        // Editing again replaces the lines.
        update(admin, id, List.of(), List.of(), 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.earnings.length()").value(0))
                .andExpect(jsonPath("$.netSalary").value(21000.0));

        // Validation.
        update(admin, id, List.of(), List.of(line("Advance", 25000)), 0).andExpect(status().isBadRequest());
        update(admin, id, List.of(line(" ", 10)), List.of(), 0).andExpect(status().isBadRequest());
        update(admin, id, List.of(line("Bonus", -1)), List.of(), 0).andExpect(status().isBadRequest());
        update(admin, id, List.of(line("Bonus", "1.234")), List.of(), 0).andExpect(status().isBadRequest());
        update(admin, id, List.of(), List.of(), -5).andExpect(status().isBadRequest());
        update(admin, UUID.randomUUID().toString(), List.of(), List.of(), 0).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/payroll/" + id).cookie(admin)).andExpect(jsonPath("$.netSalary").value(21000.0));
    }

    @Test
    void payingLocksThePayrollAndRevertingUndoesEachStep() throws Exception {
        Cookie admin = login(ADMIN);
        String id = generateNow(admin, addStaff(admin, "9002", "Shivam", "21000").get("id").asText());
        update(admin, id, List.of(line("Bonus", 1000)), List.of(), 0).andExpect(status().isOk());
        String today = LocalDate.now().toString();

        pay(admin, id, "Barter", today).andExpect(status().isBadRequest());
        pay(admin, id, "CASH", LocalDate.now().plusDays(1).toString()).andExpect(status().isBadRequest());
        pay(admin, id, "Transfer to Bank Account", today).andExpect(status().isBadRequest());
        pay(admin, id, "bank_transfer", today).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payment.mode").value("BANK_TRANSFER"))
                .andExpect(jsonPath("$.payment.modeLabel").value("Transfer to Bank Account"))
                .andExpect(jsonPath("$.payment.date").value(today))
                .andExpect(jsonPath("$.payment.note").value("Month end"));
        pay(admin, id, "CASH", today).andExpect(status().isConflict());
        update(admin, id, List.of(), List.of(), 0).andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/payroll").cookie(admin).param("roleId", teacherRoleId.toString())
                        .param("month", String.valueOf(MONTH)).param("year", String.valueOf(YEAR)))
                .andExpect(jsonPath("$[0].status").value("PAID"));

        // Reverting a paid payroll makes it generated again, payment cleared, lines kept.
        mockMvc.perform(post("/api/v1/payroll/" + id + "/revert").cookie(admin)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/payroll/" + id).cookie(admin))
                .andExpect(jsonPath("$.status").value("GENERATED"))
                .andExpect(jsonPath("$.payment").doesNotExist())
                .andExpect(jsonPath("$.earnings.length()").value(1))
                .andExpect(jsonPath("$.netSalary").value(22000.0));
        update(admin, id, List.of(), List.of(), 0).andExpect(status().isOk());

        // Reverting a generated payroll removes it.
        mockMvc.perform(post("/api/v1/payroll/" + id + "/revert").cookie(admin)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/payroll/" + id).cookie(admin)).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/payroll/" + id + "/revert").cookie(admin)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/payroll").cookie(admin).param("roleId", teacherRoleId.toString())
                        .param("month", String.valueOf(MONTH)).param("year", String.valueOf(YEAR)))
                .andExpect(jsonPath("$[0].status").value("NOT_GENERATED"));
    }

    @Test
    void theEditPageShowsThreeMonthsOfAttendanceAndApprovedLeave() throws Exception {
        Cookie admin = login(ADMIN);
        JsonNode shivam = addUnmarkedStaff(admin, "9002", "Shivam", "21000");
        String profileId = shivam.get("id").asText();
        String userId = shivam.get("userId").asText();
        LocalDate first = NOW.atDay(1);
        LocalDate lastMonthDay = NOW.minusMonths(1).atDay(5);
        try (Connection connection = connect(); Statement st = connection.createStatement()) {
            for (int i = 0; i < 3; i++) {
                st.execute("INSERT INTO staff_attendance (staff_profile_id, attendance_date, status) VALUES ('" + profileId + "', '"
                        + first.plusDays(i) + "', 'PRESENT') ON CONFLICT DO NOTHING");
            }
            st.execute("INSERT INTO staff_attendance (staff_profile_id, attendance_date, status) VALUES ('" + profileId + "', '"
                    + lastMonthDay + "', 'LATE')");
            // Approved leave covering the first two days of this month; a pending one is not counted.
            st.execute("INSERT INTO leave_requests (staff_user_id, leave_type, start_date, end_date, status, days, apply_date) VALUES ('" + userId
                    + "', 'SICK', '" + first + "', '" + first.plusDays(1) + "', 'APPROVED', 2, CURRENT_DATE)");
            st.execute("INSERT INTO leave_requests (staff_user_id, leave_type, start_date, end_date, status, days, apply_date) VALUES ('" + userId
                    + "', 'SICK', '" + first.plusDays(5) + "', '" + first.plusDays(6) + "', 'PENDING', 2, CURRENT_DATE)");
        }
        String id = generateNow(admin, profileId);
        String body = mockMvc.perform(get("/api/v1/payroll/" + id).cookie(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode months = JSON.readTree(body).get("attendance");
        assertThat(months.get(0).get("month").asInt()).isEqualTo(MONTH);
        assertThat(months.get(0).get("present").asInt()).isEqualTo(3);
        assertThat(months.get(0).get("leave").asInt()).isEqualTo(2);
        // Days before today that are not marked, not covered by the approved leave and not Sundays are unmarked.
        assertThat(months.get(0).get("unmarked").asInt()).isEqualTo(expectedUnmarked(NOW,
                java.util.Set.of(first, first.plusDays(1), first.plusDays(2)), java.util.Set.of(first, first.plusDays(1))));
        assertThat(months.get(1).get("late").asInt()).isEqualTo(1);
        assertThat(months.get(1).get("present").asInt()).isZero();
        assertThat(months.get(2).get("present").asInt()).isZero();
    }

    @Test
    void absentAndHalfDaysAreDeductedFromTheBasicSalaryAtGenerate() throws Exception {
        Cookie admin = login(ADMIN);
        String profileId = addUnmarkedStaff(admin, "9002", "Shivam", "31000").get("id").asText();
        LocalDate first = NOW.atDay(1);
        try (Connection connection = connect(); Statement st = connection.createStatement()) {
            // Day 1 absent, days 2 and 3 half days; every other day present, so nothing is unmarked or upcoming.
            String[] marks = {"ABSENT", "HALF_DAY", "HALF_DAY_SECOND_HALF"};
            for (int i = 0; i < marks.length; i++) {
                st.execute("INSERT INTO staff_attendance (staff_profile_id, attendance_date, status) VALUES ('" + profileId
                        + "', '" + first.plusDays(i) + "', '" + marks[i] + "')");
            }
            for (int day = 4; day <= NOW.lengthOfMonth(); day++) {
                st.execute("INSERT INTO staff_attendance (staff_profile_id, attendance_date, status) VALUES ('" + profileId
                        + "', '" + first.plusDays(day - 1) + "', 'PRESENT')");
            }
        }
        double perDay = 31000.0 / NOW.lengthOfMonth();
        double loss = Math.round(perDay * 2 * 100) / 100.0; // 1 absent + 2 half days = 2 unpaid days
        double presentDays = NOW.lengthOfMonth() - 3 + 1.0; // full days present + 2 half days
        double earned = Math.round((31000 - loss) * 100) / 100.0;

        String id = JSON.readTree(generate(admin, profileId, MONTH, YEAR).andExpect(status().isCreated())
                .andExpect(jsonPath("$.deductions.length()").value(1))
                .andExpect(jsonPath("$.deductions[0].type").value("Loss of pay (2 days)"))
                .andExpect(jsonPath("$.deductions[0].amount").value(loss))
                .andExpect(jsonPath("$.netSalary").value(earned))
                .andExpect(jsonPath("$.attendancePay.daysInMonth").value(NOW.lengthOfMonth()))
                .andExpect(jsonPath("$.attendancePay.absentDays").value(1))
                .andExpect(jsonPath("$.attendancePay.halfDays").value(2))
                .andExpect(jsonPath("$.attendancePay.unmarkedDays").value(0))
                .andExpect(jsonPath("$.attendancePay.leaveDays").value(2))
                .andExpect(jsonPath("$.attendancePay.presentDays").value(presentDays))
                .andExpect(jsonPath("$.attendancePay.payableDays").value(NOW.lengthOfMonth() - 2))
                .andExpect(jsonPath("$.attendancePay.earnedSalary").value(earned))
                .andExpect(jsonPath("$.attendancePay.lossOfPay").value(loss))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        // Editing keeps the lines the user sets; the attendance figures stay available for a recalculation.
        update(admin, id, List.of(line("Overtime", 2344)), List.of(), 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.attendancePay.lossOfPay").value(loss))
                .andExpect(jsonPath("$.netSalary").value(33344.0));
    }

    @Test
    void daysWithNoAttendanceMarkAreTakenAsLeaveExceptSundaysAndApprovedLeave() throws Exception {
        Cookie admin = login(ADMIN);
        JsonNode shivam = addUnmarkedStaff(admin, "9002", "Shivam", "31000");
        LocalDate first = NOW.atDay(1);
        try (Connection connection = connect(); Statement st = connection.createStatement()) {
            // Day 1 present; days 2 and 3 are covered by approved leave. Every other finished day has no mark.
            st.execute("INSERT INTO staff_attendance (staff_profile_id, attendance_date, status) VALUES ('"
                    + shivam.get("id").asText() + "', '" + first + "', 'PRESENT')");
            st.execute("INSERT INTO leave_requests (staff_user_id, leave_type, start_date, end_date, status, days, apply_date) VALUES ('"
                    + shivam.get("userId").asText() + "', 'CASUAL', '" + first.plusDays(1) + "', '" + first.plusDays(2) + "', 'APPROVED', 2, CURRENT_DATE)");
        }
        int unmarked = expectedUnmarked(NOW, java.util.Set.of(first), java.util.Set.of(first.plusDays(1), first.plusDays(2)));
        // Days that have not finished yet (today and later, without a mark) are not paid either.
        int upcoming = 0;
        for (int day = 1; day <= NOW.lengthOfMonth(); day++) {
            LocalDate date = NOW.atDay(day);
            if (!date.isBefore(LocalDate.now()) && !date.equals(first)) {
                upcoming++;
            }
        }
        double loss = Math.round(31000.0 * (unmarked + upcoming) / NOW.lengthOfMonth() * 100) / 100.0;

        String body = generate(admin, shivam.get("id").asText(), MONTH, YEAR).andExpect(status().isCreated())
                .andExpect(jsonPath("$.attendancePay.unmarkedDays").value(unmarked))
                .andExpect(jsonPath("$.attendancePay.leaveDays").value(unmarked))
                .andExpect(jsonPath("$.attendancePay.absentDays").value(0))
                .andExpect(jsonPath("$.attendancePay.upcomingDays").value(upcoming))
                .andExpect(jsonPath("$.attendancePay.payableDays").value(NOW.lengthOfMonth() - unmarked - upcoming))
                .andExpect(jsonPath("$.attendancePay.lossOfPay").value(loss))
                .andExpect(jsonPath("$.netSalary").value(Math.round((31000 - loss) * 100) / 100.0))
                .andReturn().getResponse().getContentAsString();
        JsonNode pay = JSON.readTree(body).get("attendancePay");
        assertThat(pay.get("presentDays").decimalValue()).isEqualByComparingTo("1");
        // Approved leave counts as paid leave only for days that are unmarked and already finished.
        int paidLeave = (int) java.util.stream.Stream.of(first.plusDays(1), first.plusDays(2))
                .filter(d -> d.isBefore(LocalDate.now())).count();
        assertThat(pay.get("paidLeaveDays").asInt()).isEqualTo(paidLeave);
        // Every day is accounted for exactly once: present + leave + paid leave + holidays/Sundays + upcoming.
        int accounted = 1 + unmarked + pay.get("paidLeaveDays").asInt() + pay.get("holidayDays").asInt()
                + pay.get("upcomingDays").asInt();
        assertThat(accounted).isEqualTo(NOW.lengthOfMonth());
        // The net salary is only what the present (and paid) days have earned so far.
        double perDay = 31000.0 / NOW.lengthOfMonth();
        assertThat(JSON.readTree(body).get("netSalary").asDouble())
                .isCloseTo(perDay * (NOW.lengthOfMonth() - unmarked - upcoming), org.assertj.core.data.Offset.offset(0.05));
        if (unmarked + upcoming > 0) {
            assertThat(JSON.readTree(body).get("deductions").get(0).get("type").asText()).startsWith("Loss of pay (");
        }
    }

    @Test
    void aPayrollFromBeforeLinesExistedShowsItsDeductionAsOneLine() throws Exception {
        Cookie admin = login(ADMIN);
        JsonNode shivam = addStaff(admin, "9002", "Shivam", "21000");
        try (Connection connection = connect(); Statement st = connection.createStatement()) {
            st.execute("INSERT INTO payroll_records (staff_user_id, month, year, base_salary, deductions, net_pay, status) VALUES ('"
                    + shivam.get("userId").asText() + "', " + MONTH + ", " + YEAR + ", 21000, 500, 20500, 'PENDING')");
        }
        String id = JSON.readTree(mockMvc.perform(get("/api/v1/payroll").cookie(admin).param("roleId", teacherRoleId.toString())
                        .param("month", String.valueOf(MONTH)).param("year", String.valueOf(YEAR)))
                .andReturn().getResponse().getContentAsString()).get(0).get("payrollId").asText();
        mockMvc.perform(get("/api/v1/payroll/" + id).cookie(admin))
                .andExpect(jsonPath("$.deductions.length()").value(1))
                .andExpect(jsonPath("$.deductions[0].amount").value(500.0))
                .andExpect(jsonPath("$.netSalary").value(20500.0));
    }

    @Test
    void permissionsFollowThePayrollGrants() throws Exception {
        Cookie admin = login(ADMIN);
        String shivam = addStaff(admin, "9002", "Shivam", "21000").get("id").asText();
        String id = generateNow(admin, shivam);
        String today = LocalDate.now().toString();

        // The principal can view and pay, but not generate, edit or revert a generated payroll.
        Cookie principal = login(PRINCIPAL);
        mockMvc.perform(get("/api/v1/payroll/" + id).cookie(principal)).andExpect(status().isOk());
        generate(principal, shivam, MONTH == 1 ? 12 : MONTH - 1, MONTH == 1 ? YEAR - 1 : YEAR).andExpect(status().isForbidden());
        update(principal, id, List.of(), List.of(), 0).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/payroll/" + id + "/revert").cookie(principal)).andExpect(status().isForbidden());
        pay(principal, id, "CASH", today).andExpect(status().isOk());

        // The clerk can edit and revert a generated payroll, but not pay or revert a paid one.
        Cookie clerk = login(CLERK);
        pay(clerk, id, "CASH", today).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/payroll/" + id + "/revert").cookie(clerk)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/payroll/" + id + "/revert").cookie(principal)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/payroll/" + id + "/revert").cookie(admin)).andExpect(status().isNoContent());
        update(clerk, id, List.of(line("Bonus", 100)), List.of(), 0).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/payroll/" + id + "/revert").cookie(clerk)).andExpect(status().isNoContent());

        Cookie teacher = login(TEACHER);
        mockMvc.perform(get("/api/v1/payroll/roles").cookie(teacher)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/payroll").cookie(teacher).param("roleId", teacherRoleId.toString())
                .param("month", String.valueOf(MONTH)).param("year", String.valueOf(YEAR))).andExpect(status().isForbidden());
    }
}
