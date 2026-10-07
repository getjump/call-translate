package dev.getjump.calltranslate.cloud;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class CredentialStore {
  private static final Object lock = new Object();
  private final AtomicFile file;
  private final String alias;

  public CredentialStore(Context context) {
    Context application = context.getApplicationContext();
    file = new AtomicFile(new File(application.getNoBackupFilesDir(), "elevenlabs.credential"));
    alias = application.getPackageName() + ".elevenlabs.credential.v1";
  }

  public String load() throws Exception {
    synchronized (lock) {
      byte[] encoded;
      try (var input = file.openRead()) {
        encoded = input.readNBytes(8193);
      } catch (FileNotFoundException unset) {
        return "";
      }
      if (encoded.length < 30 || encoded.length > 8192 || encoded[0] != 1 || encoded[1] != 12)
        throw new IllegalStateException("Stored ElevenLabs key is damaged; save it again");
      KeyStore store = keyStore();
      SecretKey key = (SecretKey) store.getKey(alias, null);
      if (key == null)
        throw new IllegalStateException("Stored ElevenLabs key cannot be decrypted; save it again");
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, encoded, 2, 12));
      cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
      return new String(cipher.doFinal(encoded, 14, encoded.length - 14), StandardCharsets.UTF_8);
    }
  }

  public void save(String apiKey) throws Exception {
    String value = ElevenLabsApi.validatedKey(apiKey);
    synchronized (lock) {
      KeyStore store = keyStore();
      SecretKey key = (SecretKey) store.getKey(alias, null);
      if (key == null) {
        KeyGenerator generator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(
            new KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        key = generator.generateKey();
      }
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key);
      cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
      byte[] iv = cipher.getIV();
      byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
      byte[] encoded =
          ByteBuffer.allocate(2 + iv.length + encrypted.length)
              .put((byte) 1)
              .put((byte) iv.length)
              .put(iv)
              .put(encrypted)
              .array();
      var output = file.startWrite();
      try {
        output.write(encoded);
        file.finishWrite(output);
      } catch (Exception error) {
        file.failWrite(output);
        throw error;
      }
    }
  }

  public void clear() throws Exception {
    synchronized (lock) {
      file.delete();
      if (file.getBaseFile().exists())
        throw new IllegalStateException("Cannot delete stored ElevenLabs key");
      KeyStore store = keyStore();
      if (store.containsAlias(alias)) store.deleteEntry(alias);
    }
  }

  private static KeyStore keyStore() throws Exception {
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    return store;
  }
}
