package com.smsapp.staff;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.staff.LeaveDtos.CreateLeaveRequestRequest;
import com.smsapp.staff.StaffDtos.CreateStaffProfileRequest;
import com.smsapp.staff.StaffDtos.UpdateStaffProfileRequest;
import com.smsapp.user.RoleRepository;
import com.smsapp.user.User;
import com.smsapp.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaffServiceTest {

    @Mock
    private StaffProfileRepository staffProfileRepository;

    @Mock
    private LeaveRequestRepository leaveRequestRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private AuditService auditService;

    private StaffService service() {
        return new StaffService(staffProfileRepository, leaveRequestRepository, userRepository, roleRepository,
                auditService);
    }

    private final UUID userId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();

    private StaffProfile profile() {
        StaffProfile profile = new StaffProfile();
        profile.setId(profileId);
        profile.setUserId(userId);
        profile.setEmployeeCode("EMP-001");
        profile.setDateOfJoining(LocalDate.of(2020, 1, 1));
        profile.setSalaryAmount(new BigDecimal("50000.00"));
        profile.setStatus(StaffProfileStatus.ACTIVE);
        return profile;
    }

    // --- create profile -----------------------------------------

    @Test
    void createProfileStoresTheFieldsAndAudits() {
        when(userRepository.existsById(userId)).thenReturn(true);
        when(staffProfileRepository.existsByUserId(userId)).thenReturn(false);
        when(staffProfileRepository.existsByEmployeeCode("EMP-001")).thenReturn(false);
        when(staffProfileRepository.saveAndFlush(any(StaffProfile.class))).thenAnswer(inv -> {
            StaffProfile p = inv.getArgument(0);
            p.setId(profileId);
            return p;
        });

        StaffProfile created = service().createProfile(new CreateStaffProfileRequest(
                userId, "  EMP-001 ", "  Academics ", " Teacher ", LocalDate.of(2020, 1, 1),
                new BigDecimal("50000.00")));

        assertThat(created.getUserId()).isEqualTo(userId);
        assertThat(created.getEmployeeCode()).isEqualTo("EMP-001");
        assertThat(created.getDepartment()).isEqualTo("Academics");
        assertThat(created.getDesignation()).isEqualTo("Teacher");
        assertThat(created.getStatus()).isEqualTo(StaffProfileStatus.ACTIVE);
        verify(auditService).log(eq(AuditActions.STAFF_PROFILE_CREATED), eq(AuditActions.STAFF_PROFILE),
                eq(profileId), anyMap());
    }

    @Test
    void createProfileWithANonexistentUserReturns404() {
        when(userRepository.existsById(userId)).thenReturn(false);

        assertThatThrownBy(() -> service().createProfile(new CreateStaffProfileRequest(
                userId, "EMP-001", null, null, LocalDate.of(2020, 1, 1), BigDecimal.TEN)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);

        verify(staffProfileRepository, never()).saveAndFlush(any());
    }

    @Test
    void createProfileForAUserWhoAlreadyHasOneReturns409() {
        when(userRepository.existsById(userId)).thenReturn(true);
        when(staffProfileRepository.existsByUserId(userId)).thenReturn(true);

        assertThatThrownBy(() -> service().createProfile(new CreateStaffProfileRequest(
                userId, "EMP-001", null, null, LocalDate.of(2020, 1, 1), BigDecimal.TEN)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(staffProfileRepository, never()).saveAndFlush(any());
    }

    @Test
    void createProfileWithADuplicateEmployeeCodeReturns409() {
        when(userRepository.existsById(userId)).thenReturn(true);
        when(staffProfileRepository.existsByUserId(userId)).thenReturn(false);
        when(staffProfileRepository.existsByEmployeeCode("EMP-001")).thenReturn(true);

        assertThatThrownBy(() -> service().createProfile(new CreateStaffProfileRequest(
                userId, "EMP-001", null, null, LocalDate.of(2020, 1, 1), BigDecimal.TEN)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);

        verify(staffProfileRepository, never()).saveAndFlush(any());
    }

    @Test
    void createProfileTranslatesAConcurrentInsertRaceIntoAClean409() {
        when(userRepository.existsById(userId)).thenReturn(true);
        when(staffProfileRepository.existsByUserId(userId)).thenReturn(false);
        when(staffProfileRepository.existsByEmployeeCode("EMP-001")).thenReturn(false);
        when(staffProfileRepository.saveAndFlush(any(StaffProfile.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service().createProfile(new CreateStaffProfileRequest(
                userId, "EMP-001", null, null, LocalDate.of(2020, 1, 1), BigDecimal.TEN)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.CONFLICT);
    }

    // --- response enrichment (join with the owning user) ----------

    @Test
    void toResponseJoinsTheOwningUsersEmailAndFullName() {
        User user = new User();
        user.setId(userId);
        user.setEmail("teacher@demo.edu");
        user.setFullName("Priya Teacher");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var response = service().toResponse(profile());

        assertThat(response.email()).isEqualTo("teacher@demo.edu");
        assertThat(response.fullName()).isEqualTo("Priya Teacher");
        assertThat(response.employeeCode()).isEqualTo("EMP-001");
    }

    @Test
    void listProfilesWithNamesJoinsEachProfileWithItsOwningUser() {
        User user = new User();
        user.setId(userId);
        user.setEmail("teacher@demo.edu");
        user.setFullName("Priya Teacher");
        when(staffProfileRepository.findAllByOrderByEmployeeCode()).thenReturn(List.of(profile()));
        when(userRepository.findAllById(List.of(userId))).thenReturn(List.of(user));

        var responses = service().listProfilesWithNames();

        assertThat(responses).singleElement().satisfies(r -> {
            assertThat(r.userId()).isEqualTo(userId);
            assertThat(r.fullName()).isEqualTo("Priya Teacher");
        });
    }

    // --- update profile ------------------------------------------

    @Test
    void updateProfileChangesEditableFieldsAndAudits() {
        when(staffProfileRepository.findById(profileId)).thenReturn(Optional.of(profile()));
        when(staffProfileRepository.save(any(StaffProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        StaffProfile saved = service().updateProfile(profileId, new UpdateStaffProfileRequest(
                "Science", "Senior Teacher", LocalDate.of(2021, 6, 1), new BigDecimal("60000.00"), "INACTIVE"));

        assertThat(saved.getDepartment()).isEqualTo("Science");
        assertThat(saved.getDesignation()).isEqualTo("Senior Teacher");
        assertThat(saved.getSalaryAmount()).isEqualByComparingTo("60000.00");
        assertThat(saved.getStatus()).isEqualTo("INACTIVE");
        // employeeCode is untouched -- not part of the update request.
        assertThat(saved.getEmployeeCode()).isEqualTo("EMP-001");
        verify(auditService).log(eq(AuditActions.STAFF_PROFILE_UPDATED), eq(AuditActions.STAFF_PROFILE),
                eq(profileId), anyMap());
    }

    @Test
    void updateProfileWithAnInvalidStatusReturns400() {
        when(staffProfileRepository.findById(profileId)).thenReturn(Optional.of(profile()));

        assertThatThrownBy(() -> service().updateProfile(profileId, new UpdateStaffProfileRequest(
                null, null, LocalDate.of(2020, 1, 1), BigDecimal.TEN, "RETIRED")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(staffProfileRepository, never()).save(any());
    }

    @Test
    void updateProfileNotFoundReturns404() {
        when(staffProfileRepository.findById(profileId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateProfile(profileId, new UpdateStaffProfileRequest(
                null, null, LocalDate.of(2020, 1, 1), BigDecimal.TEN, "ACTIVE")))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- leave requests -------------------------------------------

    @Test
    void createLeaveRequestByTheStaffMemberThemselvesSucceeds() {
        when(staffProfileRepository.findById(profileId)).thenReturn(Optional.of(profile()));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> {
            LeaveRequest r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        LeaveRequest created = service().createLeaveRequest(profileId, userId,
                new CreateLeaveRequestRequest("SICK", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 3), "Flu"));

        assertThat(created.getStaffUserId()).isEqualTo(userId);
        assertThat(created.getStatus()).isEqualTo(LeaveRequestStatus.PENDING);
        verify(auditService).log(eq(AuditActions.LEAVE_REQUEST_CREATED), eq(AuditActions.LEAVE_REQUEST),
                any(), anyMap());
    }

    @Test
    void createLeaveRequestByAnAdminOnBehalfOfTheStaffMemberSucceeds() {
        when(staffProfileRepository.findById(profileId)).thenReturn(Optional.of(profile()));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        // actingUserId == null means "admin, unrestricted".
        LeaveRequest created = service().createLeaveRequest(profileId, null,
                new CreateLeaveRequestRequest("CASUAL", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 1), null));

        assertThat(created.getStaffUserId()).isEqualTo(userId);
    }

    @Test
    void createLeaveRequestForSomeoneElsesProfileReturns403() {
        when(staffProfileRepository.findById(profileId)).thenReturn(Optional.of(profile()));
        UUID someoneElse = UUID.randomUUID();

        assertThatThrownBy(() -> service().createLeaveRequest(profileId, someoneElse,
                new CreateLeaveRequestRequest("SICK", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 3), null)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.FORBIDDEN);

        verify(leaveRequestRepository, never()).save(any());
    }

    @Test
    void createLeaveRequestWithEndDateBeforeStartDateReturns400() {
        when(staffProfileRepository.findById(profileId)).thenReturn(Optional.of(profile()));

        assertThatThrownBy(() -> service().createLeaveRequest(profileId, userId,
                new CreateLeaveRequestRequest("SICK", LocalDate.of(2026, 3, 3), LocalDate.of(2026, 3, 1), null)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(leaveRequestRepository, never()).save(any());
    }

    @Test
    void createLeaveRequestWithANonexistentProfileReturns404() {
        when(staffProfileRepository.findById(profileId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().createLeaveRequest(profileId, userId,
                new CreateLeaveRequestRequest("SICK", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 3), null)))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void decideLeaveRequestApprovesAndAudits() {
        UUID leaveId = UUID.randomUUID();
        LeaveRequest existing = new LeaveRequest();
        existing.setId(leaveId);
        existing.setStatus(LeaveRequestStatus.PENDING);
        when(leaveRequestRepository.findById(leaveId)).thenReturn(Optional.of(existing));
        when(leaveRequestRepository.save(any(LeaveRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        LeaveRequest saved = service().decideLeaveRequest(leaveId, "approved");

        assertThat(saved.getStatus()).isEqualTo(LeaveRequestStatus.APPROVED);
        verify(auditService).log(eq(AuditActions.LEAVE_REQUEST_DECIDED), eq(AuditActions.LEAVE_REQUEST),
                eq(leaveId), anyMap());
    }

    @Test
    void decideLeaveRequestWithAnInvalidStatusReturns400() {
        UUID leaveId = UUID.randomUUID();

        assertThatThrownBy(() -> service().decideLeaveRequest(leaveId, "PENDING"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);

        verify(leaveRequestRepository, never()).findById(any());
    }

    @Test
    void decideLeaveRequestNotFoundReturns404() {
        UUID leaveId = UUID.randomUUID();
        when(leaveRequestRepository.findById(leaveId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().decideLeaveRequest(leaveId, "REJECTED"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void ownLeaveRequestsDelegatesToTheRepository() {
        when(leaveRequestRepository.findByStaffUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        service().ownLeaveRequests(userId);

        verify(leaveRequestRepository).findByStaffUserIdOrderByCreatedAtDesc(userId);
    }

    @Test
    void listLeaveRequestsWithNoFiltersListsAll() {
        when(leaveRequestRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());

        service().listLeaveRequests(null, null);

        verify(leaveRequestRepository).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void listLeaveRequestsFiltersByStatusOnly() {
        when(leaveRequestRepository.findByStatusOrderByCreatedAtDesc("PENDING")).thenReturn(List.of());

        service().listLeaveRequests(null, "pending");

        verify(leaveRequestRepository).findByStatusOrderByCreatedAtDesc("PENDING");
    }

    @Test
    void listLeaveRequestsFiltersByStaffMemberOnly() {
        when(leaveRequestRepository.findByStaffUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        service().listLeaveRequests(userId, null);

        verify(leaveRequestRepository).findByStaffUserIdOrderByCreatedAtDesc(userId);
    }

    @Test
    void listLeaveRequestsFiltersByBothStaffMemberAndStatus() {
        when(leaveRequestRepository.findByStaffUserIdAndStatusOrderByCreatedAtDesc(userId, "APPROVED"))
                .thenReturn(List.of());

        service().listLeaveRequests(userId, "APPROVED");

        verify(leaveRequestRepository).findByStaffUserIdAndStatusOrderByCreatedAtDesc(userId, "APPROVED");
    }

    @Test
    void listLeaveRequestsWithAnInvalidStatusReturns400() {
        assertThatThrownBy(() -> service().listLeaveRequests(null, "CANCELLED"))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- eligible users ---------------------------------------------

    @Test
    void eligibleUsersExcludesUsersWhoAlreadyHaveAProfile() {
        User already = new User();
        already.setId(userId);
        already.setEmail("already@demo.edu");
        already.setFullName("Already Staff");
        User free = new User();
        UUID freeId = UUID.randomUUID();
        free.setId(freeId);
        free.setEmail("free@demo.edu");
        free.setFullName("Free User");

        when(staffProfileRepository.findAllUserIds()).thenReturn(List.of(userId));
        when(userRepository.findAllByOrderByFullName()).thenReturn(List.of(already, free));
        when(roleRepository.findNamesByUserId(freeId)).thenReturn(List.of("TEACHER"));

        List<StaffDtos.EligibleUserResponse> eligible = service().eligibleUsers();

        assertThat(eligible).singleElement().satisfies(u -> {
            assertThat(u.id()).isEqualTo(freeId);
            assertThat(u.email()).isEqualTo("free@demo.edu");
            assertThat(u.roles()).containsExactly("TEACHER");
        });
    }

    // --- own profile --------------------------------------------------

    @Test
    void ownProfileReturnsTheCallersProfile() {
        when(staffProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile()));

        StaffProfile found = service().ownProfile(userId);

        assertThat(found.getId()).isEqualTo(profileId);
    }

    @Test
    void ownProfileWithNoLinkedProfileReturns404() {
        when(staffProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().ownProfile(userId))
                .isInstanceOf(ApiException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }
}
