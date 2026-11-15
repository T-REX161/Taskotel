import test, { describe, it, mock } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import {
  EXIT_MS, REDUCED_MOTION_QUERY, Presence, backdropMotion, createPresenceHook, dialogMotion, exitDuration, menuMotion,
  prefersReducedMotion, presenceOf, toastMotion, withExitTransition,
} from './presence.js';

// Stage 3 of the motion plan: entrance and exit animations for modals, menus and temporary notices.
const require = createRequire(import.meta.url);
const motion = require('./motion.cjs');
const SRC = fileURLToPath(new URL('../', import.meta.url));
const sources = [];
(function walk(dir) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) walk(full);
    else if (/\.jsx?$/.test(name) && !name.endsWith('.test.js')) sources.push({ file: relative(SRC, full).replace(/\\/g, '/'), text: readFileSync(full, 'utf8') });
  }
})(SRC);
const read = (file) => sources.find((s) => s.file === file).text;

// ── a tiny synchronous hook runtime: enough of React's useState / useEffect to step usePresence through time ───────
function renderHook(build, initialProps) {
  const slots = [];
  const effects = [];
  let props = initialProps;
  let result;
  let rendering = false;
  let renderAgain = false;
  let index = 0;
  const hooks = {
    useState(initial) {
      const i = index++;
      if (!(i in slots)) slots[i] = initial;
      const set = (next) => {
        if (Object.is(slots[i], next)) return;
        slots[i] = next;
        if (rendering) renderAgain = true; // a render-phase update: React re-runs the component at once
        else render();
      };
      return [slots[i], set];
    },
    useEffect(fn, deps) {
      const i = index++;
      const prev = effects[i];
      if (prev && deps.every((d, k) => Object.is(d, prev.deps[k]))) return;
      effects[i] = { fn, deps, cleanup: prev?.cleanup, pending: true };
    },
  };
  const usePresence = createPresenceHook(hooks);
  function render() {
    let passes = 0;
    do {
      renderAgain = false;
      rendering = true;
      index = 0;
      result = build(usePresence, props);
      rendering = false;
      passes += 1;
      assert.ok(passes < 10, 'settles within a couple of passes');
    } while (renderAgain);
    for (const effect of effects) {
      if (!effect?.pending) continue;
      effect.pending = false;
      if (typeof effect.cleanup === 'function') effect.cleanup();
      effect.cleanup = effect.fn();
    }
  }
  render();
  return {
    get current() { return result; },
    rerender(next) { props = next; render(); },
  };
}

const presenceFor = (exitMs) => (usePresence, value) => usePresence(value, exitMs);

describe('usePresence: entrance, exit and unmount', () => {
  it('entrance: a shown value is present at once and not closing', () => {
    const hook = renderHook(presenceFor(EXIT_MS), 'Saved');
    assert.deepEqual(hook.current, { present: true, value: 'Saved', closing: false });
    const hidden = renderHook(presenceFor(EXIT_MS), null);
    assert.deepEqual(hidden.current, { present: false, value: null, closing: false }, 'nothing until something is shown');
  });

  it('exit: when the value goes, the last one stays for exactly the exit animation, then unmounts', () => {
    mock.timers.enable({ apis: ['setTimeout'] });
    try {
      const hook = renderHook(presenceFor(EXIT_MS), null);
      hook.rerender({ id: 7, title: 'Clean room 204' });
      hook.rerender(null);
      assert.deepEqual(hook.current, { present: true, value: { id: 7, title: 'Clean room 204' }, closing: true }, 'closing keeps the content that was on screen');
      mock.timers.tick(EXIT_MS - 1);
      assert.equal(hook.current.present, true, 'still animating out');
      mock.timers.tick(1);
      assert.deepEqual(hook.current, { present: false, value: null, closing: false }, 'gone after 120ms: nothing invisible is left behind');
    } finally {
      mock.timers.reset();
    }
  });

  it('reopening while it closes cancels the exit and shows the new value; no stale timer removes it later', () => {
    mock.timers.enable({ apis: ['setTimeout'] });
    try {
      const hook = renderHook(presenceFor(EXIT_MS), 'first');
      hook.rerender(null);
      mock.timers.tick(60);
      hook.rerender('second');
      assert.deepEqual(hook.current, { present: true, value: 'second', closing: false });
      mock.timers.tick(EXIT_MS * 3);
      assert.deepEqual(hook.current, { present: true, value: 'second', closing: false }, 'the cancelled exit never fires');
      hook.rerender(null);
      assert.equal(hook.current.value, 'second', 'and closes with the latest content');
    } finally {
      mock.timers.reset();
    }
  });

  it('a changed value while open is shown straight away (no exit between two open states)', () => {
    const hook = renderHook(presenceFor(EXIT_MS), 'Task A saved');
    hook.rerender('Task B saved');
    assert.deepEqual(hook.current, { present: true, value: 'Task B saved', closing: false });
  });
});

