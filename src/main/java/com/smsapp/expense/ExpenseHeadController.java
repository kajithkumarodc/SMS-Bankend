package com.smsapp.expense;

import com.smsapp.expense.ExpenseDtos.ExpenseHeadRequest;
import com.smsapp.expense.ExpenseDtos.ExpenseHeadResponse;
import com.smsapp.user.Permissions;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Configurable expense heads. Read = EXPENSE_VIEW; adding or renaming one = EXPENSE_EDIT; deleting one = EXPENSE_DELETE. */
@RestController
@RequestMapping("/api/v1/expense-heads")
public class ExpenseHeadController {

    private final ExpenseHeadService expenseHeadService;

    public ExpenseHeadController(ExpenseHeadService expenseHeadService) {
        this.expenseHeadService = expenseHeadService;
    }

    @GetMapping
    @PreAuthorize(Permissions.HAS_EXPENSE_VIEW)
    List<ExpenseHeadResponse> list() {
        return expenseHeadService.listActive().stream().map(ExpenseHeadResponse::from).toList();
    }

    @PostMapping
    @PreAuthorize(Permissions.HAS_EXPENSE_EDIT)
    ResponseEntity<ExpenseHeadResponse> create(@Valid @RequestBody ExpenseHeadRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ExpenseHeadResponse.from(expenseHeadService.create(request.name(), request.description())));
    }

    @PutMapping("/{id}")
    @PreAuthorize(Permissions.HAS_EXPENSE_EDIT)
    ExpenseHeadResponse update(@PathVariable UUID id, @Valid @RequestBody ExpenseHeadRequest request) {
        return ExpenseHeadResponse.from(expenseHeadService.update(id, request.name(), request.description()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Permissions.HAS_EXPENSE_DELETE)
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        expenseHeadService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
