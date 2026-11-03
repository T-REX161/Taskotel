package com.hotel.workload.profile;

import com.hotel.workload.security.User;
import com.hotel.workload.security.UserDto;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.Staff;
import com.hotel.workload.staff.StaffRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * A user's own profile picture. The account is always the signed-in one (the caller passes the name from the security
 * context); no request can name another account.
 *
 * <p>Replacing is ordered so the current picture survives any failure: the new image is uploaded under a new public id, then
 * the database is pointed at it, and only after that commit is the old image deleted. If the database step fails, the new
 * image is deleted again and the old one stays. Removing deletes the image in Cloudinary first and only then clears the
 * database, so a picture is never shown as removed while it is still publicly stored.
 */
@Service
public class ProfilePictureService {

    private static final Logger log = LoggerFactory.getLogger(ProfilePictureService.class);

    static final String NOT_CONFIGURED_MESSAGE = "Profile pictures are not available right now. Please try again later.";
    static final String REJECTED_MESSAGE = "This image could not be processed. Please choose a different JPG, PNG or WebP image.";
    static final String UPLOAD_UNAVAILABLE_MESSAGE = "The image service is not responding. Your current picture was kept; please try again.";
    static final String SAVE_FAILED_MESSAGE = "Your new picture could not be saved. Your current picture was kept; please try again.";
    static final String REMOVE_UNAVAILABLE_MESSAGE = "The image service is not responding. Your picture was not removed; please try again.";

    private final UserRepository userRepository;
    private final StaffRepository staffRepository;
    private final ProfileImageStorage storage;
    private final EntityManager entityManager;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();

    public ProfilePictureService(UserRepository userRepository,
                                 StaffRepository staffRepository,
                                 ProfileImageStorage storage,
                                 EntityManager entityManager,
                                 PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.staffRepository = staffRepository;
        this.storage = storage;
        this.entityManager = entityManager;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ProfileDto getProfile(String username) {
        return toDto(currentUser(username));
    }

    /** Sets the signed-in user's own display name (the account comes from the token; nothing else on the account changes). */
    public ProfileDto updateDisplayName(String username, String requested) {
        String displayName = DisplayNames.validate(requested);
        User user = currentUser(username);
        if (userRepository.updateDisplayName(user.getId(), displayName) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        }
        return toDto(userRepository.findById(user.getId()).orElse(user));
    }

    /**
     * Saves the signed-in user's own phone number (the account comes from the token). It is kept on the account, so Admins and Managers
     * without a staff record have one too, and copied to the linked staff record when there is one. The number is not verified.
     */
    public ProfileDto updatePhone(String username, String requested) {
        String phone = PhoneNumbers.validate(requested);
        User user = currentUser(username);
        transactions.executeWithoutResult(status -> {
            User locked = lockLatest(user.getId());
            locked.setPhone(phone);
            staffRepository.findByUserUsername(locked.getUsername()).ifPresent(staff -> staff.setPhone(phone));
        });
        return toDto(userRepository.findById(user.getId()).orElse(user));
    }

    /** Saves the signed-in user's own appearance, date format and time format (the account comes from the token). */
    public ProfileDto updatePreferences(String username, UserPreferences.Values requested) {
        UserPreferences.Values values = UserPreferences.validate(requested);
        User user = currentUser(username);
        if (userRepository.updatePreferences(user.getId(), values.themePreference(), values.dateFormat(), values.timeFormat()) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found");
        }
        return toDto(userRepository.findById(user.getId()).orElse(user));
    }

    public ProfileDto uploadPicture(String username, MultipartFile file) {
        ProfileImageValidator.ValidatedImage image = ProfileImageValidator.validate(file);
        User user = currentUser(username);
        if (!storage.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED_MESSAGE);
        }

        ProfileImageStorage.StoredImage stored;
        try {
            stored = storage.upload(image.content(), newPublicIdHint(user.getId()));
        } catch (ProfileImageStorageException e) {
            throw toResponse(e, UPLOAD_UNAVAILABLE_MESSAGE); // nothing was changed: the current picture stays
        }

        String previousPublicId;
        try {
            previousPublicId = transactions.execute(status -> {
                User locked = lockLatest(user.getId());
                String previous = locked.getProfileImagePublicId();
                locked.setProfileImageUrl(stored.url());
                locked.setProfileImagePublicId(stored.publicId());
                return previous;
            });
        } catch (RuntimeException e) {
            log.warn("Profile picture for user {} could not be saved; removing the new upload again: {}", user.getId(), e.toString());
            deleteQuietly(stored.publicId());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, SAVE_FAILED_MESSAGE);
        }

