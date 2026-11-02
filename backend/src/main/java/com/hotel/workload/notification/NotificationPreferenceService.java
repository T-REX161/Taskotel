package com.hotel.workload.notification;

import com.hotel.workload.notification.NotificationPreferenceDtos.PreferenceChange;
import com.hotel.workload.notification.NotificationPreferenceDtos.PreferenceDto;
import com.hotel.workload.notification.NotificationPreferenceDtos.PreferencesResponse;
import com.hotel.workload.notification.NotificationPreferenceDtos.UpdateRequest;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A user's own notification preferences. The account is always the signed-in one (the caller passes the name from the security
 * context); no request can name another account. Categories a user has not changed show their defaults, so every user, new or
 * existing, has a complete set without any rows being created for them.
 */
@Service
public class NotificationPreferenceService {

    static final String SECURITY_LOCKED_MESSAGE = "Security alerts are required and cannot be turned off.";
    static final String INVALID_REQUEST_MESSAGE = "Send the categories to change, each with its email setting.";

    private final NotificationPreferenceRepository repository;
    private final UserRepository userRepository;
    private final EntityManager entityManager;

    public NotificationPreferenceService(NotificationPreferenceRepository repository, UserRepository userRepository, EntityManager entityManager) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public PreferencesResponse get(String username) {
        return respond(currentUser(username));
    }

    /**
     * Saves the listed categories for the signed-in user and returns the complete, current set. The whole request is checked first,
     * so a refused change (unknown or repeated category, missing value, security alerts switched off) changes nothing.
     */
    @Transactional
    public PreferencesResponse update(String username, UpdateRequest request) {
        User user = currentUser(username);
        Map<NotificationCategory, PreferenceChange> changes = validate(request);

        // One save at a time per user, so two first-time saves cannot both try to create the same row
        entityManager.find(User.class, user.getId(), LockModeType.PESSIMISTIC_WRITE);

        Map<NotificationCategory, NotificationPreference> existing = new EnumMap<>(NotificationCategory.class);
        for (NotificationPreference row : repository.findByUserId(user.getId())) {
            NotificationCategory category = categoryOf(row.getCategory());
            if (category != null) existing.put(category, row);
        }
        for (Map.Entry<NotificationCategory, PreferenceChange> entry : changes.entrySet()) {
            NotificationCategory category = entry.getKey();
            boolean email = entry.getValue().email();
            NotificationPreference row = existing.get(category);
            if (row == null) {
                // The in-app column is NOT NULL and unused: new rows just carry the category's default
                repository.save(new NotificationPreference(user.getId(), category, category.defaultInApp(), email));
            } else {
                row.setEmail(email);
                repository.save(row);
            }
        }
        repository.flush();
        return respond(user);
    }

    private Map<NotificationCategory, PreferenceChange> validate(UpdateRequest request) {
        if (request == null || request.preferences() == null || request.preferences().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_REQUEST_MESSAGE);
        }
        Map<NotificationCategory, PreferenceChange> changes = new EnumMap<>(NotificationCategory.class);
        for (PreferenceChange change : request.preferences()) {
            NotificationCategory category = change == null ? null : categoryOf(change.category());
            if (category == null || change.email() == null || changes.containsKey(category)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_REQUEST_MESSAGE);
            }
            if (category.isMandatory() && !change.email()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, SECURITY_LOCKED_MESSAGE);
            }
            changes.put(category, change);
        }
        return changes;
    }

    private PreferencesResponse respond(User user) {
        Map<NotificationCategory, NotificationPreference> saved = new EnumMap<>(NotificationCategory.class);
        for (NotificationPreference row : repository.findByUserId(user.getId())) {
            NotificationCategory category = categoryOf(row.getCategory());
            if (category != null) saved.put(category, row);
        }
        List<PreferenceDto> result = new ArrayList<>();
        for (NotificationCategory category : EnumSet.allOf(NotificationCategory.class)) {
            NotificationPreference row = saved.get(category);
            // A mandatory category is always on, whatever is stored
            boolean email = category.isMandatory() || (row == null ? category.defaultEmail() : row.isEmail());
            result.add(new PreferenceDto(category.name(), email, category.isMandatory()));
        }
        return new PreferencesResponse(result);
    }

    private static NotificationCategory categoryOf(String name) {
        if (name == null) return null;
        for (NotificationCategory category : NotificationCategory.values()) {
            if (category.name().equals(name)) return category;
        }
        return null;
    }

    private User currentUser(String username) {
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in");
        }
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in"));
    }
}
