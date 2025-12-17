package org.wannes.plugModCore;

import org.bukkit.plugin.java.JavaPlugin;
import org.wannes.plugModCore.config.ConfigLoader;
import org.wannes.plugModCore.config.CoreConfig;
import org.wannes.plugModCore.config.SecurityConfig;
import org.wannes.plugModCore.module.ModuleManager;
import org.wannes.plugModCore.web.WebServer;
import org.wannes.plugModCore.security.RegistryManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.Enumeration;
import org.bukkit.Bukkit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

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

        moduleManager = new ModuleManager(this, registryManager);
        moduleManager.scanModules();

        // Defer restoring/loading/enabling modules until the server is ready.
        // Run on the main thread slightly later to avoid lifecycle/timing issues.
        // Use 20 ticks to give the server time to initialize services.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            // restore last saved states (loads + enables as necessary)
            moduleManager.restoreStatesOnStartup();

            // If autoLoadModules is enabled, load any modules that remain UPLOADED
            if (coreConfig != null && coreConfig.autoLoadModules) {
                for (var m : moduleManager.getModules()) {
                    if (m.getState() == org.wannes.plugModCore.module.ModuleState.UPLOADED) {
                        moduleManager.loadModule(m);
                    }
                }
            }
        }, 20L);

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
            // Ensure packaged web assets are deployed into the plugin data folder and overwritten
            deployWebAssets();

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

    private void deployWebAssets() {
        File webDir = new File(getDataFolder(), "web");

        // remove existing web dir so we fully overwrite every start
        deleteRecursive(webDir);
        webDir.mkdirs();

        // Try to copy resources from classpath 'web/' directory.
        try {
            URL webResource = getClass().getClassLoader().getResource("web");

            if (webResource != null) {
                String protocol = webResource.getProtocol();

                if ("file".equals(protocol)) {
                    // running from IDE/classes directory
                    Path src = Paths.get(webResource.toURI());
                    Files.walk(src).forEach(p -> {
                        try {
                            Path rel = src.relativize(p);
                            File dest = new File(webDir, rel.toString());
                            if (Files.isDirectory(p)) {
                                dest.mkdirs();
                            } else {
                                dest.getParentFile().mkdirs();
                                Files.copy(p, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException ex) {
                            getLogger().severe("Fout bij kopiëren resource: " + ex.getMessage());
                        }
                    });

                    return;
                }
            }

            // Fallback: running from JAR - extract entries starting with 'web/'
            CodeSource src = getClass().getProtectionDomain().getCodeSource();
            if (src != null) {
                String jarPath = src.getLocation().toURI().getPath();
                try (JarFile jar = new JarFile(URLDecoder.decode(jarPath, "UTF-8"))) {
                    Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry e = entries.nextElement();
                        if (!e.getName().startsWith("web/")) continue;

                        String entryName = e.getName().substring("web/".length());
                        if (entryName.isEmpty()) continue;

                        File out = new File(webDir, entryName);
                        if (e.isDirectory()) {
                            out.mkdirs();
                            continue;
                        }

                        out.getParentFile().mkdirs();
                        try (InputStream in = jar.getInputStream(e);
                             FileOutputStream fos = new FileOutputStream(out)) {
                            byte[] buf = new byte[8192];
                            int r;
                            while ((r = in.read(buf)) != -1) fos.write(buf, 0, r);
                        }
                    }
                    return;
                }
            }

        } catch (URISyntaxException | IOException e) {
            getLogger().severe("Kon web assets niet deployen: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        try { f.delete(); } catch (Exception ignored) {}
    }

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.stop();
        }
    }
}
