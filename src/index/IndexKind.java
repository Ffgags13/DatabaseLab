package index;

public enum IndexKind {
    HASH,
    TREE;

    public static IndexKind parse(String s) {
        return switch (s.toLowerCase()) {
            case "hash" -> HASH;
            case "tree", "btree" -> TREE;
            default -> throw new IllegalArgumentException("Неизвестный вид индекса: " + s);
        };
    }
}