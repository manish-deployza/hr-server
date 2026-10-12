package com.leaveapp.pojo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.leaveapp.model.enums.LedgerEntryType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// append-only, no mutable balance column anywhere -- balance is always
// SUM(amountDays) WHERE userId=? AND leaveTypeId=? AND leaveYear=?
// see prompts/features/database-schema.md#leave_ledger_entry
@Entity
@Table(
    name = "leave_ledger_entry",
    indexes = @Index(name = "idx_leave_ledger_entry_user_type_year", columnList = "user_id, leave_type_id, leave_year")
)
@Getter
@Setter
@NoArgsConstructor
public class LeaveLedgerEntryPojo {

    /* Variables */

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Id
    @GeneratedValue(strategy = GenerationType.TABLE, generator = "leave_ledger_gen")
    @TableGenerator(
        name = "leave_ledger_gen",
        table = "id_gen",
        pkColumnName = "gen_name",
        valueColumnName = "gen_val",
        pkColumnValue = "leave_ledger_entry_id",
        initialValue = 1,
        allocationSize = 100
    )
    private Long id;

    // the user this ledger entry belongs to
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "leave_type_id", nullable = false)
    private Long leaveTypeId;

    @Column(name = "leave_year", nullable = false)
    private int leaveYear;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 32)
    private LedgerEntryType entryType;

    // signed: positive for credit, negative for debit -- BigDecimal used for Half Days/
    // Fractional Days. precision=4 (max 999.9 days) -- see LeaveTypePojo.maxCarryDays.
    @Column(name = "amount_days", nullable = false, precision = 4, scale = 1)
    private BigDecimal amountDays;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    // set for DEBIT entries only. Java field shortened from referenceLeaveRequestId;
    // column name unchanged, no migration.
    @Column(name = "reference_leave_request_id", nullable = true)
    private Long refRequestId;

    // id of the user whose action wrote this entry (the applicant or the approver)
    @Column(name = "created_by", nullable = false)
    private Long createdBy;
}
