package org.wannes.plugModCore.module;

import org.wannes.plugModCore.api.Module;
import java.io.File;

/** Runtime holder. Only ModuleManager mutates lifecycle data on the server thread. */
public class ModuleContainer {
    private final File file;
    private volatile ModuleState state = ModuleState.UPLOADED;
    private volatile ModuleInfo info;
    private volatile ModuleClassLoader classLoader;
    private volatile Module moduleInstance;
    private volatile ModuleContextImpl context;
    private String internalId;
    public ModuleContainer(File file) { this.file = file; }
    public File getFile() { return file; }
    public String getFileName() { return file.getName(); }
    public ModuleState getState() { return state; }
    public void setState(ModuleState state) { this.state = state; }
    public ModuleInfo getInfo() { return info; }
    public void setInfo(ModuleInfo info) { this.info = info; }
    public ModuleClassLoader getClassLoader() { return classLoader; }
    public void setClassLoader(ModuleClassLoader loader) { classLoader = loader; }
    public Module getModuleInstance() { return moduleInstance; }
    public void setModuleInstance(Module instance) { moduleInstance = instance; }
    public void clearInstance() { moduleInstance = null; }
    public ModuleContextImpl getContext() { return context; }
    public void setContext(ModuleContextImpl context) { this.context = context; }
    public String getInternalId() { return internalId; }
    public void setInternalId(String id) { internalId = id; }
}
