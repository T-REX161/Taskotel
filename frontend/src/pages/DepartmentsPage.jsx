import React, { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { DepartmentsIcon, StaffIcon, TasksIcon, PlusIcon, DotsVerticalIcon } from '../components/icons/Icons';
import { getDepartments, getUserRole } from '../services/api';
import DepartmentIcon from '../components/common/DepartmentIcon';
import DepartmentFormModal from '../components/departments/DepartmentFormModal';
import { getDepartmentStyle } from '../utils/departmentStyles';

const SUCCESS_MESSAGE_MS = 6000;

export default function DepartmentsPage() {
  const navigate = useNavigate();
  const [departments, setDepartments] = useState([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');
  const [isFormOpen, setIsFormOpen] = useState(false);
  const [editingDepartment, setEditingDepartment] = useState(null);
  const [successMessage, setSuccessMessage] = useState('');
  const [openMenuCode, setOpenMenuCode] = useState(null);
  const menuRef = useRef(null);

  // Only ADMIN and MANAGER may create or edit departments; the server enforces this too
  const role = getUserRole();
  const canManage = role === 'ADMIN' || role === 'MANAGER';

  // silent: refresh the list in place after a change, without replacing the page with the loading spinner
  const fetchDepartments = async (silent = false) => {
    if (!silent) {
      setIsLoading(true);
      setError('');
    }
    try {
      const data = await getDepartments();
      setDepartments(Array.isArray(data) ? data : []);
    } catch (err) {
      if (err.message && err.message.includes('Redirecting to login')) {
        navigate('/login');
        return;
      }
      if (!silent) setError(err.message || 'Unable to load departments. Please try again.');
    } finally {
      if (!silent) setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchDepartments();
  }, []);

  // The success message goes away by itself
  useEffect(() => {
    if (!successMessage) return undefined;
    const timer = setTimeout(() => setSuccessMessage(''), SUCCESS_MESSAGE_MS);
    return () => clearTimeout(timer);
  }, [successMessage]);

  // Close the card menu on an outside click or Escape
  useEffect(() => {
    if (!openMenuCode) return undefined;
    const onPointerDown = (event) => {
      if (menuRef.current && !menuRef.current.contains(event.target)) setOpenMenuCode(null);
    };
    const onKeyDown = (event) => {
      if (event.key === 'Escape') setOpenMenuCode(null);
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [openMenuCode]);

  const openCreate = () => {
    setEditingDepartment(null);
    setIsFormOpen(true);
  };

  const openEdit = (department) => {
    setOpenMenuCode(null);
    setEditingDepartment(department);
    setIsFormOpen(true);
  };

  const closeForm = () => {
    setIsFormOpen(false);
    setEditingDepartment(null);
  };

  const handleSaved = (saved, mode) => {
    closeForm();
    setSuccessMessage(mode === 'edit' ? `Department "${saved.name}" was updated.` : `Department "${saved.name}" was created.`);
    fetchDepartments(true);
  };

  const header = (
    <div className="flex flex-col sm:flex-row sm:justify-between sm:items-center gap-3">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Departments</h1>
        <p className="text-gray-500 mt-1">Hotel departments, operational units, and staff capacities.</p>
      </div>
      {canManage && (
        <button
          type="button"
          onClick={openCreate}
          className="inline-flex items-center justify-center gap-2 px-4 py-2.5 bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 text-white text-sm font-semibold rounded-xl shadow-sm transition-colors cursor-pointer self-start sm:self-auto"
        >
          <PlusIcon className="w-5 h-5" />
          Add Department
        </button>
      )}
    </div>
  );

  if (isLoading) {
    return (
      <div className="w-full space-y-6">
        {header}
        <div className="flex items-center justify-center p-16 bg-white rounded-2xl border border-gray-100 shadow-sm">
          <div className="flex flex-col items-center gap-3">
            <svg className="animate-spin h-8 w-8 text-emerald-600" fill="none" viewBox="0 0 24 24">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" />
            </svg>
            <p className="text-sm font-medium text-gray-500">Loading departments...</p>
          </div>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="w-full space-y-6">
        {header}
        <div className="p-6 bg-red-50 border border-red-200 rounded-2xl text-red-800 space-y-3">
          <h3 className="font-bold text-base">Failed to load departments</h3>
          <p className="text-sm">{error}</p>
          <button
            type="button"
            onClick={() => fetchDepartments()}
            className="px-4 py-2 bg-red-600 hover:bg-red-700 text-white rounded-xl text-sm font-medium transition-colors cursor-pointer"
          >
            Retry
          </button>
        </div>
      </div>
    );
  }

  // An inactive department is not counted as one of the hotel's departments; its staff and tasks still are
  const totalDepartments = departments.filter((dept) => dept.active !== false).length;
  const activeStaff = departments.reduce((sum, dept) => sum + (Number(dept.staffCount) || 0), 0);
  const activeTasks = departments.reduce((sum, dept) => sum + (Number(dept.activeTasks) || 0), 0);

  const summaryCards = [
    { title: 'Total Departments', value: totalDepartments, icon: <DepartmentsIcon className="w-6 h-6 text-emerald-600" />, bg: 'bg-emerald-50' },
    { title: 'Active Staff', value: activeStaff, icon: <StaffIcon className="w-6 h-6 text-blue-600" />, bg: 'bg-blue-50' },
    { title: 'Active Tasks', value: activeTasks, icon: <TasksIcon className="w-6 h-6 text-purple-600" />, bg: 'bg-purple-50' },
  ];

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

      {/* Summary Cards */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        {summaryCards.map((card, idx) => (
          <div key={idx} className="bg-white rounded-xl px-6 py-4 shadow-sm border border-gray-100 flex items-center space-x-4">
            <div className={`p-3 rounded-lg ${card.bg}`}>
              {card.icon}
            </div>
            <div>
              <p className="text-sm font-medium text-gray-500">{card.title}</p>
              <h3 className="text-2xl leading-7 font-bold text-gray-900">{card.value}</h3>
            </div>
          </div>
        ))}
      </div>

      {/* Departments Grid or Empty State */}
      {departments.length === 0 ? (
        <div className="bg-white rounded-2xl border border-gray-100 shadow-sm p-12 text-center">
          <DepartmentsIcon className="w-12 h-12 text-gray-300 mx-auto mb-3" />
          <h3 className="text-base font-semibold text-gray-900">No departments found</h3>
          <p className="text-sm text-gray-500 mt-1">There are currently no departments available.</p>
        </div>
      ) : (
        // One card per department from the data; the grid wraps into new rows as departments are added
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-5 lg:gap-6">
          {departments.map((dept) => {
            // Colour and icon come from the department's stored keys, never from its position in the list
            const { classes } = getDepartmentStyle(dept);
            const isInactive = dept.active === false;
            return (
              <div
                key={dept.code || dept.name}
                className={`min-w-0 bg-white rounded-2xl border border-gray-200/80 shadow-sm hover:shadow-md transition-shadow ${isInactive ? 'opacity-75' : ''}`}
              >
                <div className="flex items-center gap-4 px-5 pt-5 pb-4">
                  <div className={`w-12 h-12 rounded-xl flex items-center justify-center shrink-0 ${classes}`}>
                    <DepartmentIcon department={dept} className="w-6 h-6" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <h3 className="text-lg font-bold text-gray-900 leading-snug break-words">{dept.name}</h3>
                    {isInactive && (
                      <span className="inline-block mt-0.5 px-2 py-0.5 rounded-full bg-gray-100 text-gray-600 text-xs font-medium">Inactive</span>
                    )}
                  </div>
                  {canManage && dept.code && (
                    <div className="relative shrink-0" ref={openMenuCode === dept.code ? menuRef : null}>
                      <button
                        type="button"
                        onClick={() => setOpenMenuCode(openMenuCode === dept.code ? null : dept.code)}
                        className="p-1.5 rounded-lg text-gray-400 hover:text-gray-700 hover:bg-gray-100 transition-colors cursor-pointer"
                        aria-label={`Actions for ${dept.name}`}
                        aria-haspopup="menu"
                        aria-expanded={openMenuCode === dept.code}
                      >
                        <DotsVerticalIcon className="w-5 h-5" />
                      </button>
                      {openMenuCode === dept.code && (
                        <div role="menu" className="absolute right-0 top-full mt-1 z-20 w-44 bg-white border border-gray-200 rounded-xl shadow-lg py-1">
                          <button
                            type="button"
                            role="menuitem"
                            onClick={() => openEdit(dept)}
                            className="w-full text-left px-4 py-2 text-sm text-gray-700 hover:bg-gray-50 cursor-pointer"
                          >
                            Edit department
                          </button>
                        </div>
                      )}
                    </div>
                  )}
                </div>

                <div className="mx-5 border-t border-gray-100" />

                <dl className="px-5 pt-4 pb-5 space-y-3.5 text-[15px]">
                  <div className="flex items-center gap-3">
                    <StaffIcon className="w-5 h-5 text-gray-400 shrink-0" />
                    <dt className="text-gray-500">Staff</dt>
                    <dd className="ml-auto font-bold text-gray-900 tabular-nums">{Number(dept.staffCount) || 0}</dd>
                  </div>
                  <div className="flex items-center gap-3">
                    <TasksIcon className="w-5 h-5 text-gray-400 shrink-0" />
                    <dt className="text-gray-500">Active Tasks</dt>
                    <dd className="ml-auto font-bold text-gray-900 tabular-nums">{Number(dept.activeTasks) || 0}</dd>
                  </div>
                </dl>
              </div>
            );
          })}
        </div>
      )}

      <DepartmentFormModal
        isOpen={isFormOpen}
        onClose={closeForm}
        department={editingDepartment}
        departments={departments}
        onSaved={handleSaved}
      />
    </div>
  );
}
