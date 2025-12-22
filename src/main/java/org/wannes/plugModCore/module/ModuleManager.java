package org.wannes.plugModCore.module;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.wannes.plugModCore.PlugModCore;
import org.wannes.plugModCore.api.Module;
import org.wannes.plugModCore.security.RegistryManager;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class ModuleManager {

    private final PlugModCore plugin;
    private final File modulesDir;
    private final File moduleDataDir;
    private final RegistryManager registryManager;

    private final List<ModuleContainer> modules = new ArrayList<>();

    public ModuleManager(PlugModCore plugin, RegistryManager registryManager) {
        this.plugin = plugin;
        this.registryManager = registryManager;

        File dataFolder = plugin.getDataFolder();
        this.modulesDir = new File(dataFolder, "modules");
        this.moduleDataDir = new File(dataFolder, "module-data");

        modulesDir.mkdirs();
        moduleDataDir.mkdirs();
    }

    /* =========================
       SCAN MODULES
       ========================= */
    public void scanModules() {
        modules.clear();

        File[] files = modulesDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
        if (files == null) return;

        for (File file : files) {
            ModuleContainer container = new ModuleContainer(file);

            String fileName = file.getName();
            if (!fileName.toLowerCase().endsWith(".jar")) continue;

            String internalId = fileName.substring(0, fileName.length() - 4);
            container.setInternalId(internalId);

            ModuleInfo info = readModuleInfo(file);
            container.setInfo(info);

            if (!info.valid) {
                container.setState(ModuleState.FAILED);
                modules.add(container);
                continue;
            }

            // If the registry contains a saved state for this module, use it so the
            // web UI immediately reflects the last known state. Otherwise default
            // to UPLOADED.
            if (registryManager != null) {
                String saved = registryManager.getModuleState(internalId);
                if (saved != null) {
                    try {
                        ModuleState s = ModuleState.valueOf(saved);
                        container.setState(s);
                    } catch (IllegalArgumentException ignored) {
                        container.setState(ModuleState.UPLOADED);
                    }
                } else {
                    container.setState(ModuleState.UPLOADED);
                }
            } else {
                container.setState(ModuleState.UPLOADED);
            }

            modules.add(container);
        }
    }

    /**
     * Restore module states from the registry: load modules that were previously
     * loaded (disabled/enabled) and re-enable those that were enabled.
     */
    public void restoreStatesOnStartup() {
        for (ModuleContainer m : new ArrayList<>(modules)) {
            String saved = registryManager != null ? registryManager.getModuleState(m.getInternalId()) : null;
            if (saved == null) continue;

            ModuleState desired;
            try {
                desired = ModuleState.valueOf(saved);
            } catch (IllegalArgumentException ex) {
                // unknown state -> skip
                plugin.getLogger().warning("Unknown saved state '" + saved + "' for module " + m.getInternalId());
                continue;
            }

            // If desired is ENABLED or DISABLED we must ensure the module is loaded.
            // Use a force-load method that ignores the container's current visible state
            // because scanModules() populates the container state from registry for UI.
            try {
                loadModuleForce(m);

                if (desired == ModuleState.ENABLED) {
                    // enable on main thread (enableModule already uses runTask)
                    enableModule(m);
                }

            } catch (Exception e) {
                plugin.getLogger().severe("Failed to restore module " + m.getInternalId() + " to state " + desired + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * Force load a module regardless of the container's current state. This is
     * used during startup to load modules that had been loaded before shutdown.
     */
    public void loadModuleForce(ModuleContainer module) {
        if (module.getState() == ModuleState.ENABLED) return; // already enabled

        try {
            URL jarUrl = module.getFile().toURI().toURL();

            ModuleClassLoader classLoader = new ModuleClassLoader(
                    jarUrl,
                    plugin.getClass().getClassLoader()
            );

            Class<?> mainClass = classLoader.loadClass(module.getInfo().mainClass);

            if (!Module.class.isAssignableFrom(mainClass)) {
                throw new IllegalStateException("Main class implementeert Module interface niet");
            }

            Module instance = (Module) mainClass.getDeclaredConstructor().newInstance();

            module.setClassLoader(classLoader);
            module.setModuleInstance(instance);

            // Context aanmaken (data folder per internalId)
            ModuleContextImpl context = new ModuleContextImpl(module, moduleDataDir);
            module.setContext(context);

            // ensure module resources from JAR are copied to module-data (only missing files)
            try {
                extractResourcesIfNeeded(module);
            } catch (Exception ignored) {}

            module.setState(ModuleState.DISABLED);
            if (registryManager != null) registryManager.setModuleState(module.getInternalId(), ModuleState.DISABLED.name());

        } catch (Exception e) {
            module.getInfo().error = "Laden faalde: " + e.getMessage();
            module.setState(ModuleState.FAILED);
            if (registryManager != null) registryManager.setModuleState(module.getInternalId(), ModuleState.FAILED.name());
            plugin.getLogger().severe("Error loading module " + module.getInternalId() + ": " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    /* =========================
       LOAD / UNLOAD
       ========================= */
    public void loadModule(ModuleContainer module) {
        if (module.getState() != ModuleState.UPLOADED) return;

        try {
            URL jarUrl = module.getFile().toURI().toURL();

            ModuleClassLoader classLoader = new ModuleClassLoader(
                    jarUrl,
                    plugin.getClass().getClassLoader()
            );

            Class<?> mainClass = classLoader.loadClass(module.getInfo().mainClass);

            if (!Module.class.isAssignableFrom(mainClass)) {
                throw new IllegalStateException("Main class implementeert Module interface niet");
            }

            Module instance = (Module) mainClass.getDeclaredConstructor().newInstance();

            module.setClassLoader(classLoader);
            module.setModuleInstance(instance);

            // Context aanmaken (data folder per internalId)
            ModuleContextImpl context = new ModuleContextImpl(module, moduleDataDir);
            module.setContext(context);

                // ensure module resources from JAR are copied to module-data (only missing files)
                try {
                    extractResourcesIfNeeded(module);
                } catch (Exception ignored) {}
            module.setState(ModuleState.DISABLED);
            if (registryManager != null) registryManager.setModuleState(module.getInternalId(), ModuleState.DISABLED.name());

        } catch (Exception e) {
            module.getInfo().error = "Laden faalde: " + e.getMessage();
            module.setState(ModuleState.FAILED);
            if (registryManager != null) registryManager.setModuleState(module.getInternalId(), ModuleState.FAILED.name());
            e.printStackTrace();
        }
    }

    /**
     * Copy any files from the module JAR's `resources/` directory into the
     * module-data/<internalId> folder, but do not overwrite existing files.
     */
    private void extractResourcesIfNeeded(ModuleContainer module) {
        File jarFile = module.getFile();
        if (jarFile == null || !jarFile.exists()) return;

        File targetBase = new File(moduleDataDir, module.getInternalId());
        if (!targetBase.exists()) targetBase.mkdirs();

        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (!name.startsWith("resources/")) continue;

                String rel = name.substring("resources/".length());
                if (rel.isEmpty()) continue;

                File out = new File(targetBase, rel);

                if (entry.isDirectory()) {
                    if (!out.exists()) out.mkdirs();
                    continue;
                }

                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();

                // skip if already exists
                if (out.exists()) continue;

                try (InputStream in = jar.getInputStream(entry)) {
                    Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to extract resources for module " + module.getInternalId() + ": " + e.getMessage());
        }
    }

    public void unloadModule(ModuleContainer module) {
        if (module.getState() == ModuleState.ENABLED) return;

        try {
            if (module.getClassLoader() != null) {
                module.getClassLoader().close();
            }

            module.setClassLoader(null);
            module.setModuleInstance(null);
            module.setContext(null);
            module.setState(ModuleState.UPLOADED);
            if (registryManager != null) registryManager.setModuleState(module.getInternalId(), ModuleState.UPLOADED.name());

        } catch (Exception e) {
            module.setState(ModuleState.FAILED);
            if (registryManager != null) registryManager.setModuleState(module.getInternalId(), ModuleState.FAILED.name());
            e.printStackTrace();
        }
    }

    /* =========================
       ENABLE / DISABLE (MAIN THREAD)
       ========================= */
    public void enableModule(ModuleContainer module) {
        if (module.getState() != ModuleState.DISABLED) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                module.getModuleInstance().onEnable(module.getContext());
                module.setState(ModuleState.ENABLED);
                plugin.getLogger().info("Module enabled: " + module.getInternalId());
                if (registryManager != null) registryManager.setModuleState(module.getInternalId(), ModuleState.ENABLED.name());

            } catch (Exception e) {
                module.setState(ModuleState.FAILED);
                plugin.getLogger().severe("Enable failed: " + module.getInternalId());
                e.printStackTrace();
            }
        });
    }

    public void disableModule(ModuleContainer module) {
        if (module.getState() != ModuleState.ENABLED) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                module.getModuleInstance().onDisable();
                module.setState(ModuleState.DISABLED);
                plugin.getLogger().info("Module disabled: " + module.getInternalId());
                if (registryManager != null) registryManager.setModuleState(module.getInternalId(), ModuleState.DISABLED.name());

            } catch (Exception e) {
                module.setState(ModuleState.FAILED);
                plugin.getLogger().severe("Disable failed: " + module.getInternalId());
                e.printStackTrace();
            }
        });
    }

    /* =========================
       DELETE
       ========================= */
    public void deleteModule(ModuleContainer module) {
        if (module.getState() == ModuleState.ENABLED) return;

        unloadModule(module);

        try {
            module.getFile().delete();
        } catch (Exception ignored) {}

        File dataDir = new File(moduleDataDir, module.getInternalId());
        deleteRecursive(dataDir);

        if (registryManager != null) {
            registryManager.unregister(module.getInternalId());
        }

        modules.remove(module);
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;

        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        try { f.delete(); } catch (Exception ignored) {}
    }

    /* =========================
       MODULE.INFO
       ========================= */
    private ModuleInfo readModuleInfo(File jarFile) {
        ModuleInfo info = new ModuleInfo();

        try (JarFile jar = new JarFile(jarFile)) {

            JarEntry entry = jar.getJarEntry("module.info.yml");
            if (entry == null) {
                info.error = "module.info.yml ontbreekt";
                return info;
            }

            try (InputStream in = jar.getInputStream(entry)) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in)
                );

                info.id = y.getString("id");
                info.name = y.getString("name");
                info.version = y.getString("version");
                info.description = y.getString("description");
                info.mainClass = y.getString("main");
                info.apiVersion = y.getInt("api-version", 1);

                if (info.id == null || info.mainClass == null) {
                    info.error = "Verplichte velden ontbreken (id / main)";
                    return info;
                }

                info.valid = true;
            }

        } catch (Exception e) {
            info.error = "Lezen faalde: " + e.getMessage();
        }

        return info;
    }

    public List<ModuleContainer> getModules() {
        return Collections.unmodifiableList(modules);
    }
}
