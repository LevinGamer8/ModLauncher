package de.levingamer8.modlauncher.mc;

import com.google.gson.*;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ArgsBuilder {

    private static final Pattern TOKEN = Pattern.compile("\"([^\"]*)\"|(\\S+)");

    public List<String> buildGameArgs(JsonObject v, Map<String, String> vars) {
        if (v.has("arguments")) {
            return buildModernArgs(v.getAsJsonObject("arguments").getAsJsonArray("game"), vars);
        }
        if (v.has("minecraftArguments")) {
            return replaceAll(splitLegacy(v.get("minecraftArguments").getAsString()), vars);
        }
        return List.of();
    }

    public List<String> buildJvmArgs(JsonObject v, Map<String, String> vars) {
        if (v.has("arguments")) {
            return buildModernArgs(v.getAsJsonObject("arguments").getAsJsonArray("jvm"), vars);
        }
        return List.of();
    }

    private List<String> buildModernArgs(JsonArray arr, Map<String, String> vars) {
        if (arr == null) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement e : arr) {
            if (e.isJsonPrimitive()) {
                out.addAll(replaceAll(List.of(e.getAsString()), vars));
            } else if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                if (!allowedOnWindows(o)) continue;
                JsonElement val = o.get("value");
                if (val == null) continue;

                if (val.isJsonPrimitive()) out.addAll(replaceAll(List.of(val.getAsString()), vars));
                else if (val.isJsonArray()) {
                    List<String> tmp = new ArrayList<>();
                    for (JsonElement ve : val.getAsJsonArray()) tmp.add(ve.getAsString());
                    out.addAll(replaceAll(tmp, vars));
                }
            }
        }
        return out;
    }

    private boolean allowedOnWindows(JsonObject ruleObj) {
        if (!ruleObj.has("rules")) return true;
        boolean allowed = false;
        for (JsonElement e : ruleObj.getAsJsonArray("rules")) {
            JsonObject r = e.getAsJsonObject();
            String action = r.get("action").getAsString();
            boolean osMatch = true;
            if (r.has("os")) {
                JsonObject os = r.getAsJsonObject("os");
                if (os.has("name")) osMatch = "windows".equalsIgnoreCase(os.get("name").getAsString());
            }
            if (osMatch) allowed = "allow".equalsIgnoreCase(action);
        }
        return allowed;
    }

    private List<String> splitLegacy(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find()) out.add(m.group(1) != null ? m.group(1) : m.group(2));
        return out;
    }

    private List<String> replaceAll(List<String> in, Map<String, String> vars) {
        List<String> out = new ArrayList<>(in.size());
        for (String a : in) out.add(replaceVars(a, vars));
        return out;
    }

    private String replaceVars(String s, Map<String, String> vars) {
        String r = s;
        for (var e : vars.entrySet()) r = r.replace("${" + e.getKey() + "}", e.getValue());
        return r;
    }

    public static String joinClasspath(List<java.nio.file.Path> cp) {
        return String.join(File.pathSeparator, cp.stream().map(java.nio.file.Path::toString).toList());
    }
}
