package com.smsapp.frontoffice;

import com.smsapp.audit.AuditActions;
import com.smsapp.audit.AuditService;
import com.smsapp.common.ApiException;
import com.smsapp.frontoffice.FrontOfficeSetupDtos.DeleteResult;
import com.smsapp.frontoffice.FrontOfficeSetupDtos.SetupItem;
import com.smsapp.frontoffice.FrontOfficeSetupDtos.SetupItemRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Setup Front Office: add, rename/describe and delete entries in the four lookup lists
 * ({@link FrontOfficeSetupList}). Names are unique per list, ignoring case.
 *
 * <p>Deleting an entry that records still point at would either be refused by the database (purposes,
 * complaint types) or silently blank those records' field (sources, references). Instead such an entry is
 * <em>deactivated</em>: it disappears from the forms and from this list, while existing records keep showing
 * it. Adding the same name again later reactivates it. Entries nothing uses are deleted outright.
 */
@Service
public class FrontOfficeSetupService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService auditService;

    public FrontOfficeSetupService(NamedParameterJdbcTemplate jdbc, AuditService auditService) {
        this.jdbc = jdbc;
        this.auditService = auditService;
    }

    /** Active entries, alphabetical. */
    @Transactional(readOnly = true)
    public List<SetupItem> list(FrontOfficeSetupList list) {
        return jdbc.query("SELECT id, name, description FROM " + list.table + " WHERE active ORDER BY lower(name)",
                (rs, i) -> new SetupItem(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description")));
    }

    /**
     * Adds an entry, or reactivates a previously deleted one with the same name.
     *
     * @throws ApiException 409 if an active entry already has that name.
     */
    @Transactional
    public SetupItem create(FrontOfficeSetupList list, SetupItemRequest request) {
        String name = request.name().trim();
        String description = blankToNull(request.description());
        Optional<Row> existing = findByName(list, name);
        if (existing.isPresent() && existing.get().active()) {
            throw duplicate(list, name);
        }
        UUID id;
        if (existing.isPresent()) {
            id = existing.get().id();
            jdbc.update("UPDATE " + list.table + " SET active = true, name = :name, description = :description WHERE id = :id",
                    params(id).addValue("name", name).addValue("description", description));
        } else {
            id = UUID.randomUUID();
            try {
                jdbc.update("INSERT INTO " + list.table + " (id, name, description, active) VALUES (:id, :name, :description, true)",
                        params(id).addValue("name", name).addValue("description", description));
            } catch (DuplicateKeyException e) {
                throw duplicate(list, name); // a concurrent save with the same name won the race
            }
        }
        auditService.log(AuditActions.FRONT_OFFICE_SETUP_CREATED, AuditActions.FRONT_OFFICE_SETUP_ITEM, id,
                Map.of("list", list.path, "name", name, "reactivated", existing.isPresent()));
        return new SetupItem(id, name, description);
    }

    /** @throws ApiException 404 if no such active entry, 409 if another active entry has that name. */
    @Transactional
    public SetupItem update(FrontOfficeSetupList list, UUID id, SetupItemRequest request) {
        Row current = requireActive(list, id);
        String name = request.name().trim();
        String description = blankToNull(request.description());
        Optional<Row> clash = findByName(list, name).filter(r -> !r.id().equals(id));
        if (clash.isPresent() && clash.get().active()) {
            throw duplicate(list, name);
        }
        if (clash.isPresent()) {
            // A deleted-but-kept entry owns the name (older records still show it), so it can't be taken over.
            throw new ApiException(list.label + " '" + clash.get().name() + "' was deleted earlier -- add it again "
                    + "from the form to restore it", HttpStatus.CONFLICT);
        }
        try {
            jdbc.update("UPDATE " + list.table + " SET name = :name, description = :description WHERE id = :id",
                    params(id).addValue("name", name).addValue("description", description));
        } catch (DuplicateKeyException e) {
            throw duplicate(list, name);
        }
        auditService.log(AuditActions.FRONT_OFFICE_SETUP_UPDATED, AuditActions.FRONT_OFFICE_SETUP_ITEM, id,
                Map.of("list", list.path, "from", current.name(), "to", name));
        return new SetupItem(id, name, description);
    }

    /**
     * Deletes an unused entry, or deactivates one that records still use.
     *
     * @throws ApiException 404 if no such active entry.
     */
    @Transactional
    public DeleteResult delete(FrontOfficeSetupList list, UUID id) {
        Row current = requireActive(list, id);
        long usage = 0;
        for (String query : list.usageQueries) {
            Long count = jdbc.queryForObject(query, params(id), Long.class);
            usage += count == null ? 0 : count;
        }
        if (usage == 0) {
            jdbc.update("DELETE FROM " + list.table + " WHERE id = :id", params(id));
            auditService.log(AuditActions.FRONT_OFFICE_SETUP_DELETED, AuditActions.FRONT_OFFICE_SETUP_ITEM, id,
                    Map.of("list", list.path, "name", current.name()));
            return new DeleteResult(DeleteResult.DELETED, 0);
        }
        jdbc.update("UPDATE " + list.table + " SET active = false WHERE id = :id", params(id));
        auditService.log(AuditActions.FRONT_OFFICE_SETUP_DEACTIVATED, AuditActions.FRONT_OFFICE_SETUP_ITEM, id,
                Map.of("list", list.path, "name", current.name(), "usage", usage));
        return new DeleteResult(DeleteResult.DEACTIVATED, usage);
    }

    // --- Helpers ----------------------------------------------------------------------

    private record Row(UUID id, String name, boolean active) {
    }

    private Optional<Row> findByName(FrontOfficeSetupList list, String name) {
        return jdbc.query("SELECT id, name, active FROM " + list.table + " WHERE lower(name) = lower(:name) "
                                + "ORDER BY active DESC LIMIT 1",
                        new MapSqlParameterSource("name", name), this::row)
                .stream().findFirst();
    }

    private Row requireActive(FrontOfficeSetupList list, UUID id) {
        return jdbc.query("SELECT id, name, active FROM " + list.table + " WHERE id = :id AND active", params(id), this::row)
                .stream().findFirst()
                .orElseThrow(() -> new ApiException(list.label + " not found", HttpStatus.NOT_FOUND));
    }

    private Row row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("name"), rs.getBoolean("active"));
    }

    private static MapSqlParameterSource params(UUID id) {
        return new MapSqlParameterSource("id", id);
    }

    private static ApiException duplicate(FrontOfficeSetupList list, String name) {
        return new ApiException(list.label + " '" + name + "' already exists", HttpStatus.CONFLICT);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
