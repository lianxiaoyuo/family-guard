package com.familyguard.app;
public final class CoordinateReferenceSelfTest {
    private static int n;
    static void ok(boolean v,String m){n++;if(!v)throw new AssertionError(m);}
    public static void main(String[] args) {
        double lat=39.9042,lon=116.4074;
        double[] gcj=CoordinateReference.wgsToGcj(lat,lon);
        ok(Math.abs(gcj[0]-lat)>0.0001 && Math.abs(gcj[1]-lon)>0.0001,"China offset");
        double[] back=CoordinateReference.gcjToWgs(gcj[0],gcj[1]);
        ok(AccuracyPolicy.distanceMeters(lat,lon,back[0],back[1]) < 0.5,"round trip <0.5m");
        double[] tokyo=CoordinateReference.gcjToWgs(35.6812,139.7671);
        ok(tokyo[0]==35.6812&&tokyo[1]==139.7671,"outside China unchanged");
        double[] sh=CoordinateReference.wgsToGcj(31.2304,121.4737);
        double[] sh2=CoordinateReference.gcjToWgs(sh[0],sh[1]);
        ok(AccuracyPolicy.distanceMeters(31.2304,121.4737,sh2[0],sh2[1])<0.5,"Shanghai roundtrip");
        System.out.println("CoordinateReference: "+n+" PASS");
    }
}
