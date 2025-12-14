package org.wannes.plugModCore.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public class ConfigLoader {

    private final JavaPlugin plugin;

    public ConfigLoader(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public CoreConfig loadCoreConfig() {
        File file = new File(plugin.getDataFolder(), "core-config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        CoreConfig cfg = new CoreConfig();

        cfg.autoLoadModules = yaml.getBoolean("modules.auto-load-on-startup", true);
        cfg.allowHotReload = yaml.getBoolean("modules.allow-hot-reload", true);
        cfg.allowLiveUpload = yaml.getBoolean("modules.allow-live-upload", true);

        cfg.webEnabled = yaml.getBoolean("web.enabled", true);
        cfg.webHost = yaml.getString("web.host", "0.0.0.0");
        cfg.webPort = yaml.getInt("web.port", 8080);

        return cfg;
    }

    public SecurityConfig loadSecurityConfig() {
        File file = new File(plugin.getDataFolder(), "security.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

        SecurityConfig cfg = new SecurityConfig();

        cfg.securityEnabled = yaml.getBoolean("security.enabled", false);
        cfg.verificationEnabled = yaml.getBoolean("verification.enabled", false);

        cfg.allowVerified = yaml.getBoolean("verification.allow.verified", true);
        cfg.allowUnverified = yaml.getBoolean("verification.allow.unverified", true);
        cfg.allowSuspicious = yaml.getBoolean("verification.allow.suspicious", true);

        cfg.warnOnUnverified = yaml.getBoolean("verification.warn-on.unverified", true);
        cfg.warnOnSuspicious = yaml.getBoolean("verification.warn-on.suspicious", true);

        return cfg;
    }
}
