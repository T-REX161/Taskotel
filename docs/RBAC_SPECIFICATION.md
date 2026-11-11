# Taskotel RBAC Specification

## 1. Document information

| Item | Value |
|---|---|
| Document | Role-Based Access Control (RBAC) Specification |
| Project | Taskotel, Hotel Staff Workload Management System |
| Version | 2.8 (draft for review; 2.8 records Stage 4A-1: one shared client-address resolver for every limiter (direct remote address by default; forwarded headers only from an explicitly configured trusted proxy) and a hard per-limiter key capacity; no migration). 2.7 records the Stage 3 follow-up: an address another account uses no longer touches any pending state, both address paths now send exactly two emails, requests for one proposed address are limited (3 per hour), and the Staff member can finish an Admin/Manager-started change from Profile; no migration). 2.6 records Stage 3: an Admin, or a Manager for their own department, can START a verified login email change for a Staff account with POST /api/staff/{id}/email-change; only the Staff member can complete it, signed in, with the code sent to the new address). 2.5 records the login-email stage: an Admin or Manager can no longer change a staff member's login email through `PUT /api/staff/{id}`; a different address is refused with 400 and the email change is left to the account holder's verified self-service flow). 2.4 records Step 2M, section 15: a password-reset request gets one answer for a known, an unknown and a deactivated address and for a failed email delivery; a new reset code replaces the old one only after the email was delivered; wrong reset codes are now really counted (a rollback used to undo the count, so the stored five-attempt limit never took effect) and a reset code is single-use under concurrency). 2.3 records Step 2L, section 15: a password change or reset ends earlier sessions (a password version inside the JWT), changing the login email needs the current password and fails closed when the current address cannot be notified, Admin accounts can no longer be permanently deleted, and a deactivated account can neither finish credential setup nor reset its password). 2.2 records the centralized security hardening, Step 2I, section 19.1: deny-by-default URL authorization with an explicit rule for every endpoint and method, Admin-only enforcement inside the department, user and account services through `CurrentUserScopeResolver.requireAdmin()`, role-dependent department details, and the confirmed findings still open). 2.1 records the implemented Manager department assignment and audit history, Step 2H, section 8.2: a dedicated Admin-only operation, an append-only audit table, an Admin-only history endpoint and screen, and the audit of Admin staff edits that move a Manager). 2.0 records the implemented Support write-path isolation, Step 2G-3, section 13.2: confirmation is Admin-only, end and archive are limited to the Manager's own department). 1.9 recorded the Step 2G-2 review corrections to section 13.1. 1.8 recorded the implemented Support read-path isolation, Step 2G-2, section 13.1. 1.7 recorded the implemented Recommendations Department Isolation, Step 2G-1, section 12.3. 1.6 recorded the implemented Dashboard Department Isolation, Step 2F, section 12.2. 1.5 recorded the implemented Workload Department Isolation, Step 2E, section 12.1, with its temporary limitations. 1.0 documented Decisions 1-15; 1.1 added Decision 16 (Manager department assignment); 1.2 added Decision 17 (six rules that settle the assumptions attached to Decision 16); 1.3 recorded the implemented Task Department Isolation (Step 2C, Decision 18, section 10.5); 1.4 records the implemented Staff Department Isolation (Step 2B, documentation Decision 19, section 8.1) and Assignment Department Isolation (Step 2D, documentation Decision 20, section 11.1), brings the Manager-validity statements up to date with the implemented scope resolver (Step 2A), and states what is still outstanding (workload, dashboard, support). Corrected after review: the Step 1 department permissions (Admin-only create, edit and delete; Manager and Staff read-only), a note that Decisions 19 and 20 are documentation decision numbers, and the labelling of the historical test result. |
| Date | 2026-11-10 |
| Status | Documentation only. Nothing described as "target" has been implemented by this document. |
| Sources | (1) Project source code, inspected for this document. (2) `Hotel_Staff_Workload_Balancer_Requirements_Architecture_Admin_All_Yes_FINAL.pdf`. (3) The RBAC decisions confirmed by the project owner: Decisions 1-16, Decision 17 and Decision 18, and the documentation decision numbers 19 and 20 (section 26.1, numbering note). (5) The implementation reports for Steps 2A-2D and the tests named in section 25. |

### How to read the labels

Every important permission carries one of these implementation labels. They describe the **current code**, not the target.

| Label | Meaning |
|---|---|
| IMPLEMENTED | Verified in source code and matches the target. |
| PARTIALLY IMPLEMENTED | Some of it exists, or it exists without the required restriction. The gap is stated. |
| NOT IMPLEMENTED | No code does this. |
| NEEDS VERIFICATION | Not confirmed by reading code, or depends on runtime behaviour or data that was not checked. |

Requirement sources are tagged **[Decision n]** (confirmed decision), **[Doc]** (requirements PDF), or **[Rec]** (recommendation, not a confirmed requirement).

## 2. Purpose and scope

This document defines who may see and do what in Taskotel for the roles ADMIN, MANAGER and STAFF. It records the agreed target model, audits the current implementation against it, lists the missing work and risks, and proposes a test strategy. It is the reference to use when the authorization code is changed.

In scope: backend API authorization, department isolation, data ownership, the frontend's navigation and action visibility, account security rules, and testing. Out of scope: implementing any change, database changes, and non-RBAC product behaviour.

## 3. Architecture and authentication overview

| Area | Facts verified in code |
|---|---|
| Backend | Java 25, Spring Boot 4, Spring Security, Spring Data JPA, PostgreSQL. Modular monolith, packages under `com.hotel.workload`. |
| Authentication | Stateless JWT. `security/JwtAuthenticationFilter.java` (lines 41-63) validates the token, loads the user, **re-reads the role from the database** (line 51), rejects missing or inactive users, and sets the authority `ROLE_<role>`. |
| Authorization | URL rules in `security/SecurityConfig.java`: deny by default (Step 2I). Every public and every protected endpoint is listed by method and path; anything else under `/api/**` is refused (401 without a sign-in, 403 with one). Services then decide again from the database through `CurrentUserScopeResolver` (section 19.1). |
| Method-level security | None. No `@EnableMethodSecurity`, `@PreAuthorize` or `@Secured` exists anywhere in `backend/src/main`. |
| Role checks in services | Department scope is decided by `CurrentUserScopeResolver` in the Staff, Task and Assignment services (Steps 2A-2D). `DashboardServiceImpl` and `WorkloadController` still compare role strings on their own (see section 21). |
| Error handling | 401 from `HttpStatusEntryPoint`; 403 from Spring's default access-denied handling or from `ResponseStatusException` in services. `security/GlobalExceptionHandler.java` handles validation, upload and email errors, not authorization. |
| Identifiers | UUID primary keys on all entities. |
| Frontend | React 19 with Vite and Tailwind. The role is stored in `localStorage` (`frontend/src/services/api.js` line 8) and read by UI components. The frontend has no route guard in `App.jsx` (lines 25-38). |

## 4. Role definitions

| Role | Definition |
|---|---|
| ADMIN | Operates the whole system across all departments: accounts, departments, tasks, assignments, support, system settings. Source: [Doc] 2.1, [Decision 3]. |
| MANAGER | Belongs to exactly one department (several Managers may share a department) and manages that department only: its staff, tasks, assignments, workload and support involving that department. Cannot manage Managers or Admins. A Manager is valid only with an active account, a linked active Staff record and a valid active department; otherwise the Manager has no department-level access. Source: [Doc] 2.2, [Decisions 3, 16, 17]. |
| STAFF | Operational employee. Sees and updates only their own tasks, availability, workload and profile. Source: [Doc] 2.3, [Decisions 1, 4, 5]. |

Department of a Manager [Decision 16]: each Manager belongs to exactly one department, several Managers may belong to the same department, and only Admin can assign or change it. **Current implementation (not a decision):** the data model has no department field on `User`. A Manager's department can only be derived from the `department` of their linked `Staff` record (`staff.user_id` is unique, so at most one). Decision 17.3 requires a linked active Staff record as part of a valid assignment, which is consistent with deriving the department from that record. Decision 17 does not say in so many words that the Staff record's department *is* the Manager's department; that single point is kept as a confirmation item (section 26.3, question 3).

## 5. Global authorization principles (target)

1. **Backend is the security boundary.** The frontend only improves usability. Every rule below must be enforced by the backend.
2. **Deny by default.** An endpoint without an explicit rule must be refused, not open to any signed-in user.
3. **Role, department and ownership are three separate checks.** A request needs the right role, then the right department scope (Manager), then ownership (Staff).
4. **Authority comes from the database, not the token.** Keep the existing behaviour of re-reading the role on each request.
5. **No client-supplied identity.** Own-record endpoints identify the caller from the security context, never from a request field.
6. **Fail closed on missing context.** A Manager with no valid department assignment gets no department-scoped access. [Decision 16] "Valid" is defined by Decision 17.3: the Manager's account is active, the Manager has a linked active Staff record, and the department is a valid active department. If any condition fails, all department-level access is denied.
7. **Least privilege for service methods.** Service methods that change data should not rely only on URL rules.
8. **History is preserved.** Prefer deactivation to deletion when assignments or approvals exist.
9. **One naming scheme.** Use the same role names and the same helper everywhere.

## 6. Complete permission matrix (target)

Scope key: ALL = every record; DEPT = records of the caller's own department (for a Manager, the one department assigned to them by an Admin; a Manager with no valid assignment has no DEPT scope, Decision 16); OWN = the caller's own records; "-" = no access. "Status" is the current implementation of that permission for the roles that should have it.

### 6.1 Dashboard

| Module | Action | Admin | Manager | Staff | Scope or restrictions | Status |
|---|---|---|---|---|---|---|
| Dashboard | View summary | Yes, ALL departments | Yes, DEPT only | Yes, OWN only | Counts, task statuses and workload limited to the scope. Staff see only their own tasks and workload. [Decisions 3, 5] | PARTIALLY IMPLEMENTED: Admin and Staff correct; Manager sees the same all-department summary as Admin (`DashboardServiceImpl` 48-56). |

### 6.2 User management (login accounts)

| Module | Action | Admin | Manager | Staff | Scope or restrictions | Status |
|---|---|---|---|---|---|---|
| Users page | Open and view user list | Yes, ALL | No | No | [Decision 10] | IMPLEMENTED (`/api/users/**` ADMIN-only, `SecurityConfig` 61; Users link only for ADMIN, `Sidebar.jsx` 31; page refuses non-admins, `UsersPage.jsx` 289). |
| Users | Create Admin account | Yes | No | No | [Decision 9] | IMPLEMENTED (role chosen only by ADMIN, `StaffServiceImpl` 89-95). |
| Users | Create Manager account | Yes | No | No | A department is required and the Manager belongs to exactly one. Several Managers may share a department. [Decisions 3, 9, 16] | IMPLEMENTED for role; department required on the creation form (`CreateStaffRequest` `@NotBlank`). The one-department-per-Manager rule is satisfied only through the single staff record (`staff.user_id` unique). |
| Users | Assign or change a Manager's department | Yes, any Manager | No (cannot change their own or another Manager's department) | No | Admin only. The new scope applies on the Manager's next request. Existing tasks, assignments and support records stay with their original department and are not moved automatically. Each change is recorded: who changed it, whose department, previous department, new department, timestamp. [Decisions 16, 17.2, 17.4, 17.5, 17.6] | IMPLEMENTED (Step 2H, section 8.2): `PUT /api/users/{id}/manager-department` (Admin only; body `{"department": "<code>"}`) changes the department of the linked Staff record, validates the target and writes the audit record in the same transaction. The ordinary staff edit (`PUT /api/staff/{id}`) still works for an Admin and now also writes the audit record when it moves a Manager. A Manager is refused on both paths (403). |
| Users | Department-level access of a Manager | n/a (Admin is not department-scoped) | Allowed only while ALL of these hold: the Manager's account is active, the Manager has a linked active Staff record, and the department is a valid active department. If any fails: no department-level access to staff, tasks, assignments, workload, dashboard department data or support (fails closed). Own-profile, own-notification and own-preference access is unaffected. | n/a | [Decisions 16, 17.3] | PARTIALLY IMPLEMENTED: `CurrentUserScopeResolver` (Step 2A) checks all three conditions in the database on every call and refuses an invalid Manager with 403 (one generic message). It is enforced for Staff management (Step 2B, section 8.1), tasks (Step 2C, section 10.5), assignments (Step 2D, section 11.1) the workload endpoint (Step 2E, section 12.1) the dashboard (Step 2F, section 12.2) and the recommendations endpoint (Step 2G-1, section 12.3). It is also enforced for the support APIs (Steps 2G-2 and 2G-3, sections 13.1 and 13.2). Of the 5 existing Managers (read-only count taken before the scope work): 5 have an active account, 4 have a linked active Staff record, 4 have an active department, so 4 are valid and 1 (no Staff record) is refused in the modules where the scope is enforced. |
| Users | Create Staff account | Yes, any department | Yes, own department only | No | [Decision 9] | IMPLEMENTED (Step 2B): a Manager may create STAFF-role accounts only, and only in their own department. The department in the request is never trusted: any value other than the Manager's own department gets 403 before anything is saved. |
| Users | Activate / deactivate account | Yes, any account (never own, never the last active Admin) | Staff in own department, via Staff Management only | No | [Decisions 2, 10, 11] | IMPLEMENTED for the department part (Step 2B): Admin rules correct (`UserAccountService` 60 and `requireNotSelf`); a Manager's activate and deactivate through Staff Management is limited to ordinary Staff of their own department (404 elsewhere, 403 for Manager or Admin accounts). |
| Users | Permanently delete account | Yes, inactive STAFF and MANAGER accounts only | No | No | Admin accounts cannot be deleted. [Decision 11] | IMPLEMENTED (Step 2L): Admin-only and inactive-only are enforced (`UserAccountService.remove`), and any account with the ADMIN role is refused with 409 on both deletion paths (`UserAccountService.remove` and `StaffServiceImpl.removeStaff`, one shared message), with or without a staff record; deactivation is the way to retire an Admin. The Users page no longer offers Remove for an Admin account. |

### 6.3 Staff management

| Module | Action | Admin | Manager | Staff | Scope or restrictions | Status |
|---|---|---|---|---|---|---|
| Staff | View staff list | Yes, ALL, including Manager and Admin accounts | Yes, STAFF-role members of own department only | No | [Decisions 3, 10] | IMPLEMENTED (Step 2B): an Admin lists every record. A Manager's list is the database query `StaffRepository.findByDepartmentAndUserRole(department, STAFF)`, so other departments' staff and Manager or Admin accounts are never loaded. |
| Staff | Create staff member | Yes | Yes, own department only | No | New accounts must use the existing verification and first-time password flow. [Decision 9] | IMPLEMENTED (Step 2B): the verification and credentials-setup flow is unchanged (`createStaff` sets unverified and credentials-setup required). A Manager's new member is always placed in the Manager's own department, taken from the scope resolver and never from the request; any other department value gets 403 before anything is saved. A Manager cannot create Manager or Admin accounts (403). |
| Staff | Edit staff details (name, phone, availability) | Yes, ALL | Yes, own department only | No | Staff edit their own basic profile separately. A Manager without a valid department assignment can edit no staff. [Decisions 1, 3, 15, 16, 17.3] | IMPLEMENTED (Step 2B): `PUT /api/staff/{id}`. A Manager reaches only ordinary Staff of their own department: a record of another department is 404, identical to an id that does not exist; a Manager or Admin account is 403 in the Manager's own department (`requireMayManage`) and 404 in another. An Admin may edit anyone. |
| Staff | Transfer a staff member to another department | Yes, ALL (target must be an active department) | No | No | Only Admin can transfer Staff between departments. Managers manage Staff only within their assigned department. [Decision 17.1] | IMPLEMENTED (Step 2B): a Manager's update must keep the staff member's current department; any change gets 403 before any field changes, so a Manager cannot move Staff into or out of any department. An Admin may transfer. Existing rules unchanged: the target department must be active, and a move is refused while the staff member has active tasks in another department (409, `StaffServiceImpl.updateStaff`). Decision 17 does not say whether Staff transfers are audited (section 26.3, question 20). |
| Staff | Change staff member's login email | Yes, only through the verified flow | Same | No | New email stays pending until verified; old stays active; notification sent. [Decision 8] | IMPLEMENTED: `PUT /api/staff/{id}` refuses (400) any email other than the one the account already has (case-insensitive); the account holder changes it through the verified flow below. |
| Staff | Deactivate / reactivate | Yes, ALL | Yes, own department only | No | [Decision 2] | IMPLEMENTED (Step 2B): same scope as edit (404 outside the department, 403 for Manager or Admin accounts). Existing rule unchanged: a member with an active task cannot be deactivated (409). |
| Staff | Permanently delete | Yes | No | No | [Decision 2] | IMPLEMENTED (`DELETE /api/staff/{id}` ADMIN-only, `SecurityConfig` 62; `removeStaff` decides with `CurrentUserScopeResolver`, so a Manager (valid or not) and Staff get 403 and no sign-in gets 401; it also requires the member to be inactive and to have no assignment history). |
| Staff | Edit Manager/Admin accounts | Yes | No | No | [Doc] "Manage Managers" | IMPLEMENTED (`requireMayManage` in `StaffServiceImpl`; a Manager gets 403 for these accounts in their own department and 404 in another). |

### 6.4 Department management

| Module | Action | Admin | Manager | Staff | Scope or restrictions | Status |
|---|---|---|---|---|---|---|
| Departments | View list | Yes, ALL, in full | Yes: own department in full; every other department reduced to its identity (code, name, active, icon, colour) | Yes: identity of every department only | [Rec] The PDF is silent on viewing. Decided by the project owner in Step 2I. | IMPLEMENTED (Step 2I, section 19.1): `DepartmentServiceImpl.getAllDepartments`. The figures for a department that is not shown in full are never computed. Invalid Manager 403, no sign-in 401. |
| Departments | Create | Yes | No | No | [Doc] "Manage Departments": Manager No | IMPLEMENTED (Step 1): `POST /api/departments` is ADMIN-only (`SecurityConfig` 89, after the all-roles read rule for `GET` at 86), so a Manager or Staff request gets 403. The Departments page shows the add control to ADMIN only (`DepartmentsPage.jsx` 27, 120); that only hides the control, the backend rule is the boundary. |
| Departments | Edit (name, description, icon, colour, head staff) | Yes | No | No | [Doc] | IMPLEMENTED (Step 1): `PUT /api/departments/{code}` is ADMIN-only (`SecurityConfig` 89); Manager and Staff get 403. The edit control is shown to ADMIN only (`DepartmentsPage.jsx` 27, 242). |
| Departments | Delete | Yes (built-in and in-use departments refused) | No | No | [Doc] | IMPLEMENTED (Step 1): `DELETE /api/departments/{code}` is ADMIN-only (`SecurityConfig` 89); Manager and Staff get 403. The delete control is shown to ADMIN only (`DepartmentsPage.jsx` 27, 242). The existing safeguards (built-in and in-use departments refused) are unchanged. |

### 6.5 Task management

