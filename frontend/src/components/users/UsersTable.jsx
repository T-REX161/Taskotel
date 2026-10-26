import React, { useState, useEffect, useRef, useCallback } from 'react';
import { createPortal } from 'react-dom';
import { DotsVerticalIcon, EyeIcon, TrashIcon } from '../icons/Icons';
import { roleBadgeClass, normalizeRole, statusBadge, formatCreatedDate, isCurrentUser, getUserActions } from './userListUtils';

const MENU_LABELS = {
  view: 'View Details',
  edit: 'Edit User',
  deactivate: 'Deactivate',
  activate: 'Activate',
  remove: 'Remove User',
};

function EditIcon({ className }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
      <path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
    </svg>
  );
}

function BanIcon({ className }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <circle cx="12" cy="12" r="10" />
      <line x1="4.93" y1="4.93" x2="19.07" y2="19.07" />
    </svg>
  );
}

function CheckIcon({ className }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <polyline points="20 6 9 17 4 12" />
    </svg>
  );
}

const MENU_ICONS = { view: EyeIcon, edit: EditIcon, deactivate: BanIcon, activate: CheckIcon, remove: TrashIcon };

const MENU_ITEM_CLASS = {
  view: 'text-gray-700 hover:bg-gray-50 hover:text-emerald-700',
  edit: 'text-gray-700 hover:bg-gray-50 hover:text-emerald-700',
  deactivate: 'text-red-600 hover:bg-red-50 font-medium',
  activate: 'text-emerald-700 hover:bg-emerald-50 font-medium',
  remove: 'text-red-600 hover:bg-red-50 font-medium',
};

/**
 * The users table: Username, Email, Role, Status, Created, Actions. The row menu is rendered through a portal
 * so the table's horizontal scroll container never clips it (same approach as the staff table).
 *
 * Props:
 *  - users: rows to show (already filtered and ordered)
 *  - staffByUserId: Map of userId -> staff record (accounts are managed through their staff record)
 *  - currentUsername, isAdmin: who is looking (own account and permission rules)
 *  - onAction(action, user, staff): action is 'view' | 'edit' | 'deactivate' | 'activate' | 'remove'
 *  - emptyState: shown in place of the rows when there are none
 *  - footer: optional content under the last row, inside the card (the pagination)
 */
