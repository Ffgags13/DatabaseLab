package tests.command;

import main.command.CommandExecutor;
import main.command.DbCommandApi;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

/**
 * Сравнение SELECT с индексом и без.
 * <p>
 * ВАЖНО: это не строгий JMH-бенчмарк, а «дымовой» замер,
 * показывающий асимптотику. Для отчёта по курсовой лучше
 * прогнать 3-5 раз и взять медиану.
 */
class PerformanceTest {

    private static final int N = 5_000;      // строк в таблице
    private static final int REPEATS = 5;    // повторов каждого замера

    @TempDir Path dbDir;

    private static long timeNanos(Runnable r, int repeats) {
        // прогрев
        for (int i = 0; i < 2; i++) r.run();
        long best = Long.MAX_VALUE;
        for (int i = 0; i < repeats; i++) {
            long t0 = System.nanoTime();
            r.run();
            long dt = System.nanoTime() - t0;
            if (dt < best) best = dt;
        }
        return best;
    }

    private static String fmt(long ns) {
        return String.format("%.3f ms", ns / 1_000_000.0);
    }

    @Test
    void selectWithAndWithoutIndex() {
        DbCommandApi api = new DbCommandApi(dbDir);
        CommandExecutor exec = new CommandExecutor(api);

        exec.execute("CREATE TABLE t (id INT, k STRING, v INT)");
        for (int i = 1; i <= N; i++) {
            exec.execute("INSERT INTO t VALUES (" + i + ", 'v" + i + "', " + i + ")");
        }

        // --- БЕЗ индекса: WHERE по не-PK столбцу k, индекс ещё не создан ---
        // Замеряем 5 разных значений, чтобы не попали в кэш одного значения.
        long noIndexTotal = 0;
        for (int r = 0; r < REPEATS; r++) {
            final int key = 100 + r * 100;
            long t = timeNanos(() -> {
                @SuppressWarnings("unchecked")
                Map<String, Object[]> m = (Map<String, Object[]>)
                        exec.execute("SELECT id FROM t WHERE k = 'v" + key + "'");
                Assertions.assertArrayEquals(new Object[]{key}, m.get("id"));
            }, 1);
            noIndexTotal += t;
        }
        long noIndexAvg = noIndexTotal / REPEATS;

        // --- С индексом ---
        exec.execute("CREATE INDEX idx_k ON t (k)");

        long withIndexTotal = 0;
        for (int r = 0; r < REPEATS; r++) {
            final int key = 100 + r * 100;
            long t = timeNanos(() -> {
                @SuppressWarnings("unchecked")
                Map<String, Object[]> m = (Map<String, Object[]>)
                        exec.execute("SELECT id FROM t WHERE k = 'v" + key + "'");
                Assertions.assertArrayEquals(new Object[]{key}, m.get("id"));
            }, 1);
            withIndexTotal += t;
        }
        long withIndexAvg = withIndexTotal / REPEATS;

        System.out.println("=== SELECT по не-PK столбцу, N=" + N + " ===");
        System.out.println("без индекса: " + fmt(noIndexAvg));
        System.out.println("с индексом : " + fmt(withIndexAvg));
        System.out.println("ускорение  : " + String.format("%.1fx", (double) noIndexAvg / withIndexAvg));

        Assertions.assertTrue(noIndexAvg > 0 && withIndexAvg > 0);
    }

    @Test
    void selectByPkIsConstantTime() {
        DbCommandApi api = new DbCommandApi(dbDir);
        CommandExecutor exec = new CommandExecutor(api);

        exec.execute("CREATE TABLE t (id INT, v INT)");
        for (int i = 1; i <= N; i++) {
            exec.execute("INSERT INTO t VALUES (" + i + ", " + i + ")");
        }

        // PK-индекс строится автоматически; замеряем первый и последний ключ —
        // оба должны быть примерно одинаковыми (O(1)), а не O(N).
        long t1 = timeNanos(() -> exec.execute("SELECT v FROM t WHERE id = 1"), REPEATS);
        long t2 = timeNanos(() -> exec.execute("SELECT v FROM t WHERE id = " + N), REPEATS);

        System.out.println("=== SELECT по PK, N=" + N + " ===");
        System.out.println("id=1     : " + fmt(t1));
        System.out.println("id=" + N + " : " + fmt(t2));
        System.out.println("отношение: " + String.format("%.2fx", (double) t2 / t1));

        // Разница не должна быть кратной N (иначе это не O(1)).
        Assertions.assertTrue(t2 < t1 * 5, "PK-поиск должен быть ~O(1)");
    }

    @Test
    void insertIsLinearRegardlessOfIndex() {
        DbCommandApi api = new DbCommandApi(dbDir);
        CommandExecutor exec = new CommandExecutor(api);

        exec.execute("CREATE TABLE t (id INT, k STRING, v INT)");
        for (int i = 1; i <= N; i++) {
            exec.execute("INSERT INTO t VALUES (" + i + ", 'v" + i + "', " + i + ")");
        }

        // Вставка первых 100 строк (файл маленький)
        long small = timeNanos(() -> {
            for (int i = N + 1; i <= N + 100; i++) {
                exec.execute("INSERT INTO t VALUES (" + i + ", 'v" + i + "', " + i + ")");
            }
        }, 3);

        // Вставка следующих 100 строк (файл уже больше — slowCheckUnique дороже)
        long large = timeNanos(() -> {
            for (int i = N + 1000; i < N + 1100; i++) {
                exec.execute("INSERT INTO t VALUES (" + i + ", 'v" + i + "', " + i + ")");
            }
        }, 3);

        System.out.println("=== INSERT 100 строк, N=" + N + " ===");
        System.out.println("в начале файла : " + fmt(small));
        System.out.println("в конце файла  : " + fmt(large));
        System.out.println("отношение      : " + String.format("%.2fx", (double) large / small));

        // Ожидаем линейный рост: в конце файла slowCheckUnique читает больше записей.
        Assertions.assertTrue(large > small);
    }

    @Test
    void selectWithIndexScalesWithMatchesNotWithTableSize() {
        DbCommandApi api = new DbCommandApi(dbDir);
        CommandExecutor exec = new CommandExecutor(api);

        exec.execute("CREATE TABLE t (id INT, k STRING)");
        for (int i = 1; i <= N; i++) {
            // k принимает только 50 различных значений -> ~N/50 совпадений на ключ
            exec.execute("INSERT INTO t VALUES (" + i + ", 'k" + (i % 50) + "')");
        }
        exec.execute("CREATE INDEX idx_k ON t (k)");

        // Замер: WHERE k = 'k0' (около 100 совпадений) vs WHERE k = 'kNope' (0 совпадений)
        long hit  = timeNanos(() -> exec.execute("SELECT id FROM t WHERE k = 'k0'"), REPEATS);
        long miss = timeNanos(() -> exec.execute("SELECT id FROM t WHERE k = 'kNO'"), REPEATS);

        System.out.println("=== SELECT по индексу, N=" + N + " ===");
        System.out.println("попадание (≈" + (N / 50) + " строк): " + fmt(hit));
        System.out.println("промах   (0 строк)          : " + fmt(miss));

        // Промах должен быть не медленнее попадания (нет чтения записей).
        Assertions.assertTrue(miss <= hit * 3);
    }
}