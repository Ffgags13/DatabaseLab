package core;

import java.util.ArrayList;
import java.util.List;

class ForeignKeyRegistry {
    List<Link> all = new ArrayList<>();

    // добавить связь
    void add(String childTable, int childColumn, String parentTable) {
        all.add(new Link(childTable, childColumn, parentTable));
    }

    // ВСЕ связи конкретной таблицы (для INSERT)
    List<Link> linksOf(String childTable) {
        List<Link> result = new ArrayList<>();
        for (Link l : all) {
            if (l.childTable.equals(childTable)) result.add(l);
        }
        return result;
    }

    // КТО ссылается на таблицу (для DELETE)
    List<Link> whoPointsTo(String parentTable) {
        List<Link> result = new ArrayList<>();
        for (Link l : all) {
            if (l.parentTable.equals(parentTable)) result.add(l);
        }
        return result;
    }
}