package com.familyguard.app;

/** Pure eligibility policy for parent-side alert presentation; testable on a JVM. */
final class GuardEventPolicy {
    private GuardEventPolicy() {}
    static boolean isAlertKind(String kind) {
        return "sos".equals(kind) || "exit".equals(kind) || "enter".equals(kind) ||
               "offline".equals(kind) || "low_battery".equals(kind) || "location_stale".equals(kind);
    }
    /** Higher priority alerts are presented first even if later telemetry arrives. */
    static int priority(String kind) {
        if("sos".equals(kind))return 100;
        if("exit".equals(kind))return 80;
        if("offline".equals(kind)||"location_stale".equals(kind))return 70;
        if("low_battery".equals(kind))return 40;
        if("enter".equals(kind))return 10;
        return 0;
    }
    static boolean shouldPresent(String kind,long eventMillis,long nowMillis) {
        if(!isAlertKind(kind) || eventMillis<=0)return false;
        long age=nowMillis-eventMillis;
        // Network clocks normally agree, but allow five minutes of device clock skew.
        long limit="sos".equals(kind)?6L*60*60*1000:15L*60*1000;
        return age>=-5L*60*1000 && age<=limit;
    }
    static boolean alreadySeen(String ids,String id) {
        return id==null || id.isEmpty() || (","+(ids==null?"":ids)+",").contains(","+id+",");
    }
    static String remember(String ids,String id) {
        String updated=(ids==null||ids.isEmpty()?id:ids+","+id);
        String[] parts=updated.split(",");
        if(parts.length<=96)return updated;
        StringBuilder out=new StringBuilder();
        for(int i=parts.length-96;i<parts.length;i++) {
            if(out.length()>0)out.append(',');
            out.append(parts[i]);
        }
        return out.toString();
    }
}
