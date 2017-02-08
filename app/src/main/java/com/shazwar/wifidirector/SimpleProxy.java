package com.shazwar.wifidirector;

import android.util.Log;

import com.google.common.base.Charsets;
import com.google.common.io.ByteSource;

import java.io.IOException;

import java.io.*;
import java.net.*;


public class SimpleProxy {

    private String proxyName;
    private int localPort;
    private int httpPort;
    private String localHost;
    private boolean STOP = false;
    private static final String TAG = "simple-proxy";

    public SimpleProxy(int local, String name){
            localPort = local;
            httpPort = 8080;
            proxyName = name;
            localHost = "192.168.49.1";
            //localHost = "10.10.2.1";
        }

        public void stop(){
            STOP = true;
        }
        /**
         * runs a single-threaded proxy server on
         * the specified local port. It never returns.
         */
        public void runServer()
                throws IOException {
            // Create a ServerSocket to listen for connections with
            ServerSocket ss = new ServerSocket(localPort);

            final byte[] request = new byte[1024];
            byte[] reply = new byte[4096];
            Log.d(TAG, "proxy listening");
            while (!STOP) {
                Socket client = null, server = null;
                try {
                    // Wait for a connection on the local port
                    Log.d(TAG, "Waiting for connection and blocking");
                    client = ss.accept();
                    Log.d(TAG, "connection accepted");

                    final InputStream streamFromClient = client.getInputStream();
                    final OutputStream streamToClient = client.getOutputStream();
                    Log.d(TAG, "got client streams");
                    // Make a connection to the real server.
                    // If we cannot connect to the server, send an error to the
                    // client, disconnect, and continue waiting for connections.
                    ByteSource byteSource = new ByteSource() {
                        @Override
                        public InputStream openStream (){
                        return streamFromClient;
                        } ;
                    };
                    String text = byteSource.asCharSource(Charsets.UTF_8).read();
                    Log.d(TAG, text);
                    if (!STOP){continue;};
                    try {
                        Log.d(TAG, "attempting to connect to localhost");
                        //server = new Socket(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("localhost", 80)));
                        server = new Socket(localHost, httpPort);
                        Log.d(TAG, "connected to " + localHost);
                    } catch (IOException e) {
                        Log.d(TAG, e.getMessage());
                        PrintWriter out = new PrintWriter(streamToClient);
                        out.print("Proxy server cannot connect to " + proxyName + ":"
                                + httpPort + ":\n" + e + "\n");
                        out.flush();
                        client.close();
                        continue;
                    }

                    // Get server streams.
                    final InputStream streamFromServer = server.getInputStream();
                    final OutputStream streamToServer = server.getOutputStream();

                    // a thread to read the client's requests and pass them
                    // to the server. A separate thread for asynchronous.
                    Thread t = new Thread() {
                        public void run() {
                            int bytesRead;
                            try {
                                Log.d(TAG, "Reading from input to server");
                                while ((bytesRead = streamFromClient.read(request)) != -1) {
                                    Log.d(TAG, "bytes read!: " + Integer.toString(bytesRead));
                                    streamToServer.write(request, 0, bytesRead);
                                    streamToServer.flush();
                                }
                            } catch (IOException e) {
                            }

                            // the client closed the connection to us, so close our
                            // connection to the server.
                            try {
                                streamToServer.close();
                            } catch (IOException e) {
                            }
                        }
                    };

                    // Start the client-to-server request thread running
                    t.start();

                    // Read the server's responses
                    // and pass them back to the client.
                    int bytesRead;
                    try {
                        while ((bytesRead = streamFromServer.read(reply)) != -1) {
                            streamToClient.write(reply, 0, bytesRead);
                            streamToClient.flush();
                        }
                    } catch (IOException e) {
                    }

                    // The server closed its connection to us, so we close our
                    // connection to our client.
                    streamToClient.close();
                } catch (IOException e) {
                    System.err.println(e);
                } finally {
                    try {
                        if (server != null)
                            server.close();
                        if (client != null)
                            client.close();
                    } catch (IOException e) {
                    }
                }
            }
        }
    }

