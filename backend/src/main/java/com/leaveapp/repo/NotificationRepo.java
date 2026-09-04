package com.leaveapp.repo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.NotificationPojo;

@Transactional
public interface NotificationRepo extends JpaRepository<NotificationPojo, Long> {

    List<NotificationPojo> findByUserIdAndReadFalse(Long userId);
}
