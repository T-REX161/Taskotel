import React, { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { useNavigate } from 'react-router-dom';
import { CloseIcon } from '../icons/Icons';
import PickerDropdown from './PickerDropdown';
import DepartmentIcon from '../common/DepartmentIcon';
import { createDepartment, updateDepartment, getStaff } from '../../services/api';
import { COLOR_KEYS, COLOR_STYLES, DEFAULT_COLOR_KEY, DEFAULT_ICON_KEY, ICON_KEYS } from '../../utils/departmentStyles';

const ICON_LABELS = {
  building: 'Building (Department)',
  sprout: 'Sprout (Housekeeping)',
  bell: 'Bell (Front Desk)',
  wrench: 'Wrench (Maintenance)',
  coffee: 'Cup (Room Service)',
  utensils: 'Utensils (Food & Beverage)',
  shield: 'Shield (Security)',
  users: 'People (Team)',
  clipboard: 'Clipboard (Tasks)',
  calendar: 'Calendar (Scheduling)',
  clock: 'Clock (Time tracking)',
  briefcase: 'Briefcase (Operations)',
  package: 'Package (Inventory)',
  truck: 'Truck (Logistics)',
  sparkles: 'Sparkles (Cleaning)',
  monitor: 'Monitor (Office operations)',
  bed: 'Bed (Rooms)',
};

const NAME_MIN = 2;
const NAME_MAX = 100;
const DESCRIPTION_MAX = 500;

const emptyForm = {
  name: '',
  description: '',
  iconKey: DEFAULT_ICON_KEY,
  colorKey: DEFAULT_COLOR_KEY,
  headStaffId: '',
  active: true,
};

const inputClass =
  'w-full px-3 py-2 border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-emerald-500 focus:border-transparent text-sm text-gray-800 bg-white disabled:bg-gray-50';

/**
 * Create a department (department = null) or edit one. Saves through the department API; nothing is simulated.
 *
 * Props:
 *  - isOpen, onClose
 *  - department: the department being edited, or null to create
 *  - departments: the current list, used for an immediate duplicate-name check (the server checks again)
 *  - onSaved(savedDepartment, mode): called after the server accepted the change; mode is 'create' or 'edit'
 */
export default function DepartmentFormModal({ isOpen, onClose, department = null, departments = [], onSaved }) {
  const navigate = useNavigate();
  const isEdit = department !== null;

  const [formData, setFormData] = useState(emptyForm);
  const [staff, setStaff] = useState([]);
  const [isLoadingStaff, setIsLoadingStaff] = useState(false);
  const [formError, setFormError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [showNameError, setShowNameError] = useState(false);

  // Start from the department being edited (or an empty form) each time the dialog opens
  useEffect(() => {
    if (!isOpen) return;
    setFormError('');
    setShowNameError(false);
    setFormData(
      department
        ? {
            name: department.name || '',
            description: department.description || '',
            iconKey: ICON_KEYS.includes(department.iconKey) ? department.iconKey : DEFAULT_ICON_KEY,
            colorKey: COLOR_KEYS.includes(department.colorKey) ? department.colorKey : DEFAULT_COLOR_KEY,
            headStaffId: department.headStaffId || '',
            active: department.active !== false,
          }
        : emptyForm
    );
  }, [isOpen, department]);

  // Department head candidates: existing staff
  useEffect(() => {
    if (!isOpen) return undefined;
    let cancelled = false;
    setIsLoadingStaff(true);
    getStaff()
      .then((list) => {
        if (!cancelled) setStaff(Array.isArray(list) ? list : []);
      })
      .catch((err) => {
        if (cancelled) return;
        if (err.message && err.message.includes('Redirecting to login')) {
          navigate('/login');
          return;
        }
        setStaff([]);
      })
      .finally(() => {
        if (!cancelled) setIsLoadingStaff(false);
      });
    return () => {
      cancelled = true;
    };
  }, [isOpen, navigate]);

  // Close with Escape (unless a save is in flight)
  useEffect(() => {
    if (!isOpen) return undefined;
    const onKeyDown = (event) => {
      if (event.key === 'Escape' && !isSubmitting) onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [isOpen, isSubmitting, onClose]);

  // Only active staff can be a department head; an existing head who is no longer active stays selectable so it is not silently dropped
  const headOptions = useMemo(() => {
    const options = staff
      .filter((member) => member.active !== false || member.id === department?.headStaffId)
      .sort((a, b) => String(a.name).localeCompare(String(b.name)));
    if (department?.headStaffId && !options.some((member) => member.id === department.headStaffId)) {
      options.unshift({ id: department.headStaffId, name: department.headStaffName || 'Current head', department: '' });
    }
    return options;
  }, [staff, department]);

  if (!isOpen) return null;

  const name = formData.name.replace(/\s+/g, ' ').trim();

  const nameError = (() => {
    if (!name) return 'Department name is required.';
    if (name.length < NAME_MIN) return `Department name must be at least ${NAME_MIN} characters.`;
    if (name.length > NAME_MAX) return `Department name must be at most ${NAME_MAX} characters.`;
    const duplicate = departments.some(
      (existing) => existing.name.trim().toLowerCase() === name.toLowerCase() && existing.code !== department?.code
    );
    if (duplicate) return `A department named "${name}" already exists.`;
    return '';
  })();
  const setField = (field, value) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
    if (formError) setFormError('');
  };

  const handleClose = () => {
    if (isSubmitting) return;
    onClose();
  };

  const handleSubmit = async (event) => {
    event.preventDefault();
    if (isSubmitting) return;
    setShowNameError(true);
    if (nameError) {
      setFormError(nameError);
      return;
    }
    if (formData.description.length > DESCRIPTION_MAX) {
      setFormError(`Description must be at most ${DESCRIPTION_MAX} characters.`);
      return;
    }

    setIsSubmitting(true);
    setFormError('');
    try {
      const payload = {
        name,
        description: formData.description.trim() || null,
        iconKey: formData.iconKey,
        colorKey: formData.colorKey,
        headStaffId: formData.headStaffId || null,
        active: formData.active,
      };
      const saved = isEdit ? await updateDepartment(department.code, payload) : await createDepartment(payload);
      onSaved(saved, isEdit ? 'edit' : 'create');
    } catch (err) {
      if (err.message && err.message.includes('Redirecting to login')) {
        navigate('/login');
        return;
      }
      if (err.status === 409) {
        setFormError(err.message || 'A department with this name already exists.');
      } else if (err.status === 403) {
        setFormError('Permission denied. Only ADMIN or MANAGER users can manage departments.');
      } else if (err.status === 404) {
        setFormError(err.message || 'Department not found. It may have been removed; close this dialog and refresh.');
      } else if (err.status === 400) {
        setFormError(err.message || 'Invalid input. Please check the form fields and try again.');
      } else if (err.status === 405 || err.status === 501) {
        setFormError('The server does not support managing departments yet.');
      } else {
        setFormError(err.message || 'An error occurred while saving the department. Please try again.');
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  const colorStyle = COLOR_STYLES[formData.colorKey] || COLOR_STYLES[DEFAULT_COLOR_KEY];
  const hasWork = isEdit && ((department.staffCount || 0) > 0 || (department.activeTasks || 0) > 0);
  const submitLabel = isEdit ? 'Save Changes' : 'Add Department';
  const showError = showNameError && Boolean(nameError);

  return createPortal(
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm p-3 sm:p-4 animate-fadeIn"
      onClick={handleClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="department-form-title"
        className="bg-white rounded-2xl shadow-2xl w-full max-w-[520px] max-h-[94vh] flex flex-col border border-gray-100"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="px-5 sm:px-6 pt-4 pb-3 flex items-start gap-3.5">
          <div className="w-11 h-11 rounded-xl bg-emerald-50 text-emerald-600 flex items-center justify-center shrink-0">
            <DepartmentIcon iconKey="building" className="w-6 h-6" />
          </div>
          <div className="min-w-0 flex-1">
            <h2 id="department-form-title" className="text-xl font-bold text-gray-900 leading-tight">
              {isEdit ? 'Edit Department' : 'Add New Department'}
            </h2>
            <p className="text-sm text-gray-500 mt-0.5">
              {isEdit ? 'Update the details of this department.' : 'Create a new department for hotel operations.'}
            </p>
          </div>
          <button
            type="button"
            onClick={handleClose}
            disabled={isSubmitting}
            className="text-gray-500 hover:text-gray-800 transition-colors cursor-pointer shrink-0 -mr-1"
            aria-label="Close dialog"
          >
            <CloseIcon className="w-6 h-6" />
          </button>
        </div>

        <form onSubmit={handleSubmit} noValidate className="flex flex-col min-h-0 flex-1">
          <div className="px-5 sm:px-6 pb-4 pt-1 space-y-3.5 overflow-y-auto min-h-0">
            {formError && (
              <div role="alert" className="p-3 rounded-xl bg-red-50 border border-red-200 text-red-800 text-sm flex items-start gap-2.5">
                <svg className="w-5 h-5 flex-shrink-0 text-red-600 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
                <span>{formError}</span>
              </div>
            )}

            {/* Department name */}
            <div className="space-y-1.5">
              <label htmlFor="department-name" className="text-sm font-semibold text-gray-800">
                Department Name *
              </label>
              <div className="relative">
                <DepartmentIcon iconKey="building" className="w-5 h-5 text-gray-400 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
                <input
                  id="department-name"
                  type="text"
                  autoFocus
                  value={formData.name}
                  onChange={(e) => setField('name', e.target.value)}
                  onBlur={() => setShowNameError(true)}
                  disabled={isSubmitting}
                  maxLength={NAME_MAX + 20}
                  aria-invalid={showError}
                  aria-describedby={showError ? 'department-name-error' : undefined}
                  className={`${inputClass} pl-10 ${showError ? 'border-red-300 focus:ring-red-400' : ''}`}
                  placeholder="e.g. Housekeeping"
                />
              </div>
              {showError && (
                <p id="department-name-error" className="text-xs text-red-600">
                  {nameError}
                </p>
              )}
            </div>

            {/* Description */}
            <div className="space-y-1.5">
              <label htmlFor="department-description" className="text-sm font-semibold text-gray-800">
                Description
              </label>
              <div className="relative">
                <textarea
                  id="department-description"
                  rows={3}
                  value={formData.description}
                  onChange={(e) => setField('description', e.target.value)}
                  disabled={isSubmitting}
                  className={`${inputClass} resize-none pb-6`}
                  placeholder="Enter department description..."
                />
                <span className={`absolute bottom-2 right-3 text-xs pointer-events-none ${formData.description.length > DESCRIPTION_MAX ? 'text-red-600' : 'text-gray-400'}`}>
                  {formData.description.length}/{DESCRIPTION_MAX}
                </span>
              </div>
            </div>

            {/* Icon, colour and a live preview of the department as it will look */}
            <div className="flex flex-wrap items-end gap-x-4 gap-y-3">
              <div className="space-y-1.5">
                <span id="department-icon-label" className="block text-sm font-semibold text-gray-800">
                  Icon
                </span>
                <PickerDropdown
                  id="department-icon"
                  labelId="department-icon-label"
                  disabled={isSubmitting}
                  trigger={
                    <span className={`w-7 h-7 rounded-md flex items-center justify-center ${colorStyle.classes}`}>
                      <DepartmentIcon iconKey={formData.iconKey} className="w-[18px] h-[18px]" />
                    </span>
                  }
                  panelClass="grid grid-cols-5 gap-1.5 w-[15rem] max-h-52 overflow-y-auto"
                >
                  {(select) =>
                    ICON_KEYS.map((key) => {
                      const selected = formData.iconKey === key;
                      return (
                        <button
                          key={key}
                          type="button"
                          role="option"
                          aria-selected={selected}
                          aria-label={ICON_LABELS[key]}
                          title={ICON_LABELS[key]}
                          onClick={() => {
                            setField('iconKey', key);
                            select();
                          }}
                          className={`w-10 h-10 rounded-lg border flex items-center justify-center transition-colors cursor-pointer ${
                            selected ? 'border-emerald-500 bg-emerald-50 text-emerald-700' : 'border-gray-200 text-gray-600 hover:bg-gray-50'
                          }`}
                        >
                          <DepartmentIcon iconKey={key} className="w-5 h-5" />
                        </button>
                      );
                    })
                  }
                </PickerDropdown>
              </div>

              <div className="space-y-1.5">
                <span id="department-color-label" className="block text-sm font-semibold text-gray-800">
                  Color
                </span>
                <PickerDropdown
                  id="department-color"
                  labelId="department-color-label"
                  disabled={isSubmitting}
                  trigger={<span className={`w-6 h-6 rounded-full ${colorStyle.swatch}`} />}
                  panelClass="grid grid-cols-5 gap-1.5 w-[12.5rem]"
                >
                  {(select) =>
                    COLOR_KEYS.map((key) => {
                      const selected = formData.colorKey === key;
                      return (
                        <button
                          key={key}
                          type="button"
                          role="option"
                          aria-selected={selected}
                          aria-label={COLOR_STYLES[key].label}
                          onClick={() => {
                            setField('colorKey', key);
                            select();
                          }}
                          className={`w-9 h-9 rounded-lg border flex items-center justify-center transition-colors cursor-pointer ${
                            selected ? 'border-emerald-500 bg-emerald-50' : 'border-gray-200 hover:bg-gray-50'
                          }`}
                        >
                          <span className={`w-5 h-5 rounded-full ${COLOR_STYLES[key].swatch}`} />
                        </button>
                      );
                    })
                  }
                </PickerDropdown>
              </div>

              <div className="space-y-1.5 flex-1 min-w-[10rem]">
                <span className="block text-sm font-semibold text-gray-800">Preview</span>
                <div className="h-10 rounded-lg border border-gray-200 bg-white px-1.5 flex items-center gap-2.5 min-w-0" aria-live="polite">
                  <span className={`w-7 h-7 rounded-md flex items-center justify-center shrink-0 ${colorStyle.classes}`}>
                    <DepartmentIcon iconKey={formData.iconKey} className="w-[18px] h-[18px]" />
                  </span>
                  <span className={`text-sm font-semibold truncate ${name ? 'text-gray-900' : 'text-gray-400'}`}>{name || 'Department name'}</span>
                </div>
              </div>
            </div>

            {/* Department head */}
            <div className="space-y-1.5">
              <label htmlFor="department-head" className="text-sm font-semibold text-gray-800">
                Department Head (Optional)
              </label>
              <div className="relative">
                <DepartmentIcon iconKey="users" className="w-5 h-5 text-gray-400 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
                <select
                  id="department-head"
                  value={formData.headStaffId}
                  onChange={(e) => setField('headStaffId', e.target.value)}
                  disabled={isSubmitting || isLoadingStaff}
                  className={`${inputClass} h-10 appearance-none pl-10 pr-9`}
                >
                  <option value="">{isLoadingStaff ? 'Loading staff...' : 'Select staff member'}</option>
                  {headOptions.map((member) => (
                    <option key={member.id} value={member.id}>
                      {member.name}
                      {member.departmentName ? ` (${member.departmentName})` : ''}
                    </option>
                  ))}
                </select>
                <ChevronDown />
              </div>
            </div>

            {/* Status */}
            <div className="space-y-1.5">
              <span id="department-status-label" className="block text-sm font-semibold text-gray-800">
                Status *
              </span>
              <div id="department-status" role="radiogroup" aria-labelledby="department-status-label" className="flex gap-3">
                {[
                  { value: true, label: 'Active', id: 'department-status-active' },
                  { value: false, label: 'Inactive', id: 'department-status-inactive' },
                ].map((option) => {
                  const selected = formData.active === option.value;
                  return (
                    <button
                      key={option.label}
                      id={option.id}
                      type="button"
                      role="radio"
                      aria-checked={selected}
                      disabled={isSubmitting}
                      onClick={() => setField('active', option.value)}
                      className={`flex-1 sm:flex-none sm:min-w-[7.5rem] h-10 px-4 rounded-lg border text-sm font-medium inline-flex items-center justify-center gap-2 transition-colors cursor-pointer disabled:opacity-60 ${
                        selected
                          ? 'border-emerald-300 bg-emerald-50 text-emerald-700'
                          : 'border-gray-200 bg-gray-50 text-gray-600 hover:bg-gray-100'
                      }`}
                    >
                      <span
                        className={`w-[18px] h-[18px] rounded-full border-2 flex items-center justify-center shrink-0 ${
                          selected ? 'border-emerald-600 text-emerald-600' : 'border-gray-400'
                        }`}
                        aria-hidden="true"
                      >
                        {selected && (
                          <svg className="w-2.5 h-2.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={4}>
                            <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                          </svg>
                        )}
                      </span>
                      {option.label}
                    </button>
                  );
                })}
              </div>
              {!formData.active && (
                <p className="text-xs text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
                  {hasWork
                    ? `This department has ${department.staffCount || 0} staff and ${department.activeTasks || 0} active tasks. They keep working as they are, but no new staff or tasks can be added to an inactive department.`
                    : 'No new staff or tasks can be added to an inactive department.'}
                </p>
              )}
            </div>
          </div>

          {/* Footer */}
          <div className="px-5 sm:px-6 py-3.5 border-t border-gray-100 flex justify-end gap-3">
            <button
              type="button"
              onClick={handleClose}
              disabled={isSubmitting}
              className="px-5 py-2 text-gray-700 text-sm font-medium bg-white hover:bg-gray-50 rounded-lg transition-colors border border-gray-200 cursor-pointer disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isSubmitting}
              className={`px-5 py-2 bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 text-white text-sm font-semibold rounded-lg transition-colors inline-flex items-center gap-2 cursor-pointer shadow-sm ${
                isSubmitting ? 'opacity-80 cursor-wait' : ''
              }`}
            >
              {isSubmitting ? (
                <>
                  <svg className="animate-spin h-4 w-4 text-white" fill="none" viewBox="0 0 24 24">
                    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                    <path
                      className="opacity-75"
                      fill="currentColor"
                      d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                    />
                  </svg>
                  <span>{isEdit ? 'Saving...' : 'Adding Department...'}</span>
                </>
              ) : (
                <>
                  {!isEdit && <DepartmentIcon iconKey="building" className="w-4 h-4" />}
                  {submitLabel}
                </>
              )}
            </button>
          </div>
        </form>
      </div>
    </div>,
    document.body
  );
}

// Decorative arrow for the native selects (the select itself stays a real, accessible control)
function ChevronDown() {
  return (
    <svg className="w-4 h-4 text-gray-500 absolute right-3 top-1/2 -translate-y-1/2 pointer-events-none" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
    </svg>
  );
}
