package com.shazwar.wifidirector;

//modified from
//https://android.googlesource.com/platform/frameworks/base/+/8a56d18/packages/services/Proxy/src/com/android/proxyhandler

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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
/**
 * @hide
 */
public class SocketConnect extends Thread {
    private InputStream from;
    private OutputStream to;
    public SocketConnect(Socket from, Socket to) throws IOException {
        this.from = from.getInputStream();
        this.to = to.getOutputStream();
        start();
    }
    @Override
    public void run() {
        final byte[] buffer = new byte[512];
        try {
            while (true) {
                int r = from.read(buffer);
                if (r < 0) {
                    break;
                }
                to.write(buffer, 0, r);
            }
            from.close();
            to.close();
        } catch (IOException io) {
        }
    }
    public static void connect(Socket first, Socket second) {
        try {
            SocketConnect sc1 = new SocketConnect(first, second);
            SocketConnect sc2 = new SocketConnect(second, first);
            try {
                sc1.join();
            } catch (InterruptedException e) {
            }
            try {
                sc2.join();
            } catch (InterruptedException e) {
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}