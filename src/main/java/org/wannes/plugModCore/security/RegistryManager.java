package org.wannes.plugModCore.security;

import org.bukkit.configuration.file.YamlConfiguration;
import org.wannes.plugModCore.PlugModCore;

import java.io.File;
import java.io.IOException;
import java.time.Instant;

public class RegistryManager {

    private final PlugModCore plugin;
    private final File file;
    private final YamlConfiguration yaml;

    public RegistryManager(PlugModCore plugin) {
        this.plugin = plugin;
        File dir = new File(plugin.getDataFolder(), "registry");
        dir.mkdirs();

        this.file = new File(dir, "modules.yml");
        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    public synchronized boolean exists(String internalId) {
        return yaml.contains("modules." + internalId);
    }

    public synchronized void registerNew(String internalId, String originalFileName, String sha256) {
        String base = "modules." + internalId;

        yaml.set(base + ".original-file", originalFileName);
        yaml.set(base + ".uploaded-at", Instant.now().toString());
        yaml.set(base + ".sha256", sha256);

        // voorbereid voor later
        yaml.set(base + ".verified", false);
        yaml.set(base + ".disabled", false);

        save();
    }

    public synchronized void save() {
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Registry save failed: " + e.getMessage());
        }
    }
}
