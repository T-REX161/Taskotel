package com.hotel.workload.profile;

import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.staff.StaffRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The order of storage and database steps, with every collaborator mocked (no Spring context, no Cloudinary). */
public class ProfilePictureServiceTest {

    private static final String OLD_ID = "taskotel/profile-pictures/user_old";
    private static final String OLD_URL = "https://res.cloudinary.com/demo/image/upload/f_auto,q_auto/v1/" + OLD_ID;
    private static final ProfileImageStorage.StoredImage NEW =
            new ProfileImageStorage.StoredImage("https://res.cloudinary.com/demo/image/upload/f_auto,q_auto/v2/new", "taskotel/profile-pictures/user_new");

    private UserRepository userRepository;
    private ProfileImageStorage storage;
    private EntityManager entityManager;
    private PlatformTransactionManager transactionManager;
    private ProfilePictureService service;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        StaffRepository staffRepository = mock(StaffRepository.class);
        storage = mock(ProfileImageStorage.class);
        entityManager = mock(EntityManager.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new ProfilePictureService(userRepository, staffRepository, storage, entityManager, transactionManager);

        user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("someone");
        user.setRole(Role.MANAGER);
        when(userRepository.findByUsername("someone")).thenReturn(Optional.of(user));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(entityManager.find(User.class, user.getId())).thenReturn(user);
        when(staffRepository.findByUserUsername(anyString())).thenReturn(Optional.empty());
        when(storage.isConfigured()).thenReturn(true);
    }

    private void withPicture() {
        user.setProfileImageUrl(OLD_URL);
        user.setProfileImagePublicId(OLD_ID);
    }

    @Test
    @DisplayName("Replace: upload the new image, commit it, and only then delete the old one")
    void replaceDeletesTheOldImageOnlyAfterTheCommit() {
        withPicture();
        when(storage.upload(any(), anyString())).thenReturn(NEW);

        ProfileDto result = service.uploadPicture("someone", TestImages.jpegFile());

        assertEquals(NEW.url(), result.profileImageUrl());
        assertEquals(NEW.publicId(), user.getProfileImagePublicId());
        InOrder order = inOrder(storage, entityManager, transactionManager);
        order.verify(storage).upload(any(), anyString());
        order.verify(entityManager).refresh(user, LockModeType.PESSIMISTIC_WRITE); // re-read under a row lock
        order.verify(transactionManager).commit(any());
        order.verify(storage).delete(OLD_ID);
    }

    @Test
    @DisplayName("The public id sent to Cloudinary is new for every upload and names the account, never another one")
    void newPublicIdPerUpload() {
        when(storage.upload(any(), anyString())).thenReturn(NEW);
        org.mockito.ArgumentCaptor<String> hint = org.mockito.ArgumentCaptor.forClass(String.class);

        service.uploadPicture("someone", TestImages.jpegFile());
        service.uploadPicture("someone", TestImages.jpegFile());

        verify(storage, org.mockito.Mockito.times(2)).upload(any(), hint.capture());
        assertTrue(hint.getAllValues().get(0).startsWith("user_" + user.getId() + "_"));
        assertTrue(!hint.getAllValues().get(0).equals(hint.getAllValues().get(1)));
    }

    @Test
    @DisplayName("A failed upload changes nothing: 502, no transaction, the old picture is neither replaced nor deleted")
    void failedUploadKeepsTheOldPicture() {
        withPicture();
        when(storage.upload(any(), anyString())).thenThrow(new ProfileImageStorageException(ProfileImageStorageException.Reason.UNAVAILABLE, "down"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.uploadPicture("someone", TestImages.jpegFile()));

        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatusCode());
        assertEquals(ProfilePictureService.UPLOAD_UNAVAILABLE_MESSAGE, e.getReason());
        assertEquals(OLD_ID, user.getProfileImagePublicId());
        assertEquals(OLD_URL, user.getProfileImageUrl());
        verify(transactionManager, never()).getTransaction(any());
        verify(storage, never()).delete(anyString());
    }

