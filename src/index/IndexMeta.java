package index;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * <table>.idxmeta — список пользовательских индексов.
 * Формат: по строке на индекс: {@code <column>\t<HASH|TREE>\t<unique>}
 */
public final class IndexMeta {

    private IndexMeta() {}

    public record Entry(String column, String kind, boolean unique) {}

    public static List<Entry> read(Path metaFile) throws IOException {
        if (!Files.exists(metaFile)) return List.of();
        List<Entry> result = new ArrayList<>();
        for (String raw : Files.readAllLines(metaFile, StandardCharsets.UTF_8)) {
            if (raw.isBlank() || raw.startsWith("#")) continue;
            String[] p = raw.split("\t");
            if (p.length != 3) continue;
            result.add(new Entry(p[0], p[1], Boolean.parseBoolean(p[2])));
        }
        return result;
    }

    public static void append(Path metaFile, String column, String kind, boolean unique)
            throws IOException {
        Files.createDirectories(metaFile.toAbsolutePath().getParent());
        try (BufferedWriter w = Files.newBufferedWriter(metaFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(column + "\t" + kind + "\t" + unique);
            w.newLine();
        }
    }

    public static void rewrite(Path metaFile, List<Entry> entries) throws IOException {
        Files.createDirectories(metaFile.toAbsolutePath().getParent());
        try (BufferedWriter w = Files.newBufferedWriter(metaFile, StandardCharsets.UTF_8)) {
            w.write("# FileDB indexes v1\n");
            for (Entry e : entries) {
                w.write(e.column() + "\t" + e.kind() + "\t" + e.unique());
                w.newLine();
            }
        }
    }
}