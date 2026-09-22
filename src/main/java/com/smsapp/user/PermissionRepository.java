package com.smsapp.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PermissionRepository extends JpaRepository<Permission, java.util.UUID> {

    List<Permission> findAllByOrderByName();

    Optional<Permission> findByName(String name);

    boolean existsByName(String name);
}
