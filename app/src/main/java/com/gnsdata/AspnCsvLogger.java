//package com.gnsdata;
//
//import android.content.Context;
//
//import java.io.File;
//import java.io.FileOutputStream;
//import java.io.OutputStreamWriter;
//import java.io.Writer;
//import java.nio.charset.StandardCharsets;
//import java.text.DecimalFormatSymbols;
//import java.text.SimpleDateFormat;
//import java.util.Date;
//import java.util.Locale;
//
///** CSV mirror for ASPN messages (Excel-friendly, UTF-8 BOM, CRLF). */
//public class AspnCsvLogger {
//    private final Writer imu, baro, sat;
//    private final char delim;
//
//    public AspnCsvLogger(Context ctx) {
//        File dir = new File(ctx.getExternalFilesDir(null), "logs");
//        if (!dir.exists()) dir.mkdirs();
//        this.delim = (DecimalFormatSymbols.getInstance().getDecimalSeparator() == ',') ? ';' : ',';
//        this.imu  = open(new File(dir, "aspn_imu.csv"));
//        this.baro = open(new File(dir, "aspn_barometer.csv"));
//        this.sat  = open(new File(dir, "aspn_satnav.csv"));
//        writeHeader(imu,  "Date","Time","Ax_mps2","Ay_mps2","Az_mps2","Gx_radps","Gy_radps","Gz_radps");
//        writeHeader(baro, "Date","Time","Pressure_Pa","Variance_Pa2");
//        writeHeader(sat,  "Date","Time","Constellation","Svid","Pseudorange_m","TDCP_m","TDCP_rate_mps");
//    }
//
//    private Writer open(File f) {
//        try {
//            boolean fresh = !f.exists() || f.length()==0;
//            FileOutputStream fos = new FileOutputStream(f, true);
//            OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
//            if (fresh) { w.write('\uFEFF'); w.flush(); }
//            return w;
//        } catch (Exception e) { return null; }
//    }
//
//    private void writeHeader(Writer w, String... cols) {
//        if (w==null) return;
//        try {
//            for (int i=0;i<cols.length;i++) { if (i>0) w.write(delim); w.write(cols[i]); }
//            w.write("\r\n"); w.flush();
//        } catch (Exception ignored) {}
//    }
//
//    private static String d(long ms, String pat) {
//        return new SimpleDateFormat(pat, Locale.US).format(new Date(ms));
//    }
//
//    public void logImu(long wallMs, double ax,double ay,double az,double gx,double gy,double gz) {
//        write(imu, d(wallMs,"yyyy-MM-dd"), d(wallMs,"HH:mm:ss.SSS"),
//                fmt(ax),fmt(ay),fmt(az),fmt(gx),fmt(gy),fmt(gz));
//    }
//    public void logBaro(long wallMs, double pPa, double varPa2) {
//        write(baro, d(wallMs,"yyyy-MM-dd"), d(wallMs,"HH:mm:ss.SSS"), fmt(pPa), fmt(varPa2));
//    }
//    public void logSatnav(long wallMs, int constellation, int svid, Double pr, Double tdcp, Double rate) {
//        write(sat, d(wallMs,"yyyy-MM-dd"), d(wallMs,"HH:mm:ss.SSS"),
//                String.valueOf(constellation), String.valueOf(svid),
//                fmt(pr), fmt(tdcp), fmt(rate));
//    }
//
//    private void write(Writer w, Object... fields) {
//        if (w==null) return;
//        try {
//            for (int i=0;i<fields.length;i++) {
//                if (i>0) w.write(delim);
//                String s = fields[i]==null ? "" : fields[i].toString();
//                boolean q = s.indexOf(delim)>=0 || s.contains("\"") || s.contains("\n") || s.contains("\r");
//                if (q) s = "\"" + s.replace("\"","\"\"") + "\"";
//                w.write(s);
//            }
//            w.write("\r\n"); w.flush();
//        } catch (Exception ignored) {}
//    }
//
//    private static String fmt(Double x) { return x==null ? "" : String.format(Locale.US,"%.9f", x)
//            .replaceAll("0+$","").replaceAll("\\.$",""); }
//}
//
