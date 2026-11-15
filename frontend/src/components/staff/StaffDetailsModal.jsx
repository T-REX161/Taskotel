import React from 'react';
import ViewOnlyBadge from '../common/ViewOnlyBadge';
import { isViewOnly } from '../../utils/managerViewAccess';
import { createPortal } from 'react-dom';
import { CircleDotIcon, ClockIcon, CloseIcon, DepartmentsIcon, MailIcon, PhoneIcon, UserIcon } from '../icons/Icons';
import UserAvatar from '../common/UserAvatar';
import { UNASSIGNED_MANAGER_NOTICE, isUnassignedManager } from '../../utils/staffDepartment';
import { avatarInitials } from '../../utils/profilePicture';
import { normalizeRole, roleBadgeClass } from '../users/userListUtils';
import { backdropMotion, dialogMotion, withExitTransition } from '../../theme/presence';
import {
  formatDepartment,
  isAvailable,
  isActive,
  getAvailabilityColor,
  getStatusColor,
} from './staffFormatters';

/** One labelled line in a details section: a small tinted icon, the label, and the value (stacked on a phone, side by side from sm up). */
export function DetailRow({ icon: Icon, label, children, note }) {
  return (
    <div className="px-4 py-3">
      <div className="flex items-start gap-3">
        <span className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-emerald-50 text-emerald-600" aria-hidden="true">
          <Icon className="w-4 h-4" />
        </span>
        <div className="min-w-0 flex-1 sm:flex sm:items-center sm:justify-between sm:gap-4 sm:min-h-8">
          <dt className="text-xs font-medium text-gray-500">{label}</dt>
          <dd className="mt-0.5 sm:mt-0 min-w-0 text-sm font-medium text-gray-900 sm:text-right break-words">{children}</dd>
        </div>
      </div>
      {note}
    </div>
  );
}

/** A value that is not on record, shown quietly instead of a bare dash. */
const NotProvided = () => <span className="font-normal italic text-gray-400">Not provided</span>;

