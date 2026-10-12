package com.leaveapp.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.leaveapp.pojo.AuditLogPojo;

public interface AuditLogRepo extends JpaRepository<AuditLogPojo, Long> {
}
