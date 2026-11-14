package com.familyguard.app;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Android Keystore AES-GCM. Private locations and session tokens are never saved as plaintext. */
final class Crypto {
    private static final String ALIAS="familyguard-v2-data-key";
    private Crypto() {}
    private static synchronized SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return g.generateKey();
    }
    static String encrypt(String plain) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());
        byte[] iv=c.getIV();byte[] ciphertext=c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return "v2:"+Base64.encodeToString(iv,Base64.NO_WRAP)+":"+Base64.encodeToString(ciphertext,Base64.NO_WRAP);
    }
    static String decrypt(String encrypted) throws Exception {
        String[] parts=encrypted.split(":",3);
        if(parts.length!=3||!"v2".equals(parts[0]))throw new IllegalArgumentException("unsupported encryption version");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv=Base64.decode(parts[1],Base64.NO_WRAP);
        if(iv.length!=12)throw new IllegalArgumentException("invalid IV");
        c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
        return new String(c.doFinal(Base64.decode(parts[2],Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
}
