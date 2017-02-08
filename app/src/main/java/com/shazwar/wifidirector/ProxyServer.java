package com.shazwar.wifidirector;

//modified from
//https://android.googlesource.com/platform/frameworks/base/+/8a56d18/packages/services/Proxy/src/com/android/proxyhandler
//https://android.googlesource.com/platform/frameworks/base/+/8a56d18/packages/services/Proxy/src/com/android/proxyhandler/ProxyServer.java

/**
 * Copyright (c) 2013, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
        //import android.net.IProxyPortListener;
        //import android.net.ProxyProperties;
        import android.os.RemoteException;
        import android.util.Log;


        import com.google.common.collect.Lists;
        import com.google.common.io.ByteStreams;

        import java.io.IOException;
        import java.io.InputStream;
        import java.io.OutputStream;
        import java.net.InetAddress;
        import java.net.InetSocketAddress;
        import java.net.Proxy;
        import java.net.ProxySelector;
        import java.net.ServerSocket;
        import java.net.Socket;
        import java.net.SocketException;
        import java.net.URI;
        import java.net.URISyntaxException;
        import java.util.List;
        import java.util.concurrent.ExecutorService;
        import java.util.concurrent.Executors;
        import java.util.concurrent.TimeUnit;

/**
 * @hide
 */
public class ProxyServer extends Thread {
    private static final String CONNECT = "CONNECT";
    private static final String HTTP_OK = "HTTP/1.1 200 OK\n";
    private static final String TAG = "ProxyServer";
    private ExecutorService threadExecutor;
    public boolean mIsRunning = false;
    private ServerSocket serverSocket;
    private int mPort;
    Socket server = null;
    //private IProxyPortListener mCallback;
    private class ProxyConnection implements Runnable {
        private Socket connection;
        private ProxyConnection(Socket connection) {
            this.connection = connection;
        }
        @Override
        public void run() {
            try {
                String requestLine = getLine(connection.getInputStream());
                if (requestLine == null) {
                    connection.close();
                    return;
                }
                String[] splitLine = requestLine.split(" ");
                if (splitLine.length < 3) {
                    connection.close();
                    return;
                }
                String requestType = splitLine[0];
                String urlString = splitLine[1];
                String host = "";
                int port = 80;
                if (requestType.equals(CONNECT)) {
                    String[] hostPortSplit = urlString.split(":");
                    host = hostPortSplit[0];
                    try {
                        port = Integer.parseInt(hostPortSplit[1]);
                    } catch (NumberFormatException nfe) {
                        port = 443;
                    }
                    urlString = "Https://" + host + ":" + port;
                } else {
                    try {
                        URI url = new URI(urlString);
                        host = url.getHost();
                        port = url.getPort();
                        if (port < 0) {
                            port = 80;
                        }
                    } catch (URISyntaxException e) {
                        connection.close();
                        return;
                    }
                }
                List<Proxy> list = Lists.newArrayList();
                try {
                    list = ProxySelector.getDefault().select(new URI(urlString));
                } catch (URISyntaxException e) {
                    e.printStackTrace();
                }
                //Socket server = null;
                server = null;
                for (Proxy proxy : list) {
                    try {
                        if (!proxy.equals(Proxy.NO_PROXY)) {
                            // Only Inets created by PacProxySelector.
                            InetSocketAddress inetSocketAddress =
                                    (InetSocketAddress)proxy.address();
                            server = new Socket(inetSocketAddress.getHostName(),
                                    inetSocketAddress.getPort());
                            sendLine(server, requestLine);
                        } else {
                            server = new Socket(host, port);
                            if (requestType.equals(CONNECT)) {
                                while (getLine(connection.getInputStream()).length() != 0);
                                // No proxy to respond so we must.
                                sendLine(connection, HTTP_OK);
                            } else {
                                sendLine(server, requestLine);
                            }
                        }
                    } catch (IOException ioe) {
                    }
                    if (server != null) {
                        break;
                    }
                }
                if (server == null) {
                    server = new Socket(host, port);
                    if (requestType.equals(CONNECT)) {
                        while (getLine(connection.getInputStream()).length() != 0);
                        // No proxy to respond so we must.
                        sendLine(connection, HTTP_OK);
                    } else {
                        sendLine(server, requestLine);
                    }
                }
                // Pass data back and forth until complete.
                //SocketConnect.connect(connection, server);
                ExecutorService taskExecutor = Executors.newFixedThreadPool(2);
                Thread input = new Thread() {
                    @Override
                    public void run() {
                        try {
                            ByteStreams.copy(server.getInputStream(), connection.getOutputStream());
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                };
                Thread output = new Thread() {
                    @Override
                    public void run() {
                        try {
                            ByteStreams.copy(connection.getInputStream(), server.getOutputStream());
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                };
                taskExecutor.execute(input);
                taskExecutor.execute(output);
                try {
                    taskExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                }catch (InterruptedException ex){
                    Log.e(TAG, "interrupted!");
                }
            } catch (IOException e) {
                Log.d(TAG, "Problem Proxying", e);
            }
            try {
                connection.close();
            } catch (IOException ioe) {
            }
        }
        private String getLine(InputStream inputStream) throws IOException {
            StringBuffer buffer = new StringBuffer();
            int byteBuffer = inputStream.read();
            if (byteBuffer < 0) return "";
            do {
                if (byteBuffer != '\r') {
                    buffer.append((char)byteBuffer);
                }
                byteBuffer = inputStream.read();
            } while ((byteBuffer != '\n') && (byteBuffer >= 0));
            return buffer.toString();
        }
        private void sendLine(Socket socket, String line) throws IOException {
            OutputStream os = socket.getOutputStream();
            os.write(line.getBytes());
            os.write('\r');
            os.write('\n');
            os.flush();
        }
    }
}
