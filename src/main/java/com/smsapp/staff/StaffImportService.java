package com.smsapp.staff;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.common.CsvParser;
import com.smsapp.staff.StaffDirectoryDtos.ImportResult;
import com.smsapp.staff.StaffDirectoryDtos.ImportRowResult;
import com.smsapp.staff.StaffDirectoryDtos.StaffRequest;
import com.smsapp.staff.StaffDirectoryService.CreatedStaff;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Human Resource > Staff Directory > Import Staff: adds staff from a CSV in Smart School's sample-file format
 * (header row of snake_case column names, dates as yyyy-MM-dd) with the Role, Designation and Department chosen
 * on the page applied to every row.
 *
 * <p>Each row is added in its own transaction (through {@link StaffDirectoryService#create}), so one bad row never
 * blocks the others. A row missing a required value, or whose Staff ID or email is already used, is skipped with
 * the reason. An optional value that isn't valid (a malformed phone number, an unknown marital status) is left out
 * and reported as a warning. Every imported row gets a login with a temporary password, returned once in the result.
 */
@Service
public class StaffImportService {

    static final int MAX_ROWS = 500;
    static final long MAX_BYTES = 2L * 1024 * 1024;

    /** The sample file's columns, in order. */
    public static final List<String> COLUMNS = List.of(
            "employee_id", "qualification", "work_exp", "name", "surname", "father_name", "mother_name",
            "contact_no", "emergency_contact_no", "email", "dob", "marital_status", "date_of_joining",
            "date_of_leaving", "local_address", "permanent_address", "note", "gender", "account_title",
            "bank_account_no", "bank_name", "ifsc_code", "bank_branch", "payscale", "basic_salary", "epf_no",
            "contract_type", "shift", "location", "facebook", "twitter", "linkedin", "instagram", "resume",
            "joining_letter", "resignation_letter");

    private static final List<String> REQUIRED = List.of("employee_id", "name", "email", "gender", "dob");
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9][0-9 ()-]{4,28}[0-9]");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final StaffDirectoryService directoryService;
    private final AuditService auditService;

    public StaffImportService(StaffDirectoryService directoryService, AuditService auditService) {
        this.directoryService = directoryService;
        this.auditService = auditService;
    }

    /**
     * @throws ApiException 400 for an empty/oversized file, a header without the required columns, or no rows;
     *                      the role/department/designation errors of {@link StaffDirectoryService#create}.
     */
    public ImportResult importCsv(byte[] content, UUID roleId, UUID designationId, UUID departmentId,
                                  boolean callerIsSuperAdmin) {
        if (content == null || content.length == 0) {
            throw new ApiException("The file is empty", HttpStatus.BAD_REQUEST);
        }
        if (content.length > MAX_BYTES) {
            throw new ApiException("The file is larger than 2 MB", HttpStatus.BAD_REQUEST);
        }
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1); // Excel's UTF-8 byte-order mark
        }
        List<List<String>> records = CsvParser.parse(text);
        if (records.isEmpty()) {
            throw new ApiException("The file is empty", HttpStatus.BAD_REQUEST);
        }
        Map<String, Integer> header = new HashMap<>();
        List<String> headerRow = records.get(0);
        for (int i = 0; i < headerRow.size(); i++) {
            header.put(headerRow.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        List<String> missing = REQUIRED.stream().filter(column -> !header.containsKey(column)).toList();
        if (!missing.isEmpty()) {
            throw new ApiException("The first line must be the column headers from the sample file. Missing: "
                    + String.join(", ", missing), HttpStatus.BAD_REQUEST);
        }
        List<List<String>> dataRows = records.subList(1, records.size()).stream()
                .filter(r -> r.stream().anyMatch(v -> !v.isBlank())).toList();
        if (dataRows.isEmpty()) {
            throw new ApiException("The file has no staff rows", HttpStatus.BAD_REQUEST);
        }
        if (dataRows.size() > MAX_ROWS) {
            throw new ApiException("Import at most " + MAX_ROWS + " staff at a time", HttpStatus.BAD_REQUEST);
        }

        List<ImportRowResult> results = new ArrayList<>();
        int imported = 0;
        for (int i = 0; i < dataRows.size(); i++) {
            ImportRowResult result = importRow(i + 2, dataRows.get(i), header, roleId, designationId, departmentId,
                    callerIsSuperAdmin);
            if ("IMPORTED".equals(result.status())) {
                imported++;
            }
            results.add(result);
        }
        auditService.log(AuditActions.STAFF_IMPORTED, AuditActions.STAFF_PROFILE, null,
                Map.of("imported", imported, "skipped", results.size() - imported));
        return new ImportResult(imported, results.size() - imported, results);
    }

    private ImportRowResult importRow(int rowNumber, List<String> values, Map<String, Integer> header, UUID roleId,
                                      UUID designationId, UUID departmentId, boolean callerIsSuperAdmin) {
        Row row = new Row(values, header);
        List<String> warnings = new ArrayList<>();
        String staffId = row.get("employee_id");
        String firstName = row.get("name");
        String lastName = row.get("surname");
        String name = String.join(" ", java.util.stream.Stream.of(firstName, lastName).filter(v -> v != null).toList());
        String email = row.get("email");

        List<String> errors = new ArrayList<>();
        if (staffId == null) errors.add("Employee ID is required");
        if (firstName == null) errors.add("Name is required");
        if (email == null) errors.add("Email is required");
        else if (!EMAIL.matcher(email).matches() || email.length() > 320) errors.add("Email \"" + email + "\" is not an email address");
        String gender = gender(row.get("gender"), errors);
        LocalDate dateOfBirth = date(row, "dob", true, errors, warnings);
        if (dateOfBirth != null && !dateOfBirth.isBefore(LocalDate.now())) {
            errors.add("Dob must be in the past");
        }
        if (staffId != null && staffId.length() > 50) errors.add("Employee ID is longer than 50 characters");
        if (firstName != null && firstName.length() > 100) errors.add("Name is longer than 100 characters");
        if (!errors.isEmpty()) {
            return new ImportRowResult(rowNumber, staffId, name, "SKIPPED", errors, email, null);
        }

        String maritalStatus = null;
        String maritalValue = row.get("marital_status");
        if (maritalValue != null) {
            String normalized = maritalValue.toUpperCase(Locale.ROOT).replace(' ', '_');
            if (List.of("SINGLE", "MARRIED", "WIDOWED", "SEPARATED", "NOT_SPECIFIED").contains(normalized)) {
                maritalStatus = normalized;
            } else {
                warnings.add("Marital status \"" + maritalValue + "\" left out (use Single, Married, Widowed, Separated or Not Specified)");
            }
        }
        String contractType = null;
        String contractValue = row.get("contract_type");
        if (contractValue != null) {
            String normalized = contractValue.toUpperCase(Locale.ROOT);
            if (normalized.equals("PERMANENT") || normalized.equals("PROBATION")) {
                contractType = normalized;
            } else {
                warnings.add("Contract type \"" + contractValue + "\" left out (use Permanent or Probation)");
            }
        }
        BigDecimal salary = null;
        String salaryValue = row.get("basic_salary");
        if (salaryValue != null) {
            try {
                salary = new BigDecimal(salaryValue.replace(",", ""));
                if (salary.signum() < 0 || salary.precision() - salary.scale() > 10) {
                    throw new NumberFormatException();
                }
                salary = salary.setScale(2, java.math.RoundingMode.HALF_UP);
            } catch (NumberFormatException ex) {
                warnings.add("Basic salary \"" + salaryValue + "\" left out (use a number such as 25000.00)");
                salary = null;
            }
        }
        if (row.get("resume") != null || row.get("joining_letter") != null || row.get("resignation_letter") != null) {
            warnings.add("Resume, joining letter and resignation letter files can't be imported -- upload them on the staff profile");
        }

        StaffRequest request = new StaffRequest(
                staffId, roleId, designationId, departmentId, limit(firstName, 100), limit(lastName, 100),
                limit(row.get("father_name"), 200), limit(row.get("mother_name"), 200), email, gender, dateOfBirth,
                date(row, "date_of_joining", false, errors, warnings),
                phone(row, "contact_no", "Contact no", warnings),
                phone(row, "emergency_contact_no", "Emergency contact no", warnings),
                maritalStatus, limit(row.get("local_address"), 500), limit(row.get("permanent_address"), 500),
                limit(row.get("qualification"), 500), limit(row.get("work_exp"), 500), limit(row.get("note"), 2000),
                null, limit(row.get("epf_no"), 50), salary, contractType, limit(row.get("shift"), 100),
                limit(row.get("location"), 100), null, null, null, null, null,
                limit(row.get("account_title"), 200), limit(row.get("bank_account_no"), 40),
                limit(row.get("bank_name"), 150), limit(row.get("ifsc_code"), 20), limit(row.get("bank_branch"), 150),
                limit(row.get("facebook"), 300), limit(row.get("twitter"), 300), limit(row.get("linkedin"), 300),
                limit(row.get("instagram"), 300));
        try {
            CreatedStaff created = directoryService.create(request, callerIsSuperAdmin);
            return new ImportRowResult(rowNumber, staffId, name, "IMPORTED", warnings, email.toLowerCase(Locale.ROOT),
                    created.temporaryPassword());
        } catch (ApiException ex) {
            // A bad role/department/designation fails every row the same way, so report it once, up front.
            if (ex.getStatus() == HttpStatus.NOT_FOUND || ex.getStatus() == HttpStatus.BAD_REQUEST) {
                throw ex;
            }
            return new ImportRowResult(rowNumber, staffId, name, "SKIPPED", List.of(ex.getMessage()), email, null);
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

    private static LocalDate date(Row row, String column, boolean required, List<String> errors, List<String> warnings) {
        String value = row.get(column);
        String label = column.replace('_', ' ');
        label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
        if (value == null) {
            if (required) errors.add(label + " is required");
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            if (required) {
                errors.add(label + " \"" + value + "\" is not a yyyy-mm-dd date");
            } else {
                warnings.add(label + " \"" + value + "\" left out (use yyyy-mm-dd)");
            }
            return null;
        }
    }

    private static String phone(Row row, String column, String label, List<String> warnings) {
        String value = row.get(column);
        if (value == null) return null;
        if (value.length() <= 30 && PHONE.matcher(value).matches()) return value;
        warnings.add(label + " \"" + value + "\" left out (not a valid phone number)");
        return null;
    }

    private static String limit(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
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
}
