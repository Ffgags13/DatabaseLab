## Диаграмма классов

```mermaid
classDiagram
    class DbCommandApi {
        -Database db
        -ReentrantReadWriteLock rwLock
        +createTable(tableName, schema, links)
        +dropTable(tableName)
        +createIndex(tableName, colName)
        +insert(tableName, row)
        +selectItems(tableName, findingCols, conditionCols, condition)
        +updateItems(tableName, newValues, conditionCols, condition)
        +deleteItems(tableName, conditionCols, condition)
    }

    class CommandException {
        <<RuntimeException>>
    }

    class Database {
        -Path dbDir
        -Map~String, Table~ tables
        -Map~String, List~ChildRef~~ incomingLinks
        +createTable(schema, links)
        +dropTable(tableName)
        +insert(tableName, row)
        +selectItems(tableName, findingCols, conditionCols, condition)
        +updateItems(tableName, newValues, conditionCols, condition)
        +deleteItems(tableName, conditionCols, condition)
    }

    class Table {
        -Path dataPath
        -Path schemaPath
        -TableSchema schema
        -Map~Object, Long~ primaryIndex
        -Map~String, Map~Object, Set~Object~~~ secondaryIndexes
        +insert(row)
        +selectByPk(pkValue)
        +selectItems(findingCols, conditionCols, condition)
        +updateItems(newValues, conditionCols, condition)
        +deleteItems(conditionCols, condition)
        +createIndex(colName)
        +compact()
    }

    class TableSchema {
        -String tableName
        -Map~String, ColumnDef~ columnsByName
        -Set~String~ indexedColumns
        -Map~String, Link~ foreignKeys
        +pkColumn() ColumnDef
        +getColumn(colName) ColumnDef
        +showSchema() String
    }

    class ColumnDef {
        <<record>>
        +String name
        +ColumnType type
        +boolean nullable
        +boolean isUniquie
    }

    class ColumnType {
        <<enumeration>>
        INT
        BOOLEAN
        DOUBLE
        STRING
        DATEDAYS
    }

    class Link {
        -String parentTableName
        -Behaviour onUpdate
        -Behaviour onDelete
        +Link(parentTableName, onUpdate, onDelete)
        +getParentTableName() String
        +getOnUpdate() Behaviour
        +getOnDelete() Behaviour
    }
    class Behaviour {
        <<enumeration>>
        CASCADE
        SET_NULL
        RESTRICT
    }

    Link --> Behaviour : использует правила

    class RecordCodec {
        <<utility>>
        +write(file, schema, row, tombstone)\$ long
        +read(file, schema, offset)\$ Map
        +markDeleted(file, offset)\$ void
        +compactWithIndex(dataPath, schema)\$ Map
    }

    class RowView {
        -Object[] values
        -ColumnType[] types
        -String[] colNames
        +eq(index, target) boolean
        +gt(index, target) boolean
        +lt(index, target) boolean
        +isNull(index) boolean
    }

    DbCommandApi --> Database : управляет
    DbCommandApi ..> CommandException : выбрасывает
    Database "1" *-- "*" Table : содержит
    Table "1" *-- "1" TableSchema : хранит схему
    TableSchema "1" *-- "*" ColumnDef : состоит из
    TableSchema "1" o-- "*" Link : внешние ключи
    ColumnDef --> ColumnType : тип поля
    Table ..> RecordCodec : читает / пишет
    Table ..> RowView : фильтрация
```



## Диаграммы последовательностей

### 1. Вставка записи (`INSERT`)
Показывает захват блокировки записи, проверку внешнего ключа в родительской таблице, запись байтов в `.dat` и обновление индексов.

```mermaid
sequenceDiagram
    actor Client
    participant API as DbCommandApi
    participant DB as Database
    participant Tbl as Table
    participant Codec as RecordCodec

    Client->>API: insert(tableName, row)
    API->>API: writeLock.lock()
    API->>DB: insert(tableName, row)
    DB->>DB: checkParentKeysExist(schema, row)
    DB->>Tbl: insert(row)
    Tbl->>Tbl: проверка уникальности PK
    Tbl->>Codec: write(raf, schema, row, false)
    Codec-->>Tbl: offset
    Tbl->>Tbl: обновление primaryIndex и secondaryIndexes
    API->>API: writeLock.unlock()
    API-->>Client: void
```

---

### 2. Выборка записей (`SELECT`)
Показывает разделяемую блокировку чтения (`readLock`), чтение строк с диска и фильтрацию через `RowView`.

```mermaid
sequenceDiagram
    actor Client
    participant API as DbCommandApi
    participant DB as Database
    participant Tbl as Table
    participant Codec as RecordCodec
    participant View as RowView

    Client->>API: selectItems(tableName, cols, condCols, predicate)
    API->>API: readLock.lock()
    API->>DB: selectItems(...)
    DB->>Tbl: selectItems(...)
    loop По записям или индексам
        Tbl->>Codec: read(raf, schema, offset)
        Codec-->>Tbl: row (Map)
        Tbl->>View: new RowView(values, types, colNames)
        Tbl->>View: predicate.test(view)
        View-->>Tbl: true / false
    end
    Tbl-->>DB: Map<String, Object[]>
    DB-->>API: результат выборки
    API->>API: readLock.unlock()
    API-->>Client: результат выборки
```

---

### 3. Обновление записей (`UPDATE`)
Показывает append-only обновление: старая версия помечается как `tombstone`, новая дописывается в конец файла, а при необходимости запускается `compact()`.

```mermaid
sequenceDiagram
    actor Client
    participant API as DbCommandApi
    participant DB as Database
    participant Tbl as Table
    participant Codec as RecordCodec

    Client->>API: updateItems(tableName, newValues, condCols, predicate)
    API->>API: writeLock.lock()
    API->>DB: updateItems(...)
    DB->>DB: checkParentKeysExist() и проверка RESTRICT
    DB->>Tbl: updateItems(...)
    loop По подходящим записям
        Tbl->>Codec: markDeleted(raf, oldOffset)
        Tbl->>Codec: write(raf, schema, updatedRow, false)
        Codec-->>Tbl: newOffset
        Tbl->>Tbl: обновление индексов
    end
    opt Доля удалённых записей >= 30%
        Tbl->>Codec: compactWithIndex(dataPath, schema)
    end
    Tbl-->>DB: updatedCount
    DB-->>API: updatedCount
    API->>API: writeLock.unlock()
    API-->>Client: updatedCount
```

---

### 4. Удаление записей (`DELETE`)
Показывает проверку ограничений внешних ключей (`RESTRICT`), логическое удаление записей (`tombstone = 1`) и каскадную обработку зависимых таблиц (`CASCADE` / `SET_NULL`).

```mermaid
sequenceDiagram
    actor Client
    participant API as DbCommandApi
    participant DB as Database
    participant Tbl as Table
    participant Codec as RecordCodec

    Client->>API: deleteItems(tableName, condCols, predicate)
    API->>API: writeLock.lock()
    API->>DB: deleteItems(...)
    DB->>DB: ensureNoRestrictBlocks()
    DB->>Tbl: deleteItems(...)
    loop По подходящим записям
        Tbl->>Codec: markDeleted(raf, offset)
        Tbl->>Tbl: удаление из primaryIndex и secondaryIndexes
    end
    Tbl-->>DB: deletedCount
    DB->>DB: каскадное удаление / SET_NULL в дочерних таблицах
    DB-->>API: deletedCount
    API->>API: writeLock.unlock()
    API-->>Client: deletedCount
```
