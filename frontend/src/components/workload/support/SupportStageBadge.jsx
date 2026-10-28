import React from 'react';
import { STAGE_STYLES } from '../../../utils/supportPlan';

const LABELS = { recommended: 'Suggested', selected: 'In plan', active: 'Active', completed: 'Completed', ended: 'Ended', cancelled: 'Cancelled' };

/** Distinguishes suggested, selected (unconfirmed), active, completed and ended support at a glance. */
export default function SupportStageBadge({ stage, title }) {
  return (
    <span
      title={title}
      className={`inline-flex items-center px-1.5 py-px rounded-md border text-[11px] leading-4 font-semibold whitespace-nowrap ${STAGE_STYLES[stage] || STAGE_STYLES.cancelled}`}
    >
      {LABELS[stage] || stage}
    </span>
  );
}
