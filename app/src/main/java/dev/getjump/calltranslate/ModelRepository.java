package dev.getjump.calltranslate;

import android.content.Context;
import android.net.Uri;
import dev.getjump.calltranslate.core.ModelFile;
import dev.getjump.calltranslate.core.ModelInstaller;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ModelRepository {
  public static final class Model {
    public final JSONObject json;
    public final String id;
    public final String type;
    public final String name;
    public final JSONArray files;

    Model(JSONObject json) throws Exception {
      this.json = json;
      id = json.getString("id");
      type = json.getString("type");
      name = json.getString("name");
      files = json.getJSONArray("files");
      if (!id.matches("[a-zA-Z0-9_-]{1,80}")) throw new IOException("Invalid model identifier");
    }

    @Override
    public String toString() {
      return name;
    }
  }

  private final Context context;
  private final File root;
  private final List<Model> models = new ArrayList<>();

  public ModelRepository(Context context) throws Exception {
    this.context = context.getApplicationContext();
    root = new File(context.getFilesDir(), "models");
    try (InputStream input = context.getAssets().open("catalog.json")) {
      read(new JSONArray(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
    }
    File custom = new File(context.getFilesDir(), "custom-models.json");
    if (custom.isFile())
      read(new JSONArray(new String(Files.readAllBytes(custom.toPath()), StandardCharsets.UTF_8)));
  }

  private void read(JSONArray entries) throws Exception {
    for (int i = 0; i < entries.length(); i++) models.add(new Model(entries.getJSONObject(i)));
  }

  public synchronized List<Model> list(String type) {
    return models.stream()
        .filter(model -> model.type.equals(type))
        .collect(java.util.stream.Collectors.toList());
  }

  public synchronized Model model(String id) {
    return models.stream()
        .filter(model -> model.id.equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown model: " + id));
  }

  public File directory(Model model) {
    return new File(root, model.id);
  }

  public File asrFile(Model model) throws Exception {
    return new File(directory(model), model.files.getJSONObject(0).getString("name"));
  }

  public boolean installed(Model model) {
    try {
      for (int i = 0; i < model.files.length(); i++) {
        JSONObject spec = model.files.getJSONObject(i);
        File file = new File(directory(model), spec.getString("name"));
        if (!file.isFile() || file.length() != spec.getLong("size")) return false;
      }
      return true;
    } catch (Exception error) {
      return false;
    }
  }

  public void verify(Model model, BooleanSupplier canceled) throws Exception {
    ModelInstaller installer = new ModelInstaller(directory(model));
    for (int i = 0; i < model.files.length(); i++) {
      JSONObject spec = model.files.getJSONObject(i);
      installer.verify(
          new ModelFile(spec.getString("name"), spec.getLong("size"), spec.getString("sha256")),
          canceled);
    }
  }

  public void download(Model model, BooleanSupplier canceled, Consumer<String> progress)
      throws Exception {
    File staging = new File(root, ".install-" + UUID.randomUUID());
    ModelInstaller installer = new ModelInstaller(staging);
    try {
      if (model.json.has("archive")) {
        downloadArchive(model, staging, canceled, progress);
        if (canceled.getAsBoolean()) throw new IOException("Загрузка отменена");
        replace(staging, directory(model));
        progress.accept("Модель установлена: " + model.name);
        return;
      }
      for (int i = 0; i < model.files.length(); i++) {
        JSONObject spec = model.files.getJSONObject(i);
        String name = spec.getString("name");
        progress.accept("Загрузка " + name + " (" + (i + 1) + "/" + model.files.length() + ")");
        ModelFile file = new ModelFile(name, spec.getLong("size"), spec.getString("sha256"));
        if (staging.getUsableSpace() > 0 && staging.getUsableSpace() < file.size() + 100000000)
          throw new IOException("Недостаточно места для модели");
        if (spec.has("asset")) {
          installer.install(file, context.getAssets().open(spec.getString("asset")), canceled);
        } else {
          HttpURLConnection connection = open(spec.getString("url"));
          try {
            installer.install(file, connection.getInputStream(), canceled);
          } finally {
            connection.disconnect();
          }
        }
      }
      if (canceled.getAsBoolean()) throw new IOException("Загрузка отменена");
      replace(staging, directory(model));
      progress.accept("Модель установлена: " + model.name);
    } finally {
      delete(staging);
    }
  }

  private void downloadArchive(
      Model model, File staging, BooleanSupplier canceled, Consumer<String> progress)
      throws Exception {
    JSONObject archive = model.json.getJSONObject("archive");
    File bundle = new File(root, ".package-" + UUID.randomUUID());
    Files.createDirectories(staging.toPath());
    long unpacked = 0;
    for (int i = 0; i < model.files.length(); i++)
      unpacked += model.files.getJSONObject(i).getLong("size");
    if (root.getUsableSpace() < unpacked + archive.getLong("size") + 100000000)
      throw new IOException("Недостаточно места для загрузки и распаковки NPU-модели");
    try {
      progress.accept("Загрузка NPU-пакета: 0 МБ");
      HttpURLConnection connection = open(archive.getString("url"));
      File zip;
      try {
        InputStream source =
            new java.io.FilterInputStream(connection.getInputStream()) {
              private long received;
              private long checkpoint;

              @Override
              public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = super.read(bytes, offset, length);
                if (count > 0) received += count;
                if (received - checkpoint > 10000000) {
                  checkpoint = received;
                  progress.accept("Загрузка NPU-пакета: " + received / 1000000 + " МБ");
                }
                return count;
              }
            };
        zip =
            new ModelInstaller(bundle)
                .install(
                    new ModelFile(
                        "model.zip", archive.getLong("size"), archive.getString("sha256")),
                    source,
                    canceled);
      } finally {
        connection.disconnect();
      }
      ModelInstaller installer = new ModelInstaller(staging);
      try (ZipFile contents = new ZipFile(zip)) {
        for (int i = 0; i < model.files.length(); i++) {
          JSONObject spec = model.files.getJSONObject(i);
          String name = spec.getString("name");
          var entry = contents.getEntry(archive.getString("prefix") + name);
          if (entry == null || entry.isDirectory())
            throw new IOException("Missing NPU file: " + name);
          progress.accept("Проверка и распаковка " + name);
          installer.install(
              new ModelFile(name, spec.getLong("size"), spec.getString("sha256")),
              contents.getInputStream(entry),
              canceled);
        }
      }
    } finally {
      delete(bundle);
    }
  }

  private static HttpURLConnection open(String url) throws Exception {
    for (int redirects = 0; redirects < 8; redirects++) {
      URI address = new URI(url);
      if (!"https".equals(address.getScheme()))
        throw new IOException("Model download requires HTTPS");
      HttpURLConnection connection = (HttpURLConnection) address.toURL().openConnection();
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(15000);
      connection.setInstanceFollowRedirects(false);
      int status = connection.getResponseCode();
      if (status >= 300 && status < 400) {
        String next = connection.getHeaderField("Location");
        connection.disconnect();
        if (next == null) throw new IOException("Redirect has no location");
        url = address.resolve(next).toString();
        continue;
      }
      if (status != 200) {
        connection.disconnect();
        throw new IOException("Download HTTP " + status);
      }
      return connection;
    }
    throw new IOException("Too many download redirects");
  }

  public synchronized Model importWhisper(Uri uri, BooleanSupplier canceled) throws Exception {
    File staging = new File(root, ".import-" + UUID.randomUUID());
    Files.createDirectories(staging.toPath());
    File modelFile = new File(staging, "model.bin");
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try {
      try (InputStream input = context.getContentResolver().openInputStream(uri);
          var output = Files.newOutputStream(modelFile.toPath())) {
        if (input == null) throw new IOException("Модель недоступна");
        byte[] buffer = new byte[65536];
        int size;
        while ((size = input.read(buffer)) != -1) {
          checkCanceled(canceled);
          output.write(buffer, 0, size);
          digest.update(buffer, 0, size);
        }
      }
      checkCanceled(canceled);
      try (var engine =
          new dev.getjump.calltranslate.ml.NativeEngine(modelFile.getPath(), false)) {}
      checkCanceled(canceled);
      StringBuilder hash = new StringBuilder();
      for (byte value : digest.digest())
        hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
      String id = "import-" + UUID.randomUUID();
      JSONObject json =
          new JSONObject()
              .put("id", id)
              .put("name", "Whisper импорт " + id.substring(7, 15))
              .put("type", "asr")
              .put("runtime", "whisper.cpp")
              .put(
                  "files",
                  new JSONArray()
                      .put(
                          new JSONObject()
                              .put("name", "model.bin")
                              .put("size", modelFile.length())
                              .put("sha256", hash.toString())));
      Model model = new Model(json);
      checkCanceled(canceled);
      replace(staging, directory(model));
      JSONArray custom = new JSONArray();
      for (Model item : models) if (item.id.startsWith("import-")) custom.put(item.json);
      custom.put(model.json);
      File pending = new File(context.getFilesDir(), "custom-models.json.part");
      Files.write(pending.toPath(), custom.toString().getBytes(StandardCharsets.UTF_8));
      Files.move(
          pending.toPath(),
          new File(context.getFilesDir(), "custom-models.json").toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      models.add(model);
      return model;
    } finally {
      delete(staging);
    }
  }

  private static void checkCanceled(BooleanSupplier canceled) throws IOException {
    if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new IOException("Импорт отменен");
  }

  private static void replace(File staging, File target) throws IOException {
    File backup = new File(target.getParentFile(), ".backup-" + UUID.randomUUID());
    boolean previous = target.exists();
    if (previous && !target.renameTo(backup))
      throw new IOException("Cannot preserve existing model");
    if (!staging.renameTo(target)) {
      if (previous && !backup.renameTo(target))
        throw new IOException("Model installation rollback failed");
      throw new IOException("Cannot activate installed model");
    }
    delete(backup);
  }

  private static void delete(File file) throws IOException {
    if (!file.exists()) return;
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) delete(child);
    }
    Files.deleteIfExists(file.toPath());
  }
}
