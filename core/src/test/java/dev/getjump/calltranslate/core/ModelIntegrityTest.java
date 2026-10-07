package dev.getjump.calltranslate.core;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Test;

public class ModelIntegrityTest {
  private static final ModelFile SPEC =
      new ModelFile(
          "weights.bin", 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");

  @Test
  public void sameLengthCorruptionIsRejectedBeforeLoading() throws Exception {
    File root = Files.createTempDirectory("integrity").toFile();
    Files.writeString(new File(root, SPEC.name()).toPath(), "bad");
    assertThrows(IOException.class, () -> new ModelInstaller(root).verify(SPEC, () -> false));
  }

  @Test
  public void verifiedLocalWeightsCanLoadWithoutNetwork() throws Exception {
    File root = Files.createTempDirectory("integrity").toFile();
    Files.writeString(new File(root, SPEC.name()).toPath(), "abc");
    new ModelInstaller(root).verify(SPEC, () -> false);
  }

  @Test
  public void verificationCanBeCanceledBeforeModelLoad() throws Exception {
    File root = Files.createTempDirectory("integrity").toFile();
    Files.writeString(new File(root, SPEC.name()).toPath(), "abc");
    assertThrows(IOException.class, () -> new ModelInstaller(root).verify(SPEC, () -> true));
  }
}
