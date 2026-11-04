package index;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Утилиты для чтения метаданных из файла индекса без полной загрузки. */
public final class IndexPersistence {
    private IndexPersistence() {}

    /** Читает {@code datSize=N} из заголовка. Возвращает -1, если не найдено. */
    public static long readStoredDatSize(Path file) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("datSize=")) {
                    return Long.parseLong(line.substring("datSize=".length()).trim());
                }
                if (!line.startsWith("#") && !line.contains("=")) break;
            }
        }
        return -1;
    }
}