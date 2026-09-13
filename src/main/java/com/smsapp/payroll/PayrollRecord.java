package com.smsapp.payroll;

import com.smsapp.common.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One staff member's payroll record for one month/year. {@code netPay} is always
 * {@code baseSalary - deductions}, computed by {@link PayrollService} (also
 * enforced by a DB CHECK constraint in migration V17, defense in depth).
 * {@code (tenant_id, staff_user_id, month, year)} is unique.
 */
@Entity
@Table(name = "payroll_records")
@Getter
@Setter
@NoArgsConstructor
public class PayrollRecord extends TenantScopedEntity {

    @Column(name = "staff_user_id", nullable = false)
    private UUID staffUserId;

    @Column(nullable = false)
    private int month;

    @Column(nullable = false)
    private int year;

    @Column(name = "base_salary", nullable = false, precision = 12, scale = 2)
    private BigDecimal baseSalary;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal deductions;

    @Column(name = "net_pay", nullable = false, precision = 12, scale = 2)
    private BigDecimal netPay;

    @Column(nullable = false, length = 20)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;
}
