package com.covefit.shell;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 壳内本地转发代理：实现「按域名分流」。
 *  - 仅 *.workers.dev 转发给 v2rayNG（127.0.0.1:10808）
 *  - 其余域名直连
 * 这样 CoveFit 云端流量走代理，其它流量（及其它 App）完全不受影响。
 */
public class LocalProxy extends Thread {
    private static final String TAG = "CoveFit.LocalProxy";
    private static final String V2RAY_HOST = "127.0.0.1";
    private static final int V2RAY_PORT = 10808;

    private final int port;
    private ServerSocket serverSocket;
    private volatile boolean running = true;

    public LocalProxy(int port) throws IOException {
        this.port = port;
        // 构造阶段即完成 bind，保证 start() 返回后端口已就绪（避免 WebView 抢跑）
        this.serverSocket = new ServerSocket(port, 0, java.net.InetAddress.getByName("127.0.0.1"));
        Log.i(TAG, "bound on 127.0.0.1:" + port);
    }

    @Override
    public void run() {
        try {
            Log.i(TAG, "listening on 127.0.0.1:" + port);
            while (running) {
                Socket client = serverSocket.accept();
                new Thread(() -> handle(client)).start();
            }
        } catch (IOException e) {
            if (running) Log.e(TAG, "server error", e);
        }
    }

    public void stopProxy() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignore) {
        }
    }

    private void handle(Socket client) {
        try {
            InputStream cis = client.getInputStream();
            OutputStream cos = client.getOutputStream();

            String firstLine = readLine(cis);
            if (firstLine == null || firstLine.isEmpty()) {
                closeQuietly(client);
                return;
            }
            String[] parts = firstLine.trim().split("\\s+");
            String method = parts[0];
            String target = parts.length > 1 ? parts[1] : "";

            // 读取请求头（逐行，不预读，避免破坏后续隧道字节流）
            List<String> headers = new ArrayList<>();
            String hostLine = null;
            String line;
            while (!(line = readLine(cis)).isEmpty()) {
                headers.add(line);
                if (line.toLowerCase().startsWith("host:")) {
                    hostLine = line.substring(5).trim();
                }
            }

            if ("CONNECT".equalsIgnoreCase(method)) {
                // HTTPS 隧道：target = host:port
                String[] hp = target.split(":");
                String host = hp[0];
                int destPort = hp.length > 1 ? Integer.parseInt(hp[1]) : 443;
                if (needsProxy(host)) {
                    Log.d(TAG, "CONNECT(proxy) " + host);
                    tunnel(client, cos, cis, V2RAY_HOST, V2RAY_PORT);
                } else {
                    Log.d(TAG, "CONNECT(direct) " + host);
                    tunnel(client, cos, cis, host, destPort);
                }
            } else {
                // 明文 HTTP（PWA 为 HTTPS，此分支极少触发，仅作兜底）
                String host = hostLine != null ? hostLine.split(":")[0] : "";
                int destPort = 80;
                if (needsProxy(host)) {
                    forwardHttp(client, cos, cis, V2RAY_HOST, V2RAY_PORT, firstLine, headers);
                } else {
                    forwardHttp(client, cos, cis, host, destPort, firstLine, headers);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "handle error", e);
            closeQuietly(client);
        }
    }

    /** 仅 covefit 云端域名走 v2rayNG */
    private boolean needsProxy(String host) {
        return host != null
                && (host.endsWith(".workers.dev") || "workers.dev".equals(host));
    }

    /** HTTPS CONNECT 隧道：先回 200，再双向拷贝 */
    private void tunnel(Socket client, OutputStream cos, InputStream cis, String host, int port)
            throws IOException {
        Socket target = new Socket();
        target.connect(new InetSocketAddress(host, port), 10000);
        cos.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        cos.flush();
        pipe(cis, client.getOutputStream(), target.getInputStream(), target.getOutputStream(), client, target);
    }

    /** 明文 HTTP 转发：重写请求行 + 头，发给上游 */
    private void forwardHttp(Socket client, OutputStream cos, InputStream cis, String host, int port,
                             String firstLine, List<String> headers) throws IOException {
        Socket target = new Socket();
        target.connect(new InetSocketAddress(host, port), 10000);
        OutputStream tos = target.getOutputStream();
        tos.write((firstLine + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (String h : headers) {
            tos.write((h + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        tos.write("\r\n".getBytes(StandardCharsets.UTF_8));
        tos.flush();
        pipe(cis, client.getOutputStream(), target.getInputStream(), target.getOutputStream(), client, target);
    }

    /** 双向字节拷贝；任一侧结束即关闭两侧 */
    private void pipe(InputStream clientIn, OutputStream clientOut,
                      InputStream targetIn, OutputStream targetOut,
                      Socket client, Socket target) {
        Thread t1 = new Thread(() -> copy(clientIn, targetOut, client, target));
        Thread t2 = new Thread(() -> copy(targetIn, clientOut, client, target));
        t1.start();
        t2.start();
        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeQuietly(client);
        closeQuietly(target);
    }

    private void copy(InputStream in, OutputStream out, Socket a, Socket b) {
        byte[] buf = new byte[16384];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignore) {
            // 对端关闭
        } finally {
            closeQuietly(a);
            closeQuietly(b);
        }
    }

    /** 逐字节读一行（不含 \r\n），避免 BufferedReader 预读破坏隧道流 */
    private String readLine(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int b;
        while ((b = is.read()) != -1) {
            if (b == '\r') continue;
            if (b == '\n') break;
            bos.write(b);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private void closeQuietly(Socket s) {
        try {
            if (s != null && !s.isClosed()) s.close();
        } catch (IOException ignore) {
        }
    }

    private void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignore) {
        }
    }
}
