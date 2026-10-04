package com.smsapp.staff;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.frontoffice.FrontOfficeFileStore;
import com.smsapp.frontoffice.FrontOfficeFileStore.StoredFile;
import com.smsapp.notification.NotificationService;
import com.smsapp.staff.LeaveManagementDtos.AttachmentInfo;
import com.smsapp.staff.LeaveManagementDtos.LeaveBalance;
import com.smsapp.staff.LeaveManagementDtos.LeaveBody;
import com.smsapp.staff.LeaveManagementDtos.LeaveResponse;
import com.smsapp.staff.LeaveManagementDtos.MyLeaveInfo;
import com.smsapp.staff.LeaveManagementDtos.Option;
import com.smsapp.staff.LeaveManagementDtos.OptionsResponse;
import com.smsapp.staff.LeaveManagementDtos.StaffOption;
import com.smsapp.user.Role;
import com.smsapp.user.RoleRepository;
import com.smsapp.user.Roles;
import com.smsapp.user.User;
import com.smsapp.user.UserRepository;
import com.smsapp.user.UserRole;
import com.smsapp.user.UserRoleRepository;
import com.smsapp.user.UserStatus;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Human Resource > Approve Leave Request, with an approval flow by the applicant's role:
 * <ul>
 *   <li>a Principal's request goes to the Super Admin;</li>
 *   <li>a Super Admin's or School Admin's request goes to another Super Admin or School Admin;</li>
 *   <li>everyone else's (Teacher, Librarian, ...) goes to the Principal.</li>
 * </ul>
 * Super Admin and School Admin can decide any request but their own; the Principal only the requests sent to them
 * (not their own, not an admin's). Nobody decides their own request. A user sees their own requests and the ones
 * they can decide, nothing else. The approvers are notified when a request is sent, and the applicant when it is
 * decided.
 *
 * <p>Days are the calendar days from the first to the last leave date, or 0.5 for a half day (one day, first or
 * second half). A staff member cannot have two requests (other than disapproved ones) that overlap. The optional
 * document is stored with {@link FrontOfficeFileStore}; replaced or deleted files are removed only after the
 * database commit, and a file written for a save that rolls back is removed.
 */
@Service
public class LeaveManagementService {

    static final String STORAGE_AREA = "leave-requests";
    static final String APPROVAL_PAGE = "/app/human-resource/approve-leave-request";
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final long MAX_DAYS = 366;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private final LeaveRequestRepository leaveRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final StaffProfileRepository profileRepository;
    private final StaffDirectoryService directoryService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final FrontOfficeFileStore fileStore;
    private final AuditService auditService;
    private final NotificationService notificationService;

    /** The signed-in user: their id and role names (without the ROLE_ prefix). */
    public record LeaveActor(UUID userId, Set<String> roles) {
    }

    /** Which approval path a request takes, by its applicant's role. */
    enum Tier {
        ADMIN, PRINCIPAL, STAFF;

        static Tier of(String roleName) {
            if (Roles.SUPER_ADMIN.equals(roleName) || Roles.SCHOOL_ADMIN.equals(roleName)) {
                return ADMIN;
            }
            return Roles.PRINCIPAL.equals(roleName) ? PRINCIPAL : STAFF;
        }

        /** Who the request is sent to, as shown on the page. */
        String approverLabel() {
            return switch (this) {
                case ADMIN -> "Super Admin / School Admin";
                case PRINCIPAL -> "Super Admin";
                case STAFF -> "Principal";
            };
        }
    }

    public LeaveManagementService(LeaveRequestRepository leaveRepository, LeaveTypeRepository leaveTypeRepository,
                                  StaffProfileRepository profileRepository, StaffDirectoryService directoryService,
                                  UserRepository userRepository, RoleRepository roleRepository,
                                  UserRoleRepository userRoleRepository, FrontOfficeFileStore fileStore,
                                  AuditService auditService, NotificationService notificationService) {
        this.leaveRepository = leaveRepository;
        this.leaveTypeRepository = leaveTypeRepository;
        this.profileRepository = profileRepository;
        this.directoryService = directoryService;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.fileStore = fileStore;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    // --- Who may do what ---------------------------------------------------------------------------

    /** Whether {@code actor} may decide (set the status of, edit, delete) a request of this applicant. */
    static boolean canDecide(LeaveActor actor, UUID applicantUserId, String applicantRole) {
        if (actor.userId().equals(applicantUserId)) {
            return false; // nobody decides their own request
        }
        if (actor.roles().contains(Roles.SUPER_ADMIN) || actor.roles().contains(Roles.SCHOOL_ADMIN)) {
            return true;
        }
        return actor.roles().contains(Roles.PRINCIPAL) && Tier.of(applicantRole) == Tier.STAFF;
    }

    private boolean canDecide(LeaveActor actor, LeaveRequest request) {
        return canDecide(actor, request.getStaffUserId(), roleNameOf(request.getStaffUserId()));
    }

    /** A request is visible to its applicant and to whoever can decide it. */
    private boolean visible(LeaveActor actor, LeaveRequest request) {
        return request.getStaffUserId().equals(actor.userId()) || canDecide(actor, request);
    }

    // --- Options ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public OptionsResponse options() {
        List<Option> roles = directoryService.options(true).roles().stream().map(r -> new Option(r.id(), r.name())).toList();
        List<Option> types = leaveTypeRepository.findByActiveTrueOrderByName().stream()
                .map(t -> new Option(t.getId(), t.getName())).toList();
        return new OptionsResponse(roles, types);
    }

    /** The active staff of a role the actor may add a request for: themselves, and staff they can decide for. */
    @Transactional(readOnly = true)
    public List<StaffOption> staff(UUID roleId, LeaveActor actor) {
        return directoryService.list(roleId, null, StaffDirectoryService.ACTIVE).stream()
                .filter(s -> s.userId().equals(actor.userId()) || canDecide(actor, s.userId(), s.roleName()))
                .map(s -> new StaffOption(s.id(), s.staffId(), s.fullName(), s.userId().equals(actor.userId()))).toList();
    }

    // --- Reads --------------------------------------------------------------------------------------

    /** The requests the actor can see (their own and the ones they can decide), latest leave date first. */
    @Transactional(readOnly = true)
    public List<LeaveResponse> list(LeaveActor actor) {
        Map<UUID, String> roleByUser = roleNamesByUser();
        List<LeaveRequest> requests = leaveRepository.findAllByOrderByStartDateDescCreatedAtDesc().stream()
                .filter(r -> r.getStaffUserId().equals(actor.userId())
                        || canDecide(actor, r.getStaffUserId(), roleByUser.get(r.getStaffUserId())))
                .toList();
        return toResponses(requests, actor);
    }

    /** The actor's own requests, latest leave date first -- the Apply Leave page, whatever their role. */
    @Transactional(readOnly = true)
    public List<LeaveResponse> mine(LeaveActor actor) {
        return toResponses(leaveRepository.findByStaffUserIdOrderByStartDateDescCreatedAtDesc(actor.userId()), actor);
    }

    /**
     * The actor as a leave applicant: who approves their requests and what is left of each leave type for the year.
     * A leave type with no entitlement on their staff record has no limit.
     *
     * @throws ApiException 404 if no staff profile is linked to the account.
     */
    @Transactional(readOnly = true)
    public MyLeaveInfo myInfo(LeaveActor actor, int year) {
        StaffProfile profile = profileRepository.findByUserId(actor.userId()).orElseThrow(
                () -> new ApiException("No staff profile is linked to your account", HttpStatus.NOT_FOUND));
        String roleName = roleNameOf(actor.userId());
        String name = userRepository.findById(actor.userId()).map(User::getFullName).orElse(profile.getEmployeeCode());
        List<LeaveRequest> counted = leaveRepository.findByStaffUserIdOrderByStartDateDescCreatedAtDesc(actor.userId()).stream()
                .filter(r -> !LeaveRequestStatus.REJECTED.equals(r.getStatus()) && r.getStartDate().getYear() == year).toList();
        List<LeaveBalance> balances = leaveTypeRepository.findByActiveTrueOrderByName().stream().map(type -> {
            Integer allotted = entitlement(profile, type.getName());
            BigDecimal used = counted.stream().filter(r -> type.getId().equals(r.getLeaveTypeId()))
                    .map(LeaveRequest::getDays).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal available = allotted == null ? null : BigDecimal.valueOf(allotted).subtract(used).max(BigDecimal.ZERO);
            return new LeaveBalance(type.getId(), type.getName(), allotted, used, available);
        }).toList();
        return new MyLeaveInfo(profile.getId(), profile.getEmployeeCode(), name, roleName, Tier.of(roleName).approverLabel(),
                year, balances);
    }

    /**
     * Withdraws the actor's own request while it is still Pending.
     *
     * @throws ApiException 404 if it does not exist or is someone else's, 409 if it has already been decided.
     */
    @Transactional
    public void cancel(UUID id, LeaveActor actor) {
        LeaveRequest request = leaveRepository.findById(id).orElseThrow(LeaveManagementService::notFound);
        if (!request.getStaffUserId().equals(actor.userId())) {
            throw notFound();
        }
        if (!LeaveRequestStatus.PENDING.equals(request.getStatus())) {
            throw new ApiException("Only a pending leave request can be cancelled", HttpStatus.CONFLICT);
        }
        String storedFile = request.getAttachmentStoredFilename();
        leaveRepository.delete(request);
        afterCommit(() -> {
            if (storedFile != null) {
                fileStore.delete(STORAGE_AREA, id, storedFile);
            }
            fileStore.deleteFolderIfEmpty(STORAGE_AREA, id);
        });
        auditService.log(AuditActions.LEAVE_REQUEST_DELETED, AuditActions.LEAVE_REQUEST, id,
                Map.of("staffUserId", request.getStaffUserId().toString(), "cancelledByApplicant", true));
    }

    /** @throws ApiException 404 if there is no such request, or the actor can neither decide it nor is its applicant. */
    @Transactional(readOnly = true)
    public LeaveResponse get(UUID id, LeaveActor actor) {
        return toResponses(List.of(require(id, actor)), actor).get(0);
    }

    // --- Writes ---------------------------------------------------------------------------------------

    /**
     * Adds a request, for oneself or (for an approver) for staff they can decide for. The status is only honoured when
     * the actor can decide it; otherwise it is Pending. A Pending request notifies the approvers.
     *
     * @throws ApiException 404 if the staff member or leave type does not exist, 403 if the actor may not add a
     *         request for that staff member, 400 for bad dates or an inactive staff member, 409 if it overlaps
     *         another request of theirs.
     */
    @Transactional
    public LeaveRequest create(LeaveBody body, LeaveActor actor) {
        StaffProfile profile = requireStaff(body.staffProfileId());
        boolean own = profile.getUserId().equals(actor.userId());
        boolean decider = canDecide(actor, profile.getUserId(), roleNameOf(profile.getUserId()));
        if (!own && !decider) {
            throw new ApiException("You can only apply for leave for yourself", HttpStatus.FORBIDDEN);
        }
        LeaveRequest request = new LeaveRequest();
        request.setStaffUserId(profile.getUserId());
        apply(request, body, decider, actor.userId());
        LeaveRequest saved = leaveRepository.saveAndFlush(request);
        auditService.log(AuditActions.LEAVE_REQUEST_CREATED, AuditActions.LEAVE_REQUEST, saved.getId(),
                Map.of("staffUserId", saved.getStaffUserId().toString(), "leaveType", saved.getLeaveType(),
                        "days", saved.getDays().toPlainString()));
        if (LeaveRequestStatus.PENDING.equals(saved.getStatus())) {
            notifyApprovers(saved);
        } else {
            notifyApplicant(saved, actor);
        }
        return saved;
    }

    /**
     * Changes a request, including its status, and notifies the applicant when it is decided. The staff member cannot
     * be changed.
     *
     * @throws ApiException 404 if there is no such request (or the actor cannot see it), 403 if the actor cannot decide
     *         it, 400 for bad dates or a different staff member, 409 if it overlaps another request of the staff member.
     */
    @Transactional
    public LeaveRequest update(UUID id, LeaveBody body, LeaveActor actor) {
        LeaveRequest request = require(id, actor);
        requireDecider(actor, request);
        StaffProfile profile = requireStaff(body.staffProfileId());
        if (!profile.getUserId().equals(request.getStaffUserId())) {
            throw new ApiException("The staff member of a leave request cannot be changed", HttpStatus.BAD_REQUEST);
        }
        String before = request.getStatus();
        apply(request, body, true, actor.userId());
        LeaveRequest saved = leaveRepository.save(request);
        auditService.log(AuditActions.LEAVE_REQUEST_UPDATED, AuditActions.LEAVE_REQUEST, id,
                Map.of("status", saved.getStatus(), "days", saved.getDays().toPlainString()));
        if (!before.equals(saved.getStatus()) && !LeaveRequestStatus.PENDING.equals(saved.getStatus())) {
            notifyApplicant(saved, actor);
        }
        return saved;
    }

    /** Deletes the request and, after commit, its document. @throws ApiException 404 if not visible, 403 if not decidable. */
    @Transactional
    public void delete(UUID id, LeaveActor actor) {
        LeaveRequest request = require(id, actor);
        requireDecider(actor, request);
        String storedFile = request.getAttachmentStoredFilename();
        leaveRepository.delete(request);
        afterCommit(() -> {
            if (storedFile != null) {
                fileStore.delete(STORAGE_AREA, id, storedFile);
            }
            fileStore.deleteFolderIfEmpty(STORAGE_AREA, id);
        });
        auditService.log(AuditActions.LEAVE_REQUEST_DELETED, AuditActions.LEAVE_REQUEST, id,
                Map.of("staffUserId", request.getStaffUserId().toString()));
    }

    // --- Attachment ---------------------------------------------------------------------------------------

    /**
     * Attaches (or replaces) the request's document -- by its applicant or by whoever can decide it.
     *
     * @throws ApiException 404 if there is no such request (or the actor cannot see it), 400 if the file is empty,
     *         over 10 MB or not an allowed type.
     */
    @Transactional
    public LeaveRequest attach(UUID id, MultipartFile file, LeaveActor actor) {
        LeaveRequest request = require(id, actor);
        StoredFile stored = fileStore.store(STORAGE_AREA, id, file);
        onRollback(() -> fileStore.delete(STORAGE_AREA, id, stored.storedFilename()));
        String previous = request.getAttachmentStoredFilename();
        if (previous != null) {
            afterCommit(() -> fileStore.delete(STORAGE_AREA, id, previous));
        }
        request.setAttachmentOriginalFilename(stored.originalFilename());
        request.setAttachmentStoredFilename(stored.storedFilename());
        request.setAttachmentContentType(stored.contentType());
        request.setAttachmentSizeBytes(stored.sizeBytes());
        LeaveRequest saved = leaveRepository.save(request);
        auditService.log(AuditActions.LEAVE_REQUEST_ATTACHMENT_UPLOADED, AuditActions.LEAVE_REQUEST, id,
                Map.of("fileName", stored.originalFilename()));
        return saved;
    }

    /** @throws ApiException 404 if there is no such request (or not visible) or it has no document, 403 if not decidable. */
    @Transactional
    public LeaveRequest removeAttachment(UUID id, LeaveActor actor) {
        LeaveRequest request = require(id, actor);
        requireDecider(actor, request);
        String storedFile = request.getAttachmentStoredFilename();
        if (storedFile == null) {
            throw new ApiException("This leave request has no attached document", HttpStatus.NOT_FOUND);
        }
        request.setAttachmentOriginalFilename(null);
        request.setAttachmentStoredFilename(null);
        request.setAttachmentContentType(null);
        request.setAttachmentSizeBytes(null);
        LeaveRequest saved = leaveRepository.save(request);
        afterCommit(() -> fileStore.delete(STORAGE_AREA, id, storedFile));
        auditService.log(AuditActions.LEAVE_REQUEST_ATTACHMENT_REMOVED, AuditActions.LEAVE_REQUEST, id, Map.of());
        return saved;
    }

    /** @throws ApiException 404 if there is no such request (or not visible), no document or the file is missing. */
    @Transactional(readOnly = true)
    public LeaveRequest requireWithAttachment(UUID id, LeaveActor actor) {
        LeaveRequest request = require(id, actor);
        if (!request.hasAttachment()) {
            throw new ApiException("This leave request has no attached document", HttpStatus.NOT_FOUND);
        }
        return request;
    }

    @Transactional(readOnly = true)
    public Resource loadAttachment(LeaveRequest request) {
        return fileStore.load(STORAGE_AREA, request.getId(), request.getAttachmentStoredFilename());
    }

    /** Maps a request to its response (one lookup each for staff, users and roles). */
    @Transactional(readOnly = true)
    public LeaveResponse toResponse(LeaveRequest request, LeaveActor actor) {
        return toResponses(List.of(request), actor).get(0);
    }

    // --- Notifications ------------------------------------------------------------------------------------

    /** Tells the approvers a request is waiting: the Principal for staff, the Super Admin for the Principal, ... */
    private void notifyApprovers(LeaveRequest request) {
        Tier tier = Tier.of(roleNameOf(request.getStaffUserId()));
        Set<UUID> found = switch (tier) {
            case STAFF -> firstNonEmpty(usersWithRoles(Roles.PRINCIPAL), usersWithRoles(Roles.SUPER_ADMIN, Roles.SCHOOL_ADMIN));
            case PRINCIPAL -> firstNonEmpty(usersWithRoles(Roles.SUPER_ADMIN), usersWithRoles(Roles.SCHOOL_ADMIN));
            case ADMIN -> usersWithRoles(Roles.SUPER_ADMIN, Roles.SCHOOL_ADMIN);
        };
        Set<UUID> recipients = new LinkedHashSet<>(found);
        recipients.remove(request.getStaffUserId());
        if (recipients.isEmpty()) {
            return;
        }
        String name = userRepository.findById(request.getStaffUserId()).map(User::getFullName).orElse("A staff member");
        notificationService.notify(recipients, "LEAVE_REQUESTED", "Leave request from " + name,
                describe(request) + " Waiting for your approval.", APPROVAL_PAGE, request.getId());
    }

    /** Tells the applicant their request was approved or disapproved, and by whom. */
    private void notifyApplicant(LeaveRequest request, LeaveActor actor) {
        boolean approved = LeaveRequestStatus.APPROVED.equals(request.getStatus());
        String decider = userRepository.findById(actor.userId()).map(User::getFullName).orElse("an approver");
        notificationService.notify(Set.of(request.getStaffUserId()), "LEAVE_DECIDED",
                "Your leave request was " + (approved ? "approved" : "disapproved"),
                describe(request) + " Decided by " + decider + ".", APPROVAL_PAGE, request.getId());
    }

    private static String describe(LeaveRequest r) {
        String dates = r.getStartDate().equals(r.getEndDate()) ? r.getStartDate().format(DATE)
                : r.getStartDate().format(DATE) + " - " + r.getEndDate().format(DATE);
        String days = r.getDays().stripTrailingZeros().toPlainString() + (r.getDays().compareTo(BigDecimal.ONE) == 0 ? " day" : " days");
        return r.getLeaveType() + ", " + dates + " (" + days + ").";
    }

    private static Set<UUID> firstNonEmpty(Set<UUID> preferred, Set<UUID> fallback) {
        return preferred.isEmpty() ? fallback : preferred;
    }

    /** The active users who hold any of the roles. */
    private Set<UUID> usersWithRoles(String... roleNames) {
        Set<UUID> userIds = new LinkedHashSet<>();
        for (String name : roleNames) {
            roleRepository.findByName(name).ifPresent(role -> userRoleRepository.findByRoleId(role.getId())
                    .forEach(link -> userIds.add(link.getUserId())));
        }
        return userRepository.findAllById(userIds).stream().filter(u -> UserStatus.ACTIVE.equals(u.getStatus()))
                .map(User::getId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // --- Helpers ---------------------------------------------------------------------------------------------

    /** The days a staff member is allotted a year of a leave type (by its name), or null when none is set. */
    static Integer entitlement(StaffProfile profile, String leaveTypeName) {
        String name = leaveTypeName.toLowerCase(Locale.ROOT);
        if (name.contains("medical")) return profile.getMedicalLeave();
        if (name.contains("casual")) return profile.getCasualLeave();
        if (name.contains("maternity")) return profile.getMaternityLeave();
        if (name.contains("sick")) return profile.getSickLeave();
        if (name.contains("mandatory")) return profile.getMandatoryLeave();
        return null;
    }

    private void apply(LeaveRequest request, LeaveBody body, boolean canSetStatus, UUID deciderUserId) {
        LeaveType type = leaveTypeRepository.findById(body.leaveTypeId())
                .orElseThrow(() -> new ApiException("Leave type not found", HttpStatus.NOT_FOUND));
        LocalDate from = body.fromDate();
        LocalDate to = body.toDate();
        String halfDay = body.halfDay() == null || body.halfDay().isBlank() ? null : body.halfDay().trim().toUpperCase(Locale.ROOT);
        if (to.isBefore(from)) {
            throw new ApiException("The leave to date cannot be before the from date", HttpStatus.BAD_REQUEST);
        }
        if (halfDay != null && !from.equals(to)) {
            throw new ApiException("A half day leave must start and end on the same date", HttpStatus.BAD_REQUEST);
        }
        long calendarDays = ChronoUnit.DAYS.between(from, to) + 1;
        if (calendarDays > MAX_DAYS) {
            throw new ApiException("A leave request can cover at most " + MAX_DAYS + " days", HttpStatus.BAD_REQUEST);
        }
        boolean overlaps = leaveRepository
                .findByStaffUserIdAndStatusNotAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        request.getStaffUserId(), LeaveRequestStatus.REJECTED, to, from)
                .stream().anyMatch(other -> !other.getId().equals(request.getId()));
        if (overlaps) {
            throw new ApiException("This staff member already has a leave request for some of these dates",
                    HttpStatus.CONFLICT);
        }
        BigDecimal requestedDays = halfDay != null ? HALF : BigDecimal.valueOf(calendarDays);
        requireWithinEntitlement(request, type, from, requestedDays, body.status());
        request.setLeaveTypeId(type.getId());
        request.setLeaveType(type.getName());
        request.setApplyDate(body.applyDate());
        request.setStartDate(from);
        request.setEndDate(to);
        request.setHalfDay(halfDay);
        request.setDays(halfDay != null ? HALF : BigDecimal.valueOf(calendarDays));
        request.setReason(blankToNull(body.reason()));
        request.setNote(blankToNull(body.note()));

        // Only someone who can decide the request sets its status; for anyone else it is (and stays) Pending.
        String status;
        if (canSetStatus && body.status() != null && !body.status().isBlank()) {
            status = body.status().trim().toUpperCase(Locale.ROOT);
        } else if (request.getStatus() != null && canSetStatus) {
            status = request.getStatus();
        } else {
            status = LeaveRequestStatus.PENDING;
        }
        if (!status.equals(request.getStatus())) {
            request.setStatus(status);
            boolean decided = !LeaveRequestStatus.PENDING.equals(status);
            request.setDecidedByUserId(decided ? deciderUserId : null);
            request.setDecidedAt(decided ? OffsetDateTime.now() : null);
            if (decided && request.getId() != null) {
                auditService.log(AuditActions.LEAVE_REQUEST_DECIDED, AuditActions.LEAVE_REQUEST, request.getId(),
                        Map.of("status", status));
            }
        }
    }

    /** A staff member cannot be given more days of a leave type in a year than their record allots. */
    private void requireWithinEntitlement(LeaveRequest request, LeaveType type, LocalDate from, BigDecimal requestedDays,
                                          String requestedStatus) {
        if (LeaveRequestStatus.REJECTED.equalsIgnoreCase(requestedStatus)) {
            return; // a disapproved request takes nothing from the balance
        }
        StaffProfile profile = profileRepository.findByUserId(request.getStaffUserId()).orElse(null);
        Integer allotted = profile == null ? null : entitlement(profile, type.getName());
        if (allotted == null) {
            return;
        }
        BigDecimal used = leaveRepository.findByStaffUserIdOrderByStartDateDescCreatedAtDesc(request.getStaffUserId()).stream()
                .filter(r -> !r.getId().equals(request.getId()) && type.getId().equals(r.getLeaveTypeId())
                        && !LeaveRequestStatus.REJECTED.equals(r.getStatus()) && r.getStartDate().getYear() == from.getYear())
                .map(LeaveRequest::getDays).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal available = BigDecimal.valueOf(allotted).subtract(used).max(BigDecimal.ZERO);
        if (requestedDays.compareTo(available) > 0) {
            throw new ApiException("Only " + available.stripTrailingZeros().toPlainString() + " day(s) of " + type.getName()
                    + " are available for " + from.getYear() + " (" + allotted + " allotted)", HttpStatus.BAD_REQUEST);
        }
    }

    /** The request, if it exists and the actor may see it (applicant or decider) -- otherwise 404, never 403. */
    private LeaveRequest require(UUID id, LeaveActor actor) {
        LeaveRequest request = leaveRepository.findById(id).orElseThrow(LeaveManagementService::notFound);
        if (!visible(actor, request)) {
            throw notFound();
        }
        return request;
    }

    private void requireDecider(LeaveActor actor, LeaveRequest request) {
        if (!canDecide(actor, request)) {
            throw new ApiException("You cannot approve or change this leave request", HttpStatus.FORBIDDEN);
        }
    }

    private StaffProfile requireStaff(UUID staffProfileId) {
        StaffProfile profile = profileRepository.findById(staffProfileId)
                .orElseThrow(() -> new ApiException("Staff member not found", HttpStatus.NOT_FOUND));
        if (!StaffDirectoryService.ACTIVE.equals(profile.getStatus())) {
            throw new ApiException(profile.getEmployeeCode() + " is not an active staff member", HttpStatus.BAD_REQUEST);
        }
        return profile;
    }

    /** The role name of each user (alphabetically first if a user somehow has several). */
    private Map<UUID, String> roleNamesByUser() {
        Map<UUID, Role> roles = roleRepository.findAll().stream().collect(Collectors.toMap(Role::getId, Function.identity()));
        Map<UUID, String> byUser = new HashMap<>();
        for (UserRole link : userRoleRepository.findAll()) {
            Role role = roles.get(link.getRoleId());
            if (role != null) {
                byUser.merge(link.getUserId(), role.getName(), (a, b) -> a.compareTo(b) <= 0 ? a : b);
            }
        }
        return byUser;
    }

    private String roleNameOf(UUID userId) {
        return userRoleRepository.findByUserId(userId).stream()
                .map(link -> roleRepository.findById(link.getRoleId()).map(Role::getName).orElse(""))
                .filter(name -> !name.isEmpty()).sorted().findFirst().orElse(null);
    }

    private List<LeaveResponse> toResponses(List<LeaveRequest> requests, LeaveActor actor) {
        Map<UUID, StaffProfile> profiles = profileRepository.findAll().stream()
                .collect(Collectors.toMap(StaffProfile::getUserId, Function.identity()));
        Map<UUID, User> users = userRepository.findAllById(requests.stream().map(LeaveRequest::getStaffUserId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        Map<UUID, Role> roles = roleRepository.findAll().stream().collect(Collectors.toMap(Role::getId, Function.identity()));
        Map<UUID, Role> roleByUser = new HashMap<>();
        for (UserRole link : userRoleRepository.findAll()) {
            Role role = roles.get(link.getRoleId());
            if (role != null) {
                roleByUser.merge(link.getUserId(), role, (a, b) -> a.getName().compareTo(b.getName()) <= 0 ? a : b);
            }
        }
        return requests.stream().map(r -> {
            StaffProfile profile = profiles.get(r.getStaffUserId());
            User user = users.get(r.getStaffUserId());
            Role role = roleByUser.get(r.getStaffUserId());
            String roleName = role == null ? null : role.getName();
            AttachmentInfo attachment = r.hasAttachment() ? new AttachmentInfo(r.getAttachmentOriginalFilename(),
                    r.getAttachmentContentType(), r.getAttachmentSizeBytes() == null ? 0 : r.getAttachmentSizeBytes()) : null;
            return new LeaveResponse(r.getId(), profile == null ? null : profile.getId(),
                    profile == null ? null : profile.getEmployeeCode(), user == null ? null : user.getFullName(),
                    role == null ? null : role.getId(), roleName, r.getLeaveTypeId(),
                    r.getLeaveType(), r.getHalfDay(), r.getStartDate(), r.getEndDate(), r.getDays(), r.getApplyDate(),
                    r.getReason(), r.getNote(), r.getStatus(), attachment, r.getDecidedAt(), r.getCreatedAt(),
                    Tier.of(roleName).approverLabel(), canDecide(actor, r.getStaffUserId(), roleName));
        }).toList();
    }

    private static ApiException notFound() {
        return new ApiException("Leave request not found", HttpStatus.NOT_FOUND);
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
}
