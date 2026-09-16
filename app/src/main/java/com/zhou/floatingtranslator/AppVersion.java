package com.zhou.floatingtranslator;

import java.math.BigInteger;
import java.util.Locale;

final class AppVersion {
    static int compare(String a, String b) {
        String[] x = clean(a).split("-", 2), y = clean(b).split("-", 2);
        String[] coreX = x[0].split("\\."), coreY = y[0].split("\\.");
        for (int i = 0; i < Math.max(coreX.length, coreY.length); i++) {
            int c = number(i < coreX.length ? coreX[i] : "0").compareTo(number(i < coreY.length ? coreY[i] : "0"));
            if (c != 0) return c;
        }
        if (x.length != y.length) return x.length == 1 ? 1 : -1;
        if (x.length == 1) return 0;
        String[] preX = x[1].split("[.-]|(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
        String[] preY = y[1].split("[.-]|(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
        for (int i = 0; i < Math.min(preX.length, preY.length); i++) {
            boolean nx = preX[i].matches("[0-9]+"), ny = preY[i].matches("[0-9]+");
            int c = nx && ny ? number(preX[i]).compareTo(number(preY[i]))
                : nx != ny ? (nx ? -1 : 1) : preX[i].compareTo(preY[i]);
            if (c != 0) return c;
        }
        return Integer.compare(preX.length, preY.length);
    }
    private static String clean(String value) {
        return value == null ? "0" : value.trim().toLowerCase(Locale.ROOT).replaceFirst("^v", "").split("\\+", 2)[0];
    }
    private static BigInteger number(String part) {
        try { return new BigInteger(part); } catch (NumberFormatException e) { return BigInteger.ZERO; }
    }
}
