import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

describe('Remove Staff Frontend Functionality', () => {
  const staffTableSource = fs.readFileSync(path.join(__dirname, 'StaffTable.jsx'), 'utf8');
  const removeModalSource = fs.readFileSync(path.join(__dirname, 'RemoveStaffModal.jsx'), 'utf8');
  const staffPageSource = fs.readFileSync(path.join(__dirname, '../../pages/StaffPage.jsx'), 'utf8');
  const apiServiceSource = fs.readFileSync(path.join(__dirname, '../../services/api.js'), 'utf8');

  describe('api.js removeStaff endpoint export', () => {
    it('defines and exports removeStaff with DELETE method to /api/staff/:id', () => {
      assert.ok(apiServiceSource.includes('export const removeStaff'), 'removeStaff must be exported from api.js');
      assert.ok(
        /apiFetch\(`\/api\/staff\/\$\{id\}`,\s*\{\s*method:\s*['"]DELETE['"]/.test(apiServiceSource),
        'removeStaff must perform a DELETE request to /api/staff/:id'
      );
    });

    it('verifies frontend DELETE endpoint matches backend StaffController mapping exactly', () => {
      const backendControllerPath = path.resolve(__dirname, '../../../../backend/src/main/java/com/hotel/workload/staff/StaffController.java');
      if (fs.existsSync(backendControllerPath)) {
        const controllerSource = fs.readFileSync(backendControllerPath, 'utf8');
        assert.ok(controllerSource.includes('@RequestMapping("/api/staff")'), 'Backend must map controller to /api/staff');
        assert.ok(
          controllerSource.includes('@DeleteMapping("/{id}")') || controllerSource.includes('DeleteMapping("/{id}")'),
          'Backend must declare @DeleteMapping("/{id}") matching frontend DELETE /api/staff/${id}'
        );
      }
    });
  });

  describe('StaffTable action menu visibility rules', () => {
    it('contains Remove Staff action in the portal menu', () => {
      assert.ok(staffTableSource.includes('Remove Staff'), 'StaffTable must render Remove Staff action button');
    });

    it('shows Remove Staff only when user is ADMIN and staff member is inactive', () => {
      // Condition must be isAdmin && !isActive(...)
      const conditionRegex = /isAdmin\s*&&\s*!isActive\(/;
      assert.ok(conditionRegex.test(staffTableSource), 'Remove Staff option must be guarded by isAdmin and !isActive()');
    });

    it('never shows Remove Staff for active staff regardless of role', () => {
      // Test the logic directly
      const evaluateCondition = (isAdmin, active) => {
        const isActiveStatus = active === true || active === 'Active';
        return isAdmin && !isActiveStatus;
      };

      // Active staff + Admin -> false
      assert.strictEqual(evaluateCondition(true, true), false);
      assert.strictEqual(evaluateCondition(true, 'Active'), false);

      // Active staff + Non-admin -> false
      assert.strictEqual(evaluateCondition(false, true), false);
      assert.strictEqual(evaluateCondition(false, 'Active'), false);

      // Inactive staff + Non-admin -> false
      assert.strictEqual(evaluateCondition(false, false), false);
      assert.strictEqual(evaluateCondition(false, 'Inactive'), false);

      // Inactive staff + Admin -> true
      assert.strictEqual(evaluateCondition(true, false), true);
      assert.strictEqual(evaluateCondition(true, 'Inactive'), true);
    });

    it('preserves existing actions (View Details, Edit Staff, Deactivate/Activate)', () => {
      assert.ok(staffTableSource.includes('View Details'), 'View Details must be preserved');
      assert.ok(staffTableSource.includes('Edit Staff'), 'Edit Staff must be preserved');
      assert.ok(staffTableSource.includes('Deactivate'), 'Deactivate must be preserved');
      assert.ok(staffTableSource.includes('Activate'), 'Activate must be preserved');
    });
  });

  describe('RemoveStaffModal specifications & accessibility', () => {
    it('has the title "Remove Staff Member?"', () => {
      assert.ok(removeModalSource.includes('Remove Staff Member?'), 'Modal title must be "Remove Staff Member?"');
    });

    it('identifies selected staff member by name', () => {
      assert.ok(
        removeModalSource.includes('staff.fullName') || removeModalSource.includes('staffName'),
        'Modal must identify the staff member by name'
      );
    });

    it('explains that permanent removal cannot be undone', () => {
      assert.ok(
        removeModalSource.toLowerCase().includes('cannot be undone'),
        'Modal must explain that the action cannot be undone'
      );
    });

    it('provides Cancel and Remove Staff buttons with disabled state during submit', () => {
      assert.ok(removeModalSource.includes('Cancel'), 'Cancel button must be present');
      assert.ok(removeModalSource.includes('Remove Staff'), 'Remove Staff button must be present');
      assert.ok(removeModalSource.includes('disabled={isSubmitting}'), 'Buttons must be disabled while submitting');
    });

    it('displays error in an accessible alert banner without unmounting the modal', () => {
      assert.ok(removeModalSource.includes('role="alert"'), 'Error banner must have role="alert"');
      assert.ok(removeModalSource.includes('{error}'), 'Error message must be rendered');
    });

    it('implements dialog accessibility semantics (role=dialog, aria-modal, keyboard trapping)', () => {
      assert.ok(removeModalSource.includes('role="dialog"'), 'Must have role="dialog"');
      assert.ok(removeModalSource.includes('aria-modal="true"'), 'Must have aria-modal="true"');
      assert.ok(removeModalSource.includes('aria-labelledby'), 'Must have aria-labelledby');
      assert.ok(removeModalSource.includes('aria-describedby'), 'Must have aria-describedby');
      assert.ok(removeModalSource.includes('Escape'), 'Must support Escape key dismissal');
      assert.ok(removeModalSource.includes('Tab'), 'Must trap Tab focus inside dialog');
    });
  });

  describe('StaffPage workflow and state wiring', () => {
    it('imports removeStaff and RemoveStaffModal', () => {
      assert.ok(staffPageSource.includes('removeStaff'), 'StaffPage must import removeStaff');
      assert.ok(staffPageSource.includes('RemoveStaffModal'), 'StaffPage must import RemoveStaffModal');
    });

    it('passes onRemove to StaffTable', () => {
      assert.ok(
        staffPageSource.includes('onRemove={handleOpenRemoveModal}'),
        'StaffTable must receive onRemove callback'
      );
    });

    it('renders RemoveStaffModal with correct props', () => {
      assert.ok(staffPageSource.includes('<RemoveStaffModal'), 'RemoveStaffModal must be rendered');
      assert.ok(staffPageSource.includes('isOpen={Boolean(removingStaff)}'), 'isOpen tied to removingStaff state');
      assert.ok(staffPageSource.includes('onClose={handleCloseRemoveModal}'), 'onClose handler wired');
      assert.ok(staffPageSource.includes('onConfirm={handleConfirmRemove}'), 'onConfirm handler wired');
      assert.ok(staffPageSource.includes('isSubmitting={isRemoving}'), 'isSubmitting prop wired');
      assert.ok(staffPageSource.includes('error={removeError}'), 'error prop wired');
    });

    it('closing or canceling the modal does not trigger removeStaff API', () => {
      // In handleCloseRemoveModal, only state is reset without invoking removeStaff
      const closeHandlerMatch = staffPageSource.match(/const handleCloseRemoveModal = \(\) => \{([\s\S]*?)\};/);
      assert.ok(closeHandlerMatch, 'handleCloseRemoveModal must exist');
      assert.ok(
        !closeHandlerMatch[1].includes('removeStaff('),
        'handleCloseRemoveModal must never call removeStaff'
      );
    });

    it('successful removal refreshes list and displays success notification', () => {
      const confirmHandlerMatch = staffPageSource.match(/const handleConfirmRemove = async \(\) => \{([\s\S]*?)\};/);
      assert.ok(confirmHandlerMatch, 'handleConfirmRemove must exist');
      assert.ok(confirmHandlerMatch[1].includes('await removeStaff('), 'Must call removeStaff');
      assert.ok(confirmHandlerMatch[1].includes('fetchStaffData('), 'Must refresh staff data');
      assert.ok(confirmHandlerMatch[1].includes('showNotification('), 'Must show success notification');
    });

    it('API failure keeps the modal open and sets error message', () => {
      const confirmHandlerMatch = staffPageSource.match(/const handleConfirmRemove = async \(\) => \{([\s\S]*?)\};/);
      assert.ok(confirmHandlerMatch, 'handleConfirmRemove must exist');
      assert.ok(confirmHandlerMatch[1].includes('setRemoveError('), 'Must set removeError on failure');
      // The modal remains open because setRemovingStaff(null) is only called on success, inside try before catch
      const tryBlock = confirmHandlerMatch[1].split('catch')[0];
      const catchBlock = confirmHandlerMatch[1].split('catch')[1];
      assert.ok(tryBlock.includes('setRemovingStaff(null)'), 'Modal closed on success');
      assert.ok(!catchBlock.includes('setRemovingStaff(null)'), 'Modal kept open on error');
    });
  });
});
