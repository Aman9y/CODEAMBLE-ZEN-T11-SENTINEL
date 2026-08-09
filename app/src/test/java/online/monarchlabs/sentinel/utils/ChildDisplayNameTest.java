package online.monarchlabs.sentinel.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ChildDisplayNameTest {
    @Test
    public void usesEnteredChildName() {
        assertEquals("Aman", ChildDisplayName.resolve(
                "a1b2c3d4e5f60708", " Aman ", "Pixel 8"));
    }

    @Test
    public void rejectsDeviceAndConnectionIdentifiers() {
        String deviceId = "a1b2c3d4e5f60708";
        assertEquals("Pixel 8", ChildDisplayName.resolve(
                deviceId, deviceId,
                "conn_1750000000000_0123456789abcdef0123456789abcdef",
                "Pixel 8"));
    }

    @Test
    public void rejectsMojibakeAndLongMachineTokens() {
        assertEquals(ChildDisplayName.FALLBACK, ChildDisplayName.resolve(
                "child-1", "Ã°Å¸â€œÂ±",
                "h1R8zSL0uKr5dNq4vYx2pWc9AbCd"));
    }
}