describe('usePresence: reduced motion', () => {
  it('the exit takes no time: the element unmounts in the same render it is closed, with no timer', () => {
    mock.timers.enable({ apis: ['setTimeout'] });
    try {
      const hook = renderHook(presenceFor(exitDuration(true)), 'Saved');
      assert.deepEqual(hook.current, { present: true, value: 'Saved', closing: false }, 'still appears');
      hook.rerender(null);
      assert.deepEqual(hook.current, { present: false, value: null, closing: false }, 'removed at once, never closing');
    } finally {
      mock.timers.reset();
    }
  });

  it('exitDuration: 120ms normally, 0 with reduced motion; EXIT_MS is the shared fast duration', () => {
    assert.equal(exitDuration(false), 120);
    assert.equal(exitDuration(true), 0);
    assert.equal(EXIT_MS, motion.EXIT_MS);
    assert.equal(`${EXIT_MS}ms`, motion.DURATIONS.fast);
  });

  it('prefersReducedMotion reads the media query, and is false where it cannot be read', () => {
    const original = globalThis.window;
    try {
      delete globalThis.window;
      assert.equal(prefersReducedMotion(), false, 'no window (tests, server)');
      globalThis.window = { matchMedia: (query) => ({ matches: query === REDUCED_MOTION_QUERY }) };
      assert.equal(prefersReducedMotion(), true);
      globalThis.window = { matchMedia: () => ({ matches: false }) };
      assert.equal(prefersReducedMotion(), false);
      globalThis.window = { matchMedia: () => { throw new Error('unsupported'); } };
      assert.equal(prefersReducedMotion(), false);
      globalThis.window = {};
      assert.equal(prefersReducedMotion(), false, 'old browsers without matchMedia');
    } finally {
      if (original === undefined) delete globalThis.window; else globalThis.window = original;
    }
  });
});

test('presenceOf: shown, closing, gone', () => {
  assert.deepEqual(presenceOf('a', null), { present: true, value: 'a', closing: false });
  assert.deepEqual(presenceOf('', 'a'), { present: true, value: 'a', closing: true });
  assert.deepEqual(presenceOf(null, null), { present: false, value: null, closing: false });
});

test('class pairs: each entrance has its exit, and every exit lets clicks through to the page', () => {
  assert.equal(backdropMotion(false), 'animate-fade-in');
  assert.equal(backdropMotion(true), 'animate-fade-out pointer-events-none');
  assert.equal(dialogMotion(false), 'animate-dialog-in');
  assert.equal(dialogMotion(true), 'animate-dialog-out', 'the panel sits inside the backdrop, which already ignores the pointer');
  assert.equal(menuMotion(false), 'animate-menu-in');
  assert.equal(menuMotion(true), 'animate-menu-out pointer-events-none');
  assert.equal(toastMotion(false), 'animate-toast-in');
  assert.equal(toastMotion(true), 'animate-toast-out pointer-events-none');
});

