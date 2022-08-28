package io.github.melin.flink.jobserver.driver.model;

import com.beust.jcommander.Parameter;

public class DriverParam {

    @Parameter(names = "-j", description = "driverId", required = true)
    private Long driverId;

    @Parameter(names = "-conf", description = "driver config", required = true)
    private String config;

    @Parameter(names = "-k", description = "kerberos enabled", required = false, arity = 1)
    private boolean kerberosEnabled = false;

    @Parameter(names = "-ku", description = "kerberos user", required = false)
    private String kerberosUser;

    @Parameter(names = "-hive", description = "hive enabled", required = false)
    private boolean hiveEnable = true;

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public String getConfig() {
        return config;
    }

    public void setConfig(String config) {
        this.config = config;
    }

    public boolean isKerberosEnabled() {
        return kerberosEnabled;
    }

    public void setKerberosEnabled(boolean kerberosEnabled) {
        this.kerberosEnabled = kerberosEnabled;
    }

    public String getKerberosUser() {
        return kerberosUser;
    }

    public void setKerberosUser(String kerberosUser) {
        this.kerberosUser = kerberosUser;
    }

    public boolean isHiveEnable() {
        return hiveEnable;
    }

    public void setHiveEnable(boolean hiveEnable) {
        this.hiveEnable = hiveEnable;
    }
}
