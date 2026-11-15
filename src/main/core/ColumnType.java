package main.core;

import main.core.domainExceptions.MaxLengthExceededException;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Objects;

//Constant-Specific Method Implementation

public enum ColumnType {
    INT{
        public Integer requireType(Object item){
            switch (Objects.requireNonNull(item)){
                case Integer i -> {
                    return i;
                }
                case Long l -> {
                    //Выбрасывает ArithmeticException если превышен maxint
                    return Math.toIntExact(l);
                }
                default -> throw new IllegalArgumentException(item.getClass().getSimpleName());
            }
        }
        public void binaryWrite(DataOutputStream outStream, Object item){
            try {
                outStream.writeInt(this.requireType(item));
            }
            catch (IOException io){
                io.printStackTrace();
            }
        }
        public Integer binaryRead(DataInputStream inStream){
            try {
                return inStream.readInt();
            }
            catch (EOFException io){
                throw new IllegalStateException("Не хватило данных во входном потоке");
            }
            catch (IOException e) {
                throw new IllegalStateException("Неожиданная ошибка при чтении из памяти", e);
            }
        }
        protected int compareValues(Object a, Object b) {
            return Integer.compare(requireType(a), requireType(b));
        }
    },
    BOOLEAN{
        public Boolean requireType(Object item){
            if(Objects.requireNonNull(item) instanceof Boolean b){
                return b;}
            throw new IllegalArgumentException(item.getClass().getSimpleName());
        }
        public void binaryWrite(DataOutputStream outStream, Object item){
            try {
                outStream.writeBoolean(this.requireType(item));
            }
            catch (IOException io){
                io.printStackTrace();
            }
        }
        public Boolean binaryRead(DataInputStream inStream){
            try {
                return inStream.readBoolean();
            }
            catch (EOFException io){
                throw new IllegalStateException("Не хватило данных во входном потоке");
            }
            catch (IOException e) {
                throw new IllegalStateException("Неожиданная ошибка при чтении из памяти", e);
            }
        }

        protected int compareValues(Object a, Object b) {
            return Boolean.compare(requireType(a), requireType(b));
        }
    },
    DOUBLE{
        public Double requireType(Object item){
            if (Objects.requireNonNull(item) instanceof Number n) {
                return n.doubleValue();
            }
            throw new IllegalArgumentException(item.getClass().getSimpleName());
        }

        public void binaryWrite(DataOutputStream outStream, Object item){
            try {
                outStream.writeDouble(this.requireType(item));
            }
            catch (IOException io){
                io.printStackTrace();
            }
        }
        public Double binaryRead(DataInputStream inStream){
            try {
                return inStream.readDouble();
            }
            catch (EOFException io){
                throw new IllegalStateException("Не хватило данных во входном потоке");
            }
            catch (IOException e) {
                throw new IllegalStateException("Неожиданная ошибка при чтении из памяти", e);
            }
        }
        protected int compareValues(Object a, Object b) {
            return Double.compare(requireType(a), requireType(b));
        }
    },
    //Обработать MaxLengthExceededException в клиенте
    STRING {
        public byte[] requireType(Object item)  throws MaxLengthExceededException{
            if (Objects.requireNonNull(item) instanceof CharSequence) {
                byte[] itemBytes = item.toString().getBytes(StandardCharsets.UTF_8);
                if (itemBytes.length > Short.MAX_VALUE) {
                    throw new MaxLengthExceededException("too long string");
                }
                return itemBytes;
            }
            throw new IllegalArgumentException(item.getClass().getSimpleName());
        }

        public void binaryWrite(DataOutputStream outStream, Object item){
            try {
                byte [] data = this.requireType(item);
                outStream.writeShort(data.length);
                outStream.write(data);
            }
            catch (IOException io){
                io.printStackTrace();
            }
        }

        public String binaryRead(DataInputStream inStream){
            try {
                short length = inStream.readShort();
                byte [] data = new byte[length];
                inStream.readFully(data);
                return new String(data, StandardCharsets.UTF_8);
            }
            catch (EOFException io){
                throw new IllegalStateException("Не хватило данных во входном потоке");
            }
            catch (IOException e) {
                throw new IllegalStateException("Неожиданная ошибка при чтении из памяти", e);
            }
        }

        protected int compareValues(Object a, Object b) {
            if (!(a instanceof CharSequence) || !(b instanceof CharSequence)) {
                throw new IllegalArgumentException("Ожидались строки для сравнения");
            }
            return a.toString().compareTo(b.toString());
        }
    },
    DATEDAYS {
        public Long requireType(Object item){
            return switch (Objects.requireNonNull(item)){
                case Integer intItem -> Long.valueOf(intItem);
                case Long intItem -> intItem;
                case Byte intItem -> Long.valueOf(intItem);
                case String str -> LocalDate.parse(str).toEpochDay();
                case LocalDate date -> date.toEpochDay();
                default -> throw new IllegalArgumentException(item.getClass().getSimpleName());
            };
        }
        public void binaryWrite(DataOutputStream outStream, Object item){
            try {
                outStream.writeLong(this.requireType(item));
            }
            catch (IOException io){
                io.printStackTrace();
            }
        }
        public LocalDate binaryRead(DataInputStream inStream){
            try {
                return LocalDate.ofEpochDay(inStream.readLong());
            }
            catch (EOFException io){
                throw new IllegalStateException("Не хватило данных во входном потоке");
            }
            catch (IOException e) {
                throw new IllegalStateException("Неожиданная ошибка при чтении из памяти", e);
            }
        }

        @Override
        protected int compareValues(Object a, Object b) {
            return Long.compare(requireType(a), requireType(b));
        }
    };
    //Метод проверяет и приводит типы если возможно к виду удобному для сериализации
    public abstract Object requireType(Object item);

    //Метод преобразующий тип полученный после requireType в бинарный поток

    public abstract void binaryWrite(DataOutputStream outStream, Object item);

    //Метод читает объект из бинарного потока
    public abstract Object binaryRead(DataInputStream inStream);

    //сравнение объектов
    protected abstract int compareValues(Object a, Object b);

    public boolean eq(Object a, Object b) {
        if (a == null || b == null) {
            return false;
        }
        return compareValues(a, b) == 0;
    }

    // Проверка "строго больше" (a > b)
    public boolean gt(Object a, Object b) {
        if (a == null || b == null) {
            return false;
        }
        return compareValues(a, b) > 0;
    }

    // Проверка "строго меньше" (a < b)
    public boolean lt(Object a, Object b) {
        if (a == null || b == null) {
            return false;
        }
        return compareValues(a, b) < 0;
    }
}
