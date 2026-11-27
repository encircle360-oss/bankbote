package com.encircle360.bankbote.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;

/**
 * A TCP front of a server, as the published port of a container is while the server behind it
 * starts: it accepts connections and closes them at once, which a TLS client sees as a terminated
 * handshake, until it is switched to pass every byte to the server.
 */
public final class FlakyFront implements AutoCloseable {

  private final ServerSocket socket;
  private final int targetPort;
  private volatile boolean forwarding;

  public FlakyFront(int targetPort) {
    this.targetPort = targetPort;
    try {
      this.socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Thread.ofVirtual().start(this::accept);
  }

  public URI uri() {
    return URI.create("https://127.0.0.1:" + socket.getLocalPort());
  }

  /** From now on, connections reach the server behind. */
  public void forward() {
    forwarding = true;
  }

  private void accept() {
    while (!socket.isClosed()) {
      Socket client;
      try {
        client = socket.accept();
      } catch (IOException e) {
        return;
      }
      if (!forwarding) {
        close(client);
        continue;
      }
      Thread.ofVirtual().start(() -> relay(client));
    }
  }

  private void relay(Socket client) {
    try (client;
        Socket server = new Socket(InetAddress.getLoopbackAddress(), targetPort)) {
      Thread upstream = Thread.ofVirtual().start(() -> pipe(client, server));
      pipe(server, client);
      upstream.join();
    } catch (IOException | InterruptedException e) {
      // the connection ends; the client sees what the server sent so far
    }
  }

  private static void pipe(Socket from, Socket to) {
    try {
      InputStream in = from.getInputStream();
      OutputStream out = to.getOutputStream();
      in.transferTo(out);
      to.shutdownOutput();
    } catch (IOException e) {
      // one side has closed
    }
  }

  private static void close(Socket client) {
    try {
      client.close();
    } catch (IOException e) {
      // already closed
    }
  }

  @Override
  public void close() {
    try {
      socket.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
