package com.familyguard.app;

/** WGS84 -> GCJ-02 and inverse for map display, used only when optional AMap returns GCJ. */
final class CoordinateReference {
    private CoordinateReference(){}
    private static final double A=6378245.0, EE=0.006693421622965943;
    static double[] gcjToWgs(double lat,double lon){
        if(outsideChina(lat,lon))return new double[]{lat,lon};
        double wLat=lat,wLon=lon;
        for(int i=0;i<5;i++){
            double[] gcj=wgsToGcj(wLat,wLon);
            wLat-=gcj[0]-lat;wLon-=gcj[1]-lon;
        }
        return new double[]{wLat,wLon};
    }
    static double[] wgsToGcj(double lat,double lon){
        if(outsideChina(lat,lon))return new double[]{lat,lon};
        double x=lon-105.0,y=lat-35.0;
        double dLat=-100+2*x+3*y+0.2*y*y+0.1*x*y+0.2*Math.sqrt(Math.abs(x));
        dLat+=(20*Math.sin(6*x*Math.PI)+20*Math.sin(2*x*Math.PI))*2/3;
        dLat+=(20*Math.sin(y*Math.PI)+40*Math.sin(y/3*Math.PI))*2/3;
        dLat+=(160*Math.sin(y/12*Math.PI)+320*Math.sin(y*Math.PI/30))*2/3;
        double dLon=300+x+2*y+0.1*x*x+0.1*x*y+0.1*Math.sqrt(Math.abs(x));
        dLon+=(20*Math.sin(6*x*Math.PI)+20*Math.sin(2*x*Math.PI))*2/3;
        dLon+=(20*Math.sin(x*Math.PI)+40*Math.sin(x/3*Math.PI))*2/3;
        dLon+=(150*Math.sin(x/12*Math.PI)+300*Math.sin(x/30*Math.PI))*2/3;
        double rad=lat/180*Math.PI;
        double magic=1-EE*Math.sin(rad)*Math.sin(rad);
        double sqrt=Math.sqrt(magic);
        dLat=dLat*180/((A*(1-EE))/(magic*sqrt)*Math.PI);
        dLon=dLon*180/(A/sqrt*Math.cos(rad)*Math.PI);
        return new double[]{lat+dLat,lon+dLon};
    }
    private static boolean outsideChina(double lat,double lon){return lon<72.004||lon>137.8347||lat<0.8293||lat>55.8271;}
}
