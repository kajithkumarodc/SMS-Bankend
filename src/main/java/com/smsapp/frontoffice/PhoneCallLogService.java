package com.smsapp.frontoffice;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.frontoffice.PhoneCallLogDtos.PhoneCallRequest;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Front Office / Phone Call Log. */
@Service
public class PhoneCallLogService {

    private final PhoneCallLogRepository repository;
    private final AuditService auditService;

    public PhoneCallLogService(PhoneCallLogRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    /** @throws ApiException 400 if the call type is unknown or the next follow-up is before the call date. */
    @Transactional
    public PhoneCallLog create(PhoneCallRequest request, UUID createdByUserId) {
        PhoneCallLog call = new PhoneCallLog();
        apply(call, request);
        call.setCreatedByUserId(createdByUserId);
        PhoneCallLog saved = repository.save(call);
        auditService.log(AuditActions.PHONE_CALL_CREATED, AuditActions.PHONE_CALL, saved.getId(),
                Map.of("phone", saved.getPhone(), "callType", saved.getCallType()));
        return saved;
    }

    /** Same rules as {@link #create}. @throws ApiException 404 if no such call. */
    @Transactional
    public PhoneCallLog update(UUID id, PhoneCallRequest request) {
        PhoneCallLog call = require(id);
        apply(call, request);
        PhoneCallLog saved = repository.save(call);
        auditService.log(AuditActions.PHONE_CALL_UPDATED, AuditActions.PHONE_CALL, id, Map.of("phone", saved.getPhone()));
        return saved;
    }

    /** @throws ApiException 404 if no such call. */
    @Transactional
    public void delete(UUID id) {
        PhoneCallLog call = require(id);
        repository.delete(call);
        auditService.log(AuditActions.PHONE_CALL_DELETED, AuditActions.PHONE_CALL, id, Map.of("phone", call.getPhone()));
    }

    @Transactional(readOnly = true)
    public PhoneCallLog get(UUID id) {
        return require(id);
    }

    /** Search, paginated. {@code query} matches name, phone, description or note. */
    @Transactional(readOnly = true)
    public Page<PhoneCallLog> search(String query, Pageable pageable) {
        Specification<PhoneCallLog> spec = (root, criteriaQuery, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (query != null && !query.isBlank()) {
                String like = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("phone")), like),
                        cb.like(cb.lower(root.get("description")), like),
                        cb.like(cb.lower(root.get("note")), like)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return repository.findAll(spec, pageable);
    }

    private static void apply(PhoneCallLog call, PhoneCallRequest request) {
        String type = CallType.normalizeOrNull(request.callType());
        if (type == null) {
            throw new ApiException("Call type must be INCOMING or OUTGOING", HttpStatus.BAD_REQUEST);
        }
        if (request.nextFollowUpDate() != null && request.nextFollowUpDate().isBefore(request.callDate())) {
            throw new ApiException("Next follow-up date can't be before the call date", HttpStatus.BAD_REQUEST);
        }
        call.setName(blankToNull(request.name()));
        call.setPhone(request.phone().trim());
        call.setCallDate(request.callDate());
        call.setDescription(blankToNull(request.description()));
        call.setNextFollowUpDate(request.nextFollowUpDate());
        call.setCallDuration(blankToNull(request.callDuration()));
        call.setNote(blankToNull(request.note()));
        call.setCallType(type);
    }

    private PhoneCallLog require(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ApiException("Phone call not found", HttpStatus.NOT_FOUND));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
