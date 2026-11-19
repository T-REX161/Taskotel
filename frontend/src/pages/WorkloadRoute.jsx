import React from 'react';
import { getUserRole } from '../services/api';
import { normalizeRole } from '../utils/accessPolicy';
import WorkloadPage from './WorkloadPage';
import MyWorkloadPage from './MyWorkloadPage';

/**
 * The /workload screen. A Staff member gets their own workload page (no department cards, no other person's workload); Admin and Manager
 * keep the existing Workload page, unchanged. This only picks the screen: the backend already returns a Staff member nothing but their own records.
 */
export default function WorkloadRoute() {
  return normalizeRole(getUserRole()) === 'STAFF' ? <MyWorkloadPage /> : <WorkloadPage />;
}
