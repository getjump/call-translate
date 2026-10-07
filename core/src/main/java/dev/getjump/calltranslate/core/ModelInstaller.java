package dev.getjump.calltranslate.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.BooleanSupplier;

public final class ModelInstaller {
  private final File root;

  public ModelInstaller(File root) {
    this.root = root;
  }

  private static String hex(byte[] bytes) {
    StringBuilder output = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      output.append(Character.forDigit((value >>> 4) & 15, 16));
      output.append(Character.forDigit(value & 15, 16));
    }
    return output.toString();
  }

  public void verify(ModelFile spec, BooleanSupplier canceled) throws IOException {
    File file = new File(root, spec.name());
    if (!file.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()))
      throw new IOException("model path escapes installation directory");
    if (!file.isFile() || file.length() != spec.size())
      throw new IOException("model size mismatch");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = Files.newInputStream(file.toPath())) {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) {
          if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new IOException("verification canceled");
          digest.update(buffer, 0, count);
        }
      }
      if (canceled.getAsBoolean()) throw new IOException("verification canceled");
      if (!hex(digest.digest()).equalsIgnoreCase(spec.sha256()))
        throw new IOException("model checksum mismatch: " + spec.name());
    } catch (NoSuchAlgorithmException error) {
      throw new IOException("SHA-256 unavailable", error);
    }
  }

  public File install(ModelFile spec, InputStream source, BooleanSupplier canceled)
      throws IOException {
    if (spec.name().isBlank()
        || spec.name().contains("/")
        || spec.name().contains("\\")
        || spec.name().equals(".")
        || spec.name().equals("..")) {
      throw new IOException("invalid model filename");
    }
    Files.createDirectories(root.toPath());
    File target = new File(root, spec.name());
    if (!target.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())) {
      throw new IOException("model path escapes installation directory");
    }
    File pending = Files.createTempFile(root.toPath(), "install-", ".part").toFile();
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      long size = 0;
      try (InputStream input = source;
          OutputStream output = Files.newOutputStream(pending.toPath())) {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) {
          if (canceled.getAsBoolean()) throw new IOException("download canceled");
          size += count;
          if (spec.size() > 0 && size > spec.size())
            throw new IOException("model exceeds expected size");
          output.write(buffer, 0, count);
          digest.update(buffer, 0, count);
        }
      }
      if (canceled.getAsBoolean()) throw new IOException("download canceled");
      if (spec.size() > 0 && size != spec.size()) throw new IOException("model size mismatch");
      if (!hex(digest.digest()).equalsIgnoreCase(spec.sha256())) {
        throw new IOException("model checksum mismatch");
      }
      Files.move(
          pending.toPath(),
          target.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
      return target;
    } catch (NoSuchAlgorithmException error) {
      throw new IOException("SHA-256 unavailable", error);
    } finally {
      Files.deleteIfExists(pending.toPath());
    }
  }
}
