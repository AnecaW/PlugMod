package org.wannes.plugModCore.module;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.wannes.plugModCore.PlugModCore;
import org.wannes.plugModCore.api.Module;
import org.wannes.plugModCore.security.RegistryManager;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.jar.*;

/** All lifecycle mutations and module callbacks are serialized on the server thread. */
public class ModuleManager {
    private final org.bukkit.plugin.Plugin plugin;
    private final org.wannes.plugModCore.config.CoreConfig config;
    private final File modulesDir;
    private final File moduleDataDir;
    private final RegistryManager registryManager;
    private final List<ModuleContainer> modules = new CopyOnWriteArrayList<>();
    private volatile boolean stopping;
    private boolean operating;
    private Map<File, java.nio.file.Path> preparedArtifacts = new HashMap<>();
    private ModuleInfo preparedReplacement;
    private final ModuleServiceRegistry services = new ModuleServiceRegistry(this);
    private static final class PendingStage {
        final Map<File, java.nio.file.Path> copies = new java.util.concurrent.ConcurrentHashMap<>();
        volatile boolean ready;
    }
    private final Map<java.util.concurrent.CompletableFuture<ModuleState>, PendingStage> pendingStages = new java.util.concurrent.ConcurrentHashMap<>();

    /** Stage file copies off-thread; complete only after the main-thread lifecycle action. */
    public java.util.concurrent.CompletableFuture<ModuleState> executeAsync(String action, ModuleContainer module) {
        mainThread();
        if (stopping || operating) throw new IllegalStateException("Module manager is busy or stopping");
        if (!modules.contains(module)) throw new IllegalArgumentException("Unknown module");
        boolean prepare = action.equals("reload") || (Set.of("load", "enable").contains(action) && order(module).stream().anyMatch(m -> m.getClassLoader() == null));
        if (!prepare) {
            switch (action) {
                case "load" -> loadModule(module);
                case "enable" -> enableModule(module);
                case "disable" -> disableModule(module);
                case "unload" -> unloadModule(module);
                case "delete" -> deleteModule(module);
                default -> throw new IllegalArgumentException("Unknown operation " + action);
            }
            return java.util.concurrent.CompletableFuture.completedFuture(module.getState());
        }
        Set<File> files = new LinkedHashSet<>();
        if (!action.equals("reload")) for (ModuleContainer m : order(module)) if (m.getClassLoader() == null) files.add(m.getFile());
        operating = true;
        var result = new java.util.concurrent.CompletableFuture<ModuleState>();
        PendingStage stage = new PendingStage();
        pendingStages.put(result, stage);
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Map<File, java.nio.file.Path> copies = stage.copies;
                try {
                    if (stopping) { pendingStages.remove(result); return; }
                    ModuleInfo replacement = null;
                    if (action.equals("reload")) {
                        java.nio.file.Path copy = Files.createTempFile("plugmod-staged-", ".jar");
                        copies.put(module.getFile(), copy);
                        Files.copy(module.getFile().toPath(), copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        replacement = readModuleInfo(copy.toFile());
                    }
                    if (replacement != null) collectReplacementArtifacts(module, module, replacement, new HashSet<>(), files);
                    for (File file : files) {
                        if (copies.containsKey(file)) continue;
                        java.nio.file.Path copy = Files.createTempFile("plugmod-staged-", ".jar");
                        copies.put(file, copy);
                        Files.copy(file.toPath(), copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    stage.ready = true;
                    if (stopping) { cleanupSnapshots(copies); pendingStages.remove(result); return; }
                    final ModuleInfo stagedReplacement = replacement;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        ModuleState completed = null;
                        Throwable failure = null;
                        try {
                            if (stopping || result.isCancelled()) throw new IllegalStateException("Operation cancelled or manager stopped");
                            preparedArtifacts = copies;
                            preparedReplacement = stagedReplacement;
                            operating = false;
                            switch (action) {
                                case "load" -> loadModule(module);
                                case "enable" -> enableModule(module);
                                case "reload" -> reloadModule(module);
                            }
                            completed = module.getState();
                        } catch (Exception | LinkageError e) { failure = e; }
                        finally {
                            operating = false;
                            preparedReplacement = null;
                            preparedArtifacts = new HashMap<>();
                            // Only unused snapshots remain in this map; live loaders own consumed ones.
                            cleanupSnapshots(copies);
                            pendingStages.remove(result);
                        }
                        if (failure == null) result.complete(completed); else result.completeExceptionally(failure);
                    });
                } catch (Exception | LinkageError e) {
                    plugin.getLogger().log(Level.SEVERE, "Artifact preparation failed for " + module.getInternalId(), e);
                    cleanupSnapshots(copies);
                    pendingStages.remove(result);
                    try { Bukkit.getScheduler().runTask(plugin, () -> { operating = false; result.completeExceptionally(e); }); }
                    catch (RuntimeException stopped) { result.completeExceptionally(e); plugin.getLogger().log(Level.WARNING, "Staging stopped during shutdown", stopped); }
                }
            });
        } catch (RuntimeException e) { operating = false; pendingStages.remove(result); result.completeExceptionally(e); }
        return result;
    }
    private void cleanupSnapshots(Map<File, java.nio.file.Path> copies) {
        for (java.nio.file.Path copy : copies.values()) {
            try { Files.deleteIfExists(copy); }
            catch (IOException e) { plugin.getLogger().log(Level.WARNING, "Cannot remove staged artifact " + copy, e); }
        }
        copies.clear();
    }
    private void collectReplacementArtifacts(ModuleContainer current, ModuleContainer root, ModuleInfo replacement, Set<ModuleContainer> visited, Set<File> files) {
        if (!visited.add(current)) return;
        ModuleInfo info = current == root ? replacement : current.getInfo();
        if (!info.valid) throw new IllegalStateException("Invalid replacement metadata: " + info.error);
        if (current == root || current.getClassLoader() == null) files.add(current.getFile());
        for (String id : info.dependencies) {
            ModuleContainer dependency = findModule(id);
            if (dependency == null) throw new IllegalStateException("Missing dependency " + id);
            collectReplacementArtifacts(dependency, root, replacement, visited, files);
        }
    }

    public void restoreStartupAsync(boolean autoLoad) {
        mainThread();
        Map<ModuleContainer, String> desired = new LinkedHashMap<>();
        for (ModuleContainer m : modules) desired.put(m, registryManager == null ? null : registryManager.getModuleState(m.getInternalId()));
        restoreNext(new ArrayList<>(desired.keySet()), desired, 0, autoLoad);
    }
    private void restoreNext(List<ModuleContainer> pending, Map<ModuleContainer, String> desired, int index, boolean autoLoad) {
        if (stopping || index >= pending.size()) return;
        ModuleContainer m = pending.get(index);
        String saved = desired.get(m);
        boolean restore = Set.of("ENABLED", "DISABLED", "LOADED").contains(saved == null ? "" : saved);
        if (!m.getInfo().valid || (!restore && !autoLoad)) { restoreNext(pending, desired, index + 1, autoLoad); return; }
        java.util.concurrent.CompletableFuture<ModuleState> result;
        try { result = executeAsync("ENABLED".equals(saved) ? "enable" : "load", m); }
        catch (RuntimeException e) { plugin.getLogger().log(Level.WARNING, "Cannot restore " + m.getInternalId(), e); restoreNext(pending, desired, index + 1, autoLoad); return; }
        result.whenComplete((state, failure) -> {
            Runnable next = () -> {
                if (failure != null) plugin.getLogger().log(Level.WARNING, "Cannot restore " + m.getInternalId(), failure);
                restoreNext(pending, desired, index + 1, autoLoad);
            };
            // The lifecycle finishes on the main thread, but staging failures finish off-thread.
            if (Bukkit.isPrimaryThread()) Bukkit.getScheduler().runTask(plugin, next);
            else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, next);
        });
    }

    public ModuleManager(PlugModCore plugin, RegistryManager registryManager) {
        this(plugin, registryManager, plugin.getCoreConfig());
    }
    ModuleManager(org.bukkit.plugin.Plugin plugin, RegistryManager registryManager, org.wannes.plugModCore.config.CoreConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.registryManager = registryManager;
        modulesDir = new File(plugin.getDataFolder(), "modules");
        moduleDataDir = new File(plugin.getDataFolder(), "module-data");
        if ((!modulesDir.isDirectory() && !modulesDir.mkdirs()) || (!moduleDataDir.isDirectory() && !moduleDataDir.mkdirs()))
            throw new IllegalStateException("Cannot create module directories");
    }
    private void mainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Module operations require the server thread");
    }
    public void scanModules() {
        mainThread();
        if (operating || stopping) throw new IllegalStateException("Module manager is busy");
        File[] files = modulesDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (files == null) throw new IllegalStateException("Cannot scan " + modulesDir);
        Arrays.sort(files, Comparator.comparing(File::getName));
        // Preserve live containers: upload/discovery must never orphan running modules.
        for (File file : files) {
            ModuleContainer existing = modules.stream().filter(m -> m.getFile().equals(file)).findFirst().orElse(null);
            if (existing != null) {
                if (existing.getClassLoader() == null && existing.getModuleInstance() == null) {
                    ModuleInfo refreshed = readModuleInfo(file);
                    if (refreshed.valid && modules.stream().anyMatch(other -> other != existing && other.getInfo().valid && refreshed.id.equals(other.getInfo().id))) {
                        refreshed.valid = false; refreshed.error = "Duplicate module ID: " + refreshed.id;
                    }
                    existing.setInfo(refreshed);
                    existing.setState(refreshed.valid ? ModuleState.UPLOADED : ModuleState.FAILED);
                }
                continue;
            }
            ModuleContainer m = new ModuleContainer(file);
            m.setInternalId(file.getName().substring(0, file.getName().length() - 4));
            m.setInfo(readModuleInfo(file));
            if (m.getInfo().valid && modules.stream().anyMatch(other -> other.getInfo().valid && other.getInfo().id.equals(m.getInfo().id))) {
                m.getInfo().valid = false;
                m.getInfo().error = "Duplicate module ID: " + m.getInfo().id;
            }
            m.setState(m.getInfo().valid ? ModuleState.UPLOADED : ModuleState.FAILED);
            modules.add(m);
        }
        // Validate only after discovery has populated the complete graph.
        for (ModuleContainer module : modules) if (module.getClassLoader() == null && module.getInfo().valid) {
            try { order(module); module.getInfo().error = null; }
            catch (IllegalStateException e) { module.getInfo().error = e.getMessage(); plugin.getLogger().warning("Module dependency validation: " + e.getMessage()); }
        }
    }
    public void restoreStatesOnStartup() {
        restoreStartupAsync(false);
    }
    private void operation(ModuleContainer m, Runnable action) {
        mainThread();
        if (stopping || operating) throw new IllegalStateException("Module manager is busy or stopping");
        if (!modules.contains(m)) throw new IllegalArgumentException("Unknown module");
        operating = true;
        try { action.run(); }
        finally { operating = false; }
    }
    private void state(ModuleContainer m, ModuleState state) {
        ModuleState previous = m.getState();
        m.setState(state);
        if (registryManager != null) registryManager.setModuleState(m.getInternalId(), state.name());
        Bukkit.getPluginManager().callEvent(new org.wannes.plugModCore.api.ModuleLifecycleEvent(m.getInfo().id, previous.name(), state.name()));
    }
    private IllegalStateException fail(ModuleContainer m, String operation, Throwable cause) {
        String diagnostic = cause.toString();
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof NoClassDefFoundError && current.getMessage() != null
                    && current.getMessage().replace('/', '.').startsWith("org.wannes.plugModCore.api.")) {
                diagnostic = missingApi(m.getInfo(), current.getMessage().replace('/', '.'), plugin.getClass().getClassLoader());
                break;
            }
        }
        m.getInfo().error = operation + " failed; recovery required: " + diagnostic;
        state(m, ModuleState.FAILED);
        plugin.getLogger().log(Level.SEVERE, m.getInternalId() + ": " + operation + " failed", cause);
        return new IllegalStateException(m.getInfo().error, cause);
    }
    public ModuleContainer findModule(String id) {
        return modules.stream().filter(m -> m.getInternalId().equals(id) || (m.getInfo().valid && id.equals(m.getInfo().id))).findFirst().orElse(null);
    }
    public java.util.Optional<org.wannes.plugModCore.api.ModuleStatus> moduleStatus(String name) {
        return modules.stream().filter(m -> m.getInfo().id != null && (m.getInfo().id.equalsIgnoreCase(name) || m.getInfo().name.equalsIgnoreCase(name))).findFirst().map(m -> new org.wannes.plugModCore.api.ModuleStatus(m.getInfo().id, m.getInfo().name, m.getInfo().version, m.getState().name()));
    }
    public boolean dependsOn(String consumer, String provider) {
        ModuleContainer root = findModule(consumer); ModuleContainer target = findModule(provider);
        return root != null && target != null && root != target && order(root).contains(target);
    }
    private List<ModuleContainer> order(ModuleContainer root) {
        List<ModuleContainer> result = new ArrayList<>();
        visit(root, new HashSet<>(), new HashSet<>(), result);
        plugin.getLogger().fine("Dependency order for " + root.getInfo().id + ": " + result.stream().map(m -> m.getInfo().id).toList());
        return result;
    }
    private void visit(ModuleContainer m, Set<ModuleContainer> visiting, Set<ModuleContainer> visited, List<ModuleContainer> result) {
        if (visited.contains(m)) return;
        if (!m.getInfo().valid) throw new IllegalStateException("Invalid metadata: " + m.getInternalId() + ": " + m.getInfo().error);
        if (!visiting.add(m)) throw new IllegalStateException("Circular dependency at " + m.getInfo().id);
        for (String id : m.getInfo().dependencies) {
            ModuleContainer dependency = modules.stream().filter(d -> d.getInfo().valid && id.equals(d.getInfo().id)).findFirst().orElse(null);
            if (dependency == null) throw new IllegalStateException(m.getInfo().id + " requires missing dependency " + id);
            visit(dependency, visiting, visited, result);
        }
        visiting.remove(m);
        visited.add(m);
        result.add(m);
    }
    private void requireNoDependants(ModuleContainer m, boolean enabledOnly) {
        for (ModuleContainer other : modules) {
            if (other == m || other.getModuleInstance() == null) continue;
            if (enabledOnly && other.getState() == ModuleState.DISABLED) continue;
            if (other.getInfo().dependencies.contains(m.getInfo().id))
                throw new IllegalStateException("Stop/unload dependant " + other.getInfo().id + " first");
        }
    }
    private void callback(ModuleContainer m, Runnable action) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try { thread.setContextClassLoader(m.getClassLoader()); action.run(); }
        finally { thread.setContextClassLoader(previous); }
    }
    public void loadModuleForce(ModuleContainer m) { loadModule(m); }
    public void loadModule(ModuleContainer m) {
        operation(m, () -> { for (ModuleContainer dependency : order(m)) loadOne(dependency); });
    }
    private void loadOne(ModuleContainer m) {
        loadOne(m, m.getFile());
    }
    private void loadOne(ModuleContainer m, File artifact) {
        if (m.getState() == ModuleState.DISABLED || m.getState() == ModuleState.ENABLED) return;
        if (m.getModuleInstance() != null || m.getClassLoader() != null) throw new IllegalStateException("Unload failed instance first: " + m.getInfo().id);
        state(m, ModuleState.LOADING);
        try {
            validateSharedApi(m.getInfo(), plugin.getClass().getClassLoader());
            List<ModuleClassLoader> dependencies = new ArrayList<>();
            for (String id : m.getInfo().dependencies) dependencies.add(findModule(id).getClassLoader());
            java.nio.file.Path prepared = preparedArtifacts.remove(artifact);
            ModuleClassLoader loader = prepared == null
                    ? new ModuleClassLoader(artifact.toURI().toURL(), plugin.getClass().getClassLoader(), dependencies)
                    : ModuleClassLoader.fromSnapshot(prepared, plugin.getClass().getClassLoader(), dependencies);
            m.setClassLoader(loader);
            List<ClassLoader> plugins = new ArrayList<>();
            for (String name : m.getInfo().pluginDependencies) {
                var external = Bukkit.getPluginManager().getPlugin(name);
                if (external == null) throw new IllegalStateException("Module " + m.getInfo().id + " requires external Paper plugin " + name + "; not found");
                plugins.add(external.getClass().getClassLoader());
            }
            loader.configure(m.getInfo().exports, plugins);
            Class<?> type = loader.loadClass(m.getInfo().mainClass);
            if (!Module.class.isAssignableFrom(type)) throw new IllegalArgumentException("Main class must implement Module");
            callback(m, () -> {
                try { m.setModuleInstance((Module) type.getDeclaredConstructor().newInstance()); }
                catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot construct module", e); }
            });
            m.setContext(new ModuleContextImpl(m, moduleDataDir, plugin, services, this));
            callback(m, () -> m.getModuleInstance().onLoad(m.getContext()));
            m.getInfo().error = null;
            state(m, ModuleState.DISABLED);
        } catch (Exception | LinkageError e) {
            try { release(m); } catch (Exception | LinkageError cleanup) { e.addSuppressed(cleanup); }
            throw fail(m, "Load", e);
        }
    }
    public void enableModule(ModuleContainer m) {
        operation(m, () -> {
            for (ModuleContainer dependency : order(m)) {
                loadOne(dependency);
                enableOne(dependency);
            }
        });
    }
    private void enableOne(ModuleContainer m) {
        if (m.getState() == ModuleState.ENABLED) return;
        if (m.getState() != ModuleState.DISABLED) throw new IllegalStateException("Cannot enable " + m.getState());
        if (m.getContext() == null) m.setContext(new ModuleContextImpl(m, moduleDataDir, plugin, services, this));
        state(m, ModuleState.ENABLING);
        try {
            for (String name : m.getInfo().pluginDependencies) if (!Bukkit.getPluginManager().isPluginEnabled(name)) throw new IllegalStateException("Module " + m.getInfo().id + " requires enabled external Paper plugin " + name);
            for (var required : m.getInfo().requiredServices.entrySet()) {
                Class<?> type = m.getClassLoader().loadClass(required.getKey());
                services.require(m.getContext(), type, required.getValue());
            }
            callback(m, () -> m.getModuleInstance().onEnable(m.getContext()));
            state(m, ModuleState.ENABLED);
        } catch (Exception | LinkageError e) {
            try { callback(m, () -> m.getModuleInstance().onDisable()); } catch (Exception | LinkageError cleanup) { e.addSuppressed(cleanup); }
            try { m.getContext().cleanup(); m.setContext(null); } catch (Exception cleanup) { e.addSuppressed(cleanup); }
            throw fail(m, "Enable", e);
        }
    }
    public void disableModule(ModuleContainer m) {
        operation(m, () -> { requireNoDependants(m, true); disableOne(m); });
    }
    private void disableOne(ModuleContainer m) {
        if (m.getState() == ModuleState.DISABLED || m.getState() == ModuleState.UPLOADED) return;
        if (m.getModuleInstance() == null) return;
        if (m.getContext() != null) m.getContext().prepareDisable();
        state(m, ModuleState.DISABLING);
        Throwable failure = null;
        try { callback(m, () -> m.getModuleInstance().onDisable()); } catch (Exception | LinkageError e) { failure = e; }
        try { if (m.getContext() != null) { m.getContext().cleanup(); m.setContext(null); } }
        catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        if (failure != null) throw fail(m, "Disable", failure);
        state(m, ModuleState.DISABLED);
    }
    public void unloadModule(ModuleContainer m) {
        operation(m, () -> { requireNoDependants(m, false); unloadOne(m); });
    }
    private void unloadOne(ModuleContainer m) {
        if (m.getState() == ModuleState.ENABLED || m.getState() == ModuleState.FAILED) disableOne(m);
        state(m, ModuleState.UNLOADING);
        try { release(m); state(m, ModuleState.UPLOADED); }
        catch (Exception | LinkageError e) { throw fail(m, "Unload", e); }
    }
    private void release(ModuleContainer m) throws Exception {
        Throwable failure = null;
        try { if (m.getModuleInstance() != null) callback(m, () -> m.getModuleInstance().onUnload()); }
        catch (Exception | LinkageError e) { failure = e; }
        try { if (m.getContext() != null) m.getContext().cleanup(); }
        catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        // Retain references after failed cleanup, allowing recovery instead of claiming an unload.
        if (failure != null) throw new Exception("Cleanup incomplete", failure);
        if (m.getClassLoader() != null) m.getClassLoader().close();
        m.setContext(null);
        m.clearInstance();
        m.setClassLoader(null);
    }
    public void reloadModule(ModuleContainer m) {
        operation(m, () -> {
            if (!config.allowHotReload) throw new IllegalStateException("Hot reload is disabled in configuration");
            List<ModuleContainer> consumers = new ArrayList<>();
            for (ModuleContainer candidate : modules) if (candidate != m && candidate.getClassLoader() != null && dependsOn(candidate.getInfo().id, m.getInfo().id)) {
                for (ModuleContainer node : order(candidate)) if (node != m && node.getClassLoader() != null && dependsOn(node.getInfo().id, m.getInfo().id) && !consumers.contains(node)) consumers.add(node);
            }
            Map<ModuleContainer, java.nio.file.Path> backups = new LinkedHashMap<>();
            Map<ModuleContainer, Boolean> enabled = new HashMap<>();
            Set<ModuleContainer> detached = new HashSet<>(); Throwable failure = null;
            try {
                for (ModuleContainer consumer : consumers) { backups.put(consumer, consumer.getClassLoader().getArtifact()); enabled.put(consumer, consumer.getState() == ModuleState.ENABLED); consumer.getClassLoader().retainArtifact(); }
                List<ModuleContainer> reverse = new ArrayList<>(consumers); Collections.reverse(reverse);
                for (ModuleContainer consumer : reverse) { unloadOne(consumer); detached.add(consumer); }
                reloadOne(m);
            } catch (Exception | LinkageError e) { failure = e; }
            for (ModuleContainer consumer : consumers) if (detached.contains(consumer)) {
                try {
                    if (m.getState() != ModuleState.ENABLED && Boolean.TRUE.equals(enabled.get(consumer))) throw new IllegalStateException("Provider " + m.getInfo().id + " could not be restored; dependant " + consumer.getInfo().id + " remains unloaded");
                    java.nio.file.Path artifact = backups.get(consumer); preparedArtifacts.put(artifact.toFile(), artifact);
                    for (ModuleContainer dependency : order(consumer)) if (dependency != consumer) loadOne(dependency);
                    loadOne(consumer, artifact.toFile());
                    if (Boolean.TRUE.equals(enabled.get(consumer))) for (ModuleContainer dependency : order(consumer)) enableOne(dependency);
                } catch (Exception | LinkageError e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
            for (var backup : backups.entrySet()) {
                preparedArtifacts.remove(backup.getValue().toFile());
                var loader = backup.getKey().getClassLoader();
                if (loader != null && loader.getArtifact().equals(backup.getValue())) loader.releaseRetainedArtifact();
                else try { Files.deleteIfExists(backup.getValue()); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
            }
            if (failure != null) throw new IllegalStateException("Reload of " + m.getInfo().id + " or dependant recovery failed: " + failure.getMessage(), failure);
        });
    }
    private void reloadOne(ModuleContainer m) {
            if (!config.allowHotReload) throw new IllegalStateException("Hot reload is disabled in configuration");
            requireNoDependants(m, false);
            if (m.getModuleInstance() == null) throw new IllegalStateException("Load module before reload");
            ModuleInfo replacement = preparedReplacement != null ? preparedReplacement : readModuleInfo(m.getFile());
            if (!replacement.valid || !replacement.id.equals(m.getInfo().id)) throw new IllegalStateException("Replacement metadata is invalid or changes module ID");
            ModuleInfo original = m.getInfo();
            m.setInfo(replacement);
            try { order(m); } finally { m.setInfo(original); }
            boolean enabled = m.getState() == ModuleState.ENABLED;
            java.nio.file.Path backup = m.getClassLoader().getArtifact();
            m.getClassLoader().retainArtifact();
            try {
                unloadOne(m);
                m.setInfo(replacement);
                try {
                    for (ModuleContainer dependency : order(m)) loadOne(dependency);
                    if (enabled) for (ModuleContainer dependency : order(m)) enableOne(dependency);
                } catch (RuntimeException replacementFailure) {
                    try {
                        unloadOne(m);
                        m.setInfo(original);
                        preparedArtifacts.put(backup.toFile(), backup);
                        loadOne(m, backup.toFile());
                        if (enabled) enableOne(m);
                    } catch (RuntimeException recoveryFailure) {
                        replacementFailure.addSuppressed(recoveryFailure);
                        throw fail(m, "Reload and recovery", replacementFailure);
                    }
                    throw new IllegalStateException("Replacement failed; previous artifact restored as " + m.getState(), replacementFailure);
                }
            } finally {
                if (m.getClassLoader() != null && m.getClassLoader().getArtifact().equals(backup)) m.getClassLoader().releaseRetainedArtifact();
                try { if (m.getClassLoader() == null || !m.getClassLoader().getArtifact().equals(backup)) Files.deleteIfExists(backup); }
                catch (IOException e) { plugin.getLogger().log(Level.WARNING, "Cannot remove reload backup " + backup, e); }
            }
    }
    public void deleteModule(ModuleContainer m) {
        operation(m, () -> {
            requireNoDependants(m, false);
            unloadOne(m);
            try { Files.delete(m.getFile().toPath()); }
            catch (IOException e) { throw fail(m, "Delete artifact", e); }
            // Persistent module data is deliberately preserved.
            if (registryManager != null) registryManager.unregister(m.getInternalId());
            modules.remove(m);
        });
    }
    public void shutdown() {
        mainThread();
        stopping = true;
        for (var entry : pendingStages.entrySet()) {
            entry.getKey().completeExceptionally(new IllegalStateException("Manager stopped during artifact preparation"));
            if (entry.getValue().ready) cleanupSnapshots(entry.getValue().copies);
        }
        pendingStages.clear();
        List<ModuleContainer> ordered = new ArrayList<>();
        for (ModuleContainer m : modules) {
            try { for (ModuleContainer d : order(m)) if (!ordered.contains(d)) ordered.add(d); }
            catch (RuntimeException e) { if (!ordered.contains(m)) ordered.add(m); }
        }
        Collections.reverse(ordered);
        for (ModuleContainer m : ordered) {
            if (m.getClassLoader() == null) continue;
            String desired = m.getState().name();
            try { unloadOne(m); }
            catch (RuntimeException e) { plugin.getLogger().log(Level.SEVERE, "Shutdown incomplete for " + m.getInternalId(), e); }
            finally { if (registryManager != null) registryManager.setModuleState(m.getInternalId(), desired); }
        }
    }
    private ModuleInfo readModuleInfo(File file) {
        ModuleInfo info = new ModuleInfo();
        try (JarFile jar = new JarFile(file)) {
            JarEntry entry = jar.getJarEntry("module.info.yml");
            if (entry == null) entry = jar.getJarEntry("resources/module.info.yml");
            if (entry == null) throw new IllegalArgumentException("Missing module.info.yml");
            YamlConfiguration y = new YamlConfiguration();
            try (InputStream in = jar.getInputStream(entry); Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) { y.load(reader); }
            info.id = y.getString("id");
            info.mainClass = y.getString("main");
            info.name = y.getString("name", info.id);
            info.version = y.getString("version");
            info.description = y.getString("description", "");
            info.apiVersion = y.getInt("api-version", 1);
            info.dependencies = List.copyOf(y.getStringList("dependencies"));
            info.exports = List.copyOf(y.getStringList("exports"));
            info.pluginDependencies = List.copyOf(y.getStringList("plugin-dependencies"));
            info.requiredApiClasses = List.copyOf(y.getStringList("required-api-classes"));
            Map<String, String> required = new LinkedHashMap<>();
            var section = y.getConfigurationSection("required-services");
            if (section != null) for (var entryValue : section.getValues(true).entrySet()) if (entryValue.getValue() instanceof String provider) required.put(entryValue.getKey(), provider);
            info.requiredServices = Map.copyOf(required);
            info.websiteEnabled = y.getBoolean("website.enabled", false);
            info.websiteEntry = y.getString("website.entry", "web/index.html");
            info.websiteTitle = y.getString("website.title", info.name);
            if (info.id == null || !info.id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid module ID");
            if (info.mainClass == null || info.mainClass.isBlank() || info.version == null || info.version.isBlank()) throw new IllegalArgumentException("Required main/version missing");
            if (info.apiVersion != 1) throw new IllegalArgumentException("Unsupported module API version " + info.apiVersion);
            for (String api : info.requiredApiClasses) if (!api.matches("org\\.wannes\\.plugModCore\\.api\\.[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*"))
                throw new IllegalArgumentException("Invalid shared API requirement " + api);
            for (String dependency : info.dependencies) if (!dependency.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid dependency ID " + dependency);
            for (String api : info.exports) if (!api.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*") || api.startsWith("org.wannes.plugModCore.api") || api.startsWith("org.bukkit") || api.startsWith("io.papermc.paper") || api.startsWith("net.kyori") || api.equals("java") || api.startsWith("java.")) throw new IllegalArgumentException("Invalid/reserved API export " + api);
            info.valid = true;
        } catch (Exception e) { info.error = "Metadata: " + e.getMessage(); plugin.getLogger().log(Level.WARNING, "Invalid module " + file.getName(), e); }
        return info;
    }
    public List<ModuleContainer> getModules() { return List.copyOf(modules); }

    static void validateSharedApi(ModuleInfo info, ClassLoader coreLoader) {
        for (String name : info.requiredApiClasses) {
            try { Class.forName(name, false, coreLoader); }
            catch (ClassNotFoundException | LinkageError e) {
                throw new IllegalStateException(missingApi(info, name, coreLoader), e);
            }
        }
    }

    private static String missingApi(ModuleInfo info, String name, ClassLoader coreLoader) {
        String artifact = "runtime core artifact unavailable";
        try {
            var source = Class.forName(Module.class.getName(), false, coreLoader).getProtectionDomain().getCodeSource();
            if (source != null) artifact = source.getLocation().toString();
        } catch (ClassNotFoundException | SecurityException ignored) { }
        return "Module '" + info.id + "' requires shared API class '" + name
                + "' (module API version " + info.apiVersion + "). Missing or incompatible in PlugModCore: " + artifact
                + ". Deploy the matching rebuilt PlugModCore core JAR and module from the same reactor build,"
                + " then fully restart Paper. Module reload cannot replace the core's API classes."
                + " Do not bundle a second PlugModCore API inside the module.";
    }
}


