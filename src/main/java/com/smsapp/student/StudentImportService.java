package com.smsapp.student;

import com.smsapp.academics.MediumRepository;
import com.smsapp.academics.SectionRepository;
import com.smsapp.common.ApiException;
import com.smsapp.school.SchoolRepository;
import com.smsapp.student.StudentDtos.CreateStudentRequest;
import com.smsapp.student.StudentDtos.ExtraDetails;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Student Information -> Student Admission -> Import Student: admits students from a CSV in Smart School's
 * sample-file format (header row of snake_case column names, dates as yyyy-MM-dd) into one class/section.
 *
 * <p>Each row is admitted in its own transaction (through {@link StudentService#create}), so one bad row never
 * blocks the others. A row missing a required value, or whose admission number already exists, is skipped with
 * the reason. An optional value that isn't valid (a malformed phone number, an unknown blood group) is left out
 * and reported as a warning, and the student is still admitted.
 */
@Service
public class StudentImportService {

    static final int MAX_ROWS = 1000;
    static final long MAX_BYTES = 2L * 1024 * 1024;

    /** The sample file's columns, in order. */
    public static final List<String> COLUMNS = List.of(
            "admission_no", "roll_no", "first_name", "middlename", "last_name", "gender", "date_of_birth",
            "category", "religion", "caste", "mobile_no", "email", "admission_date", "blood_group",
            "student_house", "height", "weight", "measurement_date", "father_name", "father_phone",
            "father_occupation", "mother_name", "mother_phone", "mother_occupation", "guardian_is",
            "guardian_name", "guardian_relation", "guardian_email", "guardian_phone", "guardian_occupation",
            "guardian_address", "current_address", "permanent_address", "bank_account_no", "bank_name",
            "ifsc_code", "national_identification_no", "local_identification_no", "rte", "previous_school",
            "note");

    private static final Pattern MOBILE = Pattern.compile("\\+?[0-9]{10,15}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Set<String> BLOOD_GROUPS = Set.of("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-");

    private final StudentService studentService;
    private final StudentIdentificationService identificationService;
    private final StudentRepository studentRepository;
    private final SectionRepository sectionRepository;
    private final MediumRepository mediumRepository;
    private final SchoolRepository schoolRepository;
    private final Validator validator;

    public StudentImportService(StudentService studentService, StudentIdentificationService identificationService,
                                StudentRepository studentRepository, SectionRepository sectionRepository,
                                MediumRepository mediumRepository, SchoolRepository schoolRepository,
                                Validator validator) {
        this.studentService = studentService;
        this.identificationService = identificationService;
        this.studentRepository = studentRepository;
        this.sectionRepository = sectionRepository;
        this.mediumRepository = mediumRepository;
        this.schoolRepository = schoolRepository;
        this.validator = validator;
    }

    public record RowResult(int row, String admissionNumber, String name, String status, List<String> messages) {
    }

    public record ImportResult(int imported, int skipped, List<RowResult> rows) {
    }

    /**
     * @throws ApiException 400 for an empty/oversized/unreadable file or a header without the required columns;
     *                      404 if the section or medium doesn't exist.
     */
    public ImportResult importCsv(byte[] content, UUID sectionId, UUID mediumId) {
        if (content == null || content.length == 0) {
            throw new ApiException("The file is empty", HttpStatus.BAD_REQUEST);
        }
        if (content.length > MAX_BYTES) {
            throw new ApiException("The file is larger than 2 MB", HttpStatus.BAD_REQUEST);
        }
        if (!sectionRepository.existsById(sectionId)) {
            throw new ApiException("Section not found", HttpStatus.NOT_FOUND);
        }
        if (mediumId != null && !mediumRepository.existsById(mediumId)) {
            throw new ApiException("Medium not found", HttpStatus.NOT_FOUND);
        }
        UUID schoolId = schoolRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new ApiException("Set up the school first", HttpStatus.BAD_REQUEST)).getId();

        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1); // Excel's UTF-8 byte-order mark
        }
        List<List<String>> records = parseCsv(text);
        if (records.isEmpty()) {
            throw new ApiException("The file is empty", HttpStatus.BAD_REQUEST);
        }
        Map<String, Integer> header = new HashMap<>();
        List<String> headerRow = records.get(0);
        for (int i = 0; i < headerRow.size(); i++) {
            header.put(headerRow.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        List<String> missing = List.of("admission_no", "first_name", "gender", "date_of_birth").stream()
                .filter(column -> !header.containsKey(column)).toList();
        if (!missing.isEmpty()) {
            throw new ApiException("The first line must be the column headers from the sample file. Missing: "
                    + String.join(", ", missing), HttpStatus.BAD_REQUEST);
        }
        List<List<String>> dataRows = records.subList(1, records.size()).stream()
                .filter(r -> r.stream().anyMatch(v -> !v.isBlank())).toList();
        if (dataRows.isEmpty()) {
            throw new ApiException("The file has no student rows", HttpStatus.BAD_REQUEST);
        }
        if (dataRows.size() > MAX_ROWS) {
            throw new ApiException("Import at most " + MAX_ROWS + " students at a time", HttpStatus.BAD_REQUEST);
        }

        List<RowResult> results = new ArrayList<>();
        int imported = 0;
        for (int i = 0; i < dataRows.size(); i++) {
            RowResult result = importRow(i + 2, dataRows.get(i), header, schoolId, sectionId, mediumId);
            if ("IMPORTED".equals(result.status())) {
                imported++;
            }
            results.add(result);
        }
        return new ImportResult(imported, results.size() - imported, results);
    }

    private RowResult importRow(int rowNumber, List<String> values, Map<String, Integer> header, UUID schoolId,
                                UUID sectionId, UUID mediumId) {
        Row row = new Row(values, header);
        List<String> warnings = new ArrayList<>();
        String admissionNo = row.get("admission_no");
        String firstName = row.get("first_name");
        String name = String.join(" ", java.util.stream.Stream.of(firstName, row.get("last_name"))
                .filter(v -> v != null).toList());

        List<String> errors = new ArrayList<>();
        if (admissionNo == null) errors.add("Admission No is required");
        if (firstName == null) errors.add("First Name is required");
        String gender = gender(row.get("gender"), errors);
        LocalDate dateOfBirth = date(row, "date_of_birth", true, errors, warnings);
        if (!errors.isEmpty()) {
            return new RowResult(rowNumber, admissionNo, name, "SKIPPED", errors);
        }
        if (studentRepository.existsByAdmissionNumber(admissionNo)) {
            return new RowResult(rowNumber, admissionNo, name, "SKIPPED",
                    List.of("Admission number " + admissionNo + " already exists"));
        }

        String bloodGroup = row.get("blood_group");
        if (bloodGroup != null) {
            String normalized = bloodGroup.toUpperCase(Locale.ROOT).replace(" ", "");
            if (BLOOD_GROUPS.contains(normalized)) {
                bloodGroup = normalized;
            } else {
                warnings.add("Blood group \"" + bloodGroup + "\" left out (use O+, A+, B+, AB+, O-, A-, B- or AB-)");
                bloodGroup = null;
            }
        }
        String guardianIs = row.get("guardian_is");
        String relationship = null;
        if (guardianIs != null) {
            relationship = switch (guardianIs.toLowerCase(Locale.ROOT)) {
                case "father" -> "FATHER";
                case "mother" -> "MOTHER";
                case "other" -> "OTHER";
                default -> {
                    warnings.add("If Guardian Is \"" + guardianIs + "\" left out (use father, mother or other)");
                    yield null;
                }
            };
        }
        Boolean rte = null;
        String rteValue = row.get("rte");
        if (rteValue != null) {
            if (rteValue.equalsIgnoreCase("yes")) rte = true;
            else if (rteValue.equalsIgnoreCase("no")) rte = false;
            else warnings.add("RTE \"" + rteValue + "\" left out (use Yes or No)");
        }
        if (row.get("student_house") != null) {
            warnings.add("House left out -- houses are not set up yet");
        }

        ExtraDetails extra = new ExtraDetails(
                mediumId,
                limit(row.get("caste"), 100),
                phone(row, "mobile_no", "Mobile No.", warnings),
                email(row, "email", "Email", warnings),
                limit(row.get("height"), 20),
                limit(row.get("weight"), 20),
                date(row, "measurement_date", false, errors, warnings),
                row.get("medical_history"),
                row.get("guardian_address"),
                limit(row.get("bank_account_no"), 40),
                limit(row.get("bank_name"), 150),
                limit(row.get("ifsc_code"), 20),
                row.get("note"));

        CreateStudentRequest request = new CreateStudentRequest(
                schoolId, limit(firstName, 100), limit(row.get("middlename"), 100), limit(row.get("last_name"), 100),
                gender, dateOfBirth, bloodGroup, null, limit(row.get("religion"), 100), null,
                limit(row.get("category"), 50),
                admissionNo, limit(row.get("roll_no"), 20), null,
                date(row, "admission_date", false, errors, warnings), sectionId,
                limit(row.get("previous_school"), 200), null, null, null, null, "IMPORT", rte,
                limit(row.get("guardian_name"), 200), relationship,
                phone(row, "guardian_phone", "Guardian Phone", warnings), null,
                email(row, "guardian_email", "Guardian Email", warnings),
                limit(row.get("guardian_occupation"), 200), null,
                limit(row.get("father_name"), 200), phone(row, "father_phone", "Father Phone", warnings), null,
                limit(row.get("father_occupation"), 200),
                limit(row.get("mother_name"), 200), phone(row, "mother_phone", "Mother Phone", warnings), null,
                limit(row.get("mother_occupation"), 200),
                null, null, null, null, null,
                limit(row.get("current_address"), 255), null, null, null, null, null,
                false, limit(row.get("permanent_address"), 255), null, null, null, null, null,
                null, null, null, null, null,
                extra);

        Set<ConstraintViolation<CreateStudentRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            List<String> messages = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted().collect(Collectors.toList());
            return new RowResult(rowNumber, admissionNo, name, "SKIPPED", messages);
        }
        try {
            Student student = studentService.create(request);
            addIdentification(student.getId(), "NATIONAL_ID", row.get("national_identification_no"));
            addIdentification(student.getId(), "LOCAL_ID", row.get("local_identification_no"));
            return new RowResult(rowNumber, admissionNo, student.getFullName(), "IMPORTED", warnings);
        } catch (ApiException ex) {
            return new RowResult(rowNumber, admissionNo, name, "SKIPPED", List.of(ex.getMessage()));
        }
    }

    private void addIdentification(UUID studentId, String type, String value) {
        if (value != null) {
            identificationService.add(studentId, type, limit(value, 200), null);
        }
    }

    private static String gender(String value, List<String> errors) {
        if (value == null) {
            errors.add("Gender is required");
            return null;
        }
        String upper = value.toUpperCase(Locale.ROOT);
        if (upper.equals("MALE") || upper.equals("FEMALE") || upper.equals("OTHER")) {
            return upper;
        }
        errors.add("Gender \"" + value + "\" is not Male or Female");
        return null;
    }

    private static LocalDate date(Row row, String column, boolean required, List<String> errors,
                                  List<String> warnings) {
        String value = row.get(column);
        String label = column.replace('_', ' ');
        if (value == null) {
            if (required) errors.add(capitalize(label) + " is required");
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            if (required) {
                errors.add(capitalize(label) + " \"" + value + "\" is not a yyyy-mm-dd date");
            } else {
                warnings.add(capitalize(label) + " \"" + value + "\" left out (use yyyy-mm-dd)");
            }
            return null;
        }
    }

    private static String phone(Row row, String column, String label, List<String> warnings) {
        String value = row.get(column);
        if (value == null) return null;
        String digits = value.replaceAll("[\\s()-]", "");
        if (MOBILE.matcher(digits).matches()) return digits;
        warnings.add(label + " \"" + value + "\" left out (needs 10-15 digits)");
        return null;
    }

    private static String email(Row row, String column, String label, List<String> warnings) {
        String value = row.get(column);
        if (value == null) return null;
        if (EMAIL.matcher(value).matches() && value.length() <= 200) return value;
        warnings.add(label + " \"" + value + "\" left out (not an email address)");
        return null;
    }

    private static String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /** One CSV line looked up by header name; blank cells read as null. */
    private record Row(List<String> values, Map<String, Integer> header) {
        String get(String column) {
            Integer index = header.get(column);
            if (index == null || index >= values.size()) return null;
            String value = values.get(index).trim();
            return value.isEmpty() ? null : value;
        }
    }

    static List<List<String>> parseCsv(String text) {
        return com.smsapp.common.CsvParser.parse(text);
    }
}
