import { Routes, Route } from 'react-router-dom';
import AppShell from './components/layout/AppShell';
import Dashboard from './pages/Dashboard';
import LoginPage from './pages/LoginPage';
import ForgotPasswordPage from './pages/ForgotPasswordPage';
import EmailVerificationPage from './pages/EmailVerificationPage';
import SetCredentialsPage from './pages/SetCredentialsPage';
import StaffPage from './pages/StaffPage';
import TasksPage from './pages/TasksPage';
import CreateTaskPage from './pages/CreateTaskPage';
import AssignmentsPage from './pages/AssignmentsPage';
import WorkloadPage from './pages/WorkloadPage';
import DepartmentsPage from './pages/DepartmentsPage';
import UsersPage from './pages/UsersPage';
import ProfilePage from './pages/ProfilePage';
import SettingsPage from './pages/SettingsPage';

function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/forgot-password" element={<ForgotPasswordPage />} />
      <Route path="/email-verification" element={<EmailVerificationPage />} />
      <Route path="/set-credentials" element={<SetCredentialsPage />} />
      <Route element={<AppShell />}>
        <Route path="/" element={<Dashboard />} />
        <Route path="/dashboard" element={<Dashboard />} />
        <Route path="/staff" element={<StaffPage />} />
        <Route path="/tasks" element={<TasksPage />} />
        <Route path="/tasks/create" element={<CreateTaskPage />} />
        <Route path="/assignments" element={<AssignmentsPage />} />
        <Route path="/workload" element={<WorkloadPage />} />
        <Route path="/departments" element={<DepartmentsPage />} />
        <Route path="/users" element={<UsersPage />} />
        <Route path="/profile" element={<ProfilePage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="*" element={<Dashboard />} />
      </Route>
    </Routes>
  );
}

export default App;
