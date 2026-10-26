import React, { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { PlusIcon } from '../components/icons/Icons';
import PageSizeToggle from '../components/common/PageSizeToggle';
import {
  getStaff,
  getWorkload,
  deactivateStaff,
  activateStaff,
  removeStaff,
} from '../services/api';
import {
  isAvailable,
  isActive,
  formatDepartment,
  sortStaffAlphabetically,
} from '../components/staff/staffFormatters';
import StaffFilters from '../components/staff/StaffFilters';
import StaffTable from '../components/staff/StaffTable';
import StaffDetailsModal from '../components/staff/StaffDetailsModal';
import AddStaffModal from '../components/staff/AddStaffModal';
import EditStaffModal from '../components/staff/EditStaffModal';
import DeactivateStaffModal from '../components/staff/DeactivateStaffModal';
import RemoveStaffModal from '../components/staff/RemoveStaffModal';

const ITEMS_PER_PAGE = 10;

export default function StaffPage() {
  const navigate = useNavigate();
  const [staffList, setStaffList] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');
  const [successMessage, setSuccessMessage] = useState('');

  // Filter states
  const [searchTerm, setSearchTerm] = useState('');
  const [selectedDepartment, setSelectedDepartment] = useState('');
  const [selectedAvailability, setSelectedAvailability] = useState('');
  const [selectedStatus, setSelectedStatus] = useState('');

  // Pagination state
  const [currentPage, setCurrentPage] = useState(1);
  const [pageSize, setPageSize] = useState(ITEMS_PER_PAGE); // rows per page, chosen with the toggle

  // Modal states
  const [isAddModalOpen, setIsAddModalOpen] = useState(false);
  const [isEditModalOpen, setIsEditModalOpen] = useState(false);
  const [editingStaff, setEditingStaff] = useState(null);
  const [viewingStaff, setViewingStaff] = useState(null);
  const [deactivatingStaff, setDeactivatingStaff] = useState(null);
  const [isDeactivating, setIsDeactivating] = useState(false);
  const [deactivateError, setDeactivateError] = useState('');
  const [removingStaff, setRemovingStaff] = useState(null);
  const [isRemoving, setIsRemoving] = useState(false);
  const [removeError, setRemoveError] = useState('');

  const fetchStaffData = async (showLoading = true) => {
    if (showLoading) setIsLoading(true);
    setError('');
    try {
      // Fetch both staff directory and calculated workload metrics in parallel
      const [staffData, workloadData] = await Promise.all([
        getStaff(),
        getWorkload().catch((err) => {
          console.warn('Failed to load workload metrics, defaulting to 0:', err);
          return [];
        }),
      ]);

      const workloadMap = new Map();
      if (Array.isArray(workloadData)) {
        workloadData.forEach((w) => {
          if (w && w.staffId) {
            workloadMap.set(w.staffId.toString(), w);
          }
        });
      }

      const mergedList = (staffData || []).map((staff) => {
        const wl = staff.id ? workloadMap.get(staff.id.toString()) : null;
        return {
          ...staff,
          activeTaskCount: wl?.activeTaskCount ?? 0,
          workloadMinutes: wl?.totalEstimatedDuration ?? 0,
          workloadStatus: wl?.workloadStatus ?? 'LOW',
        };
      });

      setStaffList(sortStaffAlphabetically(mergedList));
    } catch (err) {
      if (err.message && err.message.includes('Redirecting to login')) {
        navigate('/login');
        return;
      }
      setError('Unable to load staff members. Please try again.');
    } finally {
      if (showLoading) setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchStaffData(true);
  }, []);

  // Combined real filtering logic with alphabetical sorting (A to Z) before pagination
  const filteredStaffList = useMemo(() => {
    const filtered = staffList.filter((staff) => {
      // 1. Search filter: staff names, email, username, phone, department
      if (searchTerm.trim()) {
        const query = searchTerm.toLowerCase().trim();
        const name = (staff.fullName || staff.name || '').toLowerCase();
        const email = (staff.email || '').toLowerCase();
        const username = (staff.username || '').toLowerCase();
        const phone = (staff.phone || '').toLowerCase();
        const dept = formatDepartment(staff.department).toLowerCase();

        const matchesSearch =
          name.includes(query) ||
          email.includes(query) ||
          username.includes(query) ||
          phone.includes(query) ||
          dept.includes(query);

        if (!matchesSearch) return false;
      }

      // 2. Department filter
      if (selectedDepartment) {
        if (
          (staff.department || '').toUpperCase() !==
          selectedDepartment.toUpperCase()
        ) {
          return false;
        }
      }

      // 3. Availability filter
      if (selectedAvailability) {
        const available = isAvailable(staff.availability);
        if (selectedAvailability === 'AVAILABLE' && !available) return false;
        if (selectedAvailability === 'UNAVAILABLE' && available) return false;
      }

      // 4. Status filter ('true' for Active, 'false' for Inactive)
      if (selectedStatus !== '') {
        const active = isActive(staff.active);
        const targetActive = selectedStatus === 'true';
        if (active !== targetActive) return false;
      }

      return true;
    });

    return sortStaffAlphabetically(filtered);
  }, [
    staffList,
    searchTerm,
    selectedDepartment,
    selectedAvailability,
    selectedStatus,
  ]);

  // Reset to page 1 whenever any filter or search changes
  useEffect(() => {
    setCurrentPage(1);
  }, [searchTerm, selectedDepartment, selectedAvailability, selectedStatus, pageSize]);

  // Clamp currentPage if filtered list shrinks (e.g. after delete/deactivate). Built on the latest value,
  // so a reset to page 1 in the same render (new filter or page size) is kept.
  const totalPages = Math.max(1, Math.ceil(filteredStaffList.length / pageSize));
  useEffect(() => {
    if (currentPage > totalPages) {
      setCurrentPage((page) => Math.min(page, totalPages));
    }
  }, [currentPage, totalPages]);

  // Slice the filtered list for the current page
  const paginatedStaffList = useMemo(() => {
    const start = (currentPage - 1) * pageSize;
    return filteredStaffList.slice(start, start + pageSize);
  }, [filteredStaffList, currentPage, pageSize]);

  // Build page number array (show at most 5 page buttons with ellipsis-like clamping)
  const pageNumbers = useMemo(() => {
    if (totalPages <= 7) {
      return Array.from({ length: totalPages }, (_, i) => i + 1);
    }
    const pages = new Set([1, totalPages]);
    for (let i = Math.max(2, currentPage - 2); i <= Math.min(totalPages - 1, currentPage + 2); i++) {
      pages.add(i);
    }
    return Array.from(pages).sort((a, b) => a - b);
  }, [totalPages, currentPage]);

  const handleClearFilters = () => {
    setSearchTerm('');
    setSelectedDepartment('');
    setSelectedAvailability('');
    setSelectedStatus('');
  };

  const showNotification = (msg) => {
    setSuccessMessage(msg);
    setTimeout(() => {
      setSuccessMessage('');
    }, 4000);
  };

  // Add Staff Modal Handlers
  const handleOpenAddModal = () => {
    setIsAddModalOpen(true);
  };

  const handleCloseAddModal = () => {
    setIsAddModalOpen(false);
  };

  const handleAddSuccess = async (newStaff, fullName) => {
    setIsAddModalOpen(false);
    await fetchStaffData(false);
    showNotification(
      `Staff member "${newStaff?.name || fullName}" created successfully!`
    );
  };

  // Edit Staff Modal Handlers
  const handleOpenEditModal = (staff) => {
    setEditingStaff(staff);
    setIsEditModalOpen(true);
  };

  const handleCloseEditModal = () => {
    setIsEditModalOpen(false);
    setEditingStaff(null);
  };

  const handleEditSuccess = async (payload) => {
    setIsEditModalOpen(false);
    setEditingStaff(null);
    await fetchStaffData(false);
    showNotification(
      `Staff member "${payload.fullName}" updated successfully!`
    );
  };

  // Details Modal Handlers
  const handleOpenViewModal = (staff) => {
    setViewingStaff(staff);
  };

  const handleCloseViewModal = () => {
    setViewingStaff(null);
  };

  // Deactivate modal handlers
  const handleOpenDeactivateModal = (staff) => {
    setDeactivateError('');
    setDeactivatingStaff(staff);
  };

  const handleCloseDeactivateModal = () => {
    if (isDeactivating) return;
    setDeactivatingStaff(null);
    setDeactivateError('');
  };

  const handleConfirmDeactivate = async () => {
    if (!deactivatingStaff) return;
    setIsDeactivating(true);
    setDeactivateError('');
    try {
      await deactivateStaff(deactivatingStaff.id);
      const targetName =
        deactivatingStaff.fullName || deactivatingStaff.name || 'Staff member';
      setDeactivatingStaff(null);
      await fetchStaffData(false);
      showNotification(`Staff member "${targetName}" deactivated.`);
    } catch (err) {
      if (err.message && err.message.includes('Redirecting to login')) {
        navigate('/login');
        return;
      }
      setDeactivateError(
        err.message || 'Failed to deactivate staff member. Please try again.'
      );
    } finally {
      setIsDeactivating(false);
    }
  };

  // Remove modal handlers
  const handleOpenRemoveModal = (staff) => {
    setRemoveError('');
    setRemovingStaff(staff);
  };

  const handleCloseRemoveModal = () => {
    if (isRemoving) return;
    setRemovingStaff(null);
    setRemoveError('');
  };

  const handleConfirmRemove = async () => {
    if (!removingStaff) return;
    setIsRemoving(true);
    setRemoveError('');
    try {
      await removeStaff(removingStaff.id);
      const targetName =
        removingStaff.fullName || removingStaff.name || 'Staff member';
      setRemovingStaff(null);
      await fetchStaffData(false);
      showNotification(`Staff member "${targetName}" removed successfully.`);
    } catch (err) {
      if (err.message && err.message.includes('Redirecting to login')) {
        navigate('/login');
        return;
      }
      setRemoveError(
        err.message || 'Failed to remove staff member. Please try again.'
      );
    } finally {
      setIsRemoving(false);
    }
  };

  // Activate handler via API
  const handleActivate = async (staff) => {
    try {
      await activateStaff(staff.id);
      await fetchStaffData(false);
      showNotification(
        `Staff member "${staff.fullName || staff.name}" activated.`
      );
    } catch (err) {
      if (err.message && err.message.includes('Redirecting to login')) {
        navigate('/login');
        return;
      }
      setError(
        err.message || 'Failed to activate staff member. Please try again.'
      );
    }
  };

  if (isLoading) {
    return (
      <div className="w-full space-y-4">
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div>
            <h1 className="text-xl sm:text-2xl font-bold text-slate-900 tracking-tight">
              Staff Management
            </h1>
            <p className="text-xs sm:text-sm text-slate-500 mt-0.5">
              Manage hotel staff, departments, availability and workload.
            </p>
          </div>
        </div>
        <div className="flex items-center justify-center p-14 bg-white rounded-xl border border-slate-200/80 shadow-xs">
          <div className="flex flex-col items-center gap-3">
            <svg
              className="animate-spin h-7 w-7 text-emerald-600"
              fill="none"
              viewBox="0 0 24 24"
            >
              <circle
                className="opacity-25"
                cx="12"
                cy="12"
                r="10"
                stroke="currentColor"
                strokeWidth="4"
              />
              <path
                className="opacity-75"
                fill="currentColor"
                d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
              />
            </svg>
            <p className="text-xs sm:text-sm font-medium text-slate-500">
              Loading staff directory and workload...
            </p>
          </div>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="w-full space-y-4">
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div>
            <h1 className="text-xl sm:text-2xl font-bold text-slate-900 tracking-tight">
              Staff Management
            </h1>
            <p className="text-xs sm:text-sm text-slate-500 mt-0.5">
              Manage hotel staff, departments, availability and workload.
            </p>
          </div>
        </div>
        <div className="p-5 bg-rose-50 border border-rose-200 rounded-xl text-rose-800 space-y-3">
          <h3 className="font-semibold text-sm">Failed to load staff list</h3>
          <p className="text-xs sm:text-sm text-rose-700">{error}</p>
          <button
            type="button"
            onClick={() => fetchStaffData(true)}
            className="px-3.5 py-1.5 bg-rose-600 hover:bg-rose-700 text-white rounded-lg text-xs sm:text-sm font-medium transition-colors cursor-pointer"
          >
            Try Again
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="w-full space-y-6">
      {/* Success notification banner */}
      {successMessage && (
        <div className="p-3.5 rounded-lg bg-emerald-50 border border-emerald-200 text-emerald-800 text-sm flex items-center justify-between shadow-xs">
          <div className="flex items-center gap-2">
            <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse" />
            <span className="font-medium">{successMessage}</span>
          </div>
          <button
            type="button"
            onClick={() => setSuccessMessage('')}
            className="text-emerald-700 hover:text-emerald-900 text-xs font-semibold cursor-pointer"
          >
            Dismiss
          </button>
        </div>
      )}

      {/* Staff Management Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-gray-900 tracking-tight">
            Staff Management
          </h1>
          <p className="text-sm text-gray-500 mt-1">
            Manage hotel staff members, their roles, departments, and workload.
          </p>
        </div>
        <button
          type="button"
          onClick={handleOpenAddModal}
          className="inline-flex items-center justify-center gap-2 px-4 py-2 bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 text-white rounded-lg font-medium text-sm transition-colors cursor-pointer shadow-sm whitespace-nowrap self-start sm:self-auto"
        >
          <PlusIcon className="w-4 h-4" />
          <span>Add Staff</span>
        </button>
      </div>

      {/* Single horizontal filter row beneath the header */}
      <StaffFilters
        searchTerm={searchTerm}
        onSearchChange={setSearchTerm}
        selectedDepartment={selectedDepartment}
        onDepartmentChange={setSelectedDepartment}
        selectedAvailability={selectedAvailability}
        onAvailabilityChange={setSelectedAvailability}
        selectedStatus={selectedStatus}
        onStatusChange={setSelectedStatus}
      />

      {/* Clean White Table Container */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden w-full">
        <StaffTable
          staffList={paginatedStaffList}
          onView={handleOpenViewModal}
          onEdit={handleOpenEditModal}
          onDeactivate={handleOpenDeactivateModal}
          onActivate={handleActivate}
          onRemove={handleOpenRemoveModal}
        />

        {/* Pagination controls */}
        {filteredStaffList.length > 0 && (
          <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 px-4 py-3 border-t border-gray-100 bg-gray-50/50">
            {/* Record count label */}
            <p className="text-xs text-gray-500 shrink-0">
              Showing{' '}
              <span className="font-medium text-gray-700">
                {(currentPage - 1) * pageSize + 1}
              </span>
              {'–'}
              <span className="font-medium text-gray-700">
                {Math.min(currentPage * pageSize, filteredStaffList.length)}
              </span>
              {' '}of{' '}
              <span className="font-medium text-gray-700">{filteredStaffList.length}</span>
              {' '}staff
            </p>

            {/* Rows-per-page toggle and page buttons */}
            <div className="flex flex-wrap items-center gap-2">
              <PageSizeToggle value={pageSize} onChange={setPageSize} />
              {totalPages > 1 && (
                <div className="flex flex-wrap items-center gap-1">
                  {/* Previous */}
                  <button
                    type="button"
                    onClick={() => setCurrentPage((p) => Math.max(1, p - 1))}
                    disabled={currentPage === 1}
                    className="inline-flex items-center justify-center h-8 px-3 rounded-lg border border-gray-200 text-xs font-medium text-gray-600 bg-white hover:bg-gray-50 hover:border-gray-300 disabled:opacity-40 disabled:pointer-events-none transition-colors cursor-pointer"
                    aria-label="Previous page"
                  >
                    ‹ Prev
                  </button>

                  {/* Numbered pages */}
                  {pageNumbers.map((page, idx) => {
                    const prev = pageNumbers[idx - 1];
                    const showEllipsis = prev !== undefined && page - prev > 1;
                    return (
                      <React.Fragment key={page}>
                        {showEllipsis && (
                          <span className="px-1 text-xs text-gray-400 select-none">…</span>
                        )}
                        <button
                          type="button"
                          onClick={() => setCurrentPage(page)}
                          className={`inline-flex items-center justify-center h-8 w-8 rounded-lg border text-xs font-medium transition-colors cursor-pointer ${
                            page === currentPage
                              ? 'bg-emerald-600 border-emerald-600 text-white shadow-sm'
                              : 'bg-white border-gray-200 text-gray-600 hover:bg-gray-50 hover:border-gray-300'
                          }`}
                          aria-label={`Page ${page}`}
                          aria-current={page === currentPage ? 'page' : undefined}
                        >
                          {page}
                        </button>
                      </React.Fragment>
                    );
                  })}

                  {/* Next */}
                  <button
                    type="button"
                    onClick={() => setCurrentPage((p) => Math.min(totalPages, p + 1))}
                    disabled={currentPage === totalPages}
                    className="inline-flex items-center justify-center h-8 px-3 rounded-lg border border-gray-200 text-xs font-medium text-gray-600 bg-white hover:bg-gray-50 hover:border-gray-300 disabled:opacity-40 disabled:pointer-events-none transition-colors cursor-pointer"
                    aria-label="Next page"
                  >
                    Next ›
                  </button>
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Modals */}
      <StaffDetailsModal
        staff={viewingStaff}
        onClose={handleCloseViewModal}
      />

      <AddStaffModal
        isOpen={isAddModalOpen}
        onClose={handleCloseAddModal}
        onSuccess={handleAddSuccess}
      />

      <EditStaffModal
        isOpen={isEditModalOpen}
        staff={editingStaff}
        onClose={handleCloseEditModal}
        onSuccess={handleEditSuccess}
      />

      <DeactivateStaffModal
        isOpen={Boolean(deactivatingStaff)}
        staff={deactivatingStaff}
        onClose={handleCloseDeactivateModal}
        onConfirm={handleConfirmDeactivate}
        isSubmitting={isDeactivating}
        error={deactivateError}
      />

      <RemoveStaffModal
        isOpen={Boolean(removingStaff)}
        staff={removingStaff}
        onClose={handleCloseRemoveModal}
        onConfirm={handleConfirmRemove}
        isSubmitting={isRemoving}
        error={removeError}
      />
    </div>
  );
}

