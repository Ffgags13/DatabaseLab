API 
1. UI -> Parser
  Raw string(len > 5, stripped())
  Parser -> UI
  Exceptions***

2. Parser -> Command -> Core

     createtable(Table name, schema, Map<String FKcol, Link link>)


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
    
string[] condition cols, labmda boolean (Object[]) перейдёт во внешний класс Condition



Вызов update
     UpdateItems(string TableName, Map<string finding col, Object newValue> , string[] condition cols, labmda boolean (Object[]))
     



Вызов delete
     DeleteItems(string TableName, string[] condition cols, labmda boolean (Object[]))
     

Вызов DropTable
    DropTable(String Table name)




 
       
