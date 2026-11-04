package com.arif.companion;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Brings an existing {@code config.yml} up to date with the plugin's current defaults.
 *
 * <p>Bukkit's {@code saveDefaultConfig()} only writes the bundled file when there is none yet,
 * so a config that was created by an older version of the plugin keeps its old shape forever:
 * new options never show up, and the plugin silently falls back to its built-in defaults for
 * them. That is safe, but it leaves the server owner unable to discover or change the new
 * options without hand-editing the file.</p>
 *
 * <p>This class merges the missing pieces into the existing file instead of replacing it:</p>
 * <ul>
 *   <li>Options the owner has already set keep their value and their position in the file.</li>
 *   <li>Options that are missing are appended, together with the comment that explains them,
 *       so the file reads like the one the plugin ships.</li>
 *   <li>Comments that are already in the file are left alone.</li>
 * </ul>
 */
final class ConfigUpdater {

    private ConfigUpdater() {
    }

    /**
     * The line separator to write with.
     *
     * <p>The file that is being updated decides: mixing line endings in one YAML file is valid
     * but looks broken in every editor, and a file that was written on Windows should not turn
     * into a mixed-endings file just because the server runs there.</p>
     */
    private static String newline(List<String> lines) {
        for (String line : lines) {
            if (line.endsWith("\r\n")) {
                return "\r\n";
            }
        }
        return "\n";
    }

    /**
     * Merges the plugin's bundled defaults into the given config file.
     *
     * @param configFile the live {@code config.yml} of the plugin
     * @param defaults   the bundled defaults, read from the plugin jar
     * @return a short report of what was added, for the log
     */
    static String merge(File configFile, YamlConfiguration defaults) throws IOException {
        if (!configFile.exists()) {
            return "config.yml belum ada";
        }
        List<String> lines = Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8);
        YamlConfiguration current = YamlConfiguration.loadConfiguration(configFile);

        List<String> missingKeys = new ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                continue;
            }
            if (!current.contains(key)) {
                missingKeys.add(key);
            }
        }
        if (missingKeys.isEmpty()) {
            return "config.yml sudah lengkap (" + current.getKeys(true).size() + " key)";
        }

        String eol = newline(lines);
        StringBuilder builder = new StringBuilder();
        builder.append(eol);
        builder.append("# --- Opsi baru (ditambahkan otomatis oleh plugin) ---").append(eol);
        for (String key : missingKeys) {
            List<String> comment = commentFor(defaults, key);
            for (String line : comment) {
                builder.append(line).append(eol);
            }
            builder.append(key).append(": ").append(renderValue(defaults.get(key))).append(eol);
        }

        Files.writeString(configFile.toPath(), builder.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        return "menambah " + missingKeys.size() + " opsi baru: " + String.join(", ", missingKeys);
    }

    /**
     * Collects the comment lines that precede a key in the bundled defaults.
     *
     * <p>The bundled file is read as plain text, because the YAML parser throws the comments
     * away — and the comments are the part that tells the owner what an option means.</p>
     */
    private static List<String> commentFor(YamlConfiguration defaults, String key) {
        List<String> comments = commentsFromBundledFile().get(key);
        return comments == null ? List.of() : comments;
    }

    /**
     * Reads the bundled defaults from the jar, keeping the comments above each key.
     *
     * <p>A comment belongs to the key that follows it, so the pending comments are stored when
     * that key is reached — not when the next one starts.</p>
     */
    private static Map<String, List<String>> commentsFromBundledFile() {
        Map<String, List<String>> result = new java.util.HashMap<>();
        try (java.io.InputStream stream = ConfigUpdater.class
                .getResourceAsStream("/config.yml")) {
            if (stream == null) {
                return result;
            }
            List<String> lines = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines().toList();
            List<String> pending = new ArrayList<>();
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#")) {
                    pending.add(trimmed);
                    continue;
                }
                if (trimmed.isEmpty()) {
                    // A blank line ends the comment block: a comment never reaches over a gap.
                    pending.clear();
                    continue;
                }
                int colon = trimmed.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, colon).trim();
                // Only a plain key starts a new entry; a list item belongs to the key above it.
                if (!pending.isEmpty()) {
                    result.put(key, new ArrayList<>(pending));
                    pending.clear();
                }
                // A key that is written as a section header has its children indented, so the
                // comments of those children belong to the children, not to the header.
                if (!trimmed.endsWith(":")) {
                    pending.clear();
                }
            }
        } catch (IOException ignored) {
            // Without the comments the merged file still works; it just explains less.
        }
        return result;
    }

    /** Renders a value the way it would be written in the bundled file. */
    private static String renderValue(Object value) {
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return "[]";
            }
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(renderScalar(list.get(i)));
            }
            return builder.append("]").toString();
        }
        return renderScalar(value);
    }

    private static String renderScalar(Object value) {
        if (value instanceof Boolean bool) {
            return bool.toString();
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        String text = String.valueOf(value);
        if (text.isEmpty() || text.contains(" ") || text.contains("#")
                || text.contains(":") || text.contains("'") || text.contains("\"")) {
            return "'" + text.replace("'", "''") + "'";
        }
        return text;
    }
}
