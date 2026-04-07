package org.wannes.plugModCore.web;

import fi.iki.elonen.NanoHTTPD;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;
import org.wannes.plugModCore.PlugModCore;
import org.wannes.plugModCore.module.ModuleContainer;
import org.wannes.plugModCore.module.ModuleInfo;
import org.wannes.plugModCore.module.ModuleState;
import org.wannes.plugModCore.module.ModuleManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class WebServer extends NanoHTTPD {

    private final PlugModCore plugin;
    private final Map<String, BukkitTask> scoreboardReloadTasks = new HashMap<>();

    public WebServer(PlugModCore plugin, String hostname, int port) {
        super(hostname, port);
        this.plugin = plugin;
    }

    public void startServer() throws IOException {
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    @Override
    public Response serve(IHTTPSession session) {

        String uri = session.getUri();
        NanoHTTPD.Method method = session.getMethod();

        if (uri.equals("/") && method == NanoHTTPD.Method.GET) return showDashboard();
        if (uri.equals("/modules") && method == NanoHTTPD.Method.GET) return listModules();
        if (uri.equals("/api/modules") && method == NanoHTTPD.Method.GET) return listModulesJson();
        if (uri.equals("/modules/upload") && method == NanoHTTPD.Method.POST) return handleModuleUpload(session);

        if (uri.startsWith("/modules/load/") && method == NanoHTTPD.Method.POST) return loadModule(uri);
        if (uri.startsWith("/modules/unload/") && method == NanoHTTPD.Method.POST) return unloadModule(uri);
        if (uri.startsWith("/modules/enable/") && method == NanoHTTPD.Method.POST) return enableModule(uri);
        if (uri.startsWith("/modules/disable/") && method == NanoHTTPD.Method.POST) return disableModule(uri);
        if (uri.startsWith("/modules/delete/") && method == NanoHTTPD.Method.POST) return deleteModule(uri);

        if (uri.startsWith("/api/modules/") && uri.endsWith("/scoreboard/get") && method == NanoHTTPD.Method.GET) {
            return getScoreboardConfig(uri);
        }
        if (uri.startsWith("/api/modules/") && uri.endsWith("/scoreboard/set") && method == NanoHTTPD.Method.POST) {
            return setScoreboardConfig(session, uri);
        }

        // serve static web assets deployed to the plugin data folder (and fallback to classpath)
        if (uri.startsWith("/web/") && method == NanoHTTPD.Method.GET) return serveStatic(uri);
        if (uri.startsWith("/modules/website/") && method == NanoHTTPD.Method.GET) return serveModuleWebsite(uri);

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found");
    }

    /* =========================
       DASHBOARD
       ========================= */
    private Response showDashboard() {
        try (InputStream in = plugin.getResource("web/dashboard.html")) {
            if (in == null) {
                return newFixedLengthResponse(Response.Status.OK, "text/html", "<h1>PlugModCore Dashboard</h1><p>Resource missing</p>");
            }
            String s = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return newFixedLengthResponse(Response.Status.OK, "text/html", s);
        } catch (Exception e) {
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.OK, "text/html", "<h1>PlugModCore Dashboard</h1><p>Error</p>");
        }
    }

    /* =========================
       MODULE UPLOAD
       ========================= */
    @SuppressWarnings("deprecation")
    private Response handleModuleUpload(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);

            // determine temp uploaded file path
            String tempPath = null;
            if (files.containsKey("file")) tempPath = files.get("file");
            if (tempPath == null && !files.isEmpty()) tempPath = files.values().iterator().next();
            if (tempPath == null) return badRequest("Upload mislukt: geen bestand gevonden");

            File tempFile = new File(tempPath);
            if (!tempFile.exists()) return badRequest("Upload mislukt: tijdelijk bestand niet gevonden");

            // original filename from parms (multipart parser usually sets the field value to filename)
            String originalFileName = session.getParms().get("file");
            if (originalFileName == null || originalFileName.trim().isEmpty()) {
                // attempt to use temp file name as fallback
                originalFileName = tempFile.getName();
            }

            if (!originalFileName.toLowerCase().endsWith(".jar")) {
                return badRequest("Alleen .jar bestanden toegestaan");
            }

            File modulesDir = new File(plugin.getDataFolder(), "modules");
            modulesDir.mkdirs();

            String internalId;
            do {
                internalId = org.wannes.plugModCore.util.IdUtils.newInternalId(12);
            } while (plugin.getRegistryManager() != null && plugin.getRegistryManager().exists(internalId));

            // Make a final copy for use in lambdas / inner classes
            final String chosenInternalId = internalId;

            File target = new File(modulesDir, chosenInternalId + ".jar");
            Files.copy(tempFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);

            String sha256 = org.wannes.plugModCore.util.HashUtils.sha256(target);
            if (plugin.getRegistryManager() != null) {
                plugin.getRegistryManager().registerNew(chosenInternalId, originalFileName, sha256);
            }

            plugin.getModuleManager().scanModules();
            // Ensure upload only registers the file and marks it UPLOADED.
            // Do NOT attempt to load or enable the module here; loading should
            // be an explicit action by the user or controlled by the auto-load
            // configuration on startup.
            try {
                ModuleManager mgr = plugin.getModuleManager();
                ModuleContainer mc = mgr.getModules().stream()
                        .filter(x -> x.getInternalId().equals(chosenInternalId))
                        .findFirst().orElse(null);
                if (mc != null) {
                    mc.setState(ModuleState.UPLOADED);
                    if (plugin.getRegistryManager() != null) plugin.getRegistryManager().setModuleState(chosenInternalId, ModuleState.UPLOADED.name());
                }
            } catch (Exception ignored) {}
            plugin.getLogger().info("Module uploaded: " + chosenInternalId);

            String json = "{\"status\":\"ok\",\"id\":\"" + jsonEscape(chosenInternalId) + "\"}";
            return newFixedLengthResponse(Response.Status.OK, "application/json", json);

        } catch (Exception e) {
            e.printStackTrace();
            return badRequest("Upload mislukt: " + e.getMessage());
        }
    }

    /* =========================
       MODULE LIST (LIVE REFRESH)
       ========================= */
    private Response listModules() {
        // build only the module list fragment
        StringBuilder listHtml = new StringBuilder();
        listHtml.append("<h1>Modules</h1><ul>");

        for (ModuleContainer m : plugin.getModuleManager().getModules()) {

            listHtml.append("<li id='module-")
                    .append(m.getInternalId())
                    .append("' data-id='")
                    .append(m.getInternalId())
                    .append("' data-state='")
                    .append(m.getState())
                    .append("'>");

            listHtml.append(m.getInfo() != null && m.getInfo().name != null
                    ? m.getInfo().name
                    : m.getFileName());

            listHtml.append(" (").append(m.getState()).append(")");

            listHtml.append(" <small style='color:gray'>[")
                    .append(m.getInternalId())
                    .append("]</small> ");

            if (m.getState() == ModuleState.UPLOADED) {
                action(listHtml, "Load", "/modules/load/" + m.getInternalId());
                action(listHtml, "Delete", "/modules/delete/" + m.getInternalId());
            }

            if (m.getState() == ModuleState.DISABLED) {
                action(listHtml, "Enable", "/modules/enable/" + m.getInternalId());
                action(listHtml, "Unload", "/modules/unload/" + m.getInternalId());
            }

            if (m.getState() == ModuleState.ENABLED) {
                action(listHtml, "Disable", "/modules/disable/" + m.getInternalId());
            }

            if (m.getState() == ModuleState.FAILED) {
                action(listHtml, "Unload", "/modules/unload/" + m.getInternalId());
                action(listHtml, "Delete", "/modules/delete/" + m.getInternalId());
            }

            listHtml.append("</li>");
        }

        listHtml.append("</ul><p><a href='/'>Terug</a></p>");

        // load template from resources and replace placeholder (now located under web/)
        try (InputStream in = plugin.getResource("web/modules.html")) {
            if (in == null) {
                // fallback: return fragment directly
                return newFixedLengthResponse(Response.Status.OK, "text/html", listHtml.toString());
            }

            String template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String page = template.replace("{{MODULE_LIST}}", listHtml.toString());
            return newFixedLengthResponse(Response.Status.OK, "text/html", page);

        } catch (Exception e) {
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.OK, "text/html", listHtml.toString());
        }
    }

    /* =========================
       ACTIONS
       ========================= */
    private Response enableModule(String uri) {
        return doAction(uri, "/modules/enable/", m -> plugin.getModuleManager().enableModule(m));
    }

    private Response disableModule(String uri) {
        return doAction(uri, "/modules/disable/", m -> plugin.getModuleManager().disableModule(m));
    }

    private Response loadModule(String uri) {
        return doAction(uri, "/modules/load/", m -> plugin.getModuleManager().loadModule(m));
    }

    private Response unloadModule(String uri) {
        return doAction(uri, "/modules/unload/", m -> plugin.getModuleManager().unloadModule(m));
    }

    private Response deleteModule(String uri) {
        return doAction(uri, "/modules/delete/", m -> {
            plugin.getModuleManager().deleteModule(m);
            plugin.getModuleManager().scanModules();
        });
    }

    /* =========================
       HELPERS
       ========================= */
    private Response doAction(String uri, String prefix, java.util.function.Consumer<ModuleContainer> action) {
        String id = uri.replace(prefix, "");

        ModuleContainer module = plugin.getModuleManager()
                .getModules()
                .stream()
                .filter(m -> m.getInternalId().equals(id))
                .findFirst()
                .orElse(null);

        if (module == null) return badRequest("Module niet gevonden");

        action.accept(module);
        return redirect("/modules");
    }

    private void action(StringBuilder html, String label, String path) {
        // markeer formulieren zodat client-side JS ze kan onderscheppen
        html.append("<form method='post' action='")
                .append(path)
                .append("' class='module-action' style='display:inline'>")
                .append("<button>")
                .append(label)
                .append("</button></form> ");
    }

    private Response redirect(String path) {
        Response r = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "");
        r.addHeader("Location", path);
        return r;
    }

    private Response badRequest(String message) {
        return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", message);
    }

    private Response serveStatic(String uri) {
        try {
            String rel = uri.substring("/web/".length());

            // first try data folder (deployed assets)
            File f = new File(plugin.getDataFolder(), "web" + File.separator + rel);
            if (f.exists() && f.isFile()) {
                byte[] data = Files.readAllBytes(f.toPath());
                String ct = contentTypeByName(rel);
                String body = new String(data, StandardCharsets.UTF_8);
                return newFixedLengthResponse(Response.Status.OK, ct, body);
            }

            // fallback to packaged resource in classpath
            try (InputStream in = plugin.getResource("web/" + rel)) {
                if (in != null) {
                    String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    return newFixedLengthResponse(Response.Status.OK, contentTypeByName(rel), s);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "500: " + e.getMessage());
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found");
    }

    /**
     * Serve files directly from the module JAR. PlugModCore does not extract or modify module assets.
     * If path is empty, serve the module's configured website.entry.
     */
    private Response serveModuleWebsite(String uri) {
        try {
            // uri = /modules/website/<id>[/<path>]
            String rest = uri.substring("/modules/website/".length());
            if (rest.isEmpty()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found");

            String[] parts = rest.split("/", 2);
            String id = parts[0];
            String path = (parts.length > 1) ? parts[1] : "";

            ModuleContainer container = plugin.getModuleManager().getModules().stream()
                    .filter(m -> id.equals(m.getInternalId()))
                    .findFirst()
                    .orElse(null);

            if (container == null || container.getFile() == null || !container.getFile().exists()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found");
            }

            String entry = resolveModuleWebsiteEntry(container, path);
            if (entry == null || entry.isBlank()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found");
            }

            try (JarFile jar = new JarFile(container.getFile())) {
                JarEntry jarEntry = jar.getJarEntry(entry);
                if (jarEntry == null || jarEntry.isDirectory()) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found");
                }

                byte[] data;
                try (InputStream in = jar.getInputStream(jarEntry)) {
                    data = in.readAllBytes();
                }

                String ct = contentTypeByName(entry);
                String body = new String(data, StandardCharsets.UTF_8);
                return newFixedLengthResponse(Response.Status.OK, ct, body);
            }

        } catch (Exception e) {
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "500: " + e.getMessage());
        }
    }

    private String resolveModuleWebsiteEntry(ModuleContainer container, String path) {
        String websiteEntry = container.getInfo() != null ? container.getInfo().websiteEntry : null;
        if (websiteEntry == null || websiteEntry.isBlank()) {
            websiteEntry = "web/index.html";
        }

        if (path == null || path.isBlank() || path.endsWith("/")) {
            return websiteEntry;
        }

        // First try exact request path, then fall back to being relative to the configured website entry folder.
        if (path.equals(websiteEntry)) {
            return path;
        }

        String websiteBase = websiteEntry.contains("/") ? websiteEntry.substring(0, websiteEntry.lastIndexOf('/') + 1) : "";
        return path.startsWith("/") ? path.substring(1) : websiteBase + path;
    }

    private String contentTypeByName(String name) {
        name = name.toLowerCase();
        if (name.endsWith(".js")) return "application/javascript";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".woff") || name.endsWith(".woff2")) return "font/woff";
        return "text/plain";
    }

    private Response listModulesJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");

        boolean first = true;
        for (ModuleContainer m : plugin.getModuleManager().getModules()) {
            if (!first) sb.append(",");
            first = false;

            ModuleInfo info = m.getInfo();
            sb.append("{");
            sb.append("\"internalId\":\"").append(jsonEscape(m.getInternalId())).append("\"");
            sb.append(",\"fileName\":\"").append(jsonEscape(m.getFileName())).append("\"");
            sb.append(",\"state\":\"").append(jsonEscape(String.valueOf(m.getState()))).append("\"");

            if (info != null) {
                sb.append(",\"name\":\"").append(jsonEscape(info.name)).append("\"");
                sb.append(",\"description\":\"").append(jsonEscape(info.description)).append("\"");
                sb.append(",\"websiteEnabled\":").append(info.websiteEnabled);
                sb.append(",\"websiteEntry\":\"").append(jsonEscape(info.websiteEntry)).append("\"");
                sb.append(",\"websiteTitle\":\"").append(jsonEscape(info.websiteTitle)).append("\"");
                sb.append(",\"valid\":").append(info.valid);
                sb.append(",\"error\":\"").append(jsonEscape(info.error)).append("\"");
            } else {
                sb.append(",\"name\":\"\"");
                sb.append(",\"description\":\"\"");
                sb.append(",\"websiteEnabled\":false");
                sb.append(",\"websiteEntry\":\"\"");
                sb.append(",\"websiteTitle\":\"\"");
                sb.append(",\"valid\":false");
                sb.append(",\"error\":\"\"");
            }

            sb.append("}");
        }

        sb.append("]");

        return newFixedLengthResponse(Response.Status.OK, "application/json", sb.toString());
    }

    private String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private Response getScoreboardConfig(String uri) {
        String id = extractModuleIdForScoreboard(uri);
        if (id == null || id.isBlank()) return badRequest("Ongeldige module id");

        File configFile = new File(plugin.getDataFolder(), "module-data" + File.separator + id + File.separator + "scoreboard.yml");
        if (!configFile.exists()) {
            String json = "{\"mode\":\"manual\",\"rotationSeconds\":10,\"activePageId\":\"page-1\",\"pages\":[{\"id\":\"page-1\",\"name\":\"Pagina 1\",\"enabled\":true,\"title\":\"§aServerManager\",\"lines\":[\"§7Welkom!\",\"§fOnline: §a%online%\",\"§fHave fun!\"]}]}";
            return newFixedLengthResponse(Response.Status.OK, "application/json", json);
        }

        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(configFile);
            String mode = cfg.getString("mode", "manual");
            int rotationSeconds = cfg.getInt("rotation-seconds", 10);
            String activePageId = cfg.getString("active-page", "page-1");

            List<Map<?, ?>> pageMaps = cfg.getMapList("pages");
            if (pageMaps == null || pageMaps.isEmpty()) {
                Map<String, Object> single = new HashMap<>();
                single.put("id", "page-1");
                single.put("name", "Pagina 1");
                single.put("enabled", true);
                single.put("title", cfg.getString("title", "§aServerManager"));
                single.put("lines", cfg.getStringList("lines"));
                pageMaps = new ArrayList<>();
                pageMaps.add(single);
                activePageId = "page-1";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\"mode\":\"").append(jsonEscape(mode)).append("\"");
            sb.append(",\"rotationSeconds\":").append(rotationSeconds);
            sb.append(",\"activePageId\":\"").append(jsonEscape(activePageId)).append("\"");
            sb.append(",\"pages\":[");

            for (int i = 0; i < pageMaps.size(); i++) {
                if (i > 0) sb.append(",");
                Map<?, ?> p = pageMaps.get(i);

                String pageId = String.valueOf(p.getOrDefault("id", "page-" + (i + 1)));
                String pageName = String.valueOf(p.getOrDefault("name", "Pagina " + (i + 1)));
                boolean pageEnabled = Boolean.parseBoolean(String.valueOf(p.getOrDefault("enabled", true)));
                String pageTitle = String.valueOf(p.getOrDefault("title", "§aServerManager"));

                List<String> pageLines = new ArrayList<>();
                Object rawLines = p.get("lines");
                if (rawLines instanceof List<?> listObj) {
                    for (Object lineObj : listObj) {
                        pageLines.add(String.valueOf(lineObj));
                    }
                }

                sb.append("{\"id\":\"").append(jsonEscape(pageId)).append("\"");
                sb.append(",\"name\":\"").append(jsonEscape(pageName)).append("\"");
                sb.append(",\"enabled\":").append(pageEnabled);
                sb.append(",\"title\":\"").append(jsonEscape(pageTitle)).append("\"");
                sb.append(",\"lines\":[");
                for (int l = 0; l < pageLines.size(); l++) {
                    if (l > 0) sb.append(",");
                    sb.append("\"").append(jsonEscape(pageLines.get(l))).append("\"");
                }
                sb.append("]}");
            }

            sb.append("]}");

            return newFixedLengthResponse(Response.Status.OK, "application/json", sb.toString());
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Kon scoreboard config niet lezen: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private Response setScoreboardConfig(IHTTPSession session, String uri) {
        String id = extractModuleIdForScoreboard(uri);
        if (id == null || id.isBlank()) return badRequest("Ongeldige module id");

        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            Map<String, String> p = session.getParms();

            String mode = p.getOrDefault("mode", "manual");
            int rotationSeconds = Math.max(1, parseIntSafe(p.get("rotationSeconds"), 10));
            String activePageId = p.getOrDefault("activePageId", "");
            int pageCount = Math.max(0, parseIntSafe(p.get("pageCount"), 0));

            List<Map<String, Object>> pages = new ArrayList<>();
            for (int i = 0; i < pageCount; i++) {
                String prefix = "page." + i + ".";
                String pageId = p.getOrDefault(prefix + "id", "page-" + (i + 1));
                String pageName = p.getOrDefault(prefix + "name", "Pagina " + (i + 1));
                boolean pageEnabled = Boolean.parseBoolean(p.getOrDefault(prefix + "enabled", "true"));
                String pageTitle = p.getOrDefault(prefix + "title", "§aServerManager");
                String pageLinesText = p.getOrDefault(prefix + "lines", "");

                List<String> pageLines = new ArrayList<>();
                if (!pageLinesText.isBlank()) {
                    try (java.io.BufferedReader br = new java.io.BufferedReader(new StringReader(pageLinesText))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            pageLines.add(line);
                        }
                    }
                }

                Map<String, Object> page = new HashMap<>();
                page.put("id", pageId);
                page.put("name", pageName);
                page.put("enabled", pageEnabled);
                page.put("title", pageTitle);
                page.put("lines", pageLines);
                pages.add(page);
            }

            // Backward compatibility: old editor can still post title + lines only.
            if (pages.isEmpty()) {
                String title = p.getOrDefault("title", "§aServerManager");
                String linesText = p.getOrDefault("lines", "");

                List<String> lines = new ArrayList<>();
                if (!linesText.isBlank()) {
                    try (java.io.BufferedReader br = new java.io.BufferedReader(new StringReader(linesText))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            lines.add(line);
                        }
                    }
                }

                Map<String, Object> single = new HashMap<>();
                single.put("id", "page-1");
                single.put("name", "Pagina 1");
                single.put("enabled", true);
                single.put("title", title);
                single.put("lines", lines);
                pages.add(single);
            }

            if (activePageId.isBlank()) {
                activePageId = String.valueOf(pages.get(0).get("id"));
            }

            File moduleDir = new File(plugin.getDataFolder(), "module-data" + File.separator + id);
            moduleDir.mkdirs();
            File configFile = new File(moduleDir, "scoreboard.yml");

            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("mode", mode);
            cfg.set("rotation-seconds", rotationSeconds);
            cfg.set("active-page", activePageId);
            cfg.set("pages", pages);

            // Keep legacy keys in sync for compatibility with older module versions.
            Map<String, Object> activePage = pages.stream()
                    .filter(pg -> activePageId.equals(String.valueOf(pg.get("id"))))
                    .findFirst()
                    .orElse(pages.get(0));
            cfg.set("title", activePage.get("title"));
            cfg.set("lines", activePage.get("lines"));
            cfg.save(configFile);

            // Try hot-apply for running scoreboard module on the Bukkit main thread.
            // Debounce per module to keep live edits smooth and avoid unnecessary heavy updates.
            ModuleContainer module = plugin.getModuleManager().getModules().stream()
                    .filter(m -> id.equals(m.getInternalId()))
                    .findFirst()
                    .orElse(null);

            if (module != null && module.getModuleInstance() != null && module.getState() == ModuleState.ENABLED) {
                BukkitTask previous = scoreboardReloadTasks.remove(id);
                if (previous != null) {
                    previous.cancel();
                }

                BukkitTask scheduled = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    try {
                        java.lang.reflect.Method reload = module.getModuleInstance().getClass().getMethod("reloadFromConfig");
                        reload.invoke(module.getModuleInstance());
                    } catch (NoSuchMethodException ignored) {
                        // Optional hook. If not present, config is still persisted.
                    } catch (Exception e) {
                        plugin.getLogger().warning("Kon scoreboard niet live herladen: " + e.getMessage());
                    } finally {
                        scoreboardReloadTasks.remove(id);
                    }
                }, 3L);

                scoreboardReloadTasks.put(id, scheduled);
            }

            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}");
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Opslaan mislukt: " + e.getMessage());
        }
    }

    private String extractModuleIdForScoreboard(String uri) {
        // /api/modules/<id>/scoreboard/get|set
        String prefix = "/api/modules/";
        if (!uri.startsWith(prefix)) return null;
        String rest = uri.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) return null;
        return rest.substring(0, slash);
    }

    private int parseIntSafe(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
