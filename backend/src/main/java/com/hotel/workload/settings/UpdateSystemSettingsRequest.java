package com.hotel.workload.settings;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * All four settings, every one required. The threshold is read as a number and checked to be a whole number (a value such as 85.5 is
 * refused instead of being cut to 85). Nothing about who is saving is read from the body: the Admin is the signed-in account.
 */
public class UpdateSystemSettingsRequest {

    @NotNull(message = "autoBalancingEnabled is required")
    private Boolean autoBalancingEnabled;

    @NotNull(message = "capacityAlertEnabled is required")
    private Boolean capacityAlertEnabled;

    @NotNull(message = "crossDepartmentAssistanceEnabled is required")
    private Boolean crossDepartmentAssistanceEnabled;

    @NotNull(message = "capacityThresholdPercent is required")
    @DecimalMin(value = "1", message = "capacityThresholdPercent must be a whole number from 1 to 100")
    @DecimalMax(value = "100", message = "capacityThresholdPercent must be a whole number from 1 to 100")
    private BigDecimal capacityThresholdPercent;

    public Boolean getAutoBalancingEnabled() { return autoBalancingEnabled; }
    public void setAutoBalancingEnabled(Boolean value) { this.autoBalancingEnabled = value; }
    public Boolean getCapacityAlertEnabled() { return capacityAlertEnabled; }
    public void setCapacityAlertEnabled(Boolean value) { this.capacityAlertEnabled = value; }
    public Boolean getCrossDepartmentAssistanceEnabled() { return crossDepartmentAssistanceEnabled; }
    public void setCrossDepartmentAssistanceEnabled(Boolean value) { this.crossDepartmentAssistanceEnabled = value; }
    public BigDecimal getCapacityThresholdPercent() { return capacityThresholdPercent; }
    public void setCapacityThresholdPercent(BigDecimal value) { this.capacityThresholdPercent = value; }
}
