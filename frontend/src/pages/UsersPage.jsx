import React, { useState, useEffect } from 'react';
import { UsersIcon } from '../components/icons/Icons';
import { getUsers, getUserRole } from '../services/api';

export default function UsersPage() {
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const userRole = getUserRole();

  useEffect(() => {
    if (userRole === 'ADMIN') {
      fetchUsers();
    }
  }, [userRole]);

  const fetchUsers = async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await getUsers();
      setUsers(data || []);
    } catch (err) {
      setError(err.message || 'Failed to load users');
    } finally {
      setLoading(false);
    }
  };

  if (userRole !== 'ADMIN') {
    return (
      <div className="w-full">
        <div className="bg-red-50 text-red-800 p-4 rounded-xl border border-red-100 flex items-center shadow-sm">
          <span className="font-medium">Access Denied:</span>
          <span className="ml-2">You do not have permission to view the Users management page.</span>
        </div>
      </div>
    );
  }

  const getRoleBadge = (role) => {
    switch (role) {
      case 'ADMIN':
        return 'bg-purple-100 text-purple-800';
      case 'MANAGER':
        return 'bg-blue-100 text-blue-800';
      default:
        return 'bg-emerald-100 text-emerald-800';
    }
  };

  const formatDate = (dateString) => {
    if (!dateString) return 'N/A';
    const date = new Date(dateString);
    return date.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
  };

  if (loading) {
    return (
      <div className="w-full space-y-6">
        <div className="animate-pulse space-y-6">
          <div className="h-8 bg-gray-200 rounded w-1/4"></div>
          <div className="h-4 bg-gray-200 rounded w-1/3"></div>
          <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
            <div className="h-24 bg-gray-200 rounded-xl"></div>
            <div className="h-24 bg-gray-200 rounded-xl"></div>
            <div className="h-24 bg-gray-200 rounded-xl"></div>
            <div className="h-24 bg-gray-200 rounded-xl"></div>
          </div>
          <div className="h-64 bg-gray-200 rounded-xl"></div>
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="w-full space-y-6">
        <div className="bg-red-50 text-red-800 p-4 rounded-xl flex justify-between items-center shadow-sm border border-red-100">
          <p className="font-medium">{error}</p>
          <button onClick={fetchUsers} className="px-4 py-2 bg-red-100 hover:bg-red-200 rounded-lg text-sm font-medium transition-colors cursor-pointer">
            Retry
          </button>
        </div>
      </div>
    );
  }

  const totalUsers = users.length;
  const adminCount = users.filter((u) => u.role === 'ADMIN').length;
  const managerCount = users.filter((u) => u.role === 'MANAGER').length;
  const staffCount = users.filter((u) => u.role === 'STAFF').length;

  const summaryCards = [
    { title: 'Total Users', value: totalUsers, icon: <UsersIcon className="w-6 h-6 text-emerald-600" />, bg: 'bg-emerald-50' },
    { title: 'Administrators', value: adminCount, icon: <UsersIcon className="w-6 h-6 text-purple-600" />, bg: 'bg-purple-50' },
    { title: 'Managers', value: managerCount, icon: <UsersIcon className="w-6 h-6 text-blue-600" />, bg: 'bg-blue-50' },
    { title: 'Staff Users', value: staffCount, icon: <UsersIcon className="w-6 h-6 text-emerald-600" />, bg: 'bg-emerald-50' },
  ];

  return (
    <div className="w-full space-y-6">
      {/* Header */}
      <div className="flex justify-between items-center">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">User Accounts</h1>
          <p className="text-gray-500 mt-1">System user accounts, credentials, and access roles.</p>
        </div>
      </div>

      {/* Summary Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
        {summaryCards.map((card, idx) => (
          <div key={idx} className="bg-white rounded-xl p-6 shadow-sm border border-gray-100 flex items-center space-x-4">
            <div className={`p-3 rounded-lg ${card.bg}`}>
              {card.icon}
            </div>
            <div>
              <p className="text-sm font-medium text-gray-500">{card.title}</p>
              <h3 className="text-2xl font-bold text-gray-900">{card.value}</h3>
            </div>
          </div>
        ))}
      </div>

      {/* Users Table */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden">
        <div className="p-6 border-b border-gray-100 flex justify-between items-center">
          <h2 className="text-base font-bold text-gray-900">Registered System Users</h2>
        </div>
        {users.length === 0 ? (
          <div className="p-8 text-center text-gray-500">
            No users found.
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left">
              <thead className="bg-gray-50/50 text-sm text-gray-500 border-b border-gray-100">
                <tr>
                  <th className="px-6 py-4 font-medium">User</th>
                  <th className="px-6 py-4 font-medium">Username</th>
                  <th className="px-6 py-4 font-medium">Role</th>
                  <th className="px-6 py-4 font-medium">Status</th>
                  <th className="px-6 py-4 font-medium">Created Date</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100 text-sm">
                {users.map((user) => (
                  <tr key={user.id} className="hover:bg-gray-50/50 transition-colors">
                    <td className="px-6 py-4 font-medium text-gray-900">
                      {user.fullName || 'Unknown User'}
                    </td>
                    <td className="px-6 py-4 font-mono text-gray-600 text-xs">@{user.username}</td>
                    <td className="px-6 py-4">
                      <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold ${getRoleBadge(user.role)}`}>
                        {user.role}
                      </span>
                    </td>
                    <td className="px-6 py-4">
                      <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${user.active ? 'bg-emerald-100 text-emerald-800' : 'bg-gray-100 text-gray-800'}`}>
                        {user.active ? 'Active' : 'Inactive'}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-gray-500 text-xs">{formatDate(user.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
