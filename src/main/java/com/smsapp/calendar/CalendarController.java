package com.smsapp.calendar;

import com.smsapp.calendar.CalendarService.EventInput;
import com.smsapp.calendar.CalendarService.EventRow;
import com.smsapp.user.Permissions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Annual Calendar: CALENDAR_VIEW reads entries and types; CALENDAR_MANAGE changes them. */
@RestController
@RequestMapping("/api/v1/calendar")
public class CalendarController {

    record TypeRequest(@NotBlank @Size(max = 100) String name) {
    }

    record TypeResponse(UUID id, String name) {
        static TypeResponse from(HolidayType type) {
            return new TypeResponse(type.getId(), type.getName());
        }
    }

    record EventRequest(
            @NotNull UUID typeId,
            @NotNull LocalDate fromDate,
            @NotNull LocalDate toDate,
            @NotBlank @Size(max = 1000) String description,
            boolean frontSite) {
        EventInput toInput() {
            return new EventInput(typeId, fromDate, toDate, description, frontSite);
        }
    }

    private final CalendarService service;

    public CalendarController(CalendarService service) {
        this.service = service;
    }

    @GetMapping("/types")
    @PreAuthorize(Permissions.HAS_CALENDAR_VIEW)
    List<TypeResponse> types() {
        return service.types().stream().map(TypeResponse::from).toList();
    }

    @PostMapping("/types")
    @PreAuthorize(Permissions.HAS_CALENDAR_MANAGE)
    ResponseEntity<TypeResponse> createType(@Valid @RequestBody TypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(TypeResponse.from(service.createType(request.name())));
    }

    @PutMapping("/types/{id}")
    @PreAuthorize(Permissions.HAS_CALENDAR_MANAGE)
    TypeResponse renameType(@PathVariable UUID id, @Valid @RequestBody TypeRequest request) {
        return TypeResponse.from(service.renameType(id, request.name()));
    }

    @DeleteMapping("/types/{id}")
    @PreAuthorize(Permissions.HAS_CALENDAR_MANAGE)
    ResponseEntity<Void> deleteType(@PathVariable UUID id) {
        service.deleteType(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/events")
    @PreAuthorize(Permissions.HAS_CALENDAR_VIEW)
    List<EventRow> events(@RequestParam(required = false) UUID typeId) {
        return service.events(typeId);
    }

    @PostMapping("/events")
    @PreAuthorize(Permissions.HAS_CALENDAR_MANAGE)
    ResponseEntity<EventRow> createEvent(@Valid @RequestBody EventRequest request, Authentication authentication) {
        UUID userId = UUID.fromString(((Jwt) authentication.getPrincipal()).getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createEvent(request.toInput(), userId));
    }

    @PutMapping("/events/{id}")
    @PreAuthorize(Permissions.HAS_CALENDAR_MANAGE)
    EventRow updateEvent(@PathVariable UUID id, @Valid @RequestBody EventRequest request) {
        return service.updateEvent(id, request.toInput());
    }

    @DeleteMapping("/events/{id}")
    @PreAuthorize(Permissions.HAS_CALENDAR_MANAGE)
    ResponseEntity<Void> deleteEvent(@PathVariable UUID id) {
        service.deleteEvent(id);
        return ResponseEntity.noContent().build();
    }
}