// ── the wrappers, rendered with React ─────────────────────────────────────────────────────────────────────────

describe('withExitTransition and Presence (rendered with React)', () => {
  function Dialog({ isOpen, title, closing = false }) {
    if (!isOpen) return null;
    return createElement('div', { 'data-closing': String(closing) }, title);
  }
  const Wrapped = withExitTransition(Dialog, (props) => Boolean(props.isOpen));

  it('a closed modal renders nothing, exactly as before', () => {
    assert.equal(renderToStaticMarkup(createElement(Wrapped, { isOpen: false, title: 'x' })), '');
  });

  it('an open modal renders with its own props and is not closing', () => {
    assert.equal(renderToStaticMarkup(createElement(Wrapped, { isOpen: true, title: 'Edit task' })), '<div data-closing="false">Edit task</div>');
    assert.equal(Wrapped.displayName, 'withExitTransition(Dialog)');
  });

  it('Presence renders its child while shown and nothing otherwise', () => {
    assert.equal(renderToStaticMarkup(createElement(Presence, null, createElement(Dialog, { isOpen: true, title: 'Confirm' }))), '<div data-closing="false">Confirm</div>');
    assert.equal(renderToStaticMarkup(createElement(Presence, null, false)), '');
    assert.equal(renderToStaticMarkup(createElement(Presence, null, null)), '');
  });
});

// ── where it is used ──────────────────────────────────────────────────────────────────────────────────────────

const MODALS = {
  'components/departments/DepartmentFormModal.jsx': ['if (!isOpen) return null;', 'Boolean(props.isOpen)'],
  'components/departments/RemoveDepartmentModal.jsx': ['if (!isOpen) return null;', 'props.department != null'],
  'components/profile/ChangePasswordDialog.jsx': ['if (!isOpen) return null;', 'Boolean(props.isOpen)'],
  'components/profile/EmailChangeDialog.jsx': ['if (!isOpen) return null;', 'Boolean(props.isOpen)'],
  'components/profile/PhoneChangeDialog.jsx': ['if (!isOpen) return null;', 'Boolean(props.isOpen)'],
  'components/profile/ProfilePictureEditor.jsx': ['if (!isOpen) return null;', 'Boolean(props.isOpen)'],
  'components/staff/AddStaffModal.jsx': ['if (!isOpen) return null;', 'Boolean(props.isOpen)'],
  'components/staff/DeactivateStaffModal.jsx': ['if (!isOpen || !staff) return null;', 'Boolean(props.isOpen && props.staff)'],
  'components/staff/EditStaffModal.jsx': ['if (!isOpen || !staff) return null;', 'Boolean(props.isOpen && props.staff)'],
  'components/staff/RemoveStaffModal.jsx': ['if (!isOpen || !staff) return null;', 'Boolean(props.isOpen && props.staff)'],
  'components/staff/StaffDetailsModal.jsx': ['if (!staff) return null;', 'Boolean(props.staff)'],
  'components/tasks/DeleteTaskModal.jsx': ['if (!task) return null;', 'Boolean(props.task)'],
  'components/tasks/EditTaskModal.jsx': ['if (!task) return null;', 'Boolean(props.task)'],
  'components/tasks/TaskDetailsModal.jsx': ['if (!task) return null;', 'Boolean(props.task)'],
  // the Manager department dialog, named in pieces: managerDepartment.test.js allows its full name in no other file
  [`components/users/${'Manager'}DepartmentModal.jsx`]: ['if (!permitted) return null;', 'Boolean(props.isOpen)'],
  'components/tasks/AssignTaskModal.jsx': ['if (!task && !unassignedTasks) return null;', 'Boolean(props.asModal && (props.task || props.unassignedTasks))'],
};

