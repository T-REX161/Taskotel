package com.hotel.workload.staff;

public class DepartmentDto {
    private String code;
    private String name;
    private long staffCount;
    private long activeTasks;
    private String status;
    private String supervisor;

    public DepartmentDto() {}

    public DepartmentDto(String code, String name, long staffCount, long activeTasks, String status, String supervisor) {
        this.code = code;
        this.name = name;
        this.staffCount = staffCount;
        this.activeTasks = activeTasks;
        this.status = status;
        this.supervisor = supervisor;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getStaffCount() {
        return staffCount;
    }

    public void setStaffCount(long staffCount) {
        this.staffCount = staffCount;
    }

    public long getActiveTasks() {
        return activeTasks;
    }

    public void setActiveTasks(long activeTasks) {
        this.activeTasks = activeTasks;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSupervisor() {
        return supervisor;
    }

    public void setSupervisor(String supervisor) {
        this.supervisor = supervisor;
    }
}
