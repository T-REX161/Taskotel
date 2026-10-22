import React from 'react';

export default function PasswordRequirements({ rules }) {
  const {
    ruleMinLength = false,
    ruleUppercase = false,
    ruleLowercase = false,
    ruleNumber = false,
    ruleSpecialChar = false,
  } = rules || {};

  const requirementList = [
    { key: 'minLength', satisfied: ruleMinLength, label: 'At least 8 characters' },
    { key: 'uppercase', satisfied: ruleUppercase, label: 'At least one uppercase letter' },
    { key: 'lowercase', satisfied: ruleLowercase, label: 'At least one lowercase letter' },
    { key: 'number', satisfied: ruleNumber, label: 'At least one number' },
    { key: 'specialChar', satisfied: ruleSpecialChar, label: 'At least one special character' },
  ];

  return (
    <div className="p-3 bg-gray-50/80 rounded-xl border border-gray-100 text-xs space-y-1.5">
      <span className="block font-semibold text-gray-700 mb-1">Password requirements:</span>
      
      {requirementList.map((req) => (
        <div key={req.key} className="flex items-center gap-2">
          <span
            className={`w-4 h-4 rounded-full flex items-center justify-center shrink-0 text-[10px] ${
              req.satisfied ? 'bg-emerald-100 text-emerald-700 font-bold' : 'bg-gray-200 text-gray-400'
            }`}
          >
            {req.satisfied ? '✓' : '•'}
          </span>
          <span className={req.satisfied ? 'text-emerald-800 font-medium' : 'text-gray-500'}>
            {req.label}
          </span>
        </div>
      ))}
    </div>
  );
}
