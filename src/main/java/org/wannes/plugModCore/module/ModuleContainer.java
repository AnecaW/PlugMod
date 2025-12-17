package org.wannes.plugModCore.module;

import org.wannes.plugModCore.api.Module;

import java.io.File;

public class ModuleContainer {

    private final File file;

    private ModuleState state = ModuleState.UPLOADED;
    private ModuleInfo info;

    private ModuleClassLoader classLoader;

    // BELANGRIJK: dit is GEEN Object meer
    private Module moduleInstance;

    private ModuleContextImpl context;

    private String internalId;

    public ModuleContainer(File file) {
        this.file = file;
    }

    public File getFile() {
        return file;
    }

    public String getFileName() {
        return file.getName();
    }

    public ModuleState getState() {
        return state;
    }

    public void setState(ModuleState state) {
        this.state = state;
    }

    public ModuleInfo getInfo() {
        return info;
    }

    public void setInfo(ModuleInfo info) {
        this.info = info;
    }

    public ModuleClassLoader getClassLoader() {
        return classLoader;
    }

    public void setClassLoader(ModuleClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public Module getModuleInstance() {
        return moduleInstance;
    }

    public void setModuleInstance(Module moduleInstance) {
        this.moduleInstance = moduleInstance;
    }

    public ModuleContextImpl getContext() {
        return context;
    }

    public void setContext(ModuleContextImpl context) {
        this.context = context;
    }

    public String getInternalId() {
        return internalId;
    }

    public void setInternalId(String internalId) {
        this.internalId = internalId;
    }
}
