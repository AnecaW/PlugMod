package org.wannes.plugModCore.module;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.Plugin;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.wannes.plugModCore.config.CoreConfig;
import javax.tools.ToolProvider;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.*;
import java.util.logging.Logger;
import static org.junit.Assert.*;

/** Component tests with real compiled module JARs; the server is a small API test double. */
public class ModuleManagerTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static Server server;
    private static SimpleCommandMap commands;
    private static Thread primary;
    private static org.bukkit.plugin.PluginManager plugins;
    private static final List<Runnable> scheduled = new ArrayList<>();
    private static final List<java.util.concurrent.atomic.AtomicBoolean> cancelled = new ArrayList<>();
    private static org.bukkit.scheduler.BukkitScheduler scheduler;
    private static final org.bukkit.plugin.SimpleServicesManager services = new org.bukkit.plugin.SimpleServicesManager();
    private static final org.bukkit.event.HandlerList handlers = new org.bukkit.event.HandlerList();
    private ModuleManager manager;
    private File root;
    private Plugin host;
    @BeforeClass public static void server() throws Exception {
        primary = Thread.currentThread();
        plugins = (org.bukkit.plugin.PluginManager) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{org.bukkit.plugin.PluginManager.class}, (proxy, method, args) -> {
            if (method.getName().equals("registerEvents")) handlers.register(new org.bukkit.plugin.RegisteredListener((org.bukkit.event.Listener) args[0], (listener, event) -> {}, org.bukkit.event.EventPriority.NORMAL, (Plugin) args[1], false));
            return defaultValue(method.getReturnType());
        });
        scheduler = (org.bukkit.scheduler.BukkitScheduler) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{org.bukkit.scheduler.BukkitScheduler.class}, (proxy, method, args) -> {
            if (method.getName().startsWith("runTask")) {
                scheduled.add((Runnable) args[1]);
                var flag = new java.util.concurrent.atomic.AtomicBoolean(); cancelled.add(flag);
                return Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{org.bukkit.scheduler.BukkitTask.class}, (task, call, parameters) -> {
                    if (call.getName().equals("cancel")) { flag.set(true); return null; }
                    if (call.getName().equals("isCancelled")) return flag.get();
                    return defaultValue(call.getReturnType());
                });
            }
            return defaultValue(method.getReturnType());
        });
        server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class}, (proxy, method, args) -> switch (method.getName()) {
            case "isPrimaryThread" -> Thread.currentThread() == primary;
            case "getLogger" -> Logger.getLogger("test-server");
            case "getName" -> "test";
            case "getVersion" -> "test (MC: 1.21.11)";
            case "getBukkitVersion" -> "1.21.11-R0.1-SNAPSHOT";
            case "getCommandMap" -> commands;
            case "getPluginManager" -> plugins;
            case "getScheduler" -> scheduler;
            case "getServicesManager" -> services;
            default -> defaultValue(method.getReturnType());
        });
        commands = new SimpleCommandMap(server, new HashMap<>());
        // Paper's public setter requires a live ServerBuildInfo service. Install only
        // the test double here; no production code uses this reflection.
        java.lang.reflect.Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true); field.set(null, server);
    }
    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
    @Before public void setup() throws Exception {
        root = temp.newFolder();
        host = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getDataFolder" -> root;
            case "getLogger" -> Logger.getLogger("test-module");
            case "getName" -> "PlugModCore";
            case "getServer" -> server;
            case "isEnabled" -> true;
            default -> defaultValue(method.getReturnType());
        });
        CoreConfig cfg = new CoreConfig(); cfg.allowHotReload = true;
        manager = new ModuleManager(host, null, cfg);
        commands.getKnownCommands().clear();
        scheduled.clear(); cancelled.clear();
    }
    @After public void shutdown() throws Exception {
        manager.shutdown();
        // An intentionally permanent failed-disable fixture retains its loader in
        // production; release that test-only snapshot after its assertions.
        for (ModuleContainer m : manager.getModules()) if (m.getClassLoader() != null) m.getClassLoader().close();
    }
    private File jar(String id, String dependencies, String enable, String disable, String load) throws Exception {
        Path dir = temp.newFolder().toPath();
        Path source = dir.resolve("Entry.java");
        Files.writeString(source, "package fixture; public class Entry implements org.wannes.plugModCore.api.Module { public void onLoad(org.wannes.plugModCore.api.ModuleContext c){" + load + "} public void onEnable(org.wannes.plugModCore.api.ModuleContext c){" + enable + "} public void onDisable(){" + disable + "} }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-classpath", System.getProperty("java.class.path"), "-d", dir.toString(), source.toString()));
        File output = new File(root, "modules/" + id + ".jar");
        try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(output)); var files = Files.walk(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                jar.putNextEntry(new JarEntry(dir.relativize(file).toString().replace('\\', '/')));
                jar.write(Files.readAllBytes(file)); jar.closeEntry();
            }
            jar.putNextEntry(new JarEntry("module.info.yml"));
            jar.write(("id: " + id + "\nname: " + id + "\nversion: 1.0\nmain: fixture.Entry\n" + dependencies).getBytes(StandardCharsets.UTF_8)); jar.closeEntry();
        }
        return output;
    }
    private ModuleContainer discover(String id) { manager.scanModules(); return manager.findModule(id); }
    @Test public void missingSharedApiFailsBeforeInitializationAndRetainsRecoveryState() throws Exception {
        jar("missingapi", "required-api-classes: [org.wannes.plugModCore.api.UnavailableFixture]\n", "", "",
                "throw new AssertionError(\"must not initialize\");");
        var module = discover("missingapi");
        try { manager.loadModule(module); fail("Missing API should fail"); }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("missingapi"));
            assertTrue(expected.getMessage().contains("org.wannes.plugModCore.api.UnavailableFixture"));
            assertTrue(expected.getMessage().contains("fully restart Paper"));
        }
        assertEquals(ModuleState.FAILED, module.getState());
        assertNull(module.getModuleInstance());
        assertNull(module.getClassLoader());
    }
    @Test public void panelContractIsAuthoritativeAndMissingParentCannotFallBackToModule() throws Exception {
        File file = jar("panelapi", "required-api-classes: [org.wannes.plugModCore.api.PanelService]\n", "", "", "");
        var module = discover("panelapi"); manager.loadModule(module);
        assertSame(org.wannes.plugModCore.api.PanelService.class,
                module.getClassLoader().loadClass("org.wannes.plugModCore.api.PanelService"));
        ClassLoader oldCore = new ClassLoader(getClass().getClassLoader()) {
            @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.equals("org.wannes.plugModCore.api.PanelService")) throw new ClassNotFoundException(name);
                return super.loadClass(name);
            }
        };
        try { ModuleManager.validateSharedApi(module.getInfo(), oldCore); fail("Old core accepted"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("PanelService")); }
        File duplicate = temp.newFile("duplicate-api.jar");
        String contract = "org/wannes/plugModCore/api/PanelService.class";
        try (var source = new JarFile(file); var output = new JarOutputStream(new FileOutputStream(duplicate))) {
            for (var entry : Collections.list(source.entries())) {
                output.putNextEntry(new JarEntry(entry.getName()));
                try (var input = source.getInputStream(entry)) { input.transferTo(output); }
                output.closeEntry();
            }
            output.putNextEntry(new JarEntry(contract));
            try (var input = getClass().getClassLoader().getResourceAsStream(contract)) { assertNotNull(input); input.transferTo(output); }
            output.closeEntry();
        }
        try (var loader = new ModuleClassLoader(duplicate.toURI().toURL(), getClass().getClassLoader())) {
            assertSame(org.wannes.plugModCore.api.PanelService.class, loader.loadClass("org.wannes.plugModCore.api.PanelService"));
        }
        try (var loader = new ModuleClassLoader(duplicate.toURI().toURL(), oldCore)) {
            try { loader.loadClass("org.wannes.plugModCore.api.PanelService"); fail("Duplicate fallback accepted"); }
            catch (ClassNotFoundException expected) { assertTrue(expected.getMessage().contains("PanelService")); }
        }
    }
    @Test public void dynamicCommandHandlesReleaseImmediatelyAndAreIdempotent() throws Exception {
        jar("dynamic","","","",""); var module=discover("dynamic"); manager.enableModule(module);
        var handle=module.getContext().registerManagedCommand("dynamicpanel",(sender,command,label,args)->{throw new AssertionError("Released command invoked");},"Panel","/dynamicpanel");
        var command=commands.getCommand("dynamicpanel"); assertNotNull(command);
        handle.close(); handle.close(); assertNull(commands.getCommand("dynamicpanel")); assertNull(commands.getCommand("dynamic:dynamicpanel"));
        assertFalse(command.execute(null,"dynamicpanel",new String[0]));
        var second=module.getContext().registerManagedCommand("dynamicpanel",(sender,c,label,args)->true,"Panel","/dynamicpanel");
        assertNotNull(commands.getCommand("dynamicpanel")); manager.disableModule(module); assertNull(commands.getCommand("dynamicpanel")); second.close();
    }
    @Test public void optionalSharedServiceHandlesProviderAbsenceAndReloadWithoutHardDependency() throws Exception {
        placeholderProvider("first"); jar("optional","","","","");
        var consumer=discover("optional"); var provider=manager.findModule("provider"); manager.enableModule(consumer);
        assertNull(consumer.getContext().getOptionalService(org.wannes.plugModCore.api.PlaceholderService.class)); manager.enableModule(provider);
        assertThrows(IllegalStateException.class,()->consumer.getContext().getService(org.wannes.plugModCore.api.PlaceholderService.class));
        var old=consumer.getContext().getOptionalService(org.wannes.plugModCore.api.PlaceholderService.class); assertEquals("first",old.parse(null,""));
        manager.disableModule(provider); assertNull(consumer.getContext().getOptionalService(org.wannes.plugModCore.api.PlaceholderService.class)); assertThrows(IllegalStateException.class,()->old.parse(null,""));
        placeholderProvider("second"); manager.reloadModule(provider); manager.enableModule(provider);
        assertEquals("second",consumer.getContext().getOptionalService(org.wannes.plugModCore.api.PlaceholderService.class).parse(null,""));
    }
    @Test public void repeatedReloadReplacesClassesAndCleansCommands() throws Exception {
        jar("example", "", "c.registerCommand(\"fixturehello\", (s,cmd,l,a)->true, \"\", \"\");", "", "");
        ModuleContainer m = discover("example");
        manager.loadModule(m); manager.enableModule(m);
        for (int i = 0; i < 4; i++) {
            Object previous = m.getModuleInstance(); Class<?> previousClass = previous.getClass();
            manager.reloadModule(m);
            assertNotSame(previous, m.getModuleInstance()); assertNotSame(previousClass, m.getModuleInstance().getClass());
            assertEquals(ModuleState.ENABLED, m.getState());
            assertEquals(1, commands.getKnownCommands().values().stream().distinct().count());
        }
        manager.disableModule(m); assertTrue(commands.getKnownCommands().isEmpty());
        manager.enableModule(m); assertNotNull(commands.getCommand("fixturehello"));
        manager.unloadModule(m); assertNull(m.getModuleInstance()); assertNull(m.getClassLoader()); assertNull(m.getContext()); assertTrue(commands.getKnownCommands().isEmpty());
    }
    @Test public void rescanPreservesLiveInstance() throws Exception {
        jar("a", "", "", "", ""); ModuleContainer a = discover("a"); manager.enableModule(a);
        Object instance = a.getModuleInstance(); jar("b", "", "", "", ""); manager.scanModules();
        assertSame(a, manager.findModule("a")); assertSame(instance, a.getModuleInstance()); assertEquals(2, manager.getModules().size());
    }
    @Test public void failedReplacementRestoresOldArtifact() throws Exception {
        jar("a", "", "c.registerCommand(\"restored\",(s,cmd,l,args)->true,\"\",\"\");", "", "");
        ModuleContainer a = discover("a"); manager.enableModule(a);
        Object old = a.getModuleInstance();
        jar("a", "", "", "", "throw new IllegalStateException(\"replacement broken\");");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> manager.reloadModule(a));
        assertTrue(failure.getMessage().contains("previous artifact restored"));
        assertEquals(ModuleState.ENABLED, a.getState()); assertNotSame(old, a.getModuleInstance()); assertNotNull(commands.getCommand("restored"));
        assertEquals(1, commands.getKnownCommands().values().stream().distinct().count());
    }
    @Test public void dependenciesLoadAndEnableFirstAndBlockRemoval() throws Exception {
        jar("a", "dependencies: [b]\n", "", "", ""); jar("b", "dependencies: [c]\n", "", "", ""); jar("c", "", "", "", "");
        ModuleContainer a = discover("a"); manager.enableModule(a);
        assertEquals(ModuleState.ENABLED, manager.findModule("c").getState());
        assertThrows(IllegalStateException.class, () -> manager.unloadModule(manager.findModule("c")));
        assertThrows(IllegalStateException.class, () -> manager.disableModule(manager.findModule("b")));
        manager.unloadModule(a); manager.unloadModule(manager.findModule("b")); manager.unloadModule(manager.findModule("c"));
    }
    @Test public void missingAndCircularDependenciesLeaveNoInstances() throws Exception {
        jar("missing", "dependencies: [absent]\n", "", "", ""); jar("a", "dependencies: [b]\n", "", "", ""); jar("b", "dependencies: [a]\n", "", "", "");
        ModuleContainer m = discover("missing");
        assertThrows(IllegalStateException.class, () -> manager.loadModule(m));
        assertThrows(IllegalStateException.class, () -> manager.loadModule(manager.findModule("a")));
        for (ModuleContainer entry : manager.getModules()) assertNull(entry.getModuleInstance());
    }
    @Test public void initializationAndEnableFailuresCleanCommands() throws Exception {
        jar("loadfail", "", "", "", "c.registerCommand(\"badload\",(s,cmd,l,a)->true,\"\",\"\"); throw new IllegalStateException(\"init\");");
        jar("enablefail", "", "c.registerCommand(\"badenable\",(s,cmd,l,a)->true,\"\",\"\"); throw new IllegalStateException(\"enable\");", "", "");
        ModuleContainer m = discover("loadfail"); assertThrows(IllegalStateException.class, () -> manager.loadModule(m));
        assertEquals(ModuleState.FAILED, m.getState()); assertNull(m.getClassLoader()); assertTrue(commands.getKnownCommands().isEmpty());
        ModuleContainer e = manager.findModule("enablefail"); assertThrows(IllegalStateException.class, () -> manager.enableModule(e));
        assertEquals(ModuleState.FAILED, e.getState()); assertTrue(commands.getKnownCommands().isEmpty()); manager.unloadModule(e);
    }
    @Test public void failedDisableStillCleansAndReportsFailure() throws Exception {
        jar("bad", "", "c.registerCommand(\"bad\",(s,cmd,l,a)->true,\"\",\"\");", "throw new IllegalStateException(\"disable\");", "");
        ModuleContainer m = discover("bad"); manager.enableModule(m);
        assertThrows(IllegalStateException.class, () -> manager.disableModule(m)); assertEquals(ModuleState.FAILED, m.getState()); assertTrue(commands.getKnownCommands().isEmpty());
        assertNotNull(m.getModuleInstance());
    }
    @Test public void invalidArtifactsAreIsolated() throws Exception {
        Files.writeString(new File(root, "modules/broken.jar").toPath(), "not a jar");
        try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(new File(root, "modules/invalid.jar")))) {
            jar.putNextEntry(new JarEntry("module.info.yml")); jar.write("id: ../bad\nmain: fixture.Entry\nversion: 1.0\n".getBytes(StandardCharsets.UTF_8)); jar.closeEntry();
        }
        manager.scanModules();
        assertEquals(2, manager.getModules().size()); for (ModuleContainer m : manager.getModules()) assertEquals(ModuleState.FAILED, m.getState());
    }
    @Test public void cleanupContinuesAndRetriesFailedResource() throws Exception {
        jar("a", "", "", "", ""); ModuleContainer a = discover("a"); manager.loadModule(a);
        AtomicInteger cleaned = new AtomicInteger(); AtomicInteger tries = new AtomicInteger();
        a.getContext().manage(() -> cleaned.incrementAndGet());
        a.getContext().manage(() -> { if (tries.getAndIncrement() == 0) throw new IOException("retry"); });
        assertThrows(IllegalStateException.class, () -> manager.unloadModule(a)); assertEquals(1, cleaned.get()); assertNotNull(a.getClassLoader());
        manager.unloadModule(a); assertNull(a.getClassLoader()); assertEquals(2, tries.get());
    }
    @Test public void deletePreservesData() throws Exception {
        jar("a", "", "", "", ""); ModuleContainer a = discover("a"); manager.loadModule(a);
        Path data = a.getContext().getDataFolder().toPath().resolve("config.yml"); Files.writeString(data, "setting: keep");
        manager.deleteModule(a); assertEquals("setting: keep", Files.readString(data)); assertFalse(a.getFile().exists());
    }
    @Test public void listenerAndServiceCleanupDoesNotRemoveAnotherModulesResources() throws Exception {
        jar("a", "", "", "", ""); jar("b", "", "", "", ""); ModuleContainer a = discover("a"); ModuleContainer b = manager.findModule("b");
        manager.loadModule(a); manager.loadModule(b);
        a.getContext().registerListener(new org.bukkit.event.Listener() {});
        b.getContext().registerListener(new org.bukkit.event.Listener() {});
        Runnable first = () -> {}; java.util.function.Supplier<String> second = () -> "second";
        a.getContext().registerService(Runnable.class, first); b.getContext().registerService(java.util.function.Supplier.class, second);
        assertEquals(2, handlers.getRegisteredListeners().length); assertEquals(1, services.getRegistrations(Runnable.class).size());
        manager.unloadModule(a);
        assertEquals(1, handlers.getRegisteredListeners().length); assertNull(services.load(Runnable.class)); assertNotNull(services.load(java.util.function.Supplier.class));
        manager.unloadModule(b); assertEquals(0, handlers.getRegisteredListeners().length); assertNull(services.load(java.util.function.Supplier.class));
    }
    @Test public void managedTasksCancelAndQueuedWorkCannotRunAfterUnload() throws Exception {
        jar("a", "", "", "", ""); ModuleContainer a = discover("a"); manager.loadModule(a);
        AtomicInteger runs = new AtomicInteger();
        a.getContext().schedule(runs::incrementAndGet, 1, 20, false);
        a.getContext().schedule(runs::incrementAndGet, 1, 0, true);
        scheduled.get(0).run(); assertEquals(1, runs.get());
        manager.unloadModule(a);
        for (var flag : cancelled) assertTrue(flag.get());
        for (Runnable action : scheduled) action.run(); assertEquals(1, runs.get());
    }
    @Test public void stagedLoadRunsCallbackOnMainThreadAndRejectsConcurrentOperation() throws Exception {
        jar("a", "", "", "", "if (!org.bukkit.Bukkit.isPrimaryThread()) throw new IllegalStateException(\"wrong thread\");");
        ModuleContainer a = discover("a");
        var future = manager.executeAsync("enable", a);
        assertFalse(future.isDone());
        assertThrows(IllegalStateException.class, () -> manager.loadModule(a));
        java.util.concurrent.CompletableFuture.runAsync(scheduled.get(0)).get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(future.isDone()); assertNull(a.getModuleInstance());
        scheduled.get(1).run();
        assertEquals(ModuleState.ENABLED, future.get());
    }
    @Test public void startupRestoresActualInstancesAndShutdownPreservesDesiredState() throws Exception {
        jar("a", "", "", "", "");
        var registry = new org.wannes.plugModCore.security.RegistryManager(host);
        registry.setModuleState("a", "ENABLED");
        CoreConfig cfg = new CoreConfig(); cfg.allowHotReload = true;
        manager = new ModuleManager(host, registry, cfg);
        ModuleContainer a = discover("a");
        assertEquals(ModuleState.UPLOADED, a.getState()); assertNull(a.getModuleInstance());
        manager.restoreStartupAsync(false);
        java.util.concurrent.CompletableFuture.runAsync(scheduled.get(0)).get(5, java.util.concurrent.TimeUnit.SECONDS);
        scheduled.get(1).run();
        assertEquals(ModuleState.ENABLED, a.getState()); assertNotNull(a.getModuleInstance());
        manager.shutdown(); assertNull(a.getModuleInstance());
        assertEquals("ENABLED", new org.wannes.plugModCore.security.RegistryManager(host).getModuleState("a"));
    }
    @Test public void shutdownRejectsPendingStagedLoad() throws Exception {
        jar("a", "", "", "", ""); ModuleContainer a = discover("a");
        var future = manager.executeAsync("load", a);
        java.util.concurrent.CompletableFuture.runAsync(scheduled.get(0)).get(5, java.util.concurrent.TimeUnit.SECONDS);
        manager.shutdown(); scheduled.get(1).run();
        assertTrue(future.isCompletedExceptionally()); assertNull(a.getClassLoader()); assertNull(a.getModuleInstance());
    }
    @Test public void runningManagedWorkPreventsLoaderReleaseUntilFinished() throws Exception {
        jar("a", "", "", "", ""); ModuleContainer a = discover("a"); manager.loadModule(a);
        var started = new java.util.concurrent.CountDownLatch(1); var finish = new java.util.concurrent.CountDownLatch(1);
        a.getContext().schedule(() -> { started.countDown(); try { finish.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }, 0, 0, true);
        Thread worker = new Thread(scheduled.get(0)); worker.start();
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
        try { assertThrows(IllegalStateException.class, () -> manager.unloadModule(a)); assertEquals(ModuleState.FAILED, a.getState()); assertNotNull(a.getClassLoader()); }
        finally { finish.countDown(); worker.join(5000); }
        manager.unloadModule(a); assertNull(a.getClassLoader());
    }
    @Test public void ownedServicesRequireDependenciesRejectDuplicatesAndRevokeOldHandles() throws Exception {
        jar("provider", "", "", "", ""); jar("consumer", "dependencies: [provider]\n", "", "", ""); jar("unrelated", "", "", "", "");
        ModuleContainer provider = discover("provider"), consumer = manager.findModule("consumer"), unrelated = manager.findModule("unrelated");
        manager.enableModule(consumer); manager.enableModule(unrelated);
        AtomicInteger calls = new AtomicInteger(); provider.getContext().registerService(Runnable.class, calls::incrementAndGet);
        Runnable handle = consumer.getContext().getService(Runnable.class); handle.run(); assertEquals(1,calls.get());
        assertThrows(IllegalStateException.class, () -> consumer.getContext().requireService(Runnable.class,"unrelated"));
        assertThrows(IllegalStateException.class, () -> unrelated.getContext().getService(Runnable.class));
        assertThrows(IllegalStateException.class, () -> consumer.getContext().registerService(Runnable.class, () -> {}));
        manager.disableModule(consumer); manager.disableModule(provider);
        assertNull(services.load(Runnable.class)); assertThrows(IllegalStateException.class, handle::run);
    }
    private File placeholderProvider(String value) throws Exception {
        return jar("provider", "", "c.registerService(org.wannes.plugModCore.api.PlaceholderService.class,new org.wannes.plugModCore.api.PlaceholderService(){ public String parse(org.bukkit.OfflinePlayer p,String s){return \""+value+"\";} public String parseBrackets(org.bukkit.OfflinePlayer p,String s){return parse(p,s);} public java.util.Set<String> identifiers(){return java.util.Set.of();}});", "", "");
    }
    @Test public void activeServiceCallPreventsProviderShutdownBeforeItsCallback() throws Exception {
        jar("provider","","","",""); var provider=discover("provider");manager.enableModule(provider);
        var started=new java.util.concurrent.CountDownLatch(1);var finish=new java.util.concurrent.CountDownLatch(1);
        provider.getContext().registerService(Runnable.class,()->{started.countDown();try{finish.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        Runnable service=provider.getContext().getService(Runnable.class);Thread worker=new Thread(service);worker.start();
        try {assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));assertThrows(IllegalStateException.class,()->manager.disableModule(provider));assertEquals(ModuleState.ENABLED,provider.getState());}
        finally {finish.countDown();worker.join(5000);}
        manager.disableModule(provider);assertThrows(IllegalStateException.class,service::run);
    }
    @Test public void providerReloadRecreatesTransitiveConsumersAndRebindsServices() throws Exception {
        placeholderProvider("v1"); jar("middle", "dependencies: [provider]\n", "", "", "");
        jar("consumer", "dependencies: [middle]\nrequired-services:\n  org.wannes.plugModCore.api.PlaceholderService: provider\n", "c.requireService(org.wannes.plugModCore.api.PlaceholderService.class,\"provider\").parse(null,\"text\");", "", "");
        ModuleContainer consumer = discover("consumer"), provider = manager.findModule("provider"), middle=manager.findModule("middle");
        manager.enableModule(consumer);
        var old = consumer.getContext().getService(org.wannes.plugModCore.api.PlaceholderService.class);
        assertEquals("v1",old.parse(null,"")); var loader=consumer.getClassLoader(); var middleLoader=middle.getClassLoader();
        placeholderProvider("v2"); manager.reloadModule(provider);
        assertEquals(ModuleState.ENABLED,consumer.getState()); assertNotSame(loader,consumer.getClassLoader()); assertNotSame(middleLoader,middle.getClassLoader());
        assertEquals("v2",consumer.getContext().getService(org.wannes.plugModCore.api.PlaceholderService.class).parse(null,""));
        assertThrows(IllegalStateException.class,()->old.parse(null,""));
        for(int i=0;i<3;i++) manager.reloadModule(provider);
        assertEquals(1,services.getRegistrations(org.wannes.plugModCore.api.PlaceholderService.class).size());
        assertSame(org.wannes.plugModCore.api.PlaceholderService.class,consumer.getClassLoader().loadClass(org.wannes.plugModCore.api.PlaceholderService.class.getName()));
    }
    @Test public void missingRequiredServiceFailsBeforeDependentEnable() throws Exception {
        jar("provider","","","",""); jar("consumer","dependencies: [provider]\nrequired-services:\n  org.wannes.plugModCore.api.PlaceholderService: provider\n","throw new AssertionError(\"must not enable\");","","");
        var consumer=discover("consumer"); var error=assertThrows(IllegalStateException.class,()->manager.enableModule(consumer));
        assertTrue(error.getCause().getMessage().contains("requires API")); assertEquals(ModuleState.FAILED,consumer.getState());
    }
    private File sourceJar(String id, String metadata, Map<String,String> sources) throws Exception {
        Path dir=temp.newFolder().toPath(); List<String> arguments=new ArrayList<>(List.of("--release","21","-classpath",System.getProperty("java.class.path"),"-d",dir.toString()));
        for(var source:sources.entrySet()){Path file=dir.resolve(source.getKey());Files.createDirectories(file.getParent());Files.writeString(file,source.getValue());arguments.add(file.toString());}
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,arguments.toArray(String[]::new)));
        File output=new File(root,"modules/"+id+".jar");
        try(JarOutputStream jar=new JarOutputStream(new FileOutputStream(output));var files=Files.walk(dir)){
            for(Path file:files.filter(p->p.toString().endsWith(".class")).toList()){jar.putNextEntry(new JarEntry(dir.relativize(file).toString().replace('\\','/')));jar.write(Files.readAllBytes(file));jar.closeEntry();}
            jar.putNextEntry(new JarEntry("module.info.yml"));jar.write(("id: "+id+"\nname: "+id+"\nversion: 1\nmain: fixture.Entry\n"+metadata).getBytes(StandardCharsets.UTF_8));jar.closeEntry();
        }return output;
    }
    @Test public void exportedApiIdentityWinsOverBundledConsumerCopies() throws Exception {
        String api="package fixture.api; public interface Greeting { String hello(); }";
        sourceJar("provider","exports: [fixture.api]\n",Map.of("fixture/api/Greeting.java",api,"fixture/Entry.java","package fixture; public class Entry implements org.wannes.plugModCore.api.Module {public void onDisable(){} public void onEnable(org.wannes.plugModCore.api.ModuleContext c){c.registerService(fixture.api.Greeting.class,()->\"hello\");}}"));
        sourceJar("consumer","dependencies: [provider]\nrequired-services:\n  fixture.api.Greeting: provider\n",Map.of("fixture/api/Greeting.java",api,"fixture/Entry.java","package fixture; public class Entry implements org.wannes.plugModCore.api.Module {public void onDisable(){} public static String result; public void onEnable(org.wannes.plugModCore.api.ModuleContext c){result=c.getService(fixture.api.Greeting.class).hello();}}"));
        var consumer=discover("consumer"); manager.enableModule(consumer); var provider=manager.findModule("provider");
        assertSame(provider.getClassLoader().loadClass("fixture.api.Greeting"),consumer.getClassLoader().loadClass("fixture.api.Greeting"));
        assertEquals("hello",consumer.getModuleInstance().getClass().getField("result").get(null));
    }
    @Test public void lifecycleRejectsWorkerThread() throws Exception {
        jar("a", "", "", "", ""); ModuleContainer a = discover("a");
        var result = java.util.concurrent.CompletableFuture.runAsync(() -> assertThrows(IllegalStateException.class, () -> manager.loadModule(a)));
        result.get(5, java.util.concurrent.TimeUnit.SECONDS); assertNull(a.getModuleInstance());
    }
    @Test public void rowOperationsOnlyAffectSelectedModuleAndDeclaredDependencies() throws Exception {
        for(String id:List.of("placeholderapi","playerstats","announcements","tablist")) jar(id,"","","","");
        jar("scoreboard","dependencies: [placeholderapi]\n","","","");
        var scoreboard=discover("scoreboard");manager.loadModule(scoreboard);
        assertEquals(ModuleState.DISABLED,scoreboard.getState());assertEquals(ModuleState.DISABLED,manager.findModule("placeholderapi").getState());
        for(String id:List.of("playerstats","announcements","tablist"))assertNull(manager.findModule(id).getClassLoader());
        manager.loadModule(manager.findModule("tablist"));manager.loadModule(manager.findModule("announcements"));
        assertNull(manager.findModule("playerstats").getClassLoader());
        for(var module:manager.getModules())manager.enableModule(module);
        Map<String,ClassLoader> before=new HashMap<>();for(var module:manager.getModules())before.put(module.getInfo().id,module.getClassLoader());
        manager.unloadModule(manager.findModule("announcements"));
        assertNull(manager.findModule("announcements").getClassLoader());
        for(String id:List.of("scoreboard","placeholderapi","playerstats","tablist")){assertEquals(ModuleState.ENABLED,manager.findModule(id).getState());assertSame(before.get(id),manager.findModule(id).getClassLoader());}
        assertThrows(IllegalStateException.class,()->manager.unloadModule(manager.findModule("placeholderapi")));
        manager.scanModules();manager.unloadModule(manager.findModule("tablist"));
        assertSame(before.get("scoreboard"),scoreboard.getClassLoader());
        manager.reloadModule(manager.findModule("placeholderapi"));
        assertNotSame(before.get("scoreboard"),scoreboard.getClassLoader());
        assertSame(before.get("playerstats"),manager.findModule("playerstats").getClassLoader());
    }
    @Test public void registeredCommandsExposeSharedModuleOwnershipAndLifecycle() throws Exception {
        jar("owned", "", "c.registerCommand(\"ownedcommand\",(s,cmd,l,args)->true,\"\",\"\",\"ownedalias\");", "", "");
        manager.scanModules(); var module=manager.getModules().getFirst();manager.enableModule(module);
        var command=commands.getCommand("ownedcommand");
        assertTrue(command instanceof org.wannes.plugModCore.api.ModuleCommandMetadata);
        var metadata=(org.wannes.plugModCore.api.ModuleCommandMetadata)command;
        assertEquals("owned",metadata.moduleId());assertTrue(metadata.moduleEnabled());
        assertSame(command,commands.getCommand("ownedalias"));assertSame(command,commands.getCommand("owned:ownedalias"));
        manager.disableModule(module);assertFalse(metadata.moduleEnabled());assertNull(commands.getCommand("ownedalias"));
        manager.unloadModule(module);
    }

}

