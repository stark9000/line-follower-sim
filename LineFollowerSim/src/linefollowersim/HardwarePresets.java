package linefollowersim;

/**
 * Typical sizes to start from. These are generic starting points, not specific
 * products: always check your own datasheet / ruler and edit the fields after
 * picking a preset.
 */
final class HardwarePresets {

    static final class P {

        final String name;
        final double[] v;

        P(String name, double... v) {
            this.name = name;
            this.v = v;
        }
    }

    /** value: diameter (mm) */
    static final P[] WHEELS = {
        new P("32 mm - micro", 32),
        new P("42 mm - small", 42),
        new P("60 mm", 60),
        new P("65 mm - TT-motor wheel", 65),
        new P("70 mm", 70),
        new P("80 mm", 80),
        new P("90 mm - large", 90)
    };

    /**
     * values: no-load rpm at the wheel shaft, rated voltage, response time constant (ms, rough: bigger gearbox = slower),
     * stall torque at the wheel shaft (kg.cm, rough typical listing values - check your seller's figures)
     */
    static final P[] MOTORS = {
        new P("TT gear 1:48 (6 V, 200 rpm)", 200, 6, 45, 0.8),
        new P("N20 (6 V, 100 rpm)", 100, 6, 40, 1.6),
        new P("N20 (6 V, 200 rpm)", 200, 6, 30, 0.8),
        new P("N20 (6 V, 300 rpm)", 300, 6, 25, 0.55),
        new P("N20 (6 V, 600 rpm)", 600, 6, 18, 0.3),
        new P("N20 (6 V, 1000 rpm)", 1000, 6, 14, 0.18),
        new P("N20 (12 V, 300 rpm)", 300, 12, 22, 1.0),
        new P("N20 (12 V, 500 rpm)", 500, 12, 18, 0.6)
    };

    /** values: sensor count, pitch (mm) */
    static final P[] ARRAYS = {
        new P("3 \u00D7 20 mm pitch (wide)", 3, 20),
        new P("3 \u00D7 7.5 mm pitch (tight)", 3, 7.5),
        new P("5 \u00D7 15 mm pitch", 5, 15),
        new P("8 \u00D7 12 mm pitch", 8, 12),
        new P("8 \u00D7 9.5 mm (QTR-8 style)", 8, 9.5),
        new P("12 \u00D7 8 mm pitch", 12, 8),
        new P("16 \u00D7 8 mm pitch", 16, 8)
    };

    /** value: supply voltage (nominal) */
    static final P[] BATTERIES = {
        new P("4x AA NiMH (4.8 V)", 4.8),
        new P("4x AA alkaline (6.0 V)", 6.0),
        new P("1S Li-ion / LiPo (3.7 V)", 3.7),
        new P("2S Li-ion / LiPo (7.4 V)", 7.4),
        new P("3S LiPo (11.1 V)", 11.1),
        new P("9 V block (9.0 V)", 9.0)
    };

    /**
     * values: wheel base, wheel diameter, axle to sensors, sensor count, pitch,
     * sensor height, beam half-angle, rpm, rated V, battery V, load %, top-speed override, motor lag (ms),
     * wheel count (2 / 4), axle spacing (mm, 4 wheels)
     */
    static final P[] ROBOTS = {
        new P("Simulator default", 100, 65, 60, 8, 10, 10, 22, 200, 6, 6, 75, 0, 40, 2, 80),
        new P("Micro (N20, 32 mm wheels)", 70, 32, 35, 8, 8, 6, 22, 300, 6, 6, 75, 0, 25, 2, 80),
        new P("TT motors, 65 mm wheels", 130, 65, 80, 8, 9.5, 8, 22, 200, 6, 6, 75, 0, 45, 2, 80),
        new P("Fast (N20 12 V, 42 mm wheels)", 90, 42, 60, 12, 8, 6, 22, 500, 12, 11.1, 75, 0, 18, 2, 80),
        new P("4WD TT-motor kit (65 mm)", 125, 65, 95, 8, 9.5, 8, 22, 200, 6, 7.4, 70, 0, 55, 4, 110)
    };

    /** values: arena width, height (mm) */
    static final P[] ARENAS = {
        new P("Default (800 \u00D7 480 mm)", 800, 480),
        new P("A3 sheet (420 \u00D7 297 mm)", 420, 297),
        new P("A2 sheet (594 \u00D7 420 mm)", 594, 420),
        new P("A1 sheet (841 \u00D7 594 mm)", 841, 594),
        new P("A0 sheet (1189 \u00D7 841 mm)", 1189, 841),
        new P("1 \u00D7 1 m", 1000, 1000),
        new P("2 \u00D7 1 m", 2000, 1000),
        new P("2 \u00D7 2 m", 2000, 2000),
        new P("4 \u00D7 8 ft (2440 \u00D7 1220 mm)", 2440, 1220),
        new P("3 \u00D7 2 m", 3000, 2000),
        new P("3 \u00D7 3 m", 3000, 3000)
    };

    private HardwarePresets() {
    }

    /** Menu items: a prompt followed by the preset names. */
    static String[] menu(String prompt, P[] list) {
        String[] r = new String[list.length + 1];
        r[0] = prompt;
        for (int i = 0; i < list.length; i++) {
            r[i + 1] = list[i].name;
        }
        return r;
    }
}
