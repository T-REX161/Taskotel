import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { backdropMotion, dialogMotion, withExitTransition } from '../../theme/presence';
import {
  ROLE_LABELS,
  consequences,
  requiresPassword,
  roleChangeErrorMessage,
  roleChoices,
  roleExplanation,
  submitRoleChange,
  validateRoleChange,
} from '../../utils/roleChange';

const roleLabel = (role) => ROLE_LABELS[String(role || '').toUpperCase()] || String(role || '');

/**
 * Admin-only dialog on the Users page: change an account's system role. It is separate from Edit Staff on purpose. The person, their current
 * role, the requested role and what it means are shown first; nothing happens until "Confirm Change". A change to or from Administrator also
 * asks for the Administrator's own password. The server enforces every rule; this dialog only explains them.
 *
 * Props: isOpen, user (the account row), onClose, onChanged(updatedUser) after the server confirmed.
 */
function RoleChangeModal({ isOpen, user, onClose, onChanged, closing = false }) {
  const currentRole = String(user?.role || '').toUpperCase();
  const [newRole, setNewRole] = useState('');
  const [password, setPassword] = useState('');
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const inFlight = useRef(false); // a second click before the first answer must not send a second request
  const dialogRef = useRef(null);
  const selectRef = useRef(null);

  useEffect(() => {
    if (!isOpen) return undefined;
    setNewRole('');
    setPassword('');
    setTouched(false);
    setError('');
    const timer = setTimeout(() => selectRef.current?.focus(), 50);
    return () => clearTimeout(timer);
  }, [isOpen, user?.id]);

  useEffect(() => {
    if (!isOpen) return undefined;
    const onKeyDown = (event) => {
      if (event.key === 'Escape' && !inFlight.current) onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen || !user) return null;

  const name = user.fullName || user.username;
  const needsPassword = newRole ? requiresPassword(currentRole, newRole) : false;
  const problem = touched ? validateRoleChange({ from: currentRole, to: newRole, password }) : '';

  const handleConfirm = async () => {
    if (inFlight.current) return;
    setTouched(true);
    if (validateRoleChange({ from: currentRole, to: newRole, password }) !== '') return;
    inFlight.current = true;
    setSubmitting(true);
    setError('');
    try {
      const updated = await submitRoleChange({ userId: user.id, from: currentRole, to: newRole, password });
      onChanged?.(updated, { from: currentRole, to: newRole });
    } catch (err) {
      if (err?.message && err.message.includes('Redirecting to login')) return; // the session ended: the app is already signing out
      setError(roleChangeErrorMessage(err));
    } finally {
      inFlight.current = false;
      setSubmitting(false);
    }
  };

  const content = (
    <div
      className={`fixed inset-0 z-50 flex items-center justify-center p-4 bg-gray-900/50 backdrop-blur-xs ${backdropMotion(closing)}`}
      inert={closing}
      onClick={() => !submitting && onClose()}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="role-change-title"
        className={`bg-white rounded-2xl max-w-lg w-full p-6 shadow-xl border border-gray-100 max-h-[88vh] overflow-y-auto space-y-5 ${dialogMotion(closing)}`}
        onClick={(event) => event.stopPropagation()}
      >
        <div>
          <h3 id="role-change-title" className="text-lg font-bold text-gray-900">Change System Role</h3>
          <p className="text-xs text-gray-500 mt-0.5">Only administrators can do this. It is not part of editing a staff member.</p>
        </div>

        <dl className="grid grid-cols-2 gap-x-4 gap-y-3 rounded-xl bg-gray-50 border border-gray-100 p-4 text-sm">
          <div className="col-span-2">
            <dt className="text-xs font-medium text-gray-500">User</dt>
            <dd className="font-semibold text-gray-900 break-words">{name}{user.fullName && user.username !== user.fullName ? <span className="font-normal text-gray-500"> ({user.username})</span> : null}</dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-gray-500">Current role</dt>
            <dd className="font-semibold text-gray-900">{roleLabel(currentRole)}</dd>
          </div>
          <div>
            <dt className="text-xs font-medium text-gray-500">Requested role</dt>
            <dd className="font-semibold text-gray-900">{newRole ? roleLabel(newRole) : '—'}</dd>
          </div>
        </dl>

        <div className="space-y-1">
          <label htmlFor="role-change-select" className="text-sm font-medium text-gray-700">New role</label>
          <select
            id="role-change-select"
            ref={selectRef}
            value={newRole}
            onChange={(event) => { setNewRole(event.target.value); setError(''); }}
            disabled={submitting}
            className="w-full h-11 px-3 bg-white border border-gray-200 rounded-xl text-sm text-gray-800 focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500"
          >
            <option value="">Choose a role…</option>
            {roleChoices(currentRole).map((role) => <option key={role} value={role}>{roleLabel(role)}</option>)}
          </select>
        </div>

        {newRole && (
          <div data-testid="role-change-explanation" className="rounded-xl border border-gray-100 p-4 space-y-3 text-sm">
            <div>
              <p className="text-xs font-semibold text-gray-500">As {roleLabel(newRole)}</p>
              <p className="mt-0.5 text-gray-700">{roleExplanation(newRole)}</p>
            </div>
            <ul className="list-disc pl-5 space-y-1 text-xs text-gray-600">
              {consequences(currentRole, newRole).map((note) => <li key={note}>{note}</li>)}
            </ul>
          </div>
        )}

        {needsPassword && (
          <div className="space-y-1">
            <label htmlFor="role-change-password" className="text-sm font-medium text-gray-700">Your password</label>
            <input
              id="role-change-password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => { setPassword(event.target.value); setError(''); }}
              disabled={submitting}
              className="w-full h-11 px-3 bg-white border border-gray-200 rounded-xl text-sm text-gray-800 focus:outline-none focus:ring-2 focus:ring-emerald-500/30 focus:border-emerald-500"
            />
            <p className="text-xs text-gray-500">A change involving the administrator role needs your password again.</p>
          </div>
        )}

        {(problem || error) && (
          <div role="alert" className="p-3.5 rounded-xl bg-red-50 border border-red-200 text-red-800 text-xs">{problem || error}</div>
        )}

        <div className="flex items-center justify-end gap-3 pt-1">
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            className="px-4 py-2 text-gray-700 font-medium hover:bg-gray-50 border border-gray-200 rounded-xl text-sm transition-colors cursor-pointer disabled:opacity-50"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleConfirm}
            disabled={submitting || !newRole}
            className="px-4 py-2 bg-emerald-600 hover:bg-emerald-700 active:bg-emerald-800 text-white font-medium rounded-xl text-sm transition-colors cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed"
          >
            {submitting ? 'Changing…' : 'Confirm Change'}
          </button>
        </div>
      </div>
    </div>
  );

  return typeof document !== 'undefined' ? createPortal(content, document.body) : content;
}

// Stays mounted for its short exit after closing (none with reduced motion); see theme/presence.js
export default withExitTransition(RoleChangeModal, (props) => Boolean(props.isOpen && props.user));
