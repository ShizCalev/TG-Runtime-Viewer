package tgrv.ui;

import tgrv.model.RuntimeGroup;
import tgrv.store.GroupStatus;
import tgrv.store.SettingsStore;

import javax.swing.table.AbstractTableModel;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RuntimeTableModel extends AbstractTableModel {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String[] COLUMNS = {"Count", "Rounds", "First Seen", "Last Seen", "Server(s)", "Status", "Message", "Proc", "Source File"};

    private final SettingsStore settingsStore;
    private List<RuntimeGroup> groups = new ArrayList<>();

    public RuntimeTableModel(SettingsStore settingsStore) {
        this.settingsStore = settingsStore;
    }

    public void setGroups(List<RuntimeGroup> groups) {
        this.groups = groups == null ? Collections.emptyList() : groups;
        fireTableDataChanged();
    }

    public RuntimeGroup at(int row) {
        return groups.get(row);
    }

    @Override
    public int getRowCount() {
        return groups.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        switch (columnIndex) {
            case 0:
                return Long.class;
            case 1:
                return Integer.class;
            default:
                return String.class;
        }
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        RuntimeGroup g = groups.get(rowIndex);
        switch (columnIndex) {
            case 0:
                return g.totalCount();
            case 1:
                return g.roundCount();
            case 2:
                return DATE_FMT.format(g.firstSeen());
            case 3:
                return DATE_FMT.format(g.lastSeen());
            case 4:
                return g.serversShortLabel();
            case 5:
                return statusCode(settingsStore.get(g.key()));
            case 6:
                return g.message;
            case 7:
                return g.procName;
            case 8:
                return g.sourceFile;
            default:
                return "";
        }
    }

    public String getFullValueAt(int rowIndex, int columnIndex) {
        RuntimeGroup g = groups.get(rowIndex);
        switch (columnIndex) {
            case 4:
                return g.serversLabel();
            case 5:
                return statusLabel(settingsStore.get(g.key()));
            default:
                return String.valueOf(getValueAt(rowIndex, columnIndex));
        }
    }

    private static String statusCode(GroupStatus s) {
        if (s.hidden && s.fixed) {
            return "H,F";
        }
        if (s.hidden) {
            return "H";
        }
        if (s.fixed) {
            return "F";
        }
        return "";
    }

    private static String statusLabel(GroupStatus s) {
        if (s.hidden && s.fixed) {
            return "Hidden, Fixed";
        }
        if (s.hidden) {
            return "Hidden";
        }
        if (s.fixed) {
            return "Fixed";
        }
        return "";
    }
}
