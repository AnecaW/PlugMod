package org.wannes.plugModCore.module;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** One singleton per service interface; revocable handles bridge to Bukkit's public services API. */
public final class ModuleServiceRegistry {
    private final Map<Class<?>, Entry> entries = new ConcurrentHashMap<>();
    private final ModuleManager manager;
    ModuleServiceRegistry(ModuleManager manager) { this.manager = manager; }
    private static final class Entry implements InvocationHandler {
        final String provider;
        final AtomicReference<ModuleContextImpl> owner;
        final AtomicReference<Object> delegate;
        Object handle;
        Entry(ModuleContextImpl owner, Object delegate) { this.provider = owner.getModuleId(); this.owner = new AtomicReference<>(owner); this.delegate = new AtomicReference<>(delegate); }
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "toString" -> "Module service from " + provider;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException();
            };
            ModuleContextImpl context = owner.get();
            if (context == null) throw new IllegalStateException("Service provider " + provider + " is unavailable; obtain its new service after enabling/reload");
            return context.invokeService(() -> {
                Object target = delegate.get();
                if (target == null) throw new IllegalStateException("Service provider " + provider + " is unavailable");
                try { return method.invoke(target, args); }
                catch (InvocationTargetException e) { throw e.getCause(); }
            });
        }
        void revoke() { delegate.set(null); owner.set(null); }
    }
    public synchronized <T> void register(ModuleContextImpl owner, Class<T> type, T service) {
        if (!type.isInterface() || !Modifier.isPublic(type.getModifiers()) || !type.isInstance(service)) throw new IllegalArgumentException("Service must implement public interface " + type.getName());
        if (type.getClassLoader() instanceof ModuleClassLoader loader && !loader.exports(type.getName())) throw new IllegalArgumentException("Service API " + type.getName() + " must be in the shared core API or a declared exported package");
        if (entries.containsKey(type)) throw new IllegalStateException("Service " + type.getName() + " is already owned by module " + entries.get(type).provider);
        if (entries.keySet().stream().anyMatch(api -> api.getName().equals(type.getName()))) throw new IllegalStateException("Incompatible API definitions for " + type.getName() + "; use one authoritative exported interface");
        Entry entry = new Entry(owner, service);
        T handle = type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, entry));
        entry.handle = handle;
        entries.put(type, entry);
        try {
            owner.getHostPlugin().getServer().getServicesManager().register(type, handle, owner.getHostPlugin(), org.bukkit.plugin.ServicePriority.Normal);
            owner.manage(() -> { synchronized (this) {
                entry.revoke(); entries.remove(type, entry);
                owner.getHostPlugin().getServer().getServicesManager().unregister(type, handle);
            } });
        } catch (RuntimeException e) { entry.revoke(); entries.remove(type, entry); owner.getHostPlugin().getServer().getServicesManager().unregister(type, handle); throw e; }
    }
    public <T> T get(ModuleContextImpl consumer, Class<T> type) {
        return get(consumer,type,false);
    }
    public <T> T getOptional(ModuleContextImpl consumer, Class<T> type) {
        return get(consumer,type,true);
    }
    private <T> T get(ModuleContextImpl consumer, Class<T> type, boolean optional) {
        if (consumer.isClosed()) return null;
        Entry entry = entries.get(type);
        if (entry == null && entries.keySet().stream().anyMatch(api -> api.getName().equals(type.getName()))) throw new IllegalStateException("Duplicate API class identity for " + type.getName() + "; use the provider's exported API instead of bundling a copy");
        if (entry != null) {
            if (!optional && !consumer.getModuleId().equals(entry.provider) && !manager.dependsOn(consumer.getModuleId(), entry.provider)) throw new IllegalStateException("Module " + consumer.getModuleId() + " must declare dependency " + entry.provider + " to consume " + type.getName());
            ModuleContextImpl provider = entry.owner.get();
            if (provider == null || !provider.isServiceReady()) return null;
            return type.cast(entry.handle);
        }
        return consumer.getHostPlugin().getServer().getServicesManager().load(type);
    }
    public void require(ModuleContextImpl consumer, Class<?> type, String provider) {
        Entry entry = entries.get(type);
        if (entry == null || !entry.provider.equals(provider) || get(consumer, type) == null) throw new IllegalStateException("Module " + consumer.getModuleId() + " requires API " + type.getName() + " from provider " + provider + "; expected provider/service not enabled or registered");
    }
    public void revoke(ModuleContextImpl owner) {
        for (Entry entry : entries.values()) if (entry.owner.get() == owner) entry.revoke();
    }
}
