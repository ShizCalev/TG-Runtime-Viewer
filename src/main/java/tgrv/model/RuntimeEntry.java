package tgrv.model;

public final class RuntimeEntry {
    public final String message;
    public final String procName;
    public final String sourceFile;
    public final String src;
    public final String srcLoc;
    public final String usr;
    public final long count;

    public RuntimeEntry(String message, String procName, String sourceFile, String src, String srcLoc, String usr, long count) {
        this.message = message == null ? "" : message;
        this.procName = procName == null ? "" : procName;
        this.sourceFile = sourceFile == null ? "" : sourceFile;
        this.src = src;
        this.srcLoc = srcLoc;
        this.usr = usr;
        this.count = count;
    }

    private static final char SEP = 0x01;

    public String groupKey() {
        return message + SEP + procName + SEP + sourceFile;
    }
}
