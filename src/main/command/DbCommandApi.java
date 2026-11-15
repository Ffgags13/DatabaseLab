package main.command;

import main.core.*;
import main.core.domainExceptions.DatabaseException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;

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

    public DbCommandApi(Path dbDir) {
        try {
            this.db = new Database(dbDir);
        } catch (IOException e) {
            throw new CommandException("Ошибка открытия БД: " + dbDir, e);
        }
    }

    public void createTable(String tableName, TableSchema schema, Map<String, Link> links) {
        if (tableName == null || schema == null || !tableName.equals(schema.getTableName())) {
            throw new CommandException("Некорректное имя таблицы или схема");
        }
        execWrite(() -> db.createTable(schema, links));
    }

    public void dropTable(String tableName) {
        execWrite(() -> db.dropTable(tableName));
    }

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

    public Map<String, Object[]> selectItems(String tableName,
                                             String[] findingCols,
                                             String[] conditionCols,
                                             Predicate<RowView> condition) {
        return execRead(() -> db.selectItems(tableName, findingCols, conditionCols, condition));
    }

    public void insert(String tableName, Object[] values) {
        execWrite(() -> db.insert(tableName, values));
    }

    public void insert(String tableName, Map<String, Object> row) {
        execWrite(() -> db.insert(tableName, row));
    }

    public long updateItems(String tableName,
                            Map<String, Object> newValues,
                            String[] conditionCols,
                            Predicate<RowView> condition) {
        return execWrite(() -> db.updateItems(tableName, newValues, conditionCols, condition));
    }

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
