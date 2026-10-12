package com.leaveapp.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.leaveapp.pojo.NotificationPojo;

public interface NotificationRepo extends JpaRepository<NotificationPojo, Long> {

    @Transactional(readOnly = true)
    @Query("SELECT n FROM NotificationPojo n WHERE n.userId = :userId ORDER BY n.createdAt DESC")
    List<NotificationPojo> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    @Transactional(readOnly = true)
    @Query("SELECT n FROM NotificationPojo n WHERE n.userId = :userId AND n.read = false ORDER BY n.createdAt DESC")
    List<NotificationPojo> findByUserIdAndReadFalseOrderByCreatedAtDesc(@Param("userId") Long userId);

    // ownership-scoped lookup for mark-read -- a user can only ever touch their own rows
    @Transactional(readOnly = true)
    @Query("SELECT n FROM NotificationPojo n WHERE n.id = :id AND n.userId = :userId")
    Optional<NotificationPojo> findByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);
}
