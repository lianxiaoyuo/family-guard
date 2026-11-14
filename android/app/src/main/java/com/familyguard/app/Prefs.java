package com.familyguard.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

final class Prefs {
    private Prefs() {}
    static SharedPreferences store(Context c){return c.getSharedPreferences("family_guard_private",Context.MODE_PRIVATE);}
    static String get(Context c,String key){return store(c).getString(key,"");}
    static void put(Context c,String key,String val){store(c).edit().putString(key,val).apply();}
    static boolean isSharing(Context c){return store(c).getBoolean("sharing",false);}
    static void setSharing(Context c,boolean v){store(c).edit().putBoolean("sharing",v).commit();}
    static String role(Context c){return get(c,"role");}
    static synchronized String token(Context c){
        String secured=get(c,"token_secure");
        if(!secured.isEmpty()){
            try{return Crypto.decrypt(secured);}catch(Exception ex){Log.e("FamilyGuard","Cannot decrypt identity",ex);return "";}
        }
        // One-time migration of the V1 plaintext token; do not keep plaintext if migration fails.
        String legacy=get(c,"token");
        if(legacy.isEmpty())return "";
        try{
            String encrypted=Crypto.encrypt(legacy);
            if(!store(c).edit().putString("token_secure",encrypted).remove("token").commit())return "";
            return legacy;
        }catch(Exception ex){Log.e("FamilyGuard","Legacy token upgrade failed",ex);return "";}
    }
    static synchronized void setIdentity(Context c,String role,String token){
        try{
            String cipher=Crypto.encrypt(token);
            if(!store(c).edit().putString("role",role).putString("token_secure",cipher).remove("token").putBoolean("sharing",false).commit())
                throw new IllegalStateException("Failed to persist account");
        }catch(Exception e){throw new IllegalStateException("Android Keystore cannot save account",e);}
    }
    static synchronized void clearIdentity(Context c){
        store(c).edit().remove("role").remove("token").remove("token_secure").remove("pair_code").remove("pair_expiry").putBoolean("sharing",false).commit();
    }
}
