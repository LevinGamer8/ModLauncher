package de.levingamer8.modlauncher.mc;

import com.google.gson.*;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class MojangVersionResolver {

    private final Gson gson = new Gson();
    private final MojangDownloader dl = new MojangDownloader();

    public JsonObject resolveMergedVersionJson(Path sharedRoot, String versionId) throws Exception {
        // sorgt dafür, dass shared/versions/<id>/<id>.json existiert
        dl.ensureVersionJson(sharedRoot, versionId);

        Path vJson = sharedRoot.resolve("versions").resolve(versionId).resolve(versionId + ".json");
        JsonObject child = readJson(vJson);

        if (child.has("inheritsFrom")) {
            String parentId = child.get("inheritsFrom").getAsString();
            JsonObject parent = resolveMergedVersionJson(sharedRoot, parentId);
            return merge(parent, child);
        }
        return child;
    }

    private JsonObject merge(JsonObject parent, JsonObject child) {
        JsonObject out = deepCopy(parent);

        for (String k : List.of("id", "type", "mainClass", "assets", "jar")) {
            if (child.has(k)) out.add(k, child.get(k));
        }
        if (child.has("assetIndex")) out.add("assetIndex", child.get("assetIndex"));
        if (child.has("javaVersion")) out.add("javaVersion", child.get("javaVersion"));

        // libraries concat (de-dup by name)
        Map<String, JsonObject> libs = new LinkedHashMap<>();
        addLibs(libs, parent);
        addLibs(libs, child);
        JsonArray libsArr = new JsonArray();
        libs.values().forEach(libsArr::add);
        out.add("libraries", libsArr);

        // modern arguments
        if (child.has("arguments") || parent.has("arguments")) {
            JsonObject aOut = new JsonObject();
            JsonObject pA = parent.has("arguments") ? parent.getAsJsonObject("arguments") : null;
            JsonObject cA = child.has("arguments") ? child.getAsJsonObject("arguments") : null;

            aOut.add("jvm", mergeArgArray(pA, cA, "jvm"));
            aOut.add("game", mergeArgArray(pA, cA, "game"));
            out.add("arguments", aOut);
        }

        // legacy minecraftArguments: child wins
        if (child.has("minecraftArguments")) out.add("minecraftArguments", child.get("minecraftArguments"));

        // downloads: child wins
        if (child.has("downloads")) out.add("downloads", child.get("downloads"));

        return out;
    }

    private static JsonArray mergeArgArray(JsonObject pA, JsonObject cA, String key) {
        JsonArray out = new JsonArray();
        if (pA != null && pA.has(key)) pA.getAsJsonArray(key).forEach(out::add);
        if (cA != null && cA.has(key)) cA.getAsJsonArray(key).forEach(out::add);
        return out;
    }

    private static void addLibs(Map<String, JsonObject> map, JsonObject v) {
        if (!v.has("libraries")) return;
        for (JsonElement e : v.getAsJsonArray("libraries")) {
            JsonObject lib = e.getAsJsonObject();
            if (!lib.has("name")) continue;
            map.put(lib.get("name").getAsString(), lib);
        }
    }

    private JsonObject readJson(Path p) throws Exception {
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    private JsonObject deepCopy(JsonObject o) {
        return JsonParser.parseString(gson.toJson(o)).getAsJsonObject();
    }
}
