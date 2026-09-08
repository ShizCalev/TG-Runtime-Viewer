package tgrv.store;

public final class GroupStatus {
    public static final GroupStatus NONE = new GroupStatus(false, false);

    public final boolean hidden;
    public final boolean fixed;

    public GroupStatus(boolean hidden, boolean fixed) {
        this.hidden = hidden;
        this.fixed = fixed;
    }

    public boolean isNone() {
        return !hidden && !fixed;
    }
}
