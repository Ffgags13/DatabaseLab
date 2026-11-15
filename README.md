API 
1. UI -> Parser
  Raw string(len > 5, stripped())
  Parser -> UI
  Exceptions***&nbsp;

3.Parser->Command->Core
4. UI->Command->Core&nbsp;&nbsp;
   insert(string tableName, Object[] values)
       UnknownTableNameException
       IllegalArgumentException("Передан null вместо массива значений");
       AmountValuesException(long n)
       IncorrectType(String typename, String colname) Ожидется typename  в столбце colname
       MaxLengthExceededException(String colname) Слишком длинная строка
       NonUniqueValueException(String colname)
       NullValueException(String colname)
       
       DATEDAYS: Long(INT, BYTE) Количество дней прибавляя указанное количество дней к эпохе (epoch), которая начинается 1 января 1970 года
                 LocalDate
                 String 1970-01-01
       
   insert(string tableName, Map<Name, Object> )
       UnknownTableNameException
       IllegalArgumentException("Передан null вместо словаря значений");
       UnknownColumnName() Обнаружена запись в несуществующий столбец
       IncorrectType(String typename, String colname) Ожидется typename  в столбце colname
       MaxLengthExceededException(String colname) Слишком длинная строка          
       NonUniqueValueException(String colname)          
       NullValueException(String colname)


getSchema(string TableName) -> TableSchema schema

  Два варианта вызова select
    1Вариант-> вся таблица
      getShortSchema(String tableName)-> string ( пример Table: users | id (INT, PRI) | username (STRING, UNI, NOT NULL) | bio (STRING) |)
          UnknownTableNameException
      getTable(String tableName) -> List<Object[]> Содержит значения в столбцах по порядку как они идут в schema
      Внутренний массив Object[] — это одна конкретная строка (запись) таблицы,
      Внешний список List<...> — это коллекция всех таких строк в таблице
          UnknownTableNameException
  2Вариант
       selectItems(string TableName, string[] finding cols, string[] condition cols, labmda boolean (Object[])) -> Map<String Name, Object[]>
            UnknownTableNameException
            UnknownColumnName(String colname) Возврат или условие по несуществующему столбцу
             IncorrectTypeInConditionException(String colname) Неправильный тип в условии по colname столбцу


                  string[] condition cols, labmda boolean (Object[]) 
                  
                  ПРИМЕР:
                  WHERE price > 20000 and name == "abc"
                  string[]{price, name}
                  row -> row.gt(0, 2000) &&  row.eq(1, "abc")


Update 
UpdateItems(string TableName, Map<string finding col, Object newValue> , string[] condition cols, labmda boolean (Object[]))-> Long count выводит количество замен
            UnknownTableNameException
            UnknownColumnName(String colname) Добавление или условие по несуществующему столбцу
            IncorrectTypeInConditionException(String colname) Неправильный тип в условии по colname столбцу
            IncorrectType(String colname) Объект неправильного типа в столбце colname

Delete 
     DeleteItems(string TableName, string[] condition cols, labmda boolean (Object[]))-> Long count выводит количество удалений
            UnknownTableNameException
            UnknownColumnName(String colname) условие по несуществующему столбцу
            IncorrectTypeInConditionException(String colname) Неправильный тип в условии по colname столбцу
        
Вызов DropTable
    DropTable(String Table name)
          UnknownTableNameException


 
   class Link {
   String Name //Имя таблицы на которую ссылается текущая 
   enum UpdateBehaviour{CASCADE, SET NULL, RESTRICT}
   enum DeleteBehaviour{CASCADE, SET NULL, RESTRICT}
   }



Вызов CreateTable
  createtable(Table name, schema, Map<String FKcol, Link link>)
UnknownTableNameException
Выбрасываются при созданиии schema 
    NullableKeyException Первый столбец не может быть nullable т к он PK
    KeyNotUniqueException Первый столбец должен быть unique
    DuplicateColumnNameException имя столбца используется дважды
    UnknownParentTableNameException
    UnknownColumnName(String colname) Внешний ключ содержится в несуществующем столбце
       
CreateIndex
    createIndex(String tableName, String colName) -> void
        UnknownTableNameException(String tableName) // Таблица tableName не найдена
        UnknownColumnName(String colName)         // Столбец colName не существует в таблице
        IndexAlreadyExistsException(String colName) // (Опционально) Индекс по столбцу colName уже создан
