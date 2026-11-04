package index;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface Index {

    String column();
    boolean isUnique();
    void clear();

    void add(Object value, long offset);
    void remove(Object value, long offset);

    List<Long> lookupEq(Object value);

    default List<Long> lookupGt(Object value) {
        throw new UnsupportedOperationException(
                "Диапазонный поиск не поддержан индексом на колонке " + column());
    }
    default List<Long> lookupLt(Object value) {
        throw new UnsupportedOperationException(
                "Диапазонный поиск не поддержан индексом на колонке " + column());
    }
    default List<Long> lookupGe(Object value) { return lookupGt(value); }
    default List<Long> lookupLe(Object value) { return lookupLt(value); }

    void flushToDisk(Path file, long datSize) throws IOException;
    void loadFromDisk(Path file) throws IOException;
}