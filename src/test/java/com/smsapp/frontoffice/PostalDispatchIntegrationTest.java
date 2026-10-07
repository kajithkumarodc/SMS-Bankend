package com.smsapp.frontoffice;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.smsapp.frontoffice.PostalDispatchDtos.DispatchRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Postal Dispatch against a real PostgreSQL instance: the auto-generated DSP-YYYY-NNNNN reference number
 * (sequential, unique under concurrency, read-only, following the current academic year), supporting
 * documents, and POSTAL_DISPATCH_* permission gating.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PostalDispatchIntegrationTest {

    private static final String ADMIN = "pd-admin@school.example";
    private static final String RECEPTIONIST = "pd-receptionist@school.example";
    private static final String PRINCIPAL = "pd-principal@school.example";
    private static final String TEACHER = "pd-teacher@school.example";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PostalDispatchService dispatchService;

    @Autowired
    private DocumentNumberGenerator numberGenerator;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** Start year of the academic year today falls in (set in {@link #seed}). */
    private int thisYear;

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

    private static void sql(String... statements) {
        try (Connection connection = connect(); Statement st = connection.createStatement()) {
            for (String statement : statements) {
                st.execute(statement);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void seed() throws SQLException {
        try (Connection connection = connect(); Statement st = connection.createStatement()) {
            st.execute("TRUNCATE users, roles, permissions, user_roles, role_permissions, postal_dispatches, "
                    + "postal_dispatch_documents, document_number_counters, academic_years CASCADE");
            // The academic year today falls in, plus last year -- still flagged "current" in Settings, the
            // situation that once put last year's number on new dispatches. Dates are relative to today so
            // the test keeps working in any year.
            LocalDate start = LocalDate.now().minusMonths(1).withDayOfMonth(1);
            thisYear = start.getYear();
            st.execute("INSERT INTO academic_years (id, name, start_date, end_date, is_current) VALUES "
                    + "(gen_random_uuid(), 'last year', '" + start.minusYears(1) + "', '" + start.minusDays(1) + "', true), "
                    + "(gen_random_uuid(), 'this year', '" + start + "', '" + start.plusYears(1).minusDays(1) + "', false)");
            UUID adminRole = seedUser(st, ADMIN, "SCHOOL_ADMIN");
            UUID receptionistRole = seedUser(st, RECEPTIONIST, "RECEPTIONIST");
            UUID principalRole = seedUser(st, PRINCIPAL, "PRINCIPAL");
            seedUser(st, TEACHER, "TEACHER");
            // Same grants V34 makes (other test classes truncate roles/permissions).
            grant(st, adminRole, "POSTAL_DISPATCH_VIEW", "POSTAL_DISPATCH_CREATE", "POSTAL_DISPATCH_EDIT",
                    "POSTAL_DISPATCH_DELETE", "POSTAL_DISPATCH_EXPORT", "POSTAL_DISPATCH_PRINT");
            grant(st, receptionistRole, "POSTAL_DISPATCH_VIEW", "POSTAL_DISPATCH_CREATE", "POSTAL_DISPATCH_EDIT");
            grant(st, principalRole, "POSTAL_DISPATCH_VIEW", "POSTAL_DISPATCH_EXPORT");
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

    private String dispatchJson(String toTitle, Object... overrides) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("toTitle", toTitle);
        body.put("fromTitle", "Mount Carmel School");
        body.put("address", "Education Department, Main Road");
        body.put("dispatchDate", "2026-09-26");
        for (int i = 0; i < overrides.length; i += 2) {
            body.put((String) overrides[i], overrides[i + 1]);
        }
        return JSON.writeValueAsString(body);
    }

    /** Creates a dispatch and returns {id, referenceNo}. */
    private String[] createDispatch(Cookie caller, String toTitle) throws Exception {
        var result = mockMvc.perform(post("/api/v1/postal-dispatches").cookie(caller)
                        .contentType(MediaType.APPLICATION_JSON).content(dispatchJson(toTitle)))
                .andExpect(status().isCreated())
                .andReturn();
        var node = JSON.readTree(result.getResponse().getContentAsString());
        return new String[] {node.get("id").asText(), node.get("referenceNo").asText()};
    }

    // --- Reference number ---------------------------------------------------------------

    private String dsp(int year, int n) {
        return String.format("DSP-%d-%05d", year, n);
    }

    @Test
    void numbersUseTheAcademicYearTodayFallsInEvenIfAnOldYearIsStillFlaggedCurrent() throws Exception {
        Cookie admin = login(ADMIN);
        assertThat(createDispatch(admin, "Education Department")[1]).isEqualTo(dsp(thisYear, 1));
        assertThat(createDispatch(admin, "NCC Camp Program")[1]).isEqualTo(dsp(thisYear, 2));
    }

    @Test
    void theSequenceRestartsAt00001EachAcademicYear() {
        sql("DELETE FROM academic_years",
                "INSERT INTO academic_years (id, name, start_date, end_date, is_current) VALUES "
                        + "(gen_random_uuid(), '2026-2027', '2026-06-01', '2027-04-30', true), "
                        + "(gen_random_uuid(), '2027-2028', '2027-06-01', '2028-04-30', false)");
        assertThat(issueOn("2026-09-01")).isEqualTo("DSP-2026-00001");
        assertThat(issueOn("2027-02-15")).isEqualTo("DSP-2026-00002"); // Feb 2027 is still 2026-2027
        assertThat(issueOn("2027-06-01")).isEqualTo("DSP-2027-00001"); // new academic year: restart
        assertThat(issueOn("2027-07-10")).isEqualTo("DSP-2027-00002");
        // 2028-2029 hasn't been set up yet: inferred from the 1 June year start, and restarts again.
        assertThat(issueOn("2028-09-01")).isEqualTo("DSP-2028-00001");
        // A late entry back in 2026-2027 continues that year rather than reusing a number.
        assertThat(issueOn("2027-03-01")).isEqualTo("DSP-2026-00003");
    }

    private String issueOn(String date) {
        return transactionTemplate.execute(status ->
                numberGenerator.next(PostalDispatchService.REFERENCE_SERIES, LocalDate.parse(date)).value());
    }

    @Test
    void withNoAcademicYearsTheCalendarYearIsUsed() throws Exception {
        sql("DELETE FROM academic_years");
        assertThat(createDispatch(login(ADMIN), "Books delivery")[1]).isEqualTo(dsp(LocalDate.now().getYear(), 1));
    }

    @Test
    void theReferenceNumberCannotBeChangedByAnEdit() throws Exception {
        Cookie admin = login(ADMIN);
        String[] created = createDispatch(admin, "Education Department");

        mockMvc.perform(put("/api/v1/postal-dispatches/" + created[0]).cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchJson("Education Dept (edited)", "referenceNo", "HACKED-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toTitle").value("Education Dept (edited)"))
                .andExpect(jsonPath("$.referenceNo").value(created[1]));
    }

    @Test
    void concurrentSavesGetUniqueGaplessNumbers() throws Exception {
        int saves = 20;
        // Two threads race for the same counter row. More would exceed the test pool (3 connections; each save
        // also needs one for its REQUIRES_NEW audit entry -- see application-test.yml), which tests the pool,
        // not the numbering.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < saves; i++) {
                int n = i;
                tasks.add(() -> dispatchService.create(
                        new DispatchRequest("Concurrent " + n, null, null, null, LocalDate.of(2026, 9, 26)), null)
                        .getReferenceNo());
            }
            List<String> numbers = new ArrayList<>();
            for (Future<String> f : pool.invokeAll(tasks)) {
                numbers.add(f.get());
            }
            assertThat(numbers).doesNotHaveDuplicates();
            assertThat(numbers).containsExactlyInAnyOrderElementsOf(
                    IntStream.rangeClosed(1, saves).mapToObj(i -> dsp(thisYear, i)).toList());
        } finally {
            pool.shutdownNow();
        }
    }

    // --- Validation / list ----------------------------------------------------------------

    @Test
    void toTitleAndDateAreRequired() throws Exception {
        Cookie admin = login(ADMIN);
        mockMvc.perform(post("/api/v1/postal-dispatches").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchJson(" "))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/postal-dispatches").cookie(admin).contentType(MediaType.APPLICATION_JSON)
                        .content(dispatchJson("No date", "dispatchDate", null))).andExpect(status().isBadRequest());
        // A rejected save must not use up a number.
        assertThat(createDispatch(admin, "First real one")[1]).isEqualTo(dsp(thisYear, 1));
    }

    @Test
    void listSearchesByReferenceAndSorts() throws Exception {
        Cookie admin = login(ADMIN);
        createDispatch(admin, "Zeta Office");
        String[] second = createDispatch(admin, "Alpha Office");
        mockMvc.perform(get("/api/v1/postal-dispatches").cookie(admin).param("q", second[1]))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].toTitle").value("Alpha Office"));
        mockMvc.perform(get("/api/v1/postal-dispatches").cookie(admin).param("sort", "toTitle,asc"))
                .andExpect(jsonPath("$.content[0].toTitle").value("Alpha Office"));
        mockMvc.perform(get("/api/v1/postal-dispatches").cookie(admin).param("sort", "referenceNo,desc"))
                .andExpect(jsonPath("$.content[0].referenceNo").value(dsp(thisYear, 2)));
        mockMvc.perform(get("/api/v1/postal-dispatches").cookie(admin).param("sort", "createdByUserId,asc"))
                .andExpect(status().isBadRequest());
    }

    // --- Documents -------------------------------------------------------------------------

    @Test
    void multipleDocumentsCanBeAttachedDownloadedAndRemoved() throws Exception {
        Cookie receptionist = login(RECEPTIONIST);
        String id = createDispatch(receptionist, "Education Department")[0];
        byte[] letter = "%PDF-1.4 covering letter".getBytes();

        var first = mockMvc.perform(multipart("/api/v1/postal-dispatches/" + id + "/documents")
                        .file(new MockMultipartFile("file", "covering-letter.pdf", "application/pdf", letter)).cookie(receptionist))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value("covering-letter.pdf"))
                .andReturn();
        String firstId = JSON.readTree(first.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(multipart("/api/v1/postal-dispatches/" + id + "/documents")
                        .file(new MockMultipartFile("file", "receipt.png", "image/png", new byte[] {1, 2, 3})).cookie(receptionist))
                .andExpect(status().isCreated());
        mockMvc.perform(multipart("/api/v1/postal-dispatches/" + id + "/documents")
                        .file(new MockMultipartFile("file", "run.exe", "application/x-msdownload", letter)).cookie(receptionist))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/postal-dispatches/" + id).cookie(receptionist))
                .andExpect(jsonPath("$.documents.length()").value(2))
                .andExpect(jsonPath("$.documents[0].fileName").value("covering-letter.pdf"));
        mockMvc.perform(get("/api/v1/postal-dispatches/" + id + "/documents/" + firstId).cookie(receptionist))
                .andExpect(status().isOk())
                .andExpect(content().bytes(letter));

        mockMvc.perform(delete("/api/v1/postal-dispatches/" + id + "/documents/" + firstId).cookie(receptionist))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/postal-dispatches/" + id).cookie(receptionist))
                .andExpect(jsonPath("$.documents.length()").value(1));
        // A document id from another dispatch (or none) is a 404, never a cross-dispatch read.
        String otherId = createDispatch(receptionist, "Other")[0];
        mockMvc.perform(get("/api/v1/postal-dispatches/" + otherId + "/documents/" + firstId).cookie(receptionist))
                .andExpect(status().isNotFound());
    }

    @Test
    void aDispatchHoldsAtMostTenDocuments() throws Exception {
        Cookie admin = login(ADMIN);
        String id = createDispatch(admin, "Bulk")[0];
        for (int i = 0; i < PostalDispatchService.MAX_DOCUMENTS; i++) {
            mockMvc.perform(multipart("/api/v1/postal-dispatches/" + id + "/documents")
                            .file(new MockMultipartFile("file", "page" + i + ".png", "image/png", new byte[] {1})).cookie(admin))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(multipart("/api/v1/postal-dispatches/" + id + "/documents")
                        .file(new MockMultipartFile("file", "one-too-many.png", "image/png", new byte[] {1})).cookie(admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletingADispatchRemovesItsDocumentsAndFiles() throws Exception {
        Cookie admin = login(ADMIN);
        String id = createDispatch(admin, "Education Department")[0];
        mockMvc.perform(multipart("/api/v1/postal-dispatches/" + id + "/documents")
                        .file(new MockMultipartFile("file", "scan.pdf", "application/pdf", new byte[] {9})).cookie(admin))
                .andExpect(status().isCreated());
        Path dir = Path.of("target/test-uploads/postal-dispatches", id);
        assertThat(dir.toFile().list()).hasSize(1);

        mockMvc.perform(delete("/api/v1/postal-dispatches/" + id).cookie(admin)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/postal-dispatches/" + id).cookie(admin)).andExpect(status().isNotFound());
        assertThat(dir).doesNotExist();
    }

    // --- Permissions -------------------------------------------------------------------------

    @Test
    void permissionsFollowV34Grants() throws Exception {
        String id = createDispatch(login(ADMIN), "Education Department")[0];

        Cookie receptionist = login(RECEPTIONIST);
        mockMvc.perform(delete("/api/v1/postal-dispatches/" + id).cookie(receptionist)).andExpect(status().isForbidden());

        Cookie principal = login(PRINCIPAL);
        mockMvc.perform(get("/api/v1/postal-dispatches").cookie(principal)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/postal-dispatches").cookie(principal).contentType(MediaType.APPLICATION_JSON)
                .content(dispatchJson("Nope"))).andExpect(status().isForbidden());

        Cookie teacher = login(TEACHER);
        mockMvc.perform(get("/api/v1/postal-dispatches").cookie(teacher)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/postal-dispatches/" + id).cookie(teacher)).andExpect(status().isForbidden());
    }
}
