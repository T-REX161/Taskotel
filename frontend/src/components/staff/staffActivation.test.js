import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

describe('Staff Activation and Deactivation Frontend Functionality', () => {
  const staffTableSource = fs.readFileSync(path.join(__dirname, 'StaffTable.jsx'), 'utf8');
  const deactivateModalSource = fs.readFileSync(path.join(__dirname, 'DeactivateStaffModal.jsx'), 'utf8');
  const staffPageSource = fs.readFileSync(path.join(__dirname, '../../pages/StaffPage.jsx'), 'utf8');
  const apiServiceSource = fs.readFileSync(path.join(__dirname, '../../services/api.js'), 'utf8');

  describe('api.js activation and deactivation endpoints', () => {
    it('defines and exports activateStaff with PATCH method to /api/staff/:id/activate', () => {
      assert.ok(apiServiceSource.includes('export const activateStaff'), 'activateStaff must be exported from api.js');
      assert.ok(
        /apiFetch\(`\/api\/staff\/\$\{id\}\/activate`,\s*\{\s*method:\s*['"]PATCH['"]/.test(apiServiceSource),
        'activateStaff must perform a PATCH request to /api/staff/:id/activate'
      );
    });

    it('defines and exports deactivateStaff with PATCH method to /api/staff/:id/deactivate', () => {
      assert.ok(apiServiceSource.includes('export const deactivateStaff'), 'deactivateStaff must be exported from api.js');
      assert.ok(
        /apiFetch\(`\/api\/staff\/\$\{id\}\/deactivate`,\s*\{\s*method:\s*['"]PATCH['"]/.test(apiServiceSource),
        'deactivateStaff must perform a PATCH request to /api/staff/:id/deactivate'
      );
    });

    it('verifies frontend endpoints match backend StaffController mappings exactly', () => {
      const backendControllerPath = path.resolve(__dirname, '../../../../backend/src/main/java/com/hotel/workload/staff/StaffController.java');
      if (fs.existsSync(backendControllerPath)) {
        const controllerSource = fs.readFileSync(backendControllerPath, 'utf8');
        assert.ok(controllerSource.includes('@RequestMapping("/api/staff")'), 'Backend must map controller to /api/staff');
        assert.ok(
          controllerSource.includes('@PatchMapping("/{id}/activate"') || controllerSource.includes('PatchMapping("/{id}/activate")'),
          'Backend must declare @PatchMapping("/{id}/activate") matching frontend activateStaff(id)'
        );
        assert.ok(
          controllerSource.includes('@PatchMapping("/{id}/deactivate"') || controllerSource.includes('PatchMapping("/{id}/deactivate")'),
          'Backend must declare @PatchMapping("/{id}/deactivate") matching frontend deactivateStaff(id)'
        );
      }
    });
  });

  describe('StaffTable action menu activation & deactivation buttons', () => {
    it('contains both Activate and Deactivate action buttons', () => {
      assert.ok(staffTableSource.includes('Deactivate'), 'StaffTable must render Deactivate action button');
      assert.ok(staffTableSource.includes('Activate'), 'StaffTable must render Activate action button');
    });

    it('conditionally renders Deactivate for active staff and Activate for inactive staff', () => {
      const conditionMatch = staffTableSource.includes('isActive(activeMenu.staff.active)');
      assert.ok(conditionMatch, 'StaffTable must check isActive(activeMenu.staff.active) for toggling between Deactivate and Activate');
    });

    it('preserves all existing actions', () => {
      assert.ok(staffTableSource.includes('View Details'), 'View Details action must be preserved');
      assert.ok(staffTableSource.includes('Edit Staff'), 'Edit Staff action must be preserved');
      assert.ok(staffTableSource.includes('Remove Staff'), 'Remove Staff action must be preserved');
    });
  });

  describe('StaffPage activation and deactivation flows', () => {
    it('imports activateStaff and deactivateStaff in StaffPage', () => {
      assert.ok(staffPageSource.includes('activateStaff'), 'StaffPage must import activateStaff');
      assert.ok(staffPageSource.includes('deactivateStaff'), 'StaffPage must import deactivateStaff');
    });

    it('handleActivate invokes activateStaff(staff.id) and refreshes staff list without altering availability', () => {
      const activateHandlerMatch = staffPageSource.match(/const handleActivate = async \(\w+\) => \{([\s\S]*?)\};/);
      assert.ok(activateHandlerMatch, 'handleActivate function must exist in StaffPage.jsx');
      const body = activateHandlerMatch[1];
      assert.ok(body.includes('await activateStaff('), 'handleActivate must call activateStaff');
      assert.ok(body.includes('fetchStaffData('), 'handleActivate must refresh staff data');
      assert.ok(body.includes('showNotification('), 'handleActivate must show success notification');
      assert.ok(!body.includes('updateStaff('), 'handleActivate must NOT call updateStaff with full body');
      assert.ok(!body.includes('availability:'), 'handleActivate must NOT mutate availability');
    });

    it('handleConfirmDeactivate invokes deactivateStaff(deactivatingStaff.id) and refreshes staff list', () => {
      const deactivateHandlerMatch = staffPageSource.match(/const handleConfirmDeactivate = async \(\) => \{([\s\S]*?)\};/);
      assert.ok(deactivateHandlerMatch, 'handleConfirmDeactivate function must exist in StaffPage.jsx');
      const body = deactivateHandlerMatch[1];
      assert.ok(body.includes('await deactivateStaff('), 'handleConfirmDeactivate must call deactivateStaff');
      assert.ok(body.includes('fetchStaffData('), 'handleConfirmDeactivate must refresh staff data');
      assert.ok(body.includes('showNotification('), 'handleConfirmDeactivate must show success notification');
    });

    it('preserves DeactivateStaffModal wiring and props in StaffPage', () => {
      assert.ok(staffPageSource.includes('<DeactivateStaffModal'), 'DeactivateStaffModal must be rendered');
      assert.ok(staffPageSource.includes('isOpen={Boolean(deactivatingStaff)}'), 'isOpen wired to deactivatingStaff');
      assert.ok(staffPageSource.includes('onClose={handleCloseDeactivateModal}'), 'onClose wired');
      assert.ok(staffPageSource.includes('onConfirm={handleConfirmDeactivate}'), 'onConfirm wired');
    });
  });

  describe('DeactivateStaffModal specifications & accessibility', () => {
    it('has the title "Deactivate Staff Member?"', () => {
      assert.ok(deactivateModalSource.includes('Deactivate Staff Member?'), 'Modal title must be "Deactivate Staff Member?"');
    });

    it('identifies selected staff member by name', () => {
      assert.ok(
        deactivateModalSource.includes('staffName') || deactivateModalSource.includes('staff.fullName'),
        'Modal must identify the staff member by name'
      );
    });

    it('explains task assignment impact and later reactivation', () => {
      assert.ok(
        deactivateModalSource.includes('no longer be eligible for new task assignments'),
        'Modal must explain that staff will no longer be eligible for task assignments'
      );
      assert.ok(
        deactivateModalSource.includes('reactivate them later'),
        'Modal must explain that staff can be reactivated later'
      );
    });

    it('provides Cancel and Deactivate Staff buttons with disabled state during submission', () => {
      assert.ok(deactivateModalSource.includes('Cancel'), 'Cancel button must be present');
      assert.ok(deactivateModalSource.includes('Deactivate Staff'), 'Deactivate Staff button must be present');
      assert.ok(deactivateModalSource.includes('disabled={isSubmitting}'), 'Buttons must be disabled while submitting');
    });

    it('renders error alert banner with role="alert" when error occurs', () => {
      assert.ok(deactivateModalSource.includes('role="alert"'), 'Error banner must have role="alert"');
      assert.ok(deactivateModalSource.includes('{error}'), 'Error message must be rendered');
    });
  });
});
