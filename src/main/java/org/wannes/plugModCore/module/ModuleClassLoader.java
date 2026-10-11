package org.wannes.plugModCore.module;

import java.net.URL;
import java.net.URLClassLoader;

public class ModuleClassLoader extends URLClassLoader {
    private final java.util.List<ModuleClassLoader> dependencies;
    private final java.nio.file.Path artifact;
    private boolean retainArtifact;
    private java.util.List<String> exports = java.util.List.of();
    private java.util.List<ClassLoader> pluginDependencies = java.util.List.of();
    void configure(java.util.List<String> exports, java.util.List<ClassLoader> plugins) { this.exports = java.util.List.copyOf(exports); this.pluginDependencies = java.util.List.copyOf(plugins); }
    public boolean exports(String name) { return exports.stream().anyMatch(p -> name.startsWith(p + ".")); }
    private void apiOwners(String name, java.util.Set<ModuleClassLoader> visited, java.util.Set<ModuleClassLoader> owners) {
        if (!visited.add(this)) return;
        if (exports(name)) owners.add(this);
        for (ModuleClassLoader dependency : dependencies) dependency.apiOwners(name, visited, owners);
    }

    public ModuleClassLoader(URL jarUrl, ClassLoader parent) throws java.io.IOException {
        this(jarUrl, parent, java.util.List.of());
    }
    public ModuleClassLoader(URL jarUrl, ClassLoader parent, java.util.List<ModuleClassLoader> dependencies) throws java.io.IOException {
        this(snapshot(jarUrl), parent, dependencies);
    }
    private ModuleClassLoader(java.nio.file.Path artifact, ClassLoader parent, java.util.List<ModuleClassLoader> dependencies) throws java.net.MalformedURLException {
        super(new URL[]{artifact.toUri().toURL()}, parent);
        this.artifact = artifact;
        this.dependencies = java.util.List.copyOf(dependencies);
    }
    private static java.nio.file.Path snapshot(URL source) throws java.io.IOException {
        java.nio.file.Path copy = java.nio.file.Files.createTempFile("plugmod-module-", ".jar");
        try (java.io.InputStream input = source.openStream()) {
            java.nio.file.Files.copy(input, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return copy;
        } catch (java.io.IOException e) { java.nio.file.Files.deleteIfExists(copy); throw e; }
    }
    java.nio.file.Path getArtifact() { return artifact; }
    void retainArtifact() { retainArtifact = true; }
    void releaseRetainedArtifact() { retainArtifact = false; }
    static ModuleClassLoader fromSnapshot(java.nio.file.Path artifact, ClassLoader parent, java.util.List<ModuleClassLoader> dependencies) throws java.io.IOException {
        return new ModuleClassLoader(artifact, parent, dependencies);
    }
    @Override public void close() throws java.io.IOException {
        super.close();
        if (!retainArtifact) java.nio.file.Files.deleteIfExists(artifact);
    }
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.startsWith("org.wannes.plugModCore.api.") || name.startsWith("org.bukkit.") || name.startsWith("io.papermc.paper.") || name.startsWith("net.kyori.") || name.startsWith("java."))
            return getParent().loadClass(name);
        java.util.Set<ModuleClassLoader> owners = new java.util.HashSet<>();
        for (ModuleClassLoader dependency : dependencies) dependency.apiOwners(name, new java.util.HashSet<>(), owners);
        if (owners.size() > 1 || (exports(name) && !owners.isEmpty())) throw new ClassNotFoundException("Conflicting exported API providers for " + name + "; declare one authoritative API owner");
        if (!owners.isEmpty()) return owners.iterator().next().loadClass(name);
        return super.loadClass(name, resolve);
    }
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try { return super.findClass(name); }
        catch (ClassNotFoundException ignored) { /* Only declared dependencies are visible. */ }
        for (ModuleClassLoader dependency : dependencies) {
            try { return dependency.loadClass(name); }
            catch (ClassNotFoundException ignored) { /* Try the next declared dependency. */ }
        }
        for (ClassLoader dependency : pluginDependencies) {
            try { return dependency.loadClass(name); } catch (ClassNotFoundException ignored) { }
        }
        throw new ClassNotFoundException("Class " + name + " unavailable in this module, its declared module dependencies or external plugin dependencies");
    }
}