    @Test
    @DisplayName("An image Cloudinary refuses is a 400 with a plain message")
    void rejectedImage() {
        when(storage.upload(any(), anyString())).thenThrow(new ProfileImageStorageException(ProfileImageStorageException.Reason.REJECTED, "Invalid image file"));
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.uploadPicture("someone", TestImages.jpegFile()));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertEquals(ProfilePictureService.REJECTED_MESSAGE, e.getReason());
    }

    @Test
    @DisplayName("If the database cannot be updated, the new image is deleted again and the old one is kept")
    void failedSaveRemovesTheNewUpload() {
        withPicture();
        when(storage.upload(any(), anyString())).thenReturn(NEW);
        doThrow(new TransactionSystemException("database unavailable")).when(transactionManager).commit(any());

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.uploadPicture("someone", TestImages.jpegFile()));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatusCode());
        assertEquals(ProfilePictureService.SAVE_FAILED_MESSAGE, e.getReason());
        verify(storage).delete(NEW.publicId());
        verify(storage, never()).delete(OLD_ID);
    }

    @Test
    @DisplayName("If the old image cannot be deleted after a successful replacement, the upload still succeeds")
    void oldImageCleanupFailureDoesNotFailTheUpload() {
        withPicture();
        when(storage.upload(any(), anyString())).thenReturn(NEW);
        doThrow(new ProfileImageStorageException(ProfileImageStorageException.Reason.UNAVAILABLE, "down")).when(storage).delete(OLD_ID);

        assertEquals(NEW.url(), service.uploadPicture("someone", TestImages.jpegFile()).profileImageUrl());
    }

    @Test
    @DisplayName("Without credentials the upload is a 503 and nothing is sent; an invalid file never reaches Cloudinary")
    void notConfiguredOrInvalid() {
        when(storage.isConfigured()).thenReturn(false);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.uploadPicture("someone", TestImages.jpegFile()));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatusCode());

        when(storage.isConfigured()).thenReturn(true);
        assertThrows(ResponseStatusException.class, () -> service.uploadPicture("someone", TestImages.file("a.gif", "image/gif", TestImages.png())));
        verify(storage, never()).upload(any(), anyString());
    }

    @Test
    @DisplayName("Remove: delete in Cloudinary first, then clear the account")
    void removeDeletesThenClears() {
        withPicture();

        ProfileDto result = service.removePicture("someone");

        assertNull(result.profileImageUrl());
        assertNull(user.getProfileImagePublicId());
        InOrder order = inOrder(storage, transactionManager);
        order.verify(storage).delete(OLD_ID);
        order.verify(transactionManager).commit(any());
    }

    @Test
    @DisplayName("If Cloudinary cannot delete the picture, nothing is cleared: 502 and the picture is still shown")
    void failedRemoveKeepsThePicture() {
        withPicture();
        doThrow(new ProfileImageStorageException(ProfileImageStorageException.Reason.UNAVAILABLE, "down")).when(storage).delete(OLD_ID);

        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> service.removePicture("someone"));

        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatusCode());
        assertEquals(ProfilePictureService.REMOVE_UNAVAILABLE_MESSAGE, e.getReason());
        assertEquals(OLD_ID, user.getProfileImagePublicId());
        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    @DisplayName("Removing when there is no picture does nothing")
    void removeWithoutPicture() {
        assertNull(service.removePicture("someone").profileImageUrl());
        verify(storage, never()).delete(anyString());
        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    @DisplayName("A picture uploaded while the old one was being removed is kept")
    void removeKeepsAPictureUploadedMeanwhile() {
        withPicture();
        // The row, re-read under the lock, already points at a newer picture
        doAnswerRefresh(() -> {
            user.setProfileImagePublicId(NEW.publicId());
            user.setProfileImageUrl(NEW.url());
        });

        service.removePicture("someone");

        verify(storage).delete(OLD_ID);
        assertEquals(NEW.publicId(), user.getProfileImagePublicId());
        assertEquals(NEW.url(), user.getProfileImageUrl());
    }

    @Test
    @DisplayName("Without a signed-in name there is no profile")
    void noName() {
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class, () -> service.getProfile(null)).getStatusCode());
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class, () -> service.getProfile("nobody")).getStatusCode());
    }

    private void doAnswerRefresh(Runnable change) {
        org.mockito.Mockito.doAnswer(invocation -> {
            change.run();
            return null;
        }).when(entityManager).refresh(eq(user), eq(LockModeType.PESSIMISTIC_WRITE));
    }
}
