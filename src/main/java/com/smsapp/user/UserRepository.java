package com.smsapp.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByTenantIdAndEmail(UUID tenantId, String email);

    long countByTenantId(UUID tenantId);

    boolean existsByIdAndTenantId(UUID id, UUID tenantId);

    List<User> findByTenantIdOrderByFullName(UUID tenantId);
}
