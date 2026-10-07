package com.smsapp.payroll;

import com.smsapp.common.UuidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** One named earning or deduction line of a payroll record (V46). */
@Entity
@Table(name = "payroll_items")
@Getter
@Setter
@NoArgsConstructor
public class PayrollItem extends UuidEntity {

    public static final String EARNING = "EARNING";
    public static final String DEDUCTION = "DEDUCTION";

    @Column(name = "payroll_record_id", nullable = false)
    private UUID payrollRecordId;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false, length = 100)
    private String type;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** Order the lines were entered in. */
    @Column(nullable = false)
    private int position;
}
