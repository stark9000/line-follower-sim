package linefollowersim;

/**
 * Rough timing profile of a microcontroller running the line-follower loop.
 * Loop compute time = (sensors * readCost + mathCost) / clock.
 * The cycle counts are ballpark figures (Arduino analogRead ~104 us at 16 MHz,
 * soft-float maths on AVR/ESP8266, hardware FPU on ESP32/Teensy ...). Every
 * number can be edited in the MCU tab.
 */
public final class McuProfile {

    public final String name;
    public final double mhz, adcCycles, digCycles, mathCycles;

    McuProfile(String name, double mhz, double adcCycles, double digCycles, double mathCycles) {
        this.name = name;
        this.mhz = mhz;
        this.adcCycles = adcCycles;
        this.digCycles = digCycles;
        this.mathCycles = mathCycles;
    }

    public static final McuProfile[] ALL = {
        new McuProfile("Uno / Nano (16 MHz)", 16, 1664, 64, 5000),
        new McuProfile("Pro Mini 3.3 V (8 MHz)", 8, 1664, 64, 5000),
        new McuProfile("ESP8266 (80 MHz)", 80, 8000, 40, 3000),
        new McuProfile("ESP8266 (160 MHz)", 160, 8000, 40, 3000),
        new McuProfile("STM32 Blue Pill (72 MHz)", 72, 600, 20, 3500),
        new McuProfile("ESP32 (240 MHz)", 240, 4800, 30, 600),
        new McuProfile("Teensy 4.0 (600 MHz)", 600, 3000, 20, 300),
        new McuProfile("Ideal (no CPU limit)", 1000, 0, 0, 0),
        new McuProfile("RP2040 Pico (133 MHz)", 133, 530, 40, 8000)    // last, so saved workspaces keep their choice
    };

    public static String[] names() {
        String[] n = new String[ALL.length];
        for (int i = 0; i < n.length; i++) {
            n[i] = ALL[i].name;
        }
        return n;
    }
}
