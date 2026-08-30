package com.smsapp.tenant;

import jakarta.persistence.EntityManager;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TenantSessionAspect {

    private final EntityManager entityManager;

    public TenantSessionAspect(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Around("@annotation(transactional)")
    public Object setTenantSession(ProceedingJoinPoint joinPoint, Transactional transactional) throws Throwable {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId != null) {
            UUID.fromString(tenantId);
            entityManager.createNativeQuery("SELECT set_config('app.current_tenant_id', :tenantId, true)")
                    .setParameter("tenantId", tenantId)
                    .getSingleResult();
        }
        return joinPoint.proceed();
    }
}
