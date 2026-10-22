package core;

import dao.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public class DataBase {

    private final Repository<Table> repo;

    public DataBase(Repository<Table> repo) { this.repo = repo; }

    private final Map<String, Column> schemaMap= new LinkedHashMap<>();

    public Table getTable(String name) {
        return repo.findById(name)
                .orElseThrow(() -> new IllegalArgumentException("Нет таблицы: " + name));
    }
    public boolean hasTable(String name) { return repo.exists(name); }

    public void createTable(String name, List<Column> schema) {
        if (repo.exists(name)) throw new IllegalStateException("Таблица уже есть: " + name);
        schema.forEach(column -> {this.schemaMap.put(column.getName(),column);});
        repo.save(new Table(name, schemaMap));
    }

    public void dropTable(String name) {
        if (!repo.exists(name)) throw new IllegalStateException("Нет таблицы: " + name);
        repo.delete(name);
    }
}