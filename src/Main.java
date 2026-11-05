import core.*;
import ui.MainFrame;

import javax.swing.*;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("data");
        Catalog catalog = new Catalog(root.resolve("catalog.txt"), root);

        // Создать таблицу cars, если её ещё нет
        if (!catalog.hasTable("cars")) {
            catalog.createTable("cars", new ColumnDef[]{
                    ColumnDef.primaryKey("id",    ColumnType.INT),
                    ColumnDef.required  ("brand", ColumnType.STRING),
                    ColumnDef.required  ("price", ColumnType.DOUBLE),
                    ColumnDef.required  ("made",  ColumnType.DATE)
            });
        }

        // Открываем окно. Третий аргумент — dataRoot для JFileChooser.
        SwingUtilities.invokeLater(() ->
                new MainFrame(catalog, "cars", root).setVisible(true));
    }
}