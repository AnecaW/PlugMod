package org.wannes.plugModCore.module;

import org.wannes.plugModCore.api.ModuleContext;

import java.io.File;
import java.util.logging.Logger;

public class ModuleContextImpl implements ModuleContext {

    private final ModuleContainer module;
    private final File dataFolder;
    private final Logger logger;

    public ModuleContextImpl(ModuleContainer module, File baseDataFolder) {
        this.module = module;

        this.dataFolder = new File(baseDataFolder, module.getInfo().id);
        this.dataFolder.mkdirs();

        this.logger = Logger.getLogger(
                "PlugModCore::" + module.getInfo().id
        );
    }

    @Override
    public String getModuleId() {
        return module.getInfo().id;
    }

    @Override
    public String getModuleName() {
        return module.getInfo().name;
    }

    @Override
    public String getVersion() {
        return module.getInfo().version;
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    @Override
    public File getDataFolder() {
        return dataFolder;
    }
}
