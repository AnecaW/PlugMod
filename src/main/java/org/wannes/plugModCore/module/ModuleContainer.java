package org.wannes.plugModCore.module;

import java.io.File;

public class ModuleContainer {

    private final File file;
    private ModuleState state = ModuleState.UPLOADED;
    private ModuleInfo info;

    // NIEUW
    private ModuleClassLoader classLoader;
    private Object moduleInstance;

    public ModuleContainer(File file) {
        this.file = file;
    }

    public File getFile() { return file; }
    public String getFileName() { return file.getName(); }

    public ModuleState getState() { return state; }
    public void setState(ModuleState state) { this.state = state; }

    public ModuleInfo getInfo() { return info; }
    public void setInfo(ModuleInfo info) { this.info = info; }

    // NIEUW
    public ModuleClassLoader getClassLoader() { return classLoader; }
    public void setClassLoader(ModuleClassLoader cl) { this.classLoader = cl; }

    public Object getModuleInstance() { return moduleInstance; }
    public void setModuleInstance(Object instance) { this.moduleInstance = instance; }
    private ModuleContextImpl context;
    public ModuleContextImpl getContext() {
        return context;
    }

    public void setContext(ModuleContextImpl context) {
        this.context = context;
    }

    private String internalId;

    public String getInternalId() {
        return internalId;
    }

    public void setInternalId(String internalId) {
        this.internalId = internalId;
    }
}
