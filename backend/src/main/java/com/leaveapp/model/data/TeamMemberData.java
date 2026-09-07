package com.leaveapp.model.data;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

// One row of GET /api/manager/team -- a direct report plus their leave balances,
// embedded so the roster+detail-modal screen needs no follow-up call.
@Getter
@Setter
public class TeamMemberData {

    /* Variables */

    private Long id;
    private String name;
    private String designation;
    private String department;
    private List<LeaveBalanceData> balances;
}
