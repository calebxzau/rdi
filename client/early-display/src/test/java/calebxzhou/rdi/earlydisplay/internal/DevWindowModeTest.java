package calebxzhou.rdi.earlydisplay.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class DevWindowModeTest {
    @Test
    void playersWithoutThePropertyGetTheNormalWindow() {
        assertEquals(DevWindowMode.Normal, DevWindowMode.parse(null));
        assertEquals(DevWindowMode.Normal, DevWindowMode.parse(""));
        assertEquals(DevWindowMode.Normal, DevWindowMode.parse("  "));
    }

    @Test
    void readsTheModesWhateverTheirCase() {
        assertEquals(DevWindowMode.Background, DevWindowMode.parse("background"));
        assertEquals(DevWindowMode.Background, DevWindowMode.parse(" BACKGROUND "));
        assertEquals(DevWindowMode.Normal, DevWindowMode.parse("normal"));
    }

    @Test
    void anUnknownValueIsReportedInsteadOfGuessed() {
        assertNull(DevWindowMode.parse("hidden"));
        assertNull(DevWindowMode.parse("minimized"));
    }
}
