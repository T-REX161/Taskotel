import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// Line endings are normalised: git stores LF, but a Windows checkout (core.autocrlf=true) writes CRLF, and the
// multi-line source checks below must give the same result either way
const read = (...parts) => fs.readFileSync(path.join(__dirname, ...parts), 'utf8').replace(/\r\n/g, '\n');
const pageSource = read('DepartmentsPage.jsx');
const modalSource = read('..', 'components', 'departments', 'DepartmentFormModal.jsx');
const pickerSource = read('..', 'components', 'departments', 'PickerDropdown.jsx');
const apiSource = read('..', 'services', 'api.js');
const cardsStart = pageSource.indexOf('{departments.map((dept) => {');
const cards = pageSource.slice(cardsStart, pageSource.indexOf('})}', cardsStart));

describe('DepartmentsPage', () => {
  it('keeps the three summary cards, counting active departments and the real staff and task totals', () => {
    for (const title of ["title: 'Total Departments'", "title: 'Active Staff'", "title: 'Active Tasks'"]) {
      assert.ok(pageSource.includes(title), title);
    }
    assert.ok(pageSource.includes("departments.filter((dept) => dept.active !== false).length"), 'Total counts active departments');
    assert.ok(pageSource.includes('sum + (Number(dept.staffCount) || 0)'));
    assert.ok(pageSource.includes('sum + (Number(dept.activeTasks) || 0)'));
    assert.ok(pageSource.indexOf('{/* Summary Cards */}') < pageSource.indexOf('{/* Departments Grid or Empty State */}'), 'Summary cards stay above the grid');
  });

  it('renders one card per department from the fetched data in a wrapping responsive grid', () => {
    assert.ok(pageSource.includes('const data = await getDepartments();'), 'Data still comes from the API');
    assert.ok(cardsStart > 0, 'Cards are generated from the departments array');
    assert.ok(pageSource.includes('grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3'), 'Three columns on desktop, fewer on smaller screens');
    assert.ok(!/HOUSEKEEPING|FRONT_DESK|MAINTENANCE|ROOM_SERVICE|SECURITY/.test(pageSource), 'No department is hardcoded on the page');
  });

  it('gives each card an icon tile styled from the department\'s stored keys, the name, a divider, and staff / active-task rows', () => {
    assert.ok(cards.includes('getDepartmentStyle(dept)'), 'Colour comes from the department\'s stored keys');
    assert.ok(cards.includes('<DepartmentIcon department={dept}'), 'Icon comes from the department\'s stored keys');
    assert.ok(!/\bidx\b|\bindex\b/.test(cards), 'Nothing is chosen by position');
    assert.ok(cards.includes('{dept.name}') && cards.includes('break-words'), 'Long names wrap');
    assert.ok(cards.includes('border-t border-gray-100'), 'Divider under the heading');
    assert.ok(cards.includes('<StaffIcon') && cards.includes('>Staff</dt>') && cards.includes('{Number(dept.staffCount) || 0}'));
    assert.ok(cards.includes('<TasksIcon') && cards.includes('>Active Tasks</dt>') && cards.includes('{Number(dept.activeTasks) || 0}'));
    assert.ok(cards.includes('Inactive'), 'Inactive departments are marked');
  });

  it('shows Add Department only to ADMIN and MANAGER, and opens the form modal', () => {
    assert.ok(pageSource.includes("role === 'ADMIN' || role === 'MANAGER'"), 'Role gate (the server enforces it too)');
    assert.ok(/\{canManage && \(\s*<button[^>]*onClick=\{openCreate\}/.test(pageSource), 'The button is behind the role gate and opens the dialog');
    assert.ok(pageSource.includes('<PlusIcon') && pageSource.includes('Add Department'));
    assert.ok(pageSource.includes('bg-emerald-600'), 'Emerald button');
    assert.ok(pageSource.includes('<DepartmentFormModal'), 'The modal is rendered');
  });

  it('after saving, refreshes the list in place and shows a success message', () => {
    assert.ok(/const handleSaved = \(saved, mode\) => \{[\s\S]*fetchDepartments\(true\);/.test(pageSource), 'The list is refetched from the API');
    assert.ok(pageSource.includes('role="status"') && pageSource.includes('was created.'), 'Success message');
    assert.ok(pageSource.includes('if (!silent) {'), 'A silent refresh does not replace the page with the spinner');
  });

  it('the card menu only offers an action that is really implemented (edit)', () => {
    assert.ok(pageSource.includes('aria-haspopup="menu"') && pageSource.includes('Edit department'));
    assert.ok(pageSource.includes('onClick={() => openEdit(dept)}'));
    assert.ok(/\{canManage && dept\.code && \(/.test(pageSource), 'Menu is only for managers');
  });
});

describe('DepartmentFormModal', () => {
  it('has every field of the department form and the two buttons', () => {
    for (const label of ['Department Name *', 'Description', 'Icon', 'Color', 'Department Head (Optional)', 'Status *']) {
      assert.ok(modalSource.includes(label), label);
    }
    assert.ok(modalSource.includes('Cancel') && modalSource.includes("'Add Department'") && modalSource.includes("'Save Changes'"));
    assert.ok(modalSource.includes("label: 'Active'") && modalSource.includes("label: 'Inactive'"), 'Active and Inactive options side by side');
    assert.ok(modalSource.includes('role="radiogroup"') && modalSource.includes('aria-checked={selected}'), 'Status is an accessible radio group');
    assert.ok(modalSource.includes('Add New Department') && modalSource.includes('Create a new department for hotel operations.'), 'Header title and subtitle');
    assert.ok(modalSource.includes('{formData.description.length}/{DESCRIPTION_MAX}'), 'Description character counter');
    assert.ok(modalSource.includes('id="department-icon"') && modalSource.includes('id="department-color"'), 'Icon and Color dropdowns');
    assert.ok(modalSource.includes('<PickerDropdown') && pickerSource.includes('role="listbox"') && pickerSource.includes('aria-haspopup="listbox"'), 'Graphic-only dropdowns');
    const iconPicker = modalSource.slice(modalSource.indexOf('id="department-icon"'), modalSource.indexOf('id="department-color-label"'));
    const colorPicker = modalSource.slice(modalSource.indexOf('id="department-color"'), modalSource.indexOf('Preview</span>'));
    assert.ok(!/>\s*\{ICON_LABELS/.test(iconPicker), 'Icon options show no text');
    assert.ok(!/>\s*\{COLOR_STYLES\[key\]\.label\}\s*</.test(colorPicker), 'Colour options show no text');
    assert.ok(iconPicker.includes("'border-emerald-500 bg-emerald-50 text-emerald-700'"), 'Selected icon: green border, light-green background');
    assert.ok(modalSource.includes('active: true'), 'Status defaults to Active');
  });

  it('previews the chosen icon and colour live and offers only the supported keys', () => {
    assert.ok(modalSource.includes('Preview') && modalSource.includes('aria-live="polite"'));
    assert.ok(modalSource.includes('ICON_KEYS.map') && modalSource.includes('COLOR_KEYS.map'));
    assert.ok(modalSource.includes('<DepartmentIcon iconKey={formData.iconKey}'));
    assert.ok(modalSource.includes('COLOR_STYLES[formData.colorKey]'));
  });

  it('validates the name before saving and checks duplicates without regard to case', () => {
    assert.ok(modalSource.includes('Department name is required.'));
    assert.ok(modalSource.includes('const NAME_MIN = 2;') && modalSource.includes('const NAME_MAX = 100;'));
    assert.ok(modalSource.includes('existing.name.trim().toLowerCase() === name.toLowerCase()'));
    assert.ok(/if \(nameError\) \{\s*setFormError\(nameError\);\s*return;/.test(modalSource), 'An invalid form is not sent');
  });

  it('saves through the real API, handles each error status, and shows loading', () => {
    assert.ok(modalSource.includes('await updateDepartment(department.code, payload) : await createDepartment(payload)'));
    for (const status of ['409', '403', '404', '400']) {
      assert.ok(modalSource.includes(`err.status === ${status}`), `Handles ${status}`);
    }
    assert.ok(modalSource.includes('isSubmitting') && modalSource.includes('animate-spin'), 'Loading state');
    assert.ok(modalSource.includes("'Adding Department...'"));
    assert.ok(modalSource.includes('onSaved(saved, isEdit ? \'edit\' : \'create\')'));
  });

  it('Cancel and Escape close it without saving, and it is accessible', () => {
    assert.ok(modalSource.includes('onClick={handleClose}'));
    assert.ok(modalSource.includes("event.key === 'Escape'"));
    assert.ok(modalSource.includes('role="dialog"') && modalSource.includes('aria-modal="true"') && modalSource.includes('aria-labelledby'));
  });

  it('keeps every hook above the early return', () => {
    const early = modalSource.indexOf('if (!isOpen) return null;');
    assert.ok(early > 0);
    assert.ok(!/use(State|Effect|Memo)\(/.test(modalSource.slice(early)), 'No hook after the early return');
  });
});

describe('Remove Department', () => {
  const removeSource = read('..', 'components', 'departments', 'RemoveDepartmentModal.jsx');

  it('the card menu offers Remove Department in red with a trash icon, below Edit', () => {
    assert.ok(pageSource.indexOf('Edit department') < pageSource.indexOf('Remove Department'));
    assert.ok(pageSource.includes('onClick={() => openRemove(dept)}'));
    assert.ok(/text-red-600 hover:bg-red-50[^"]*"\s*>\s*<TrashIcon/.test(pageSource), 'Red, with a trash icon');
    assert.ok(/const openRemove = \(department\) => \{\s*setOpenMenuCode\(null\);/.test(pageSource), 'The menu closes before the dialog opens');
  });

  it('asks for confirmation with the department name, and only the confirm button deletes', () => {
    assert.ok(removeSource.includes('Remove Department?'));
    assert.ok(removeSource.includes('Are you sure you want to remove the &quot;') && removeSource.includes('{department.name}'));
    assert.ok(removeSource.includes('action may affect staff and task assignments associated with it.'));
    assert.ok(removeSource.includes('bg-red-50 text-red-600') && removeSource.includes('<TrashIcon'), 'Trash icon in a red-tinted tile');
    assert.strictEqual((removeSource.match(/deleteDepartment\(/g) || []).length, 1, 'One call site');
    const handleClose = removeSource.slice(removeSource.indexOf('const handleClose'), removeSource.indexOf('const handleRemove'));
    assert.ok(!handleClose.includes('deleteDepartment'), 'Cancel, close and outside clicks never delete');
    assert.ok(/onClick=\{handleClose\}[\s\S]*?Cancel/.test(removeSource) && removeSource.includes('onClick={handleRemove}'));
    assert.ok(removeSource.includes("if (event.key === 'Escape' && !isRemoving) onClose();"), 'Escape and outside clicks are ignored while removing');
  });

  it('shows progress, reports the server explanation on failure, and refreshes after success', () => {
    assert.ok(removeSource.includes('Removing...') && removeSource.includes('animate-spin'));
    assert.ok(removeSource.includes('err.status === 409') && removeSource.includes('setError(err.message'), 'In-use / built-in explanation from the server');
    assert.ok(/await deleteDepartment\(department\.code\);\s*inFlight\.current = false;\s*onRemoved\(department\);/.test(removeSource), 'Success only after the server confirms');
    assert.ok(/const handleRemoved = \(removed\) => \{[\s\S]*?was removed\.[\s\S]*?fetchDepartments\(true\);/.test(pageSource), 'Cards and counts refresh from the API');
    assert.ok(apiSource.includes('export const deleteDepartment = (code)') && /method: 'DELETE'/.test(apiSource));
  });

  it('sends at most one request per confirmation, and no placeholder remains', () => {
    assert.ok(/if \(inFlight\.current\) return;\s*inFlight\.current = true;/.test(removeSource), 'A second click is ignored immediately');
    assert.ok(/disabled=\{isRemoving\}/.test(removeSource), 'The buttons are disabled while removing');
    assert.ok(!removeSource.includes('does not support removing departments'), 'The real endpoint is used');
    assert.ok(removeSource.includes('err.status === 405') && removeSource.includes('Restart the backend'), 'An outdated server is explained, not shown as a bare "Method Not Allowed"');
  });
});

describe('department API wrappers', () => {
  it('create and update use the department endpoints with JSON bodies', () => {
    assert.ok(apiSource.includes("export const createDepartment = async (request)"));
    assert.ok(apiSource.includes("apiFetch('/api/departments', {\n    method: 'POST',"));
    assert.ok(apiSource.includes('export const updateDepartment = async (code, request)'));
    assert.ok(apiSource.includes('`/api/departments/${encodeURIComponent(code)}`') && apiSource.includes("method: 'PUT'"));
  });
});