| Module | Action | Admin | Manager | Staff | Scope or restrictions | Status |
|---|---|---|---|---|---|---|
| Tasks | View | Yes, ALL | Yes, DEPT | Yes, OWN assigned tasks only | [Decisions 3, 4, 18] | IMPLEMENTED (Step 2C): an Admin lists every task, including tasks with no department. A Manager's list is the database query `TaskRepository.findByDepartment`, so other departments' tasks and tasks with no department are never loaded. Staff keep the assigned-task query unchanged. |
| Tasks | Create | Yes | Yes, DEPT | No | [Doc] "Create Tasks"; [Decisions 3, 18] | IMPLEMENTED (Step 2C): a Manager's task always belongs to their own department. A blank or missing department defaults to it; an explicit other department (or an unknown code) is refused with 403 before anything is looked up or saved. An Admin may create a task in any department, or with none. |
| Tasks | Edit details | Yes, ALL | Yes, DEPT | No | [Decisions 3, 4, 18] | IMPLEMENTED (Step 2C): a Manager reaches a task only through the department-scoped lock, so a task of another department or with no department is 404, identical to an id that does not exist. A Manager cannot move a task to another department or clear its department (403, before any field changes). An Admin keeps the existing lock and may still change or clear a task's department. |
| Tasks | Delete | Yes, ALL | Yes, DEPT | No | [Decisions 3, 4, 18] | IMPLEMENTED (Step 2C): same scoped lock as edit, so a Manager deleting another department's task or a department-less task gets 404 and nothing changes (the task, its assignment and any support on it stay). Admin may delete any task. The existing assignment and support-lifecycle clean-up is unchanged. |
| Tasks | Update status | Yes, any task | Yes, DEPT tasks | Yes, OWN assigned tasks only, transitions in 10.2 | [Decisions 4, 18] | PARTIALLY IMPLEMENTED: Admin (any task) and Manager (their own department's tasks, department-scoped since Step 2C) change status through the full edit (`PUT /api/tasks/{id}`): IMPLEMENTED. Staff: NOT IMPLEMENTED (no endpoint, deliberately not added in Step 2C), and the Staff transition rules in 10.2 are not encoded. |
| Tasks | View unassigned tasks | Yes, ALL | Yes, DEPT pool | No | [Decisions 4, 12, 18] | IMPLEMENTED for the department part (Step 2C): a Manager lists their own department's tasks, assigned or not. Tasks with no department are in no Manager's pool. A separate unassign operation (Decision 12) is NOT IMPLEMENTED. |

### 6.6 Task assignment

| Module | Action | Admin | Manager | Staff | Scope or restrictions | Status |
|---|---|---|---|---|---|---|
| Assignments | Assign task to staff | Yes, ALL | Yes, DEPT task to DEPT staff | No | [Doc] 3.4, [Decision 3] | IMPLEMENTED (Step 2D): a Manager's task and target Staff member must both belong to the Manager's department, otherwise 404 (section 11.1). The existing rules (inactive, Manager or Admin account, unavailable, completed or cancelled task, duplicate, task/staff department match) are unchanged. Admin keeps the existing behaviour, including tasks with no department. |
| Assignments | Reassign | Yes, ALL | Yes, DEPT | No | [Decision 12] | IMPLEMENTED (Step 2D): `PUT /api/assignments/{id}`. A Manager reaches an assignment only when its task belongs to their department, and the new target must too (404 otherwise). The current holder's department is not checked, so the receiving department's Manager can reassign a support-loan assignment. Admin: any. |
| Assignments | Unassign (task returns to its department pool) | Yes, ALL | Yes, DEPT | No | Record who and when; warn if IN_PROGRESS. [Decision 12] | NOT IMPLEMENTED (no endpoint, no audit field). |
| Assignments | View assignments | Yes, ALL | Yes, DEPT | Yes, OWN | [Doc] 3.4, [Decision 3] | IMPLEMENTED (Step 2D): Admin lists all; a Manager's list is the database query `AssignmentRepository.findByTaskDepartmentOrderByAssignedAtDesc`, scoped by the task's department; Staff see only their own assignments, unchanged. |

### 6.7 Workload

| Module | Action | Admin | Manager | Staff | Scope or restrictions | Status |
|---|---|---|---|---|---|---|
| Workload | View all staff workload | Yes, ALL | Yes, DEPT staff, including individual workloads | No | [Decision 5] | IMPLEMENTED (Step 2E, section 12.1): `GET /api/workload` gives a Manager only the operational Staff of their own department, counting only that department's own work. TEMPORARY LIMITATION: work done under a support loan, in either direction, is excluded from both Managers' views (section 12.1). |
| Workload | View own workload | n/a | n/a | Yes, OWN | [Decision 5] | IMPLEMENTED (`WorkloadServiceImpl.calculateWorkload`, Staff scope; unchanged by Step 2E). |
| Workload | Recommendations (cross-department lending suggestions) | Yes, ALL | Yes, involving own department | No | [Rec]. The PDF mentions smart balancing as optional and later. | PARTIALLY IMPLEMENTED (`/api/workload/recommendations` ADMIN/MANAGER; no department restriction). |

### 6.8 Cross-department support

| Module | Action | Admin | Manager | Staff | Scope or restrictions | Status |
|---|---|---|---|---|---|---|
| Support | Create / approve plan | Yes, ALL | Preview only (own receiving department); confirmation is Admin only [Step 2G decision] | No | Approval sequence beyond "Admin confirms" is not defined. [Decision 7] See 26.3. | IMPLEMENTED for authorization (Step 2G-3, section 13.2): `confirm` is Admin only, in the service and at the URL. No "request" or approval state exists (unresolved, 26.3). |
| Support | End active support | Yes, ALL | Yes, involving own department (donor or receiver) | No | [Decision 7] | IMPLEMENTED (Step 2G-3, section 13.2): a Manager reaches only records involving their department; others answer 404. |
| Support | Archive ended record | Yes, ALL | Yes, involving own department (donor or receiver) | No | [Rec] | IMPLEMENTED (Step 2G-3, section 13.2). |
| Support | View support assignments | Yes, ALL | Yes, involving own department (history, section 13.1) | Staff are blocked from the support APIs (an own-loan view is the workload row) | [Decision 7] | IMPLEMENTED for Admin and Manager (Step 2G-2). Staff: 403 on `/api/support/**`; their own loan appears on their workload row. |

### 6.9 Profile, notifications, availability, settings

| Module | Action | Admin | Manager | Staff | Scope or restrictions | Status |
|---|---|---|---|---|---|---|
| Profile | View own profile | Yes | Yes | Yes | OWN [Decision 15] | IMPLEMENTED (`GET /api/profile`). |
| Profile | Edit own display name, phone, picture | Yes | Yes | Yes | OWN; no role, department, status or permission fields [Decision 15] | IMPLEMENTED (profile endpoints accept none of those fields). |
| Profile | Change own password | Yes | Yes | Yes | OWN [Decision 15] | IMPLEMENTED (`PUT /api/profile/password`). |
| Profile | Change own login email | Yes | Yes | Yes | The current password is required; verified before active; old stays active until the code is confirmed; the current address is notified [Decision 8] | IMPLEMENTED (`EmailChangeService`, endpoints `/api/profile/email-change/*`; Step 2L added the current-password requirement and the fail-closed notice, section 15). |
| Profile | Edit another user's profile | Yes, ALL | Staff in own department | No | [Decision 15] | PARTIALLY IMPLEMENTED (only through staff edit, which is department-scoped for Managers since Step 2B; see 6.3). |
| Notification preferences | View / edit own | Yes | Yes | Yes | OWN; email and in-app separate; Security and account notifications cannot be disabled [Decision 13] | PARTIALLY IMPLEMENTED: Security locked (`NotificationPreferenceService` 93); email is the only channel (`NotificationPreferenceDtos`); category list differs from the decision (section 16). |
| Availability | Update own availability | n/a | n/a | Yes, OWN, not to unavailable while ASSIGNED or IN_PROGRESS tasks exist | [Decisions 1, 6] | NOT IMPLEMENTED. |
| Availability | Update another's availability | Yes, ALL (with warning) | Yes, DEPT (with warning) | No | [Decisions 1, 6] | PARTIALLY IMPLEMENTED via `PUT /api/staff/{id}`: the department check exists for Managers since Step 2B (their own department's Staff only); no warning. |
| System settings | View / modify global settings | Yes | No | No | [Decision 14] | NOT IMPLEMENTED (no global settings feature or endpoint). |
| Personal preferences | Appearance, date and time format | Yes | Yes | Yes | OWN [Decision 14] | IMPLEMENTED (`PUT /api/profile/preferences`). |

## 7. Department-level access and isolation rules

Confirmed by [Decisions 3, 16 and 17]:

1. Every Manager belongs to exactly one department. [Decisions 3, 16]
2. Several Managers may belong to the same department. [Decision 16]
3. Only Admin can assign a Manager to a department or change that assignment. [Decisions 16, 17.2]
4. A Manager may read or modify only that department's staff, tasks, assignments, workload and (per Decision 7) support involving it, and cannot access another department's data. [Decisions 3, 16]
5. A Manager has department-level access only with a **valid assignment**: an active account, a linked active Staff record and a valid active department. If any condition fails, all department-level access is denied (fails closed). [Decisions 16, 17.3]
6. A change to a Manager's department applies immediately: the next request after the change is evaluated against the new department. [Decision 17.4]
7. After a Manager's department changes, existing tasks, assignments and support records stay associated with their original department. They are not moved automatically. [Decision 17.5]
8. Each change of a Manager's department is recorded with who changed it, whose department was changed, the previous department, the new department and the timestamp. [Decision 17.6]
9. Only Admin can transfer Staff between departments. Managers manage Staff only within their assigned department. [Decision 17.1]
10. Admin has access across all departments. [Decision 3]
11. Isolation must be enforced by the backend, not only hidden in the frontend. [Decision 3]

Because several Managers can share a department, department scope is a property of the department, not of an individual Manager: two Managers of the same department can both act on that department's data. Sharing a department does not let Managers act on each other's accounts: Managers cannot manage Manager accounts (requirements PDF, "Manage Managers" is Admin only).

Target rules for the backend. Items marked [Rec] are recommendations, not confirmed decisions; every other item follows from the confirmed decisions listed above.

- Resolve the caller's department from the database on each request (not from the JWT), the way the role is resolved. This is also what makes Decision 17.4 (immediate effect) hold: nothing about the department is cached in the token.
- Evaluate Manager validity (Decision 17.3) on every request: account active, linked Staff record active, department active. A Manager who fails any check is refused every department-scoped operation.
- For any operation addressed by id (staff, task, assignment, support), load the resource and compare its department to the caller's. [Rec: return 404 rather than 403 where revealing the resource's existence would leak information.]
- A Manager must not be able to transfer a staff member into or out of a department. Only Admin may transfer Staff. [Decision 17.1]
- Only Admin may assign or change a Manager's department, and the change must be refused unless the target department exists and is active. [Decisions 16, 17.2, 17.3]
- Changing a Manager's department must not touch existing tasks, assignments or support records. [Decision 17.5] The Manager simply no longer has scope over the original department's records.
- Record every Manager department change in an audit record holding: the Admin who made the change, the Manager whose department changed, the previous department, the new department and the timestamp. [Decision 17.6] [Rec: the record is append-only and is written in the same transaction as the change.]
- List endpoints must filter in the query, not after loading. [Rec]

Consequences of the decisions above (stated so they are not missed; none is a new decision):

- A transferred Manager can no longer see or act on the old department's records, including tasks they created there. [Follows from 17.4 and 17.5]
- The old department may be left with no valid Manager. Only Admin then has access to its work until another Manager is assigned. Whether to prevent or warn about this is not decided (section 26.3, question 18).
- Deactivating a department, deactivating the Manager's Staff record or deactivating the Manager's account each makes that Manager invalid at the next request. [Follows from 17.3, 17.4]
- An invalid Manager can still sign in and manage their own profile, notification preferences and personal preferences (these are own-record features, not department-level access).

**Current state: IMPLEMENTED for every department-scoped area; one existing Manager account is still invalid (section 26.3, question 15).** `security/CurrentUserScopeResolver.java` (Step 2A) resolves the caller's scope from the database on every call: Admin gets all departments, a valid Manager gets the department of their linked Staff record, Staff get their own records only. It fails closed with 403 for an invalid Manager (inactive account, no linked Staff record, inactive Staff record, no or inactive department; one generic message that does not say which check failed) and returns 401 without a sign-in. It is enforced in `StaffServiceImpl` (Step 2B, section 8.1), `TaskServiceImpl` (Step 2C, section 10.5), `AssignmentServiceImpl` (Step 2D, section 11.1) and `WorkloadServiceImpl` (Step 2E, section 12.1) and `DashboardServiceImpl` (Step 2F, section 12.2) and `WorkloadRecommendationServiceImpl` (Step 2G-1, section 12.3). It is also enforced for the support APIs (Steps 2G-2 and 2G-3, sections 13.1 and 13.2); every department-scoped area is now covered. The consistency rules remain as they were (a task's assignee must belong to the task's department, except where approved support lends staff across departments; a staff member cannot be moved to another department while holding active tasks in a different one; the target department must be active). Decision 17.6 (the audit trail of Manager department changes) is IMPLEMENTED by Step 2H (section 8.2).

**Data facts (read-only queries, counts only):**

| Role | Accounts | With a staff record | Without a staff record |
|---|---|---|---|
| ADMIN | 2 | 1 | 1 |
| MANAGER | 5 | 4 | **1** |
| STAFF | 31 | 31 | 0 |

Validity of the 5 existing Managers against Decision 17.3: 5 have an active account, 4 have a linked Staff record, 4 of those Staff records are active, 4 of their departments are active, so **4 are valid and 1 is not** (no Staff record). The 4 valid Managers are spread over 3 departments, so at least two Managers already share a department, which Decision 16 allows. The invalid Manager is refused (403) in the modules where isolation is now enforced (Staff management, tasks and assignments). How and when that Manager is given a valid assignment is not decided (section 26.3, question 15); no data was changed. Re-checked read-only on 2026-11-10 (Step 2H, section 8.2): still 5 Managers, 4 valid, 1 invalid (the account named `manager`, no linked Staff record); no data was changed.

**Current implementation of the Manager-department link (verified):** there is no department column on `users`. The only link is `staff.department`, reached through `staff.user_id` (unique, so at most one department per account). Decision 17.3 requires a linked active Staff record, which fits this link; whether the Staff record's department is formally *the* Manager assignment is kept as a confirmation item (section 26.3, question 3). The `departments.head_staff_id` column is a different concept (a department's head) and is not a Manager assignment; no department currently has a head set (verified, count only).

## 8. User and Staff management permissions

See 6.2 and 6.3. Additional rules:

- The Users page lists all accounts (Admin only). Manager and Staff have no access. [Decision 10]
- Managers manage Staff only through Staff Management, within their department. [Decisions 3, 10] A Manager without a valid assignment (active account, linked active Staff record, valid active department) manages no staff. [Decisions 16, 17.3]
- Staff transfers: only Admin can transfer Staff between departments; a Manager cannot move Staff into or out of a department. [Decision 17.1]
- Manager accounts: every Manager belongs to exactly one department; several Managers may share one; only an Admin can assign or change a Manager's department; a Manager can never change their own or another Manager's department. [Decisions 16, 17.2] Creating a Manager account requires a department. [Decisions 9, 16] Each department change is audited (who, whose, previous department, new department, timestamp). [Decision 17.6] IMPLEMENTED by Step 2H (section 8.2).
- Self-protection (existing, IMPLEMENTED): a user cannot deactivate or delete their own account; the last active Admin cannot be deactivated (`UserAccountService` 60). Because deletion requires an inactive account, the last active Admin is also protected from deletion.
- Permanent deletion (existing, IMPLEMENTED for staff): only Admin; the account must be inactive; staff with any assignment history are refused (`StaffServiceImpl.removeStaff`); users who created tasks or approved support records are refused (`UserAccountService`). Deactivation is the way to retain history. [Decisions 2, 11]
- ~~**Conflict:** Admin accounts can currently be permanently deleted once inactive.~~ RESOLVED (Step 2L): an Admin account is refused with 409 on both deletion paths (see 6.2 and section 15).
- Account creation: every account is created unverified with credentials-setup required, so the first-time email verification and password setup flow applies. IMPLEMENTED (`StaffServiceImpl` 104-110). Public self-registration is not provided: no register endpoint exists (`/api/auth/register` from the requirements PDF is not implemented). IMPLEMENTED (and recommended to stay so).
- The creation form requires a department for every account, including Admin accounts. Decision 9 requires it only for Manager and Staff. Documented as a minor difference (section 22).

### 8.1 Staff department isolation (IMPLEMENTED, Step 2B)

Implemented in `staff/StaffServiceImpl.java` and `staff/StaffRepository.java`, using `CurrentUserScopeResolver` and `DepartmentScope` (section 7). The scope is resolved at the start of every staff-management operation, from the database, never from the JWT or the request.

| Rule | Behaviour |
|---|---|
| Admin | Global Staff access: lists every record (including Manager and Admin accounts), creates in any department, edits, transfers, deactivates and reactivates anyone. Only Admin removes a member permanently. |
| Manager, scope | Ordinary (STAFF-role) Staff of the Manager's own department. Managers of the same department share that scope. |
| Manager, list | Database-side filtering: `StaffRepository.findByDepartmentAndUserRole(department, STAFF)`. Other departments' staff and Manager or Admin accounts are never loaded. |
| Manager, create | STAFF-role accounts only (Manager or Admin accounts: 403). The new member's department is the Manager's own, from the resolver. Any other department value gets 403 before anything is looked up or saved, so the refusal says nothing about whether that department exists. |
| Manager, update, deactivate, activate | The record is loaded and then scope-checked: a record of another department is 404 "Staff not found", identical to an id that does not exist. A Manager or Admin account is 403 ("Only administrators can change manager or admin accounts") in the Manager's own department and 404 in another. |
| Manager, department changes | A Manager's update must keep the staff member's current department; any change gets 403 before any field changes. Only Admin transfers Staff (Decision 17.1). |
| Username availability check | Uses the same staff-management scope: Staff and invalid Managers get 403, no sign-in 401. |
| Staff | 403 on every staff-management operation (URL rules for the endpoints, and the service refuses a Staff caller on direct calls). Own-profile behaviour is unchanged (separate profile endpoints). |
| Invalid Manager | 403 on every staff-management operation (inactive account, no linked Staff record, inactive Staff record, no or inactive department). No sign-in: 401. |
| Permanent removal | Admin only, decided by the scope resolver. This replaced a separate authority check that accepted a bare `ADMIN` authority and answered 403 to an unauthenticated caller; an unauthenticated call is now 401. |
| Unchanged | Duplicate username or email (409), password policy (400), refusal to deactivate a member with an active task (409), refusal to move a member with active tasks in another department (409), the target department must be active, the verification and first-time password flow, and the login email (shown, never changed by a staff update; a different address is refused with 400, section 15). |

Verified by `StaffDepartmentIsolationTest` (19 tests), `StaffRemovalAuthorizationTest` (12 tests), the scope-resolver tests (`CurrentUserScopeResolverTest` 18, `CurrentUserScopeResolverDatabaseTest` 12, `CurrentUserScopeResolverTransactionTest` 3), and the existing staff tests updated to use valid Manager fixtures.

Known limitation (not changed): `updateStaff` and `deactivateStaff` lock the staff row by id before the scope check (the existing lock-first pattern), so a Manager's request for an out-of-scope id takes a brief row lock before the 404. `activateStaff` takes no lock. Tasks and assignments, by contrast, use department-scoped locks (sections 10.5 and 11.1).

### 8.2 Manager department assignment and audit history (IMPLEMENTED, Step 2H)

Implemented in `staff/ManagerDepartmentServiceImpl.java`, `ManagerDepartmentController.java`, `ManagerDepartmentAuditRecorder.java`, `ManagerDepartmentChange.java` and `StaffTransferRules.java`; the screen is `ManagerDepartmentModal.jsx` on the Users page.

**Where the department lives.** A Manager's department is the department of their linked Staff record and nothing else. No department field was added to `User`. This confirms the current implementation described in section 26.4 (question 3 remains a confirmation item, but the implementation now relies on it).

