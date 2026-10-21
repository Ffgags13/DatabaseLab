package core;

import dao.Repository;
import java.util.List;


public class DataBase {

    private final Repository<Table> repo;

    public DataBase(Repository<Table> repo) { this.repo = repo; }

    public Table getTable(String name) {
        return repo.findById(name)
                .orElseThrow(() -> new IllegalArgumentException("Нет таблицы: " + name));
    }
    public boolean hasTable(String name) { return repo.exists(name); }

    public void createTable(String name, List<Column> schema) {
        if (repo.exists(name)) throw new IllegalStateException("Таблица уже есть: " + name);
        repo.save(new Table(name, schema));
    }

    public void dropTable(String name) {
        if (!repo.exists(name)) throw new IllegalStateException("Нет таблицы: " + name);
        repo.delete(name);
    }
}