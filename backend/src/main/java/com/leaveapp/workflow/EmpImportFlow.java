package com.leaveapp.workflow;

// import flow (layering: Flow -> Service -> Repo/DB):
// EmpImportFlow -> UserService     -> UserRepo     -> DB (per-row identity checks)
// EmpImportFlow -> HrEmpFlow  -> ...           -> DB (shared persistRow insert)
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.exception.BadRequestException;
import com.leaveapp.exception.ConflictException;
import com.leaveapp.model.data.EmpImportResultData;
import com.leaveapp.model.form.EmpImportRowForm;
import com.leaveapp.service.SubscriptionService;
import com.leaveapp.service.UserService;

// Bulk employee create from CSV/TSV rows the frontend already parsed to JSON. Split
// out of HrEmpFlow (which owns single create/update/deactivate) to keep both
// classes under the size limit; reuses HrEmpFlow.persistRow for the actual
// user+employee insert so there is exactly one place that writes those two rows.
@Service
public class EmpImportFlow {

    /* Variables */

    @Autowired
    private UserService userSvc;

    @Autowired
    private HrEmpFlow hrEmpFlow;

    @Autowired
    private SubscriptionService subscriptionSvc;

    @Value("${app.import.max-rows}")
    private int maxRows;

    /* Public Methods */

    // Phase 1 validates every row and collects skip reasons (HrImportSupport.resolveRow);
    // phase 2 inserts the survivors as INVITED users (each gets an invite mail). A
    // phase-2 failure (only reachable via a concurrent-insert race) rolls back the
    // whole batch -- every predictable rejection is already a per-row skip by then.
    @Transactional(rollbackFor = Exception.class)
    public EmpImportResultData importEmployees(Long orgId,
        List<EmpImportRowForm> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new BadRequestException("no rows provided");
        }
        if (rows.size() > maxRows) {
            throw new BadRequestException("no more than " + maxRows + " rows may be imported at once");
        }
        List<String> skipped = new ArrayList<>();
        List<PendingRow> pending = new ArrayList<>();
        Set<String> seenEmails = new HashSet<>();
        // Snapshot once, not re-read per row: a batch import fills up to this many seats
        // and skips the rest with a reason, rather than rejecting the whole request.
        int remainingSeats = subscriptionSvc.remainingSeats(orgId);
        int rowNum = 0;
        for (EmpImportRowForm row : rows) {
            rowNum++;
            String email = row.getEmail() == null ? "" : row.getEmail().trim();
            try {
                Long mgrId = HrImportSupport.resolveRow(userSvc, orgId, email, seenEmails, row);
                if (pending.size() >= remainingSeats) {
                    skipped.add(HrImportSupport.skipLine(rowNum, email,
                        "seat limit reached for your current plan"));
                    continue;
                }
                pending.add(new PendingRow(rowNum, email, mgrId, row));
            } catch (BadRequestException | ConflictException e) {
                // Only the app's own typed rejections become a per-row skip line; anything
                // else (NPE, a Hibernate constraint error, ...) is a real bug and must not
                // echo its raw message to the HR admin's browser -- let it propagate to
                // GlobalExceptionHandler's generic 500 instead.
                skipped.add(HrImportSupport.skipLine(rowNum, email, e.getMessage()));
            }
        }
        for (PendingRow p : pending) {
            // persistRow leaves the user INVITED and queues the invite mail -- no password here
            hrEmpFlow.persistRow(orgId, HrImportSupport.toEmpForm(p.row(), p.email(), p.managerId()));
        }
        EmpImportResultData result = new EmpImportResultData();
        result.setCreated(pending.size());
        result.setSkipped(skipped);
        return result;
    }


    // Phase-1 survivor awaiting phase-2 insert: its 1-based row index, trimmed
    // email, resolved managerId, and the original row for the rest of its fields.
    private record PendingRow(int rowNum, String email, Long managerId, EmpImportRowForm row) {
    }
}
