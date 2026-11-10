package com.hotel.workload.staff;

import com.hotel.workload.security.Role;
import com.hotel.workload.security.User;
import com.hotel.workload.security.UserRepository;
import com.hotel.workload.verification.EmailSendingException;
import com.hotel.workload.verification.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Stage 3: an Admin, or a Manager for their own department, starts a verified login email change for a Staff account
 * (POST /api/staff/{id}/email-change). Over HTTP through the real security chain, services, BCrypt and the in-memory database; the email
 * service is mocked, so the tests prove what the application asks it to send and what is stored. The Staff member alone completes the change,
 * with the existing signed-in confirmation (POST /api/profile/email-change/confirm). Every test uses its own client address, so the shared
 * in-memory rate limiters never interfere. Commits for real (no wrapping transaction) so the concurrency test is meaningful.
 */
@SpringBootTest
public class StaffLoginEmailChangeRequestTest {

    private static final String PREFIX = "sle_";
    private static final String CONFIRM = "/api/profile/email-change/confirm";
    private static final AtomicInteger CLIENTS = new AtomicInteger();
    private static final AtomicInteger ACCOUNTS = new AtomicInteger();

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private EmailService emailService;

    private MockMvc mockMvc;
    private User admin;
    private User manager;
    private User holder;
    private Staff holderStaff;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        reset(emailService);
        when(emailService.isConfigured()).thenReturn(true);
        cleanUp();
        admin = account("admin", Role.ADMIN, "admin");
        manager = account("manager", Role.MANAGER, "manager");
        withStaffRecord(manager, TestDepartments.HOUSEKEEPING);
        holder = account("holder", Role.STAFF, "holder");
        holderStaff = withStaffRecord(holder, TestDepartments.HOUSEKEEPING);
    }

    @AfterEach
    void cleanUp() {
        staffRepository.findAllWithUser().stream().filter(s -> s.getUser().getUsername().startsWith(PREFIX)).forEach(staffRepository::delete);
        userRepository.findAll().stream().filter(u -> u.getUsername().startsWith(PREFIX)).forEach(userRepository::delete);
    }

    // ── fixtures and helpers ─────────────────────────────────────────────────────────────────────

    private User account(String name, Role role, String label) {
        User user = new User();
        user.setUsername(PREFIX + name + "_" + ACCOUNTS.incrementAndGet()); // unique per account, so the shared in-memory limiters (keyed by user name) never carry over between tests
        user.setEmail(PREFIX + label + "@old.test");
        user.setPassword(passwordEncoder.encode("Correct@123"));
        user.setRole(role);
        user.setActive(true);
        user.setEmailVerified(true);
        user.setCredentialsSetupRequired(false);
        return userRepository.saveAndFlush(user);
    }

    private Staff withStaffRecord(User user, Department department) {
        Staff staff = new Staff();
        staff.setUser(user);
        staff.setName("Name " + user.getUsername());
        staff.setEmail(user.getEmail());
        staff.setDepartment(department);
        staff.setAvailability(Availability.AVAILABLE);
        return staffRepository.saveAndFlush(staff);
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    private static String address() {
        int n = CLIENTS.incrementAndGet();
        return "10.30." + (n / 250) + "." + (n % 250);
    }

    private MvcResult initiate(User caller, Staff target, String newEmail) throws Exception {
        return initiate(caller, target, newEmail, address());
    }

    private MvcResult initiate(User caller, Staff target, String newEmail, String clientAddress) throws Exception {
        return mockMvc.perform(post("/api/staff/" + target.getId() + "/email-change").contentType(MediaType.APPLICATION_JSON)
                .content("{\"newEmail\":\"" + newEmail + "\"}")
                .with(user(caller.getUsername()).roles(caller.getRole().name())).with(r -> { r.setRemoteAddr(clientAddress); return r; })).andReturn();
    }

    private MvcResult confirm(User as, String code) throws Exception {
        return confirm(as, code, address());
    }

    private MvcResult confirm(User as, String code, String clientAddress) throws Exception {
        return mockMvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}")
                .with(user(as.getUsername()).roles(as.getRole().name())).with(r -> { r.setRemoteAddr(clientAddress); return r; })).andReturn();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    /** The code the application asked the email service to send to this new address most recently. */
    private String sentCode(String toNewEmail) {
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendAdministratorEmailChangeVerificationEmail(to.capture(), code.capture());
        int last = to.getAllValues().size() - 1;
        assertEquals(toNewEmail, to.getAllValues().get(last), "the code goes to the NEW address");
        return code.getAllValues().get(last);
    }

    private void assertNothingPending(User user) {
        User stored = reload(user);
        assertNull(stored.getPendingEmail());
        assertNull(stored.getPendingEmailCodeHash());
    }

    private static String newAddress(String label) {
        return PREFIX + label + "@new.test";
    }

    // ── RBAC and department isolation ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Admin starts a change for a Staff account: code to the NEW address, notice to the CURRENT one, active email untouched until verified")
    void adminInitiates() throws Exception {
        String fresh = newAddress("admin1");
        MvcResult result = initiate(admin, holderStaff, fresh);
        assertEquals(200, result.getResponse().getStatus(), body(result));
        assertTrue(body(result).contains("\"pendingEmailMasked\":\"sl***@new.test\""), body(result));
        assertFalse(body(result).contains(fresh), "the full address is not echoed");

        String code = sentCode(fresh);
        assertTrue(code.matches("\\d{6}"));
        assertFalse(body(result).contains(code), "the code is never in the response");
        verify(emailService).sendAdministratorEmailChangeNotice(eq(holder.getEmail()), anyString());

        User stored = reload(holder);
        assertEquals(holder.getEmail(), stored.getEmail(), "the active login email is unchanged");
        assertEquals(holder.getEmail(), staffRepository.findById(holderStaff.getId()).orElseThrow().getEmail(), "so is the staff record");
        assertEquals(fresh, stored.getPendingEmail());
        assertNotEquals(code, stored.getPendingEmailCodeHash(), "only a hash of the code is stored");
    }

    @Test
    @DisplayName("A Manager starts a change for Staff in their own department")
    void managerInitiatesInOwnDepartment() throws Exception {
        MvcResult result = initiate(manager, holderStaff, newAddress("mgr1"));
        assertEquals(200, result.getResponse().getStatus(), body(result));
        assertEquals(holder.getEmail(), reload(holder).getEmail());
        assertEquals(newAddress("mgr1"), reload(holder).getPendingEmail());
    }

    @Test
    @DisplayName("A Manager cannot start a change for Staff in another department: 404 as if it did not exist, nothing sent, nothing stored")
    void managerOutsideDepartmentIsRefused() throws Exception {
        User other = account("other", Role.STAFF, "other");
        Staff otherStaff = withStaffRecord(other, TestDepartments.MAINTENANCE);
        MvcResult result = initiate(manager, otherStaff, newAddress("x1"));
        assertEquals(404, result.getResponse().getStatus());
        verify(emailService, never()).sendAdministratorEmailChangeVerificationEmail(anyString(), anyString());
        verify(emailService, never()).sendAdministratorEmailChangeNotice(anyString(), anyString());
        assertNothingPending(other);
    }

    @Test
    @DisplayName("Only Staff accounts: a Manager cannot start a change for a Manager or Admin account (403), and neither can an Admin")
    void onlyStaffAccountsAreEligible() throws Exception {
        User otherManager = account("manager2", Role.MANAGER, "manager2");
        Staff otherManagerStaff = withStaffRecord(otherManager, TestDepartments.HOUSEKEEPING);
        User adminAccount = account("admin2", Role.ADMIN, "admin2");
        Staff adminStaff = withStaffRecord(adminAccount, TestDepartments.HOUSEKEEPING);
        assertEquals(403, initiate(manager, otherManagerStaff, newAddress("y1")).getResponse().getStatus());
        assertEquals(403, initiate(manager, adminStaff, newAddress("y2")).getResponse().getStatus());
        assertEquals(403, initiate(admin, otherManagerStaff, newAddress("y3")).getResponse().getStatus());
        assertEquals(403, initiate(admin, adminStaff, newAddress("y4")).getResponse().getStatus());
        verify(emailService, never()).sendAdministratorEmailChangeVerificationEmail(anyString(), anyString());
        assertNothingPending(otherManager);
        assertNothingPending(adminAccount);
    }

    @Test
    @DisplayName("A Staff member cannot use the staff management endpoint, for anyone (403); no sign-in is 401; an unknown id is 404")
    void staffAndAnonymousAreRefused() throws Exception {
        User peer = account("peer", Role.STAFF, "peer");
        withStaffRecord(peer, TestDepartments.HOUSEKEEPING);
        assertEquals(403, initiate(peer, holderStaff, newAddress("z1")).getResponse().getStatus());
        assertEquals(403, initiate(holder, holderStaff, newAddress("z2")).getResponse().getStatus(), "not even for themselves");
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        assertEquals(401, mockMvc.perform(post("/api/staff/" + holderStaff.getId() + "/email-change").contentType(MediaType.APPLICATION_JSON)
                .content("{\"newEmail\":\"" + newAddress("z3") + "\"}")).andReturn().getResponse().getStatus());
        Staff ghost = new Staff();
        ghost.setId(java.util.UUID.randomUUID());
        assertEquals(404, initiate(admin, ghost, newAddress("z4")).getResponse().getStatus());
        assertNothingPending(holder);
    }

    @Test
    @DisplayName("An invalid address, or the account's own address, is a 400 and changes nothing")
    void invalidOrSameAddress() throws Exception {
        assertEquals(400, initiate(admin, holderStaff, "not-an-email").getResponse().getStatus());
        assertEquals(400, initiate(admin, holderStaff, holder.getEmail().toUpperCase()).getResponse().getStatus());
        verify(emailService, never()).sendAdministratorEmailChangeVerificationEmail(anyString(), anyString());
        assertNothingPending(holder);
    }

    @Test
    @DisplayName("A deactivated account, and one that has not finished first-time setup, cannot be given a pending change (409)")
    void inactiveOrUnfinishedAccounts() throws Exception {
        holder.setActive(false);
        userRepository.saveAndFlush(holder);
        assertEquals(409, initiate(admin, holderStaff, newAddress("i1")).getResponse().getStatus());
        holder.setActive(true);
        holder.setCredentialsSetupRequired(true);
        userRepository.saveAndFlush(holder);
        assertEquals(409, initiate(admin, holderStaff, newAddress("i2")).getResponse().getStatus());
        verify(emailService, never()).sendAdministratorEmailChangeVerificationEmail(anyString(), anyString());
        assertNothingPending(holder);
    }

    // ── only the account holder completes it ────────────────────────────────────────────────────

    @Test
    @DisplayName("The Staff member, signed in, completes it: the email switches, the staff record follows, the old address is told, sessions and the password are untouched")
    void holderConfirms() throws Exception {
        String fresh = newAddress("hc1");
        initiate(admin, holderStaff, fresh);
        String code = sentCode(fresh);
        String passwordHashBefore = reload(holder).getPassword();

        MvcResult confirmed = confirm(holder, code);
        assertEquals(200, confirmed.getResponse().getStatus(), body(confirmed));
        User stored = reload(holder);
        assertEquals(fresh, stored.getEmail());
        assertEquals(fresh, staffRepository.findById(holderStaff.getId()).orElseThrow().getEmail());
        assertNull(stored.getPendingEmail());
        assertEquals(passwordHashBefore, stored.getPassword(), "no credential change: existing sessions stay valid (the token's password version is derived from the hash)");
        verify(emailService).sendEmailChangeNotice(eq(holder.getEmail()), anyString(), eq(true));
    }

    @Test
    @DisplayName("Nobody else can complete it: an Admin or Manager confirming only ever acts on their OWN account, so the Staff member's pending change is untouched")
    void adminAndManagerCannotConfirmForTheHolder() throws Exception {
        String fresh = newAddress("nc1");
        initiate(admin, holderStaff, fresh);
        String code = sentCode(fresh);
        for (User other : List.of(admin, manager)) {
            MvcResult result = confirm(other, code);
            assertEquals(400, result.getResponse().getStatus(), other.getRole() + ": " + body(result));
        }
        User stored = reload(holder);
        assertEquals(holder.getEmail(), stored.getEmail());
        assertEquals(fresh, stored.getPendingEmail(), "still pending, still waiting for the holder");
        assertEquals(0, stored.getPendingEmailAttemptCount(), "the other accounts' attempts did not count against the holder");
        assertEquals(admin.getEmail(), reload(admin).getEmail());
        assertEquals(manager.getEmail(), reload(manager).getEmail());
        assertEquals(200, confirm(holder, code).getResponse().getStatus(), "the holder can still finish");
    }

    @Test
    @DisplayName("An account named in the query string or the body is ignored: confirmation always acts on the signed-in account, so an Admin or Manager cannot complete it for the holder")
    void confirmationCannotBeSteeredToAnotherAccount() throws Exception {
        String fresh = newAddress("st1");
        initiate(admin, holderStaff, fresh);
        String code = sentCode(fresh);
        for (User caller : List.of(admin, manager)) {
            for (String steer : List.of("?username=" + holder.getUsername(), "?as=" + holder.getUsername(), "?userId=" + holder.getId())) {
                MvcResult viaQuery = mockMvc.perform(post(CONFIRM + steer).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}")
                        .with(user(caller.getUsername()).roles(caller.getRole().name())).with(r -> { r.setRemoteAddr(address()); return r; })).andReturn();
                assertEquals(400, viaQuery.getResponse().getStatus(), caller.getRole() + " " + steer);
            }
            MvcResult viaBody = mockMvc.perform(post(CONFIRM).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"" + code + "\",\"username\":\"" + holder.getUsername() + "\",\"userId\":\"" + holder.getId() + "\"}")
                    .with(user(caller.getUsername()).roles(caller.getRole().name())).with(r -> { r.setRemoteAddr(address()); return r; })).andReturn();
            assertEquals(400, viaBody.getResponse().getStatus(), caller.getRole() + " via body");
        }
        User stored = reload(holder);
        assertEquals(holder.getEmail(), stored.getEmail(), "the holder's email did not change");
        assertEquals(fresh, stored.getPendingEmail(), "and their pending request is untouched");
        assertEquals(0, stored.getPendingEmailAttemptCount());
        assertEquals(200, confirm(holder, code).getResponse().getStatus(), "only the holder completes it");
    }

    @Test
    @DisplayName("A code works once: using it again is refused and nothing changes a second time")
    void codeCannotBeReused() throws Exception {
        String fresh = newAddress("ru1");
        initiate(admin, holderStaff, fresh);
        String code = sentCode(fresh);
        assertEquals(200, confirm(holder, code).getResponse().getStatus());
        assertEquals(400, confirm(holder, code).getResponse().getStatus());
        assertEquals(fresh, reload(holder).getEmail());
        verify(emailService, times(1)).sendEmailChangeNotice(anyString(), anyString(), eq(true));
    }

    @Test
    @DisplayName("Wrong codes are counted and the fifth cancels the request; the right code is then refused and the email never changed")
    void wrongCodesAreLimited() throws Exception {
        String fresh = newAddress("wc1");
        initiate(admin, holderStaff, fresh);
        String code = sentCode(fresh);
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 1; i <= 5; i++) assertEquals(400, confirm(holder, wrong).getResponse().getStatus());
        assertNothingPending(holder);
        assertEquals(400, confirm(holder, code).getResponse().getStatus());
        assertEquals(holder.getEmail(), reload(holder).getEmail());
    }

    @Test
    @DisplayName("An expired code is refused, cancels the request, and the email does not change")
    void expiredCode() throws Exception {
        String fresh = newAddress("ex1");
        initiate(admin, holderStaff, fresh);
        String code = sentCode(fresh);
        User stored = reload(holder);
        stored.setPendingEmailExpiresAt(LocalDateTime.now().minusSeconds(1));
        userRepository.saveAndFlush(stored);
        assertEquals(400, confirm(holder, code).getResponse().getStatus());
        assertNothingPending(holder);
        assertEquals(holder.getEmail(), reload(holder).getEmail());
    }

    @Test
    @DisplayName("One pending request per account: a newer request replaces the older one and the older code stops working immediately")
    void newerRequestReplacesTheOlder() throws Exception {
        String first = newAddress("rp1");
        String second = newAddress("rp2");
        initiate(admin, holderStaff, first);
        String firstCode = sentCode(first);
        initiate(manager, holderStaff, second);
        String secondCode = sentCode(second);

        assertEquals(second, reload(holder).getPendingEmail());
        assertEquals(400, confirm(holder, firstCode).getResponse().getStatus(), "the first code is dead");
        assertEquals(holder.getEmail(), reload(holder).getEmail());
        assertEquals(200, confirm(holder, secondCode).getResponse().getStatus());
        assertEquals(second, reload(holder).getEmail());
    }

    @Test
    @DisplayName("Asking for the same address again inside the cooldown is a 429; there is a second email only after it")
    void resendCooldown() throws Exception {
        String fresh = newAddress("cd1");
        assertEquals(200, initiate(admin, holderStaff, fresh).getResponse().getStatus());
        assertEquals(429, initiate(admin, holderStaff, fresh).getResponse().getStatus());
        verify(emailService, times(1)).sendAdministratorEmailChangeVerificationEmail(eq(fresh), anyString());
        User stored = reload(holder);
        stored.setPendingEmailLastSentAt(LocalDateTime.now().minusSeconds(61));
        userRepository.saveAndFlush(stored);
        assertEquals(200, initiate(admin, holderStaff, fresh).getResponse().getStatus(), "a resend after the cooldown works");
    }

    @Test
    @DisplayName("Rate limit: a sixth request for the same account from the same client in an hour is a 429")
    void initiationIsRateLimited() throws Exception {
        String client = address();
        for (int i = 1; i <= 5; i++) assertEquals(200, initiate(admin, holderStaff, newAddress("rl" + i), client).getResponse().getStatus());
        assertEquals(429, initiate(admin, holderStaff, newAddress("rl6"), client).getResponse().getStatus());
    }

    // ── delivery failure ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("If the code email cannot be sent, nothing is stored and the active email is unchanged; an earlier pending change is kept")
    void codeDeliveryFailureLeavesNothing() throws Exception {
        String earlier = newAddress("df0");
        initiate(admin, holderStaff, earlier);
        String earlierCode = sentCode(earlier);

        doThrow(new EmailSendingException("down")).when(emailService).sendAdministratorEmailChangeVerificationEmail(eq(newAddress("df1")), anyString());
        assertNotEquals(200, initiate(admin, holderStaff, newAddress("df1")).getResponse().getStatus());

        User stored = reload(holder);
        assertEquals(holder.getEmail(), stored.getEmail());
        assertEquals(earlier, stored.getPendingEmail(), "the earlier pending change is exactly as it was");
        assertEquals(200, confirm(holder, earlierCode).getResponse().getStatus(), "and its code still works");
    }

    @Test
    @DisplayName("If the notice to the current address cannot be sent, the change does not start (nothing stored)")
    void noticeFailureLeavesNothing() throws Exception {
        doThrow(new EmailSendingException("down")).when(emailService).sendAdministratorEmailChangeNotice(anyString(), anyString());
        assertNotEquals(200, initiate(admin, holderStaff, newAddress("nf1")).getResponse().getStatus());
        assertNothingPending(holder);
        assertEquals(holder.getEmail(), reload(holder).getEmail());
    }

    // ── uniqueness and not revealing other accounts ─────────────────────────────────────────────

    @Test
    @DisplayName("An address another account uses gets the same status and body as a free one, and each makes exactly two sends")
    void addressInUseIsIndistinguishable() throws Exception {
        User other = account("other", Role.STAFF, "other");
        withStaffRecord(other, TestDepartments.MAINTENANCE); // a department the Manager cannot see
        String taken = other.getEmail();
        String free = newAddress("free1");

        reset(emailService);
        when(emailService.isConfigured()).thenReturn(true);
        MvcResult freeResult = initiate(manager, holderStaff, free);
        assertEquals(List.of("sendAdministratorEmailChangeVerificationEmail", "sendAdministratorEmailChangeNotice"), sends(), "free: the code and the old-address notice");

        reset(emailService);
        when(emailService.isConfigured()).thenReturn(true);
        MvcResult takenResult = initiate(manager, holderStaff, taken);
        assertEquals(List.of("sendEmailAddressInUseNotice", "sendAdministratorEmailChangeNotice"), sends(), "in use: the harmless notice and the old-address notice, no code");

        assertEquals(freeResult.getResponse().getStatus(), takenResult.getResponse().getStatus());
        assertEquals(200, takenResult.getResponse().getStatus());
        // Only the masked address itself differs (it is the address the caller typed)
        assertEquals(body(freeResult).replaceAll("\"pendingEmailMasked\":\"[^\"]*\"", ""), body(takenResult).replaceAll("\"pendingEmailMasked\":\"[^\"]*\"", ""));
    }

    @Test
    @DisplayName("The harmless notice goes to the proposed address and carries no code; the old address is told as usual")
    void inUseNoticeTargets() throws Exception {
        User other = account("other4", Role.STAFF, "other4");
        withStaffRecord(other, TestDepartments.HOUSEKEEPING);
        initiate(admin, holderStaff, other.getEmail());
        verify(emailService).sendEmailAddressInUseNotice(eq(other.getEmail()));
        verify(emailService).sendAdministratorEmailChangeNotice(eq(holder.getEmail()), anyString());
        verify(emailService, never()).sendAdministratorEmailChangeVerificationEmail(anyString(), anyString());
        verify(emailService, never()).sendEmailChangeVerificationEmail(anyString(), anyString());
    }

    @Test
    @DisplayName("An already-registered address creates no pending state when none exists, and the holder has nothing to complete")
    void addressInUseCreatesNoPendingState() throws Exception {
        User other = account("other3", Role.STAFF, "other3");
        withStaffRecord(other, TestDepartments.HOUSEKEEPING);
        assertEquals(200, initiate(admin, holderStaff, other.getEmail()).getResponse().getStatus());
        assertNothingPending(holder);
        assertNull(reload(holder).getPendingEmailExpiresAt());
        assertEquals(400, confirm(holder, "123456").getResponse().getStatus());
        assertEquals(holder.getEmail(), reload(holder).getEmail());
        assertEquals(other.getEmail(), reload(other).getEmail());
    }

    @Test
    @DisplayName("An already-registered address leaves an existing valid pending request exactly as it was: address, hash, expiry, attempts, last-sent, and its code still works")
    void addressInUseDoesNotTouchExistingPending() throws Exception {
        String fresh = newAddress("keep1");
        initiate(admin, holderStaff, fresh);
        String code = sentCode(fresh);
        // one wrong guess, so the attempt count is not its initial value either
        String wrong = code.equals("000000") ? "111111" : "000000";
        assertEquals(400, confirm(holder, wrong).getResponse().getStatus());
        String before = pendingState(holder);
        assertTrue(before.contains("attempts=1"), before);

        User other = account("other5", Role.STAFF, "other5");
        withStaffRecord(other, TestDepartments.HOUSEKEEPING);
        assertEquals(200, initiate(manager, holderStaff, other.getEmail()).getResponse().getStatus());

        assertEquals(before, pendingState(holder), "every pending field is exactly as it was");
        assertEquals(200, confirm(holder, code).getResponse().getStatus(), "the earlier code is still valid");
        assertEquals(fresh, reload(holder).getEmail());
    }

    @Test
    @DisplayName("A valid, available address still replaces an older pending request (and the older code dies)")
    void availableAddressStillReplaces() throws Exception {
        String first = newAddress("rep1");
        String second = newAddress("rep2");
        initiate(admin, holderStaff, first);
        String firstCode = sentCode(first);
        initiate(admin, holderStaff, second);
        assertEquals(second, reload(holder).getPendingEmail());
        assertEquals(400, confirm(holder, firstCode).getResponse().getStatus());
    }

    @Test
    @DisplayName("Delivery failures keep an earlier valid pending request: failing code email, failing old-address notice, failing in-use notice")
    void deliveryFailuresKeepTheEarlierPendingRequest() throws Exception {
        String earlier = newAddress("df0");
        initiate(admin, holderStaff, earlier);
        String earlierCode = sentCode(earlier);
        String before = pendingState(holder);

        // 1. the code email to a free address fails
        doThrow(new EmailSendingException("down")).when(emailService).sendAdministratorEmailChangeVerificationEmail(eq(newAddress("df1")), anyString());
        assertNotEquals(200, initiate(admin, holderStaff, newAddress("df1")).getResponse().getStatus());
        assertEquals(before, pendingState(holder));

        // 2. the old-address notice fails (free address)
        doThrow(new EmailSendingException("down")).when(emailService).sendAdministratorEmailChangeNotice(anyString(), anyString());
        assertNotEquals(200, initiate(admin, holderStaff, newAddress("df2")).getResponse().getStatus());
        assertEquals(before, pendingState(holder));

        // 3. the harmless notice fails (address in use), and then the old-address notice fails (address in use)
        User other = account("other6", Role.STAFF, "other6");
        withStaffRecord(other, TestDepartments.HOUSEKEEPING);
        doThrow(new EmailSendingException("down")).when(emailService).sendEmailAddressInUseNotice(anyString());
        assertNotEquals(200, initiate(admin, holderStaff, other.getEmail()).getResponse().getStatus());
        assertEquals(before, pendingState(holder));
        reset(emailService);
        when(emailService.isConfigured()).thenReturn(true);
        doThrow(new EmailSendingException("down")).when(emailService).sendAdministratorEmailChangeNotice(anyString(), anyString());
        assertNotEquals(200, initiate(admin, holderStaff, other.getEmail()).getResponse().getStatus());
        assertEquals(before, pendingState(holder));

        reset(emailService);
        when(emailService.isConfigured()).thenReturn(true);
        assertEquals(200, confirm(holder, earlierCode).getResponse().getStatus(), "the earlier request survived all four failures");
    }

    @Test
    @DisplayName("A failure of either required send means nothing new is stored, on a free address with no earlier pending request")
    void failureStoresNothing() throws Exception {
        doThrow(new EmailSendingException("down")).when(emailService).sendAdministratorEmailChangeNotice(anyString(), anyString());
        assertNotEquals(200, initiate(admin, holderStaff, newAddress("fs1")).getResponse().getStatus());
        assertNothingPending(holder);
    }

    // ── recipient-address limit ─────────────────────────────────────────────────────────────────

    private Staff extraStaff(String name) {
        User u = account(name, Role.STAFF, name);
        return withStaffRecord(u, TestDepartments.HOUSEKEEPING);
    }

    @Test
    @DisplayName("Recipient limit: 3 messages per proposed address per hour across different clients and different target accounts; the 4th is a 429")
    void recipientLimitAcrossClientsAndTargets() throws Exception {
        String shared = newAddress("rcp1");
        Staff t2 = extraStaff("rt2");
        Staff t3 = extraStaff("rt3");
        Staff t4 = extraStaff("rt4");
        assertEquals(200, initiate(admin, holderStaff, shared, address()).getResponse().getStatus());
        assertEquals(200, initiate(manager, t2, shared, address()).getResponse().getStatus());
        assertEquals(200, initiate(admin, t3, shared, address()).getResponse().getStatus());
        MvcResult fourth = initiate(manager, t4, shared, address());
        assertEquals(429, fourth.getResponse().getStatus(), body(fourth));
        assertNothingPending(t4.getUser());
    }

    @Test
    @DisplayName("Case and surrounding spaces do not give a fresh recipient allowance")
    void recipientLimitNormalisesTheAddress() throws Exception {
        String base = newAddress("rcp2");
        Staff t2 = extraStaff("rt5");
        Staff t3 = extraStaff("rt6");
        Staff t4 = extraStaff("rt7");
        assertEquals(200, initiate(admin, holderStaff, base).getResponse().getStatus());
        assertEquals(200, initiate(admin, t2, base.toUpperCase()).getResponse().getStatus());
        assertEquals(200, initiate(admin, t3, "  " + base + " ").getResponse().getStatus());
        assertEquals(429, initiate(admin, t4, base.substring(0, 6).toUpperCase() + base.substring(6)).getResponse().getStatus());
    }

    @Test
    @DisplayName("Addresses in use count towards the recipient limit too")
    void recipientLimitAppliesToAddressesInUse() throws Exception {
        User other = account("other7", Role.STAFF, "other7");
        withStaffRecord(other, TestDepartments.HOUSEKEEPING);
        Staff t2 = extraStaff("rt8");
        Staff t3 = extraStaff("rt9");
        Staff t4 = extraStaff("rt10");
        assertEquals(200, initiate(admin, holderStaff, other.getEmail()).getResponse().getStatus());
        assertEquals(200, initiate(admin, t2, other.getEmail()).getResponse().getStatus());
        assertEquals(200, initiate(admin, t3, other.getEmail()).getResponse().getStatus());
        assertEquals(429, initiate(admin, t4, other.getEmail()).getResponse().getStatus());
    }

    @Test
    @DisplayName("A recipient-limit refusal changes nothing: an existing pending request keeps every field")
    void recipientLimitRefusalDoesNotMutatePendingState() throws Exception {
        Staff t2 = extraStaff("rt11");
        Staff t3 = extraStaff("rt12");
        String shared = newAddress("rcp3");
        initiate(admin, holderStaff, newAddress("rcp3own"));
        String before = pendingState(holder);

        assertEquals(200, initiate(admin, t2, shared).getResponse().getStatus());
        assertEquals(200, initiate(admin, t3, shared).getResponse().getStatus());
        Staff t4 = extraStaff("rt13");
        assertEquals(200, initiate(admin, t4, shared).getResponse().getStatus());
        assertEquals(429, initiate(admin, holderStaff, shared).getResponse().getStatus());
        assertEquals(before, pendingState(holder));
    }

    @Test
    @DisplayName("A failed delivery still counts against the recipient limit (a failure never resets it)")
    void failedDeliveryStillCountsTowardsTheRecipientLimit() throws Exception {
        String shared = newAddress("rcp4");
        Staff t2 = extraStaff("rt14");
        Staff t3 = extraStaff("rt15");
        Staff t4 = extraStaff("rt16");
        doThrow(new EmailSendingException("down")).doNothing().when(emailService).sendAdministratorEmailChangeVerificationEmail(eq(shared), anyString());
        assertNotEquals(200, initiate(admin, holderStaff, shared).getResponse().getStatus());
        assertEquals(200, initiate(admin, t2, shared).getResponse().getStatus());
        assertEquals(200, initiate(admin, t3, shared).getResponse().getStatus());
        assertEquals(429, initiate(admin, t4, shared).getResponse().getStatus(), "the failed attempt used one of the three");
    }

    // ── the profile shows a pending request to the holder, masked ──────────────────────────────

    @Test
    @DisplayName("After an Admin starts a change, the holder's profile reports it masked, with its expiry, and nothing else about it")
    void holderProfileShowsTheMaskedPendingRequest() throws Exception {
        String fresh = newAddress("pr1");
        initiate(admin, holderStaff, fresh);
        String code = sentCode(fresh);
        MvcResult profile = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/profile")
                .with(user(holder.getUsername()).roles("STAFF"))).andReturn();
        String json = body(profile);
        assertEquals(200, profile.getResponse().getStatus(), json);
        assertTrue(json.contains("\"pendingEmailMasked\":\"sl***@new.test\""), json);
        assertTrue(json.contains("\"pendingEmailExpiresAt\":\""), json);
        assertFalse(json.contains(fresh) || json.contains(code) || json.contains(reload(holder).getPendingEmailCodeHash()), "no full address, code or hash");
        assertTrue(json.contains("\"email\":\"" + holder.getEmail() + "\""), "the active email is still the old one");
    }

    // ── helpers for the tests above ─────────────────────────────────────────────────────────────

    /** The email-sending calls made on the mock, in order, by method name (anything that is not a send, such as isConfigured, is left out). */
    private List<String> sends() {
        return org.mockito.Mockito.mockingDetails(emailService).getInvocations().stream()
                .map(i -> i.getMethod().getName()).filter(n -> n.startsWith("send")).toList();
    }

    /** Every pending field of an account as one string, so "exactly unchanged" can be asserted in one comparison. */
    private String pendingState(User user) {
        User u = reload(user);
        return "email=" + u.getPendingEmail() + ";hash=" + u.getPendingEmailCodeHash() + ";expires=" + u.getPendingEmailExpiresAt()
                + ";attempts=" + u.getPendingEmailAttemptCount() + ";lastSent=" + u.getPendingEmailLastSentAt();
    }

    @Test
    @DisplayName("Two accounts may both have the same address pending; the first to confirm gets it and the second is refused (409) and cancelled")
    void pendingCollisionIsResolvedAtConfirmation() throws Exception {
        User second = account("second", Role.STAFF, "second");
        Staff secondStaff = withStaffRecord(second, TestDepartments.HOUSEKEEPING);
        String shared = newAddress("shared");
        initiate(admin, holderStaff, shared);
        String firstCode = sentCode(shared);
        initiate(admin, secondStaff, shared);
        String secondCode = sentCode(shared);

        assertEquals(200, confirm(holder, firstCode).getResponse().getStatus());
        assertEquals(409, confirm(second, secondCode).getResponse().getStatus());
        assertEquals(shared, reload(holder).getEmail());
        assertEquals(second.getEmail(), reload(second).getEmail());
        assertNothingPending(second);
    }

    // ── login and password reset use only the active email ─────────────────────────────────────

    @Test
    @DisplayName("While a change is pending, password reset and sign-in use only the active email: the pending address gets nothing and cannot sign in")
    void pendingAddressIsNotUsedForResetOrLogin() throws Exception {
        String pending = newAddress("pw1");
        initiate(admin, holderStaff, pending);

        MvcResult resetPending = mockMvc.perform(post("/api/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + pending + "\"}").with(r -> { r.setRemoteAddr(address()); return r; })).andReturn();
        assertEquals(200, resetPending.getResponse().getStatus());
        verify(emailService, never()).sendPasswordResetEmail(eq(pending), anyString());

        MvcResult resetActive = mockMvc.perform(post("/api/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + holder.getEmail() + "\"}").with(r -> { r.setRemoteAddr(address()); return r; })).andReturn();
        assertEquals(200, resetActive.getResponse().getStatus());
        verify(emailService).sendPasswordResetEmail(eq(holder.getEmail()), anyString());

        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + pending + "\",\"password\":\"Correct@123\"}").with(r -> { r.setRemoteAddr(address()); return r; })).andReturn();
        assertEquals(401, login.getResponse().getStatus());
    }

    @Test
    @DisplayName("A deactivated account cannot complete a pending change")
    void deactivatedHolderCannotConfirm() throws Exception {
        String fresh = newAddress("da1");
        initiate(admin, holderStaff, fresh);
        String code = sentCode(fresh);
        holder.setActive(false);
        userRepository.saveAndFlush(holder);
        assertEquals(403, confirm(holder, code).getResponse().getStatus());
        assertEquals(holder.getEmail(), reload(holder).getEmail());
    }

    // ── concurrency ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Two simultaneous confirmations with the same correct code: exactly one succeeds, the email changes once and the notice is sent once")
    void concurrentConfirmationsConsumeTheCodeOnce() throws Exception {
        String fresh = newAddress("cc1");
        initiate(admin, holderStaff, fresh);
        String code = sentCode(fresh);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = List.of(
                    pool.submit(() -> { go.await(); return confirm(holder, code).getResponse().getStatus(); }),
                    pool.submit(() -> { go.await(); return confirm(holder, code).getResponse().getStatus(); }));
            go.countDown();
            int ok = 0;
            for (Future<Integer> f : results) if (f.get() == 200) ok++;
            assertEquals(1, ok, "exactly one confirmation wins");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(fresh, reload(holder).getEmail());
        assertNull(reload(holder).getPendingEmail());
        verify(emailService, times(1)).sendEmailChangeNotice(anyString(), anyString(), eq(true));
    }
}
