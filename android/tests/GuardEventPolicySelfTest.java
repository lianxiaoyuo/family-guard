package com.familyguard.app;
public class GuardEventPolicySelfTest {
    private static void check(boolean ok){if(!ok)throw new AssertionError("GuardEventPolicy failed");}
    public static void main(String[] ignored){
        long now=2000000000000L;
        check(GuardEventPolicy.priority("sos")>GuardEventPolicy.priority("exit"));
        check(GuardEventPolicy.priority("exit")>GuardEventPolicy.priority("low_battery"));
        check(GuardEventPolicy.priority("unrecognized")==0);
        check(GuardEventPolicy.shouldPresent("sos",now-5*3600000L,now));
        check(!GuardEventPolicy.shouldPresent("sos",now-7*3600000L,now));
        check(GuardEventPolicy.shouldPresent("offline",now-5*60000L,now));
        check(GuardEventPolicy.shouldPresent("location_stale",now-5*60000L,now));
        check(!GuardEventPolicy.shouldPresent("low_battery",now-30*60000L,now));
        check(!GuardEventPolicy.shouldPresent("media",now,now));
        check(GuardEventPolicy.shouldPresent("sos",now+1000L,now));
        check(!GuardEventPolicy.shouldPresent("sos",now+6*60000L,now));
        String s="";for(int i=0;i<150;i++)s=GuardEventPolicy.remember(s,"id"+i);
        check(s.split(",").length==96);
        check(GuardEventPolicy.alreadySeen(s,"id149"));
        check(!GuardEventPolicy.alreadySeen(s,"id0"));
        System.out.println("GuardEventPolicySelfTest PASS");
    }
}
