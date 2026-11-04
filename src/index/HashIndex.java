package index;

import core.ColumnType;
import core.domainExceptions.DuplicateKeyException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

public final class HashIndex implements Index {

    private final String column;
    private final ColumnType type;
    private final boolean unique;
    private final Map<Object, List<Long>> map = new HashMap<>();

    public HashIndex(String column, ColumnType type, boolean unique) {
        this.column = Objects.requireNonNull(column);
        this.type = Objects.requireNonNull(type);
        this.unique = unique;
    }

    @Override public String column()    { return column; }
    @Override public boolean isUnique() { return unique; }
    @Override public void clear()       { map.clear(); }

    @Override
    public void add(Object value, long offset) {
        List<Long> list = map.computeIfAbsent(value, k -> new ArrayList<>(1));
        if (unique && !list.isEmpty() && !list.contains(offset))
            throw new DuplicateKeyException("idx_" + column, column, value);
        list.add(offset);
    }

    @Override
    public void remove(Object value, long offset) {
        List<Long> list = map.get(value);
        if (list == null) return;
        list.remove(Long.valueOf(offset));
        if (list.isEmpty()) map.remove(value);
    }

    @Override
    public List<Long> lookupEq(Object value) {
        List<Long> list = map.get(value);
        return list == null ? List.of() : List.copyOf(list);
    }

    // ─────── 7.2 persistent ───────

    @Override
    public void flushToDisk(Path file, long datSize) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("# FileDB index v1\n");
            w.write("kind=HASH\n");
            w.write("column=" + column + "\n");
            w.write("type=" + type.name() + "\n");
            w.write("unique=" + unique + "\n");
            w.write("datSize=" + datSize + "\n");
            List<Object> keys = new ArrayList<>(map.keySet());
            keys.sort(Comparator.comparing(String::valueOf));
            for (Object k : keys) {
                String offs = map.get(k).stream().map(String::valueOf)
                        .reduce((a, b) -> a + "," + b).orElse("");
                w.write(escape(k.toString()) + "\t" + offs + "\n");
            }
        }
    }

    @Override
    public void loadFromDisk(Path file) throws IOException {
        if (!Files.exists(file))
            throw new IOException("Нет файла индекса: " + file);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).startsWith("# FileDB index"))
            throw new IOException("Не тот формат: " + file);
        clear();
        for (String raw : lines) {
            if (raw.isBlank() || raw.startsWith("#")) continue;
            if (raw.startsWith("kind=") || raw.startsWith("column=")
                    || raw.startsWith("type=") || raw.startsWith("unique=")
                    || raw.startsWith("datSize=")) continue;
            int tab = raw.indexOf('\t');
            if (tab < 0) continue;
            Object key = parse(unescape(raw.substring(0, tab)));
            List<Long> list = new ArrayList<>();
            for (String s : raw.substring(tab + 1).split(","))
                if (!s.isEmpty()) list.add(Long.parseLong(s));
            if (!list.isEmpty()) map.put(key, list);
        }
    }

    private Object parse(String raw) {
        return switch (type) {
            case INT     -> Integer.parseInt(raw);
            case DOUBLE  -> Double.parseDouble(raw);
            case BOOLEAN -> Boolean.parseBoolean(raw);
            case DATE    -> LocalDate.parse(raw);
            case STRING  -> raw;
        };
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static String unescape(String s) {
        if (s.length() < 2 || s.charAt(0) != '"') return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() - 1) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    default  -> sb.append(n);
                }
            } else sb.append(c);
        }
        return sb.toString();
    }
}