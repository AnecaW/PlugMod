package org.wannes.plugModCore.module;

import org.bukkit.configuration.file.YamlConfiguration;
import org.wannes.plugModCore.api.Module;

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

    public ModuleManager(File dataFolder) {
        this.modulesDir = new File(dataFolder, "modules");
        modulesDir.mkdirs();
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

            // 1. Lees module.info.yml
            ModuleInfo info = readModuleInfo(file);
            container.setInfo(info);

            if (!info.valid) {
                container.setState(ModuleState.FAILED);
                modules.add(container);
                continue;
            }

            // 2. Info ok → LOADED
            container.setState(ModuleState.LOADED);
            modules.add(container);
        }

        // 3. Main classes laden
        loadMainClasses();
    }

    /* =========================
       MAIN CLASS LOADING
       ========================= */
    private void loadMainClasses() {
        for (ModuleContainer module : modules) {

            if (module.getState() != ModuleState.LOADED) continue;
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
            instance.onEnable();
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
