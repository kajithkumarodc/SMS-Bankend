package com.smsapp.calendar;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.staff.StaffProfile;
import com.smsapp.staff.StaffProfileRepository;
import com.smsapp.user.User;
import com.smsapp.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Annual Calendar: the entries of the school year and the types they are filed under. Type names are unique ignoring
 * case; a type that entries use cannot be deleted.
 */
@Service
public class CalendarService {

    /** An entry as the calendar list shows it; {@code createdBy} is "Name (staff id)" when the creator is staff. */
    public record EventRow(UUID id, UUID typeId, String typeName, LocalDate fromDate, LocalDate toDate, String description,
                           boolean frontSite, String createdByName, String createdByCode) {
    }

    public record EventInput(UUID typeId, LocalDate fromDate, LocalDate toDate, String description, boolean frontSite) {
    }

    private final HolidayTypeRepository typeRepository;
    private final CalendarEventRepository eventRepository;
    private final UserRepository userRepository;
    private final StaffProfileRepository staffRepository;
    private final AuditService auditService;

    public CalendarService(HolidayTypeRepository typeRepository, CalendarEventRepository eventRepository, UserRepository userRepository,
                           StaffProfileRepository staffRepository, AuditService auditService) {
        this.typeRepository = typeRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.staffRepository = staffRepository;
        this.auditService = auditService;
    }

    // --- Types ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<HolidayType> types() {
        return typeRepository.findAllByOrderByName();
    }

    /** @throws ApiException 409 if a type with that name already exists. */
    @Transactional
    public HolidayType createType(String name) {
        String trimmed = name.trim();
        if (typeRepository.existsByNameIgnoreCase(trimmed)) {
            throw typeNameTaken(trimmed);
        }
        HolidayType type = new HolidayType();
        type.setName(trimmed);
        HolidayType saved = saveType(type, trimmed);
        auditService.log(AuditActions.HOLIDAY_TYPE_CREATED, AuditActions.HOLIDAY_TYPE, saved.getId(), Map.of("name", trimmed));
        return saved;
    }

    /** @throws ApiException 404 if no such type, 409 if another one has that name. */
    @Transactional
    public HolidayType renameType(UUID id, String name) {
        HolidayType type = requireType(id);
        String trimmed = name.trim();
        if (typeRepository.existsByNameIgnoreCaseAndIdNot(trimmed, id)) {
            throw typeNameTaken(trimmed);
        }
        type.setName(trimmed);
        HolidayType saved = saveType(type, trimmed);
        auditService.log(AuditActions.HOLIDAY_TYPE_UPDATED, AuditActions.HOLIDAY_TYPE, id, Map.of("name", trimmed));
        return saved;
    }

    /** @throws ApiException 404 if no such type, 409 if calendar entries use it. */
    @Transactional
    public void deleteType(UUID id) {
        HolidayType type = requireType(id);
        if (eventRepository.existsByHolidayTypeId(id)) {
            throw new ApiException("'" + type.getName() + "' is used by calendar entries and can't be deleted", HttpStatus.CONFLICT);
        }
        typeRepository.delete(type);
        auditService.log(AuditActions.HOLIDAY_TYPE_DELETED, AuditActions.HOLIDAY_TYPE, id, Map.of("name", type.getName()));
    }

    // --- Entries --------------------------------------------------------------------------------

    /** The entries, latest first; {@code typeId} narrows to one type. */
    @Transactional(readOnly = true)
    public List<EventRow> events(UUID typeId) {
        return toRows(typeId == null ? eventRepository.findAllByOrderByFromDateDescCreatedAtDesc()
                : eventRepository.findByHolidayTypeIdOrderByFromDateDescCreatedAtDesc(typeId));
    }

    /** @throws ApiException 404 if the type doesn't exist, 400 if the end date is before the start date. */
    @Transactional
    public EventRow createEvent(EventInput input, UUID creatorUserId) {
        validate(input);
        CalendarEvent event = new CalendarEvent();
        apply(event, input);
        event.setCreatedByUserId(creatorUserId);
        CalendarEvent saved = eventRepository.save(event);
        auditService.log(AuditActions.CALENDAR_EVENT_CREATED, AuditActions.CALENDAR_EVENT, saved.getId(), Map.of("from", saved.getFromDate().toString()));
        return toRows(List.of(saved)).get(0);
    }

    /** @throws ApiException 404 if no such entry or type, 400 if the end date is before the start date. */
    @Transactional
    public EventRow updateEvent(UUID id, EventInput input) {
        CalendarEvent event = requireEvent(id);
        validate(input);
        apply(event, input);
        CalendarEvent saved = eventRepository.save(event);
        auditService.log(AuditActions.CALENDAR_EVENT_UPDATED, AuditActions.CALENDAR_EVENT, id, Map.of("from", saved.getFromDate().toString()));
        return toRows(List.of(saved)).get(0);
    }

    /** @throws ApiException 404 if no such entry. */
    @Transactional
    public void deleteEvent(UUID id) {
        CalendarEvent event = requireEvent(id);
        eventRepository.delete(event);
        auditService.log(AuditActions.CALENDAR_EVENT_DELETED, AuditActions.CALENDAR_EVENT, id, Map.of("from", event.getFromDate().toString()));
    }

    // --- Helpers --------------------------------------------------------------------------------

    private void validate(EventInput input) {
        requireType(input.typeId());
        if (input.toDate().isBefore(input.fromDate())) {
            throw new ApiException("The To Date can't be before the From Date", HttpStatus.BAD_REQUEST);
        }
    }

    private static void apply(CalendarEvent event, EventInput input) {
        event.setHolidayTypeId(input.typeId());
        event.setFromDate(input.fromDate());
        event.setToDate(input.toDate());
        event.setDescription(input.description().trim());
        event.setFrontSite(input.frontSite());
    }

    private HolidayType requireType(UUID id) {
        return typeRepository.findById(id).orElseThrow(() -> new ApiException("Holiday type not found", HttpStatus.NOT_FOUND));
    }

    private CalendarEvent requireEvent(UUID id) {
        return eventRepository.findById(id).orElseThrow(() -> new ApiException("Calendar entry not found", HttpStatus.NOT_FOUND));
    }

    private HolidayType saveType(HolidayType type, String name) {
        try {
            return typeRepository.saveAndFlush(type);
        } catch (DataIntegrityViolationException ex) {
            throw typeNameTaken(name);
        }
    }

    private List<EventRow> toRows(List<CalendarEvent> events) {
        Map<UUID, String> typeNames = typeRepository.findAll().stream().collect(Collectors.toMap(HolidayType::getId, HolidayType::getName));
        Map<UUID, User> users = userRepository.findAllById(events.stream().map(CalendarEvent::getCreatedByUserId).filter(u -> u != null).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        Map<UUID, String> codes = staffRepository.findAll().stream()
                .collect(Collectors.toMap(StaffProfile::getUserId, StaffProfile::getEmployeeCode, (a, b) -> a));
        return events.stream().map(e -> {
            User creator = e.getCreatedByUserId() == null ? null : users.get(e.getCreatedByUserId());
            return new EventRow(e.getId(), e.getHolidayTypeId(), typeNames.get(e.getHolidayTypeId()), e.getFromDate(), e.getToDate(),
                    e.getDescription(), e.isFrontSite(), creator == null ? null : creator.getFullName(),
                    creator == null ? null : codes.get(creator.getId()));
        }).toList();
    }

    private static ApiException typeNameTaken(String name) {
        return new ApiException("A holiday type named '" + name + "' already exists", HttpStatus.CONFLICT);
    }
}
