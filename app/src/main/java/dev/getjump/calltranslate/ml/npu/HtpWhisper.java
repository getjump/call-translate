package dev.getjump.calltranslate.ml.npu;

final class HtpWhisper {
  static {
    System.loadLibrary("whisper_htp");
  }

  static native long nativeInit(
      String nativeLibraryDirectory, String encoderPath, String decoderPath, byte[] melFilters);

  static native int[] nativeTranscribe(
      long handle,
      long requestId,
      float[] samples,
      int languageToken,
      int[] languageTokens,
      int startToken,
      int transcribeToken,
      int noTimestampsToken);

  static native void nativeCancel(long handle, long requestId);

  static native void nativeFree(long handle);

  private HtpWhisper() {}
}
