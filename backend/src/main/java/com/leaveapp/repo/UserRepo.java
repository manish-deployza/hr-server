package com.leaveapp.repo;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.UserPojo;
import com.leaveapp.model.enums.UserRole;

public interface UserRepo extends JpaRepository<UserPojo, Long> {

    @Transactional(readOnly = true)
    @Query("SELECT u FROM UserPojo u WHERE u.orgId = :orgId AND u.email = :email")
    Optional<UserPojo> findByOrgIdAndEmail(@Param("orgId") Long orgId, @Param("email") String email);

    @Transactional(readOnly = true)
    @Query("SELECT COUNT(u) > 0 FROM UserPojo u WHERE u.orgId = :orgId AND u.email = :email")
    boolean existsByOrgIdAndEmail(@Param("orgId") Long orgId, @Param("email") String email);

    @Transactional(readOnly = true)
    @Query("SELECT COUNT(u) > 0 FROM UserPojo u WHERE u.id = :id AND u.orgId = :orgId")
    boolean existsByIdAndOrgId(@Param("id") Long id, @Param("orgId") Long orgId);

    @Transactional(readOnly = true)
    @Query("SELECT u FROM UserPojo u WHERE u.orgId = :orgId")
    List<UserPojo> findByOrgId(@Param("orgId") Long orgId);

    @Transactional(readOnly = true)
    @Query("SELECT u FROM UserPojo u WHERE u.orgId = :orgId AND u.role IN :roles")
    List<UserPojo> findByOrgIdAndRoleIn(@Param("orgId") Long orgId, @Param("roles") Collection<UserRole> roles);

    // SubscriptionService's seat-cap check/display -- non-DISABLED (i.e. not exited) headcount only.
    @Transactional(readOnly = true)
    @Query("SELECT COUNT(u) FROM UserPojo u WHERE u.orgId = :orgId AND u.status <> com.leaveapp.model.enums.UserStatus.DISABLED")
    long countActiveByOrgId(@Param("orgId") Long orgId);
}
