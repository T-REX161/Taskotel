import React, { useState, useEffect, useMemo, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { PlusIcon, SearchIcon, UsersIcon } from '../components/icons/Icons';
import {
  getUsers,
  getStaff,
  getWorkload,
  getUserRole,
  getUsername,
  activateStaff,
  deactivateStaff,
  removeStaff,
} from '../services/api';
import UsersTable from '../components/users/UsersTable';
import TablePagination from '../components/common/TablePagination';
import {
  ROLE_OPTIONS,
  STATUS_OPTIONS,
  USERS_PER_PAGE,
  filterUsers,
  sortUsers,
  indexStaffByUserId,
  isCurrentUser,
} from '../components/users/userListUtils';
import { getPaginationState } from '../utils/assignmentUtils';
// Accounts are created and managed through the existing staff flows; these are the same dialogs the Staff page uses
import AddStaffModal from '../components/staff/AddStaffModal';
import EditStaffModal from '../components/staff/EditStaffModal';
import StaffDetailsModal from '../components/staff/StaffDetailsModal';
import DeactivateStaffModal from '../components/staff/DeactivateStaffModal';
import RemoveStaffModal from '../components/staff/RemoveStaffModal';

const SUCCESS_MESSAGE_MS = 6000;

function ChevronDownIcon({ className }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="m6 9 6 6 6-6" />
    </svg>
  );
}

