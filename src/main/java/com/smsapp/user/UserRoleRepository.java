package com.smsapp.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Every write here happens right after the {@link User} and {@link Role} it links are created. */
public interface UserRoleRepository extends JpaRepository<UserRole, UserRole.Key> {

    List<UserRole> findByUserId(UUID userId);

    void deleteByUserId(UUID userId);
}
