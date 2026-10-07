package com.smsapp.staff;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.frontoffice.FrontOfficeFileStore;
import com.smsapp.frontoffice.FrontOfficeFileStore.StoredFile;
import com.smsapp.staff.StaffDirectoryDtos.DocumentInfo;
import com.smsapp.staff.StaffDirectoryDtos.LookupOption;
import com.smsapp.staff.StaffDirectoryDtos.StaffCardResponse;
import com.smsapp.staff.StaffDirectoryDtos.StaffMemberResponse;
import com.smsapp.staff.StaffDirectoryDtos.StaffOptionsResponse;
import com.smsapp.staff.StaffDirectoryDtos.StaffRequest;
import com.smsapp.user.Role;
import com.smsapp.user.RoleRepository;
import com.smsapp.user.Roles;
import com.smsapp.user.User;
import com.smsapp.user.UserRepository;
import com.smsapp.user.UserRole;
import com.smsapp.user.UserRoleRepository;
import com.smsapp.user.UserService;
import com.smsapp.user.UserService.CreatedAccount;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Human Resource > Staff Directory: the full staff record behind {@link StaffProfile}. Adding a staff member
 * creates their login (email + one role + a temporary password) and their profile in one transaction. The photo
 * and documents are stored with {@link FrontOfficeFileStore}; replaced or deleted files are removed only after
 * the database commit, and a file written for a save that rolls back is removed, so disk and database agree.
 *
 * <p>{@code StaffProfile.department} / {@code designation} (text, read by the older Staff page) are kept in step
 * with the chosen lookup entries.
 */
@Service
public class StaffDirectoryService {

    static final String STORAGE_AREA = "staff";
    public static final String ACTIVE = "ACTIVE";

    private final StaffProfileRepository profileRepository;
    private final StaffDocumentRepository documentRepository;
    private final DepartmentRepository departmentRepository;
    private final DesignationRepository designationRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserService userService;
    private final FrontOfficeFileStore fileStore;
    private final AuditService auditService;

    public StaffDirectoryService(StaffProfileRepository profileRepository, StaffDocumentRepository documentRepository,
                                 DepartmentRepository departmentRepository, DesignationRepository designationRepository,
                                 UserRepository userRepository, RoleRepository roleRepository,
                                 UserRoleRepository userRoleRepository, UserService userService,
                                 FrontOfficeFileStore fileStore, AuditService auditService) {
        this.profileRepository = profileRepository;
        this.documentRepository = documentRepository;
        this.departmentRepository = departmentRepository;
        this.designationRepository = designationRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.userService = userService;
        this.fileStore = fileStore;
        this.auditService = auditService;
    }

    /** A new staff member together with the one-time temporary password of their login. */
    public record CreatedStaff(StaffProfile profile, String temporaryPassword) {
    }

    // --- Options ------------------------------------------------------------------------------

    /** Roles a staff member can have (never STUDENT/PARENT; SUPER_ADMIN only for a super admin), departments, designations. */
    @Transactional(readOnly = true)
    public StaffOptionsResponse options(boolean callerIsSuperAdmin) {
        List<LookupOption> roles = roleRepository.findAllByOrderByName().stream()
                .filter(role -> isAssignable(role, callerIsSuperAdmin))
                .map(role -> new LookupOption(role.getId(), role.getName())).toList();
        List<LookupOption> designations = designationRepository.findByActiveTrueOrderByName().stream()
                .map(d -> new LookupOption(d.getId(), d.getName())).toList();
        List<LookupOption> departments = departmentRepository.findByActiveTrueOrderByName().stream()
                .map(d -> new LookupOption(d.getId(), d.getName())).toList();
        return new StaffOptionsResponse(roles, designations, departments);
    }

    // --- Directory ------------------------------------------------------------------------------

