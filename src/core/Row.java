package core;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

public class Row implements Serializable {

    private final Map<String, Object> data;

    public Row(){
        this.data = new LinkedHashMap<>();
    }

    public Row(Map<String, Object> data){
        this.data = new LinkedHashMap<>(data);
    }

    public Map<String, Object> getData() {
        return data;
    }

    public Object getValue(String column){
        return data.get(column);
    }

    public void setValue(String column, Object value){
        data.put(column, value);

    }

    @Override
    public String toString()
    {
        return data.values().toString();
    }
}

