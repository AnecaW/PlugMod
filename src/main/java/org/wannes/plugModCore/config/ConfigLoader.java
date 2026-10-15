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
        YamlConfiguration yaml = read(file);
        for (String key : java.util.List.of("modules.auto-load-on-startup", "modules.allow-hot-reload", "modules.allow-live-upload", "web.enabled")) {
            if (yaml.contains(key) && !yaml.isBoolean(key)) throw new IllegalArgumentException(key + " must be a boolean");
        }
        if (yaml.contains("web.port") && !yaml.isInt("web.port")) throw new IllegalArgumentException("web.port must be an integer");

        CoreConfig cfg = new CoreConfig();

        cfg.autoLoadModules = yaml.getBoolean("modules.auto-load-on-startup", true);
        cfg.allowHotReload = yaml.getBoolean("modules.allow-hot-reload", true);
        cfg.allowLiveUpload = yaml.getBoolean("modules.allow-live-upload", true);

        cfg.webEnabled = yaml.getBoolean("web.enabled", true);
        cfg.developmentMode = yaml.getBoolean("development.enabled", false);
        cfg.webHost = yaml.getString("web.host", "0.0.0.0");
        cfg.webPort = yaml.getInt("web.port", 8080);
        if (cfg.webPort < 1 || cfg.webPort > 65535) throw new IllegalArgumentException("web.port must be between 1 and 65535");
        if (cfg.webHost == null || cfg.webHost.isBlank()) throw new IllegalArgumentException("web.host must not be blank");
        cfg.webAccessToken = yaml.getString("web.access-token", "");
        if (cfg.webEnabled && cfg.webAccessToken.isBlank()) {
            cfg.webAccessToken = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID();
            yaml.set("web.access-token", cfg.webAccessToken);
            try { yaml.save(file); }
            catch (java.io.IOException e) { throw new IllegalStateException("Cannot save web access token", e); }
            plugin.getLogger().info("Web login: username admin, password is web.access-token in core-config.yml");
        }

        return cfg;
    }

    public SecurityConfig loadSecurityConfig() {
        File file = new File(plugin.getDataFolder(), "security.yml");
        YamlConfiguration yaml = read(file);

        SecurityConfig cfg = new SecurityConfig();

        cfg.securityEnabled = yaml.getBoolean("security.enabled", false);
        cfg.verificationEnabled = yaml.getBoolean("verification.enabled", false);
        if (cfg.verificationEnabled) throw new IllegalArgumentException("Artifact signature verification is not implemented; verification.enabled must be false");

        cfg.allowVerified = yaml.getBoolean("verification.allow.verified", true);
        cfg.allowUnverified = yaml.getBoolean("verification.allow.unverified", true);
        cfg.allowSuspicious = yaml.getBoolean("verification.allow.suspicious", true);

        cfg.warnOnUnverified = yaml.getBoolean("verification.warn-on.unverified", true);
        cfg.warnOnSuspicious = yaml.getBoolean("verification.warn-on.suspicious", true);

        return cfg;
    }
    public void saveWebPort(int port) throws java.io.IOException {
        File file = new File(plugin.getDataFolder(), "core-config.yml");
        YamlConfiguration yaml = read(file);
        yaml.set("web.port", port);
        java.nio.file.Path temporary = java.nio.file.Files.createTempFile(file.toPath().getParent(), "core-config-", ".tmp");
        try {
            java.nio.file.Files.writeString(temporary, yaml.saveToString(), java.nio.charset.StandardCharsets.UTF_8);
            // Require atomic replacement: a failed save must not damage the last working configuration.
            java.nio.file.Files.move(temporary, file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { java.nio.file.Files.deleteIfExists(temporary); }
    }
    private YamlConfiguration read(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (!file.exists()) return yaml;
        try { yaml.load(file); return yaml; }
        catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException e) { throw new IllegalStateException("Invalid configuration " + file.getName(), e); }
    }
}
