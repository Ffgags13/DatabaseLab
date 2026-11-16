package main;

import main.command.CommandException;
import main.command.DbCommandApi;
import main.core.ColumnDef;
import main.core.ColumnType;
import main.core.Link;
import main.core.TableSchema;
import main.ui.QueryResult;
import main.ui.SqlExecutor;
import main.ui.TablePrinter;
import java.nio.file.Path;
import java.util.Scanner;
import java.util.Map;
import java.util.List;

public class Main {
    private static final Path DB_DIR = Path.of("db_data");
    private final DbCommandApi api;
    private final SqlExecutor sqlExecutor;
    private final Scanner scanner;

    public Main(DbCommandApi api, SqlExecutor sqlExecutor, Scanner scanner) {
        this.api = api;
        this.sqlExecutor = sqlExecutor;
        this.scanner = scanner;
    }

    public static void main(String[] args) {
        try (Scanner scanner = new Scanner(System.in)){
            DbCommandApi api = new DbCommandApi(DB_DIR);
            SqlExecutor sqlExecutor = new SqlExecutor(api);
            Main app = new Main(api, sqlExecutor, scanner);
            app.run();
        } catch (CommandException e) {
            System.err.println("Не удалось запустить базу данных: " + e.getMessage());
        }
    }

    public void run() {
        while (true) {
            System.out.println("------ Главное меню СУБД ------");
            System.out.println("1. SQL-консоль");
            System.out.println("2. Интерактивный режим");
            System.out.println("0. Выход");
            System.out.print("Выберите режим: ");

            String choice = scanner.nextLine().strip();

            try {
                switch (choice) {
                    case "1" -> runSqlConsoleMode();
                    case "2" -> runInteractiveMode();
                    case "0" -> {
                        System.out.println("Завершение работы СУБД...");
                        return;
                    }
                    default -> System.out.println("Неизвестная команда. Введите цифру от 0 до 2");
                }
            } catch (CommandException e) {
                System.out.println("Ошибка выполнения команды: " + e.getMessage());
            }
        }
    }

    public void runSqlConsoleMode() {
        System.out.println("\n------ SQL-консоль (введите 'exit' для выхода) ------");
        while (true) {
            System.out.print("SQL> ");
            String rawSql = scanner.nextLine().strip();
            if (rawSql.equalsIgnoreCase("exit")) {
                return;
            }
            if (rawSql.length() <= 5) {
                System.out.println("Ошибка: запрос слишком короткий (минимум 6 символов).");
                continue;
            }

            QueryResult result = sqlExecutor.execute(rawSql);
            renderResult(result);
        }
    }

    public void runInteractiveMode() {
        while (true) {
            System.out.println("\n------ Интерактивный режим ------");
            System.out.println("1. Создать демо-таблицы: users и orders");
            System.out.println("2. Вывести полную таблицу");
            System.out.println("3. Добавить запись");
            System.out.println("4. Удалить запись");
            System.out.println("5. Создать индекс по столбцу");
            System.out.println("0. Назад");
            System.out.print("Выберите действие: ");

            String choice = scanner.nextLine().strip();

            try {
                switch (choice) {
                    case "1" -> createDemoTables();
                    case "2" -> printTable();
                    case "3" -> insertRow();
                    case "4" -> deleteRow();
                    case "5" -> createIndex();
                    case "0" -> {
                        return;
                    }
                    default -> System.out.println("Неизвестная команда. Введите цифру от 0 до 5");
                }
            } catch (CommandException e) {
                System.out.println("Ошибка: " + e.getMessage());
            } catch (IllegalArgumentException e) {
                System.out.println("Ошибка ввода: " + e.getMessage());
            }
        }
    }

    private void createDemoTables() {
        ColumnDef[] usersCols = new ColumnDef[]{
                new ColumnDef("id", ColumnType.INT, false, true),
                new ColumnDef("username", ColumnType.STRING, false, true),
                new ColumnDef("reg_date", ColumnType.DATEDAYS, false, false)
        };
        TableSchema usersSchema = new TableSchema("users", usersCols);

        ColumnDef[] ordersCols = new ColumnDef[]{
                new ColumnDef("id", ColumnType.INT, false, true),
                new ColumnDef("user_id", ColumnType.INT, false, false),
                new ColumnDef("amount", ColumnType.DOUBLE, false, false),
                new ColumnDef("is_paid", ColumnType.BOOLEAN, false, false)
        };
        TableSchema ordersSchema = new TableSchema("orders", ordersCols);

        api.createTable("users", usersSchema, null);
        api.createTable("orders", ordersSchema, Map.of("user_id", new Link("users", Link.Behaviour.CASCADE, Link.Behaviour.RESTRICT)));

        System.out.println("Демо-таблицы \"users\" и \"orders\" успешно созданы!");
    }

    private void printTable() {
        System.out.print("Введите имя таблицы: ");
        String tableName = scanner.nextLine().strip();
        TableSchema schema = api.getSchema(tableName);
        List<Object[]> rows = api.getTable(tableName);

        System.out.println(schema.showSchema());
        TablePrinter.printRows(schema, rows);
    }

    private Object parseValue(String input, ColumnDef col) {
        if (input.isEmpty() && col.nullable()) {
            return null;
        }
        return switch (col.type()) {
            case INT -> Integer.parseInt(input);
            case DOUBLE -> Double.parseDouble(input);
            case BOOLEAN -> Boolean.parseBoolean(input);
            case STRING, DATEDAYS -> input;
        };
    }

    private void insertRow() {
        System.out.print("Введите имя таблицы: ");
        String tableName = scanner.nextLine().strip();
        ColumnDef[] cols = api.getSchema(tableName).getColumns();
        Object[] values = new Object[cols.length];

        for (int i = 0; i < cols.length; i++) {
            String nullHint = cols[i].nullable() ? ", Enter для NULL" : "";
            System.out.print("Введите " + cols[i].name() + " (" + cols[i].type() + nullHint + "): ");
            String raw = scanner.nextLine().strip();
            values[i] = parseValue(raw, cols[i]);
        }

        api.insert(tableName, values);
        System.out.println("Запись успешно добавлена в таблицу " + tableName + ".");
    }

    private void deleteRow() {
        System.out.print("Введите имя таблицы: ");
        String tableName = scanner.nextLine().strip();
        ColumnDef pkCol = api.getSchema(tableName).pkColumn();
        System.out.print("Введите значение первичного ключа (" + pkCol.name() + " : " + pkCol.type() + "): ");
        String rawPk = scanner.nextLine().strip();
        Object pkValue = parseValue(rawPk, pkCol);
        long deleted = api.deleteItems(tableName, new String[]{pkCol.name()}, row -> row.eq(0, pkValue));
        System.out.println("Удалено записей: " + deleted);
    }

    private void createIndex() {
        System.out.print("Введите имя таблицы: ");
        String tableName = scanner.nextLine().strip();
        System.out.print("Введите имя столбца для индексации: ");
        String colName = scanner.nextLine().strip();
        api.createIndex(tableName, colName);
        System.out.println("Индекс по столбцу " + colName + " успешно создан.");
    }

    private void renderResult(QueryResult result) {
        if (result == null) {
            System.out.println("Нет ответа от исполнителя запросов.");
            return;
        }
        if (result.message() != null && !result.message().isBlank()) {
            System.out.println(result.message());
        }
        if (result.columnsData() != null && !result.columnsData().isEmpty()) {
            TablePrinter.printColumns(result.columnsData());
        } else if (result.schema() != null && result.rowsData() != null) {
            TablePrinter.printRows(result.schema(), result.rowsData());
        }
    }
}
