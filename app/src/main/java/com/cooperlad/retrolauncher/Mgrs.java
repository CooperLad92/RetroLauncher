package com.cooperlad.retrolauncher;

import java.util.Locale;

/** WGS84 lat/lon to a 1 km MGRS grid reference, e.g. "29U PV 84 32". */
final class Mgrs {
    private Mgrs() { }

    static String fromLatLon(double lat, double lon) {
        if (lat < -80 || lat > 84) return "OUT OF GRID";
        int zone = (int) Math.floor((lon + 180) / 6) + 1;
        if (lat >= 56 && lat < 64 && lon >= 3 && lon < 12) zone = 32;
        if (lat >= 72) {
            if (lon >= 0 && lon < 9) zone = 31;
            else if (lon >= 9 && lon < 21) zone = 33;
            else if (lon >= 21 && lon < 33) zone = 35;
            else if (lon >= 33 && lon < 42) zone = 37;
        }
        double a = 6378137.0, f = 1 / 298.257223563, k0 = 0.9996;
        double e2 = f * (2 - f), ep2 = e2 / (1 - e2);
        double lon0 = Math.toRadians((zone - 1) * 6 - 180 + 3);
        double phi = Math.toRadians(lat), lam = Math.toRadians(lon);
        double sin = Math.sin(phi), cos = Math.cos(phi), tan = Math.tan(phi);
        double n = a / Math.sqrt(1 - e2 * sin * sin);
        double t = tan * tan, c = ep2 * cos * cos, aa = cos * (lam - lon0);
        double m = a * ((1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256) * phi
                - (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2 * e2 * e2 / 1024) * Math.sin(2 * phi)
                + (15 * e2 * e2 / 256 + 45 * e2 * e2 * e2 / 1024) * Math.sin(4 * phi)
                - (35 * e2 * e2 * e2 / 3072) * Math.sin(6 * phi));
        double east = k0 * n * (aa + (1 - t + c) * Math.pow(aa, 3) / 6
                + (5 - 18 * t + t * t + 72 * c - 58 * ep2) * Math.pow(aa, 5) / 120) + 500000;
        double north = k0 * (m + n * tan * (aa * aa / 2 + (5 - t + 9 * c + 4 * c * c) * Math.pow(aa, 4) / 24
                + (61 - 58 * t + t * t + 600 * c - 330 * ep2) * Math.pow(aa, 6) / 720));
        if (lat < 0) north += 10000000;

        char band = "CDEFGHJKLMNPQRSTUVWXX".charAt((int) Math.floor((lat + 80) / 8));
        String[] colSets = {"ABCDEFGH", "JKLMNPQR", "STUVWXYZ"};
        int e100 = Math.max(1, Math.min(8, (int) Math.floor(east / 100000)));
        char col = colSets[(zone - 1) % 3].charAt(e100 - 1);
        String rows = "ABCDEFGHJKLMNPQRSTUV";
        int n100 = (int) Math.floor(north / 100000) % 20;
        char row = rows.charAt((n100 + (zone % 2 == 0 ? 5 : 0)) % 20);
        int ek = (int) ((east % 100000) / 1000), nk = (int) ((north % 100000) / 1000);
        return String.format(Locale.US, "%d%c %c%c %02d %02d", zone, band, col, row, ek, nk);
    }
}
