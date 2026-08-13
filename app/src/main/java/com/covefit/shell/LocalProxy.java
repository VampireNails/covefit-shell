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
                    chainViaProxy(client, cos, cis, host, destPort);
                } else {
                    Log.d(TAG, "CONNECT(direct) " + host);
                    directTunnel(client, cos, cis, host, destPort);
                }
            } else {
                // 明文 HTTP（路径A等直连场景会走此分支）
                String host = "";
                int destPort = 80;
                if (hostLine != null) {
                    String[] hp = hostLine.split(":");
                    host = hp[0];
                    if (hp.length > 1) {
                        try { destPort = Integer.parseInt(hp[1]); } catch (NumberFormatException ignore) {}
                    }
                }
                if (needsProxy(host)) {
                    chainViaProxyHttp(client, cos, cis, host, destPort, firstLine, headers);
                } else {
                    // 直连：把绝对形式请求行改写为 origin 形式发给源站
                    forwardHttp(client, cos, cis, host, destPort, toOriginForm(firstLine), headers);
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

    /** 把代理收到的绝对形式请求行(GET http://host:port/path)改写为 origin 形式(GET /path)发给源站 */
    private String toOriginForm(String firstLine) {
        String[] parts = firstLine.trim().split("\\s+");
        if (parts.length < 2) return firstLine;
        String method = parts[0];
        String target = parts[1];
        String path;
        if (target.startsWith("http://") || target.startsWith("https://")) {
            int schemeEnd = target.indexOf("//");
            int slash = target.indexOf('/', schemeEnd + 2);
            path = slash >= 0 ? target.substring(slash) : "/";
        } else {
            path = target;
        }
        String version = parts.length > 2 ? parts[2] : "HTTP/1.1";
        return method + " " + path + " " + version;
    }

    /** 直连隧道：直接连目标，回 200 后双向拷贝 */
    private void directTunnel(Socket client, OutputStream cos, InputStream cis, String host, int port)
            throws IOException {
        Socket target = new Socket();
        target.connect(new InetSocketAddress(host, port), 10000);
        cos.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        cos.flush();
        pipe(cis, client.getOutputStream(), target.getInputStream(), target.getOutputStream(), client, target);
    }

    /**
     * 经 v2rayNG HTTP 代理的隧道链：
     *  客户端 CONNECT host:port → 我们向 v2rayNG(127.0.0.1:10808) 再发一次 CONNECT，
     *  待其回 200 后，把 v2rayNG 的 200 透传给客户端，再双向拷贝。
     * （v2rayNG 仅代理端口是 HTTP 代理，不是裸 TCP 转发，必须先做代理握手。）
     */
    private void chainViaProxy(Socket client, OutputStream cos, InputStream cis, String host, int port)
            throws IOException {
        Socket upstream = new Socket();
        upstream.connect(new InetSocketAddress(V2RAY_HOST, V2RAY_PORT), 10000);
        OutputStream uos = upstream.getOutputStream();
        String connectReq = "CONNECT " + host + ":" + port + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n\r\n";
        uos.write(connectReq.getBytes(StandardCharsets.UTF_8));
        uos.flush();

        InputStream uis = upstream.getInputStream();
        String statusLine = readLine(uis);
        String h;
        while ((h = readLine(uis)) != null && !h.isEmpty()) { /* 跳过上游响应头 */ }

        if (statusLine != null && statusLine.contains("200")) {
            cos.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            cos.flush();
            pipe(cis, client.getOutputStream(), uis, upstream.getOutputStream(), client, upstream);
        } else {
            Log.e(TAG, "upstream proxy rejected CONNECT: " + statusLine);
            cos.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            cos.flush();
        }
        closeQuietly(client);
        closeQuietly(upstream);
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

    /** 明文 HTTP 经 v2rayNG 代理：向上游发绝对形式请求行，再透传响应（兜底分支） */
    private void chainViaProxyHttp(Socket client, OutputStream cos, InputStream cis, String host, int port,
                                   String firstLine, List<String> headers) throws IOException {
        Socket upstream = new Socket();
        upstream.connect(new InetSocketAddress(V2RAY_HOST, V2RAY_PORT), 10000);
        OutputStream uos = upstream.getOutputStream();
        String[] m = firstLine.trim().split("\\s+");
        String method = m.length > 0 ? m[0] : "GET";
        String absPath = m.length > 1 ? m[1] : "/";
        String absLine = method + " http://" + host + ":" + port + absPath + " HTTP/1.1";
        uos.write((absLine + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (String h : headers) {
            uos.write((h + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        uos.write("\r\n".getBytes(StandardCharsets.UTF_8));
        uos.flush();
        // 把客户端剩余请求体透传给上游
        byte[] buf = new byte[16384];
        int n;
        while ((n = cis.read(buf)) != -1) {
            uos.write(buf, 0, n);
            uos.flush();
        }
        // 透传上游响应到客户端
        InputStream uis = upstream.getInputStream();
        while ((n = uis.read(buf)) != -1) {
            cos.write(buf, 0, n);
            cos.flush();
        }
        closeQuietly(client);
        closeQuietly(upstream);
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
