package org.wannes.plugModCore;

import org.bukkit.plugin.java.JavaPlugin;
import org.wannes.plugModCore.config.ConfigLoader;
import org.wannes.plugModCore.config.CoreConfig;
import org.wannes.plugModCore.config.SecurityConfig;
import org.wannes.plugModCore.module.ModuleManager;
import org.wannes.plugModCore.web.WebServer;
import org.wannes.plugModCore.security.RegistryManager;

import java.io.File;

public class PlugModCore extends JavaPlugin {

    private CoreConfig coreConfig;
    private SecurityConfig securityConfig;

    private WebServer webServer;
    private ModuleManager moduleManager;
    private RegistryManager registryManager;

    @Override
    public void onEnable() {

        /* =========================
           1. DATAFOLDER & MAPPEN
           ========================= */
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        createDir("modules");
        createDir("module-data");
        createDir("registry");
        createDir("logs");

        /* =========================
           2. CONFIG TEMPLATES
           ========================= */
        saveDefaultConfigFiles();

        /* =========================
           3. CONFIG LADEN
           ========================= */
        ConfigLoader loader = new ConfigLoader(this);
        coreConfig = loader.loadCoreConfig();
        securityConfig = loader.loadSecurityConfig();

        getLogger().info("Config geladen:");
        getLogger().info("Web enabled: " + coreConfig.webEnabled);
        getLogger().info("Security enabled: " + securityConfig.securityEnabled);

          /* =========================
              4. REGISTRY + MODULE MANAGER
              ========================= */
          registryManager = new RegistryManager(this);

          moduleManager = new ModuleManager(getDataFolder(), registryManager);
        moduleManager.scanModules();

        if (coreConfig != null && coreConfig.autoLoadModules) {
            for (var m : moduleManager.getModules()) {
                moduleManager.loadModule(m);
            }
        }

        getLogger().info("Modules gevonden: " + moduleManager.getModules().size());

        /* =========================
           5. WEBSERVER
           ========================= */
        if (coreConfig.webEnabled) {
            startWebServer();
        }

        getLogger().info("PlugModCore succesvol gestart.");
    }

    /* =========================
       GETTERS
       ========================= */
    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    /* =========================
       HELPERS
       ========================= */
    private void createDir(String name) {
        File dir = new File(getDataFolder(), name);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private void saveDefaultConfigFiles() {
        saveResourceIfNotExists("templates/core-config.yml", "core-config.yml");
        saveResourceIfNotExists("templates/security.yml", "security.yml");
    }

    private void saveResourceIfNotExists(String resourcePath, String outputName) {
        File outFile = new File(getDataFolder(), outputName);
        if (!outFile.exists()) {
            saveResource(resourcePath, false);
        }
    }

    public RegistryManager getRegistryManager() {
        return registryManager;
    }

    private void startWebServer() {
        try {
            webServer = new WebServer(
                    this,                       // <<< BELANGRIJK
                    coreConfig.webHost,
                    coreConfig.webPort
            );

            webServer.startServer();
            getLogger().info(
                    "Webserver gestart op http://" +
                            coreConfig.webHost + ":" +
                            coreConfig.webPort
            );

        } catch (Exception e) {
            getLogger().severe("Kon webserver niet starten!");
            e.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.stop();
        }
    }
}
