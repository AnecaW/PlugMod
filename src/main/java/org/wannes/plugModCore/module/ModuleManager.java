package org.wannes.plugModCore.module;

import org.bukkit.configuration.file.YamlConfiguration;
import org.wannes.plugModCore.api.Module;
import org.wannes.plugModCore.security.RegistryManager;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class ModuleManager {

    private final File modulesDir;
    private final List<ModuleContainer> modules = new ArrayList<>();
    private final RegistryManager registryManager;

    public ModuleManager(File dataFolder, RegistryManager registryManager) {
        this.modulesDir = new File(dataFolder, "modules");
        modulesDir.mkdirs();
        this.registryManager = registryManager;
    }

    /* =========================
       MODULE SCAN FLOW
       ========================= */
    public void scanModules() {
        modules.clear();

        File[] files = modulesDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".jar"));
        if (files == null) return;

        for (File file : files) {
            ModuleContainer container = new ModuleContainer(file);

            // internalId = bestandsnaam zonder ".jar"
            String name = file.getName();
            String internalId = name.substring(0, name.length() - 4);
            container.setInternalId(internalId);

            ModuleInfo info = readModuleInfo(file);
            container.setInfo(info);

            if (!info.valid) {
                container.setState(ModuleState.FAILED);
                modules.add(container);
                continue;
            }

            // After upload we keep modules in UPLOADED state until explicitly loaded
            container.setState(ModuleState.UPLOADED);
            modules.add(container);
        }
    }

    /* =========================
       LOAD / UNLOAD SINGLE MODULE
       ========================= */
    public void loadModule(ModuleContainer module) {
        if (module.getState() != ModuleState.UPLOADED) return;

        ModuleInfo info = module.getInfo();

        try {
            URL jarUrl = module.getFile().toURI().toURL();

            ModuleClassLoader classLoader = new ModuleClassLoader(
                    jarUrl,
                    this.getClass().getClassLoader()
            );

            Class<?> mainClass = classLoader.loadClass(info.mainClass);

            if (!Module.class.isAssignableFrom(mainClass)) {
                throw new IllegalStateException("Main class implementeert Module interface niet");
            }

            Module instance = (Module) mainClass
                    .getDeclaredConstructor()
                    .newInstance();

            module.setClassLoader(classLoader);
            module.setModuleInstance(instance);
            module.setState(ModuleState.DISABLED);

        } catch (Exception e) {
            module.setState(ModuleState.FAILED);
            info.error = "Main class laden faalde: " + e.getMessage();
        }
    }

    public void unloadModule(ModuleContainer module) {
        // Prevent unloading while enabled
        if (module.getState() == ModuleState.ENABLED) return;

        try {
            if (module.getContext() != null) {
                // nothing to call on context, just drop ref
                module.setContext(null);
            }

            if (module.getClassLoader() != null) {
                try {
                    module.getClassLoader().close();
                } catch (Exception ignored) {}
                module.setClassLoader(null);
            }

            module.setModuleInstance(null);
            module.setState(ModuleState.UPLOADED);

        } catch (Exception e) {
            module.setState(ModuleState.FAILED);
        }
    }

    public void deleteModule(ModuleContainer module) {
        // Ensure unloaded
        if (module.getState() == ModuleState.ENABLED) return;

        unloadModule(module);

        // Delete jar
        try {
            module.getFile().delete();
        } catch (Exception ignored) {}

        // Delete data folder
        File dataBase = new File(modulesDir.getParentFile(), "module-data");
        File moduleData = new File(dataBase, module.getInternalId());
        if (moduleData.exists()) {
            // best-effort recursive delete
            deleteRecursive(moduleData);
        }

        // Remove registry entry
        if (registryManager != null) {
            registryManager.unregister(module.getInternalId());
        }
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        try { f.delete(); } catch (Exception ignored) {}
    }

    /* =========================
       MODULE.INFO READER
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

                // verplichte velden
                if (info.id == null || info.mainClass == null) {
                    info.error = "Verplichte velden ontbreken (id / main)";
                    return info;
                }

                info.valid = true;
            }

        } catch (Exception e) {
            info.error = "Fout bij lezen: " + e.getMessage();
        }

        return info;
    }

    /* =========================
       ENABLE / DISABLE
       ========================= */
    public void enableModule(ModuleContainer module) {
        if (module.getState() != ModuleState.DISABLED) return;

        try {
            Module instance = (Module) module.getModuleInstance();
            ModuleContextImpl context = new ModuleContextImpl(
                    module,
                    new File(modulesDir.getParentFile(), "module-data")
            );

            module.setContext(context);
            instance.onEnable(context);
            module.setState(ModuleState.ENABLED);

        } catch (Exception e) {
            module.setState(ModuleState.FAILED);
            module.getInfo().error = "onEnable faalde: " + e.getMessage();
        }
    }

    public void disableModule(ModuleContainer module) {
        if (module.getState() != ModuleState.ENABLED) return;

        try {
            Module instance = (Module) module.getModuleInstance();
            instance.onDisable();
            module.setState(ModuleState.DISABLED);

        } catch (Exception e) {
            module.setState(ModuleState.FAILED);
        }
    }

    /* =========================
       GETTERS
       ========================= */
    public List<ModuleContainer> getModules() {
        return Collections.unmodifiableList(modules);
    }
}