    /**
     * The directory, ordered by Staff ID. {@code roleId} narrows to one role; {@code query} matches Staff ID,
     * name, phone, email, role, designation, department or work location. Only ACTIVE staff unless
     * {@code status} says otherwise.
     */
    @Transactional(readOnly = true)
    public List<StaffCardResponse> list(UUID roleId, String query, String status) {
        String wantedStatus = status == null || status.isBlank() ? ACTIVE : status.trim().toUpperCase(Locale.ROOT);
        List<StaffProfile> profiles = profileRepository.findAllByOrderByEmployeeCode().stream()
                .filter(p -> wantedStatus.equals(p.getStatus())).toList();
        Lookups lookups = lookups(profiles);
        String term = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return profiles.stream()
                .map(p -> card(p, lookups))
                .filter(card -> roleId == null || roleId.equals(card.roleId()))
                .filter(card -> term.isEmpty() || matches(card, term))
                .toList();
    }

    @Transactional(readOnly = true)
    public StaffMemberResponse get(UUID id) {
        return toResponse(require(id));
    }

    /** @throws ApiException 404 if no such staff member. */
    @Transactional(readOnly = true)
    public StaffProfile require(UUID id) {
        return profileRepository.findById(id).orElseThrow(() -> new ApiException("Staff member not found", HttpStatus.NOT_FOUND));
    }

    // --- Create / update ------------------------------------------------------------------------

    /**
     * @throws ApiException 409 if the Staff ID or email is already used, 404 if the department or designation
     *         doesn't exist, 400 if the role doesn't exist or can't be given to staff.
     */
    @Transactional
    public CreatedStaff create(StaffRequest request, boolean callerIsSuperAdmin) {
        Role role = requireAssignableRole(request.roleId(), callerIsSuperAdmin);
        String staffId = request.staffId().trim();
        if (profileRepository.existsByEmployeeCode(staffId)) {
            throw staffIdConflict(staffId);
        }
        Department department = department(request.departmentId());
        Designation designation = designation(request.designationId());

        CreatedAccount account = userService.createAccount(request.email(), fullName(request), role.getId());

        StaffProfile profile = new StaffProfile();
        profile.setUserId(account.user().getId());
        profile.setEmployeeCode(staffId);
        profile.setStatus(ACTIVE);
        apply(profile, request, department, designation);
        StaffProfile saved;
        try {
            saved = profileRepository.saveAndFlush(profile);
        } catch (DataIntegrityViolationException ex) {
            throw staffIdConflict(staffId);
        }
        auditService.log(AuditActions.STAFF_PROFILE_CREATED, AuditActions.STAFF_PROFILE, saved.getId(),
                Map.of("employeeCode", saved.getEmployeeCode(), "userId", saved.getUserId().toString(),
                        "source", "staff_directory"));
        return new CreatedStaff(saved, account.temporaryPassword());
    }

    /**
     * Updates everything on the form except the Staff ID and the login email, which must be sent unchanged.
     *
     * @throws ApiException 404 if no such staff member, department or designation, 400 if the Staff ID or email
     *         differs from the existing one or the role can't be given to staff.
     */
    @Transactional
    public StaffProfile update(UUID id, StaffRequest request, boolean callerIsSuperAdmin) {
        StaffProfile profile = require(id);
        User user = userRepository.findById(profile.getUserId())
                .orElseThrow(() -> new IllegalStateException("Staff profile " + id + " references a missing user"));
        if (!profile.getEmployeeCode().equals(request.staffId().trim())) {
            throw new ApiException("The Staff ID can't be changed", HttpStatus.BAD_REQUEST);
        }
        if (!user.getEmail().equalsIgnoreCase(request.email().trim())) {
            throw new ApiException("The login email can't be changed here", HttpStatus.BAD_REQUEST);
        }
        Role role = requireAssignableRole(request.roleId(), callerIsSuperAdmin);
        apply(profile, request, department(request.departmentId()), designation(request.designationId()));
        StaffProfile saved = profileRepository.save(profile);
        userService.updateNameAndRole(user.getId(), fullName(request), role.getId());
        auditService.log(AuditActions.STAFF_PROFILE_UPDATED, AuditActions.STAFF_PROFILE, id,
                Map.of("employeeCode", saved.getEmployeeCode(), "source", "staff_directory"));
        return saved;
    }

