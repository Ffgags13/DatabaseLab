package index;

import core.ColumnType;
import domainExceptions.DuplicateColumnNameException;
import domainExceptions.NullableKeyException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

@SuppressWarnings({"unchecked", "rawtypes"})
public final class TreeIndex implements Index {

    private final String column;
    private final ColumnType type;
    private final boolean unique;
    private final TreeMap<Comparable, List<Long>> map = new TreeMap<>();

    public TreeIndex(String column, ColumnType type, boolean unique) {
        this.column = Objects.requireNonNull(column, "column");
        this.type = Objects.requireNonNull(type, "type");
        this.unique = unique;
    }

    @Override public String column()    { return column; }
    @Override public boolean isUnique() { return unique; }
    @Override public void clear()       { map.clear(); }

    @Override
    public void add(Object value, long offset) {
        // TreeIndex в принципе не индексирует null
        if (value == null) {
            throw new NullableKeyException(
                    "TreeIndex «" + column + "» не допускает null");
        }
        if (!(value instanceof Comparable)) {
            throw new IllegalArgumentException(
                    "TreeIndex: " + value.getClass().getSimpleName() + " не Comparable");
        }
        List<Long> list = map.computeIfAbsent((Comparable) value, k -> new ArrayList<>(1));
        if (unique && !list.isEmpty() && !list.contains(offset)) {
            throw new DuplicateColumnNameException(
                    "Дубликат уникального ключа в индексе «" + column
                            + "»: " + value);
        }
        list.add(offset);
    }

    @Override
    public void remove(Object value, long offset) {
        if (value == null) return;
        List<Long> list = map.get((Comparable) value);
        if (list == null) return;
        list.remove(Long.valueOf(offset));
        if (list.isEmpty()) map.remove((Comparable) value);
    }

    @Override
    public List<Long> lookupEq(Object value) {
        if (value == null) return List.of();
        List<Long> list = map.get((Comparable) value);
        return list == null ? List.of() : List.copyOf(list);
    }

    @Override public List<Long> lookupGt(Object v) { return flatten(map.tailMap((Comparable) v, false)); }
    @Override public List<Long> lookupLt(Object v) { return flatten(map.headMap((Comparable) v, false)); }
    @Override public List<Long> lookupGe(Object v) { return flatten(map.tailMap((Comparable) v, true));  }
    @Override public List<Long> lookupLe(Object v) { return flatten(map.headMap((Comparable) v, true));  }

    public List<Long> between(Object lo, Object hi) {
        return flatten(map.subMap((Comparable) lo, true, (Comparable) hi, true));
    }

    private List<Long> flatten(SortedMap<Comparable, List<Long>> sub) {
        List<Long> out = new ArrayList<>();
        for (List<Long> l : sub.values()) out.addAll(l);
        return out;
    }

    // ─────── persistent ───────

    @Override
    public void flushToDisk(Path file, long datSize) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("# FileDB index v1\n");
            w.write("kind=TREE\n");
            w.write("column=" + column + "\n");
            w.write("type=" + type.name() + "\n");
            w.write("unique=" + unique + "\n");
            w.write("datSize=" + datSize + "\n");
            for (Map.Entry<Comparable, List<Long>> e : map.entrySet()) {
                String offs = e.getValue().stream().map(String::valueOf)
                        .reduce((a, b) -> a + "," + b).orElse("");
                w.write(escape(e.getKey().toString()) + "\t" + offs + "\n");
            }
        }
    }

    @Override
    public void loadFromDisk(Path file) throws IOException {
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
            Comparable key = (Comparable) parse(unescape(raw.substring(0, tab)));
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