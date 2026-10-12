package com.leaveapp.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.leaveapp.model.enums.EmpStatus;
import com.leaveapp.model.data.EmpRowData;
import com.leaveapp.model.enums.UserRole;
import com.leaveapp.model.enums.UserStatus;
import com.leaveapp.pojo.EmpPojo;
import com.leaveapp.pojo.EmpSensitivePojo;
import com.leaveapp.pojo.UserPojo;

// Plain unit test, no Spring context: the mapper is pure field copying.
public class HrEmpMapperTest {

    /* Public Methods */

    @Test
    public void toRow_copiesIdentityProfileAndPhone() {
        EmpSensitivePojo sens = new EmpSensitivePojo();
        sens.setPhone("+91 98765 43210");

        EmpRowData row = HrEmpMapper.toRow(aUser(), anEmp(), sens);

        assertEquals(7L, row.getId());
        assertEquals("w@acme.test", row.getEmail());
        assertTrue(row.isEmailVerified());
        assertEquals("HR", row.getRole());
        assertEquals("ON_NOTICE", row.getStatus());
        assertEquals("EMP-1", row.getEmployeeCode());
        assertEquals("Wanda", row.getName());
        assertEquals("+91 98765 43210", row.getPhone());
        assertEquals(3L, row.getManagerId());
        assertEquals("People", row.getDepartment());
        assertEquals("Lead", row.getDesignation());
        assertEquals("REGULAR", row.getEmployeeType());
        assertEquals(LocalDate.of(2020, 1, 1), row.getDateOfJoining());
        assertEquals(LocalDate.of(2030, 1, 1), row.getDateOfExit());
        assertEquals(0, new BigDecimal("10.5").compareTo(row.getBasicSalary()));
    }

    @Test
    public void toRow_nullSensitiveRow_leavesPhoneNull() {
        EmpRowData row = HrEmpMapper.toRow(aUser(), anEmp(), null);

        assertNull(row.getPhone());
        assertEquals("Wanda", row.getName());
    }

    @Test
    public void toRow_sensitiveRowWithoutPhone_leavesPhoneNull() {
        EmpRowData row = HrEmpMapper.toRow(aUser(), anEmp(), new EmpSensitivePojo());

        assertNull(row.getPhone());
    }

    /* Private Helper Methods */

    private static UserPojo aUser() {
        UserPojo u = new UserPojo();
        u.setId(7L);
        u.setEmail("w@acme.test");
        u.setEmailVerified(true);
        u.setRole(UserRole.HR);
        u.setStatus(UserStatus.ACTIVE);
        return u;
    }

    private static EmpPojo anEmp() {
        EmpPojo e = new EmpPojo();
        e.setEmployeeCode("EMP-1");
        e.setStatus(EmpStatus.ON_NOTICE);
        e.setName("Wanda");
        e.setManagerId(3L);
        e.setDepartment("People");
        e.setDesignation("Lead");
        e.setEmployeeType("REGULAR");
        e.setDateOfJoining(LocalDate.of(2020, 1, 1));
        e.setDateOfExit(LocalDate.of(2030, 1, 1));
        e.setBasicSalary(new BigDecimal("10.5"));
        return e;
    }
}
