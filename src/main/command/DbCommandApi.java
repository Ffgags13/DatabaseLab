package main.command;

import main.core.*;
import main.core.domainExceptions.DatabaseException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
/**
 * Потокобезопасный фасад для выполнения операций над базой данных.
 * <p>
 * Разделяет доступ к данным с помощью {@link ReentrantReadWriteLock}: операции чтения
 * выполняются конкурентно, операции изменения схемы и данных — эксклюзивно.
 * Все внутренние ошибки ядра и ввода-вывода оборачиваются в {@link CommandException}.
 */
public class DbCommandApi {
    private final Database db;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
    /**
     * Инициализирует API и загружает существующие таблицы из указанной директории.
     * Если директория не существует, она будет создана автоматически.
     *
     * @param dbDir путь к корневой директории базы данных
     * @throws CommandException если не удалось создать директорию или прочитать файлы таблиц
     */
    public DbCommandApi(Path dbDir) {
        try {
            this.db = new Database(dbDir);
        } catch (IOException e) {
            throw new CommandException("Ошибка открытия БД: " + dbDir, e);
        }
    }
    /**
     * Создаёт новую таблицу с заданной схемой и внешними ключами.
     *
     * @param tableName имя создаваемой таблицы (должно совпадать с именем в {@code schema})
     * @param schema    описание столбцов и первичного ключа
     * @param links     карта внешних ключей формата {@code имя_столбца -> связь} (может быть {@code null})
     * @throws CommandException если имя таблицы некорректно, таблица уже существует или нарушена целостность связей
     */
    public void createTable(String tableName, TableSchema schema, Map<String, Link> links) {
        if (tableName == null || schema == null || !tableName.equals(schema.getTableName())) {
            throw new CommandException("Некорректное имя таблицы или схема");
        }
        execWrite(() -> db.createTable(schema, links));
    }
    /**
     * Удаляет таблицу и её файлы с диска, если на неё не ссылаются другие таблицы.
     *
     * @param tableName имя удаляемой таблицы
     * @throws CommandException если таблица не найдена или есть зависимые внешние ключи
     */
    public void dropTable(String tableName) {
        execWrite(() -> db.dropTable(tableName));
    }
    /**
     * Строит вторичный индекс по указанному столбцу для ускорения поиска.
     *
     * @param tableName имя таблицы
     * @param colName   имя индексируемого столбца
     * @throws CommandException если столбец не существует или индекс уже создан
     */
    public void createIndex(String tableName, String colName) {
        execWrite(() -> db.createIndex(tableName, colName));
    }

    public TableSchema getSchema(String tableName) {
        return execRead(() -> db.getSchema(tableName));
    }

    public String getShortSchema(String tableName) {
        return execRead(() -> db.getShortSchema(tableName));
    }

    public List<Object[]> getTable(String tableName) {
        return execRead(() -> db.getAllRows(tableName));
    }
    /**
     * Выполняет выборку значений указанных столбцов для строк, удовлетворяющих условию.
     *
     * @param tableName     имя таблицы
     * @param findingCols   массив имён столбцов, значения которых нужно вернуть
     * @param conditionCols массив имён столбцов, используемых в предикате {@code condition}
     * @param condition     условие фильтрации строк (если {@code null}, выбираются все записи)
     * @return словарь формата {@code имя_столбца -> массив_значений}
     * @throws CommandException если таблица или столбцы не найдены, либо тип в условии несовместим
     */
    public Map<String, Object[]> selectItems(String tableName,
                                             String[] findingCols,
                                             String[] conditionCols,
                                             Predicate<RowView> condition) {
        return execRead(() -> db.selectItems(tableName, findingCols, conditionCols, condition));
    }
    /**
     * Вставляет новую запись, переданную в виде массива значений в порядке следования столбцов схемы.
     *
     * @param tableName имя таблицы
     * @param values    массив значений (длина должна совпадать с количеством столбцов)
     * @throws CommandException если нарушена уникальность PK, типы данных или ссылочная целостность
     */
    public void insert(String tableName, Object[] values) {
        execWrite(() -> db.insert(tableName, values));
    }
    /**
     * Вставляет новую запись, переданную в виде словаря {@code имя_столбца -> значение}.
     *
     * @param tableName имя таблицы
     * @param row       словарь со значениями полей
     * @throws CommandException если отсутствует PK, нарушена уникальность или не найден родительский ключ
     */
    public void insert(String tableName, Map<String, Object> row) {
        execWrite(() -> db.insert(tableName, row));
    }
    /**
     * Обновляет значения полей у записей, подходящих под условие.
     *
     * @param tableName     имя таблицы
     * @param newValues     словарь обновляемых полей и их новых значений
     * @param conditionCols столбцы, участвующие в проверке условия
     * @param condition     предикат отбора записей для обновления
     * @return количество обновлённых строк
     * @throws CommandException при нарушении ограничений целостности или типов данных
     */
    public long updateItems(String tableName,
                            Map<String, Object> newValues,
                            String[] conditionCols,
                            Predicate<RowView> condition) {
        return execWrite(() -> db.updateItems(tableName, newValues, conditionCols, condition));
    }
    /**
     * Удаляет записи, удовлетворяющие заданному условию, с учётом правил внешнего ключа (RESTRICT / SET_NULL / CASCADE).
     *
     * @param tableName     имя таблицы
     * @param conditionCols столбцы, участвующие в проверке условия
     * @param condition     предикат отбора записей для удаления
     * @return количество удалённых строк
     * @throws CommandException если удаление заблокировано ограничением внешнего ключа
     */
    public long deleteItems(String tableName,
                            String[] conditionCols,
                            Predicate<RowView> condition) {
        return execWrite(() -> db.deleteItems(tableName, conditionCols, condition));
    }

    private <T> T execRead(IoSupplier<T> action) {
        rwLock.readLock().lock();
        try {
            return action.get();
        } catch (DatabaseException | IllegalArgumentException e) {
            throw new CommandException(e.getMessage(), e);
        } catch (IOException e) {
            throw new CommandException("Ошибка чтения с диска: " + e.getMessage(), e);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    private <T> T execWrite(IoSupplier<T> action) {
        rwLock.writeLock().lock();
        try {
            return action.get();
        } catch (DatabaseException | IllegalArgumentException e) {
            throw new CommandException(e.getMessage(), e);
        } catch (IOException e) {
            throw new CommandException("Ошибка записи на диск: " + e.getMessage(), e);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void execWrite(IoAction action) {
        execWrite(() -> {
            action.run();
            return null;
        });
    }
}
