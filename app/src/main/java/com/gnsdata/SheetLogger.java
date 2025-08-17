package com.gnsdata;
/*
Created by: Katki
Date: 08/14 - 08/22
Github: https://github.com/incognitosushiroll/GNSSData.git

This file within the GNSSData project will write to an android's external files directory (Files app).
This class is one tiny thread for writing the GNSS data to a .csv file.
 */

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;


public class SheetLogger {

    // --- public getters (so you can show/share paths in UI) ---
    public File sensorsFile() { return sensorsFile; }
    public File gnssFile()    { return gnssFile;    }
    public String sensorsPath() { return sensorsFile.getAbsolutePath(); }
    public String gnssPath()    { return gnssFile.getAbsolutePath();    }

    // --- internals ---
    private final File sensorsFile;
    private final File gnssFile;
    private Writer sensorsWriter;
    private Writer gnssWriter;
    private final char delimiter;
    private final boolean writeBomOnEmpty;

    // Thread-safe date/time formatters (we create fresh per call; SimpleDateFormat isn’t thread-safe).
    private static String fmtDate(long wallMs) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(wallMs));
    }
    private static String fmtTime(long wallMs) {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(wallMs));
    }

    /** Factory: put logs under app-specific *external* storage (easy to grab via Files/USB; no permission). */
    public static SheetLogger atExternal(Context ctx, char delimiter, boolean bom) {
        File base = ctx.getExternalFilesDir(null);
        File dir = new File(base, "logs");
        if (!dir.exists()) dir.mkdirs();
        return new SheetLogger(
                new File(dir, "sensors_log.csv"),
                new File(dir, "gnss_log.csv"),
                delimiter, bom);
    }

    /** Excel normally wants ';' when decimal separator is ',' — this picks for you. */
    public static char defaultExcelDelimiterForLocale() {
        char decimalSep = DecimalFormatSymbols.getInstance().getDecimalSeparator();
        return (decimalSep == ',') ? ';' : ',';
    }

    // Constructor: open both writers and write headers if files empty.
    private SheetLogger(File sensors, File gnss, char delimiter, boolean bom) {
        this.sensorsFile = sensors;
        this.gnssFile = gnss;
        this.delimiter = delimiter;
        this.writeBomOnEmpty = bom;
        this.sensorsWriter = openWriter(sensors);
        this.gnssWriter = openWriter(gnss);
        writeSensorsHeaderIfEmpty();
        writeGnssHeaderIfEmpty();
    }

    private Writer openWriter(File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            boolean newFile = !file.exists() || file.length() == 0;
            FileOutputStream fos = new FileOutputStream(file, true);
            OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            if (newFile && writeBomOnEmpty) {
                // UTF-8 BOM so Excel detects encoding (important for “m/s²” characters etc.)
                w.write('\uFEFF');
                w.flush();
            }
            return w;
        } catch (IOException e) {
            return null; // degrade gracefully: no crash
        }
    }

    // ========================= SENSORS =========================

    /** Header: one-time, wide columns for sensors. */
    private synchronized void writeSensorsHeaderIfEmpty() {
        if (sensorsWriter == null || !isEffectivelyEmpty(sensorsFile)) return;
        writeSensorsRowRaw(
                "Date", "Time", "ElapsedNs",
                "Baro_hPa",
                "Accel_X_mps2", "Accel_Y_mps2", "Accel_Z_mps2",
                "Gyro_X_radps", "Gyro_Y_radps", "Gyro_Z_radps"
        );
    }

    /**
     * Write one *wide* sensor row.
     * Any null value → blank cell (so columns stay aligned, and Excel shows empty).
     */
    public synchronized void logSensorsWide(
            long wallMs, long elapsedNs,
            Float baro_hPa,
            Float ax, Float ay, Float az,
            Float gx, Float gy, Float gz
    ) {
        if (sensorsWriter == null) return;
        writeSensorsRowRaw(
                fmtDate(wallMs), fmtTime(wallMs), String.valueOf(elapsedNs),
                toCsv(baro_hPa),
                toCsv(ax), toCsv(ay), toCsv(az),
                toCsv(gx), toCsv(gy), toCsv(gz)
        );
    }

    private void writeSensorsRowRaw(Object... fields) {
        writeRow(sensorsWriter, fields);
    }

    // =========================== GNSS ==========================

    /** Header for GNSS per-satellite rows. */
    private synchronized void writeGnssHeaderIfEmpty() {
        if (gnssWriter == null || !isEffectivelyEmpty(gnssFile)) return;
        writeGnssRowRaw(
                "Date", "Time", "ElapsedNs",
                "Constellation", "Svid",
                "Pseudorange_m", "TDCP_m", "TDCP_rate_mps"
        );
    }

    /**
     * GNSS row per satellite per epoch.
     * tdcpMeters / tdcpRate may be null (when ADR not valid yet) → blank cells.
     */
    public synchronized void logGnssPerSv(
            long wallMs, long elapsedNs,
            int constellation, int svid,
            double prMeters,
            Double tdcpMeters, Double tdcpRateMps
    ) {
        if (gnssWriter == null) return;
        writeGnssRowRaw(
                fmtDate(wallMs), fmtTime(wallMs), String.valueOf(elapsedNs),
                constellation, svid,
                toCsv(prMeters), toCsv(tdcpMeters), toCsv(tdcpRateMps)
        );
    }

    private void writeGnssRowRaw(Object... fields) {
        writeRow(gnssWriter, fields);
    }

    // ======================= CSV glue =========================

    private void writeRow(Writer w, Object... fields) {
        if (w == null) return;
        try {
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) w.write(delimiter);
                w.write(escapeCsvField(fields[i]));
            }
            w.write("\r\n"); // CRLF is Excel-friendly
            w.flush();
        } catch (IOException ignored) {}
    }

    /** Convert Java object → CSV cell; numbers use Locale.US to ensure '.' decimal point. Null → blank. */
    private String escapeCsvField(Object v) {
        if (v == null) return "";
        String s;
        if (v instanceof Float || v instanceof Double) {
            s = String.format(Locale.US, "%.9f", ((Number) v).doubleValue());
            s = s.indexOf('.') >= 0 ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
        } else {
            s = String.valueOf(v);
        }
        boolean needsQuotes = s.indexOf(delimiter) >= 0 || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (needsQuotes) s = "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
    // Add this method inside SheetLogger (e.g., near other helpers)
    private boolean isEffectivelyEmpty(File f) {
        long len = f.length();
        // If we chose to write a BOM, a just-created file will be 3 bytes long.
        return len == 0 || (writeBomOnEmpty && len <= 3);
    }
    public synchronized void ensureHeaders() {
        writeSensorsHeaderIfEmpty();
        writeGnssHeaderIfEmpty();
    }
    private static String toCsv(Float f)  { return f == null ? "" : String.format(Locale.US, "%.6f", f); }
    private static String toCsv(Double d) { return d == null ? "" : String.format(Locale.US, "%.9f", d); }

    public synchronized void close() {
        try { if (sensorsWriter != null) sensorsWriter.close(); } catch (IOException ignored) {}
        try { if (gnssWriter    != null) gnssWriter.close();    } catch (IOException ignored) {}
        sensorsWriter = null;
        gnssWriter = null;
    }
}