    // --- Status and login ---------------------------------------------------------------------------

    /** Disables or re-enables a staff member: their directory status and their login. */
    @Transactional
    public StaffProfile setActive(UUID id, boolean active) {
        StaffProfile profile = require(id);
        User user = userRepository.findById(profile.getUserId())
                .orElseThrow(() -> new ApiException("Staff member not found", HttpStatus.NOT_FOUND));
        profile.setStatus(active ? ACTIVE : "INACTIVE");
        user.setStatus(active ? "ACTIVE" : "INACTIVE");
        userRepository.save(user);
        StaffProfile saved = profileRepository.save(profile);
        auditService.log(AuditActions.STAFF_STATUS_CHANGED, AuditActions.STAFF_PROFILE, id,
                Map.of("employeeCode", saved.getEmployeeCode(), "status", saved.getStatus()));
        return saved;
    }

    /** Issues a new temporary password for the staff member's login; returned once. */
    @Transactional
    public String resetPassword(UUID id) {
        StaffProfile profile = require(id);
        String password = userService.resetPasswordForStaff(profile.getUserId());
        auditService.log(AuditActions.STAFF_PASSWORD_RESET, AuditActions.STAFF_PROFILE, id,
                Map.of("employeeCode", profile.getEmployeeCode()));
        return password;
    }

    // --- Photo ----------------------------------------------------------------------------------

