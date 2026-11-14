package com.familyguard.app;
public final class ServerAddressSelfTest {
    private static int n=0;
    private static void accepted(String uri, boolean debug) {
        n++; if (!uri.trim().replaceAll("/+$", "").equals(ServerAddress.normalize(uri,debug)))
            throw new AssertionError("bad normalization: "+uri);
    }
    private static void denied(String uri, boolean debug) {
        n++;try{ServerAddress.normalize(uri,debug);throw new AssertionError("accepted unsafe: "+uri);}
        catch(IllegalArgumentException expected){}
    }
    public static void main(String[] ignored){
        accepted("https://guard.example.com",false);
        accepted("https://guard.example.com:443/",false);
        accepted("http://192.168.1.100:8088",true);
        accepted("http://10.0.2.2:8088",true);
        accepted("http://172.16.0.10:8088",true);
        accepted("http://172.31.0.10:8088",true);
        accepted("http://127.0.0.1:8088",true);
        denied("http://guard.example.com",true);
        denied("http://8.8.8.8:8088",true);
        denied("http://192.168.1.100:8088",false);
        denied("http://172.32.0.1:8088",true);
        denied("http://fake.localhost.com:8088",true);
        denied("https://user:password@guard.example.com",true);
        denied("https://guard.example.com/?token=leak",true);
        denied("javascript:alert(1)",true);
        denied("https://guard.example.com/v1",false);
        denied("http://01.02.03.004:8088",false);
        denied("",true);
        System.out.println("ServerAddress: "+n+" checks PASS");
    }
}
