package com.smsapp.frontoffice;

import com.smsapp.common.ApiException;
import com.smsapp.frontoffice.PhoneCallLogDtos.PhoneCallRequest;
import com.smsapp.frontoffice.PhoneCallLogDtos.PhoneCallResponse;
import com.smsapp.user.Permissions;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Front Office / Phone Call Log, gated by the PHONE_CALL_* permissions seeded in V33. */
@RestController
@RequestMapping("/api/v1/phone-calls")
public class PhoneCallLogController {

    /** Sortable list columns: API sort key -> entity property. */
    private static final Map<String, String> SORTABLE = Map.of(
            "name", "name",
            "phone", "phone",
            "callDate", "callDate",
            "nextFollowUpDate", "nextFollowUpDate",
            "callType", "callType",
            "createdAt", "createdAt");

    private final PhoneCallLogService service;

    public PhoneCallLogController(PhoneCallLogService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize(Permissions.HAS_PHONE_CALL_CREATE)
    ResponseEntity<PhoneCallResponse> create(@Valid @RequestBody PhoneCallRequest request, Authentication authentication) {
        UUID userId = UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(PhoneCallResponse.from(service.create(request, userId)));
    }

    /**
     * Search/list, paginated. {@code q} matches name, phone, description or note. Sort keys are the
     * {@link #SORTABLE} names (400 otherwise); by default and as a tie-break, the latest call comes first.
     */
    @GetMapping
    @PreAuthorize(Permissions.HAS_PHONE_CALL_VIEW)
    PagedModel<PhoneCallResponse> list(@RequestParam(required = false) String q,
                                       @PageableDefault(size = 50) Pageable pageable) {
        return new PagedModel<>(service.search(q, withEntitySort(pageable)).map(PhoneCallResponse::from));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Permissions.HAS_PHONE_CALL_VIEW)
    PhoneCallResponse get(@PathVariable UUID id) {
        return PhoneCallResponse.from(service.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Permissions.HAS_PHONE_CALL_EDIT)
    PhoneCallResponse update(@PathVariable UUID id, @Valid @RequestBody PhoneCallRequest request) {
        return PhoneCallResponse.from(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Permissions.HAS_PHONE_CALL_DELETE)
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static Pageable withEntitySort(Pageable pageable) {
        Sort sort = Sort.unsorted();
        for (Sort.Order order : pageable.getSort()) {
            String property = SORTABLE.get(order.getProperty());
            if (property == null) {
                throw new ApiException("Can't sort phone calls by '" + order.getProperty() + "'", HttpStatus.BAD_REQUEST);
            }
            sort = sort.and(Sort.by(order.withProperty(property)));
        }
        for (String tieBreak : List.of("callDate", "createdAt")) {
            if (sort.getOrderFor(tieBreak) == null) {
                sort = sort.and(Sort.by(Sort.Direction.DESC, tieBreak));
            }
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
