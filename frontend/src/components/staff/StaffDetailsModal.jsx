import React from 'react';
import { createPortal } from 'react-dom';
import { CloseIcon } from '../icons/Icons';
import {
  formatDepartment,
  isAvailable,
  isActive,
  getAvailabilityColor,
  getStatusColor,
} from './staffFormatters';

/**
 * StaffDetailsModal — read-only view of a staff member's complete details.
 *
 * Props:
 *   staff   – staff object to display (or null)
 *   onClose – callback to dismiss modal
 */
export default function StaffDetailsModal({ staff, onClose }) {
  if (!staff) return null;

  const available = isAvailable(staff.availability);
  const active = isActive(staff.active);

  return createPortal(
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm p-4 animate-fadeIn"
      onClick={onClose}
    >
      <div
        className="bg-white rounded-2xl shadow-xl w-full max-w-lg max-h-[85vh] overflow-y-auto border border-gray-100 p-6 space-y-6"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex justify-between items-center border-b border-gray-100 pb-4">
          <div>
            <h2 className="text-xl font-bold text-gray-900">
              {staff.fullName || staff.name || 'Staff Details'}
            </h2>
            <p className="text-xs text-gray-400 mt-0.5">
              @{staff.username || 'staff'} {staff.role ? `• ${staff.role}` : ''}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 transition-colors cursor-pointer"
            aria-label="Close modal"
          >
            <CloseIcon className="w-6 h-6" />
          </button>
        </div>

        {/* Details Grid */}
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-sm">
          <div className="bg-gray-50/70 p-4 rounded-xl border border-gray-100">
            <span className="text-xs font-medium text-gray-500 uppercase">Department</span>
            <p className="font-semibold text-gray-900 mt-1">
              {formatDepartment(staff.department)}
            </p>
          </div>

          <div className="bg-gray-50/70 p-4 rounded-xl border border-gray-100">
            <span className="text-xs font-medium text-gray-500 uppercase">Role</span>
            <p className="font-semibold text-gray-900 mt-1">
              {staff.role || 'STAFF'}
            </p>
          </div>

          <div className="bg-gray-50/70 p-4 rounded-xl border border-gray-100">
            <span className="text-xs font-medium text-gray-500 uppercase">Availability</span>
            <div className="mt-1">
              <span
                className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${getAvailabilityColor(
                  staff.availability
                )}`}
              >
                {available ? 'Available' : 'Unavailable'}
              </span>
            </div>
          </div>

          <div className="bg-gray-50/70 p-4 rounded-xl border border-gray-100">
            <span className="text-xs font-medium text-gray-500 uppercase">Status</span>
            <div className="mt-1">
              <span
                className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${getStatusColor(
                  staff.active
                )}`}
              >
                {active ? 'Active' : 'Inactive'}
              </span>
            </div>
          </div>

          <div className="bg-gray-50/70 p-4 rounded-xl border border-gray-100">
            <span className="text-xs font-medium text-gray-500 uppercase">Email</span>
            <p
              className="font-semibold text-gray-900 mt-1 truncate"
              title={staff.email || '-'}
            >
              {staff.email || '-'}
            </p>
          </div>

          <div className="bg-gray-50/70 p-4 rounded-xl border border-gray-100">
            <span className="text-xs font-medium text-gray-500 uppercase">Phone</span>
            <p className="font-semibold text-gray-900 mt-1">
              {staff.phone || '-'}
            </p>
          </div>
        </div>

        {/* Footer */}
        <div className="flex justify-end pt-2 border-t border-gray-100">
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-2 bg-gray-100 hover:bg-gray-200 text-gray-700 font-medium rounded-lg text-sm transition-colors cursor-pointer"
          >
            Close
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}
