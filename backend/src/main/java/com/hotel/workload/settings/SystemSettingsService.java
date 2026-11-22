package com.hotel.workload.settings;

import com.hotel.workload.security.CurrentUserScopeResolver;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * System-wide settings, Admin only. The Admin check is made here, from the signed-in account ({@link CurrentUserScopeResolver}), so it
 * holds however this service is reached; the URL rule in SecurityConfig is a second layer.
 *
 * <p>Storage: one row (see {@link SystemSettings}). Reading with no row returns the defaults and writes nothing. The first successful
 * update creates the row, later ones change it. Two first updates at the same moment cannot make two rows (the unique key refuses the
 * second); the loser simply repeats its save, which then updates the row the winner created.
 */
@Service
public class SystemSettingsService {

    static final String INVALID_THRESHOLD_MESSAGE = "capacityThresholdPercent must be a whole number from 1 to 100";
    static final String MISSING_VALUES_MESSAGE =
            "All four settings are required: autoBalancingEnabled, capacityAlertEnabled, crossDepartmentAssistanceEnabled and capacityThresholdPercent";

    private final SystemSettingsRepository repository;
    private final UserRepository userRepository;
    private final CurrentUserScopeResolver scopeResolver;
    private final TransactionTemplate transactions;

    public SystemSettingsService(SystemSettingsRepository repository, UserRepository userRepository, CurrentUserScopeResolver scopeResolver,
                                 PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.scopeResolver = scopeResolver;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** The stored settings, or the defaults when nothing has been saved yet. */
    public SystemSettingsDto get() {
        scopeResolver.requireAdmin();
        return repository.findBySettingsKey(SystemSettings.GLOBAL_KEY).map(SystemSettingsDto::of).orElseGet(SystemSettingsDto::defaults);
    }

    /**
     * The settings in force, for the application's own behaviour (support, recommendations, dashboard alerts). Internal: no controller exposes
     * it and it makes no permission decision, because the callers have already resolved who is asking and never return these values to a
     * non-Admin. Same source as {@link #get()}: the stored row, or the defaults while nothing has been saved. Writes nothing.
     */
    public SystemSettingsDto current() {
        return repository.findBySettingsKey(SystemSettings.GLOBAL_KEY).map(SystemSettingsDto::of).orElseGet(SystemSettingsDto::defaults);
    }

    /** Saves all four settings and returns them as stored. */
    public SystemSettingsDto update(UpdateSystemSettingsRequest request) {
        scopeResolver.requireAdmin();
        int threshold = validate(request);
        User admin = userRepository.findByUsername(scopeResolver.currentUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found"));
        try {
            return save(request, threshold, admin);
        } catch (DataIntegrityViolationException firstSaveRace) {
            // Another Admin created the row between our look-up and our insert: it exists now, so this time it is an update
            return save(request, threshold, admin);
        }
    }

    private SystemSettingsDto save(UpdateSystemSettingsRequest request, int threshold, User admin) {
        return transactions.execute(status -> {
            SystemSettings row = repository.lockBySettingsKey(SystemSettings.GLOBAL_KEY).orElseGet(SystemSettings::new);
            row.setAutoBalancingEnabled(request.getAutoBalancingEnabled());
            row.setCapacityAlertEnabled(request.getCapacityAlertEnabled());
            row.setCrossDepartmentAssistanceEnabled(request.getCrossDepartmentAssistanceEnabled());
            row.setCapacityThresholdPercent(threshold);
            row.setUpdatedAt(LocalDateTime.now());
            row.setUpdatedByUserId(admin.getId());
            row.setUpdatedByUsername(admin.getUsername());
            return SystemSettingsDto.of(repository.saveAndFlush(row));
        });
    }

    /** Checked here too (not only by the controller's validation), so a direct call cannot store a bad value. Returns the threshold as an int. */
    private static int validate(UpdateSystemSettingsRequest request) {
        if (request == null || request.getAutoBalancingEnabled() == null || request.getCapacityAlertEnabled() == null
                || request.getCrossDepartmentAssistanceEnabled() == null || request.getCapacityThresholdPercent() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, MISSING_VALUES_MESSAGE);
        }
        BigDecimal value = request.getCapacityThresholdPercent();
        boolean whole = value.stripTrailingZeros().scale() <= 0;
        if (!whole || value.compareTo(BigDecimal.ONE) < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_THRESHOLD_MESSAGE);
        }
        return value.intValueExact();
    }
}
