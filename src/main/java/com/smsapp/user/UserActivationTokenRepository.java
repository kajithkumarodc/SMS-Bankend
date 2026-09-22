package com.smsapp.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserActivationTokenRepository extends JpaRepository<UserActivationToken, UUID> {

    Optional<UserActivationToken> findByToken(String token);
}
