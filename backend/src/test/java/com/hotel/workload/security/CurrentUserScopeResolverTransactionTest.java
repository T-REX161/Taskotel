package com.hotel.workload.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

/**
 * The resolver's transaction boundary, checked on the real Spring bean. This test is deliberately NOT {@code @Transactional}: a test-managed
 * transaction would hide whether the resolver opens its own.
 *
 * <p>The resolver is annotated at class level, so BOTH public entry points ({@code resolve()} and {@code resolve(Authentication)}) are
 * intercepted by the transaction proxy. That matters because {@code resolve()} calls {@code resolve(Authentication)} on {@code this}, a
 * self-call the proxy does not see; an annotation on the inner method alone would leave the normal entry point without a transaction.
 */
@SpringBootTest
public class CurrentUserScopeResolverTransactionTest {

    @Autowired private CurrentUserScopeResolver resolver;
    @MockitoSpyBean private PlatformTransactionManager transactionManager;

    @BeforeEach
    public void signInAsAdmin() {
        // The Admin path reads no repository, so the only transaction opened is the resolver's own
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tx_admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        clearInvocations(transactionManager);
    }

    @AfterEach
    public void clear() {
        SecurityContextHolder.clearContext();
    }

    private TransactionDefinition openedTransaction() {
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture()); // exactly one transaction was requested
        return definition.getValue();
    }

    @Test
    @DisplayName("The resolver is a transactional Spring proxy with a read-only boundary at class level")
    public void classLevelReadOnlyBoundary() throws Exception {
        assertTrue(AopUtils.isAopProxy(resolver), "The resolver must be proxied for the transaction to apply");
        Transactional annotation = AnnotatedElementUtils.findMergedAnnotation(CurrentUserScopeResolver.class, Transactional.class);
        assertNotNull(annotation);
        assertTrue(annotation.readOnly());
        // The methods do not repeat the annotation: one boundary, declared once
        assertNull(CurrentUserScopeResolver.class.getMethod("resolve").getAnnotation(Transactional.class));
        assertNull(CurrentUserScopeResolver.class.getMethod("resolve", Authentication.class).getAnnotation(Transactional.class));
    }

    @Test
    @DisplayName("resolve(), the normal entry point, runs inside one read-only transaction")
    public void resolveWithoutArgumentsOpensAReadOnlyTransaction() {
        assertTrue(resolver.resolve().isAll());

        TransactionDefinition definition = openedTransaction();
        assertTrue(definition.isReadOnly(), "The transaction must be read-only");
        assertNotNull(definition.getName());
        assertTrue(definition.getName().endsWith("CurrentUserScopeResolver.resolve"), definition.getName());
    }

    @Test
    @DisplayName("resolve(Authentication) also runs inside one read-only transaction")
    public void resolveWithAuthenticationOpensAReadOnlyTransaction() {
        assertTrue(resolver.resolve(SecurityContextHolder.getContext().getAuthentication()).isAll());

        TransactionDefinition definition = openedTransaction();
        assertTrue(definition.isReadOnly());
        assertTrue(definition.getName().endsWith("CurrentUserScopeResolver.resolve"), definition.getName());
    }
}
