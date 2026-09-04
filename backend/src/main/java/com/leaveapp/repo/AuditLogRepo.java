package com.leaveapp.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.AuditLogPojo;

@Transactional
public interface AuditLogRepo extends JpaRepository<AuditLogPojo, Long> {
}
