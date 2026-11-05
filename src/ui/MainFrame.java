package ui;

import core.*;
import domainExceptions.ColumnNotFoundException;
import domainExceptions.DuplicateKeyException;
import domainExceptions.NullableKeyException;
import index.Index;
import index.IndexKind;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MainFrame extends JFrame {

    private Catalog catalog;
    private String tableName;
    private TableSchema schema;
    private final Path dataRoot;

    private DefaultTableModel model;
    private final JTable jtable = new JTable();
    private final JLabel status = new JLabel("Готово");

    public MainFrame(Catalog catalog, String tableName, Path dataRoot) {
        super("Файловая БД");
        this.dataRoot = dataRoot;
        this.catalog = catalog;

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1000, 600);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        add(toolbar(), BorderLayout.NORTH);
        add(new JScrollPane(jtable), BorderLayout.CENTER);
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(status, BorderLayout.SOUTH);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                try { if (catalog != null) catalog.close(); } catch (IOException ignored) {}
            }
        });

        try {
            switchTo(catalog, tableName);
        } catch (Exception ex) {
            showError(ex);
        }
    }

    // ───────── Toolbar ─────────

    private JToolBar toolbar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.add(btn("Создать",         this::onCreate));
        tb.add(btn("Открыть",         this::onOpen));
        tb.add(btn("Сохранить",       this::onSave));
        tb.add(btn("Очистить",        this::onClear));
        tb.add(btn("Сжать файл",      this::onCompact));
        tb.addSeparator();
        tb.add(btn("Добавить",        this::onAdd));
        tb.add(btn("Изменить",        this::onEdit));
        tb.add(btn("Удалить",         this::onDelete));
        tb.add(btn("Поиск",           this::onSearch));
        tb.add(btn("Показать все",    this::refresh));
        tb.addSeparator();
        tb.add(btn("Создать индекс",  this::onCreateIndex));
        tb.add(btn("Удалить индекс",  this::onDropIndex));
        tb.add(btn("Индексы",         this::onListIndexes));
        tb.addSeparator();
        tb.add(btn("Backup",          this::onBackup));
        tb.add(btn("Restore",         this::onRestore));
        tb.add(btn("Экспорт .xlsx",   this::onExport));
        return tb;
    }

    private JButton btn(String text, Run r) {
        JButton b = new JButton(text);
        b.addActionListener(e -> {
            try { r.run(); }
            catch (Exception ex) { showError(ex); }
        });
        return b;
    }

    // ───────── Переключение таблицы ─────────

    private void switchTo(Catalog newCatalog, String newTableName) throws Exception {
        if (newCatalog == null) throw new IllegalArgumentException("catalog == null");
        if (!newCatalog.hasTable(newTableName)) {
            throw new IllegalArgumentException("В каталоге нет таблицы: " + newTableName);
        }
        this.catalog = newCatalog;
        this.tableName = newTableName;
        this.schema = newCatalog.getSchema(newTableName);

        ColumnDef[] cols = schema.getColumns();
        String[] names = new String[cols.length];
        for (int i = 0; i < cols.length; i++) names[i] = cols[i].name();

        this.model = new DefaultTableModel(names, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        jtable.setModel(model);
        jtable.setAutoCreateRowSorter(true);

        setTitle("Файловая БД — " + newTableName);
        refresh();
    }

    // ───────── Файловые действия ─────────

    private void onCreate() throws Exception {
        String name = JOptionPane.showInputDialog(this, "Имя новой таблицы:");
        if (name == null || name.isBlank()) return;
        name = name.trim();

        if (catalog.hasTable(name)) {
            info("Таблица уже существует: " + name);
            return;
        }

        ColumnDef[] cols = new ColumnDef[]{
                ColumnDef.primaryKey("id",    ColumnType.INT),
                ColumnDef.required  ("brand", ColumnType.STRING),
                ColumnDef.required  ("price", ColumnType.DOUBLE),
                ColumnDef.required  ("made",  ColumnType.DATE)
        };
        catalog.createTable(name, cols);
        switchTo(catalog, name);
    }

    private void onOpen() throws Exception {
        JFileChooser ch = new JFileChooser(dataRoot.toFile());
        ch.setDialogTitle("Открыть файл БД");
        ch.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Файлы БД (*.dat)", "dat"));
        if (ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        Path selected = ch.getSelectedFile().toPath();
        String fileName = selected.getFileName().toString();
        String baseName = fileName.endsWith(".dat")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;

        // ───── Случай 1: файл уже есть в текущем Catalog ─────
        if (catalog.hasTable(baseName)
                && catalog.getTable(baseName).getDataFile().toAbsolutePath()
                .equals(selected.toAbsolutePath())) {
            switchTo(catalog, baseName);
            info("Открыта таблица: " + baseName);
            return;
        }

        // ───── Случай 2: файл вне каталога — спрашиваем схему ─────
        if (!confirm("Файл вне каталога. Открыть как таблицу '" + baseName
                + "' со схемой cars (id, brand, price, made)?")) {
            return;
        }

        // Закрываем старый каталог, чтобы освободить .dat
        if (catalog != null) catalog.close();

        // Создаём временный каталог в памяти и копируем туда файл
        Path tmpRoot = Files.createTempDirectory("filedb-open");
        Catalog tmp = new Catalog(tmpRoot.resolve("catalog.txt"), tmpRoot);

        ColumnDef[] cols = new ColumnDef[]{
                ColumnDef.primaryKey("id",    ColumnType.INT),
                ColumnDef.required  ("brand", ColumnType.STRING),
                ColumnDef.required  ("price", ColumnType.DOUBLE),
                ColumnDef.required  ("made",  ColumnType.DATE)
        };
        tmp.createTable(baseName, cols);

        // Подменяем пустой .dat на выбранный
        Path target = tmp.getTable(baseName).getDataFile();
        tmp.close();
        Files.copy(selected, target, StandardCopyOption.REPLACE_EXISTING);

        // Переоткрываем каталог с уже готовым .dat
        tmp = new Catalog(tmpRoot.resolve("catalog.txt"), tmpRoot);

        this.catalog = tmp;
        switchTo(tmp, baseName);
        info("Открыт внешний файл: " + selected
                + "\nСкопирован во временный каталог: " + tmpRoot);
    }

    private void onSave() throws Exception {
        Table t = catalog.getTable(tableName);
        info("Все изменения уже сохранены в " + t.getDataFile());
        refresh();
    }

    private void onClear() throws Exception {
        if (!confirm("Удалить все живые записи?")) return;
        Table t = catalog.getTable(tableName);
        int n = t.delete(row -> true);
        info("Удалено: " + n);
        refresh();
    }

    private void onCompact() throws Exception {
        Table t = catalog.getTable(tableName);
        int removed = t.compact();
        info("Сжато. Удалено tombstone-записей: " + removed);
        refresh();
    }

    // ───────── CRUD ─────────

    private void onAdd() throws Exception {
        Table t = catalog.getTable(tableName);
        Map<String, Object> row = showEditDialog(null);
        if (row == null) return;
        t.insert(row);
        refresh();
    }

    private void onEdit() throws Exception {
        Table t = catalog.getTable(tableName);
        int r = jtable.getSelectedRow();
        if (r < 0) { info("Выберите строку"); return; }
        if (jtable.getRowSorter() != null) r = jtable.convertRowIndexToModel(r);

        String pkName = schema.pkColumn().name();
        Object oldPk = model.getValueAt(r, 0);

        Map<String, Object> current = t.select(new Condition.Eq(pkName, oldPk))
                .stream().findFirst().orElse(null);
        if (current == null) { info("Запись не найдена"); return; }

        Map<String, Object> updated = showEditDialog(current);
        if (updated == null) return;

        Map<String, Object> patch = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : updated.entrySet()) {
            if (!Objects.equals(current.get(e.getKey()), e.getValue())) {
                patch.put(e.getKey(), e.getValue());
            }
        }
        if (patch.isEmpty()) { info("Ничего не изменилось"); return; }

        int n = t.update(new Condition.Eq(pkName, oldPk), patch);
        info("Обновлено: " + n);
        refresh();
    }

    private void onDelete() throws Exception {
        Table t = catalog.getTable(tableName);
        int r = jtable.getSelectedRow();
        if (r < 0) { info("Выберите строку"); return; }
        if (jtable.getRowSorter() != null) r = jtable.convertRowIndexToModel(r);

        String pkName = schema.pkColumn().name();
        Object pk = model.getValueAt(r, 0);
        if (!confirm("Удалить запись с ключом " + pk + "?")) return;

        int n = t.delete(new Condition.Eq(pkName, pk));
        info("Удалено: " + n);
        refresh();
    }

    // ───────── Поиск ─────────

    private void onSearch() throws Exception {
        Table t = catalog.getTable(tableName);
        String[] fields = columnNames();

        String field = (String) JOptionPane.showInputDialog(this,
                "Поле:", "Поиск", JOptionPane.QUESTION_MESSAGE,
                null, fields, fields[0]);
        if (field == null) return;

        String[] ops = {"=", ">", ">=", "<", "<="};
        String op = (String) JOptionPane.showInputDialog(this,
                "Оператор:", "Поиск", JOptionPane.QUESTION_MESSAGE,
                null, ops, ops[0]);
        if (op == null) return;

        String raw = JOptionPane.showInputDialog(this, "Значение:");
        if (raw == null) return;
        Object value = coerce(field, raw);

        Condition cond = switch (op) {
            case "="  -> new Condition.Eq(field, value);
            case ">"  -> new Condition.Gt(field, value);
            case ">=" -> new Condition.Ge(field, value);
            case "<"  -> new Condition.Lt(field, value);
            case "<=" -> new Condition.Le(field, value);
            default   -> throw new IllegalArgumentException("?");
        };

        List<Map<String, Object>> res = t.select(cond);
        fill(res);
        status.setText("Найдено: " + res.size() + "  (" + field + " " + op + " " + raw + ")");
    }

    // ───────── Индексы ─────────

    private void onCreateIndex() throws Exception {
        String[] fields = columnNames();
        String col = (String) JOptionPane.showInputDialog(this,
                "Колонка:", "Создать индекс",
                JOptionPane.QUESTION_MESSAGE, null, fields, fields[1]);
        if (col == null) return;

        String[] kinds = {"HASH", "TREE"};
        String kindS = (String) JOptionPane.showInputDialog(this,
                "Вид:", "Создать индекс",
                JOptionPane.QUESTION_MESSAGE, null, kinds, kinds[0]);
        if (kindS == null) return;

        boolean unique = JOptionPane.showConfirmDialog(this,
                "Уникальный?", "Создать индекс",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;

        catalog.createIndex(tableName, col, IndexKind.valueOf(kindS), unique);
        info("Индекс создан: " + col + " (" + kindS + (unique ? ", unique" : "") + ")");
    }

    private void onDropIndex() throws Exception {
        Table t = catalog.getTable(tableName);
        List<Index> idx = t.getIndexes();
        if (idx.isEmpty()) { info("Индексов нет"); return; }

        String[] cols = idx.stream().map(Index::column).toArray(String[]::new);
        String col = (String) JOptionPane.showInputDialog(this,
                "Индекс на колонке:", "Удалить индекс",
                JOptionPane.QUESTION_MESSAGE, null, cols, cols[0]);
        if (col == null) return;

        if (col.equals(schema.pkColumn().name())) {
            info("PK-индекс удалить нельзя");
            return;
        }
        catalog.dropIndex(tableName, col);
        info("Индекс удалён: " + col);
    }

    private void onListIndexes() throws Exception {
        Table t = catalog.getTable(tableName);
        StringBuilder sb = new StringBuilder("Индексы таблицы " + tableName + ":\n");
        for (Index ix : t.getIndexes()) {
            sb.append("• ").append(ix.column())
                    .append(" (").append(ix.isUnique() ? "unique" : "non-unique").append(")\n");
        }
        info(sb.toString());
    }

    // ───────── Backup / Restore / Export ─────────

    private void onBackup() throws Exception {
        Table t = catalog.getTable(tableName);
        JFileChooser ch = new JFileChooser(dataRoot.toFile());
        ch.setSelectedFile(new java.io.File(tableName + ".bak"));
        if (ch.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Files.copy(t.getDataFile(), ch.getSelectedFile().toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        info("Backup: " + ch.getSelectedFile());
    }

    private void onRestore() throws Exception {
        JFileChooser ch = new JFileChooser(dataRoot.toFile());
        if (ch.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path src = ch.getSelectedFile().toPath();

        Path data = catalog.getTable(tableName).getDataFile();
        catalog.close();

        Files.copy(src, data, StandardCopyOption.REPLACE_EXISTING);

        // Переоткрываем каталог
        catalog = new Catalog(dataRoot.resolve("catalog.txt"), dataRoot);
        if (!catalog.hasTable(tableName)) {
            info("Файл восстановлен, но таблица " + tableName + " исчезла из каталога.");
            return;
        }
        switchTo(catalog, tableName);
        info("Восстановлено: " + src);
    }

    private void onExport() throws Exception {
        Table t = catalog.getTable(tableName);
        JFileChooser ch = new JFileChooser(dataRoot.toFile());
        ch.setSelectedFile(new java.io.File(tableName + ".xlsx"));
        if (ch.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        List<Map<String, Object>> rows = t.selectAll();
        exportXlsx(rows, ch.getSelectedFile().toPath());
        info("Экспортировано: " + ch.getSelectedFile());
    }

    private void exportXlsx(List<Map<String, Object>> rows, Path out) throws IOException {
        try {
            Class.forName("org.apache.poi.xssf.usermodel.XSSFWorkbook");
        } catch (ClassNotFoundException e) {
            // Fallback: CSV
            StringBuilder sb = new StringBuilder();
            ColumnDef[] cols = schema.getColumns();
            for (int i = 0; i < cols.length; i++) {
                if (i > 0) sb.append(';');
                sb.append(cols[i].name());
            }
            sb.append('\n');
            for (Map<String, Object> row : rows) {
                for (int i = 0; i < cols.length; i++) {
                    if (i > 0) sb.append(';');
                    Object v = row.get(cols[i].name());
                    sb.append(v == null ? "" : v.toString());
                }
                sb.append('\n');
            }
            Files.writeString(out, sb.toString());
            return;
        }
    }

    // ───────── Refresh ─────────

    private void refresh() throws Exception {
        if (catalog == null || tableName == null) {
            if (model != null) model.setRowCount(0);
            status.setText("Нет таблицы");
            return;
        }
        Table t = catalog.getTable(tableName);
        fill(t.selectAll());
        TableStats st = t.getStats();
        status.setText("Таблица: " + tableName
                + "  |  файл: " + t.getDataFile()
                + "  |  live=" + st.liveRecords()
                + ", tomb=" + st.tombstoneRecords()
                + ", size=" + st.fileSizeBytes() + "B"
                + (t.needsCompaction() ? "  ⚠ пора сжать" : ""));
    }

    private void fill(List<Map<String, Object>> rows) {
        model.setRowCount(0);
        ColumnDef[] cols = schema.getColumns();
        for (Map<String, Object> row : rows) {
            Object[] vals = new Object[cols.length];
            for (int i = 0; i < cols.length; i++)
                vals[i] = row.get(cols[i].name());
            model.addRow(vals);
        }
    }

    // ───────── Диалог ─────────

    private Map<String, Object> showEditDialog(Map<String, Object> base) {
        ColumnDef[] cols = schema.getColumns();
        JTextField[] fields = new JTextField[cols.length];

        JPanel p = new JPanel(new GridLayout(0, 2, 4, 4));
        for (int i = 0; i < cols.length; i++) {
            ColumnDef c = cols[i];
            boolean isPk = (i == 0);
            Object cur = base == null ? null : base.get(c.name());
            String def = cur == null
                    ? (c.type() == ColumnType.DATE ? LocalDate.now().toString() : "")
                    : cur.toString();
            fields[i] = new JTextField(def);
            if (isPk && base != null) fields[i].setEditable(false);
            p.add(new JLabel(c.name() + " (" + c.type() + "):"));
            p.add(fields[i]);
        }

        int res = JOptionPane.showConfirmDialog(this, p,
                base == null ? "Добавление" : "Редактирование",
                JOptionPane.OK_CANCEL_OPTION);
        if (res != JOptionPane.OK_OPTION) return null;

        try {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < cols.length; i++)
                row.put(cols[i].name(), coerce(cols[i], fields[i].getText().trim()));
            return row;
        } catch (Exception ex) {
            showError(ex);
            return null;
        }
    }

    // ───────── Helpers ─────────

    private String[] columnNames() {
        ColumnDef[] cols = schema.getColumns();
        String[] names = new String[cols.length];
        for (int i = 0; i < cols.length; i++) names[i] = cols[i].name();
        return names;
    }

    private Object coerce(String field, String raw) {
        return coerce(schema.getColumn(field), raw);
    }

    private Object coerce(ColumnDef def, String raw) {
        if (raw == null || raw.isEmpty()) return null;
        return switch (def.type()) {
            case INT     -> Integer.parseInt(raw);
            case DOUBLE  -> Double.parseDouble(raw);
            case BOOLEAN -> Boolean.parseBoolean(raw);
            case DATE    -> LocalDate.parse(raw);
            case STRING  -> raw;
        };
    }

    private boolean confirm(String msg) {
        return JOptionPane.showConfirmDialog(this, msg, "Подтверждение",
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    private void info(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Инфо",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void showError(Exception ex) {
        JOptionPane.showMessageDialog(this,
                ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                "Ошибка", JOptionPane.ERROR_MESSAGE);
    }

    @FunctionalInterface
    interface Run { void run() throws Exception; }
}