function FilterSelect({ label, value, onChange, options }) {
  return (
    <div className="relative">
      <select
        aria-label={label}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="w-full h-11 pl-4 pr-10 appearance-none bg-white border border-gray-200 rounded-xl text-sm text-gray-800 cursor-pointer focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500 transition-colors"
      >
        {options.map((option) => (
          <option key={option.value || 'all'} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      <ChevronDownIcon className="w-4 h-4 text-gray-500 absolute right-3.5 top-1/2 -translate-y-1/2 pointer-events-none" />
    </div>
  );
}

const isRedirect = (err) => Boolean(err?.message && err.message.includes('Redirecting to login'));

export default function UsersPage() {
  const navigate = useNavigate();
  const userRole = getUserRole();
  const isAdmin = userRole === 'ADMIN';
  const currentUsername = getUsername();

  const [users, setUsers] = useState([]);
  const [staffList, setStaffList] = useState([]);
  const [staffUnavailable, setStaffUnavailable] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [successMessage, setSuccessMessage] = useState('');
  const [actionError, setActionError] = useState('');

  // Filters (combined: search AND role AND status)
  const [query, setQuery] = useState('');
  const [roleFilter, setRoleFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(USERS_PER_PAGE);

  // Dialogs
  const [isAddOpen, setIsAddOpen] = useState(false);
  const [viewingStaff, setViewingStaff] = useState(null);
  const [editing, setEditing] = useState(null); // { staff, user }
  const [deactivating, setDeactivating] = useState(null); // { staff, user }
  const [isDeactivating, setIsDeactivating] = useState(false);
  const [deactivateError, setDeactivateError] = useState('');
  const [removing, setRemoving] = useState(null); // { staff, user }
  const [isRemoving, setIsRemoving] = useState(false);
  const [removeError, setRemoveError] = useState('');

  // Users from /api/users; staff records (with workload, as on the Staff page) to manage the linked accounts
  const fetchUsers = useCallback(async (showLoading = true) => {
    if (showLoading) setLoading(true);
    setError(null);
    try {
      const [userData, staffData, workloadData] = await Promise.all([
        getUsers(),
        getStaff().catch((err) => (isRedirect(err) ? Promise.reject(err) : null)),
        getWorkload().catch(() => []),
      ]);
      const workloadByStaffId = new Map(
        (Array.isArray(workloadData) ? workloadData : []).filter((w) => w && w.staffId).map((w) => [String(w.staffId), w])
      );
      const staffWithWorkload = (Array.isArray(staffData) ? staffData : []).map((staff) => {
        const wl = staff.id ? workloadByStaffId.get(String(staff.id)) : null;
        return {
          ...staff,
          activeTaskCount: wl?.activeTaskCount ?? 0,
          workloadMinutes: wl?.totalEstimatedDuration ?? 0,
          workloadStatus: wl?.workloadStatus ?? 'LOW',
        };
      });
      setUsers(Array.isArray(userData) ? userData : []);
      setStaffList(staffWithWorkload);
      setStaffUnavailable(staffData === null);
    } catch (err) {
      if (isRedirect(err)) {
        navigate('/login');
        return;
      }
      setError(err.message || 'Failed to load users');
    } finally {
      if (showLoading) setLoading(false);
    }
  }, [navigate]);

  useEffect(() => {
    if (isAdmin) fetchUsers();
  }, [isAdmin, fetchUsers]);

  // The success message goes away by itself
  useEffect(() => {
    if (!successMessage) return undefined;
    const timer = setTimeout(() => setSuccessMessage(''), SUCCESS_MESSAGE_MS);
    return () => clearTimeout(timer);
  }, [successMessage]);

  const staffByUserId = useMemo(() => indexStaffByUserId(staffList), [staffList]);
  const visibleUsers = useMemo(
    () => sortUsers(filterUsers(users, { query, role: roleFilter, status: statusFilter })),
    [users, query, roleFilter, statusFilter]
  );
  const hasFilters = Boolean(query.trim() || roleFilter || statusFilter);

  // Pagination: pages of the chosen size (10 by default), cut from the searched and filtered list
  // (so pages never skip or repeat a user). A new search, filter or page size starts again at page 1.
  useEffect(() => {
    setCurrentPage(1);
  }, [query, roleFilter, statusFilter, pageSize]);

  const pagination = useMemo(
    () => getPaginationState({ totalItems: visibleUsers.length, currentPage, pageSize }),
    [visibleUsers.length, currentPage, pageSize]
  );

  // Keep the stored page valid when the list shrinks, e.g. after a removal on the last page
  // (the render already uses the clamped page). Built on the latest value, so a reset to page 1
  // in the same render (new search, filter or page size) is kept.
  useEffect(() => {
    if (currentPage !== pagination.page) {
      setCurrentPage((page) => Math.min(page, pagination.totalPages));
    }
  }, [currentPage, pagination.page, pagination.totalPages]);

  const pageUsers = useMemo(
    () => visibleUsers.slice(pagination.startIndex, pagination.endIndex),
    [visibleUsers, pagination.startIndex, pagination.endIndex]
  );

  const notify = (message) => {
    setActionError('');
    setSuccessMessage(message);
  };

  const clearFilters = () => {
    setQuery('');
    setRoleFilter('');
    setStatusFilter('');
  };

  const handleAction = async (action, user, staff) => {
    if (!staff) return;
    const own = isCurrentUser(user, currentUsername);
    setActionError('');
    switch (action) {
      case 'view':
        setViewingStaff(staff);
        break;
      case 'edit':
        setEditing({ staff, user });
        break;
      case 'deactivate':
        if (own) return; // never your own account
        setDeactivateError('');
        setDeactivating({ staff, user });
        break;
      case 'remove':
        if (own || !isAdmin) return;
        setRemoveError('');
        setRemoving({ staff, user });
        break;
      case 'activate':
        try {
          await activateStaff(staff.id);
          await fetchUsers(false);
          notify(`User "${user.username}" activated.`);
        } catch (err) {
          if (isRedirect(err)) {
            navigate('/login');
            return;
          }
          setActionError(err.message || 'Failed to activate the user. Please try again.');
        }
        break;
      default:
        break;
    }
  };

  const handleAddSuccess = async (newStaff, fullName) => {
    setIsAddOpen(false);
    await fetchUsers(false);
    notify(`User "${newStaff?.username || fullName}" created.`);
  };

  const handleEditSuccess = async () => {
    const username = editing?.user?.username;
    setEditing(null);
    await fetchUsers(false);
    notify(`User "${username}" updated.`);
  };

  const handleConfirmDeactivate = async () => {
    if (!deactivating) return;
    setIsDeactivating(true);
    setDeactivateError('');
    try {
      await deactivateStaff(deactivating.staff.id);
      const { username } = deactivating.user;
      setDeactivating(null);
      await fetchUsers(false);
      notify(`User "${username}" deactivated.`);
    } catch (err) {
      if (isRedirect(err)) {
        navigate('/login');
        return;
      }
      setDeactivateError(err.message || 'Failed to deactivate the user. Please try again.');
    } finally {
      setIsDeactivating(false);
    }
  };

  const handleConfirmRemove = async () => {
    if (!removing) return;
    setIsRemoving(true);
    setRemoveError('');
    try {
      await removeStaff(removing.staff.id);
      const { username } = removing.user;
      setRemoving(null);
      await fetchUsers(false);
      notify(`User "${username}" removed.`);
    } catch (err) {
      if (isRedirect(err)) {
        navigate('/login');
        return;
      }
      setRemoveError(err.message || 'Failed to remove the user. Please try again.');
    } finally {
      setIsRemoving(false);
    }
  };

  if (!isAdmin) {
    return (
      <div className="w-full">
        <div className="bg-red-50 text-red-800 p-4 rounded-xl border border-red-100 flex items-center shadow-sm">
          <span className="font-medium">Access Denied:</span>
          <span className="ml-2">You do not have permission to view the Users management page.</span>
        </div>
      </div>
    );
  }

  const header = (
    <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Users</h1>
        <p className="text-gray-500 mt-1">Manage system users and roles.</p>
      </div>
      <button
        type="button"
        onClick={() => setIsAddOpen(true)}
        className="inline-flex items-center justify-center gap-2 px-4 py-2.5 bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 text-white text-sm font-semibold rounded-xl shadow-sm transition-colors cursor-pointer self-start sm:self-auto"
      >
        <PlusIcon className="w-5 h-5" />
        Add User
      </button>
    </div>
  );

  if (loading) {
    return (
      <div className="w-full space-y-6" aria-busy="true">
        {header}
        <div className="animate-pulse space-y-6">
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)_minmax(0,1fr)] gap-3 sm:gap-4">
            <div className="h-11 bg-gray-200 rounded-xl sm:col-span-2 lg:col-span-1" />
            <div className="h-11 bg-gray-200 rounded-xl" />
            <div className="h-11 bg-gray-200 rounded-xl" />
          </div>
          <div className="h-80 bg-gray-200 rounded-xl" />
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="w-full space-y-6">
        {header}
        <div role="alert" className="bg-red-50 text-red-800 p-4 rounded-xl flex justify-between items-center gap-3 shadow-sm border border-red-100">
          <p className="font-medium">{error}</p>
          <button
            type="button"
            onClick={() => fetchUsers()}
            className="px-4 py-2 bg-red-100 hover:bg-red-200 rounded-lg text-sm font-medium transition-colors cursor-pointer"
          >
            Retry
          </button>
        </div>
      </div>
    );
  }

  const emptyState = (
    <div className="flex flex-col items-center text-center gap-1.5">
      <UsersIcon className="w-10 h-10 text-gray-300 mb-1" />
      <p className="text-sm font-semibold text-gray-900">{hasFilters ? 'No users match your filters' : 'No users yet'}</p>
      <p className="text-sm text-gray-500">
        {hasFilters ? 'Try a different search, role or status.' : 'Add a user to give someone access to Taskotel.'}
      </p>
      {hasFilters && (
        <button
          type="button"
          onClick={clearFilters}
          className="mt-2 px-4 py-2 text-sm font-medium text-emerald-700 bg-emerald-50 hover:bg-emerald-100 rounded-lg transition-colors cursor-pointer"
        >
          Clear filters
        </button>
      )}
    </div>
  );

  return (
    <div className="w-full space-y-6">
      {header}

      {successMessage && (
        <div role="status" className="p-3.5 rounded-xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-sm flex items-center justify-between gap-3 animate-fadeIn">
          <span>{successMessage}</span>
          <button
            type="button"
            onClick={() => setSuccessMessage('')}
            className="text-emerald-700 hover:text-emerald-900 font-medium cursor-pointer"
            aria-label="Dismiss message"
          >
            Dismiss
          </button>
        </div>
      )}

      {actionError && (
        <div role="alert" className="p-3.5 rounded-xl bg-red-50 border border-red-200 text-red-800 text-sm flex items-center justify-between gap-3">
          <span>{actionError}</span>
          <button type="button" onClick={() => setActionError('')} className="text-red-700 hover:text-red-900 font-medium cursor-pointer">
            Dismiss
          </button>
        </div>
      )}

      {staffUnavailable && (
        <div className="p-3.5 rounded-xl bg-amber-50 border border-amber-200 text-amber-800 text-sm flex items-center justify-between gap-3">
          <span>Staff records could not be loaded, so account actions are unavailable right now.</span>
          <button type="button" onClick={() => fetchUsers()} className="text-amber-800 hover:text-amber-900 font-medium cursor-pointer">
            Retry
          </button>
        </div>
      )}

      {/* Search and filters */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)_minmax(0,1fr)] gap-3 sm:gap-4">
        <div className="relative sm:col-span-2 lg:col-span-1">
          <SearchIcon className="w-[18px] h-[18px] text-gray-400 absolute left-3.5 top-1/2 -translate-y-1/2 pointer-events-none" />
          <input
            type="text"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search users..."
            aria-label="Search users by username or email"
            className="w-full h-11 pl-10 pr-3 bg-white border border-gray-200 rounded-xl text-sm text-gray-800 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500 transition-colors"
          />
        </div>
        <FilterSelect label="Filter by role" value={roleFilter} onChange={setRoleFilter} options={ROLE_OPTIONS} />
        <FilterSelect label="Filter by status" value={statusFilter} onChange={setStatusFilter} options={STATUS_OPTIONS} />
      </div>

      {/* Users table */}
      <UsersTable
        users={pageUsers}
        staffByUserId={staffByUserId}
        currentUsername={currentUsername}
        isAdmin={isAdmin}
        onAction={handleAction}
        emptyState={emptyState}
        footer={
          <TablePagination
            pagination={pagination}
            totalItems={visibleUsers.length}
            noun={['user', 'users']}
            onPageChange={setCurrentPage}
            label="Users pagination"
            pageSize={pageSize}
            onPageSizeChange={setPageSize}
          />
        }
      />

      {/* Dialogs (shared with the Staff page) */}
      <StaffDetailsModal staff={viewingStaff} onClose={() => setViewingStaff(null)} />

      <AddStaffModal
        isOpen={isAddOpen}
        onClose={() => setIsAddOpen(false)}
        onSuccess={handleAddSuccess}
        title="Add User"
        description="Create a login and staff record with the role you choose"
        submitLabel="Add User"
      />

      <EditStaffModal
        isOpen={Boolean(editing)}
        staff={editing?.staff || null}
        onClose={() => setEditing(null)}
        onSuccess={handleEditSuccess}
      />

      <DeactivateStaffModal
        isOpen={Boolean(deactivating)}
        staff={deactivating?.staff || null}
        onClose={() => {
          if (isDeactivating) return;
          setDeactivating(null);
          setDeactivateError('');
        }}
        onConfirm={handleConfirmDeactivate}
        isSubmitting={isDeactivating}
        error={deactivateError}
      />

      <RemoveStaffModal
        isOpen={Boolean(removing)}
        staff={removing?.staff || null}
        onClose={() => {
          if (isRemoving) return;
          setRemoving(null);
          setRemoveError('');
        }}
        onConfirm={handleConfirmRemove}
        isSubmitting={isRemoving}
        error={removeError}
      />
    </div>
  );
}