| Endpoint | Who | Behaviour |
|---|---|---|
| `GET /api/users/{id}/manager-department` | Admin | The Manager's current department, with flags saying whether the account, Staff record and department are active and whether the Manager is currently valid (Decision 17.3). Changes nothing. |
| `PUT /api/users/{id}/manager-department` | Admin | Body `{"department": "<code>"}`. Changes the department of the linked Staff record and writes the audit record in the same transaction. |
| `GET /api/users/{id}/manager-department/history` | Admin | The audit records for that Manager, newest first. An empty list when there are none. |

**Authorization.** Enforced twice: the URL rule for `/api/users/**` (Admin only) and the service, which asks `CurrentUserScopeResolver` and refuses anyone whose scope is not ALL. No sign-in: 401. Manager (valid or invalid, any target including their own record) and Staff: 403. The frontend control is shown to an Admin only, but is not the control.

**Assignment rules (all checked before anything changes; a failure changes and records nothing).**

| Condition | Result |
|---|---|
| Target user does not exist | 404 |
| Target is not a Manager (Staff or Admin) | 400 |
| Manager has no linked Staff record | 409. No Staff record or department is created or invented. |
| Department missing, blank, unknown or inactive | 400 (the same checks as every other department choice, `DepartmentResolver.requireActive`) |
| Department is the one the Manager already has | 409, no audit record |
| Manager holds an active task (PENDING, ASSIGNED, IN_PROGRESS) in another department | 409. This is the existing Staff transfer rule, now one shared implementation (`StaffTransferRules`) used by both paths, so neither can be weakened without the other. |

The Staff row is locked first (the same lock-first pattern as the staff edit) and read again before the checks. Existing tasks, assignments and support records are not touched (Decision 17.5). The new department applies on the Manager's next request (Decision 17.4): nothing is cached. Assigning a department does not reactivate an inactive account or Staff record, so an invalid Manager stays denied until it is made valid by other means; the screen reports why.

**Audit history** (table `manager_department_changes`, append-only; nothing updates or deletes a row). Like `support_assignments`, it uses plain identifiers with name snapshots and no foreign keys, so a record stays correct after a department is renamed or an account is removed.

| Field | Meaning |
|---|---|
| `id` | Record id |
| `managerUserId`, `managerUsername` | The Manager's account |
| `staffId`, `staffName` | The linked Staff record |
| `previousDepartmentId`, `previousDepartmentCode`, `previousDepartmentName` | Where the Manager was |
| `newDepartmentId`, `newDepartmentCode`, `newDepartmentName` | Where the Manager is now |
| `changedByUserId`, `changedByUsername` | The Admin who made the change |
| `changeSource` | `MANAGER_DEPARTMENT_ASSIGNMENT` (the dedicated operation) or `STAFF_EDIT` (an Admin staff edit that moved a Manager) |
| `changedAt` | Timestamp |

The record is written in the caller's transaction (propagation MANDATORY): if it cannot be written (for example the acting Admin cannot be identified) the department change is rolled back, so there is never a change without a record or a record without a change. The history endpoint returns only these fields and only this table; no other audit information exists or is exposed. Only an Admin can read it.

**Admin staff edit.** `PUT /api/staff/{id}` still lets an Admin move a staff member between departments. When the record belongs to a Manager and the department changes, it now also writes the audit record (`STAFF_EDIT`); otherwise no record is written. Without this, an Admin could change a Manager's department with no record, against Decision 17.6. A Manager is still refused any department change (403), and the active-task rule is unchanged. Decision 17 does not say whether transfers of ordinary Staff are audited; they still are not (question 20).

**Schema.** One new table and index. In development Hibernate (`ddl-auto: update`) creates it; for production `db/migration/V15__manager_department_changes.sql` is provided in the project's usual hand-run style (existence guard, lock timeout, rollback note). It has **not** been applied by this step.

