package org.wannes.plugModCore.module;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.wannes.plugModCore.api.ModuleContext;
import java.io.File;
import java.util.*;
import java.util.logging.Logger;

/** Resources belong to one activation, never to the entire host plugin. */
public class ModuleContextImpl implements ModuleContext {
    private final ModuleContainer module;
    private final File dataFolder;
    private final Plugin hostPlugin;
    private final Logger logger;
    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    private volatile boolean closed;
    private int running;
    private int serviceCalls;
    private boolean acceptingServices = true;
    private final ModuleServiceRegistry services;
    private final ModuleManager manager;
    private static final Map<Plugin, Map<String, Integer>> OUTGOING = new WeakHashMap<>();
    public ModuleContextImpl(ModuleContainer module, File base, Plugin hostPlugin) {
        this(module, base, hostPlugin, null, null);
    }
    ModuleContextImpl(ModuleContainer module, File base, Plugin hostPlugin, ModuleServiceRegistry services, ModuleManager manager) {
        this.module = module;
        this.services = services; this.manager = manager;
        this.hostPlugin = hostPlugin;
        dataFolder = new File(base, module.getInternalId());
        if (!dataFolder.isDirectory() && !dataFolder.mkdirs()) throw new IllegalStateException("Cannot create " + dataFolder);
        logger = Logger.getLogger("PlugModCore::" + getModuleId());
    }
    public String getModuleId() { return module.getInfo().id; }
    public String getModuleName() { return module.getInfo().name; }
    public String getVersion() { return module.getInfo().version; }
    public File getDataFolder() { return dataFolder; }
    public Logger getLogger() { return logger; }
    public Plugin getHostPlugin() { return hostPlugin; }
    public synchronized <T extends AutoCloseable> T manage(T resource) {
        if (closed) throw new IllegalStateException("Module context is closed");
        resources.push(Objects.requireNonNull(resource));
        return resource;
    }
    private void checkThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Registration requires the server thread");
        if (closed) throw new IllegalStateException("Module context is closed");
    }
    public void registerListener(Listener listener) {
        checkThread();
        hostPlugin.getServer().getPluginManager().registerEvents(listener, hostPlugin);
        manage(() -> HandlerList.unregisterAll(listener));
    }
    public synchronized BukkitTask schedule(Runnable action, long delay, long period, boolean async) {
        if (closed) throw new IllegalStateException("Module context is closed");
        Runnable guarded = () -> {
            synchronized (this) { if (closed) return; running++; }
            Thread thread = Thread.currentThread();
            ClassLoader previous = thread.getContextClassLoader();
            try { thread.setContextClassLoader(module.getClassLoader()); action.run(); }
            finally { thread.setContextClassLoader(previous); synchronized (this) { running--; } }
        };
        BukkitTask task = period > 0
            ? (async ? Bukkit.getScheduler().runTaskTimerAsynchronously(hostPlugin, guarded, delay, period) : Bukkit.getScheduler().runTaskTimer(hostPlugin, guarded, delay, period))
            : (async ? Bukkit.getScheduler().runTaskLaterAsynchronously(hostPlugin, guarded, delay) : Bukkit.getScheduler().runTaskLater(hostPlugin, guarded, delay));
        manage(task::cancel);
        return task;
    }
    public <T> void registerService(Class<T> type, T service) {
        checkThread();
        if (services != null) { services.register(this, type, service); return; }
        hostPlugin.getServer().getServicesManager().register(type, service, hostPlugin, org.bukkit.plugin.ServicePriority.Normal);
        manage(() -> hostPlugin.getServer().getServicesManager().unregister(type, service));
    }
    public <T> T getService(Class<T> type) { return services == null ? hostPlugin.getServer().getServicesManager().load(type) : services.get(this, type); }
    public <T> T getOptionalService(Class<T> type) {
        // Optional integrations must use a parent-owned contract, not an isolated provider class.
        try { if(hostPlugin.getClass().getClassLoader().loadClass(type.getName()) != type) throw new IllegalArgumentException("Optional API has duplicate class identity: "+type.getName()); }
        catch(ClassNotFoundException failure) { throw new IllegalArgumentException("Optional API must be shared by the core: "+type.getName(),failure); }
        return services == null ? hostPlugin.getServer().getServicesManager().load(type) : services.getOptional(this,type);
    }
    public <T> T requireService(Class<T> type, String provider) {
        if (services == null) return ModuleContext.super.requireService(type, provider);
        services.require(this, type, provider);
        T service = getService(type);
        if (service == null) throw new IllegalStateException("Service " + type.getName() + " from " + provider + " became unavailable for " + getModuleId());
        return service;
    }
    public java.util.Optional<org.wannes.plugModCore.api.ModuleStatus> findModule(String name) { return manager == null ? java.util.Optional.empty() : manager.moduleStatus(name); }
    public boolean isClosed() { return closed; }
    public boolean isServiceReady() { return !closed && module.getState() == ModuleState.ENABLED; }
    @FunctionalInterface interface ServiceCall { Object invoke() throws Throwable; }
    Object invokeService(ServiceCall action) throws Throwable {
        synchronized (this) { if (!acceptingServices || !isServiceReady()) throw new IllegalStateException("Module " + getModuleId() + " is not enabled"); running++; serviceCalls++; }
        Thread thread = Thread.currentThread(); ClassLoader previous = thread.getContextClassLoader();
        try { thread.setContextClassLoader(module.getClassLoader()); return action.invoke(); }
        finally { thread.setContextClassLoader(previous); synchronized (this) { running--; serviceCalls--; } }
    }
    synchronized void prepareDisable() {
        if (serviceCalls > 0) throw new IllegalStateException("Module " + getModuleId() + " has " + serviceCalls + " active service calls; retry after they finish");
        acceptingServices = false;
    }
    public void registerIncomingChannel(String channel, org.bukkit.plugin.messaging.PluginMessageListener listener) {
        checkThread();
        var messenger = hostPlugin.getServer().getMessenger();
        messenger.registerIncomingPluginChannel(hostPlugin, channel, listener);
        manage(() -> messenger.unregisterIncomingPluginChannel(hostPlugin, channel, listener));
    }
    public void registerOutgoingChannel(String channel) {
        checkThread();
        var messenger = hostPlugin.getServer().getMessenger();
        synchronized (OUTGOING) {
            Map<String, Integer> channels = OUTGOING.computeIfAbsent(hostPlugin, ignored -> new HashMap<>());
            if (!channels.containsKey(channel)) messenger.registerOutgoingPluginChannel(hostPlugin, channel);
            channels.merge(channel, 1, Integer::sum);
        }
        manage(() -> {
            synchronized (OUTGOING) {
                Map<String, Integer> channels = OUTGOING.get(hostPlugin);
                int count = channels.get(channel);
                if (count == 1) {
                    messenger.unregisterOutgoingPluginChannel(hostPlugin, channel);
                    channels.remove(channel);
                } else channels.put(channel, count - 1);
                if (channels.isEmpty()) OUTGOING.remove(hostPlugin);
            }
        });
    }
    public void registerCommand(String name, CommandExecutor executor, String description, String usage, String... aliases) {
        registerManagedCommand(name, executor, description, usage, aliases);
    }
    public AutoCloseable registerManagedCommand(String name, CommandExecutor executor, String description, String usage, String... aliases) {
        checkThread();
        RegisteredModuleCommand command = new RegisteredModuleCommand(name, executor, description, usage, aliases);
        CommandMap map = hostPlugin.getServer().getCommandMap();
        map.register(getModuleId(), command);
        class Registration implements AutoCloseable {
            boolean released;
            public void close() {
                if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Command cleanup requires the server thread");
                if (released) return;
                map.getKnownCommands().values().removeIf(value -> value == command);
                command.unregister(map);
                command.released = true;
                synchronized (ModuleContextImpl.this) { resources.remove(this); }
                released = true;
            }
        }
        return manage(new Registration());
    }
    private final class RegisteredModuleCommand extends Command implements org.wannes.plugModCore.api.ModuleCommandMetadata {
        private final CommandExecutor executor;
        private boolean released;
        RegisteredModuleCommand(String name, CommandExecutor executor, String description, String usage, String[] aliases) {
            super(name, description == null ? "" : description, usage == null ? "/" + name : usage, aliases == null ? List.of() : Arrays.asList(aliases));
            this.executor = Objects.requireNonNull(executor);
        }
        public String moduleId() { return getModuleId(); }
        public String moduleName() { return getModuleName(); }
        public boolean moduleEnabled() { return !closed && !released && module.getState() == ModuleState.ENABLED; }
            public boolean execute(CommandSender sender, String label, String[] args) {
                return !closed && !released && executor.onCommand(sender, this, label, args);
            }
            public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                if (closed || released) return List.of();
                if (executor instanceof TabCompleter completer) {
                    List<String> result = completer.onTabComplete(sender, this, alias, args);
                    return result == null ? List.of() : result;
                }
                return List.of();
            }
    }
    /** Continue independent cleanup and retain failed resources for a retry. */
    public synchronized void cleanup() throws Exception {
        closed = true;
        if (services != null) services.revoke(this);
        Exception failure = null;
        Deque<AutoCloseable> failed = new ArrayDeque<>();
        while (!resources.isEmpty()) {
            AutoCloseable resource = resources.pop();
            try { resource.close(); }
            catch (Exception | LinkageError e) {
                failed.addLast(resource);
                if (failure == null) failure = new Exception("Resource cleanup failed for " + getModuleId());
                failure.addSuppressed(e);
            }
        }
        resources.addAll(failed);
        if (running > 0) {
            if (failure == null) failure = new Exception("Managed work is still executing for " + getModuleId() + "; retry unloading after it finishes");
        }
        if (failure != null) throw failure;
    }
}
