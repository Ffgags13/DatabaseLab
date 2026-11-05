package core;

public record TableStats(long liveRecords, long tombstoneRecords, long fileSizeBytes) {
    public long totalRecords() { return liveRecords + tombstoneRecords; }
    public double tombstoneRatio() {
        long total = totalRecords();
        return total == 0 ? 0.0 : (double) tombstoneRecords / total;
    }
}