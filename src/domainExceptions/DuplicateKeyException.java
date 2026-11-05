package domainExceptions;

/**
 * Нарушено уникальное ограничение: значение уже есть в индексе/PK.
 */
public class DuplicateKeyException extends RuntimeException {

  private final String indexName;
  private final String column;
  private final Object key;

  public DuplicateKeyException(String indexName, String column, Object key) {
    super("Нарушено уникальное ограничение «" + indexName + "»: ключ "
            + key + " уже есть в колонке «" + column + "»");
    this.indexName = indexName;
    this.column = column;
    this.key = key;
  }

  public String indexName() { return indexName; }
  public String column() { return column; }
  public Object key() { return key; }

  @Override
  public String toString() {
    return "DuplicateKeyException{indexName=" + indexName
            + ", column=" + column + ", key=" + key + '}';
  }
}