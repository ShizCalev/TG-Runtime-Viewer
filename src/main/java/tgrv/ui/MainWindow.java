package tgrv.ui;

import tgrv.Version;
import tgrv.model.LogServer;
import tgrv.model.RuntimeGroup;
import tgrv.service.Aggregator;
import tgrv.service.LogExtractor;
import tgrv.service.SyncService;
import tgrv.store.GroupStatus;
import tgrv.store.SettingsStore;
import tgrv.store.RoundCache;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class MainWindow extends JFrame {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter URL_DAY_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private static final Color BG = new Color(24, 24, 24);
    private static final Color BG_ALT = new Color(32, 33, 34);
    private static final Color FG = new Color(220, 220, 220);
    private static final Color FG_DIM = new Color(160, 160, 160);
    private static final Color GRID = new Color(55, 55, 55);
    private static final Color SELECTION_BG = new Color(63, 100, 148);
    private static final Color SELECTION_FG = Color.WHITE;
    private static final Color ERROR_FG = new Color(255, 120, 120);

    private static final Color ROW_HIDDEN_BG = new Color(80, 32, 32);
    private static final Color ROW_FIXED_BG = new Color(28, 68, 36);
    private static final Color ROW_HIDDEN_AND_FIXED_BG = new Color(72, 58, 24);
    private static final Color ROW_STRIPE_BG = new Color(21, 22, 23);

    private final RoundCache cache;
    private final SyncService syncService;
    private final SettingsStore settingsStore;
    private final List<LogServer> servers;

    private final RuntimeTableModel tableModel;
    private final JTable table;
    private final JTextPane detailArea = new JTextPane();
    private final JLabel statusLabel = new JLabel(" ");
    private final JSpinner windowDaysSpinner = new JSpinner(new SpinnerNumberModel(SyncService.RETENTION_DAYS, 1, SyncService.RETENTION_DAYS, 1));
    private final PlaceholderTextField filterField = new PlaceholderTextField(30);
    private final JComboBox<String> hiddenFilterSelector = new JComboBox<>(new String[]{"With Hidden", "Only Hidden", "No Hidden"});
    private final JComboBox<String> fixedFilterSelector = new JComboBox<>(new String[]{"With Fixed", "Only Fixed", "No Fixed"});
    private final JComboBox<String> serverSelector;
    private final JComboBox<String> nullFilterSelector = new JComboBox<>(new String[]{"With Nulls", "Only Nulls", "No Nulls"});

    private static final String ALL_SERVERS = "All Servers";

    private volatile Instant lastSyncTime;
    private final Timer clockTimer;
    private JSplitPane centerSplit;
    private int minDetailHeight;
    private Rectangle lastNormalBounds;
    private double uiScale;
    private int detailFontSize;
    private SimpleAttributeSet DETAIL_LABEL_STYLE;
    private SimpleAttributeSet DETAIL_PLAIN_STYLE;
    private Map<String, SimpleAttributeSet> TYPE_PATH_STYLES;
    private SimpleAttributeSet TYPE_PATH_DEFAULT_STYLE;
    private SimpleAttributeSet NULL_DOT_STYLE;
    private SimpleAttributeSet TYPE_MISMATCH_STYLE;
    private final List<Runnable> exampleDialogRescalers = new ArrayList<>();
    private final int[] pendingUiScalePercent = {100};
    private final Timer uiScaleDebounceTimer = new Timer(150, e -> applyUiScale(pendingUiScalePercent[0]));

    public MainWindow(RoundCache cache, SyncService syncService, SettingsStore settingsStore, List<LogServer> servers) {
        super("TG Runtime Viewer v" + Version.CURRENT);
        this.cache = cache;
        this.syncService = syncService;
        this.settingsStore = settingsStore;
        this.servers = servers;
        Integer savedScalePercent = settingsStore.getUiScalePercent();
        this.uiScale = (savedScalePercent != null ? savedScalePercent : 100) / 100.0;
        initDetailStyles();
        this.lastSyncTime = settingsStore.getLastCheckTime();
        this.tableModel = new RuntimeTableModel(settingsStore);
        this.table = new JTable(tableModel) {
            @Override
            public String getToolTipText(MouseEvent event) {
                int viewRow = rowAtPoint(event.getPoint());
                int viewCol = columnAtPoint(event.getPoint());
                if (viewRow < 0 || viewCol < 0) {
                    return null;
                }
                int modelRow = convertRowIndexToModel(viewRow);
                int modelCol = convertColumnIndexToModel(viewCol);
                if (modelCol == 4 || modelCol == 5) {
                    return tableModel.getFullValueAt(modelRow, modelCol);
                }
                return null;
            }
        };

        List<String> serverOptions = new ArrayList<>();
        serverOptions.add(ALL_SERVERS);
        for (LogServer s : servers) {
            serverOptions.add(s.name);
        }
        this.serverSelector = new JComboBox<>(serverOptions.toArray(new String[0]));
        boldenComboText(serverSelector);
        boldenComboText(hiddenFilterSelector);
        boldenComboText(fixedFilterSelector);
        boldenComboText(nullFilterSelector);

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        restoreWindowBounds();
        getContentPane().setBackground(BG);
        setIconImages(loadWindowIcons());

        add(buildTopBar(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        clockTimer = new Timer(1000, e -> updateStatusBar());
        clockTimer.start();
        uiScaleDebounceTimer.setRepeats(false);

        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                trackNormalBounds();
            }

            @Override
            public void componentMoved(java.awt.event.ComponentEvent e) {
                trackNormalBounds();
            }
        });

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                clockTimer.stop();
                uiScaleDebounceTimer.stop();
                syncService.shutdown();
                persistWindowState();
            }
        });

        addPropertyChangeListener("graphicsConfiguration", evt -> SwingUtilities.invokeLater(() -> {
            invalidateTree(this);
            validate();
            repaint();
        }));

        Integer savedDivider = settingsStore.getSplitDividerLocation();
        SwingUtilities.invokeLater(() -> {
            int maxDivider = centerSplit.getHeight() - centerSplit.getDividerSize() - minDetailHeight;
            int location = savedDivider != null ? savedDivider : centerSplit.getDividerLocation();
            centerSplit.setDividerLocation(Math.min(location, Math.max(0, maxDivider)));
        });
    }

    private void restoreWindowBounds() {
        SettingsStore.WindowBounds saved = settingsStore.getWindowBounds();
        Rectangle target = saved != null ? new Rectangle(saved.x, saved.y, saved.width, saved.height) : null;
        Rectangle usable = usableScreenBounds(target);

        int width = Math.max(400, Math.min(target != null ? target.width : 1280, usable.width));
        int height = Math.max(300, Math.min(target != null ? target.height : 780, usable.height));
        int x;
        int y;
        if (target != null) {
            x = Math.max(usable.x, Math.min(target.x, usable.x + usable.width - width));
            y = Math.max(usable.y, Math.min(target.y, usable.y + usable.height - height));
        } else {
            x = usable.x + (usable.width - width) / 2;
            y = usable.y + (usable.height - height) / 2;
        }
        setBounds(x, y, width, height);
        lastNormalBounds = new Rectangle(x, y, width, height);

        if (saved != null && saved.maximized) {
            setExtendedState(Frame.MAXIMIZED_BOTH);
        }
    }

    private static Rectangle usableScreenBounds(Rectangle target) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice chosen = ge.getDefaultScreenDevice();
        if (target != null) {
            Point center = new Point(target.x + target.width / 2, target.y + target.height / 2);
            for (GraphicsDevice gd : ge.getScreenDevices()) {
                if (gd.getDefaultConfiguration().getBounds().contains(center)) {
                    chosen = gd;
                    break;
                }
            }
        }
        GraphicsConfiguration gc = chosen.getDefaultConfiguration();
        Rectangle bounds = gc.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(
                bounds.x + insets.left,
                bounds.y + insets.top,
                bounds.width - insets.left - insets.right,
                bounds.height - insets.top - insets.bottom
        );
    }

    private void trackNormalBounds() {
        if ((getExtendedState() & Frame.MAXIMIZED_BOTH) == 0) {
            lastNormalBounds = getBounds();
        }
    }

    private void persistWindowState() {
        Rectangle bounds = lastNormalBounds != null ? lastNormalBounds : getBounds();
        boolean maximized = (getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
        settingsStore.setWindowBounds(bounds.x, bounds.y, bounds.width, bounds.height, maximized);
        if (centerSplit != null) {
            settingsStore.setSplitDividerLocation(centerSplit.getDividerLocation());
        }
    }

    private static void invalidateTree(Container c) {
        c.invalidate();
        for (Component child : c.getComponents()) {
            if (child instanceof Container) {
                invalidateTree((Container) child);
            } else {
                child.invalidate();
            }
        }
    }

    private static List<Image> loadWindowIcons() {
        List<Image> icons = new ArrayList<>();
        for (int size : new int[]{16, 32, 48}) {
            try (var in = MainWindow.class.getResourceAsStream("/tgrv/icon" + size + ".png")) {
                if (in != null) {
                    icons.add(javax.imageio.ImageIO.read(in));
                }
            } catch (java.io.IOException ignored) {
            }
        }
        return icons;
    }

    private LogServer serverByName(String name) {
        for (LogServer s : servers) {
            if (s.name.equals(name)) {
                return s;
            }
        }
        return null;
    }

    private String roundLogsUrl(String serverName, LocalDate date, int roundId) {
        LogServer server = serverByName(serverName);
        return server == null ? null : server.baseUrl + "/" + URL_DAY_FMT.format(date) + "/round-" + roundId + "/";
    }

    private void openUrl(String url) {
        if (url == null) {
            return;
        }
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Couldn't open a browser for:\n" + url,
                    "Open Logs Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void darken(JComponent c) {
        c.setBackground(BG);
        c.setForeground(FG);
    }

    private static final Color CONTROL_TEXT = new Color(50, 50, 50);
    private static final String BOLDEN_COLOR_MARKER = "tgrv.bolden.color";

    private static Font currentBaseFont(JComponent fallback) {
        Font base = UIManager.getFont("defaultFont");
        return base != null ? base : fallback.getFont();
    }

    private static void applyBoldenedFont(JComponent c, Color color) {
        c.setFont(currentBaseFont(c).deriveFont(Font.BOLD));
        c.setForeground(color);
        c.putClientProperty(BOLDEN_COLOR_MARKER, color);
    }

    private static void boldenControlText(JComponent c) {
        applyBoldenedFont(c, CONTROL_TEXT);
    }

    private static void boldenControlTextWhite(JComponent c) {
        applyBoldenedFont(c, Color.WHITE);
    }

    private static final JList<Object> DUMMY_RENDERER_LIST = new JList<>();

    private static void boldenComboText(JComboBox<?> combo) {
        boldenControlText(combo);
        ListCellRenderer<Object> defaultRenderer = (ListCellRenderer<Object>) combo.getRenderer();
        combo.setRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JList<?> safeList = list != null ? list : DUMMY_RENDERER_LIST;
            Component comp = defaultRenderer.getListCellRendererComponent(safeList, value, index, isSelected, cellHasFocus);
            if (index == -1) {
                comp.setForeground(CONTROL_TEXT);
            }
            comp.setFont(currentBaseFont(combo).deriveFont(Font.BOLD));
            return comp;
        });
    }

    private static void refreshComponentFonts(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JComponent) {
                JComponent jc = (JComponent) child;
                Object colorMarker = jc.getClientProperty(BOLDEN_COLOR_MARKER);
                if (colorMarker instanceof Color) {
                    applyBoldenedFont(jc, (Color) colorMarker);
                } else {
                    String uiClassId = jc.getUIClassID();
                    if (uiClassId != null && uiClassId.endsWith("UI")) {
                        Font font = UIManager.getFont(uiClassId.substring(0, uiClassId.length() - 2) + ".font");
                        if (font != null) {
                            jc.setFont(font);
                        }
                    }
                }
            }
            if (child instanceof Container) {
                refreshComponentFonts((Container) child);
            }
        }
    }

    private void applyUiScale(int percent) {
        double oldScale = uiScale;
        uiScale = percent / 100.0;

        tgrv.Main.installNimbusAndScale(percent);
        refreshComponentFonts(this);

        table.setRowHeight(Math.max(10, Math.round(22 * (float) uiScale)));
        double ratio = uiScale / oldScale;
        for (int i = 0; i < COLUMN_NAMES.length; i++) {
            columnRestoreWidth[i] = Math.max(15, (int) Math.round(columnRestoreWidth[i] * ratio));
            if (columnVisible[i]) {
                applyColumnVisibility(i, true, columnRestoreWidth[i]);
            }
        }
        table.doLayout();
        persistColumnLayout();

        initDetailStyles();
        detailArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, detailFontSize));
        JComponent detailScroll = (JComponent) centerSplit.getBottomComponent();
        int lineHeight = detailArea.getFontMetrics(detailArea.getFont()).getHeight();
        minDetailHeight = lineHeight * 9 + Math.round(24 * (float) uiScale);
        detailScroll.setMinimumSize(new Dimension(200, minDetailHeight));
        showDetailForSelection();
        for (Runnable rescale : new ArrayList<>(exampleDialogRescalers)) {
            try {
                rescale.run();
            } catch (RuntimeException ex) {
                exampleDialogRescalers.remove(rescale);
            }
        }

        updateStatusBar();
        resizeStatusLabelToFit();

        settingsStore.setUiScalePercent(percent);

        revalidate();
        repaint();
    }

    private JButton quickRangeButton(String label, int days) {
        JButton b = new JButton(label);
        b.addActionListener(e -> windowDaysSpinner.setValue(days));
        boldenControlText(b);
        return b;
    }

    private static final String[] COLUMN_NAMES =
            {"Count", "Rounds", "First Seen", "Last Seen", "Server(s)", "Status", "Message", "Proc", "Source File"};
    private static final int[] DEFAULT_COLUMN_WIDTHS = {70, 60, 100, 100, 60, 45, 380, 240, 240};
    private static final boolean[] FIXED_WIDTH_COLUMN = {true, true, true, true, true, true, false, false, false};

    private final int[] columnRestoreWidth = new int[COLUMN_NAMES.length];
    private final boolean[] columnVisible = new boolean[COLUMN_NAMES.length];
    private final JCheckBoxMenuItem[] columnVisibilityItems = new JCheckBoxMenuItem[COLUMN_NAMES.length];

    private void applyColumnVisibility(int index, boolean visible, int width) {
        TableColumn column = table.getColumnModel().getColumn(index);
        if (!visible) {
            column.setMinWidth(0);
            column.setMaxWidth(0);
            column.setPreferredWidth(0);
            return;
        }
        if (FIXED_WIDTH_COLUMN[index]) {
            column.setMinWidth(width);
            column.setMaxWidth(width);
        } else {
            column.setMinWidth(15);
            column.setMaxWidth(Integer.MAX_VALUE);
        }
        column.setPreferredWidth(width);
    }

    private void persistColumnLayout() {
        Map<Integer, SettingsStore.ColumnLayout> layouts = new java.util.HashMap<>();
        for (int i = 0; i < COLUMN_NAMES.length; i++) {
            if (columnVisible[i]) {
                columnRestoreWidth[i] = table.getColumnModel().getColumn(i).getWidth();
            }
            layouts.put(i, new SettingsStore.ColumnLayout(columnRestoreWidth[i], columnVisible[i]));
        }
        settingsStore.setColumnLayouts(layouts);
    }

    private void setColumnVisible(int index, boolean visible) {
        columnVisible[index] = visible;
        applyColumnVisibility(index, visible, columnRestoreWidth[index]);
        if (columnVisibilityItems[index] != null) {
            columnVisibilityItems[index].setSelected(visible);
        }
    }

    private int defaultColumnWidth(int col) {
        return Math.round(DEFAULT_COLUMN_WIDTHS[col] * (float) uiScale);
    }

    private void resetColumnSizes() {
        for (int col = 0; col < COLUMN_NAMES.length; col++) {
            columnRestoreWidth[col] = defaultColumnWidth(col);
            applyColumnVisibility(col, columnVisible[col], defaultColumnWidth(col));
        }
    }

    private JPopupMenu buildColumnHeaderPopupMenu() {
        JPopupMenu menu = new JPopupMenu();
        for (int i = 0; i < COLUMN_NAMES.length; i++) {
            int index = i;
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(COLUMN_NAMES[i], columnVisible[i]);
            item.addActionListener(e -> {
                setColumnVisible(index, item.isSelected());
                persistColumnLayout();
            });
            columnVisibilityItems[i] = item;
            menu.add(item);
        }
        menu.addSeparator();
        JMenuItem reset = new JMenuItem("Reset Column Sizes");
        reset.addActionListener(e -> {
            resetColumnSizes();
            persistColumnLayout();
        });
        menu.add(reset);
        return menu;
    }

    private JPanel controlGroup(JComponent... components) {
        JPanel group = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        darken(group);
        for (JComponent c : components) {
            group.add(c);
        }
        return group;
    }

    private JComponent buildTopBar() {
        JPanel bar = new JPanel(new WrapLayout(FlowLayout.LEFT, 14, 8));
        darken(bar);

        JLabel serverLabel = new JLabel("Server:");
        serverLabel.setForeground(FG);
        serverSelector.addActionListener(e -> refreshTable());
        bar.add(controlGroup(serverLabel, serverSelector));

        JLabel timeframeLabel = new JLabel("Timeframe:");
        timeframeLabel.setForeground(FG);
        windowDaysSpinner.addChangeListener(e -> refreshTable());
        windowDaysSpinner.addMouseWheelListener(e -> {
            SpinnerNumberModel model = (SpinnerNumberModel) windowDaysSpinner.getModel();
            int min = (Integer) model.getMinimum();
            int max = (Integer) model.getMaximum();
            int current = (Integer) windowDaysSpinner.getValue();
            int next = current - e.getWheelRotation();
            windowDaysSpinner.setValue(Math.max(min, Math.min(max, next)));
        });
        bar.add(controlGroup(timeframeLabel, windowDaysSpinner));

        bar.add(controlGroup(
                quickRangeButton("Today", 1),
                quickRangeButton("Last 7 Days", 7),
                quickRangeButton("30 Days", SyncService.RETENTION_DAYS)));

        hiddenFilterSelector.setSelectedItem("With Hidden");
        hiddenFilterSelector.setToolTipText("Filter by whether a runtime is marked Hidden");
        hiddenFilterSelector.addActionListener(e -> refreshTable());
        fixedFilterSelector.setSelectedItem("With Fixed");
        fixedFilterSelector.setToolTipText("Filter by whether a runtime is marked Fixed");
        fixedFilterSelector.addActionListener(e -> refreshTable());
        nullFilterSelector.setToolTipText("Filter by whether the message contains \"null.\" (e.g. \"Cannot read null.foo\")");
        nullFilterSelector.addActionListener(e -> refreshTable());
        bar.add(controlGroup(hiddenFilterSelector, fixedFilterSelector, nullFilterSelector));

        filterField.setBackground(BG_ALT);
        filterField.setForeground(FG);
        filterField.setCaretColor(FG);
        filterField.setPlaceholder("Filter");
        filterField.setPlaceholderColor(FG_DIM);
        bar.add(controlGroup(filterField));
        return bar;
    }

    private JComponent buildCenter() {
        table.setAutoCreateRowSorter(false);
        table.setBackground(BG_ALT);
        table.setForeground(FG);
        table.setGridColor(GRID);
        table.setSelectionBackground(SELECTION_BG);
        table.setSelectionForeground(SELECTION_FG);
        table.setRowHeight(Math.max(10, Math.round(22 * (float) uiScale)));
        ToolTipManager.sharedInstance().registerComponent(table);

        JTableHeader header = table.getTableHeader();
        header.setBackground(BG);
        boldenControlText(header);
        header.setReorderingAllowed(false);
        TableCellRenderer defaultHeaderRenderer = header.getDefaultRenderer();
        header.setDefaultRenderer((headerTable, value, isSelected, hasFocus, row, column) -> {
            Component comp = defaultHeaderRenderer.getTableCellRendererComponent(headerTable, value, isSelected, hasFocus, row, column);
            if (comp instanceof JLabel) {
                ((JLabel) comp).setHorizontalAlignment(SwingConstants.CENTER);
            }
            return comp;
        });

        TableRowSorter<RuntimeTableModel> sorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(sorter);

        for (int i = 0; i < COLUMN_NAMES.length; i++) {
            SettingsStore.ColumnLayout saved = settingsStore.getColumnLayout(i);
            int width = saved != null ? saved.width : defaultColumnWidth(i);
            boolean visible = saved == null || saved.visible;
            columnRestoreWidth[i] = width;
            columnVisible[i] = visible;
            applyColumnVisibility(i, visible, width);
        }
        table.setFillsViewportHeight(true);

        table.getColumnModel().getColumn(6).setCellRenderer(new PathHighlightRenderer(true));
        PathHighlightRenderer pathOnlyRenderer = new PathHighlightRenderer(false);
        table.getColumnModel().getColumn(7).setCellRenderer(pathOnlyRenderer);
        table.getColumnModel().getColumn(8).setCellRenderer(pathOnlyRenderer);

        StatusTintRenderer statusTintRenderer = new StatusTintRenderer();
        table.setDefaultRenderer(Long.class, statusTintRenderer);
        table.setDefaultRenderer(Integer.class, statusTintRenderer);
        table.setDefaultRenderer(String.class, statusTintRenderer);

        JPopupMenu columnMenu = buildColumnHeaderPopupMenu();
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowMenu(e);
                persistColumnLayout();
            }

            private void maybeShowMenu(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    refreshComponentFonts(columnMenu);
                    columnMenu.show(header, e.getX(), e.getY());
                }
            }
        });

        sorter.toggleSortOrder(0);
        sorter.toggleSortOrder(0);

        ListSelectionListener selectionListener = e -> {
            if (!e.getValueIsAdjusting()) {
                showDetailForSelection();
            }
        };
        table.getSelectionModel().addListSelectionListener(selectionListener);

        JPopupMenu popup = buildRowPopupMenu();
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }

            private void maybeShowPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int row = table.rowAtPoint(e.getPoint());
                if (row < 0) {
                    return;
                }
                table.setRowSelectionInterval(row, row);
                refreshComponentFonts(popup);
                popup.show(table, e.getX(), e.getY());
            }
        });

        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter(sorter);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter(sorter);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter(sorter);
            }
        });

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.getViewport().setBackground(BG_ALT);
        tableScroll.setBackground(BG);

        detailArea.setEditable(false);
        detailArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, detailFontSize));
        detailArea.setBackground(BG_ALT);
        detailArea.setForeground(FG);
        detailArea.setCaretColor(FG);
        installLinkHandling(detailArea);
        installCopyPopup(detailArea);
        JScrollPane detailScroll = new JScrollPane(detailArea);
        detailScroll.getViewport().setBackground(BG_ALT);

        int lineHeight = detailArea.getFontMetrics(detailArea.getFont()).getHeight();
        minDetailHeight = lineHeight * 9 + Math.round(24 * (float) uiScale);
        detailScroll.setMinimumSize(new Dimension(200, minDetailHeight));

        centerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, detailScroll);
        centerSplit.setResizeWeight(0.75);
        centerSplit.setBackground(BG);
        centerSplit.setBorder(BorderFactory.createEmptyBorder());
        return centerSplit;
    }

    private JPopupMenu buildRowPopupMenu() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem copyRow = new JMenuItem("Copy");
        copyRow.addActionListener(e -> {
            Action copyAction = table.getActionMap().get("copy");
            if (copyAction != null) {
                copyAction.actionPerformed(new ActionEvent(table, ActionEvent.ACTION_PERFORMED, "copy"));
            }
        });
        popup.add(copyRow);
        popup.addSeparator();
        JMenuItem viewFullExamples = new JMenuItem("View Full Examples");
        viewFullExamples.addActionListener(e -> showFullExamplesForSelection());
        popup.add(viewFullExamples);
        popup.addSeparator();
        JMenuItem toggleHidden = new JMenuItem("Toggle Hidden");
        toggleHidden.addActionListener(e -> forSelectedGroups(g -> {
            GroupStatus s = settingsStore.get(g.key());
            settingsStore.setHidden(g.key(), !s.hidden);
        }));
        JMenuItem toggleFixed = new JMenuItem("Toggle Fixed");
        toggleFixed.addActionListener(e -> forSelectedGroups(g -> {
            GroupStatus s = settingsStore.get(g.key());
            settingsStore.setFixed(g.key(), !s.fixed);
        }));
        JMenuItem clearStatus = new JMenuItem("Clear Status");
        clearStatus.addActionListener(e -> forSelectedGroups(g -> {
            settingsStore.setHidden(g.key(), false);
            settingsStore.setFixed(g.key(), false);
        }));
        popup.add(toggleHidden);
        popup.add(toggleFixed);
        popup.addSeparator();
        popup.add(clearStatus);
        return popup;
    }

    private static final int MAX_FULL_EXAMPLES = 1000;

    private void showFullExamplesForSelection() {
        if (table.getSelectedRow() < 0) {
            return;
        }
        int modelRow = table.convertRowIndexToModel(table.getSelectedRow());
        RuntimeGroup g = tableModel.at(modelRow);

        List<LogExtractor.Example> examples = LogExtractor.findExamples(cache, g, MAX_FULL_EXAMPLES);

        JTextPane area = new JTextPane() {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return false;
            }
        };
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, detailFontSize));
        area.setBackground(BG_ALT);
        area.setForeground(FG);
        installCopyPopup(area);

        JTextPane pageLabel = new JTextPane();
        pageLabel.setEditable(false);
        pageLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, detailFontSize));
        pageLabel.setBackground(BG);
        pageLabel.setForeground(FG);
        pageLabel.setBorder(BorderFactory.createEmptyBorder());
        installLinkHandling(pageLabel);
        installCopyPopup(pageLabel);
        JButton prevButton = new JButton("< Prev");
        JButton nextButton = new JButton("Next >");
        JPanel navButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        darken(navButtons);
        navButtons.add(prevButton);
        navButtons.add(nextButton);
        JSpinner jumpSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 1, 1));
        jumpSpinner.setToolTipText("Jump to example #");
        ((JSpinner.DefaultEditor) jumpSpinner.getEditor()).getTextField().setColumns(4);

        int[] page = {0};
        boolean[] syncingSpinner = {false};
        Runnable render = () -> {
            syncingSpinner[0] = true;
            ((SpinnerNumberModel) jumpSpinner.getModel()).setMaximum(Math.max(1, examples.size()));
            jumpSpinner.setValue(page[0] + 1);
            syncingSpinner[0] = false;
            navButtons.setVisible(examples.size() > 1);

            if (examples.isEmpty()) {
                pageLabel.setText("No examples");
                prevButton.setEnabled(false);
                nextButton.setEnabled(false);
                jumpSpinner.setEnabled(false);
                area.setText("No full examples available yet.\n\n"
                        + "This appears once the raw runtime.log for at least one of this runtime's "
                        + "rounds has been downloaded (it's fetched alongside the condensed log on each "
                        + "sync cycle, but older rounds cached before this feature was added won't have "
                        + "one until they're re-synced).");
                area.setCaretPosition(0);
                return;
            }
            LogExtractor.Example ex = examples.get(page[0]);
            String capNote = examples.size() >= MAX_FULL_EXAMPLES ? " (capped at " + MAX_FULL_EXAMPLES + ")" : "";
            DefaultStyledDocument pageDoc = new DefaultStyledDocument();
            appendPlain(pageDoc, "Example " + (page[0] + 1) + " of " + examples.size() + capNote
                    + "   -   [" + ex.serverName + "]  ");
            appendDetail(pageDoc, "Round: ", null);
            appendRoundLink(pageDoc, ex.serverName, ex.date, ex.roundId);
            pageLabel.setStyledDocument(pageDoc);
            DefaultStyledDocument blockDoc = new DefaultStyledDocument();
            try {
                appendFullExampleBlock(blockDoc, ex.blockText);
            } catch (BadLocationException ex2) {
                throw new IllegalStateException(ex2);
            }
            area.setStyledDocument(blockDoc);
            area.setCaretPosition(0);
            prevButton.setEnabled(page[0] > 0);
            nextButton.setEnabled(page[0] < examples.size() - 1);
            jumpSpinner.setEnabled(true);
        };
        prevButton.addActionListener(e -> {
            page[0]--;
            render.run();
        });
        nextButton.addActionListener(e -> {
            page[0]++;
            render.run();
        });
        jumpSpinner.addChangeListener(e -> {
            if (syncingSpinner[0]) {
                return;
            }
            int target = (Integer) jumpSpinner.getValue() - 1;
            if (target != page[0] && target >= 0 && target < examples.size()) {
                page[0] = target;
                render.run();
            }
        });
        render.run();

        JPanel nav = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 6));
        darken(nav);
        nav.add(navButtons);
        nav.add(jumpSpinner);
        nav.add(pageLabel);

        JScrollPane scroll = new JScrollPane(area);
        scroll.getViewport().setBackground(BG_ALT);
        scroll.setPreferredSize(new Dimension(900, 560));

        JPanel content = new JPanel(new BorderLayout());
        darken(content);
        content.add(nav, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);

        String dialogTitle = (g.message + "  -  " + g.sourceFile).replace('\n', ' ').replace('\r', ' ');
        JDialog dialog = new JDialog(this, dialogTitle, false);
        dialog.getContentPane().setBackground(BG);
        dialog.add(content);
        dialog.pack();
        dialog.setLocationRelativeTo(this);

        Runnable rescale = () -> {
            refreshComponentFonts(dialog);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, detailFontSize));
            pageLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, detailFontSize));
            render.run();
            dialog.getContentPane().invalidate();
            dialog.pack();
            dialog.validate();
            dialog.repaint();
        };
        exampleDialogRescalers.add(rescale);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                exampleDialogRescalers.remove(rescale);
            }

            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                exampleDialogRescalers.remove(rescale);
            }
        });

        dialog.setVisible(true);
    }

    private void forSelectedGroups(java.util.function.Consumer<RuntimeGroup> action) {
        for (int viewRow : table.getSelectedRows()) {
            int modelRow = table.convertRowIndexToModel(viewRow);
            action.accept(tableModel.at(modelRow));
            tableModel.fireTableRowsUpdated(modelRow, modelRow);
        }
        showDetailForSelection();
    }

    private void applyFilter(TableRowSorter<RuntimeTableModel> sorter) {
        String text = filterField.getText().trim();
        if (text.isEmpty()) {
            sorter.setRowFilter(null);
            return;
        }
        try {
            sorter.setRowFilter(RowFilter.regexFilter("(?i)" + Pattern.quote(text), 6, 7, 8));
        } catch (PatternSyntaxException ex) {
            sorter.setRowFilter(null);
        }
    }

    private static final Color LABEL_COLOR = new Color(230, 180, 90);

    private static final Map<String, Color> TYPE_PATH_COLORS = Map.ofEntries(
            Map.entry("obj", new Color(224, 128, 74)),
            Map.entry("mob", new Color(120, 200, 120)),
            Map.entry("datum", new Color(120, 170, 235)),
            Map.entry("turf", new Color(190, 160, 110)),
            Map.entry("atom", new Color(90, 200, 200)),
            Map.entry("area", new Color(200, 120, 190)),
            Map.entry("client", new Color(140, 220, 190)),
            Map.entry("proc", new Color(175, 150, 224))
    );
    private static final Color TYPE_PATH_DEFAULT_COLOR = new Color(150, 180, 180);
    private static final Pattern TYPE_PATH_PATTERN = Pattern.compile("(?<![A-Za-z0-9_])/[A-Za-z_][A-Za-z0-9_]*(?:/[A-Za-z_][A-Za-z0-9_]*)*");

    private static final Color NULL_DOT_COLOR = new Color(215, 140, 140);
    private static final Color TYPE_MISMATCH_COLOR = new Color(224, 165, 60);
    private static final String TYPE_MISMATCH_TEXT = "type mismatch:";
    private static final Pattern MESSAGE_HIGHLIGHT_PATTERN = Pattern.compile(
            TYPE_PATH_PATTERN.pattern() + "|null\\.|" + Pattern.quote(TYPE_MISMATCH_TEXT));
    private static final Pattern BLOCK_FIELD_LABEL_PATTERN = Pattern.compile("^ -\\s+[A-Za-z][A-Za-z_. ]*:");
    private static final Pattern RUNTIME_HEADER_PATTERN = Pattern.compile("RUNTIME: runtime error:");

    private static String typePathRoot(String path) {
        int secondSlash = path.indexOf('/', 1);
        return path.substring(1, secondSlash < 0 ? path.length() : secondSlash).toLowerCase();
    }

    private static Color typePathColor(String path) {
        return TYPE_PATH_COLORS.getOrDefault(typePathRoot(path), TYPE_PATH_DEFAULT_COLOR);
    }

    private static Color highlightColor(String matched) {
        if (matched.startsWith("/")) {
            return typePathColor(matched);
        }
        return matched.equals(TYPE_MISMATCH_TEXT) ? TYPE_MISMATCH_COLOR : NULL_DOT_COLOR;
    }

    private void applyStatusTint(Component comp, JTable table, boolean isSelected, int viewRow) {
        if (isSelected) {
            comp.setBackground(table.getSelectionBackground());
            return;
        }
        RuntimeGroup g = tableModel.at(table.convertRowIndexToModel(viewRow));
        GroupStatus s = settingsStore.get(g.key());
        if (s.hidden && s.fixed) {
            comp.setBackground(ROW_HIDDEN_AND_FIXED_BG);
        } else if (s.hidden) {
            comp.setBackground(ROW_HIDDEN_BG);
        } else if (s.fixed) {
            comp.setBackground(ROW_FIXED_BG);
        } else {
            comp.setBackground(viewRow % 2 == 0 ? table.getBackground() : ROW_STRIPE_BG);
        }
    }

    private final class StatusTintRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            comp.setFont(table.getFont());
            applyStatusTint(comp, table, isSelected, row);
            return comp;
        }
    }

    private final class PathHighlightRenderer extends DefaultTableCellRenderer {
        private final boolean highlightNull;

        PathHighlightRenderer(boolean highlightNull) {
            this.highlightNull = highlightNull;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            label.setFont(table.getFont());
            String text = value == null ? "" : value.toString();
            label.setText(isSelected ? text : toHighlightedHtml(text));
            applyStatusTint(label, table, isSelected, row);
            return label;
        }

        private String toHighlightedHtml(String text) {
            Pattern pattern = highlightNull ? MESSAGE_HIGHLIGHT_PATTERN : TYPE_PATH_PATTERN;
            Matcher m = pattern.matcher(text);
            StringBuilder html = null;
            int last = 0;
            while (m.find()) {
                if (html == null) {
                    html = new StringBuilder("<html><body>");
                }
                if (m.start() > last) {
                    html.append(escapeHtml(text.substring(last, m.start())));
                }
                String matched = m.group();
                html.append("<font color='#").append(toHex(highlightColor(matched))).append("'>")
                        .append(escapeHtml(matched)).append("</font>");
                last = m.end();
            }
            if (html == null) {
                return text;
            }
            if (last < text.length()) {
                html.append(escapeHtml(text.substring(last)));
            }
            html.append("</body></html>");
            return html.toString();
        }

        private static String escapeHtml(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    private static final Object TYPE_PATH_MARKER_KEY = new Object();

    private void initDetailStyles() {
        detailFontSize = Math.max(6, Math.round(12 * (float) uiScale));
        DETAIL_LABEL_STYLE = detailStyle(LABEL_COLOR, true);
        DETAIL_PLAIN_STYLE = detailStyle(FG, false);
        TYPE_PATH_DEFAULT_STYLE = detailStyle(TYPE_PATH_DEFAULT_COLOR, false);
        NULL_DOT_STYLE = detailStyle(NULL_DOT_COLOR, false);
        TYPE_MISMATCH_STYLE = detailStyle(TYPE_MISMATCH_COLOR, false);
        Map<String, SimpleAttributeSet> styles = new java.util.HashMap<>();
        for (Map.Entry<String, Color> e : TYPE_PATH_COLORS.entrySet()) {
            SimpleAttributeSet style = detailStyle(e.getValue(), false);
            style.addAttribute(TYPE_PATH_MARKER_KEY, Boolean.TRUE);
            styles.put(e.getKey(), style);
        }
        TYPE_PATH_STYLES = styles;
        TYPE_PATH_DEFAULT_STYLE.addAttribute(TYPE_PATH_MARKER_KEY, Boolean.TRUE);
    }

    private SimpleAttributeSet detailStyle(Color color, boolean bold) {
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setFontFamily(attrs, Font.MONOSPACED);
        StyleConstants.setFontSize(attrs, detailFontSize);
        StyleConstants.setBold(attrs, bold);
        StyleConstants.setForeground(attrs, color);
        return attrs;
    }

    private static final Object LINK_URL_KEY = new Object();
    private static final Object ROUND_LINK_KEY = new Object();
    private static final Color LINK_COLOR = new Color(120, 180, 240);

    private static final class RoundLink {
        final String serverName;
        final LocalDate date;
        final int roundId;

        RoundLink(String serverName, LocalDate date, int roundId) {
            this.serverName = serverName;
            this.date = date;
            this.roundId = roundId;
        }
    }

    private void appendPlain(StyledDocument doc, String text) {
        try {
            doc.insertString(doc.getLength(), text, DETAIL_PLAIN_STYLE);
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void appendRoundLink(StyledDocument doc, String serverName, LocalDate date, int roundId) {
        SimpleAttributeSet attrs = detailStyle(LINK_COLOR, false);
        StyleConstants.setUnderline(attrs, true);
        attrs.addAttribute(LINK_URL_KEY, roundLogsUrl(serverName, date, roundId));
        attrs.addAttribute(ROUND_LINK_KEY, new RoundLink(serverName, date, roundId));
        try {
            doc.insertString(doc.getLength(), String.valueOf(roundId), attrs);
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String typePathAt(JTextPane pane, Point point) {
        int pos = pane.viewToModel2D(point);
        if (pos < 0) {
            return null;
        }
        StyledDocument doc = pane.getStyledDocument();
        Element el = doc.getCharacterElement(pos);
        if (!el.getAttributes().isDefined(TYPE_PATH_MARKER_KEY)) {
            return null;
        }
        try {
            return doc.getText(el.getStartOffset(), el.getEndOffset() - el.getStartOffset());
        } catch (BadLocationException e) {
            return null;
        }
    }

    private static RoundLink roundLinkAt(JTextPane pane, Point point) {
        int pos = pane.viewToModel2D(point);
        if (pos < 0) {
            return null;
        }
        StyledDocument doc = pane.getStyledDocument();
        Element el = doc.getCharacterElement(pos);
        Object info = el.getAttributes().getAttribute(ROUND_LINK_KEY);
        return info instanceof RoundLink ? (RoundLink) info : null;
    }

    private void openRoundLogsDir(RoundLink link) {
        openUrl(roundLogsUrl(link.serverName, link.date, link.roundId));
    }

    private void installCopyPopup(JTextPane pane) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copyItem = new JMenuItem("Copy Selected");
        copyItem.addActionListener(e -> pane.copy());
        JMenuItem copyTypeItem = new JMenuItem("Copy Type");
        copyTypeItem.addActionListener(e -> {
            StringSelection selection = new StringSelection(copyTypeItem.getActionCommand());
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
        });
        RoundLink[] currentRoundLink = {null};
        JMenuItem copyRoundItem = new JMenuItem("Copy Round Number");
        copyRoundItem.addActionListener(e -> {
            if (currentRoundLink[0] != null) {
                StringSelection selection = new StringSelection(String.valueOf(currentRoundLink[0].roundId));
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
            }
        });
        JMenuItem openRoundDirItem = new JMenuItem("Open Round Logs Dir");
        openRoundDirItem.addActionListener(e -> {
            if (currentRoundLink[0] != null) {
                openRoundLogsDir(currentRoundLink[0]);
            }
        });

        pane.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }

            private void maybeShowPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                menu.removeAll();
                if (pane.getSelectedText() != null) {
                    menu.add(copyItem);
                }
                String typePath = typePathAt(pane, e.getPoint());
                if (typePath != null) {
                    copyTypeItem.setText("Copy Type: " + typePath);
                    copyTypeItem.setActionCommand(typePath);
                    menu.add(copyTypeItem);
                }
                currentRoundLink[0] = roundLinkAt(pane, e.getPoint());
                if (currentRoundLink[0] != null) {
                    menu.add(copyRoundItem);
                    menu.add(openRoundDirItem);
                }
                if (menu.getComponentCount() > 0) {
                    refreshComponentFonts(menu);
                    menu.show(pane, e.getX(), e.getY());
                }
            }
        });
    }

    private void installLinkHandling(JTextPane pane) {
        pane.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                String url = linkUrlAt(pane, e.getPoint());
                if (url != null) {
                    openUrl(url);
                }
            }
        });
        pane.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                boolean overLink = linkUrlAt(pane, e.getPoint()) != null;
                pane.setCursor(Cursor.getPredefinedCursor(overLink ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        });
    }

    private static String linkUrlAt(JTextPane pane, Point point) {
        int pos = pane.viewToModel2D(point);
        if (pos < 0) {
            return null;
        }
        StyledDocument doc = pane.getStyledDocument();
        Element el = doc.getCharacterElement(pos);
        Object url = el.getAttributes().getAttribute(LINK_URL_KEY);
        return url instanceof String ? (String) url : null;
    }

    private void appendDetail(StyledDocument doc, String label, String value) {
        appendDetail(doc, label, value, false);
    }

    private void appendDetail(StyledDocument doc, String label, String value, boolean highlightNull) {
        try {
            if (label != null) {
                doc.insertString(doc.getLength(), label, DETAIL_LABEL_STYLE);
            }
            if (value != null) {
                appendWithTypePathHighlighting(doc, value, highlightNull);
            }
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void appendWithTypePathHighlighting(StyledDocument doc, String text) throws BadLocationException {
        appendWithTypePathHighlighting(doc, text, false);
    }

    private void appendWithTypePathHighlighting(StyledDocument doc, String text, boolean highlightNull) throws BadLocationException {
        Pattern pattern = highlightNull ? MESSAGE_HIGHLIGHT_PATTERN : TYPE_PATH_PATTERN;
        Matcher m = pattern.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                doc.insertString(doc.getLength(), text.substring(last, m.start()), DETAIL_PLAIN_STYLE);
            }
            String matched = m.group();
            SimpleAttributeSet style;
            if (matched.startsWith("/")) {
                style = TYPE_PATH_STYLES.getOrDefault(typePathRoot(matched), TYPE_PATH_DEFAULT_STYLE);
            } else if (matched.equals(TYPE_MISMATCH_TEXT)) {
                style = TYPE_MISMATCH_STYLE;
            } else {
                style = NULL_DOT_STYLE;
            }
            doc.insertString(doc.getLength(), matched, style);
            last = m.end();
        }
        if (last < text.length()) {
            doc.insertString(doc.getLength(), text.substring(last), DETAIL_PLAIN_STYLE);
        }
    }

    private void appendFullExampleBlock(StyledDocument doc, String blockText) throws BadLocationException {
        String[] lines = blockText.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher labelMatcher = BLOCK_FIELD_LABEL_PATTERN.matcher(line);
            Matcher headerMatcher = RUNTIME_HEADER_PATTERN.matcher(line);
            if (labelMatcher.lookingAt()) {
                String label = labelMatcher.group();
                doc.insertString(doc.getLength(), label, DETAIL_LABEL_STYLE);
                appendWithTypePathHighlighting(doc, line.substring(label.length()));
            } else if (headerMatcher.find()) {
                doc.insertString(doc.getLength(), line.substring(0, headerMatcher.start()), DETAIL_PLAIN_STYLE);
                doc.insertString(doc.getLength(), headerMatcher.group(), DETAIL_LABEL_STYLE);
                appendWithTypePathHighlighting(doc, line.substring(headerMatcher.end()));
            } else {
                appendWithTypePathHighlighting(doc, line);
            }
            if (i < lines.length - 1) {
                doc.insertString(doc.getLength(), "\n", DETAIL_PLAIN_STYLE);
            }
        }
    }

    private void showDetailForSelection() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            detailArea.setText("");
            return;
        }
        int modelRow = table.convertRowIndexToModel(viewRow);
        RuntimeGroup g = tableModel.at(modelRow);
        GroupStatus status = settingsStore.get(g.key());

        DefaultStyledDocument doc = new DefaultStyledDocument();
        appendDetail(doc, "Message:     ", g.message + "\n", true);
        appendDetail(doc, "Proc:        ", g.procName + "\n");
        appendDetail(doc, "Source File: ", g.sourceFile + "\n");
        if (status.hidden || status.fixed) {
            appendDetail(doc, "Status:      ", (status.hidden ? "Hidden " : "") + (status.fixed ? "Fixed" : "") + "\n");
        }
        if (g.exampleSrc != null) {
            appendDetail(doc, "src:         ", g.exampleSrc + "\n");
        }
        if (g.exampleUsr != null) {
            appendDetail(doc, "usr:         ", g.exampleUsr + "\n");
        }
        appendDetail(doc, "Total count: ", g.totalCount()
                + " across " + g.roundCount() + " round(s), " + g.firstSeen() + " to " + g.lastSeen() + "\n");
        appendDetail(doc, "\nOccurrences by round:\n", null);
        for (RuntimeGroup.Occurrence o : g.occurrences()) {
            appendDetail(doc, null, "  [" + o.serverName + "]  ");
            appendDetail(doc, "Round: ", null);
            appendRoundLink(doc, o.serverName, o.date, o.roundId);
            appendDetail(doc, null, "  " + o.date + "  count=" + o.count + "\n");
        }

        detailArea.setStyledDocument(doc);
        detailArea.setCaretPosition(0);
    }

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        darken(bar);
        statusLabel.setForeground(FG_DIM);
        statusLabel.setVerticalAlignment(SwingConstants.TOP);
        bar.add(statusLabel, BorderLayout.CENTER);
        bar.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                resizeStatusLabelToFit();
            }
        });

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        darken(actions);

        JLabel scaleLabel = new JLabel("UI Scale:");
        boldenControlTextWhite(scaleLabel);
        actions.add(scaleLabel);

        int savedScalePercent = settingsStore.getUiScalePercent() != null ? settingsStore.getUiScalePercent() : 100;
        SpinnerNumberModel uiScaleModel = new SpinnerNumberModel(savedScalePercent, 50, 300, 5);
        JSpinner uiScaleSpinner = new JSpinner(uiScaleModel);
        uiScaleSpinner.setToolTipText("Adjust the UI scale");
        JFormattedTextField uiScaleField = ((JSpinner.DefaultEditor) uiScaleSpinner.getEditor()).getTextField();
        uiScaleField.setColumns(3);
        boldenControlTextWhite(uiScaleField);
        uiScaleSpinner.addChangeListener(e -> {
            pendingUiScalePercent[0] = (Integer) uiScaleSpinner.getValue();
            uiScaleDebounceTimer.restart();
        });
        uiScaleSpinner.addMouseWheelListener(e -> {
            int min = (Integer) uiScaleModel.getMinimum();
            int max = (Integer) uiScaleModel.getMaximum();
            int step = (Integer) uiScaleModel.getStepSize();
            int current = (Integer) uiScaleSpinner.getValue();
            int next = current - e.getWheelRotation() * step;
            uiScaleSpinner.setValue(Math.max(min, Math.min(max, next)));
        });
        actions.add(uiScaleSpinner);
        JLabel percentLabel = new JLabel("%");
        boldenControlTextWhite(percentLabel);
        actions.add(percentLabel);

        JButton refreshNow = new JButton("Refresh Now");
        refreshNow.addActionListener(e -> {
            statusLabel.setText("Refreshing...");
            resizeStatusLabelToFit();
            syncService.triggerNow();
        });
        boldenControlText(refreshNow);
        actions.add(refreshNow);

        JButton openCacheButton = new JButton("Open Cache Folder");
        openCacheButton.setToolTipText(cache.root().toString());
        boldenControlText(openCacheButton);
        openCacheButton.addActionListener(e -> {
            try {
                Path root = cache.root();
                Files.createDirectories(root);
                Desktop.getDesktop().open(root.toFile());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Couldn't open cache folder:\n" + cache.root(),
                        "Open Cache Failed", JOptionPane.ERROR_MESSAGE);
            }
        });
        actions.add(openCacheButton);

        bar.add(actions, BorderLayout.EAST);
        return bar;
    }

    public void refreshTable() {
        int days = (Integer) windowDaysSpinner.getValue();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(days - 1L);
        String selectedServer = (String) serverSelector.getSelectedItem();
        String serverFilter = (selectedServer == null || ALL_SERVERS.equals(selectedServer)) ? null : selectedServer;
        List<RuntimeGroup> groups = Aggregator.aggregate(cache.all(), from, today, serverFilter);

        String hiddenFilter = (String) hiddenFilterSelector.getSelectedItem();
        String fixedFilter = (String) fixedFilterSelector.getSelectedItem();
        groups.removeIf(g -> {
            GroupStatus s = settingsStore.get(g.key());
            boolean failsHidden = "Only Hidden".equals(hiddenFilter) ? !s.hidden : "No Hidden".equals(hiddenFilter) && s.hidden;
            boolean failsFixed = "Only Fixed".equals(fixedFilter) ? !s.fixed : "No Fixed".equals(fixedFilter) && s.fixed;
            return failsHidden || failsFixed;
        });

        String nullFilter = (String) nullFilterSelector.getSelectedItem();
        if ("Only Nulls".equals(nullFilter)) {
            groups.removeIf(g -> !g.message.contains("null."));
        } else if ("No Nulls".equals(nullFilter)) {
            groups.removeIf(g -> g.message.contains("null."));
        }

        String selectedKey = null;
        if (table.getSelectedRow() >= 0) {
            int modelRow = table.convertRowIndexToModel(table.getSelectedRow());
            if (modelRow < tableModel.getRowCount()) {
                selectedKey = tableModel.at(modelRow).key();
            }
        }

        tableModel.setGroups(groups);

        if (selectedKey != null) {
            for (int i = 0; i < groups.size(); i++) {
                if (groups.get(i).key().equals(selectedKey)) {
                    int viewRow = table.convertRowIndexToView(i);
                    table.setRowSelectionInterval(viewRow, viewRow);
                    break;
                }
            }
        }
        updateStatusBar();
    }

    public void onSyncStarted() {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Syncing...");
            resizeStatusLabelToFit();
        });
    }

    public void onSyncFinished(SyncService.SyncEvent event) {
        SwingUtilities.invokeLater(() -> {
            lastSyncTime = event.time;
            settingsStore.setLastCheckTime(event.time);
            refreshTable();
            statusLabel.setForeground(event.error != null ? ERROR_FG : FG_DIM);
        });
    }

    private static final Color LOCAL_TIME_COLOR = new Color(200, 220, 200);
    private static final Color UTC_TIME_COLOR = new Color(150, 180, 220);

    private static String statusBarLabel(String text) {
        return "<b><font color='#" + toHex(LABEL_COLOR) + "'>" + text + "</font></b>";
    }

    private static String statusBarColored(String text, Color c) {
        return "<font color='#" + toHex(c) + "'>" + text + "</font>";
    }

    private static String toHex(Color c) {
        return String.format("%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private void updateStatusBar() {
        StringBuilder sb = new StringBuilder("<html>");
        sb.append(statusBarLabel("Cached rounds:")).append(' ');
        List<String> perServer = new ArrayList<>();
        for (LogServer s : servers) {
            long count = cache.all().stream().filter(rc -> rc.serverName.equals(s.name)).count();
            perServer.add(s.name + "=" + count);
        }
        sb.append(String.join("&nbsp;&nbsp;|&nbsp;&nbsp;", perServer)).append("&nbsp;&nbsp;|&nbsp;&nbsp;");
        if (lastSyncTime != null) {
            sb.append(statusBarLabel("Last check:")).append(' ')
                    .append(statusBarColored(TIME_FMT.format(lastSyncTime.atZone(ZoneId.systemDefault())), LOCAL_TIME_COLOR))
                    .append(" (UTC: ")
                    .append(statusBarColored(TIME_FMT.format(lastSyncTime.atZone(ZoneOffset.UTC)), UTC_TIME_COLOR))
                    .append(")&nbsp;&nbsp;|&nbsp;&nbsp;");
            long secondsSince = Instant.now().getEpochSecond() - lastSyncTime.getEpochSecond();
            long nextInSeconds = Math.max(0, SyncService.POLL_INTERVAL_MINUTES * 60 - secondsSince);
            sb.append(statusBarLabel("Next check in")).append(' ')
                    .append(nextInSeconds / 60).append("m ").append(nextInSeconds % 60).append('s');
        } else {
            sb.append("Not checked yet");
        }
        sb.append("</html>");
        statusLabel.setText(sb.toString());
        resizeStatusLabelToFit();
    }

    private void resizeStatusLabelToFit() {
        Container bar = statusLabel.getParent();
        if (!(bar instanceof JComponent) || !(bar.getLayout() instanceof BorderLayout)) {
            return;
        }
        Component actions = ((BorderLayout) bar.getLayout()).getLayoutComponent(BorderLayout.EAST);
        int actionsWidth = actions != null ? actions.getPreferredSize().width : 0;
        Insets insets = ((JComponent) bar).getInsets();
        int available = bar.getWidth() - insets.left - insets.right - actionsWidth - 8;
        if (available <= 0) {
            return;
        }
        Object htmlView = statusLabel.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey);
        int height;
        if (htmlView instanceof javax.swing.text.View) {
            javax.swing.text.View view = (javax.swing.text.View) htmlView;
            view.setSize(available, Integer.MAX_VALUE);
            height = (int) Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS));
        } else {
            height = statusLabel.getPreferredSize().height;
        }
        Dimension newSize = new Dimension(available, Math.max(height, 16));
        if (!newSize.equals(statusLabel.getPreferredSize())) {
            statusLabel.setPreferredSize(newSize);
            bar.revalidate();
            bar.repaint();
        }
    }
}