    /** @throws ApiException 404 if no such staff member, 400 if the file isn't an image, is empty or over 10 MB. */
    @Transactional
    public StaffProfile attachPhoto(UUID id, MultipartFile file) {
        StaffProfile profile = require(id);
        String contentType = file == null ? null : file.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new ApiException("The photo must be a JPG, PNG or WEBP image", HttpStatus.BAD_REQUEST);
        }
        StoredFile stored = fileStore.store(STORAGE_AREA, id, file);
        onRollback(() -> fileStore.delete(STORAGE_AREA, id, stored.storedFilename()));
        String previous = profile.getPhotoStoredFilename();
        if (previous != null) {
            afterCommit(() -> fileStore.delete(STORAGE_AREA, id, previous));
        }
        profile.setPhotoOriginalFilename(stored.originalFilename());
        profile.setPhotoStoredFilename(stored.storedFilename());
        profile.setPhotoContentType(stored.contentType());
        StaffProfile saved = profileRepository.save(profile);
        auditService.log(AuditActions.STAFF_PHOTO_UPLOADED, AuditActions.STAFF_PROFILE, id,
                Map.of("employeeCode", saved.getEmployeeCode()));
        return saved;
    }

    /** @throws ApiException 404 if no such staff member or it has no photo. */
    @Transactional
    public StaffProfile removePhoto(UUID id) {
        StaffProfile profile = require(id);
        String storedFile = profile.getPhotoStoredFilename();
        if (storedFile == null) {
            throw new ApiException("This staff member has no photo", HttpStatus.NOT_FOUND);
        }
        profile.setPhotoOriginalFilename(null);
        profile.setPhotoStoredFilename(null);
        profile.setPhotoContentType(null);
        StaffProfile saved = profileRepository.save(profile);
        afterCommit(() -> fileStore.delete(STORAGE_AREA, id, storedFile));
        auditService.log(AuditActions.STAFF_PHOTO_REMOVED, AuditActions.STAFF_PROFILE, id,
                Map.of("employeeCode", saved.getEmployeeCode()));
        return saved;
    }

    /** @throws ApiException 404 if there's no photo or the file is missing. */
    @Transactional(readOnly = true)
    public Resource loadPhoto(StaffProfile profile) {
        if (!profile.hasPhoto()) {
            throw new ApiException("This staff member has no photo", HttpStatus.NOT_FOUND);
        }
        return fileStore.load(STORAGE_AREA, profile.getId(), profile.getPhotoStoredFilename());
    }

    // --- Documents ------------------------------------------------------------------------------

    /**
     * Attaches (or replaces) the staff member's document of that kind.
     *
     * @throws ApiException 404 if no such staff member, 400 if the kind is unknown or the file is empty, over
     *         10 MB or not an allowed type.
     */
    @Transactional
    public StaffProfile attachDocument(UUID id, String kindName, MultipartFile file) {
        StaffProfile profile = require(id);
        StaffDocumentKind kind = requireKind(kindName);
        StoredFile stored = fileStore.store(STORAGE_AREA, id, file);
        onRollback(() -> fileStore.delete(STORAGE_AREA, id, stored.storedFilename()));
        StaffDocument document = documentRepository.findByStaffProfileIdAndKind(id, kind.name()).orElseGet(() -> {
            StaffDocument created = new StaffDocument();
            created.setStaffProfileId(id);
            created.setKind(kind.name());
            return created;
        });
        String previous = document.getStoredFilename();
        if (previous != null) {
            afterCommit(() -> fileStore.delete(STORAGE_AREA, id, previous));
        }
        document.setOriginalFilename(stored.originalFilename());
        document.setStoredFilename(stored.storedFilename());
        document.setContentType(stored.contentType());
        document.setSizeBytes(stored.sizeBytes());
        documentRepository.save(document);
        auditService.log(AuditActions.STAFF_DOCUMENT_UPLOADED, AuditActions.STAFF_PROFILE, id,
                Map.of("kind", kind.name(), "fileName", stored.originalFilename()));
        return profile;
    }

    /** @throws ApiException 404 if no such staff member or no document of that kind, 400 if the kind is unknown. */
    @Transactional
    public StaffProfile removeDocument(UUID id, String kindName) {
        StaffProfile profile = require(id);
        StaffDocumentKind kind = requireKind(kindName);
        StaffDocument document = documentRepository.findByStaffProfileIdAndKind(id, kind.name())
                .orElseThrow(() -> new ApiException("No " + kind.title() + " has been uploaded", HttpStatus.NOT_FOUND));
        String storedFile = document.getStoredFilename();
        documentRepository.delete(document);
        afterCommit(() -> fileStore.delete(STORAGE_AREA, id, storedFile));
        auditService.log(AuditActions.STAFF_DOCUMENT_REMOVED, AuditActions.STAFF_PROFILE, id, Map.of("kind", kind.name()));
        return profile;
    }

    /** @throws ApiException 404 if there's no document of that kind or the file is missing, 400 if the kind is unknown. */
    @Transactional(readOnly = true)
    public StaffDocument document(UUID id, String kindName) {
        StaffDocumentKind kind = requireKind(kindName);
        require(id);
        return documentRepository.findByStaffProfileIdAndKind(id, kind.name())
                .orElseThrow(() -> new ApiException("No " + kind.title() + " has been uploaded", HttpStatus.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Resource loadDocument(StaffDocument document) {
        return fileStore.load(STORAGE_AREA, document.getStaffProfileId(), document.getStoredFilename());
    }

    // --- Response mapping -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public StaffMemberResponse toResponse(StaffProfile p) {
        Lookups lookups = lookups(List.of(p));
        User user = lookups.users().get(p.getUserId());
        Role role = lookups.roleOf(p.getUserId());
        List<DocumentInfo> documents = documentRepository.findByStaffProfileId(p.getId()).stream()
                .map(d -> new DocumentInfo(d.getKind(), StaffDocumentKind.valueOf(d.getKind()).title(),
                        d.getOriginalFilename(), d.getContentType(), d.getSizeBytes()))
                .sorted(Comparator.comparing(d -> StaffDocumentKind.valueOf(d.kind()).ordinal())).toList();
        return new StaffMemberResponse(p.getId(), p.getUserId(), p.getEmployeeCode(), p.getFirstName(), p.getLastName(),
                user == null ? null : user.getFullName(), p.getFatherName(), p.getMotherName(),
                user == null ? null : user.getEmail(), role == null ? null : role.getId(),
                role == null ? null : role.getName(), p.getDesignationId(), lookups.designationName(p.getDesignationId()),
                p.getDepartmentId(), lookups.departmentName(p.getDepartmentId()), p.getGender(), p.getDateOfBirth(),
                p.getDateOfJoining(), p.getDateOfLeaving(), p.getPhone(), p.getEmergencyContactNumber(),
                p.getMaritalStatus(), p.getCurrentAddress(), p.getPermanentAddress(), p.getQualification(),
                p.getWorkExperience(), p.getNote(), p.getPanNumber(), p.getEpfNo(), p.getSalaryAmount(),
                p.getContractType(), p.getWorkShift(), p.getWorkLocation(), p.getMedicalLeave(), p.getCasualLeave(),
                p.getMaternityLeave(), p.getSickLeave(), p.getMandatoryLeave(), p.getAccountTitle(),
                p.getBankAccountNumber(), p.getBankName(), p.getIfscCode(), p.getBankBranchName(), p.getFacebookUrl(),
                p.getTwitterUrl(), p.getLinkedinUrl(), p.getInstagramUrl(), p.hasPhoto(), documents, p.getStatus(),
                p.getCreatedAt());
    }

    // --- Helpers ----------------------------------------------------------------------------------

    private StaffCardResponse card(StaffProfile p, Lookups lookups) {
        User user = lookups.users().get(p.getUserId());
        Role role = lookups.roleOf(p.getUserId());
        return new StaffCardResponse(p.getId(), p.getUserId(), p.getEmployeeCode(), user == null ? null : user.getFullName(), p.getPhone(),
                user == null ? null : user.getEmail(), p.getWorkLocation(), lookups.departmentName(p.getDepartmentId()),
                lookups.designationName(p.getDesignationId()), role == null ? null : role.getId(),
                role == null ? null : role.getName(), p.getDateOfJoining(), p.hasPhoto(), p.getStatus());
    }

    private static boolean matches(StaffCardResponse card, String term) {
        return java.util.stream.Stream.of(card.staffId(), card.fullName(), card.phone(), card.email(), card.roleName(),
                        card.designationName(), card.departmentName(), card.workLocation())
                .anyMatch(value -> value != null && value.toLowerCase(Locale.ROOT).contains(term));
    }

    /** The users, roles, departments and designations behind a set of profiles, each fetched once. */
    private record Lookups(Map<UUID, User> users, Map<UUID, Role> roleByUserId, Map<UUID, String> departments,
                           Map<UUID, String> designations) {
        Role roleOf(UUID userId) {
            return roleByUserId.get(userId);
        }

        String departmentName(UUID id) {
            return id == null ? null : departments.get(id);
        }

        String designationName(UUID id) {
            return id == null ? null : designations.get(id);
        }
    }

    private Lookups lookups(List<StaffProfile> profiles) {
        Set<UUID> userIds = profiles.stream().map(StaffProfile::getUserId).collect(Collectors.toSet());
        Map<UUID, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        Map<UUID, Role> roles = roleRepository.findAll().stream().collect(Collectors.toMap(Role::getId, r -> r));
        Map<UUID, Role> roleByUserId = new HashMap<>();
        for (UserRole link : userRoleRepository.findAll()) {
            Role role = roles.get(link.getRoleId());
            if (role != null && userIds.contains(link.getUserId())) {
                // A staff login has one role; if it somehow has several, show the alphabetically first.
                roleByUserId.merge(link.getUserId(), role,
                        (a, b) -> a.getName().compareTo(b.getName()) <= 0 ? a : b);
            }
        }
        Map<UUID, String> departments = departmentRepository.findAll().stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));
        Map<UUID, String> designations = designationRepository.findAll().stream()
                .collect(Collectors.toMap(Designation::getId, Designation::getName));
        return new Lookups(users, roleByUserId, departments, designations);
    }

    private void apply(StaffProfile p, StaffRequest r, Department department, Designation designation) {
        p.setDepartmentId(department == null ? null : department.getId());
        p.setDepartment(department == null ? null : department.getName());
        p.setDesignationId(designation == null ? null : designation.getId());
        p.setDesignation(designation == null ? null : designation.getName());
        p.setFirstName(r.firstName().trim());
        p.setLastName(blankToNull(r.lastName()));
        p.setFatherName(blankToNull(r.fatherName()));
        p.setMotherName(blankToNull(r.motherName()));
        p.setGender(r.gender().trim().toUpperCase(Locale.ROOT));
        p.setDateOfBirth(r.dateOfBirth());
        p.setDateOfJoining(r.dateOfJoining());
        p.setPhone(blankToNull(r.phone()));
        p.setEmergencyContactNumber(blankToNull(r.emergencyContactNumber()));
        p.setMaritalStatus(upperOrNull(r.maritalStatus()));
        p.setCurrentAddress(blankToNull(r.currentAddress()));
        p.setPermanentAddress(blankToNull(r.permanentAddress()));
        p.setQualification(blankToNull(r.qualification()));
        p.setWorkExperience(blankToNull(r.workExperience()));
        p.setNote(blankToNull(r.note()));
        p.setPanNumber(blankToNull(r.panNumber()));
        p.setEpfNo(blankToNull(r.epfNo()));
        p.setSalaryAmount(r.basicSalary() == null ? BigDecimal.ZERO : r.basicSalary());
        p.setContractType(upperOrNull(r.contractType()));
        p.setWorkShift(blankToNull(r.workShift()));
        p.setWorkLocation(blankToNull(r.workLocation()));
        p.setMedicalLeave(r.medicalLeave());
        p.setCasualLeave(r.casualLeave());
        p.setMaternityLeave(r.maternityLeave());
        p.setSickLeave(r.sickLeave());
        p.setMandatoryLeave(r.mandatoryLeave());
        p.setAccountTitle(blankToNull(r.accountTitle()));
        p.setBankAccountNumber(blankToNull(r.bankAccountNumber()));
        p.setBankName(blankToNull(r.bankName()));
        p.setIfscCode(blankToNull(r.ifscCode()));
        p.setBankBranchName(blankToNull(r.bankBranchName()));
        p.setFacebookUrl(blankToNull(r.facebookUrl()));
        p.setTwitterUrl(blankToNull(r.twitterUrl()));
        p.setLinkedinUrl(blankToNull(r.linkedinUrl()));
        p.setInstagramUrl(blankToNull(r.instagramUrl()));
    }

    private static String fullName(StaffRequest r) {
        String last = blankToNull(r.lastName());
        return r.firstName().trim() + (last == null ? "" : " " + last);
    }

    private Department department(UUID id) {
        return id == null ? null : departmentRepository.findById(id)
                .orElseThrow(() -> new ApiException("Department not found", HttpStatus.NOT_FOUND));
    }

    private Designation designation(UUID id) {
        return id == null ? null : designationRepository.findById(id)
                .orElseThrow(() -> new ApiException("Designation not found", HttpStatus.NOT_FOUND));
    }

    private Role requireAssignableRole(UUID roleId, boolean callerIsSuperAdmin) {
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ApiException("Role not found", HttpStatus.BAD_REQUEST));
        if (!isAssignable(role, callerIsSuperAdmin)) {
            throw new ApiException("That role can't be given to a staff member", HttpStatus.BAD_REQUEST);
        }
        return role;
    }

    private static boolean isAssignable(Role role, boolean callerIsSuperAdmin) {
        String name = role.getName();
        if (Roles.STUDENT.equals(name) || Roles.PARENT.equals(name)) {
            return false;
        }
        return callerIsSuperAdmin || !Roles.SUPER_ADMIN.equals(name);
    }

    private static StaffDocumentKind requireKind(String kindName) {
        return StaffDocumentKind.parse(kindName)
                .orElseThrow(() -> new ApiException("Unknown document type '" + kindName + "'", HttpStatus.BAD_REQUEST));
    }

    private static ApiException staffIdConflict(String staffId) {
        return new ApiException("Staff ID '" + staffId + "' is already in use", HttpStatus.CONFLICT);
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static void onRollback(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    action.run();
                }
            }
        });
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String upperOrNull(String value) {
        String trimmed = blankToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }
}
