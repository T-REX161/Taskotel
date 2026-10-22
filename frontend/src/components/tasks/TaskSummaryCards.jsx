import React, { useMemo } from 'react';
import { TasksIcon } from '../icons/Icons';

/**
 * TaskSummaryCards — metric cards for Total Tasks, Pending, In Progress, and Completed.
 * Matches existing dashboard summary card styling with real task status counts.
 *
 * Props:
 *   tasks - Array of task objects from getTasks()
 */
export default function TaskSummaryCards({ tasks = [] }) {
  const counts = useMemo(() => {
    let pending = 0;
    let inProgress = 0;
    let completed = 0;

    tasks.forEach((t) => {
      if (t.status === 'PENDING') {
        pending += 1;
      } else if (t.status === 'IN_PROGRESS') {
        inProgress += 1;
      } else if (t.status === 'COMPLETED') {
        completed += 1;
      }
    });

    return {
      total: tasks.length,
      pending,
      inProgress,
      completed,
    };
  }, [tasks]);

  const cards = [
    {
      title: 'Total Tasks',
      value: counts.total,
      icon: <TasksIcon className="w-6 h-6 text-emerald-600" />,
      bg: 'bg-emerald-50',
    },
    {
      title: 'Pending',
      value: counts.pending,
      icon: <TasksIcon className="w-6 h-6 text-yellow-600" />,
      bg: 'bg-yellow-50',
    },
    {
      title: 'In Progress',
      value: counts.inProgress,
      icon: <TasksIcon className="w-6 h-6 text-purple-600" />,
      bg: 'bg-purple-50',
    },
    {
      title: 'Completed',
      value: counts.completed,
      icon: <TasksIcon className="w-6 h-6 text-emerald-600" />,
      bg: 'bg-emerald-50',
    },
  ];

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 sm:gap-6 w-full">
      {cards.map((card, idx) => (
        <div
          key={idx}
          className="bg-white rounded-xl p-5 sm:p-6 shadow-sm border border-gray-100 flex items-center space-x-4"
        >
          <div className={`p-3 rounded-lg ${card.bg}`}>{card.icon}</div>
          <div>
            <p className="text-xs sm:text-sm font-medium text-gray-500">
              {card.title}
            </p>
            <h3 className="text-xl sm:text-2xl font-bold text-gray-900">
              {card.value}
            </h3>
          </div>
        </div>
      ))}
    </div>
  );
}