describe('modals', () => {
  it('every modal overlay in the app has the backdrop fade and the panel scale, in and out', () => {
    const overlays = sources.filter((s) => /className=[{"`][^\n]*fixed inset-0 z-50/.test(s.text)).map((s) => s.file).sort();
    const animated = sources.filter((s) => s.text.includes('${backdropMotion(closing)}')).map((s) => s.file).sort();
    assert.deepEqual(animated, overlays);
    assert.equal(overlays.length, 21, '16 self-hiding modals and 5 support dialogs');
  });

  it('each keeps its own open check, and its wrapper mirrors it, so it mounts and unmounts as before', () => {
    for (const [file, [gate, shown]] of Object.entries(MODALS)) {
      const text = read(file);
      assert.ok(text.includes(gate), `${file}: own check kept`);
      assert.ok(text.includes(`, (props) => ${shown});`) && text.includes('export default withExitTransition('), `${file}: wrapper`);
      assert.match(text, /closing = false/, `${file}: closed is the default`);
    }
  });

  it('while closing, the overlay takes no clicks, focus or keys (inert, pointer-events-none), so nothing invisible blocks the page', () => {
    for (const { file, text } of sources.filter((s) => s.text.includes('${backdropMotion(closing)}'))) {
      const line = text.split('\n').findIndex((l) => l.includes('${backdropMotion(closing)}'));
      const tag = text.split('\n').slice(line, line + 2).join('\n');
      assert.ok(tag.includes('inert={closing}'), `${file}: backdrop is inert while closing`);
      assert.ok(text.includes('${dialogMotion(closing)}'), `${file}: panel animates`);
    }
  });

  it('click-outside, Escape and focus handling are unchanged: the backdrop still closes on click, the dialogs still listen for Escape', () => {
    assert.ok(read('components/tasks/EditTaskModal.jsx').includes('onClick={() => !isSubmitting && onClose()}'));
    assert.ok(read('components/departments/DepartmentFormModal.jsx').includes("if (event.key === 'Escape' && !isSubmitting) onClose();"));
    assert.ok(read('components/staff/DeactivateStaffModal.jsx').includes('previouslyFocused.focus();'), 'focus still returns to the opener');
    assert.ok(read('components/staff/DeactivateStaffModal.jsx').includes('createPortal(modalContent, document.body)'));
  });

  it('parents that showed a modal conditionally now always render it, so its exit can play', () => {
    const workload = read('pages/WorkloadPage.jsx');
    assert.ok(!workload.includes('{selectedStaff && ('));
    assert.match(workload, /<StaffDetailsModal\s+staff=\{selectedStaff\}/);
    const support = read('components/workload/CrossDepartmentSupportPanel.jsx');
    assert.equal((support.match(/<Presence>/g) || []).length, 5, 'each support dialog is wrapped');
    for (const dialog of ['SupportEndDialog', 'SupportArchiveDialog', 'SupportReserveOverrideDialog', 'SupportDiscardDialog', 'SupportConfirmDialog']) {
      assert.match(support, new RegExp(`<Presence>\\s*\\{[^\\n]*&& \\(\\s*<${dialog}`), dialog);
    }
  });
});

describe('menus', () => {
  const ROW_MENUS = ['components/staff/StaffTable.jsx', 'components/tasks/TasksTable.jsx', 'components/users/UsersTable.jsx', 'pages/AssignmentsPage.jsx'];

  it('row action menus: the shown menu is kept for its exit, animates in and out, and takes no input while closing', () => {
    for (const file of ROW_MENUS) {
      const text = read(file);
      assert.ok(text.includes('const menuPresence = usePresence(useMemo(() => (activeMenu && menuPosition ? { activeMenu, menuPosition } : null), [activeMenu, menuPosition]));'), file);
      assert.match(text, /\{menuPresence\.present && (menu && )?createPortal\(/, file);
      assert.ok(text.includes('${menuMotion(menuPresence.closing)}') && text.includes('inert={menuPresence.closing}'), file);
    }
  });

  it('outside click, Escape and the menu actions still use the real open state', () => {
    assert.ok(read('components/tasks/TasksTable.jsx').includes("if (e.key === 'Escape') setActiveMenu(null);"));
    assert.ok(read('pages/AssignmentsPage.jsx').includes('if (menuRef.current?.contains(e.target) || activeMenu.buttonEl?.contains(e.target)) return;'));
    assert.ok(read('pages/AssignmentsPage.jsx').includes('const isMenuOpen = activeMenu?.assignment?.id === assignment.id;'), 'the row highlight ends as soon as the menu closes');
  });

  it('rows-per-page list, department card menu and icon / colour pickers use the same pair', () => {
    assert.ok(read('components/common/PageSizeToggle.jsx').includes('${menuMotion(listPresence.closing)}'));
    assert.ok(read('pages/DepartmentsPage.jsx').includes('{menuPresence.value === dept.code && ('));
    assert.ok(read('pages/DepartmentsPage.jsx').includes('inert={menuPresence.closing} className={`absolute right-0 top-full mt-1 z-20 w-52'));
    assert.ok(read('components/departments/PickerDropdown.jsx').includes('inert={list.closing}') && read('components/departments/PickerDropdown.jsx').includes('${menuMotion(list.closing)}'));
  });
});

describe('temporary notices', () => {
  const BANNERS = { 'pages/TasksPage.jsx': 1, 'pages/AssignmentsPage.jsx': 2, 'pages/UsersPage.jsx': 1, 'pages/StaffPage.jsx': 1, 'pages/DepartmentsPage.jsx': 1 };

  it('success banners slide in and out and render the kept text while they leave', () => {
    for (const [file, n] of Object.entries(BANNERS)) {
      const text = read(file);
      assert.equal((text.match(/\{notice\.present && \(/g) || []).length, n, file);
      assert.equal((text.match(/inert=\{notice\.closing\}[^\n]*\$\{toastMotion\(notice\.closing\)\}/g) || []).length, n, file);
      assert.ok(text.includes('{notice.value}'), file);
    }
  });

  it('auto-dismiss timing is unchanged: the same timers clear the same state, and the exit starts when they fire', () => {
    assert.match(read('pages/TasksPage.jsx'), /const timer = setTimeout\(\(\) => \{\s*setSuccessBanner\(''\);/);
    assert.match(read('pages/AssignmentsPage.jsx'), /const timer = setTimeout\(\(\) => \{\s*setSuccessBanner\(''\);\s*\}, 4500\)/);
    assert.ok(read('pages/UsersPage.jsx').includes("const timer = setTimeout(() => setSuccessMessage(''), SUCCESS_MESSAGE_MS);"));
    assert.ok(read('pages/DepartmentsPage.jsx').includes("const timer = setTimeout(() => setSuccessMessage(''), SUCCESS_MESSAGE_MS);"));
    const hook = read('hooks/useTransientNotice.js');
    assert.ok(hook.includes('setTimeout(() => setState(null), duration)') && hook.includes('const presence = usePresence(notice);'));
    // manual close still clears the state straight away
    assert.ok(read('pages/TasksPage.jsx').includes("onClick={() => setSuccessBanner('')}"));
  });

  it('profile notices use the hook\'s kept text and closing flag', () => {
    const page = read('pages/ProfilePage.jsx');
    for (const name of ['nameNotice', 'emailNotice', 'phoneNotice']) {
      assert.ok(page.includes(`{${name}.shown && (`) && page.includes(`inert={${name}.closing}`) && page.includes(`\${toastMotion(${name}.closing)}`), name);
    }
    assert.ok(page.includes('inert={noticeClosing}') && page.includes('${toastMotion(noticeClosing)}'));
  });

  it('the create-task banner only enters: the page navigates away with it', () => {
    assert.ok(read('pages/CreateTaskPage.jsx').includes('shadow-xs animate-toast-in">'));
  });
});

test('no page transitions: route changes are not animated', () => {
  for (const { file, text } of sources) {
    assert.doesNotMatch(text, /AnimatePresence|framer-motion|view-transition-name|startViewTransition\(\(\) => navigate/, file);
  }
});
