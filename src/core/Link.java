package core;

public class Link {
    String childTable;    // "orders"
    int    childColumn;   // 1  (это user_id)
    String parentTable;   // "users"

    Link(String childTable, int childColumn, String parentTable) {
        this.childTable  = childTable;
        this.childColumn = childColumn;
        this.parentTable = parentTable;
    }
}
