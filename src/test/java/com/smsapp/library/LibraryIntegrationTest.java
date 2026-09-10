package com.smsapp.library;

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

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Library catalog + issue/return against a real PostgreSQL instance with RLS
 * (plan section 2). Covers: tenant isolation on the catalog, the staff loan
 * history and the portal endpoints; SCHOOL_ADMIN-only add/issue/return
 * (TEACHER/STUDENT/PARENT may read the catalog but 403 on writes);
 * {@code available_copies} increment/decrement; 400 when issuing with no copies;
 * 409 when returning an already-returned loan; and ownership isolation on
 * {@code /me/student/library} and {@code /me/children/{id}/library}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LibraryIntegrationTest {

    private static final String SCHOOL_A = "lib-a";
    private static final String SCHOOL_B = "lib-b";
    private static final String ADMIN_A = "admin@lib-a.example";
    private static final String TEACHER_A = "teacher@lib-a.example";
    private static final String STUDENT_A = "student@lib-a.example";   // linked to studentAId
    private static final String PARENT_A = "parent@lib-a.example";     // guardian of studentAId
    private static final String PARENT_A2 = "parent2@lib-a.example";   // guardian of studentA2Id
    private static final String ADMIN_B = "admin@lib-b.example";
    private static final String STUDENT_B = "student@lib-b.example";   // linked to studentBId
    private static final String PASSWORD = "secret";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private UUID studentAId;    // "Anaya", tenant A, linked to STUDENT_A + guardian PARENT_A
    private UUID studentA2Id;   // "Bhavya", tenant A, guardian PARENT_A2
    private UUID studentBId;    // "Chandra", tenant B, linked to STUDENT_B
    private UUID bookAId;       // "Refactoring" / "Fowler", tenant A, 1 copy, on loan to studentA
    private UUID loanAId;       // the open loan of bookA to studentA
    private UUID bookBId;       // tenant B book

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("JWT_SECRET", () -> "integration-test-secret-with-at-least-32-chars");
        registry.add("JWT_ISSUER_URI", () -> "http://localhost:8080");
    }

    @BeforeEach
    void seed() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID schoolAId = UUID.randomUUID();
        UUID schoolBId = UUID.randomUUID();
        studentAId = UUID.randomUUID();
        studentA2Id = UUID.randomUUID();
        studentBId = UUID.randomUUID();
        bookAId = UUID.randomUUID();
        loanAId = UUID.randomUUID();
        bookBId = UUID.randomUUID();
        UUID loanBId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(
                System.getProperty("DB_URL", "jdbc:postgresql://localhost:5433/sms_db_test"),
                System.getProperty("DB_USERNAME", "postgres"),
                System.getProperty("DB_PASSWORD", "1234"));
             Statement st = connection.createStatement()) {

            st.execute("TRUNCATE tenants, schools, users, roles, permissions, user_roles, students, "
                    + "attendance_records, exam_marks, exams, class_subjects, subjects, sections, classes, "
                    + "invoices, fee_structures, announcements, book_loans, library_books, audit_log CASCADE");

            seedTenant(st, tenantA, "Tenant A", SCHOOL_A, schoolAId);
            seedTenant(st, tenantB, "Tenant B", SCHOOL_B, schoolBId);

            // One role row per (tenant, role); a tenant may have many users in a role.
            UUID adminRoleA = seedRole(st, tenantA, "SCHOOL_ADMIN");
            UUID teacherRoleA = seedRole(st, tenantA, "TEACHER");
            UUID studentRoleA = seedRole(st, tenantA, "STUDENT");
            UUID parentRoleA = seedRole(st, tenantA, "PARENT");
            UUID adminRoleB = seedRole(st, tenantB, "SCHOOL_ADMIN");
            UUID studentRoleB = seedRole(st, tenantB, "STUDENT");

            seedUser(st, tenantA, ADMIN_A, adminRoleA);
            seedUser(st, tenantA, TEACHER_A, teacherRoleA);
            UUID studentAUser = seedUser(st, tenantA, STUDENT_A, studentRoleA);
            UUID parentAUser = seedUser(st, tenantA, PARENT_A, parentRoleA);
            UUID parentA2User = seedUser(st, tenantA, PARENT_A2, parentRoleA);
            seedUser(st, tenantB, ADMIN_B, adminRoleB);
            UUID studentBUser = seedUser(st, tenantB, STUDENT_B, studentRoleB);

            seedStudent(st, tenantA, schoolAId, studentAId, "Anaya", studentAUser, parentAUser);
            seedStudent(st, tenantA, schoolAId, studentA2Id, "Bhavya", null, parentA2User);
            seedStudent(st, tenantB, schoolBId, studentBId, "Chandra", studentBUser, null);

            // Tenant A: a 1-copy book, already issued to studentA (so 0 available).
            seedBook(st, tenantA, bookAId, "Refactoring", "Fowler", 1, 0);
            seedLoan(st, tenantA, loanAId, bookAId, studentAId, LocalDate.now().minusDays(3), null);

            // Tenant B: its own book + loan -- must never appear in tenant A's views.
            seedBook(st, tenantB, bookBId, "The Pragmatic Programmer", "Hunt", 2, 1);
            seedLoan(st, tenantB, loanBId, bookBId, studentBId, LocalDate.now().minusDays(1), null);
        }
    }

    // --- seed helpers ------------------------------------------------

    private static void seedTenant(Statement st, UUID tenantId, String name, String identifier, UUID schoolId)
            throws SQLException {
        st.execute("INSERT INTO tenants (id, name, identifier) VALUES ('"
                + tenantId + "', '" + name + "', '" + identifier + "')");
        st.execute("INSERT INTO schools (id, tenant_id, name) VALUES ('"
                + schoolId + "', '" + tenantId + "', '" + name + " School')");
    }

    private static UUID seedRole(Statement st, UUID tenantId, String role) throws SQLException {
        UUID roleId = UUID.randomUUID();
        st.execute("INSERT INTO roles (id, tenant_id, name) VALUES ('"
                + roleId + "', '" + tenantId + "', '" + role + "')");
        return roleId;
    }

    private UUID seedUser(Statement st, UUID tenantId, String email, UUID roleId) throws SQLException {
        UUID userId = UUID.randomUUID();
        st.execute("INSERT INTO users (id, tenant_id, email, password_hash, full_name) VALUES ('"
                + userId + "', '" + tenantId + "', '" + email + "', '" + passwordEncoder.encode(PASSWORD) + "', '"
                + email + "')");
        st.execute("INSERT INTO user_roles (user_id, role_id, tenant_id) VALUES ('"
                + userId + "', '" + roleId + "', '" + tenantId + "')");
        return userId;
    }

    private static void seedStudent(Statement st, UUID tenantId, UUID schoolId, UUID studentId, String name,
                                    UUID studentUserId, UUID guardianUserId) throws SQLException {
        st.execute("INSERT INTO students (id, tenant_id, school_id, full_name, admission_number, status, "
                + "student_user_id, guardian_user_id) VALUES ('" + studentId + "', '" + tenantId + "', '" + schoolId
                + "', '" + name + "', 'ADM-" + name + "', 'ACTIVE', "
                + (studentUserId == null ? "NULL" : "'" + studentUserId + "'") + ", "
                + (guardianUserId == null ? "NULL" : "'" + guardianUserId + "'") + ")");
    }

    private static void seedBook(Statement st, UUID tenantId, UUID id, String title, String author,
                                 int total, int available) throws SQLException {
        st.execute("INSERT INTO library_books (id, tenant_id, title, author, total_copies, available_copies) VALUES ('"
                + id + "', '" + tenantId + "', '" + title + "', '" + author + "', " + total + ", " + available + ")");
    }

    private static void seedLoan(Statement st, UUID tenantId, UUID id, UUID bookId, UUID studentId,
                                 LocalDate issued, LocalDate returned) throws SQLException {
        st.execute("INSERT INTO book_loans (id, tenant_id, book_id, student_id, issued_date, due_date, returned_date) "
                + "VALUES ('" + id + "', '" + tenantId + "', '" + bookId + "', '" + studentId + "', '" + issued
                + "', '" + issued.plusDays(14) + "', "
                + (returned == null ? "NULL" : "'" + returned + "'") + ")");
    }

    private Cookie login(String schoolIdentifier, String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schoolIdentifier\":\"" + schoolIdentifier + "\",\"email\":\"" + email
                                + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("access_token");
    }

    private UUID addBook(Cookie admin, String title, String author, int copies) throws Exception {
        var result = mockMvc.perform(post("/api/v1/library/books").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"author\":\"" + author
                                + "\",\"totalCopies\":" + copies + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private int availableCopies(Cookie session, UUID bookId) throws Exception {
        var result = mockMvc.perform(get("/api/v1/library/books").param("q", "").cookie(session))
                .andExpect(status().isOk()).andReturn();
        for (JsonNode book : JSON.readTree(result.getResponse().getContentAsString()).get("content")) {
            if (book.get("id").asText().equals(bookId.toString())) {
                return book.get("availableCopies").asInt();
            }
        }
        throw new AssertionError("book " + bookId + " not in the catalog");
    }

    private UUID issue(Cookie admin, UUID bookId, UUID studentId) throws Exception {
        var result = mockMvc.perform(post("/api/v1/library/loans").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + bookId + "\",\"studentId\":\"" + studentId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    // --- Catalog: read for all, write for admin -----------------

    @Test
    void schoolAdminAddsABookAndEveryRoleCanBrowseTheCatalog() throws Exception {
        addBook(login(SCHOOL_A, ADMIN_A), "Clean Code", "Martin", 4);

        for (String email : new String[] {ADMIN_A, TEACHER_A, STUDENT_A, PARENT_A}) {
            mockMvc.perform(get("/api/v1/library/books").cookie(login(SCHOOL_A, email)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[?(@.title == 'Clean Code')].author",
                            org.hamcrest.Matchers.hasItem("Martin")));
        }
    }

    @Test
    void teacherStudentAndParentCannotAddBooks() throws Exception {
        String body = "{\"title\":\"X\",\"author\":\"Y\",\"totalCopies\":1}";
        for (String email : new String[] {TEACHER_A, STUDENT_A, PARENT_A}) {
            mockMvc.perform(post("/api/v1/library/books").cookie(login(SCHOOL_A, email))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void catalogSearchMatchesTitleOrAuthorAndIsTenantScoped() throws Exception {
        JsonNode aResults = JSON.readTree(mockMvc.perform(
                        get("/api/v1/library/books").param("q", "fowler").cookie(login(SCHOOL_A, ADMIN_A)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(aResults.get("content")).hasSize(1);
        assertThat(aResults.get("content").get(0).get("title").asText()).isEqualTo("Refactoring");

        // Tenant A cannot find tenant B's "The Pragmatic Programmer".
        JsonNode crossTenant = JSON.readTree(mockMvc.perform(
                        get("/api/v1/library/books").param("q", "pragmatic").cookie(login(SCHOOL_A, ADMIN_A)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(crossTenant.get("content")).isEmpty();
    }

    // --- Issue / return: available_copies bookkeeping ----------

    @Test
    void issuingDecrementsAndReturningIncrementsAvailableCopies() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        UUID bookId = addBook(admin, "Domain-Driven Design", "Evans", 2);
        assertThat(availableCopies(admin, bookId)).isEqualTo(2);

        issue(admin, bookId, studentAId);
        assertThat(availableCopies(admin, bookId)).isEqualTo(1);

        UUID secondLoan = issue(admin, bookId, studentA2Id);
        assertThat(availableCopies(admin, bookId)).isZero();

        // No copies left -> 400.
        mockMvc.perform(post("/api/v1/library/loans").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + bookId + "\",\"studentId\":\"" + studentAId + "\"}"))
                .andExpect(status().isBadRequest());

        // Return one -> back to 1.
        mockMvc.perform(post("/api/v1/library/loans/" + secondLoan + "/return").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnedDate").isNotEmpty());
        assertThat(availableCopies(admin, bookId)).isEqualTo(1);
    }

    @Test
    void issuedDueDateIs14DaysOut() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);
        UUID bookId = addBook(admin, "Working Effectively with Legacy Code", "Feathers", 1);

        var result = mockMvc.perform(post("/api/v1/library/loans").cookie(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + bookId + "\",\"studentId\":\"" + studentAId + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode loan = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(LocalDate.parse(loan.get("dueDate").asText()))
                .isEqualTo(LocalDate.parse(loan.get("issuedDate").asText()).plusDays(14));
    }

    @Test
    void returningAnAlreadyReturnedLoanReturns409() throws Exception {
        Cookie admin = login(SCHOOL_A, ADMIN_A);

        mockMvc.perform(post("/api/v1/library/loans/" + loanAId + "/return").cookie(admin))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/library/loans/" + loanAId + "/return").cookie(admin))
                .andExpect(status().isConflict());
    }

    @Test
    void onlySchoolAdminCanIssueOrReturn() throws Exception {
        String issueBody = "{\"bookId\":\"" + bookAId + "\",\"studentId\":\"" + studentAId + "\"}";
        for (String email : new String[] {TEACHER_A, STUDENT_A, PARENT_A}) {
            Cookie session = login(SCHOOL_A, email);
            mockMvc.perform(post("/api/v1/library/loans").cookie(session)
                            .contentType(MediaType.APPLICATION_JSON).content(issueBody))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/library/loans/" + loanAId + "/return").cookie(session))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void cannotIssueAnotherTenantsBookOrStudentAndCannotReturnAnotherTenantsLoan() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);
        UUID bookId = addBook(adminA, "Test-Driven Development", "Beck", 3);

        // Another tenant's book.
        mockMvc.perform(post("/api/v1/library/loans").cookie(adminA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + bookBId + "\",\"studentId\":\"" + studentAId + "\"}"))
                .andExpect(status().isNotFound());
        // Another tenant's student.
        mockMvc.perform(post("/api/v1/library/loans").cookie(adminA).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookId\":\"" + bookId + "\",\"studentId\":\"" + studentBId + "\"}"))
                .andExpect(status().isNotFound());
        // Tenant B admin cannot return tenant A's loan.
        mockMvc.perform(post("/api/v1/library/loans/" + loanAId + "/return").cookie(login(SCHOOL_B, ADMIN_B)))
                .andExpect(status().isNotFound());
    }

    // --- Active loans (staff) --------------------------------

    @Test
    void activeLoansListsOpenLoansTenantScopedExcludesReturnedAndIsStaffOnly() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);

        mockMvc.perform(get("/api/v1/library/loans/active").cookie(adminA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bookTitle").value("Refactoring"))
                .andExpect(jsonPath("$[0].studentName").value("Anaya"));

        // Tenant B's open loan never shows for tenant A.
        mockMvc.perform(get("/api/v1/library/loans/active").cookie(login(SCHOOL_B, ADMIN_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bookTitle").value("The Pragmatic Programmer"));

        // A returned loan drops off the list.
        mockMvc.perform(post("/api/v1/library/loans/" + loanAId + "/return").cookie(adminA))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/library/loans/active").cookie(adminA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // Teacher may read it; student / parent may not.
        mockMvc.perform(get("/api/v1/library/loans/active").cookie(login(SCHOOL_A, TEACHER_A)))
                .andExpect(status().isOk());
        for (String email : new String[] {STUDENT_A, PARENT_A}) {
            mockMvc.perform(get("/api/v1/library/loans/active").cookie(login(SCHOOL_A, email)))
                    .andExpect(status().isForbidden());
        }
    }

    // --- Staff loan history -----------------------------------

    @Test
    void staffLoanHistoryIsTenantScopedAndStaffOnly() throws Exception {
        Cookie adminA = login(SCHOOL_A, ADMIN_A);

        mockMvc.perform(get("/api/v1/library/loans").param("studentId", studentAId.toString()).cookie(adminA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bookTitle").value("Refactoring"));

        // Teacher may also read it.
        mockMvc.perform(get("/api/v1/library/loans").param("studentId", studentAId.toString())
                        .cookie(login(SCHOOL_A, TEACHER_A)))
                .andExpect(status().isOk());

        // Tenant A cannot read a tenant-B student's loan history.
        mockMvc.perform(get("/api/v1/library/loans").param("studentId", studentBId.toString()).cookie(adminA))
                .andExpect(status().isNotFound());

        // STUDENT / PARENT may not use the staff endpoint at all.
        for (String email : new String[] {STUDENT_A, PARENT_A}) {
            mockMvc.perform(get("/api/v1/library/loans").param("studentId", studentAId.toString())
                            .cookie(login(SCHOOL_A, email)))
                    .andExpect(status().isForbidden());
        }
    }

    // --- Portal: ownership isolation --------------------------

    @Test
    void studentSeesOnlyTheirOwnLibraryHistory() throws Exception {
        mockMvc.perform(get("/api/v1/me/student/library").cookie(login(SCHOOL_A, STUDENT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bookTitle").value("Refactoring"));

        // Tenant B student sees only their own (Tenant B) loan, never tenant A's.
        mockMvc.perform(get("/api/v1/me/student/library").cookie(login(SCHOOL_B, STUDENT_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bookTitle").value("The Pragmatic Programmer"));
    }

    @Test
    void parentSeesTheirOwnChildsLibraryHistoryButNotAnothersOrAnotherTenants() throws Exception {
        // PARENT_A is Anaya's (studentAId) guardian.
        mockMvc.perform(get("/api/v1/me/children/" + studentAId + "/library").cookie(login(SCHOOL_A, PARENT_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bookTitle").value("Refactoring"));

        // Bhavya (studentA2Id) is PARENT_A2's child, not PARENT_A's -> 404, same tenant.
        mockMvc.perform(get("/api/v1/me/children/" + studentA2Id + "/library").cookie(login(SCHOOL_A, PARENT_A)))
                .andExpect(status().isNotFound());

        // A tenant-A parent asking for a tenant-B student's id gets 404, never a leak.
        mockMvc.perform(get("/api/v1/me/children/" + studentBId + "/library").cookie(login(SCHOOL_A, PARENT_A)))
                .andExpect(status().isNotFound());
    }

    @Test
    void thePortalLibraryEndpointsAreRoleGated() throws Exception {
        // A PARENT cannot use the STUDENT self endpoint, and vice versa.
        mockMvc.perform(get("/api/v1/me/student/library").cookie(login(SCHOOL_A, PARENT_A)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/me/children/" + studentAId + "/library").cookie(login(SCHOOL_A, STUDENT_A)))
                .andExpect(status().isForbidden());
    }
}
