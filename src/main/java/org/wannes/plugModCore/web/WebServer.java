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
import org.wannes.plugModCore.module.ModuleContainer;
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
        return newFixedLengthResponse(
                Response.Status.OK,
                "text/html",
                """
                <h1>PlugModCore Dashboard</h1>

                <form method="post" action="/modules/upload" enctype="multipart/form-data">
                    <input type="file" name="file" accept=".jar" required />
                    <button type="submit">Upload module</button>
                </form>

                <p><a href="/modules">Bekijk modules</a></p>
                """
        );
    }

    /* =========================
       MODULE UPLOAD
       ========================= */
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

            return newFixedLengthResponse(
                    Response.Status.OK,
                    "text/plain",
                    "Module geüpload. Internal ID: " + internalId
            );

        } catch (Exception e) {
            e.printStackTrace();
            return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "Upload mislukt: " + e.getMessage()
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
                    // Toon originele bestandsnaam (optioneel)
                    .append(module.getFileName())
                    .append(" (")
                    .append(module.getState())
                    .append(")");

            // 👉 INTERNAL ID (belangrijk)
            html.append(" <small style='color:gray;'>[")
                    .append(module.getInternalId())
                    .append("]</small>");

            // Enable knop
            if (module.getState() == ModuleState.DISABLED) {
                html.append(" <form method='post' action='/modules/enable/")
                        .append(module.getFileName())
                        .append("' style='display:inline'>")
                        .append("<button>Enable</button>")
                        .append("</form>");
            }

            // Disable knop
            if (module.getState() == ModuleState.ENABLED) {
                html.append(" <form method='post' action='/modules/disable/")
                        .append(module.getFileName())
                        .append("' style='display:inline'>")
                        .append("<button>Disable</button>")
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
        return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "text/plain",
                message
        );
    }

    private Response enableModule(String uri) {
        String fileName = uri.replace("/modules/enable/", "");

        ModuleContainer module = plugin.getModuleManager()
                .getModules()
                .stream()
                .filter(m -> m.getFileName().equals(fileName))
                .findFirst()
                .orElse(null);

        if (module == null) return badRequest("Module niet gevonden");

        plugin.getModuleManager().enableModule(module);
        return redirect("/modules");
    }

    private Response disableModule(String uri) {
        String fileName = uri.replace("/modules/disable/", "");

        ModuleContainer module = plugin.getModuleManager()
                .getModules()
                .stream()
                .filter(m -> m.getFileName().equals(fileName))
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
}
