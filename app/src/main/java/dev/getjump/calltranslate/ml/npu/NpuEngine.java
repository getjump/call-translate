package dev.getjump.calltranslate.ml.npu;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.system.OsConstants;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

public final class NpuEngine implements AutoCloseable {
  private final Context context;
  private final Object state = new Object();
  private final Binder owner = new Binder();
  private final CountDownLatch connected = new CountDownLatch(1);
  private final ReentrantLock transcription = new ReentrantLock();
  private final IBinder.DeathRecipient death = () -> fail("NPU process died");
  private final ServiceConnection connection =
      new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
          synchronized (state) {
            if (closed) return;
            try {
              binder.linkToDeath(death, 0);
              remote = INpuService.Stub.asInterface(binder);
            } catch (RemoteException error) {
              failure = new IllegalStateException("NPU process died while connecting", error);
            }
          }
          connected.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
          fail("NPU process disconnected");
        }

        @Override
        public void onBindingDied(ComponentName name) {
          fail("NPU binding died");
        }

        @Override
        public void onNullBinding(ComponentName name) {
          fail("NPU service returned no binding");
        }
      };

  private INpuService remote;
  private long sessionId;
  private long nextRequestId;
  private Operation active;
  private boolean bound;
  private boolean closed;
  private IllegalStateException failure;

  public NpuEngine(Context context, File modelDirectory) throws Exception {
    requireBackgroundThread();
    if (context == null || modelDirectory == null)
      throw new IllegalArgumentException("Context and NPU model directory are required");
    this.context = context.getApplicationContext();
    try {
      synchronized (state) {
        bound =
            this.context.bindService(
                new Intent(this.context, NpuService.class), connection, Context.BIND_AUTO_CREATE);
        if (!bound) throw new IllegalStateException("Cannot bind NPU service");
      }
      if (!connected.await(10, TimeUnit.SECONDS))
        throw new TimeoutException("NPU service connection timed out");
      INpuService service;
      synchronized (state) {
        checkOpen();
        service = remote;
      }
      ExecutorService initializer = Executors.newSingleThreadExecutor();
      try {
        var initialized =
            initializer.submit(
                () -> {
                  long opened = service.openSession(modelDirectory.getCanonicalPath(), owner);
                  synchronized (state) {
                    if (!closed && failure == null) {
                      sessionId = opened;
                      return opened;
                    }
                  }
                  service.closeSession(opened);
                  throw new CancellationException("NPU initialization canceled");
                });
        try {
          initialized.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
          Throwable cause = error.getCause();
          if (cause instanceof Exception exception) throw exception;
          throw new IllegalStateException("NPU initialization failed", cause);
        } catch (TimeoutException error) {
          initialized.cancel(true);
          throw new TimeoutException("NPU session initialization timed out");
        }
      } finally {
        initializer.shutdown();
      }
    } catch (Exception error) {
      close();
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      throw error;
    }
  }

  public String[] transcribe(float[] samples, String language) throws Exception {
    requireBackgroundThread();
    if (samples == null || samples.length == 0 || samples.length > 480000)
      throw new IllegalArgumentException("NPU audio must contain 1 sample to 30 seconds at 16 kHz");
    if (language == null || !language.matches("(?i)auto|[a-z]{2,3}"))
      throw new IllegalArgumentException("Invalid Whisper source language");
    transcription.lockInterruptibly();
    Operation operation = null;
    try {
      INpuService service;
      long session;
      synchronized (state) {
        checkOpen();
        if (sessionId == 0) throw new IllegalStateException("NPU session is not initialized");
        service = remote;
        session = sessionId;
        operation = new Operation(++nextRequestId);
        active = operation;
      }
      int sampleCount = Math.max(16000, samples.length);
      try (SharedMemory audio = SharedMemory.create("calltranslate-npu-audio", sampleCount * 4)) {
        ByteBuffer buffer = audio.mapReadWrite().order(ByteOrder.LITTLE_ENDIAN);
        try {
          for (int i = 0; i < samples.length; i++) {
            if (!Float.isFinite(samples[i]))
              throw new IllegalArgumentException("NPU audio contains a non-finite sample");
            if ((i & 4095) == 0) checkCanceled(operation);
            buffer.putFloat(samples[i]);
          }
        } finally {
          SharedMemory.unmap(buffer);
        }
        if (!audio.setProtect(OsConstants.PROT_READ))
          throw new IllegalStateException("Cannot protect NPU audio shared memory");
        checkCanceled(operation);
        Bundle result;
        try {
          result = service.transcribe(session, operation.id, audio, sampleCount, language);
        } catch (RemoteException error) {
          fail("NPU transcription connection failed");
          throw new IllegalStateException("NPU transcription connection failed", error);
        }
        checkCanceled(operation);
        if (result == null) throw new IllegalStateException("NPU returned no result");
        String error = result.getString("error");
        if (error != null) {
          String message = result.getString("message", "NPU transcription failed");
          switch (error) {
            case "canceled" -> throw new CancellationException(message);
            case "invalid" -> throw new IllegalArgumentException(message);
            default -> throw new IllegalStateException(message);
          }
        }
        String original = result.getString("original");
        String detected = result.getString("language");
        if (original == null || detected == null)
          throw new IllegalStateException("NPU returned an incomplete transcript");
        synchronized (state) {
          checkOpen();
        }
        return new String[] {original, detected};
      }
    } finally {
      synchronized (state) {
        if (active == operation) active = null;
      }
      transcription.unlock();
    }
  }

  public void cancel() {
    INpuService service;
    long session;
    Operation operation;
    synchronized (state) {
      operation = active;
      if (operation == null) return;
      operation.canceled = true;
      service = remote;
      session = sessionId;
    }
    if (service != null && session != 0) {
      try {
        service.cancel(session, operation.id);
      } catch (RemoteException error) {
        fail("NPU cancellation connection failed");
      }
    }
  }

  @Override
  public void close() {
    INpuService service;
    long session;
    boolean unbind;
    synchronized (state) {
      if (closed) return;
      closed = true;
      if (active != null) active.canceled = true;
      service = remote;
      session = sessionId;
      sessionId = 0;
      remote = null;
      unbind = bound;
      bound = false;
    }
    connected.countDown();
    if (service != null) {
      if (session != 0) {
        try {
          service.closeSession(session);
        } catch (RemoteException error) {
          android.util.Log.w("CallTranslate", "NPU session close connection failed", error);
        }
      }
      service.asBinder().unlinkToDeath(death, 0);
    }
    if (unbind) context.unbindService(connection);
  }

  private void checkOpen() {
    if (closed) throw new IllegalStateException("NPU engine is closed");
    if (failure != null) throw failure;
    if (remote == null) throw new IllegalStateException("NPU service is not connected");
  }

  private void fail(String message) {
    synchronized (state) {
      if (!closed && failure == null) failure = new IllegalStateException(message);
      if (active != null) active.canceled = true;
    }
    connected.countDown();
  }

  private static void checkCanceled(Operation operation) {
    if (operation.canceled || Thread.currentThread().isInterrupted())
      throw new CancellationException("NPU transcription canceled");
  }

  private static void requireBackgroundThread() {
    if (Looper.myLooper() == Looper.getMainLooper())
      throw new IllegalStateException(
          "NPU initialization and transcription require a worker thread");
  }

  private static final class Operation {
    final long id;
    volatile boolean canceled;

    Operation(long id) {
      this.id = id;
    }
  }
}
