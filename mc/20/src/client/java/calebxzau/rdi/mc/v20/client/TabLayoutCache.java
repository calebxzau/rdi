package calebxzau.rdi.mc.v20.client;

import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.List;

/** Keeps the single currently visible global-player tab layout. */
public final class TabLayoutCache {
    private static long fontEpoch;
    private static Layout cached;

    private TabLayoutCache() {
    }

    public static synchronized Layout get(
            GlobalPlayerListState.Snapshot snapshot,
            int screenWidth,
            int screenHeight,
            Font font
    ) {
        if (cached != null
                && cached.snapshot == snapshot
                && cached.screenWidth == screenWidth
                && cached.screenHeight == screenHeight
                && cached.font == font
                && cached.fontEpoch == fontEpoch) {
            return cached;
        }

        cached = create(snapshot, screenWidth, screenHeight, font, fontEpoch);
        return cached;
    }

    public static synchronized void invalidateFont() {
        fontEpoch++;
        cached = null;
    }

    public static synchronized void clear() {
        cached = null;
    }

    private static Layout create(
            GlobalPlayerListState.Snapshot snapshot,
            int screenWidth,
            int screenHeight,
            Font font,
            long fontEpoch
    ) {
        int lineHeight = 10;
        int padding = 8;
        int maxRows = Math.max(1, (screenHeight - 20 - padding) / lineHeight);
        List<RTabRow> allRows = snapshot.getRows();
        List<RTabRow> rows = allRows;
        if (allRows.size() > maxRows) {
            int contentLimit = maxRows - 1;
            int visibleRowCount = Math.min(contentLimit, allRows.size());
            ArrayList<RTabRow> visibleRows = new ArrayList<>(allRows.subList(0, visibleRowCount));
            int shownPlayers = 0;
            for (RTabRow row : visibleRows) {
                if (row.isPlayer()) shownPlayers++;
            }
            visibleRows.add(new RTabRow("还有" + (snapshot.getPlayerCount() - shownPlayers) + "名玩家", null, 0xFFEFEFEF));
            rows = List.copyOf(visibleRows);
        }

        int requestedWidth = 0;
        for (RTabRow row : rows) {
            requestedWidth = Math.max(requestedWidth, font.width(row.text()) + (row.isPlayer() ? 12 : 0));
        }
        int panelWidth = Math.min(Math.max(48, requestedWidth + 12), Math.min(260, screenWidth - 8));
        int availableTextWidth = Math.max(8, panelWidth - 24);
        ArrayList<String> displayText = new ArrayList<>(rows.size());
        for (RTabRow row : rows) {
            displayText.add(ellipsize(font, row.text(), availableTextWidth - (row.isPlayer() ? 11 : 0)));
        }

        return new Layout(
                snapshot,
                screenWidth,
                screenHeight,
                font,
                fontEpoch,
                List.copyOf(rows),
                List.copyOf(displayText),
                panelWidth,
                (screenWidth - panelWidth) / 2,
                10,
                lineHeight,
                padding
        );
    }

    private static String ellipsize(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String ellipsis = "…";
        if (font.width(ellipsis) > maxWidth) return "";
        int low = 0;
        int high = text.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (font.width(text.substring(0, middle) + ellipsis) <= maxWidth) low = middle;
            else high = middle - 1;
        }
        return text.substring(0, low) + ellipsis;
    }

    public static final class Layout {
        private final GlobalPlayerListState.Snapshot snapshot;
        private final int screenWidth;
        private final int screenHeight;
        private final Font font;
        private final long fontEpoch;
        private final List<RTabRow> rows;
        private final List<String> displayText;
        private final int panelWidth;
        private final int x;
        private final int y;
        private final int lineHeight;
        private final int padding;

        private Layout(
                GlobalPlayerListState.Snapshot snapshot,
                int screenWidth,
                int screenHeight,
                Font font,
                long fontEpoch,
                List<RTabRow> rows,
                List<String> displayText,
                int panelWidth,
                int x,
                int y,
                int lineHeight,
                int padding
        ) {
            this.snapshot = snapshot;
            this.screenWidth = screenWidth;
            this.screenHeight = screenHeight;
            this.font = font;
            this.fontEpoch = fontEpoch;
            this.rows = rows;
            this.displayText = displayText;
            this.panelWidth = panelWidth;
            this.x = x;
            this.y = y;
            this.lineHeight = lineHeight;
            this.padding = padding;
        }

        public List<RTabRow> rows() { return rows; }
        public List<String> displayText() { return displayText; }
        public int panelWidth() { return panelWidth; }
        public int x() { return x; }
        public int y() { return y; }
        public int lineHeight() { return lineHeight; }
        public int padding() { return padding; }
    }
}
