package org.wannes.plugModCore.web;

import fi.iki.elonen.NanoHTTPD;
import org.wannes.plugModCore.PlugModCore;
import org.wannes.plugModCore.module.ModuleContainer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import org.wannes.plugModCore.module.ModuleState;


public class WebServer extends NanoHTTPD {

    private final PlugModCore plugin;

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
        Method method = session.getMethod();

        // Dashboard
        if (uri.equals("/") && method == Method.GET) {
            return showDashboard();
        }

        // Upload module
        if (uri.equals("/modules/upload") && method == Method.POST) {
            return handleModuleUpload(session);
        }

        // List modules
        if (uri.equals("/modules") && method == Method.GET) {
            return listModules();
        }

        if (uri.startsWith("/modules/load/") && method == Method.POST) {
            return loadModule(uri);
        }

        if (uri.startsWith("/modules/unload/") && method == Method.POST) {
            return unloadModule(uri);
        }

        if (uri.startsWith("/modules/delete/") && method == Method.POST) {
            return deleteModule(uri);
        }

        if (uri.startsWith("/modules/enable/") && method == Method.POST) {
            return enableModule(uri);
        }

        if (uri.startsWith("/modules/disable/") && method == Method.POST) {
            return disableModule(uri);
        }

        return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "404 Not Found"
        );
    }

    /* =========================
       DASHBOARD
       ========================= */
    private Response showDashboard() {
        StringBuilder sb = new StringBuilder();
        sb.append("<h1>PlugModCore Dashboard</h1>\n");
        sb.append("<form id=\"uploadForm\" method=\"post\" action=\"/modules/upload\" enctype=\"multipart/form-data\">\n");
        sb.append("  <input type=\"file\" name=\"file\" accept=\".jar\" required />\n");
        sb.append("  <button type=\"button\" id=\"uploadBtn\">Upload module</button>\n");
        sb.append("</form>\n");
        sb.append("<p><a href=\"/modules\">Bekijk modules</a></p>\n");
        sb.append("<script>\n");
        sb.append("(function(){\n");
        sb.append("  const form = document.getElementById('uploadForm');\n");
        sb.append("  const btn = document.getElementById('uploadBtn');\n");
        sb.append("  async function doUpload(){\n");
        sb.append("    const fd = new FormData(form);\n");
        sb.append("    try{\n");
        sb.append("      const res = await fetch('/modules/upload', { method: 'POST', body: fd });\n");
        sb.append("      let contentType = res.headers.get('content-type') || '';\n");
        sb.append("      if (contentType.includes('application/json')) {\n");
        sb.append("        const data = await res.json();\n");
        sb.append("        if (data.success) {\n");
        sb.append("          alert('Upload geslaagd! Internal ID: ' + data.internalId + '\\n' + (data.message || ''));\n");
        sb.append("          form.reset();\n");
        sb.append("        } else {\n");
        sb.append("          alert('Upload mislukt: ' + (data.message || 'Onbekende fout'));\n");
        sb.append("        }\n");
        sb.append("      } else {\n");
        sb.append("        const text = await res.text();\n");
        sb.append("        alert('Response: ' + text);\n");
        sb.append("      }\n");
        sb.append("    }catch(err){\n");
        sb.append("      alert('Upload mislukt: ' + err.message);\n");
        sb.append("    }\n");
        sb.append("  }\n");
        sb.append("  btn.addEventListener('click', function(e){ e.preventDefault(); doUpload(); });\n");
        sb.append("  form.addEventListener('submit', function(e){ e.preventDefault(); });\n");
        sb.append("})();\n");
        sb.append("</script>\n");

        return newFixedLengthResponse(
                Response.Status.OK,
                "text/html",
                sb.toString()
        );
    }

    /* =========================
       MODULE UPLOAD
       ========================= */
    @SuppressWarnings("deprecation")
    private Response handleModuleUpload(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);

            // Originele bestandsnaam (alleen informatief)
            String originalFileName = session.getParms().get("file");
            if (originalFileName == null) {
                return badRequest("Geen bestand ontvangen");
            }

            if (!originalFileName.toLowerCase().endsWith(".jar")) {
                return badRequest("Alleen .jar bestanden toegestaan");
            }

            // Temp file van NanoHTTPD
            String tempFilePath = files.get("file");
            if (tempFilePath == null) {
                return badRequest("Upload mislukt (temp file ontbreekt)");
            }

            File tempFile = new File(tempFilePath);

            // Modules map
            File modulesDir = new File(plugin.getDataFolder(), "modules");
            modulesDir.mkdirs();

        /* =========================
           1. GENEREER UNIEKE INTERNAL ID
           ========================= */
            String internalId;
            do {
                internalId = org.wannes.plugModCore.util.IdUtils.newInternalId(12);
            } while (plugin.getRegistryManager().exists(internalId));

        /* =========================
           2. SLA JAR OP ALS <internalId>.jar
           ========================= */
            File target = new File(modulesDir, internalId + ".jar");
            Files.copy(
                    tempFile.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );

        /* =========================
           3. HASH + REGISTRY ENTRY
           ========================= */
            String sha256 = org.wannes.plugModCore.util.HashUtils.sha256(target);
            plugin.getRegistryManager().registerNew(
                    internalId,
                    originalFileName,
                    sha256
            );

        /* =========================
           4. MODULES OPNIEUW SCANNEN
           ========================= */
            plugin.getModuleManager().scanModules();

                // Log to server console
                plugin.getLogger().info("Module uploaded: " + internalId + " (" + originalFileName + ")");

            String json = "{\"success\":true,\"internalId\":\"" + internalId + "\",\"message\":\"Module geüpload\"}";
            return newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    json
            );

        } catch (Exception e) {
            e.printStackTrace();
            String json = "{\"success\":false,\"message\":\"Upload mislukt: " + escapeJson(e.getMessage()) + "\"}";
            return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    json
            );
        }
    }

    /* =========================
       MODULE LIJST
       ========================= */
    private Response listModules() {
        StringBuilder html = new StringBuilder();
        html.append("<h1>Modules</h1><ul>");

        for (ModuleContainer module : plugin.getModuleManager().getModules()) {

                html.append("<li>")
                    // display module name (if available) or file name
                    .append(module.getInfo() != null && module.getInfo().name != null ? module.getInfo().name : module.getFileName())
                    .append(" (")
                    .append(module.getState())
                    .append(")");

                // INTERNAL ID
                html.append(" <small style='color:gray;'>[")
                    .append(module.getInternalId())
                    .append("]</small>");

                // Buttons per state
                if (module.getState() == ModuleState.UPLOADED) {
                // Load and Delete available
                html.append(" <form method='post' action='/modules/load/")
                    .append(module.getInternalId())
                    .append("' style='display:inline'>")
                    .append("<button>Load</button>")
                    .append("</form>");

                html.append(" <form method='post' action='/modules/delete/")
                    .append(module.getInternalId())
                    .append("' style='display:inline'>")
                    .append("<button>Delete</button>")
                    .append("</form>");
                }

                if (module.getState() == ModuleState.DISABLED) {
                html.append(" <form method='post' action='/modules/enable/")
                    .append(module.getInternalId())
                    .append("' style='display:inline'>")
                    .append("<button>Enable</button>")
                    .append("</form>");

                html.append(" <form method='post' action='/modules/unload/")
                    .append(module.getInternalId())
                    .append("' style='display:inline'>")
                    .append("<button>Unload</button>")
                    .append("</form>");
                }

                if (module.getState() == ModuleState.ENABLED) {
                html.append(" <form method='post' action='/modules/disable/")
                    .append(module.getInternalId())
                    .append("' style='display:inline'>")
                    .append("<button>Disable</button>")
                    .append("</form>");
                }

                if (module.getState() == ModuleState.FAILED) {
                html.append(" <form method='post' action='/modules/unload/")
                    .append(module.getInternalId())
                    .append("' style='display:inline'>")
                    .append("<button>Unload</button>")
                    .append("</form>");

                html.append(" <form method='post' action='/modules/delete/")
                    .append(module.getInternalId())
                    .append("' style='display:inline'>")
                    .append("<button>Delete</button>")
                    .append("</form>");
                }

                html.append("</li>");
        }

        html.append("</ul>");
        html.append("<p><a href='/'>Terug naar dashboard</a></p>");

        return newFixedLengthResponse(
                Response.Status.OK,
                "text/html",
                html.toString()
        );
    }

    /* =========================
       HELPERS
       ========================= */
    private Response badRequest(String message) {
        String json = "{\"success\":false,\"message\":\"" + escapeJson(message) + "\"}";
        return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                json
        );
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private Response enableModule(String uri) {
        String internalId = uri.replace("/modules/enable/", "");

        ModuleContainer module = plugin.getModuleManager()
            .getModules()
            .stream()
            .filter(m -> m.getInternalId().equals(internalId))
            .findFirst()
            .orElse(null);

        if (module == null) return badRequest("Module niet gevonden");

        plugin.getModuleManager().enableModule(module);
        return redirect("/modules");
    }

    private Response disableModule(String uri) {
        String internalId = uri.replace("/modules/disable/", "");

        ModuleContainer module = plugin.getModuleManager()
            .getModules()
            .stream()
            .filter(m -> m.getInternalId().equals(internalId))
            .findFirst()
            .orElse(null);

        if (module == null) return badRequest("Module niet gevonden");

        plugin.getModuleManager().disableModule(module);
        return redirect("/modules");
    }

    private Response redirect(String path) {
        Response r = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "");
        r.addHeader("Location", path);
        return r;
    }

    private Response loadModule(String uri) {
        String internalId = uri.replace("/modules/load/", "");

        ModuleContainer module = plugin.getModuleManager()
                .getModules()
                .stream()
                .filter(m -> m.getInternalId().equals(internalId))
                .findFirst()
                .orElse(null);

        if (module == null) return badRequest("Module niet gevonden");

        plugin.getLogger().info("Loading module: " + internalId);
        plugin.getModuleManager().loadModule(module);

        if (module.getState() == ModuleState.DISABLED) {
            plugin.getLogger().info("Module loaded (ready to enable): " + internalId);
        } else if (module.getState() == ModuleState.FAILED) {
            plugin.getLogger().severe("Module load failed: " + internalId + " -> " + module.getInfo().error);
        }

        return redirect("/modules");
    }

    private Response unloadModule(String uri) {
        String internalId = uri.replace("/modules/unload/", "");

        ModuleContainer module = plugin.getModuleManager()
                .getModules()
                .stream()
                .filter(m -> m.getInternalId().equals(internalId))
                .findFirst()
                .orElse(null);

        if (module == null) return badRequest("Module niet gevonden");

        plugin.getLogger().info("Unloading module: " + internalId);
        plugin.getModuleManager().unloadModule(module);

        if (module.getState() == ModuleState.UPLOADED) {
            plugin.getLogger().info("Module unloaded: " + internalId);
        } else if (module.getState() == ModuleState.FAILED) {
            plugin.getLogger().severe("Module unload encountered error: " + internalId);
        }

        return redirect("/modules");
    }

    private Response deleteModule(String uri) {
        String internalId = uri.replace("/modules/delete/", "");

        ModuleContainer module = plugin.getModuleManager()
                .getModules()
                .stream()
                .filter(m -> m.getInternalId().equals(internalId))
                .findFirst()
                .orElse(null);

        if (module == null) return badRequest("Module niet gevonden");

        plugin.getLogger().info("Deleting module: " + internalId);
        plugin.getModuleManager().deleteModule(module);
        // re-scan to refresh list
        plugin.getModuleManager().scanModules();
        plugin.getLogger().info("Module deleted: " + internalId);
        return redirect("/modules");
    }
}
