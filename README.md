API 
1. UI -> Parser
  Raw string(len > 5, stripped())
  Parser -> UI
  Exceptions***&nbsp;
2. Parser -> Command -> Core&nbsp;
     createtable(Table name, schema, Map<String FKcol, Link link>)&nbsp;&nbsp;&nbsp;
   class Link {
   String Name
   enum DeleteBehaviour{CASCADE, SET NULL, RESTRICT}
   enum DeleteBehaviour{CASCADE, SET NULL, RESTRICT}
   }

Два варианта вызова insert
     1. insert(string tableName, Object[] values)
     2. insert(string tableName, Map<Name, Object> ) Остальные будут null 

Два варианта вызова select
    1. getTable(string TableName) 
    2. selectItems(string TableName, string[] finding cols, string[] condition cols, labmda boolean (Object[])) -> Map<String Name, Object[]>

    
Вызов update
     UpdateItems(string TableName, Map<string finding col, Object newValue> , string[] condition cols, labmda boolean (Object[]))
     



Вызов delete
     DeleteItems(string TableName, string[] condition cols, labmda boolean (Object[]))
     

Вызов DropTable
    DropTable(String Table name)

3. UI->Command->Core&nbsp;&nbsp;
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
      getTable(String tableName) -> Object[] Содержит значения в столбцах по порядку как они идут в schema
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
                  vals -> and(gt(vals[0], 2000), eq(vals[1], "abc"))


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
       
