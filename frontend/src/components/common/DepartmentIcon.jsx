import React from 'react';
import {
  DepartmentsIcon,
  HousekeepingIcon,
  FrontDeskIcon,
  MaintenanceIcon,
  RoomServiceIcon,
  FoodBeverageIcon,
  SecurityIcon,
  StaffIcon,
  ClipboardIcon,
  CalendarIcon,
  ClockIcon,
  BriefcaseIcon,
  PackageIcon,
  TruckIcon,
  SparklesIcon,
  MonitorIcon,
  BedIcon,
} from '../icons/Icons';
import { getDepartmentStyle } from '../../utils/departmentStyles';

// Stored icon key -> icon component. Only keys are stored in the database, never components.
export const ICON_COMPONENTS = {
  building: DepartmentsIcon,
  sprout: HousekeepingIcon,
  bell: FrontDeskIcon,
  wrench: MaintenanceIcon,
  coffee: RoomServiceIcon,
  utensils: FoodBeverageIcon,
  shield: SecurityIcon,
  users: StaffIcon,
  clipboard: ClipboardIcon,
  calendar: CalendarIcon,
  clock: ClockIcon,
  briefcase: BriefcaseIcon,
  package: PackageIcon,
  truck: TruckIcon,
  sparkles: SparklesIcon,
  monitor: MonitorIcon,
  bed: BedIcon,
};

/**
 * The icon for a department: pass the department (its code, or an object with iconKey), or an iconKey directly.
 * An unknown key renders the generic building icon.
 */
export default function DepartmentIcon({ department, iconKey, className = 'w-4 h-4' }) {
  const key = iconKey || getDepartmentStyle(department).iconKey;
  const Icon = ICON_COMPONENTS[key] || DepartmentsIcon;
  return <Icon className={className} aria-hidden="true" />;
}
