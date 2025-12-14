package org.wannes.plugModCore.config;

public class SecurityConfig {

    public boolean securityEnabled = false;
    public boolean verificationEnabled = false;

    public boolean allowVerified = true;
    public boolean allowUnverified = true;
    public boolean allowSuspicious = true;

    public boolean warnOnUnverified = true;
    public boolean warnOnSuspicious = true;
}