        // The new picture is committed; the old image is no longer referenced anywhere
        if (previousPublicId != null && !previousPublicId.equals(stored.publicId())) {
            deleteQuietly(previousPublicId);
        }
        log.info("Profile picture updated for user {}", user.getId());
        return toDto(userRepository.findById(user.getId()).orElse(user));
    }

    public ProfileDto removePicture(String username) {
        User user = currentUser(username);
        String publicId = user.getProfileImagePublicId();
        if (publicId == null && user.getProfileImageUrl() == null) {
            return toDto(user); // nothing to remove
        }

        if (publicId != null) {
            if (!storage.isConfigured()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED_MESSAGE);
            }
            try {
                storage.delete(publicId);
            } catch (ProfileImageStorageException e) {
                throw toResponse(e, REMOVE_UNAVAILABLE_MESSAGE); // the database still shows the picture, which still exists
            }
        }

        transactions.executeWithoutResult(status -> {
            User locked = lockLatest(user.getId());
            // Clear only the picture that was just deleted: one uploaded meanwhile is kept
            if (Objects.equals(locked.getProfileImagePublicId(), publicId)) {
                locked.setProfileImageUrl(null);
                locked.setProfileImagePublicId(null);
            }
        });
        log.info("Profile picture removed for user {}", user.getId());
        return toDto(userRepository.findById(user.getId()).orElse(user));
    }

    /**
     * The account row, locked until the surrounding transaction ends and re-read from the database: the copy loaded earlier in
     * the request may be stale, and a concurrent picture change must be seen before deciding which image is the old one.
     */
    private User lockLatest(UUID userId) {
        User user = entityManager.find(User.class, userId);
        if (user == null) {
            throw new IllegalStateException("The account no longer exists");
        }
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        return user;
    }

    private User currentUser(String username) {
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in");
        }
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in"));
    }

    private ProfileDto toDto(User user) {
        java.util.Optional<Staff> staffRecord = staffRepository.findByUserUsername(user.getUsername());
        String fullName = staffRecord
                .map(Staff::getName)
                .filter(name -> !name.isBlank())
                .orElseGet(() -> UserDto.deriveFallbackName(user));
        // The staff record's number (which administrators also edit), else the one saved on the account; never invented
        String phone = staffRecord.map(Staff::getPhone).map(String::trim).filter(number -> !number.isEmpty())
                .or(() -> java.util.Optional.ofNullable(user.getPhone()).map(String::trim).filter(number -> !number.isEmpty()))
                .orElse(null);
        return new ProfileDto(user.getUsername(), user.getRole() == null ? null : user.getRole().name(), fullName, user.getProfileImageUrl(),
                DisplayNames.effective(user), user.isActive(), user.isEmailVerified(),
                user.getCreatedAt(), user.getLastLoginAt(), user.getPasswordChangedAt(), UserPreferences.of(user), user.getEmail(), phone);
    }

    /** A new name for every upload, so a replacement never overwrites the picture that is still in use. */
    private String newPublicIdHint(UUID userId) {
        byte[] suffix = new byte[6];
        random.nextBytes(suffix);
        return "user_" + userId + "_" + HexFormat.of().formatHex(suffix);
    }

    private void deleteQuietly(String publicId) {
        try {
            storage.delete(publicId);
        } catch (RuntimeException e) {
            // Left over in Cloudinary only; the account already points at the right picture
            log.warn("Could not delete unused profile image {} from Cloudinary: {}", publicId, e.getMessage());
        }
    }

    private static ResponseStatusException toResponse(ProfileImageStorageException e, String unavailableMessage) {
        return switch (e.getReason()) {
            case NOT_CONFIGURED -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED_MESSAGE);
            case REJECTED -> new ResponseStatusException(HttpStatus.BAD_REQUEST, REJECTED_MESSAGE);
            case UNAVAILABLE -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, unavailableMessage);
        };
    }
}