/** A compact status pill with a small dot, in the existing status colours. */
function StatusPill({ className, children }) {
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium ${className}`}>
      <span className="h-1.5 w-1.5 rounded-full bg-current opacity-70" aria-hidden="true" />
      {children}
    </span>
  );
}

/**
 * StaffDetailsModal — read-only view of a person's details (Users, Staff and Workload pages).
 * A profile header (picture, name, username, role badge, department), then Work information and Contact information as compact,
 * divided rows. Nothing is fetched or changed here.
 *
 * Props:
 *   staff            – staff object to display (or null)
 *   onClose          – callback to dismiss modal
 *   departmentAccess – optional extra Work information row(s) shown right below Department; the Users page passes a Manager's
 *                      additional view-only departments here (read by that row's own component, never by this modal)
 */
function StaffDetailsModal({ staff, onClose, closing = false, departmentAccess = null }) {
  if (!staff) return null;

  const available = isAvailable(staff.availability);
  const active = isActive(staff.active);
  const displayName = staff.fullName || staff.name || staff.username;
  const roleLabel = staff.role || 'STAFF';
  const departmentLabel = formatDepartment(staff.department);
  // Another department's member, seen through an Administrator's view-only grant: the server sends no contact details or username
  const viewOnly = isViewOnly(staff);

  return createPortal(
    <div
      className={`fixed inset-0 z-50 flex items-end sm:items-center justify-center bg-black/50 backdrop-blur-sm p-0 sm:p-4 ${backdropMotion(closing)}`}
      inert={closing}
      onClick={onClose}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="staff-details-title"
        className={`relative flex w-full sm:max-w-md max-h-[92vh] sm:max-h-[88vh] flex-col overflow-hidden rounded-t-2xl sm:rounded-2xl border border-gray-100 bg-white shadow-xl ${dialogMotion(closing)}`}
        onClick={(e) => e.stopPropagation()}
      >
        <button
          type="button"
          onClick={onClose}
          className="absolute right-3 top-3 z-10 rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-white/80 hover:text-gray-600 focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer"
          aria-label="Close modal"
        >
          <CloseIcon className="w-5 h-5" />
        </button>

        {/* Profile header */}
        <div className="shrink-0 border-b border-gray-100 bg-gradient-to-b from-emerald-50/80 to-white px-6 pb-5 pt-7 text-center">
          <UserAvatar
            imageUrl={staff.profileImageUrl}
            initials={avatarInitials(displayName)}
            name={displayName}
            className="mx-auto w-20 h-20 rounded-full shrink-0 ring-4 ring-white shadow-sm"
            fallbackClassName="bg-emerald-100 text-emerald-800 font-semibold text-2xl"
          />
          <h2 id="staff-details-title" className="mt-3 text-lg font-semibold tracking-tight text-gray-900 break-words">
            {staff.fullName || staff.name || 'Staff Details'}
          </h2>
          {viewOnly ? (
            <div className="mt-1.5 flex justify-center"><ViewOnlyBadge /></div>
          ) : (
            <p className="mt-0.5 text-sm text-gray-500 break-all">@{staff.username || 'staff'}</p>
          )}
          <div className="mt-3 flex flex-wrap items-center justify-center gap-x-3 gap-y-2">
            <span className={`inline-flex items-center rounded-md px-2.5 py-1 text-xs font-bold uppercase tracking-wide ${roleBadgeClass(normalizeRole(roleLabel))}`}>
              {roleLabel}
            </span>
            <span className="inline-flex items-center gap-1.5 text-sm text-gray-500">
              <DepartmentsIcon className="w-4 h-4 text-gray-400" aria-hidden="true" />
              {departmentLabel}
            </span>
          </div>
        </div>

        {/* Details */}
        <div className="min-h-0 flex-1 space-y-5 overflow-y-auto px-5 py-5 sm:px-6">
          <section aria-labelledby="staff-details-work">
            <h3 id="staff-details-work" className="mb-2 px-1 text-[11px] font-semibold uppercase tracking-wider text-gray-400">
              Work information
            </h3>
            <dl className="divide-y divide-gray-100 rounded-xl border border-gray-100">
              <DetailRow
                icon={DepartmentsIcon}
                label="Department"
                note={isUnassignedManager(staff) && <p className="mt-2 ml-11 text-xs text-amber-700">{UNASSIGNED_MANAGER_NOTICE}</p>}
              >
                {formatDepartment(staff.department)}
              </DetailRow>
              {departmentAccess}
              <DetailRow icon={UserIcon} label="Role">
                {roleLabel}
              </DetailRow>
              <DetailRow icon={ClockIcon} label="Availability">
                <StatusPill className={getAvailabilityColor(staff.availability)}>{available ? 'Available' : 'Unavailable'}</StatusPill>
              </DetailRow>
              <DetailRow icon={CircleDotIcon} label="Account status">
                <StatusPill className={getStatusColor(staff.active)}>{active ? 'Active' : 'Inactive'}</StatusPill>
              </DetailRow>
            </dl>
          </section>

          {viewOnly ? (
            <p data-testid="view-only-contact-note" className="rounded-xl border border-gray-100 bg-gray-50 px-4 py-3 text-xs text-gray-500">
              Contact details are not shown for departments you can only view.
            </p>
          ) : (
          <section aria-labelledby="staff-details-contact">
            <h3 id="staff-details-contact" className="mb-2 px-1 text-[11px] font-semibold uppercase tracking-wider text-gray-400">
              Contact information
            </h3>
            <dl className="divide-y divide-gray-100 rounded-xl border border-gray-100">
              <DetailRow icon={MailIcon} label="Email">
                {staff.email ? <span className="break-all" title={staff.email}>{staff.email}</span> : <NotProvided />}
              </DetailRow>
              <DetailRow icon={PhoneIcon} label="Phone">
                {staff.phone ? <span className="break-all tabular-nums">{staff.phone}</span> : <NotProvided />}
              </DetailRow>
            </dl>
          </section>
          )}
        </div>

        {/* Footer */}
        <div className="flex shrink-0 justify-end border-t border-gray-100 bg-gray-50/60 px-5 py-3 sm:px-6">
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg border border-gray-200 bg-white px-4 py-2 text-sm font-medium text-gray-700 shadow-xs transition-colors hover:bg-gray-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 cursor-pointer"
          >
            Close
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}

// Stays mounted for its 120ms exit after closing (none with reduced motion); see theme/presence.js
export default withExitTransition(StaffDetailsModal, (props) => Boolean(props.staff));