**Existing Manager accounts (read-only check, 2026-11-10).** 5 Managers, 4 valid, 1 invalid: the account named `manager` (id prefix `a4c9b25e`) has no linked Staff record. The other four (`ayeshakhan`, `neelsingh`, `nikhilroy`, `rohanroy`) have an active account, an active Staff record and an active department. No data was changed. Proposed repair (not done, needs the owner's decision, question 15): an Admin creates the Staff record for that account through the existing staff flow with the department the owner names, then assigns or confirms the department with this operation, which leaves an audit record; or deactivates the account if it is not needed. The dedicated operation deliberately refuses (409) rather than creating a Staff record itself.

Verified by `ManagerDepartmentAssignmentTest` (19 tests), `ManagerDepartmentRollbackTest` (2 tests, outside a test transaction so the rollback is real) and `managerDepartment.test.js` (17 tests).

Still unresolved (section 26.3): repairing the Manager without a Staff record (15); whether a department may be left without a valid Manager (18); transfers of ordinary Staff are not audited (20); retention of audit records (19).

## 9. Department management permissions

See 6.4. Departments are Admin-only to create, edit and delete (IMPLEMENTED: the URL rules in `SecurityConfig`, and since Step 2I `DepartmentServiceImpl` refuses a non-Admin itself, so a direct call to the service cannot bypass the URL rule; Manager and Staff can only view, with the role-dependent detail in section 19.1). Existing safeguards (IMPLEMENTED): built-in departments and departments with staff or tasks cannot be removed (`DepartmentController` comment, 409). Naming and assignment of a department "head" (`departments.head_staff_id`) is part of editing and is Admin-only in the target. A department head is **not** the same as a Manager's department assignment [Decision 16] and must not be used as one without a decision (section 26.3). Several Managers can belong to one department, whereas a department has at most one head record. Deactivating a department makes every Manager of that department invalid at the next request, because a valid assignment requires an active department. [Decisions 17.3, 17.4] Only Admin manages departments, so only Admin can trigger this.

## 10. Task management and status transitions

### 10.1 Who can do what

See 6.5. Staff view only tasks assigned to them. Staff cannot create, edit, delete, reassign, unassign, cancel or reopen tasks. [Decision 4] Department isolation for Managers is implemented for tasks (section 10.5).

### 10.2 Staff status transitions (target)

| From | To | Allowed for Staff | Notes |
|---|---|---|---|
| ASSIGNED | IN_PROGRESS | Yes | Own task only |
| IN_PROGRESS | COMPLETED | Yes | Own task only |
| ASSIGNED | COMPLETED | No | Must pass through IN_PROGRESS (Decision 4 sequence) |
| any | CANCELLED | No | |
| COMPLETED | any | No | No reopening |
| IN_PROGRESS | ASSIGNED | No | Not listed |
| PENDING | any | No | A pending task has no assignee |

Manager (own department) and Admin (all departments) may change statuses subject to the consistency rules below.

### 10.3 Existing consistency rules (IMPLEMENTED, `TaskServiceImpl.rejectStatusChangeThatConflictsWithAssignment`, lines 177-206)

- COMPLETED and CANCELLED tasks cannot change status.
- A task with an assignment cannot go back to PENDING.
- A task with no assignment can only be PENDING or CANCELLED.
- Completing, cancelling, deleting or reassigning a task also ends any temporary support on it (`SupportLifecycle`).

### 10.4 Gaps

- No endpoint lets Staff update status. `PUT /api/tasks/{id}` is ADMIN/MANAGER and overwrites the whole task (`UpdateTaskRequest`). The requirements PDF's `PATCH /api/tasks/{id}/status` does not exist. NOT IMPLEMENTED.
- The current rules allow any change between non-terminal statuses (for example ASSIGNED to COMPLETED). They do not encode the Staff sequence. NOT IMPLEMENTED.

### 10.5 Task department isolation (IMPLEMENTED, Step 2C)

Implemented in `task/TaskServiceImpl.java` and `task/TaskRepository.java`, using `CurrentUserScopeResolver` and `DepartmentScope` (section 7). The scope is resolved at the start of every task operation, from the database, never from the JWT or the request.

| Rule | Behaviour |
|---|---|
| Admin | Global access to tasks of every department, including tasks with no department. Existing locking (`lockByIds`) is unchanged. May still move a task to another department or clear its department. |
| Manager, scope | Only tasks whose own `Task.department` is the Manager's department. The department of an assigned Staff member does not decide a task's scope. |
| Manager, list | Database-side filtering: `TaskRepository.findByDepartment(department)`. All tasks are never loaded and filtered in Java. |
| Manager, update and delete | A department-scoped pessimistic lock, `TaskRepository.lockByIdAndDepartment(id, department)`. A task of another department, a task with no department, and an id that does not exist all give the same 404 "Task not found". An out-of-scope task is never locked, and PostgreSQL re-checks the department once it holds the row lock, so a department change committed meanwhile is seen. |
| Manager, create | The department is the Manager's own. Blank or missing means their own; another department (or an unknown code) gets 403 before any lookup or save. |
| Manager, department changes | A Manager's update must keep the task's current department. Moving it to another department, or clearing it (blank or missing), gets 403 before any field changes. |
| Tasks without a department | Invisible and inaccessible to Managers (not listed, 404 on update and delete, never created by a Manager). Admin keeps full access. |
| Staff | Unchanged: they see only tasks assigned to them, through the existing assigned-task query. Create, update and delete are refused (403). No Staff status-update endpoint was added. |
| Invalid Manager | 403 on every task operation (inactive account, no linked Staff record, inactive Staff record, no or inactive department). No sign-in: 401. |
| Direct service calls | The same rules apply when `TaskService` is called without HTTP, because the checks are in the service, not only in the URL rules. The former string-based role helper `isAdminOrManager` was removed, so the scope resolver is the single decision source. |
| Unchanged | Existing assignment, support-lifecycle (support ends when its task is completed, cancelled, moved or deleted), status-transition, validation and department-conflict rules, and the task-before-staff lock order. |

Verified by `TaskDepartmentIsolationTest` (18 tests), `TaskServiceImplScopeTest` (7 tests), the updated `TaskControllerTest` and `AssignmentConcurrencyTest`, and mutation checks on six critical rules. Assignments were done in Step 2D (section 11.1). Still outside this step: workload, dashboard and support (a Manager's view of tasks held by staff lent out of their department is for those steps), and the optional frontend change that defaults Manager forms to their own department.

## 11. Assignment and reassignment permissions

See 6.6. Existing assignment rules (IMPLEMENTED, `AssignmentServiceImpl.createAssignment` and `reassignTask`): the target must be active, not an Admin/Manager account, available, in the task's department, with no active assignment; the task must not be completed or cancelled and must not already be assigned.

Unassignment [Decision 12]: unassigned tasks return to their department's pool. The system must record who unassigned and when (a new audit record is needed; no such field exists today) and warn first if the task is IN_PROGRESS. NOT IMPLEMENTED. Related open question: what status an unassigned task returns to (the existing rules imply PENDING).

### 11.1 Assignment department isolation (IMPLEMENTED, Step 2D)

Implemented in `assignment/AssignmentServiceImpl.java`, `assignment/AssignmentRepository.java` and `staff/StaffRepository.java`, using `CurrentUserScopeResolver` and `DepartmentScope` (section 7). The scope is resolved before any repository call or lock, for create, reassign and list. An assignment's scope is the department of its **task**.

| Rule | Behaviour |
|---|---|
| Admin | Global access to every assignment. Existing behaviour is kept: the existing locks (`lockByIds`) and rules are unchanged, including for tasks with no department (Admin can still assign one to staff of any department; the task/staff department match is checked only when both have a department). |
| Manager, list | Database-side filtering by the task's department: `AssignmentRepository.findByTaskDepartmentOrderByAssignedAtDesc(department)`. Other departments' assignments and assignments of tasks with no department are never loaded. |
| Manager, create | The task is taken with the department-scoped lock `TaskRepository.lockByIdAndDepartment`, and the target Staff member with the department-scoped lock `StaffRepository.lockByIdAndDepartment`. Both must belong to the Manager's department. A task of another department, a task with no department or a nonexistent task gives 404 "Task not found"; a Staff member of another department or a nonexistent one gives 404 "Staff not found". The Staff lock is taken only after the task is known to be in scope. The existing rules then run unchanged. |
| Manager, reassign | The assignment's task is found first (`findTaskIdById`) and taken with the department-scoped lock, then the new target Staff member with the department-scoped lock; the assignment is read only after both locks. An assignment that does not exist, or whose task is in another department or has no department, gives the same 404 "Assignment not found". A target outside the department gives 404 "Staff not found". |
| Current holder | Not checked. Only the task's department and the new target's department decide. A support-loan assignment (a task of the receiving department held by lent staff from another department) is listed for, and can be reassigned by, the **receiving** department's Manager, which ends the support as before. The lending (home) department's Manager does not see it and gets 404 on it, because the task is not theirs. |
| Tasks without a department | Hidden from Managers: not listed, and 404 on create and reassign. |
| Staff | Keep the own-assignment listing (a user with no Staff record gets an empty list). Create and reassign are refused (403) by the URL rules, and the service also refuses a Staff scope before reading anything. |
| Invalid Manager | 403 on list, create and reassign. No sign-in: 401. |
| Locking | Task first, then Staff, as before. A task or Staff member outside a Manager's scope is never locked, the Staff lock is never taken once the task is refused, and the checks use the freshly locked rows. |
| Unchanged | The eligibility rules (active, not a Manager or Admin account, available, task not completed or cancelled, no duplicate, task/staff department match), the support lifecycle (reassigning ends support on the task), the concurrency protections, and the support confirm and end logic (not modified). The former string-based role helper `isAdminOrManager` was removed. |

A Manager's refusal for an out-of-scope Staff member is 404, whereas an Admin whose target is in a different department from the task still gets the existing 400.

Verified by `AssignmentDepartmentIsolationTest` (21 tests), `AssignmentServiceImplScopeTest` (9 tests), the updated `AssignmentControllerTest` (valid Manager fixtures), three Manager-path race tests in `AssignmentConcurrencyTest`, and mutation checks on six critical rules (section 25). `AssignmentConcurrencyTest`, `WorkloadEligibilityTest` and `SupportAssignmentServiceTest` now sign in as Admin where they call the assignment service directly, because they test locking and eligibility rules, not authorization. Still outside this step: unassign (Decision 12), workload, dashboard and support.

## 12. Workload and dashboard visibility

See 6.1 and 6.7. Staff receive only their own row (IMPLEMENTED) and their own dashboard (`DashboardServiceImpl.buildStaffDashboardSummary`). Manager scoping of the workload endpoint is IMPLEMENTED (Step 2E, section 12.1). Manager scoping of the dashboard is IMPLEMENTED (Step 2F, section 12.2). The recommendations endpoint (`GET /api/workload/recommendations`) is still NOT scoped (section 12.2).

### 12.1 Workload department isolation (IMPLEMENTED, Step 2E)

Implemented in `workload/WorkloadServiceImpl.java` and `assignment/AssignmentRepository.java`, using `CurrentUserScopeResolver` and `DepartmentScope` (section 7). The scope is resolved once, at the start of `calculateWorkload()`, from the database; the service no longer accepts a caller-supplied permission. Applies to `GET /api/workload` only.

| Rule | Behaviour |
|---|---|
| Admin | Every operational Staff member (Manager and Admin accounts have no row) and all active work, including support-loan work and work on tasks with no department. Unchanged. |
| Manager | Only the operational STAFF of the Manager's own department (`StaffRepository.findByDepartmentAndUserRole`). Their counts, minutes and status come only from the active assignments whose task belongs to that department and whose holder's home department is that department (`AssignmentRepository.findActiveByTaskAndStaffDepartment`). The filter is in the database query; no global load is filtered afterwards, and support records and departments are not loaded for a Manager. |
| Staff | Their own row only, unchanged (including loan work they hold and the support label on their own row). No Staff record: 403, as before. |
| Invalid Manager | 403 (inactive account, no linked Staff record, inactive Staff record, no or inactive department). No sign-in: 401. |
| Direct service calls | The same rules apply without HTTP. The former `calculateWorkload(username, isAdminOrManager)` was removed. |
| Unchanged | Workload calculation, status thresholds (LOW, NORMAL, HIGH, AT_RISK), eligibility rules (Manager and Admin accounts get no row) and the response shape. |

**Temporary, intentional limitation: support-loan work is excluded from both Managers' workload views.** This is the approved interim rule until the Support isolation step; it is not a final design.
- A Staff member stays in their home department's workload list. The home department's Manager sees them, but work they do for another department (a task of another department) is not counted in their task count or estimated minutes. They appear idle (0 tasks, 0 minutes, LOW) and the other department is not named. No other department's task detail appears.
- The receiving department's Manager does not see the borrowed Staff member in their workload list at all, and the borrowed work is in nobody's rows for that Manager.
- So cross-department support-loan work is excluded from both Managers' views, in both directions (an Alpha person lent to Beta and a Beta person lent to Alpha). Only Admin, and the borrowed person's own Staff view, include it.
- Consequence to be aware of: a Manager can see a lent person as free while they are working elsewhere. How workload and support are shown across departments, and who sees them, is for the Support isolation step (section 26.3, questions 6, 21 and 22).

**Temporary limitation: tasks with no department.** An assignment of a task with no department (only an Admin can create one) is not counted in a Manager's workload counts, minutes or status, so it cannot distort a Manager's figures. Admin still sees it. This matches the Manager's task and assignment views (Decisions 18 and 20). Whether such tasks should exist at all is still undecided (section 26.3, question 2).

**Former temporary exception `DashboardWorkloadSource`: REMOVED in Step 2F.** The interface, its two global methods and `DashboardWorkloadSourceUsageTest` were deleted; the dashboard now uses the internal `ScopedWorkloadCalculator` under its own resolved scope (section 12.2).

### 12.2 Dashboard department isolation (IMPLEMENTED, Step 2F)

Implemented in `dashboard/DashboardServiceImpl.java` (with new count queries in `StaffRepository`, `TaskRepository` and `AssignmentRepository`), using `CurrentUserScopeResolver`. Applies to `GET /api/dashboard/summary`, the only dashboard endpoint. The scope is resolved from the database, not from the role strings in the token, so presenting an Admin authority does not widen it.

| Rule | Behaviour |
|---|---|
| Admin | The global summary, unchanged: all operational Staff, all tasks (including tasks with no department), all active assignments, and the global workload buckets. |
| Manager | Only the Manager's own department, every figure a database count filtered by the department (no global load filtered in Java): operational STAFF counts (total, available, unavailable, inactive); tasks of the department by status (tasks with no department never count); active assignments where the task AND the holder's home department are the department (`AssignmentRepository.countActiveByTaskAndStaffDepartment`). The workload buckets come from the same calculation as `GET /api/workload`, run under the dashboard's already-resolved scope through the internal `ScopedWorkloadCalculator` (one scope resolution per dashboard request; the interface is not on `WorkloadService`, no endpoint accepts a scope, and `ScopedWorkloadCalculatorUsageTest` restricts it to `DashboardServiceImpl`), so the two agree exactly, including the support-loan and department-less exclusions of section 12.1. |
| Staff | Their own dashboard only (own workload, own assignments and own task counts), unchanged, including loan work they hold. No Staff record: 403. |
| Invalid Manager | 403 for the whole dashboard (inactive account, no linked Staff record, inactive Staff record, no or inactive department). No sign-in: 401. |
| Frontend | A Manager's dashboard neither requests nor shows the support-related Operations Insights (`Dashboard.jsx`, `buildInsights` with `showSupport: false`). |

**Recommendations endpoint: now scoped in Step 2G-1 (section 12.3).** The Manager dashboard still neither requests nor shows the support insights (`Dashboard.jsx`, `showSupport: false`); that Step 2F frontend behaviour was deliberately left unchanged.

**Inherited temporary limitations (decided in Step 2E):** support-loan work is excluded from both Managers' dashboard figures, and tasks with no department are not counted for a Manager. Department statistics returned by `GET /api/departments` and the Staff `recentAssignments` list (the Staff member's full own history, unsorted and unbounded) were not changed.

Verified by `DashboardDepartmentIsolationTest` (16 tests, real security chain and H2: Admin global, Manager own department only with exact counts, another department's Manager, two Managers sharing a department, no inference by arithmetic, workload buckets equal to `GET /api/workload`, support loans in both directions, no nested leakage, four invalid-Manager cases, unauthenticated, Staff own data and loan work, Staff without a record, request parameters ignored, direct service calls, a forged Admin authority, and the repository count queries), `DashboardServiceImplScopeTest` (4 mock tests: a Manager uses only the department queries, an Admin only the global ones, a refused scope stops before any repository, Staff use no aggregate), the updated `DashboardControllerTest` and `WorkloadDepartmentIsolationTest.dashboardFollowsTheSameScope`, and frontend tests for the hidden Manager support insight. Mutation checks were run on ten rules (Manager gets the global summary; the role string decides instead of the scope; task total, active assignments and staff total not filtered; the count query ignores the task's department; the count query ignores the holder's home department; workload buckets global; Manager branch skipped; resolver bypassed), and each made at least one test fail.

### 12.3 Workload recommendations department isolation (IMPLEMENTED, Step 2G-1)

Implemented in `workload/WorkloadRecommendationServiceImpl.java` and `workload/WorkloadRecommendationEngine.java` (new entry point `recommendForReceiver`), with new queries in `TaskRepository` and `AssignmentRepository`. Applies to `GET /api/workload/recommendations` only. The scope is resolved once from `CurrentUserScopeResolver`; no request value, department code or `DepartmentScope` can be supplied by a caller, and a direct service call enforces the same rules as HTTP.

| Rule | Behaviour |
|---|---|
| Admin | The existing global recommendations, unchanged (all pressured departments, helper ids and department codes, donor impact figures, full reason text, and the count of tasks with no department). |
| Manager | Only the Manager's own department as the receiving department, with that department's own pressure figures, own waiting task titles and receiver impact. Other departments' recommendation entries, pressure, task titles and names are not returned. |
| Donor information for a Manager | A suggested helper carries only the person's name and their home department's display name. Not returned: the helper's id, the home department code, donor impact figures (`donorImpact` is null), and the donor reason text (replaced by "Currently free and available to help."). The global count of tasks with no department is not reported. |
| Staff | 403, unchanged (also at the URL level). |
| Invalid Manager | 403 (inactive account, no linked Staff record, inactive Staff record, no or inactive department). No sign-in: 401. |
| Unchanged | Severity, the reserve rule, helper lendability (active STAFF account that can sign in), ranking, and the Admin response contract. |

**Data loaded for a Manager.** Only: the receiving department's unassigned active tasks (`TaskRepository.findWaitingByDepartment`), the NUMBER of waiting tasks per department (`countWaitingByDepartmentCode`, counts only, needed by the reserve rule), the ids of staff who hold an active assignment (`AssignmentRepository.findBusyStaffIds`), plus the staff and department lists (needed to find free helpers). No other department's task, and no global assignment list, is loaded.

**Consistency with Steps 2E and 2F.** A person lent to another department holds an assignment, so they are busy and not free, in the Manager's figures as in the Admin's; tasks with no department are never counted as waiting. A Manager's run is evaluated on its own: helpers are not "used up" by other departments' requests, so a Manager can be shown a helper that the Admin view assigns to a higher-priority department first.

**Former limitation (resolved).** After Step 2G-1 a Manager's suggestions carry no staff id, so the support panel's "use suggestions" shortcut could not pre-fill a plan. The support APIs were scoped in Steps 2G-2 and 2G-3, and the frontend (Step 2G-4) now matches each suggestion to the support options by exact home department name plus Staff name: one unambiguous match pre-fills, zero or several leave the person for manual selection.

#### Approved support policy (Step 2G; IMPLEMENTED in Steps 2G-2 and 2G-3)

These decisions are confirmed. The READ paths (options, preview, history) were implemented in Step 2G-2 (section 13.1) and the WRITE paths (confirm, end, archive) in Step 2G-3 (section 13.2).

| Action | Approved policy | Status |
|---|---|---|
| Support options and preview | Admin: any department. Manager: only for their own receiving department. | IMPLEMENTED (Step 2G-2, section 13.1) |
| Confirm cross-department support | Admin only. | IMPLEMENTED (Step 2G-3, section 13.2) |
| Support history | Admin: all. Manager: records involving their own department as donor or receiver. Approver usernames are not shown to Managers. | IMPLEMENTED (Step 2G-2, section 13.1) |
| End or archive | Admin: any. Manager: only records involving their own department. | IMPLEMENTED (Step 2G-3, section 13.2) |
| Donor Manager's view of a loan | Only the loaned Staff member's name and their temporary unavailability; no receiving department and no task details. | IMPLEMENTED in the history and in the end and archive responses and errors (Steps 2G-2 and 2G-3) |

Still unresolved (not decided here): request and approval states, multiple simultaneous loans per person, cancellation and return behaviour beyond the existing automatic end, notifications, and audit history beyond the existing records (section 26.3, questions 1, 21 and 22).

Verified by `WorkloadRecommendationDepartmentIsolationTest` (14 tests, real security chain and H2: Admin global and unchanged, the algorithm preserved for a Manager's own department, own department only, other Managers, no leakage of other departments' titles, names, codes, staff ids or pressure, minimal donor identity, no donor impact or workload text, no department-less count, loan and department-less consistency, four invalid-Manager cases, Staff 403, no sign-in 401, request parameters ignored, direct service calls, scope resolved once, and the repository queries), `WorkloadRecommendationServiceImplScopeTest` (4 mock tests), `WorkloadRecommendationReceiverEngineTest` (2 pure tests), and the updated `WorkloadRecommendationControllerTest` and `WorkloadRecommendationJwtAccessTest` (valid-Manager fixtures). Mutation checks were run on nine rules; eight were caught, and one (every department treated as pressured for a Manager) is an equivalent mutant because no other department's tasks are ever loaded.

Verified by `WorkloadDepartmentIsolationTest` (16 tests: Admin global, Manager own department only, another department's Manager, two Managers sharing a department, loan work excluded in both directions, tasks with no department excluded, existing calculations and thresholds, the four invalid-Manager cases, unauthenticated, Staff own row and unchanged loan behaviour, direct service calls, the database query itself, and the unchanged dashboard), `WorkloadServiceImplScopeTest` (5 mock tests: a Manager's view uses only the department queries, no global load, scope resolved once, refusals stop before any repository), (`DashboardWorkloadSourceUsageTest`, 2 tests, was deleted in Step 2F with the interface it guarded) and the updated `WorkloadControllerTest`. Mutation checks were run on eight critical rules, and each made at least one test fail: a Manager gets the global rows, a Manager's staff list is not filtered, a Manager's assignments are not filtered, the query ignores the task's department, the query ignores the holder's home department, every caller is treated as an Admin, Staff get every staff member, and the scope resolver is bypassed. Still outside Step 2E: the recommendations endpoint, the dashboard (Step 2F), support, and the department statistics returned by `GET /api/departments`.

## 13. Support assignment permissions

[Decision 7] and 6.8. The agreed model has three actors: Admin (all), Manager (support involving their own department), Staff (own support details only).

**Ambiguity, not invented here:** the decision does not say how a cross-department request becomes approved. Open points: (a) whether the lending department's Manager must approve, the receiving department's Manager, or both; (b) whether Admin approval substitutes; (c) what "request" means as a state (the current system has "preview" and "confirm", not "request" and "approve"); (d) who may end support; (e) whether the loaned staff member is notified. These are listed in section 26.3. No approval sequence is specified in this document.

Existing behaviour (verified): a plan is previewed (`POST /api/support/preview`), then confirmed (`POST /api/support/assignments`), which records the approving user and creates temporary assignments; support ends automatically when its task is completed, cancelled, reassigned or deleted, or manually (`POST /api/support/assignments/{id}/end`). Eligible lent staff must be active, available, STAFF-role, able to sign in and without an active task.

### 13.1 Support read paths: department isolation (IMPLEMENTED, Step 2G-2)

Implemented in `support/SupportAssignmentServiceImpl.java` (`getOptions`, `preview`, `list`), `SupportAssignmentRepository.java` and `SupportDtos.java`, using `CurrentUserScopeResolver`. The scope is resolved once per read request from the database; no request value, department code or `DepartmentScope` is trusted, and a direct service call enforces the same rules as HTTP. Applies to `GET /api/support/options`, `POST /api/support/preview` and `GET /api/support/assignments` only. `POST /api/support/assignments` (confirm), `.../{id}/end` and `.../{id}/archive` are unchanged (Step 2G-3).

| Rule | Behaviour |
|---|---|
| Admin | Unchanged: options and preview for any department with full donor figures, and the whole history with approver usernames. |
| Manager, options | Only their own department as the receiving department. Another department (or an unknown one) is 404 with the same body, before anything is loaded. Donor departments are listed with the helper's id and name only: free, available and waiting counts, lendable limits, the explanatory note and every person's task count, minutes and workload status are omitted (null). |
| Manager, preview | Only their own receiving department. Another department is answered exactly like a department that does not exist (`DEPARTMENT_NOT_FOUND`, same body). For their own department the valid pairs, their own impact and the stale-plan fingerprint are kept; donor impact is omitted, `canOverrideReserve` is false, and problems about a person who is not a possible helper, or a task that is not the department's own waiting task, read as "no longer exists" (no name, no task title). Donor-reserve problems and warnings carry a generic message with no figure. |
| Manager, history | Reading it reconciles only the active records that involve their own department (see below). Only records where their department is the donor (home) or the receiver, selected in the database (`findHistoryInvolvingDepartment`, with a status variant), not archived, newest first. Never the approver username. As receiver: the person, home department, task, status and dates. As donor only: the person's name and id, their own department, status and dates (that they are on a temporary loan); no receiving department, task, assignment, plan, end reason or task status. |
| Staff | 403. No sign-in: 401. |
| Invalid Manager | 403 (inactive account, no linked Staff record, inactive Staff record, no or inactive department). |
| Unchanged | Validation, reserve rules, preview calculations, stale-plan fingerprint, transactions and the Admin response contract. |

No endpoint reads a single history record, so the "404 for an out-of-scope record" rule has nothing to apply to yet; it is for the end and archive paths in Step 2G-3. The donor figures in `DonorDepartment` and `DonorStaff` became nullable types (the JSON is unchanged for an Admin).

**Reconciliation on history reads.** The history read first ends active records whose task is gone, finished or moved (the safety net behind the automatic support-ending rules). An Admin's read reconciles every active record, as before. A Manager's read reconciles only the active records that involve their own department (`findByStatusInvolvingDepartment`), so reading the history can never change another department's support records. The rule is judged per record on its own task, so a subset is reconciled by exactly the same rules; records of other departments are ended by the lifecycle hooks, by an Admin's read, or by that department's own Manager's read. The automatic ending itself is not removed.

**Narrowed Manager load for options and preview (database level).** A Manager's options and preview no longer load the global staff, active tasks, assignments or support records. They read (`loadForReceiver`): the receiving department's active staff and waiting tasks (`findActiveWithUserByDepartment`, `TaskRepository.findWaitingByDepartment`), the people who are active and available anywhere (`findActiveWithUserByAvailability`: the possible helpers and each donor's free count for the reserve rule), the NUMBER of waiting tasks per other department (`countWaitingByDepartmentCode`), the ids of busy staff (`findBusyStaffIds`) and of held tasks of the department (`findHeldTaskIdsByDepartment`), one aggregate of the minutes attributed to the department by the support rule (`sumSupportAttributedMinutes`), and the people named in a plan by id. No other department's task, assignment or support record is loaded. The algorithm, reserve rule and stale-plan fingerprint are unchanged: tests compare a Manager's narrowed options and preview (figures, verdicts, helpers and fingerprints) with the Admin's full calculation, including support loans, the reserve rule with a donor's own waiting work, and tasks that are held while still PENDING. Donor impact is not computed for a Manager. Remaining in-memory step: the staff entities of other departments' active, available people are loaded, because the possible helpers and each donor's free count are derived from them; no other department's task detail is.

**Frontend (not changed).** The Manager support panel still expects donor figures. With the approved redaction, `SupportPlanBuilder` shows "null free of null available" and "null waiting", `donor.canLend` is null so the "cannot lend" note shows an empty message, and each helper shows "null active · NaN" minutes; `SupportImpactPreview` is safe because a Manager's `donors` list is empty (no donor free-staff line or table), but `donorCapacityLeft` and `whyCannotAdd` in `utils/supportPlan.js` read donor capacity that is now null. The "use suggestions" shortcut (`USE_SUGGESTIONS`) matches a recommendation's `staffId` and `homeDepartmentCode`, which the recommendations endpoint intentionally omits for a Manager, so it cannot pre-fill a plan. Both need a frontend step that renders a Manager's donor list without figures and resolves a suggestion to an option person by department name and person name (or by an opaque suggestion handle supplied by the backend); the redaction rules must not be relaxed for this. That step is not part of 2G-2.

Verified by `SupportReadDepartmentIsolationTest` (17 tests, real security chain and H2: Admin unchanged, a Manager's options, preview and history, donor and receiving views, another department 404 and not-found equivalence, no leakage of tasks, people, approvers or donor figures, four invalid-Manager cases, Staff 403, no sign-in 401, direct service calls, scope resolved once, and the repository queries), `SupportAssignmentServiceImplScopeTest` (8 mock tests) and the updated `SupportAuthorizationTest`. The other support suites (`SupportAssignmentServiceTest`, `SupportArchiveTest`, `SupportEndServiceTest`, `SupportReserveOverrideTest`) now sign in with Admin authority for their reads, because they test support behaviour in the global view. Mutation checks were run on fourteen rules and each made at least one test fail; the review corrections added `SupportReadDepartmentIsolationTest` tests for reconciliation scope and narrowed-load equivalence (25 tests in all) and mock tests that a Manager read uses only the scoped and narrowed queries (10 in all), with nine further mutation checks.

### 13.2 Support write paths: department isolation (IMPLEMENTED, Step 2G-3)

Implemented in `support/SupportAssignmentServiceImpl.java` (`confirm`, `end`, `archive`), `SupportAssignmentRepository.java` and `security/SecurityConfig.java`, using `CurrentUserScopeResolver`. The scope is resolved once per request from the database; no department code or role from the client is trusted, and a direct service call enforces the same rules as HTTP. Applies to `POST /api/support/assignments` (confirm), `POST /api/support/assignments/{id}/end` and `POST /api/support/assignments/{id}/archive`. The read paths (section 13.1) are unchanged, except that the donor view now also keeps `archivedAt`, so the Manager who archived a record sees that it was archived.

| Rule | Behaviour |
|---|---|
| Confirm | Admin only. Any Manager (valid or not), Staff and an unauthenticated caller are refused (403 or 401) before anything is locked or created: in the service (`confirm` checks the resolved scope first) and at the URL (`SecurityConfig`: `POST /api/support/assignments` is `hasRole("ADMIN")`). |
| End | Admin: any record. A valid Manager: only a record involving their department as donor or receiver. The record is found (`findTaskIdByIdInvolvingDepartment`) and locked (`lockByIdInvolvingDepartment`) through the department filter, in the same order as before (task, then record, then person), so another department's record is never loaded or locked and answers exactly like a record that does not exist (404, `SUPPORT_NOT_FOUND`, same body). |
| Archive | The same scoping through `lockByIdInvolvingDepartment`. An ACTIVE record of another department is also 404, not 409, so its existence and state are not revealed. |
| Responses and errors | An Admin gets the full record. A receiving Manager gets the receiving view (no approver). A donor-only Manager gets only the person, their own department, status and dates: no receiving department, task, plan, approver or end reason. Conflict errors (already ended, still active, task in progress) carry no task id and no task title for a donor-only Manager. |
| Staff, invalid Manager, anonymous | 403, 403 (inactive account, no Staff record, inactive Staff record, no or inactive department), 401. |
| Unchanged | Locking order, transactions, the 409 error mappings, the lifecycle rules (automatic ending, the task returning to its waiting list), the stale-plan check and every validation and reserve rule on confirm. |

A Manager who ends a record where their department is only the donor changes the receiving department's task (it returns to that department's waiting list). That follows the approved rule "a Manager can end a support involving their own department"; it is a deliberate exception to the task-department scope of Steps 2C and 2D.

**Frontend (not changed).** The support panel still offers confirmation to a Manager; the backend now refuses it (403) and the panel shows the server's message through its normal failure path. Hiding the confirm control for Managers, and the Manager donor-figure and suggestion repairs described in section 13.1, belong to the separate frontend integration stage.

**Still unresolved (not decided here).** Request and approval states, multiple simultaneous loans per person, cancellation and return behaviour beyond the existing automatic end and manual end, notifications, and audit history beyond the existing records (section 26.3, questions 1, 21 and 22).

Verified by `SupportWriteDepartmentIsolationTest` (16 tests, real security chain and H2: confirm Admin-only over HTTP and by direct service call, end and archive by the receiving and the donor Manager, an unrelated Manager and a missing id answering identically, no leakage in the donor view or in conflict errors, an ended-meanwhile record, Admin global behaviour, invalid Managers, Staff, anonymous, direct service calls, scope resolved once, and the scoped repository lookups), `SupportConcurrencyTest` (8 tests, including six Managers from three departments racing to end one record: exactly one wins, the other involved ones get 409, the unrelated ones 404), `SupportAssignmentServiceImplScopeTest` (16 mock tests: Manager end and archive use only the scoped lookups, Admin the global ones, refused scopes stop before any repository, confirm by a Manager stops before any repository) and the updated `SupportAuthorizationTest`. The other support suites sign in with Admin authority where they confirm. Mutation checks were run on eleven rules and each made at least one test fail.

## 14. Availability management rules

[Decisions 1, 6]:

1. Staff update only their own availability. Admin updates any; Manager updates staff in their department.
2. Staff cannot set themselves unavailable while holding a task in ASSIGNED or IN_PROGRESS.
3. Manager (own department) and Admin may override this. They must be warned about the active tasks before confirming.
4. Changing availability never automatically cancels or reassigns tasks.

Current state:
- Admin/Manager can change availability only as part of a full staff edit (`PUT /api/staff/{id}`). PARTIALLY IMPLEMENTED (the Manager department scope exists since Step 2B; no warning).
- Staff cannot change availability. NOT IMPLEMENTED (the PDF's `PATCH /api/staff/{id}/availability` does not exist).
- Assignment rules already refuse unavailable staff (IMPLEMENTED). That is a separate rule from this section.

## 15. Profile and account security

[Decisions 8, 9, 15]. Verified existing behaviour (IMPLEMENTED unless noted):

- All profile endpoints identify the caller from the security context (`authentication.getName()`), with no `{id}`.
- Profile editing accepts display name, phone, picture, preferences and password. It does not accept role, department, account status or permissions.
- Login email change (`EmailChangeService`; Step 2L, K-02): the request must carry the signed-in user's **current password**, checked against the authenticated account only (never an account named by the caller); missing or wrong is 400 (never 401), and every request past the own-address checks is charged to the request limiter, so password guessing through this endpoint is limited, and the password is checked before anything that reveals whether another account uses the address. A six-digit code is sent to the **new** address; the address stays pending; the old email remains the login email until the code is confirmed; the pending address is never used to sign in; a notice is sent to the old address when the request is made and again when the change is completed, and **a notice that cannot be sent stops the change instead of being lost silently**: if the request notice fails nothing is stored (the code that went to the new address is useless), and if the completion notice fails the transaction rolls back (the email stays as it was and the same code still works once the email service recovers). The cost is that an email change waits for the email service, which it already needed for the code. Failures are logged without any address or code and answered with the fixed email-failure message. Attempts and resends are limited.
- **Verified login email change for Staff, started by an Admin or Manager (Stage 3).** `PUT /api/staff/{id}` still refuses any different email (400). The way to change it is `POST /api/staff/{id}/email-change` with `{newEmail}` (Admin, or a Manager for staff of their own department; Staff are refused at the URL layer). It reuses the self-service email-change machinery (`EmailChangeService`, the `pending_email*` columns, the same code lifetime, attempt limit, resend cooldown, hashing and notice rules). The checks run in the same order as every other staff change: scope (404 outside a Manager's department), then role (a Manager may not touch Manager or Admin accounts, 403), then the account must be a **Staff-role** account (403 otherwise, for Admin too), active (409 if deactivated) and past first-time setup (409). Then: the new address is validated (400), the request is rate limited by its own limiter (5 per client and target account, 10 per client, 10 per target account, per hour; 429) so it cannot use up the account holder's own allowance, and by a recipient limiter (3 requests per proposed address per hour across every client and target account (each accepted request attempts two sends, so up to 6 sends), 429; see below), a code is sent to the NEW address and a notice to the CURRENT address (an administrator or manager asked for the change; nothing has changed; fails closed), and only then is the pending change stored. The **active login email is never changed by this call**, on the user or the staff record. **Only the account holder completes it:** the existing `POST /api/profile/email-change/confirm` acts on the signed-in account only, so an Admin or Manager who sends the code affects only their own account and cannot verify for anyone else (verification by the initiator would prove nothing, since they may control the new address). One pending change per account: a newer request, from anyone, replaces the older one and the older code stops working at once. **An address another account uses changes nothing.** The answer (status and body) is the same whether or not the proposed address belongs to another account, so a Manager cannot read it as a probe of addresses outside their department. If it is in use, **no pending state is created or changed**: an existing valid pending request keeps its address, code hash, expiry, attempt count and last-sent time exactly, and its code still works (before this follow-up a decoy pending request replaced it and killed its code). No code is sent to that address; instead a harmless notice (the address was submitted as a proposed login address but is already registered; no code, no account details) is sent to it, and the current address gets a notice that is true for the path taken (see below), so **both paths make exactly two sends** and a failure of either aborts with nothing stored (an earlier valid pending request survives). The pending request of a free address is stored only after both sends succeed. A valid newer request still replaces an older valid pending request (no minimum age). Uniqueness is checked again at confirmation (a pending address of another account does not block a request, so it cannot be squatted; if two accounts are pending on one free address, the first to confirm wins and the second gets 409 and is cancelled).
  **Notice to the current address.** For a free address it says an administrator or manager asked for the change to the (masked) address, that nothing has changed yet, and that the change only happens if the holder confirms it themselves with a code sent to the new address (subject "Email Address Change Requested"). For an address in use it is a different message (subject "Email Address Change Could Not Proceed"): the request could not proceed because that address cannot be used, the email address has not changed, and no change is waiting to be confirmed; it never says a change is pending, scheduled or initiated, and names no account, username or department of the address's owner. Both are sent through one two-send path, so the number of sends is the same.
  **Recipient-address limit.** Every request is also counted against its proposed address: 3 per address per hour across all clients and all target accounts (429), for addresses in use as well as free ones; the address is normalised (trimmed, lower case) and keyed by a salted SHA-256 hash so the address is not held in the limiter or its logs. A refusal happens before anything is sent or stored, and a failed delivery still counts (it is never reset). Chosen because a mailbox rarely needs more than a try and a resend or two per hour from this feature, and it stops many accounts or clients from mailing one address. It is in memory and per application instance (not distributed): a restart clears it and each instance keeps its own counts.
  **Staff Profile continuation.** `GET /api/profile` reports a pending change to its owner as `pendingEmailMasked` and `pendingEmailExpiresAt` (both null when none or when its code has expired; never the full address, the code or its hash). Profile then shows "An email change to ne***@x is waiting for your verification code" with an "Enter verification code" action that opens the email dialog directly on the code step: no new address, no password, and no request is sent (so it cannot replace the pending request). The code goes to the existing `POST /api/profile/email-change/confirm` and the updated profile is shown at once. The page reads the profile afresh when it opens. The ordinary self-service flow is unchanged when nothing is pending. A deactivated account can neither start nor complete a change. A completed change does not invalidate existing sessions (the holder confirms it signed in; nothing about the password changes, so the token's password version is unchanged); sign-in and password reset continue to use only the active address. The staff list reports a pending change as `pendingEmailMasked` and `pendingEmailExpiresAt` (masked address, only while the code is valid). Known limitation: the emails are still sent synchronously inside the request. Making both paths send two emails removes the large systematic difference (a different number of sends) but not all timing differences (SendGrid latency varies, and the in-use check does its own database work); asynchronous delivery or an outbox was deliberately not added (it needs a table and a scheduler, and would store the code at rest). A request to an address in use leaves the account's pending state as it was, so the initiating screen shows no new pending request for it, while a free address shows one; a Manager who can see that difference can learn the address is in use, as any signed-in user already can through the self-service request (which answers 409 to a signed-in user who gives their own password). No database migration is needed for this follow-up.
- **Resolved (login-email stage):** an Admin or Manager editing a staff member can no longer change the login email. `StaffServiceImpl.updateStaff` runs its scope, role and department checks first, then compares the email in the request with the account's own (ignoring case and surrounding spaces); a different address is refused with 400 and a message that the account holder must change it from their own account settings, nothing is saved, and no email is silently ignored. The same rule applies to an Admin editing any record, including their own. Creating a staff member still takes an email and keeps the duplicate rule (409). The edit screen shows the login email read-only with an explanation and sends back the existing address. The only writers of a login email are now creation, the verified self-service confirmation (`EmailChangeService`) and the demo data seeder.
- New accounts: created unverified with a temporary password and credentials-setup required; the user verifies the email, then sets their own password.
- **Sessions end when the password changes (Step 2L, K-01).** A token records the account's password version (`pwv`: the last `passwordChangedAt` as epoch milliseconds, 0 if never changed), taken from the database when the token is issued (login and credential setup). On every request `JwtAuthenticationFilter` compares it with the account's current version, after the signature, expiry, active and role checks, which are unchanged; a mismatch means no authentication (401). A successful password change, password reset or first-time setup moves the version; a failed one does not, so existing sessions survive failures. The version is plain arithmetic on the stored value (no time zone or daylight-saving rule can change it) and every writer stores milliseconds, so the database's microsecond precision cannot make two reads differ. Tokens issued before this check existed (no `pwv`) are accepted only if issued no earlier than the second of the last password change (one-second precision, so a sign-in in the same second as a change is not wrongly refused); accounts that never changed their password accept them. Limitation: the device that changes the password is signed out too (its token is also old) and meets the normal sign-in screen on its next request; tokens are otherwise not revocable (no logout list), and still last up to 24 hours.
- **Client address for rate limiting and limiter capacity (Stage 4A-1).**
  **Which address a limiter sees.** Every limiter that is keyed by client (login, credential setup, password reset request and confirm, verification send and verify, password change, and the three email-change limiters, including the Admin/Manager-initiated one) gets its client from one component, `ClientAddressResolver`, instead of each controller calling `getRemoteAddr()`. The recipient-address limiter uses a fixed label instead of a client, as before. The username or email is never used as a stand-in for the client.
  **Default: the direct remote address only.** With nothing configured, `X-Forwarded-For`, `Forwarded`, `X-Real-IP` and every similar header are ignored, so a caller cannot choose which client a limit applies to. A null or blank remote address is "unknown", an unreadable one is used as text (cut to 64 characters), and IPv6 is written in one canonical form. Spring's global forwarded-header handling (`server.forward-headers-strategy`) is **not** enabled.
  **Deployment consequence.** Behind a reverse proxy or load balancer the direct address is the proxy's, so every user shares one client allowance (for login, 20 attempts per 15 minutes in total), and enough failed attempts from anyone can block sign-in for all users. Until the topology is decided, run the application without a proxy in front of it, or configure the trusted proxy below. This is a deployment decision, not something the code can guess.
  **Optional trusted proxies (off by default).** `auth.client-address.trusted-proxies` takes a comma-separated list of IP addresses and CIDR ranges (for example `10.0.0.5,192.168.0.0/24,2001:db8::/32`). Only when the connection's own peer is in that list, and only `X-Forwarded-For` (several header lines are joined in order), is the chain read from the right, skipping hops that are themselves trusted; the first untrusted hop is the client, or the leftmost hop if all are trusted. Anything unclear (an empty, malformed, host-name, zone-scoped or port-bearing entry, or more than 20 hops) is refused and the peer's own address is used, so a request can never pick its identity. Entries that cannot be read, and any /0 range, stop start-up. No host name is ever looked up. **Requirement for whoever deploys it:** the proxy must overwrite or sanitise any inbound `X-Forwarded-For` (or at least append the address it saw), the application must be reachable only through it, and the trusted range must contain only hosts that are really proxies.
  **Capacity.** Each limiter instance (they do not share a map) tracks at most 50,000 keys (`AttemptLimiter.DEFAULT_MAX_KEYS`; roughly a few tens of megabytes at most). Keys that have aged out of the window are removed first (periodically, and also when the map is full, at most once a second). When the map is still full, an attempt that would need a new key is refused with 429 and a 60-second Retry-After, and **nothing is stored**; an attempt whose keys are all already tracked is judged exactly as before, so active counters are never evicted or reset to make room. The sliding window and every configured attempt limit are unchanged. The cost is that a flood of distinct clients or accounts can make one limiter turn away other *new* keys for up to a window; that was chosen over unbounded memory or letting an attacker push a real counter out, and one saturated limiter does not affect the others.
  **Still true.** All limits are in memory and per application instance: a restart clears them and several instances each keep their own counts. They are not a distributed limit.
- **Password reset (Step 2M, K-03).** `POST /api/auth/password-reset/request` ends the same way for a known address, an unknown one, a deactivated account, an account inside its 60-second cooldown and an account whose email could not be delivered: 200 with an empty body, nothing about any address, and a throw-away hash computed so the hashing work resembles a real request. Only an email service that is not configured at all is an error (500), and it is the same error for every request. Under a lock on the account row (`SELECT … FOR UPDATE`) the request (1) reserves the cooldown, which is kept even if delivery fails, (2) hands the email to the email service, and (3) only after that succeeded stores the new code, replacing the old one and restarting its attempt count. A delivery failure (any exception from the email client) is logged without address or code and changes nothing else: an earlier valid code keeps its hash, expiry and attempt count and still works, and no undelivered code is left stored. `POST /api/auth/password-reset/confirm` keeps the generic "Invalid code or email." for every refusal, the five-wrong-codes cancellation, the 10-minute expiry, single use and the password-strength rule (a weak password is refused without using up the code); it also locks the row, so parallel guesses are all counted and a code works once. **Defect fixed:** before Step 2M the refusal was a runtime exception inside a default transaction, so the saved attempt count was rolled back and the stored limit never took effect (only the in-memory limiter limited guessing); `confirmReset` now keeps those changes (`noRollbackFor`). A successful reset moves the password version (section 15, K-01) and ends earlier sessions; a failed one does not; a deactivated account cannot reset (K-07).
- **Deactivated accounts cannot finish setup or reset (Step 2L, K-07).** `PUT /api/auth/credentials/setup` and `POST /api/auth/password-reset/confirm` refuse an inactive account with the same answer as a bad token or an unknown email respectively, change nothing, leave any code or attempt count as it was, and issue no session; reactivating the account makes the same token or code work again.
- "Manager can edit Staff profiles within their department" and "Admin can manage all profiles": only via `PUT /api/staff/{id}`, which is department-scoped for Managers since Step 2B. PARTIALLY IMPLEMENTED (no dedicated profile-management feature, and the login email is not editable there, see above).

## 16. Notification preferences

[Decision 13]. Current implementation (verified, `notification/NotificationCategory.java`):

| Existing category | Mandatory | Default email |
|---|---|---|
| TASK_ASSIGNED | No | On |
| TASK_STATUS | No | Off |
| WORKLOAD_ALERT | No | On |
| TASK_REMINDER | No | Off |
| SECURITY | **Yes** | On |

Gaps against the decision:
- The decision lists optional categories for task assignment, reassignment, completion, availability changes and support assignment updates. The system has different categories; reassignment, completion, availability and support categories do not exist. NOT IMPLEMENTED.
- The decision requires separate in-app and email preferences. The service stores both flags, but the API exposes **email only** ("Email is the only channel", `NotificationPreferenceDtos`). PARTIALLY IMPLEMENTED.
- Security and account-related notifications cannot be disabled: IMPLEMENTED for SECURITY (`NotificationPreferenceService` 93). Whether a separate "account" category is wanted is open.
- No code outside the `notification` package reads these preferences. The system does not currently send task, workload or reminder notifications (only security emails such as the email-change notice). So the preferences are stored but have no effect today. NEEDS VERIFICATION of any other sender; none was found.

## 17. System settings

[Decision 14]. Target: Admin only can view and change global settings; Manager and Staff cannot; everyone manages personal preferences. Current: the Settings page contains only personal preferences (appearance, date format, time format) and notification preferences, for every role (`SettingsPage.jsx`). There is no global settings feature, endpoint or storage. NOT IMPLEMENTED. The content of global settings is not defined (section 26.3).

## 18. Frontend navigation, route and component visibility

### 18.1 Target sidebar

| Entry | Admin | Manager | Staff |
|---|---|---|---|
| Dashboard | Yes | Yes | Yes |
| Staff | Yes | Yes (own department) | No |
| Tasks | Yes | Yes (own department) | Yes (own tasks) |
| Assignments | Yes | Yes (own department) | Yes (own assignments) |
| Workload | Yes | Yes (own department) | Yes (own workload) |
| Departments | Yes (manage) | Yes, read-only [Rec] | Yes, read-only [Rec] |
| Users | Yes | No | No |
| Profile | Yes | Yes | Yes |
| Settings (personal) | Yes | Yes | Yes |
| Settings (global) | Yes | No | No |

### 18.2 Current state

`frontend/src/components/layout/Sidebar.jsx` lines 21-37 shows Staff, Tasks, Assignments, Workload and Departments to every role and Users only to ADMIN. Consequences: STAFF sees a Staff link that ends in a 403 and an error message ("Unable to load staff members", `StaffPage.jsx`). PARTIALLY IMPLEMENTED.

### 18.3 Routes and direct URLs

`App.jsx` defines all routes unconditionally; there is no guard by authentication or role, and an unknown path renders the Dashboard. Direct URLs therefore load the page shell and rely on the backend answering 401 (the API wrapper clears the session and redirects to login, `api.js` lines 56-60) or 403 (error text). Only `UsersPage.jsx` (line 289) shows an explicit "no access" view. NOT IMPLEMENTED as route-level protection.

Target: a route guard that checks authentication and role before rendering, a single "no access" page for refused routes, and unauthenticated access redirected to login. The guard improves experience only; the backend must still refuse.

### 18.4 Action visibility (current, verified)

| Area | Condition used | File |
|---|---|---|
| Tasks: create, edit, assign, delete | `ADMIN` or `MANAGER` | `TasksPage.jsx` 30-31, `TasksTable.jsx` 311-395 |
| Assignments: manage | `ADMIN` or `MANAGER` | `AssignmentsPage.jsx` 46-47 |
| Departments: add, edit, delete | `ADMIN` only (`canManage`) | `DepartmentsPage.jsx` 27, 120, 242 (Step 1; matches the target). Manager and Staff can only view. |
| Staff: role choice when adding | `ADMIN` only | `AddStaffModal.jsx` 136-137 |
| Staff: remove menu item | `ADMIN` only | `StaffTable.jsx` 46-47, 358 |
| Users page | `ADMIN` only | `UsersPage.jsx` 73-74, 289 |
| Support panel and end action | `ADMIN` or `MANAGER` | `WorkloadPage.jsx` 438, `CrossDepartmentSupportPanel.jsx` 220 |

Required additions for the target: department-limited lists and actions for Manager; a task status control for Staff on own tasks; an availability control for Staff with the active-task block; an availability override warning for Manager and Admin; an unassign action with the IN_PROGRESS warning. (Admin-only department controls were done in Step 1.)

## 19. Backend API authorization requirements

Role abbreviations: A = Admin, M = Manager, S = Staff. "Dept" = restricted to the caller's department. "Own" = restricted to the caller's own records.

**Note on the "Current rule" column (Step 2I).** The line numbers in parentheses (for example `(63)`) refer to `SecurityConfig` before it was rewritten in Step 2I and are historical. The authoritative, current list of rules is the explicit inventory in section 19.1, which `SecurityConfigEndpointCoverageTest` checks against the controllers.

| Method and endpoint | Target roles and scope | Current rule | Status | Required change |
|---|---|---|---|---|
| `POST /api/auth/login`, `POST /api/auth/email-verification/send`, `/verify`, `PUT /api/auth/credentials/setup`, `POST /api/auth/password-reset/request`, `/confirm` | Public | permitAll, listed by method and path (Step 2I) | IMPLEMENTED for the URL rule. The verification endpoints have open findings (section 19.1, R5): account enumeration and no send limit. | Pending the owner's decision on the R5 fix. |
| `GET /api/users` | A | ADMIN (explicit); `UserServiceImpl.getAllUsers` also requires Admin (Step 2I) | IMPLEMENTED | None. |
| `PATCH /api/users/{id}/activate`, `/deactivate` | A | ADMIN (explicit); `UserAccountService` also requires Admin; the acting user comes from the security context (Step 2I) | IMPLEMENTED | None. |
| `DELETE /api/users/{id}` | A; inactive STAFF/MANAGER only; never ADMIN | ADMIN (explicit); `UserAccountService.remove` also requires Admin (Step 2I) and refuses any Admin-role account with 409 (Step 2L) | IMPLEMENTED | None. `DELETE /api/staff/{id}` refuses an Admin-role account the same way. |
| `GET /api/staff` | A all; M Dept | ADMIN, MANAGER (63) | IMPLEMENTED (Step 2B) | None for department isolation. Admin: all records; Manager: ordinary Staff of their own department (`findByDepartmentAndUserRole`); Staff 403; invalid Manager 403; no sign-in 401. |
| `GET /api/staff/username-availability` | A, M | ADMIN, MANAGER (63) | IMPLEMENTED | Keep. Since Step 2B the service also applies the staff-management scope: Staff and invalid Managers get 403. |
| `POST /api/staff` | A any role (a Manager account must be given a department); M STAFF in own Dept only; a Manager who is not valid (Decision 17.3) creates nothing | ADMIN, MANAGER (63) | IMPLEMENTED (Step 2B) | None for department isolation. A Manager's staff always get the Manager's own department; another department is 403; an invalid Manager is 403. |
| `PUT /api/staff/{id}` | A: any change, including transferring Staff between departments [17.1] and assigning or changing a Manager's department [17.2]; target department must exist and be active. M: own Dept only, and **no** department change at all. Email change via the verified flow. | ADMIN, MANAGER (63) | PARTIALLY IMPLEMENTED | Done (Step 2B): the Manager scope check (404 outside the department), no department change by a Manager (403), an invalid Manager denied (403). Done (Step 2H): an Admin edit that moves a Manager now writes the audit record [17.6] in the same transaction. Still open: route email change through verification. |
| Assign / change a Manager's department | A only [Decisions 16, 17.2]. Applies on the Manager's next request [17.4]. Does not move existing tasks, assignments or support records [17.5]. Writes an audit record: changing Admin, Manager affected, previous department, new department, timestamp [17.6]. | `PUT /api/users/{id}/manager-department` (Admin only: URL rule and service). Anonymous 401; Manager and Staff 403 | IMPLEMENTED (Step 2H, section 8.2) | A dedicated operation was chosen (formerly section 26.3, question 17). The staff edit remains and is audited as well. |
| Read the Manager-department change audit trail | A only (Manager and Staff: no access, not even to their own history) | `GET /api/users/{id}/manager-department/history` (Admin only: URL rule and service). Anonymous 401; Manager and Staff 403 | IMPLEMENTED (Step 2H, section 8.2) | Admin-only reading is now decided (formerly section 26.3, question 19). Retention is not decided. |
| `PATCH /api/staff/{id}/deactivate`, `/activate` | A; M Dept | ADMIN, MANAGER (63) | IMPLEMENTED (Step 2B) | None for department isolation. Manager: scope check (404 outside the department; 403 for Manager or Admin accounts). |
| `DELETE /api/staff/{id}` | A | ADMIN (62) and service re-check | IMPLEMENTED | None. |
| `PATCH /api/staff/{id}/availability` (or equivalent) | A any; M Dept; S Own, with active-task block and override rules | Does not exist | NOT IMPLEMENTED | Add endpoint with ownership, department and warning rules. |
| `GET /api/departments` | A full; M own department in full, others identity only; S identity only | A, M, S (explicit rule); `DepartmentServiceImpl.getAllDepartments` decides the detail | IMPLEMENTED (Step 2I) | None. Basic identity = code, name, active, icon, colour (and the id); description, head, staff and task figures, status and supervisor are null. |
| `POST /api/departments`, `PUT/DELETE /api/departments/{code}` | A | ADMIN (explicit rules); `DepartmentServiceImpl` also requires Admin (Step 2I) | IMPLEMENTED | None. Manager and Staff get 403 from the URL rule and from a direct service call; no sign-in 401. |
| `GET /api/tasks` | A all; M Dept; S Own | A, M, S (explicit rule since Step 2I); `TaskServiceImpl.getAllTasks` scopes | IMPLEMENTED (Step 2C) | None for department isolation. Admin: all; Manager: `findByDepartment`; Staff: assigned tasks, unchanged; no sign-in 401; invalid Manager 403. |
| `POST /api/tasks` | A; M Dept | ADMIN, MANAGER (64); `TaskServiceImpl.createTask` scopes | IMPLEMENTED (Step 2C) | None for department isolation. Manager: own department (blank defaults to it, other department 403). |
| `PUT /api/tasks/{id}` | A; M Dept | ADMIN, MANAGER (65); `TaskServiceImpl.updateTask` scopes | IMPLEMENTED (Step 2C) | None for department isolation. Manager: scoped lock (404 outside the department), no move or clear of the department (403). Also the path for status changes. |
| `DELETE /api/tasks/{id}` | A; M Dept | ADMIN, MANAGER (66); `TaskServiceImpl.deleteTask` scopes | IMPLEMENTED (Step 2C) | None for department isolation. Manager: scoped lock (404 outside the department). |
| `PATCH /api/tasks/{id}/status` (or equivalent) | A any; M Dept; S Own with section 10.2 transitions | Does not exist | NOT IMPLEMENTED | Add endpoint with ownership and transition rules. |
| `GET /api/tasks/{id}` | A; M Dept; S Own | Does not exist | NOT IMPLEMENTED (only if needed) | Add with the same scoping if the UI needs it. |
| `POST /api/assignments` | A; M Dept | ADMIN, MANAGER (67); `AssignmentServiceImpl.createAssignment` scopes | IMPLEMENTED (Step 2D) | None for department isolation. Manager: task and target Staff in their own department (404 otherwise); Staff 403; invalid Manager 403; no sign-in 401. |
| `PUT /api/assignments/{id}` (reassign) | A; M Dept | ADMIN, MANAGER (68); `AssignmentServiceImpl.reassignTask` scopes | IMPLEMENTED (Step 2D) | None for department isolation. Manager: the assignment's task and the new target in their own department (404 otherwise); the current holder's department is not checked; Staff 403; invalid Manager 403. |
| Unassign task | A; M Dept; with audit and IN_PROGRESS warning | Does not exist | NOT IMPLEMENTED | Add endpoint and audit record. |
| `GET /api/assignments` | A all; M Dept; S Own | all roles (69), `AssignmentServiceImpl.getAllAssignments` scopes | IMPLEMENTED (Step 2D) | None for department isolation. Admin: all; Manager: by the task's department; Staff: own, unchanged; no sign-in 401; invalid Manager 403. |
| `GET /api/workload` | A all; M Dept; S Own | all roles (84), `WorkloadServiceImpl.calculateWorkload` scopes | IMPLEMENTED (Step 2E) | None for department isolation. Admin: all; Manager: own department's staff and own work only (support-loan work excluded, temporary, section 12.1); Staff: own row; invalid Manager 403; no sign-in 401. |
| `GET /api/workload/recommendations` | A; M own Dept as receiver | ADMIN, MANAGER (81), `WorkloadRecommendationServiceImpl` scopes | IMPLEMENTED (Step 2G-1) | None for department isolation. Admin global; Manager own department only with redacted donor information (section 12.3); Staff 403; invalid Manager 403; no sign-in 401. |
| `GET /api/dashboard/summary` | A all; M Dept; S Own | all roles (85), `DashboardServiceImpl` scopes | IMPLEMENTED (Step 2F) | None for department isolation. Admin: global; Manager: own department only (section 12.2); Staff: own data; invalid Manager 403; no sign-in 401. |
| `GET /api/support/options`, `POST /api/support/preview`, `POST /api/support/assignments`, `GET /api/support/assignments`, `POST .../{id}/archive`, `POST .../{id}/end` | A all; M involving own Dept; S Own details (view only) | ADMIN, MANAGER (83); POST /api/support/assignments ADMIN only | PARTIALLY IMPLEMENTED | Department rules IMPLEMENTED (Steps 2G-2 and 2G-3, sections 13.1 and 13.2). Still open: Staff read-own endpoint, request/approval model once defined. |
| `GET /api/profile`, `PUT /api/profile/display-name`, `/phone`, `/preferences`, `/password`, `POST` and `DELETE /api/profile/picture` | A, M, S Own | A, M, S (explicit rules since Step 2I); the account comes from the token | IMPLEMENTED | None. |
| `POST /api/profile/email-change/request`, `/confirm` | A, M, S Own | A, M, S (explicit rules since Step 2I) | IMPLEMENTED | None. |
| `GET/PUT /api/notification-preferences` | A, M, S Own | A, M, S (explicit rules since Step 2I) | PARTIALLY IMPLEMENTED | Category list and channel gaps (section 16). |
| System settings endpoints | A | Do not exist | NOT IMPLEMENTED | Define and add. |
| `GET /api/test/admin`, `/manager`, `/staff` | Non-production only | Role-gated outside production; not mapped and denied in production | IMPLEMENTED | None. |
| Any other `/api/**` (unknown route, or a method not listed for a known route; OPTIONS, and a HEAD on a path with no GET rule, included) | Denied unless listed | `denyAll` after the explicit rules (Step 2I): 401 without a sign-in, 403 with one, for every role including Admin | IMPLEMENTED | None. A new endpoint stays closed until a rule is added; `SecurityConfigEndpointCoverageTest` fails until it also has an entry in the inventory. |

Endpoints named in the requirements PDF that do not exist: `POST /api/auth/register` (should stay absent), `GET /api/staff/{id}`, `PATCH /api/staff/{id}/availability`, `GET /api/tasks/{id}`, `PATCH /api/tasks/{id}/status`, `POST /api/assignments/{id}/reassign` (reassignment is `PUT /api/assignments/{id}` instead), `GET /api/staff/{id}/tasks`, `GET /api/tasks/{id}/assignment`, `GET /api/workload/staff/{id}`, `/overloaded`, `/department/{department}`, `GET /api/dashboard/workload`.

### 19.1 Deny-by-default policy and complete endpoint authorization (IMPLEMENTED, Step 2I)

**Policy.** `SecurityConfig` lists every public endpoint and every protected endpoint by HTTP method and path. Everything else under `/api/**` is refused by a final `denyAll` rule: 401 when nobody is signed in, 403 when someone is (Admin included). There is no "any signed-in user" fallback. Outside `/api` only the single-page app files and the error page are served; they hold no data. **HEAD** is allowed only on a path that has a GET rule, for exactly the same roles as that GET (the single helper `allowGet` writes both rules together); there is no general HEAD rule, so a HEAD on any other path is refused. **OPTIONS** is not listed anywhere and is refused for every role (the application has no CORS configuration, so there is no cross-origin preflight to serve); the earlier behaviour in which OPTIONS on the recommendations route followed that route's rule was replaced by this policy and its test updated.

**Two layers.** The URL rules are the first layer. The services decide again, from the database, through `CurrentUserScopeResolver` (sections 7 to 13), so a rule that is wrong or missing cannot return another department's data, and an Admin-only operation checks for itself.

| Method and route | Admin | Manager | Staff | Anonymous | Department scoping and service check |
|---|---|---|---|---|---|
| POST `/api/auth/login`, POST `/api/auth/email-verification/send`, POST `…/verify`, PUT `/api/auth/credentials/setup`, POST `/api/auth/password-reset/request`, POST `…/confirm` | yes | yes | yes | yes (public) | not applicable |
| GET `/api/profile`, PUT `/api/profile/display-name`, `/phone`, `/preferences`, `/password`, POST and DELETE `/api/profile/picture`, POST `/api/profile/email-change/request`, `/confirm`, GET and PUT `/api/notification-preferences` | yes | yes | yes | 401 | own account only, from the token |
| GET `/api/users`, GET and PUT `/api/users/{id}/manager-department`, GET `…/history`, PATCH `/api/users/{id}/activate`, `/deactivate`, DELETE `/api/users/{id}` | yes | 403 | 403 | 401 | Admin only; the services call `requireAdmin()` |
| GET `/api/staff`, GET `/api/staff/username-availability`, POST `/api/staff`, PUT `/api/staff/{id}`, PATCH `/api/staff/{id}/activate`, `/deactivate` | yes | yes, own department | 403 | 401 | scope from the resolver (section 8.1) |
| DELETE `/api/staff/{id}` | yes | 403 | 403 | 401 | Admin only (`requireAdmin()`) |
| GET `/api/tasks`, GET `/api/assignments` | yes, all | yes, own department | yes, own | 401 | scope from the resolver (sections 10.5, 11.1) |
| POST `/api/tasks`, `/api/assignments`, PUT `/api/tasks/{id}`, `/api/assignments/{id}`, DELETE `/api/tasks/{id}` | yes | yes, own department | 403 | 401 | scope from the resolver |
| GET `/api/workload`, GET `/api/dashboard/summary` | yes, all | yes, own department | yes, own | 401 | scope from the resolver (sections 12.1, 12.2) |
| GET `/api/workload/recommendations` | yes | yes, own department as receiver | 403 | 401 | section 12.3 |
| GET `/api/departments` | full | own in full, others identity only | identity only | 401 | `DepartmentServiceImpl.getAllDepartments` |
| POST `/api/departments`, PUT and DELETE `/api/departments/{code}` | yes | 403 | 403 | 401 | Admin only (`requireAdmin()`) |
| POST `/api/support/assignments` (confirm) | yes | 403 | 403 | 401 | Admin only, also in the service (section 13.2) |
| GET `/api/support/options`, GET `/api/support/assignments`, POST `/api/support/preview`, POST `…/{id}/archive`, POST `…/{id}/end` | yes, all | yes, involving own department | 403 | 401 | sections 13.1, 13.2 |
| GET `/api/test/admin`, `/manager`, `/staff` | role-gated | role-gated | role-gated | 401 | development only; not mapped or reachable in production |
| HEAD on any route above that has a GET | same as that GET | same as that GET | same as that GET | 401 | same rule as the GET |
| every other `/api/**` route or method (OPTIONS, HEAD without a GET, unknown paths) | 403 | 403 | 403 | 401 | denied |

**Centralized authorization design (kept small on purpose).** There is no permission framework. The one mechanism is `CurrentUserScopeResolver`, which already decides, from the database on every call, what the signed-in user may reach (sections 7 and 8). Step 2I added to it:

- `requireAdmin()` and `requireAdmin(message)`: no sign-in 401; a Manager (valid or not) or Staff member 403. Used by `DepartmentServiceImpl` (create, update, delete), `UserServiceImpl`, `UserAccountService`, `ManagerDepartmentServiceImpl` and `StaffServiceImpl.removeStaff`, so every Admin-only operation answers the same way whichever route reaches it and its existing 403 message is kept where tests pin it.
- `currentUsername()`: the acting identity, read from the security context. `UserAccountService` no longer accepts a caller-supplied user name (the controller used to pass one), so its own-account and last-Admin protections cannot be bypassed by a direct call.

`@PreAuthorize` and a permission matrix were considered and rejected: they would add a second mechanism that checks only the role string, not Manager validity or department.

**Who may create a `DepartmentScope`.** A scope is trusted wherever it is passed, so only the resolver creates one. `DepartmentScopeIssuanceTest` fails if any production class outside the `security` package (or any class in it other than the resolver) creates a scope, or if a controller references a scope or the resolver. The workload calculation that takes an already-resolved scope remains the internal `ScopedWorkloadCalculator` seam, used only by the dashboard and guarded by `ScopedWorkloadCalculatorUsageTest`; it is not on `WorkloadService` and no request value can reach it. (The factories stay public because tests build scopes, so this is a test-enforced restriction, not a compile-time one.)

**Department details by role (C1).** `GET /api/departments` resolves the caller's scope. Admin: every field. Valid Manager: every field for their own department; for every other department only `id`, `code`, `name`, `active`, `iconKey`, `colorKey` (the rest are null and are not computed). Staff: the identity of every department only. Invalid Manager 403, no sign-in 401. The Departments page shows a dash instead of 0 for a figure that is not available and does not total figures it cannot see. Verified by `DepartmentVisibilityTest` and `departmentDisplay.test.js`.

**Remaining findings and gaps (see the audit; nothing here is a confirmed exploitable bypass of department isolation).**

1. **R5, email verification (Step 2J) — stages 1 and 2 of the plan implemented; the rest is open.** DONE, stage 1 (no account enumeration): `POST …/send` and `POST …/verify` no longer reveal whether an account exists. A send answers 200 with one fixed message for an unknown, inactive, verified, known, locked, cooling-down or delivery-failed case; every failed verification (unknown or inactive account, no code, wrong, expired, locked, used-up) answers 400 with one fixed message and no attempt count; nothing echoes the identifier; every verify makes one bcrypt comparison whatever the account or the submitted code, and a send that sends nothing hashes once. A delivery failure is not reported to the public caller (logged server-side; no new code is stored, so an earlier valid code is untouched). DONE, stage 2 (rate limiting, lockout, cooldown, concurrency): (a) failed attempts are cumulative in the database (`verification_attempt_count` is no longer reset by a resend); the fifth failure locks verification for 15 minutes (`verification_locked_until`) and clears the active code; while locked every verification is refused without counting and no email is sent; when the lock has passed the count restarts at zero; a successful verification clears the count, the lock and the cooldown. (b) A send within 60 seconds of the previous one (`verification_last_sent_at`) is silently suppressed with the same answer; the cooldown is reserved before the email is attempted and kept if delivery fails. Unknown and inactive identifiers have no row, so they carry no cooldown, and their send is answered identically. (c) Both operations run while the account row is locked (`SELECT … FOR UPDATE`, `UserRepository.findByUsernameForUpdate` and `findByEmailForUpdate`), so parallel guesses cannot exceed the limit, a correct code works once, and parallel sends send one email. (d) What counts as a failed attempt: a wrong, missing, blank or malformed code submitted for an account that has an active, unexpired code. Not counted: an unknown or inactive account, an account with no active code, an expired code (it is cleared on first use and cannot be guessed, so refusing it gives a guesser nothing, and counting it would push a person who typed the right code a little late towards a lockout), and anything submitted while locked. The volume of those requests is limited by the per-client limits. (e) In-memory per-client limits in front of both endpoints (`VerificationSendLimiter`: 3 per client and account, 10 per client, 6 per account; `VerificationAttemptLimiter`: 5, 20 and 30; all per 15 minutes; configurable under `auth.verification-*-rate-limit`), counted before the account is looked up and answered with one fixed 429 and `Retry-After`. Every request that reaches either endpoint is charged exactly once, including one with a missing, blank, non-numeric, too-short, too-long or non-ASCII-digit code, no identifier, or an unreadable body: bean validation was removed from `VerifyCodeRequest` (a validation failure is answered before the controller runs and so could never be charged), the strict six-digit rule is enforced in the service instead, and a malformed code gets the same generic failure as a wrong one and never verifies. The client is the connection's own address; X-Forwarded-For, Forwarded and X-Real-IP are deliberately ignored, because any caller can set them. Schema: migration `V16__verification_lockout.sql` adds the two nullable columns; it is provided but NOT applied (Hibernate creates them in development; apply by hand elsewhere). LIMITATIONS of stage 2: the in-memory limits exist per application instance only (the POC assumes one instance), are cleared by a restart, and are not shared across instances; behind a reverse proxy every user shares the proxy's address, so the per-client limit would apply to everyone until a trusted-proxy configuration is explicitly set up; only the database state (attempts, lockout, cooldown) holds across restarts and instances. A lockout can be used to keep a pending user locked out of verification by repeating wrong guesses (15 minutes at a time); the cumulative count has no time decay, so failures spread over a long time still add up until a success or a lockout expiry. **Still open (later approved stages): response timing** (email dispatch is still synchronous and now also runs while the account row is locked, so a send that reaches SendGrid takes longer than one that does not, and a slow provider holds the row lock; closed by asynchronous dispatch), no explicit SendGrid timeout, and login still fails with a server error if delivery fails (stage 5).
2. No frontend route guards (section 18.3); deliberately out of scope for this backend step.
3. HEAD mirrors GET exactly (same roles, same paths); OPTIONS and any other unlisted method are refused on `/api`. Nothing in the application uses OPTIONS. `SecurityConfigEndpointCoverageTest` checks HEAD against GET for every GET route and every role.
4. The preflight and DB tooling script still describe the schema up to V14 (unchanged here).
4a. **Password-reset limitations after Step 2M.** (i) Email is still sent synchronously, inside the request, while the account row is locked, so a request for a real account takes longer than one for an unknown address (response timing), a slow provider holds the lock, and there is no explicit SendGrid timeout; asynchronous dispatch is a separate, unapproved stage. (ii) A successful resend restarts the attempt count, and using up or completing a code clears the cooldown, so after five wrong guesses a new code can be requested at once; guessing is bounded only by the in-memory limiters (3 requests per client and address, 10 per client, 10 per address per hour; 5, 20 and 30 confirm attempts per 15 minutes), which are per application instance and cleared by a restart. (iii) The address is matched exactly after lower-casing the typed value, so an account whose stored email contains capital letters cannot use the reset (existing behaviour). (iv) The six-digit code is drawn from 100000 to 999999. (v) An email service that is not configured at all answers 500 to every request.
5. **Step 2K audit findings.** DONE in Step 2L: K-01 (sessions end on password change or reset), K-02 (email change needs the current password and the notice cannot be lost), K-05 (Admin accounts are not deletable), K-07 (inactive accounts cannot finish setup or reset). DONE in Step 2M: K-03 (password reset no longer leaks account existence, keeps an earlier valid code when delivery fails, and counts wrong codes durably). STILL OPEN: K-04 (verification send is still accepted for fully onboarded accounts), K-06 (Managers can probe usernames and emails across departments), and the potential risks P-01 to P-09 of the audit (email sent inside database transactions, response timing, token kept in browser storage, and others).

## 20. Data ownership and department isolation

| Resource | Owner or scope key | Staff access | Manager access | Admin access |
|---|---|---|---|---|
| Task | `task.department`; assignee through `assignments` | Own assigned only | Same department | All |
| Assignment | the task's department (Step 2D); the holder's department is not checked; `assignment.staff` identifies the holder | Own only | Assignments of tasks in their own department | All |
| Staff record | `staff.department`; `staff.user` | Own via Profile only | Same department (STAFF role) | All |
| User account | `users.id` | Own via Profile | Staff accounts in the same department via Staff Management | All via Users |
| Support assignment | `home_department_code`, `receiving_department_code`, `staff_id` | Own only | Involving own department | All |
| Manager's department assignment | A single department per Manager (current storage: `staff.department` of the Manager's own Staff record) | None | None (can know their own department; cannot change it) | Assign and change; each change audited [Decisions 16, 17] |
| Manager department change audit record | One record per change: changing Admin, affected Manager, previous department, new department, timestamp | `manager_department_changes` (append-only; section 8.2) | Written by the system in the same transaction as the change [Decision 17.6] | Read by Admin only |
| Notification preference | `user_id` | Own | Own | Own |
| Department | `code` | Read | Read | Manage |

Several Managers may share one department; the department is the scope key, not the individual Manager. [Decision 16]

Schema notes (verified in the migration scripts and a read-only catalog query):
- `tasks.department` is nullable. A task without a department is outside every Manager's scope; who may see and assign such tasks is open (section 26.3).
- `staff.department` is NOT NULL. `users` has no department column.
- No database constraint enforces "Managers and Staff have a department" for pre-existing data (see section 7, one Manager has no staff record). The `staff.user_id` unique constraint does ensure that an account has at most one staff record, so at most one department can be derived per account. No database constraint prevents several Managers from sharing a department (which Decision 16 allows).
- All identifiers are UUIDs; they are not secret and must not be treated as an authorization mechanism.

## 21. Security rules and edge cases

Rules (target):
1. A Manager must never be able to read, change, assign to, or move staff, tasks or assignments of another department, including by guessing UUIDs.
2. A Staff member cannot read another person's record through any endpoint or by changing an id.
3. Ownership for task status is "the caller's staff record is the task's current assignee".
4. A removed or reassigned assignee loses the right to update status immediately.
5. An unverified or pending email never authenticates.
6. The last active Admin cannot be deactivated or deleted.
7. A user cannot deactivate, delete, promote, re-department or change the permissions of themselves.
8. Admin and Manager accounts are never assigned tasks (existing rule, IMPLEMENTED).
9. A deactivated or deleted user is rejected on the next request (existing, IMPLEMENTED, `JwtAuthenticationFilter`; tests exist: `SecurityConfigTest`, `JwtDeactivationIntegrationTest`).
10. Changing availability never cancels or reassigns tasks.
11. Refused requests must not reveal whether a resource exists in another department.
12. A Manager's department assignment can be set or changed only by an Admin. A Manager can never set or change their own or another Manager's department. [Decisions 16, 17.2]
13. A Manager without a valid assignment (active account, linked active Staff record, valid active department) is denied every department-scoped operation (fails closed). It must never fall back to "all departments". [Decisions 16, 17.3]
14. Managers of the same department share that department's scope; sharing a department grants no access to other Managers' accounts. [Decision 16]
15. Only Admin can transfer Staff between departments; a Manager cannot transfer Staff into or out of any department. [Decision 17.1]
16. A Manager's department change applies immediately: the next request after the change is evaluated against the new department, with no cached department in the token or session. [Decision 17.4]
17. A Manager's department change leaves existing tasks, assignments and support records in their original department. [Decision 17.5]
18. Every Manager department change is recorded: who changed it, whose department changed, previous department, new department, timestamp. [Decision 17.6] [Rec: append-only, written in the same transaction as the change.]

Edge cases to settle in implementation:
- Tasks with no department. Decided and implemented for tasks (Decision 18, Step 2C) and for assignments (Decision 20, Step 2D): invisible and inaccessible to Managers, accessible to Admin. For assignments Admin keeps the existing behaviour, so Admin can assign a task with no department to staff of any department. Not decided: whether tasks without a department should be forbidden altogether, and how other modules (workload, dashboard, support) treat them.
- Staff in temporary cross-department support: whose department scope applies while on loan (home or receiving)? Decided for assignments only (Decision 20, Step 2D): the task's (receiving) department decides and the lent holder's home department is not checked. For workload, a temporary interim rule applies (Step 2E, section 12.1): the person stays in the home department's list, and support-loan work is excluded from both Managers' views. Not decided for Staff management, dashboard and support (section 26.3, questions 6, 21 and 22).
- A Manager who is also referenced as a department head.
- A Manager with no Staff record (exists today: 1 of 5). Decided: this Manager is not valid and is denied all department-level access. [Decisions 16, 17.3] How and when they are given a valid assignment is not decided (section 26.3, question 15).
- A Manager whose department is inactive, whose Staff record is inactive, or whose account is inactive. Decided: not valid; all department-level access is denied from the next request. [Decisions 17.3, 17.4] A department that no longer exists also yields no valid department.
- A Manager changed to a different department by an Admin while their old department has open tasks, assignments or support. Decided: the Admin may do it, the new scope applies immediately, and the existing records stay with the original department. [Decisions 16, 17.4, 17.5] Not decided: whether the old department may be left without a valid Manager (question 18), and whether anyone should be warned.
- A transferred Manager's own earlier work: tasks they created in the old department stay there and are no longer in their scope. [Follows from 17.4 and 17.5]
- Staff transferred by an Admin while holding active tasks in the old department: the existing system refuses the move until those tasks are reassigned or completed (`StaffServiceImpl` 171-184). Decision 17 does not change or confirm this rule, so it is documented as existing behaviour only (section 26.3, question 20).
- Staff deactivated while holding tasks (existing staff rules cover active assignments).
- Concurrent updates: existing task and staff locking (`lockByIds`) already serializes assignment and status changes; new endpoints should follow the same lock order. Since Steps 2C and 2D a Manager's task and assignment operations use department-scoped locks (`lockByIdAndDepartment` on tasks and on staff) in the same task-then-staff order, so an out-of-scope record is never locked. Staff update and deactivate lock the row by id before the scope check (section 8.1).

Inconsistent role handling in the current code (verified):
- `DashboardServiceImpl` and the workload endpoint now use `CurrentUserScopeResolver` (Steps 2E and 2F); the role-string check was removed from the dashboard. `WorkloadRecommendationServiceImpl` also uses it (Step 2G-1); the support service does not yet. The bare-name checks (`ADMIN`, `MANAGER`) that `TaskServiceImpl`, `AssignmentServiceImpl` and `StaffServiceImpl` used to contain were removed in Steps 2B-2D; the filter only ever produced `ROLE_*`.

## 22. Existing implementation audit (decision by decision)

| # | Decision | Status | What exists / what is missing |
|---|---|---|---|
| 1 | Availability | PARTIALLY IMPLEMENTED | Admin/Manager can change availability inside the full staff edit (a Manager within their own department only, since Step 2B). Staff cannot. No dedicated endpoint. |
| 2 | Permanent staff deletion | PARTIALLY IMPLEMENTED | Admin-only deletion with inactive and no-history rules: IMPLEMENTED. Manager deactivate/reactivate is limited to their own department's Staff since Step 2B. |
| 3 | Department isolation | IMPLEMENTED | IMPLEMENTED for Staff management (Step 2B, section 8.1), tasks (Step 2C, section 10.5) and assignments (Step 2D, section 11.1): department-scoped lists, 404 outside the department, Manager creation in their own department, no Manager transfers, department-less tasks inaccessible to Managers, invalid Managers fail closed. IMPLEMENTED for workload (Step 2E, section 12.1), with the temporary support-loan limitation. IMPLEMENTED for the dashboard (Step 2F, section 12.2). IMPLEMENTED for the recommendations endpoint (Step 2G-1, section 12.3). IMPLEMENTED for the support APIs (Steps 2G-2 and 2G-3, sections 13.1 and 13.2; frontend Step 2G-4). One Manager has no Staff record (see Decision 16) and is refused in the enforced modules. |
| 4 | Task status | PARTIALLY IMPLEMENTED | Admin (any task) and Manager (own department's tasks, department-scoped since Step 2C) can change status by full edit: IMPLEMENTED. No Staff status path and no Staff transition rules: NOT IMPLEMENTED. |
| 5 | Workload | IMPLEMENTED (with the temporary support-loan limitation) | `GET /api/workload` is IMPLEMENTED (Step 2E, section 12.1): Admin global, Manager own department only, Staff own row. Temporary limitation: support-loan work is excluded from both Managers' views. The dashboard's workload figures are IMPLEMENTED (Step 2F, section 12.2). The recommendations endpoint is IMPLEMENTED (Step 2G-1, section 12.3). The support APIs are IMPLEMENTED (Steps 2G-2 and 2G-3, sections 13.1 and 13.2). |
| 6 | Availability with active tasks | NOT IMPLEMENTED | No Staff availability change; no active-task block; no override warning. Availability changes do not auto-cancel tasks (the staff edit only sets the field), but this was not tested at runtime: NEEDS VERIFICATION. |
| 7 | Support assignments | PARTIALLY IMPLEMENTED | Admin and Manager can preview, confirm, end and archive; no department check; Staff cannot see own support; no "request/approval" state. |
| 8 | Login email changes | IMPLEMENTED | Self-service flow compliant; the Admin/Manager staff edit no longer changes it. |
| 9 | Account creation | PARTIALLY IMPLEMENTED | Role rules, verification flow, department required, no self-registration: IMPLEMENTED. Manager limited to their own department (Step 2B). Form also demands a department for Admin accounts. |
| 10 | Users page | IMPLEMENTED | Backend rule, sidebar, page guard. |
| 11 | Permanent user deletion | PARTIALLY IMPLEMENTED | Admin-only, inactive-only, history refusals, last-Admin protection: IMPLEMENTED. Inactive Admin accounts are deletable. |
| 12 | Unassign / reassign | PARTIALLY IMPLEMENTED | Reassign exists for Admin/Manager and is department-scoped for Managers since Step 2D. Unassign, audit record and IN_PROGRESS warning do not exist. |
| 13 | Notification preferences | PARTIALLY IMPLEMENTED | Own preferences and locked Security exist; email only; category list differs; no code sends the optional notifications. |
| 14 | System settings | NOT IMPLEMENTED | Personal preferences only. |
| 15 | Profile editing | PARTIALLY IMPLEMENTED | Own profile, password, picture, email verification: IMPLEMENTED. Manager editing Staff profiles in own department and Admin managing all profiles exist only through the staff edit (department-scoped for Managers since Step 2B). |
| 16 | Manager department assignment | IMPLEMENTED (with the open items below) | Several Managers can share a department (4 valid Managers already span 3 departments): IMPLEMENTED. One department per Manager, through the single Staff record (`staff.user_id` unique): IMPLEMENTED; no second department field exists on `User`. Admin-only assignment or change: IMPLEMENTED by the dedicated operation and by the staff edit (Step 2H, section 8.2). Department-limited Manager access and the fail-closed rule: IMPLEMENTED for Staff management, tasks, assignments, workload, dashboard, recommendations and the support APIs. Open: 1 of 5 existing Managers has no Staff record and so no department (section 26.3, question 15); a department left without a valid Manager (question 18). |
| 17.1 | Only Admin transfers Staff between departments | IMPLEMENTED (Step 2B) | Transfers happen through `PUT /api/staff/{id}`; a Manager's request must keep the staff member's department (403 otherwise). Existing safeguards (target department must be active; move refused while the staff member has active tasks in another department) are IMPLEMENTED. |
| 17.2 | Only Admin assigns or changes a Manager's department | IMPLEMENTED | The dedicated operation and the staff edit are both Admin-only for Manager records (Step 2H, section 8.2); a Manager cannot change any department. |
| 17.3 | Valid Manager = active account + linked active Staff record + valid active department; otherwise deny all department-level access | IMPLEMENTED | `CurrentUserScopeResolver` implements the full validity check and is enforced for Staff management, tasks, assignments and the workload endpoint, the dashboard and the recommendations endpoint (Steps 2B-2G-1) and the support APIs (Steps 2G-2 and 2G-3). Read-only count, re-checked 2026-11-10: 4 of 5 existing Managers are valid, 1 (no Staff record) is not. |
| 17.4 | New department scope applies on the next request | PARTIALLY IMPLEMENTED | The scope resolver reads the Manager's records from the database on every call and nothing is cached in the token, so a department change applies on the next request in the enforced modules. `StaffDepartmentIsolationTest` covers this for Staff management (a Manager whose department or Staff record changes). Not applicable yet to the support APIs. |
| 17.5 | Existing tasks, assignments and support stay with their original department after a Manager transfer | NEEDS VERIFICATION | By reading the code, changing a Manager's Staff record department touches no task, assignment or support row, and tasks and support carry their own department codes, so nothing moves automatically. This was not exercised at runtime. |
| 17.6 | Audit trail for Manager department changes (who, whose, previous, new, timestamp) | IMPLEMENTED | Table `manager_department_changes`, written in the same transaction as the change by the dedicated operation and by an Admin staff edit that moves a Manager (Step 2H, section 8.2). Flyway `V15__manager_department_changes.sql` is provided but not applied. |

Test coverage observed (NEEDS VERIFICATION of completeness): tests exist for JWT validity and deactivation, user account rules, notification preferences, profile contact, assignment forbidden cases and production configuration, plus the department-isolation suites for the scope resolver (Step 2A), Staff management (Step 2B), tasks (Step 2C) and assignments (Step 2D) listed in section 25. No test calls every endpoint as each role and department, and none covers support isolation.

## 23. Missing or incomplete functionality

1. Caller-department resolution and enforcement for Manager (Decisions 3, 16, 17), including the validity check (active account, linked active Staff record, valid active department) and the fail-closed rule (Decision 17.3). Done for the resolver (Step 2A), Staff management (Step 2B, section 8.1), tasks (Step 2C, section 10.5) and assignments (Step 2D, section 11.1); Done for the workload endpoint (Step 2E, section 12.1), the dashboard (Step 2F, section 12.2) and the recommendations endpoint (Step 2G-1, section 12.3); Done for the support read paths (Step 2G-2, section 13.1) and write paths (Step 2G-3, section 13.2); the department-isolation list is complete for the modules in scope (row 3 of section 22).
2. A valid assignment for every Manager (one existing Manager has no Staff record; repair not decided, section 26.3, question 15). The dedicated Admin-only operation and its audit record are IMPLEMENTED (Step 2H, section 8.2).
3. Staff task status endpoint, ownership check and transition rules (Decision 4).
4. Staff availability endpoint with the active-task block and the override warning (Decisions 1, 6).
5. Task unassignment, its audit record, return-to-pool behaviour and the IN_PROGRESS warning (Decision 12).
6. Staff view of their own support details (Decision 7) and a defined request/approval workflow.
7. (Done in the login-email stage.) Verified login-email changes when an Admin or Manager edits a staff member (Decision 8).
8. Prevent deletion of Admin accounts through the normal UI and API (Decision 11).
9. ~~Admin-only department management~~ Done in Step 1 (requirements PDF; Decision 3 context): ADMIN-only on the backend and in the Departments page; Manager and Staff can only view.
10. Global system settings (Decision 14).
11. In-app notification channel, the decision's optional categories, and the actual sending of optional notifications (Decision 13).
12. Route-level guard and role-aware sidebar (section 18).
13. ~~Deny-by-default final rule and centralized role helper~~ IMPLEMENTED (Step 2I, section 19.1): `denyAll` after explicit per-method rules, and `CurrentUserScopeResolver.requireAdmin()`. Method-level security (`@PreAuthorize`) was considered and deliberately not adopted. Still open: the R5 email-verification findings (section 19.1) and frontend route guards (section 18.3).
14. ~~An audit record for every Manager department change~~ IMPLEMENTED (Step 2H, section 8.2). Retention of the audit records is not decided.

## 24. Risks and recommendations

| Risk | Impact | Recommendation |
|---|---|---|
| Default-allow final rule | A new endpoint was open to STAFF until a rule was added | IMPLEMENTED (Step 2I): the final `/api/**` rule is `denyAll`, every endpoint and method is listed, and `SecurityConfigEndpointCoverageTest` fails when a mapped route has no entry. |
| Department isolation incomplete | Department isolation is implemented for every module in scope (Steps 2B-2G-3); the open items are the unresolved support workflow decisions and the frontend integration of the Manager support panel | Implement section 7 in services (query-level), with tests per endpoint. Staff management (Step 2B), tasks (Step 2C), assignments (Step 2D) the workload endpoint (Step 2E), the dashboard (Step 2F) and the recommendations endpoint (Step 2G-1) are done; the support read paths (Step 2G-2) and write paths (Step 2G-3) are done. |
| No method-level security | One missing URL rule exposes a service method | Enable method security as a second layer; use one authorization helper. |
| Manager-driven email change without verification | (Closed in the login-email stage) A Manager could redirect a staff member's login email and then use password reset | The staff update refuses a different email; only the verified self-service flow changes it. |
| Manager could move staff between departments through the edit form (resolved in Step 2B) | Would defeat isolation | Only Admin may transfer Staff (Decision 17.1); a Manager's department change is refused with 403. |
| Manager with no valid assignment (1 of 5 today: no Staff record) | Under Decisions 16 and 17.3 they fail closed and have no department-level access; a fail-open implementation would expose every department | The repair is a separate, undecided data step (question 15); the implementation must fail closed. |
| Any role able to change a Manager's department | A Manager could widen or move their own scope | Only an Admin may assign or change it (Decisions 16, 17.2). Since Step 2B a Manager cannot change any department through the staff edit and is refused for Manager accounts; Admin is the only path, through the dedicated operation or the staff edit, and either way an audit record is written (Step 2H, section 8.2). |
| Manager department changes are not recorded | A scope change cannot be reconstructed or attributed | IMPLEMENTED (Step 2H): an append-only record in the same transaction as the change, by the dedicated operation and by an Admin staff edit that moves a Manager. |
| Old department left without a valid Manager after a transfer | Only Admin can then act on that department's work | Not decided (question 18). [Rec] warn the Admin before completing the change. |
| Department or Staff-record deactivation silently removes Manager access | A whole department may lose its Managers at once | Consequence of Decision 17.3; [Rec] show the effect to the Admin before deactivating a department that has Managers. |
| Admin accounts deletable | Conflicts with Decision 11 | Refuse in service and hide in UI. |
| Frontend role from `localStorage` | Editable by the user, so UI only | Keep UI as convenience only; never rely on it. |
| Inconsistent role strings | Easy to introduce a wrong check | One helper, one naming scheme. |
| Stored notification preferences with no sender | Users may believe they receive alerts | Document it; implement sending or hide the feature. |

## 25. RBAC testing strategy

Principles: every row of the matrix in section 6 and every endpoint in section 19 gets at least one positive and one negative test, run as ADMIN, a MANAGER of each of two departments, a STAFF member, and an unauthenticated caller. Tests call the HTTP API, not the services, so the real security chain runs. Use at least two departments, and at least two Managers in one department plus one Manager in another, so both isolation between departments and Managers sharing a department can be tested [Decision 16].

| Area | Positive scenarios | Negative scenarios |
|---|---|---|
| Admin across departments | Admin reads, creates, edits, assigns and deletes in Department A and B | Admin cannot delete self, delete the last active Admin, or delete an Admin account |
| Manager in own department | Manager lists, edits, assigns, reassigns, unassigns, updates status and views workload in own department | Same actions on Department B by id return 403 or 404 and change nothing; lists never include Department B |
| Manager restrictions | Create Staff in own department | Create Staff in another department; create Manager or Admin; edit or delete Manager/Admin accounts; manage departments; open Users; change staff department; permanent-delete staff |
| Staff own records | Read own tasks, assignments, workload, dashboard, profile; update own task status ASSIGNED to IN_PROGRESS to COMPLETED | Read another person's task or workload by id; update status of a task assigned to someone else |
| Staff transitions | ASSIGNED to IN_PROGRESS; IN_PROGRESS to COMPLETED | ASSIGNED to COMPLETED; any to CANCELLED; COMPLETED to anything; PENDING to anything; update a task after it was reassigned |
| Unauthorized API calls | Valid role calls its endpoints | No token: 401; deactivated user: rejected; STAFF on every ADMIN/MANAGER endpoint: 403; unknown `/api/**` path with a STAFF token: refused (deny-by-default) |
| Availability | Staff mark themselves unavailable with no active task; Manager/Admin override with the warning acknowledged | Staff mark unavailable with an ASSIGNED or IN_PROGRESS task; Manager overrides outside own department; availability change leaves the tasks unchanged |
| Manager department assignment [Decisions 16, 17.2, 17.4] | Admin assigns a department to a Manager and changes it later; two Managers of the same department both see and act on that department's staff, tasks, assignments and workload; after an Admin changes a Manager's department, the very next request from that Manager is scoped to the new department | A Manager changes their own department; a Manager changes another Manager's department; a Manager or Staff calls the assign or change operation; assigning a department that does not exist or is inactive is refused; a Manager gets no access to another Manager's account because they share a department |
| Manager validity [Decisions 16, 17.3] | A Manager with an active account, a linked active Staff record and an active department has department access; the Manager can still sign in and use own profile, notification preferences and personal preferences even when invalid | For each failing condition separately (inactive account, no Staff record, inactive Staff record, inactive department, department that no longer exists): every department-scoped read and write is refused (staff, tasks, assignments, workload, dashboard department data, support); lists return nothing rather than all departments; the existing Manager without a Staff record is covered; access is lost on the very next request after the department or Staff record is deactivated, and restored after it is reactivated |
| Staff transfers [Decision 17.1] | Admin transfers a staff member to another active department | A Manager transfers staff into or out of a department (including their own); Staff transfer themselves; transfer to an inactive department; existing rule: transfer while the staff member has active tasks in another department is refused |
| Work after a Manager transfer [Decision 17.5] | Tasks, assignments and support records created before the transfer keep their original department codes and remain visible to Managers of that department and to Admin | The transferred Manager cannot read or change those records after the transfer; no record is moved or re-assigned automatically |
| Manager department audit [Decision 17.6] | Each change writes exactly one record with the changing Admin, the affected Manager, the previous department, the new department and a timestamp; a failed change writes no record | A Manager or Staff cannot create, edit, read or delete audit records; an unchanged department writes no record (verified by `ManagerDepartmentAssignmentTest` and `ManagerDepartmentRollbackTest`) |
| Account creation | Admin creates Admin, Manager (with department) and Staff; Manager creates Staff in own department; new account must verify email and set password before login | Self-registration endpoint absent; Manager or Staff creates other roles; account with no department (Manager or Staff) |
| Account deletion | Admin deletes an inactive STAFF or MANAGER with no history | Delete an active account; delete an account with history; delete an Admin account; delete self; Manager or Staff delete anyone |
| Email verification | New email pending, old email still logs in, new email logs in after confirmation, notice sent | Pending email cannot log in; wrong or expired code; Manager edit of a staff email follows the same flow |
| Support assignments | Admin creates, ends and views all; Manager acts on support involving own department; Staff views only own support | Manager acts on support not involving own department; Staff calls create, end or list-all |
| Last active Admin | Deactivate or delete an Admin when another active Admin exists | Deactivate or delete the last active Admin |
| Frontend | Sidebar and buttons per section 18; route guard redirects | Direct URL as the wrong role shows the no-access page; editing `localStorage` role changes visibility only, backend still refuses |

Implemented for tasks (Step 2C): `TaskDepartmentIsolationTest` (two departments, two Managers sharing one, a Manager in the other, a department-less task, invalid Managers, Staff, direct service calls, and a check that refused operations change no task, assignment or support row) and `TaskServiceImplScopeTest` (repository use per scope). Mutation checks were run on six critical rules: Manager list loads everything, Manager locks any task, any department allowed on create, no default department, moving a department allowed, and Staff allowed to manage.

Implemented for the scope resolver (Step 2A): `CurrentUserScopeResolverTest` (18 tests), `CurrentUserScopeResolverDatabaseTest` (12) and `CurrentUserScopeResolverTransactionTest` (3).

Implemented for Staff management (Step 2B): `StaffDepartmentIsolationTest` (19 tests: two departments, two Managers sharing one, invalid Managers, Staff, direct service calls, identical 404 for out-of-scope and nonexistent ids, no change after refusals) and `StaffRemovalAuthorizationTest` (12 tests), plus the existing staff tests updated to use valid Manager fixtures.

Implemented for assignments (Step 2D): `AssignmentDepartmentIsolationTest` (21 tests, real security chain and H2: a Manager's list, two Managers sharing a department, create and reassign inside and outside the department, own-department task to another department's Staff, tasks with no department, identical 404 for out-of-scope and nonexistent records, the four invalid-Manager cases, Staff, unauthenticated, Admin global, support-loan visibility and reassignment, no change after refusals) and `AssignmentServiceImplScopeTest` (9 mock tests: repository and lock use per scope, the task-then-Staff lock order, and no lock for an out-of-scope task or Staff member). `AssignmentConcurrencyTest` gained three Manager-path race tests (one Staff member and six tasks; a Manager assigning while an Admin moves the task to another department; the same for reassignment). `AssignmentControllerTest` uses valid Manager fixtures. Mutation checks were run on six critical rules, and each made at least one test fail: a Manager's create finds any task, a Manager's create accepts any department's Staff member, a Manager's list returns every assignment, Staff allowed to create or reassign, reassign ignores the task's department, reassign accepts a target in any department. Historical result: the full backend suite passed with 1,069 tests, 0 failures and 0 errors, as reported at the completion of Step 2D on November 4, 2026. The suite was not rerun for the documentation updates (version 1.4 and its corrections), so this is not a current result; the per-suite test counts in this document are likewise as of each step's completion. These suites do not cover dashboard, recommendations or support isolation, which are not implemented.

Implemented for workload (Step 2E): `WorkloadDepartmentIsolationTest` (16 tests), `WorkloadServiceImplScopeTest` (5), (`DashboardWorkloadSourceUsageTest`, 2 tests, was deleted in Step 2F with the interface it guarded) and the updated `WorkloadControllerTest`, `WorkloadEligibilityTest`, `SupportAssignmentServiceTest` and `SupportEndServiceTest` (the last three sign in as Admin where they read the global view). Mutation checks were run on eight critical rules (section 12.1), and each made at least one test fail. Historical result: the full backend suite passed with 1,092 tests, 0 failures, 0 errors and 0 skipped when this section was written (after the Step 2E review corrections); like the other counts in this section, it is a historical result and not a current one. These suites do not cover dashboard, recommendations or support isolation.

Existing tests to extend rather than replace: `SecurityConfigTest`, `UserControllerTest`, `UserAccountManagementTest`, `AssignmentControllerTest`, `NotificationPreferenceControllerTest`.

## 26. Open questions and assumptions

### 26.1 Confirmed decisions (treated as requirements)

Decisions 1-18 as supplied by the project owner, plus the documentation decision numbers 19 and 20 (numbering note below): availability, permanent staff deletion, department isolation, task status, workload visibility, availability with active tasks, support assignments, login email changes, account creation, Users page, permanent user deletion, task unassignment and reassignment, notification preferences, system settings, profile editing, Manager department assignment, the six rules of Decision 17, task department isolation (Decision 18), Staff department isolation (Decision 19, documentation number) and assignment department isolation (Decision 20, documentation number).

**Decision 16 (Manager department assignment), as confirmed:**
- Multiple Managers can belong to the same department.
- Each Manager must belong to exactly one department.
- Admin can assign or change a Manager's department.
- Managers can access and manage data only within their assigned department.
- Managers cannot access another department's data.
- A Manager without a valid department assignment must fail closed and must not receive department-level access.

Where it appears in this document: sections 4, 5, 6.2, 6.3, 7, 8, 9, 19, 20, 21, 22, 23, 24, 25 and 26.

**Decision 17 (rules that settle the assumptions attached to Decision 16), as confirmed by the project owner:**
- 17.1 Only Admin can transfer Staff between departments. Managers manage Staff only within their assigned department.
- 17.2 Only Admin can assign or change a Manager's department.
- 17.3 A valid Manager assignment requires an active account, a linked active Staff record and a valid active department. If any condition fails, all department-level access is denied.
- 17.4 A Manager's new department scope applies immediately on subsequent requests.
- 17.5 Existing tasks, assignments and support records remain associated with their original department. They are not transferred automatically because a Manager's department changed.
- 17.6 Record who changed the department, whose department was changed, the previous department, the new department and the timestamp.

Decision 17 was given as "sensible security and business rules" to resolve the assumptions; this document treats it as confirmed. Anything not stated in it is not decided, including the items in section 26.3.

**Decision 18 (Task department isolation, Step 2C), as approved by the project owner and now implemented:**
- Admin has global access to tasks across all departments.
- A Manager accesses only tasks of their own department. The Manager's scope comes from `CurrentUserScopeResolver` and `DepartmentScope`, never from JWT claims or client-supplied department values.
- Staff keep their existing task visibility and permissions unchanged.
- An invalid Manager gets 403; an unauthenticated request gets 401.
- A Manager accessing a task outside their department gets 404, identical to a nonexistent task.
- A Manager cannot move a task to another department or clear its department. An Admin still can.
- A Manager creating a task with a blank or missing department automatically uses their own department; explicitly requesting another department gets 403.
- Tasks without a department are invisible and inaccessible to Managers; Admin retains access.
- Task scope is determined by `Task.department`, not by the department of the assigned Staff member.
- No Staff status-update endpoint is introduced in this step.

**Numbering note for Decisions 19 and 20.** Decision 19 and Decision 20 are documentation decision numbers introduced in version 1.4 of this document to record the rules the project owner approved for the Step 2B (Staff) and Step 2D (Assignments) implementations. The earlier approvals did not carry these numbers. They continue the sequence after Decision 18 and do not conflict with any existing decision number. Their content is the approved implementation rules; nothing beyond those rules is decided by them.

**Decision 19 (documentation number; Staff department isolation, Step 2B): the rules approved for Step 2B, now implemented:**
- Admin has global Staff access.
- A Manager can view and manage operational Staff accounts only within their own department. The Manager's scope comes from `CurrentUserScopeResolver`, never from request values.
- Staff created by a Manager are automatically assigned to the Manager's department; any other department is refused (403).
- Managers cannot transfer Staff between departments (403). Only Admin can.
- A Staff record outside the Manager's department gets 404, identical to a nonexistent record.
- Staff and invalid Managers cannot perform Manager-level Staff management operations (403; no sign-in 401).
- Existing business rules and own-profile behaviour are unchanged. (Existing, not part of this decision: a Manager cannot manage Manager or Admin accounts, 403 in their own department.)

**Decision 20 (documentation number; Assignment department isolation, Step 2D): the rules approved for Step 2D, now implemented:**
- Admin has global access to assignments, including tasks with no department (existing behaviour kept).
- A Manager can access and manage assignments only when the task belongs to their own department. The scope comes from `CurrentUserScopeResolver`.
- The target Staff member must also belong to the Manager's department, for both create and reassign.
- Staff keep their existing own-assignment listing and cannot create or reassign assignments.
- An invalid Manager gets 403; an unauthenticated request gets 401.
- Tasks with no department are hidden from Managers and cannot be assigned or reassigned by them.
- A record outside the Manager's scope (task, assignment or target Staff member) gets 404, identical to a nonexistent one.
- Support-loan assignments are scoped by the task's department: the receiving department's Manager can access assignments for tasks in their department even when the current holder belongs to another department.
- The current holder's home department is not an authorization condition during reassignment; the task's department and the new target's department are checked.
- Existing business rules, support lifecycle behaviour, Admin permissions, the concurrency protections and the task-then-Staff lock order are preserved.

### 26.2 Relationship to the requirements PDF

- The PDF's responsibilities table is garbled in text extraction. This document reads it as: Manage Managers (Admin Yes, Manager No, Staff No); Manage Staff (Yes, Yes, No); Manage Departments (Yes, No, No); Create Tasks (Yes, Yes, No); Assign/Reassign (Yes, Yes, No); View Workload (Yes, Yes, Own); Update Task Status (Yes, Optional, Yes); Update Availability (Yes, Optional, Yes). The "Optional" Manager entries are superseded by Decisions 1 and 4. This reading is an inference and should be confirmed.
- Conflicts between the PDF and the confirmed decisions: the PDF has no department isolation for Managers, no Staff transition rules, no support assignment, unassignment, notification or system-settings rules. The decisions add to the PDF; where they differ, this document follows the decisions.
- The PDF lists `POST /api/auth/register`. Decision 9 keeps self-registration disabled, so that endpoint must stay absent.
- The PDF's API list is not the implemented API (differences listed in section 19).
- The PDF says nothing about how a Manager is tied to a department, whether Managers can share one, or who assigns it. Decisions 16 and 17 supply this; it is not derived from the PDF.

### 26.3 Unresolved questions (not invented here)

1. **Support approval sequence (Decision 7).** Which Manager approves (lending, receiving, both)? Does Admin approval substitute? What is a "request" as a system state? Who may end support? Is the lent staff member notified?
2. ~~Tasks without a department.~~ **RESOLVED for tasks by Decision 18 (Step 2C):** invisible and inaccessible to Managers, accessible to Admin. Decision 20 (Step 2D) applies the same rule to assignments. Still not decided: whether tasks without a department should be forbidden altogether (Admin can today assign one to staff of any department), and how the dashboard and support treat them. (Workload, Step 2E: such assignments are excluded from a Manager's workload figures, section 12.1.)
3. **Manager department storage (confirmation only).** Decision 17.3 requires a linked active Staff record, which fits using that record's department as the Manager's department. Decision 17 does not state outright that this is the formal assignment, or that no separate field will be added. Confirm.
4. ~~Open items when an Admin changes a Manager's department.~~ **RESOLVED by Decision 17.5:** existing tasks, assignments and support stay with their original department. (The related question of an old department left without a Manager is open: question 18.)
5. **Departments list visibility.** **RESOLVED (Step 2I, project owner):** Admin sees every department in full; a Manager sees their own department in full and every other department as basic identity only; Staff see basic identity only (section 19.1).
6. **Staff on loan.** While on cross-department support, do Manager scopes follow the home or receiving department? **Decided for assignments only (Decision 20, Step 2D):** the task's (receiving) department decides and the home department is not checked. For workload, only a temporary interim rule exists (Step 2E, section 12.1): the person stays in the home department's list and support-loan work is excluded from both Managers' views; it is not a final decision. Still not decided for Staff management, dashboard and support.
7. **Unassign status.** Does an unassigned task return to PENDING? Does support on it end?
8. **Optional notification categories.** Exact list and defaults for assignment, reassignment, completion, availability changes and support updates; whether an "account" mandatory category exists separately from SECURITY; whether in-app notifications need a notification feed.
9. **Global system settings.** Which settings exist and where they are stored.
10. **Admin department.** Decision 9 requires departments for Manager and Staff only; should the creation form stop requiring one for Admin accounts?
11. **Manager "view staff" detail.** Whether Managers see Admin/Manager accounts in lists (currently hidden).
12. **Staff editing own name.** "Basic profile" is documented as display name, phone, picture, password and verified email. The staff record's full name is managed by Admin/Manager; confirm that.
13. **Audit trail scope for other actions.** Decision 17.6 covers Manager department changes. "Who unassigned and when" is covered by Decision 12. Whether other privileged actions (deletion, availability override, support approval, Staff department transfers) are also recorded is not decided.
14. ~~What "valid department assignment" means.~~ **RESOLVED by Decision 17.3:** active account, linked active Staff record and valid active department. An inactive department is therefore not valid, and a deactivated Staff record makes the Manager invalid.
15. **Repairing the existing Manager without a Staff record (still open; Step 2H changed no data).** One of the 5 current Managers has no Staff record, so under Decision 17.3 they are not valid. Decision 17 does not say how or when they obtain a valid assignment (for example, who creates the Staff record, with which department and Staff fields). Not decided; no data was changed.
16. ~~Can a Manager transfer staff out of their own department?~~ **RESOLVED by Decision 17.1:** no. Only Admin transfers Staff between departments.
17. ~~Dedicated operation for assigning or changing a Manager's department.~~ **RESOLVED (Step 2H, project owner's instruction):** a dedicated Admin-only operation, in addition to the existing staff edit, which is kept and audited.
18. **Old department without a valid Manager.** After an Admin moves a Manager, or a department or Staff record is deactivated, a department can be left with no valid Manager. Should the system block, warn or allow it? Not decided.
19. **Who may read the Manager department change audit trail.** **PARTLY RESOLVED (Step 2H):** Admin only; a Manager cannot read their own or another Manager's history, and Staff cannot read it. Not decided: retention, and whether the history is exposed anywhere beyond the Admin Users screen.
20. **Staff transfer rules beyond "Admin only".** Existing behaviour refuses a transfer while the staff member has active tasks in another department. Decision 17.1 neither confirms nor changes that rule, and does not say whether Staff transfers are audited.

21. **Lending department's view of lent staff.** Under Decision 20 the lending (home) department's Manager does not see assignments of their lent staff on another department's tasks, and cannot reassign them. Whether that Manager should be able to see which of their staff are on loan and where, and how this fits the approval sequence in question 1, is not decided.

22. **Cross-department workload accounting (Support isolation step).** Step 2E temporarily excludes support-loan work from both Managers' workload views. Not decided: whether the receiving department's Manager should see the borrowed person and their borrowed work, whether the home department's Manager should see that their person is on loan and where, whose counts and percentages the borrowed minutes belong to, and how this fits the approval sequence in question 1. Related: the dashboard (Step 2F) and the recommendations endpoint (Step 2G-1) are now scoped; the support APIs are not (approved policy in section 12.3).

### 26.4 Assumptions

- [Current implementation; confirmation pending, question 3] A Manager's department is today derived from the department on their linked Staff record. Decision 17.3 is consistent with that but does not state it outright.
- [Resolved] Staff transfers are Admin-only: Decision 17.1 (formerly an assumption).
- [Resolved] An invalid Manager assignment fails closed, and "valid" is defined by Decision 17.3 (formerly an assumption).
- "Active tasks" for the availability block means tasks assigned to the staff member in ASSIGNED or IN_PROGRESS.
- Admin accounts do not need a department.
- The current assignment rules (no assignment to unavailable, inactive, or privileged staff) stay as they are.
- Role names stay ADMIN, MANAGER and STAFF.