export default function UsersTable({ users, staffByUserId, currentUsername, isAdmin, onAction, emptyState, footer = null }) {
  const [activeMenu, setActiveMenu] = useState(null); // { user, buttonEl }
  const [menuPosition, setMenuPosition] = useState(null);
  const menuRef = useRef(null);

  const updatePosition = useCallback(() => {
    if (!activeMenu?.buttonEl) return;
    if (!document.body.contains(activeMenu.buttonEl)) {
      setActiveMenu(null);
      return;
    }
    const rect = activeMenu.buttonEl.getBoundingClientRect();
    const spaceBelow = window.innerHeight - rect.bottom;
    const openUpward = spaceBelow < 200 && rect.top > spaceBelow;
    setMenuPosition({
      openUpward,
      top: rect.bottom + 6,
      bottom: window.innerHeight - rect.top + 6,
      right: Math.max(8, window.innerWidth - rect.right),
    });
  }, [activeMenu]);

  useEffect(() => {
    if (!activeMenu) {
      setMenuPosition(null);
      return undefined;
    }
    updatePosition();
    const onKeyDown = (event) => {
      if (event.key === 'Escape') setActiveMenu(null);
    };
    const onPointerDown = (event) => {
      if (menuRef.current && !menuRef.current.contains(event.target) && !activeMenu.buttonEl.contains(event.target)) {
        setActiveMenu(null);
      }
    };
    window.addEventListener('scroll', updatePosition, true);
    window.addEventListener('resize', updatePosition);
    document.addEventListener('keydown', onKeyDown);
    document.addEventListener('pointerdown', onPointerDown);
    return () => {
      window.removeEventListener('scroll', updatePosition, true);
      window.removeEventListener('resize', updatePosition);
      document.removeEventListener('keydown', onKeyDown);
      document.removeEventListener('pointerdown', onPointerDown);
    };
  }, [activeMenu, updatePosition]);

  const toggleMenu = (event, user) => {
    event.stopPropagation();
    // Read the button now: React may run the state updater after the event is over, when currentTarget is null
    const buttonEl = event.currentTarget;
    setActiveMenu((current) => (current?.user?.id === user.id ? null : { user, buttonEl }));
  };

  const menuUser = activeMenu?.user;
  const menuStaff = menuUser ? staffByUserId.get(String(menuUser.id)) || null : null;
  const menu = menuUser ? getUserActions(menuUser, { staff: menuStaff, currentUsername, isAdmin }) : null;

  return (
    <div className="bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full min-w-[800px] text-left table-fixed">
          <colgroup>
            <col className="w-[18%]" />
            <col className="w-[25%]" />
            <col className="w-[14%]" />
            <col className="w-[13%]" />
            <col className="w-[18%]" />
            <col className="w-[12%]" />
          </colgroup>
          <thead className="bg-gray-50 border-b border-gray-200">
            <tr className="text-sm font-semibold text-gray-800">
              <th scope="col" className="px-5 py-3.5 border-r border-gray-100">Username</th>
              <th scope="col" className="px-5 py-3.5 border-r border-gray-100">Email</th>
              <th scope="col" className="px-5 py-3.5 border-r border-gray-100">Role</th>
              <th scope="col" className="px-5 py-3.5 border-r border-gray-100">Status</th>
              <th scope="col" className="px-5 py-3.5 border-r border-gray-100">Created</th>
              <th scope="col" className="px-5 py-3.5 text-center">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {users.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-6 py-12">
                  {emptyState}
                </td>
              </tr>
            ) : (
              users.map((user) => {
                const status = statusBadge(user.active);
                const role = normalizeRole(user.role) || String(user.role || 'Unknown').toUpperCase();
                const isMenuOpen = activeMenu?.user?.id === user.id;
                const own = isCurrentUser(user, currentUsername);
                return (
                  <tr key={user.id} className="hover:bg-gray-50/60 transition-colors">
                    <td className="px-5 py-4">
                      <div className="flex items-center gap-2 min-w-0">
                        <span className="font-bold text-[15px] text-gray-900 truncate" title={user.fullName ? `${user.username} (${user.fullName})` : user.username}>
                          {user.username}
                        </span>
                        {own && (
                          <span className="shrink-0 px-1.5 py-0.5 rounded bg-gray-100 text-gray-500 text-[10px] font-semibold uppercase tracking-wide">You</span>
                        )}
                      </div>
                    </td>
                    <td className="px-5 py-4 text-sm text-gray-700">
                      <span className="block truncate" title={user.email || ''}>{user.email || '—'}</span>
                    </td>
                    <td className="px-5 py-4">
                      <span className={`inline-flex items-center px-2.5 py-1 rounded-md text-xs font-bold uppercase tracking-wide ${roleBadgeClass(user.role)}`}>
                        {role}
                      </span>
                    </td>
                    <td className="px-5 py-4">
                      <span className={`inline-flex items-center px-2.5 py-1 rounded-md text-xs font-semibold ${status.className}`}>
                        {status.label}
                      </span>
                    </td>
                    <td className="px-5 py-4 text-sm text-gray-700 whitespace-nowrap">{formatCreatedDate(user.createdAt)}</td>
                    <td className="px-5 py-4 text-center">
                      <button
                        type="button"
                        onClick={(event) => toggleMenu(event, user)}
                        className={`w-9 h-9 rounded-lg border inline-flex items-center justify-center transition-colors cursor-pointer focus:outline-none focus-visible:ring-2 focus-visible:ring-emerald-500 ${
                          isMenuOpen
                            ? 'border-gray-400 bg-gray-100 text-gray-800'
                            : 'border-gray-200 bg-white text-gray-600 hover:bg-gray-50 hover:border-gray-300 hover:text-gray-800'
                        }`}
                        aria-label={`Actions for ${user.username}`}
                        aria-haspopup="menu"
                        aria-expanded={isMenuOpen}
                        title="Actions"
                      >
                        <DotsVerticalIcon className="w-4 h-4" />
                      </button>
                    </td>
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      {footer}

      {activeMenu && menuPosition && menu && createPortal(
        <div
          ref={menuRef}
          role="menu"
          aria-label={`Actions for ${menuUser.username}`}
          style={{
            position: 'fixed',
            ...(menuPosition.openUpward ? { bottom: `${menuPosition.bottom}px` } : { top: `${menuPosition.top}px` }),
            right: `${menuPosition.right}px`,
            zIndex: 9999,
          }}
          className="w-56 rounded-xl bg-white shadow-lg border border-gray-200 py-1 select-none animate-fadeIn"
          onClick={(event) => event.stopPropagation()}
        >
          {menu.actions.map((action, index) => {
            const Icon = MENU_ICONS[action];
            const startsDangerGroup = index > 0 && ['deactivate', 'activate'].includes(action);
            return (
              <React.Fragment key={action}>
                {startsDangerGroup && <div className="my-1 border-t border-gray-100" role="separator" />}
                <button
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    const user = activeMenu.user;
                    setActiveMenu(null);
                    onAction(action, user, menuStaff);
                  }}
                  className={`w-full text-left px-3.5 py-2 text-sm flex items-center gap-2.5 transition-colors cursor-pointer ${MENU_ITEM_CLASS[action]}`}
                >
                  <Icon className="w-4 h-4 shrink-0" />
                  <span>{MENU_LABELS[action]}</span>
                </button>
              </React.Fragment>
            );
          })}
          {menu.note && (
            <p className={`px-3.5 py-2 text-xs leading-relaxed text-gray-500 ${menu.actions.length ? 'border-t border-gray-100 mt-1' : ''}`}>
              {menu.note}
            </p>
          )}
        </div>,
        document.body
      )}
    </div>
  );
}
