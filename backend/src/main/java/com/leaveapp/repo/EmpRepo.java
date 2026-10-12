package com.leaveapp.repo;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.EmpPojo;

// The Long id is the user id (EmpPojo.userId) -- employee is 1:1 with user.
public interface EmpRepo extends JpaRepository<EmpPojo, Long> {

    @Transactional(readOnly = true)
    @Query("SELECT COUNT(e) > 0 FROM EmpPojo e WHERE e.managerId = :managerId")
    boolean existsByManagerId(@Param("managerId") Long managerId);

    @Transactional(readOnly = true)
    @Query("SELECT e FROM EmpPojo e WHERE e.orgId = :orgId")
    List<EmpPojo> findByOrgId(@Param("orgId") Long orgId);

    // a manager's direct reports -- backed by ix_emp_mgr
    @Transactional(readOnly = true)
    @Query("SELECT e FROM EmpPojo e WHERE e.managerId = :managerId")
    List<EmpPojo> findByManagerId(@Param("managerId") Long managerId);

    // DirectoryFlow.list's ORG/REPORTS scopes: filtering, exited-status exclusion, and
    // pagination all pushed to the DB (Pageable's Sort supplies ORDER BY) -- unlike
    // findByOrgId/findByManagerId above, this never loads a whole org/team into memory.
    @Transactional(readOnly = true)
    @Query("""
        SELECT e FROM EmpPojo e
        WHERE e.orgId = :orgId
          AND e.status <> com.leaveapp.model.enums.EmpStatus.EXITED
          AND (:managerId IS NULL OR e.managerId = :managerId)
          AND (:department IS NULL OR LOWER(e.department) = LOWER(:department))
          AND (:search IS NULL OR (e.name IS NOT NULL AND LOWER(e.name) LIKE LOWER(CONCAT('%', :search, '%'))))
        """)
    Page<EmpPojo> searchDirectory(@Param("orgId") Long orgId, @Param("managerId") Long managerId,
        @Param("department") String department, @Param("search") String search, Pageable pageable);
}
