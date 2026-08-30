package com.smsapp.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID> {

    @Query("select role.name from Role role join UserRole userRole on userRole.roleId = role.id where userRole.userId = :userId")
    List<String> findNamesByUserId(UUID userId);
}
