package dev.getjump.calltranslate.core;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;

public class CoreContractsTest {
  private static final String ABC_SHA =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

  @Test
  public void mismatchPreservesInstalledModel() throws Exception {
    File root = Files.createTempDirectory("models").toFile();
    Files.writeString(new File(root, "weights.bin").toPath(), "old");
    ModelInstaller installer = new ModelInstaller(root);
    assertThrows(
        java.io.IOException.class,
        () ->
            installer.install(
                new ModelFile("weights.bin", 3, ABC_SHA),
                new ByteArrayInputStream("bad".getBytes()),
                () -> false));
    assertEquals("old", Files.readString(new File(root, "weights.bin").toPath()));
  }

  @Test
  public void verifiedDownloadBecomesInstalled() throws Exception {
    File root = Files.createTempDirectory("models").toFile();
    File installed =
        new ModelInstaller(root)
            .install(
                new ModelFile("weights.bin", 3, ABC_SHA),
                new ByteArrayInputStream("abc".getBytes()),
                () -> false);
    assertEquals("abc", Files.readString(installed.toPath()));
  }

  @Test
  public void pathEscapeIsRejected() throws Exception {
    File root = Files.createTempDirectory("models").toFile();
    assertThrows(
        java.io.IOException.class,
        () ->
            new ModelInstaller(root)
                .install(
                    new ModelFile("../escape.bin", 3, ABC_SHA),
                    new ByteArrayInputStream("abc".getBytes()),
                    () -> false));
  }

  @Test
  public void canceledDownloadPreservesInstalledModel() throws Exception {
    File root = Files.createTempDirectory("models").toFile();
    Files.writeString(new File(root, "weights.bin").toPath(), "old");
    assertThrows(
        java.io.IOException.class,
        () ->
            new ModelInstaller(root)
                .install(
                    new ModelFile("weights.bin", 3, ABC_SHA),
                    new ByteArrayInputStream("abc".getBytes()),
                    () -> true));
    assertEquals("old", Files.readString(new File(root, "weights.bin").toPath()));
  }

  @Test
  public void pcmByteBoundariesAndLittleEndianArePreserved() {
    AudioWindow window = new AudioWindow(3);
    assertTrue(window.append(new byte[] {0}, 1).isEmpty());
    List<float[]> output = window.append(new byte[] {-128, -1, 127, 0, 0}, 5);
    assertEquals(1, output.size());
    assertArrayEquals(new float[] {-1f, 32767f / 32768f, 0f}, output.get(0), 0.000001f);
  }

  @Test
  public void consecutiveWindowsDoNotDuplicateOrLoseSamples() {
    AudioWindow window = new AudioWindow(2);
    List<float[]> output = window.append(new byte[] {0, 1, 0, 2, 0, 3, 0, 4}, 8);
    assertEquals(2, output.size());
    assertArrayEquals(new float[] {256f / 32768, 512f / 32768}, output.get(0), 0f);
    assertArrayEquals(new float[] {768f / 32768, 1024f / 32768}, output.get(1), 0f);
  }

  @Test
  public void oldSessionAndOldLanguageResultsAreRejected() {
    SessionGate gate = new SessionGate();
    long first = gate.start();
    assertTrue(gate.accepts(first, 0));
    long revision = gate.languageChanged();
    assertFalse(gate.accepts(first, 0));
    assertTrue(gate.accepts(first, revision));
    gate.stop();
    assertFalse(gate.accepts(first, revision));
    long second = gate.start();
    assertFalse(gate.accepts(first, revision));
    assertTrue(gate.accepts(second, gate.revision()));
  }
}
