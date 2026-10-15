package org.wannes.plugModCore;

import org.bukkit.plugin.java.JavaPlugin;
import org.wannes.plugModCore.config.ConfigLoader;
import org.wannes.plugModCore.config.CoreConfig;
import org.wannes.plugModCore.config.SecurityConfig;
import org.wannes.plugModCore.module.ModuleManager;
import org.wannes.plugModCore.module.ModuleContainer;
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

    private org.wannes.plugModCore.web.WebListenerLifecycle webServer;
    private ModuleManager moduleManager;
    private RegistryManager registryManager;
    private boolean allocateNewWebPort;
    private org.wannes.plugModCore.network.PaperNetwork network;

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
        allocateNewWebPort=!Files.isRegularFile(getDataFolder().toPath().resolve("core-config.yml"));
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
        org.wannes.plugModCore.module.ModuleCommand command = new org.wannes.plugModCore.module.ModuleCommand(moduleManager, this);
        java.util.Objects.requireNonNull(getCommand("module")).setExecutor(command);
        getCommand("module").setTabCompleter(command);

        // Defer restoring/loading/enabling modules until the server is ready.
        // Run on the main thread slightly later to avoid lifecycle/timing issues.
        // Use 20 ticks to give the server time to initialize services.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            moduleManager.restoreStartupAsync(coreConfig.autoLoadModules);
        }, 20L);

        getLogger().info("Modules gevonden: " + moduleManager.getModules().size());

        /* =========================
           5. WEBSERVER
           ========================= */
        try { network = new org.wannes.plugModCore.network.PaperNetwork(this); }
        catch (Exception failure) { getLogger().log(java.util.logging.Level.SEVERE,"Network agent startup failed; standalone operation retained unless a committed network link exists",failure); }
        boolean linkedOnDisk = Files.exists(getDataFolder().toPath().resolve("network/link-confirmed.json"));
        try { var file=getDataFolder().toPath().resolve("network/network-state.json");if(Files.exists(file)){var stored=org.wannes.plugmodcore.network.Protocol.JSON.fromJson(Files.readString(file),com.google.gson.JsonObject.class);if(stored.get("schema").getAsInt()!=1)throw new IllegalArgumentException("Unknown network-state schema");linkedOnDisk=linkedOnDisk||stored.get("linked").getAsBoolean();} }
        catch(Exception failure){linkedOnDisk=true;getLogger().severe("Cannot verify network state; standalone WebUI stays closed until local recovery");}
        if (coreConfig.webEnabled && (!linkedOnDisk || network!=null&&!network.isManagementConnected())) {
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
    public CoreConfig getCoreConfig() { return coreConfig; }
    public org.wannes.plugModCore.network.PaperNetwork getNetwork() { return network; }
    public void restartNetworkAgentAfterLocalRecovery() throws Exception {if(network!=null)network.close();network=new org.wannes.plugModCore.network.PaperNetwork(this);}
    public void stopStandaloneWeb() { if(getListeningWebPort()<0)return;if(webServer!=null)webServer.stop();getLogger().info("Standalone WebUI stopped after committed proxy pairing; local modules remain active"); }
    public void restoreStandaloneWeb() throws IOException {if(network!=null&&network.isManagementConnected())throw new IOException("Authenticated proxy contact active; standalone listener stays closed");if(coreConfig.webEnabled){startWebServer();if(getListeningWebPort()<0)throw new IOException("Standalone listener could not be restored; correct bind/configuration and retry locally");}}

    public int getListeningWebPort() { return webServer == null ? -1 : webServer.port(); }

    public synchronized void changeWebPort(int port) throws IOException {
        if (network != null && network.isManagementConnected()) throw new IOException("Backend is network-managed; unlink locally or through the proxy before restoring standalone WebUI");
        if (!coreConfig.webEnabled) throw new IOException("WebUI is disabled in core-config.yml; enable it and restart first.");
        if (webServer == null) throw new IOException("WebUI lifecycle is unavailable.");
        webServer.change(port, new ConfigLoader(this)::saveWebPort);
        coreConfig.webPort = port;
        getLogger().info("WebUI listening on http://" + org.wannes.plugmodcore.network.DevelopmentAccess.host(coreConfig.developmentMode,coreConfig.webHost) + ":" + webServer.port());
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
            try (InputStream in = getResource(resourcePath)) {
                if (in == null) throw new IOException("Missing resource " + resourcePath);
                Files.copy(in, outFile.toPath());
            } catch (IOException e) { throw new IllegalStateException("Cannot create " + outputName, e); }
        }
    }

    public RegistryManager getRegistryManager() {
        return registryManager;
    }

    private void startWebServer() {
        if(getListeningWebPort()>=0)return;
        try {
            // Ensure packaged web assets are deployed into the plugin data folder and overwritten
            deployWebAssets();

            if(webServer==null)webServer = new org.wannes.plugModCore.web.WebListenerLifecycle(port -> new WebServer(this, coreConfig.webHost, port));
            if(allocateNewWebPort){var allocated=org.wannes.plugmodcore.network.ManagementPorts.allocate(0,true,port->{try{webServer.change(port,new ConfigLoader(this)::saveWebPort);return port;}catch(IOException failure){Throwable cause=failure;while(cause.getCause()!=null)cause=cause.getCause();if(cause instanceof java.net.BindException)throw (java.net.BindException)cause;throw failure;}});coreConfig.webPort=allocated.port();allocateNewWebPort=false;}else webServer.change(coreConfig.webPort, port -> {});
            getLogger().info(
                    "Webserver gestart op http://" +
                            org.wannes.plugmodcore.network.DevelopmentAccess.host(coreConfig.developmentMode,coreConfig.webHost) + ":" +
                            webServer.port()
            );

        } catch (Exception e) {
            getLogger().severe("Kon webserver niet starten!");
            e.printStackTrace();
        }
    }

    private void deployWebAssets() {
        File webDir = new File(getDataFolder(), "web");

        // Update packaged assets while preserving unrelated files in the web directory.
        webDir.mkdirs();

        // Try to copy resources from classpath 'web/' directory.
        try {
            URL webResource = getClass().getClassLoader().getResource("web");

            if (webResource != null) {
                String protocol = webResource.getProtocol();

                if ("file".equals(protocol)) {
                    // running from IDE/classes directory
                    Path src = Paths.get(webResource.toURI());
                    try (java.util.stream.Stream<Path> paths = Files.walk(src)) { paths.forEach(p -> {
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
                    }); }

                    return;
                }
            }

            // Fallback: running from JAR - extract entries starting with 'web/'
            CodeSource src = getClass().getProtectionDomain().getCodeSource();
            if (src != null) {
                try (JarFile jar = new JarFile(Paths.get(src.getLocation().toURI()).toFile())) {
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

    @Override
    public void onDisable() {
        if (network != null) network.close();
        if (webServer != null) {
            webServer.stop();
        }
        if (moduleManager != null) moduleManager.shutdown();
    }
}


