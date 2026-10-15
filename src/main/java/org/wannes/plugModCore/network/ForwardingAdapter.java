package org.wannes.plugModCore.network;

import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;

/** Verified Paper global-config layouts; unknown layouts never receive invented fields. */
final class ForwardingAdapter {
    record Support(boolean available, String adapter, String reason) {}
    static Support inspect(ServerRuntime runtime, Path root) {
        if (!runtime.paperCompatible() || runtime.regionThreaded())
            return new Support(false, "", "Automatic forwarding requires conventional Paper scheduling; implementation=" + runtime.implementation());
        String adapter;
        if (runtime.minecraft().equals("1.21.11")) adapter = "paper-global-1.21.11";
        else if (runtime.minecraft().matches("26\\.3(?:\\.[0-9]+)?")) adapter = "paper-global-26.3";
        else return new Support(false, "", "No verified forwarding configuration adapter for Minecraft " + runtime.minecraft()
                + " (API/build " + runtime.api() + "). Management pairing remains available; game configuration is retained.");
        Path paper = root.resolve("config/paper-global.yml"), properties = root.resolve("server.properties"), spigot = root.resolve("spigot.yml");
        if (!Files.isRegularFile(paper) || !Files.isRegularFile(properties) || !Files.isRegularFile(spigot))
            return new Support(false, adapter, "Forwarding configuration files missing; management remains available");
        try {
            var yaml = new YamlConfiguration(); yaml.load(paper.toFile());
            int schema = yaml.getInt("_version", -1);
            if (schema != 31)
                return new Support(false, adapter, "Unverified paper-global.yml schema " + schema + "; game configuration is retained");
            if (!(yaml.get("proxies.velocity.enabled") instanceof Boolean)
                    || !(yaml.get("proxies.velocity.online-mode") instanceof Boolean)
                    || !(yaml.get("proxies.velocity.secret") instanceof String))
                return new Support(false, adapter, "Velocity configuration layout is missing or has invalid types; game configuration is retained");
            return new Support(true, adapter, "");
        } catch (Exception invalid) {
            return new Support(false, adapter, "Cannot read forwarding configuration: " + invalid.getMessage());
        }
    }

    static String effectiveSecret(String configured, String environment) {
        return environment != null && !environment.isEmpty() ? environment : configured;
    }

    static void requireWritable(ServerRuntime runtime, Path root) {
        var support = inspect(runtime, root);
        if (!support.available()) throw new IllegalStateException(support.reason());
        for (String name : new String[]{"config/paper-global.yml", "server.properties", "spigot.yml"}) {
            Path file = root.resolve(name);
            if (!Files.isWritable(file) || !Files.isWritable(file.toAbsolutePath().getParent()))
                throw new IllegalStateException("Forwarding configuration or backup directory not writable: " + file);
        }
        if (YamlConfiguration.loadConfiguration(root.resolve("spigot.yml").toFile()).getBoolean("settings.bungeecord", false))
            throw new IllegalStateException("Existing BungeeCord forwarding conflicts with Velocity modern forwarding; game configuration retained");
    }
}
