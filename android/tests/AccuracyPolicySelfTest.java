package com.familyguard.app;

public final class AccuracyPolicySelfTest {
    private static int n=0;
    static void check(boolean v,String why){n++;if(!v)throw new AssertionError(why);}
    public static void main(String[] args){
        check(AccuracyPolicy.valid(39.9,116.4,3.5f,0),"precise valid");
        check(AccuracyPolicy.valid(-89.9,-179,2500,14000),"coarse fallback valid");
        check(!AccuracyPolicy.valid(91,116,5,0),"invalid latitude");
        check(!AccuracyPolicy.valid(39,181,5,0),"invalid longitude");
        check(!AccuracyPolicy.valid(39,116,Float.NaN,0),"nan accuracy");
        check(!AccuracyPolicy.valid(39,116,5,15001),"stale fix");
        check(!AccuracyPolicy.valid(39,116,-1,0),"negative accuracy");
        check(AccuracyPolicy.accept("gps",3,10000,null,0,0),"first fix");
        check(!AccuracyPolicy.accept("gps",2,10000,"gps",4,10000),"out of order");
        check(!AccuracyPolicy.accept("network",40,12000,"gps",5,10000),"reject network downgrade");
        check(AccuracyPolicy.accept("gps",8,12000,"network",40,10000),"switch to gps");
        check(AccuracyPolicy.accept("network",300,25000,"gps",3,10000),"fallback when gps stale");
        check(!AccuracyPolicy.accept("gps",500,11500,"gps",5,10000),"reject quality collapse");
        check(AccuracyPolicy.accept("gps",10,18000,"gps",5,10000),"recover after few seconds");
        check(AccuracyPolicy.SEND_EVERY_MS == 1000,"send frequency");
        check(AccuracyPolicy.PARENT_REFRESH_MS == 1000,"parent polling");
        check(!AccuracyPolicy.accept("amap",9,13000,"gps",2,11000),"recent precise GNSS outranks fused result");
        check(AccuracyPolicy.accept("amap",3,13000,"gps",8,11000),"better AMap fusion accepted");
        check(AccuracyPolicy.accept("amap",30,20000,"gps",2,11000),"fresh fallback after stale GNSS");
        check(AccuracyPolicy.accept("fused",3,12500,"gps",5,10000),"fused can improve GPS");
        check(!AccuracyPolicy.accept("fused",70,12500,"gps",5,10000),"coarse fused does not replace GPS");
        check(AccuracyPolicy.accept("fused",35,18000,"gps",5,10000),"fused can recover after GPS signal loss");
        check(!AccuracyPolicy.accept("fused",5,12000,"gps",4,10000),"similar fused result must not cause GNSS jitter");
        check(!AccuracyPolicy.accept("gps",12,12000,"fused",3,10000),"worse GPS must not displace excellent fused fix");
        check(AccuracyPolicy.distanceMeters(39.9,116.4,39.9,116.4)<0.01,"same point distance");
        check(AccuracyPolicy.distanceMeters(39.9,116.4,39.901,116.4)>110,"100m latitude distance");
        check(AccuracyPolicy.plausible(10,3,3,1000,0,0),"normal walking");
        check(!AccuracyPolicy.plausible(150,3,3,1000,0,0),"one-second teleport rejected");
        check(AccuracyPolicy.plausible(60,5,5,1000,32,35),"fast-vehicle motion retained");
        check(AccuracyPolicy.plausible(500,5,5,15000,0,0),"recover after signal gap");
        check(!AccuracyPolicy.plausible(Double.NaN,5,5,1000,0,0),"invalid distance");
        System.out.println("AccuracyPolicy: "+n+" checks PASS");
    }
}
