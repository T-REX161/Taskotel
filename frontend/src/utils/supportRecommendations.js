// Helpers for the Cross-Department Support panel on the Workload page.
// The response is a recommendation only: nothing in it has been approved, assigned or moved.

const SEVERITY_LABELS = { CRITICAL: 'Critical', HIGH: 'High', MODERATE: 'Moderate' };

const SEVERITY_BADGES = {
  CRITICAL: 'bg-red-50 text-red-700 border-red-200',
  HIGH: 'bg-amber-50 text-amber-700 border-amber-200',
  MODERATE: 'bg-gray-100 text-gray-600 border-gray-200',
};

// The redesigned panel keeps red for ending actions and errors, so workload pressure is shown in amber: a stronger
// amber for Critical, a lighter one for High, and a neutral tone for Moderate. The badge classes above are unchanged.
const SEVERITY_TONES = {
  CRITICAL: { chip: 'bg-amber-100 text-amber-900 border-amber-300', dot: 'bg-amber-500', accent: 'border-l-amber-500' },
  HIGH: { chip: 'bg-amber-50 text-amber-800 border-amber-200', dot: 'bg-amber-400', accent: 'border-l-amber-400' },
  MODERATE: { chip: 'bg-gray-100 text-gray-600 border-gray-200', dot: 'bg-gray-400', accent: 'border-l-gray-300' },
};

export const getSeverityTone = (severity) => SEVERITY_TONES[severity] || SEVERITY_TONES.MODERATE;

const SEVERITY_RANK = { CRITICAL: 3, HIGH: 2, MODERATE: 1 };

/**
 * The code of the department that most needs help: highest severity, then most uncovered tasks, then most waiting minutes.
 * Only marked when there is something to single out (more than one department, or one that is High or Critical).
 */
export function pickMostUrgent(departments) {
  const list = Array.isArray(departments) ? departments : [];
  if (list.length === 0) return null;
  const score = (d) => [SEVERITY_RANK[d.severity] || 1, d.pressure?.uncoveredTasks || 0, d.pressure?.waitingMinutes || 0];
  const best = [...list].sort((a, b) => {
    const [x, y] = [score(a), score(b)];
    for (let i = 0; i < x.length; i++) if (x[i] !== y[i]) return y[i] - x[i];
    return String(a.name).localeCompare(String(b.name));
  })[0];
  return list.length > 1 || (SEVERITY_RANK[best.severity] || 1) > 1 ? best.code : null;
}

/** Totals for the overview strip, from the departments that need support (with or without possible helpers). */
export function summarizeNeeds(normalized) {
  const all = [...(normalized?.recommendations || []), ...(normalized?.unsupported || [])];
  return {
    departments: all.length,
    uncovered: all.reduce((sum, d) => sum + (d.pressure?.uncoveredTasks || 0), 0),
    waiting: all.reduce((sum, d) => sum + (d.pressure?.waitingTasks || 0), 0),
  };
}

export const getSeverityLabel =(severity) => SEVERITY_LABELS[severity] || 'Moderate';

export const getSeverityBadge = (severity) => SEVERITY_BADGES[severity] || SEVERITY_BADGES.MODERATE;

export const formatMinutes = (minutes) => `${Number.isFinite(minutes) ? minutes : 0} min`;

const asArray = (value) => (Array.isArray(value) ? value : []);

/** The API response with every list present, so the panel never has to guard against a missing field. */
export function normalizeRecommendations(data) {
  return {
    generatedAt: data?.generatedAt || null,
    recommendations: asArray(data?.recommendations).map((rec) => ({
      ...rec,
      reasons: asArray(rec?.reasons),
      suggestions: asArray(rec?.suggestions).map((suggestion) => ({ ...suggestion, reasons: asArray(suggestion?.reasons) })),
    })),
    unsupported: asArray(data?.unsupported),
    limitations: asArray(data?.limitations),
  };
}

/** True when there is nothing to show: no department needs support and none is left without a possible donor. */
export const hasNoPressure = (normalized) => normalized.recommendations.length === 0 && normalized.unsupported.length === 0;
