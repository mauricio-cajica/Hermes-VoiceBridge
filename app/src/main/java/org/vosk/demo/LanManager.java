package org.vosk.demo;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class LanManager {
    private static final String TAG = "LanManager";
    private static final String SERVICE_TYPE = "_vosksubtitle._tcp.";
    
    private final Context context;
    private final NsdManager nsdManager;
    private final LanListener listener;
    
    private ServerSocket serverSocket;
    private final List<Socket> clientSockets = new ArrayList<>();
    private Socket clientSocket;
    private boolean isHosting = false;
    private boolean isClient = false;
    
    private String serviceName;
    private NsdManager.RegistrationListener registrationListener;
    private NsdManager.DiscoveryListener discoveryListener;

    public interface LanListener {
        void onRoomDiscovered(NsdServiceInfo serviceInfo);
        void onSubtitleReceived(String text);
        void onConnectionStatusChanged(String status);
        void onError(String error);
    }

    public LanManager(Context context, LanListener listener) {
        this.context = context;
        this.listener = listener;
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    }

    public void createRoom(String name) {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(0);
                int port = serverSocket.getLocalPort();
                registerService(name, port);
                isHosting = true;
                listener.onConnectionStatusChanged("Hosting: " + name);
                
                while (isHosting) {
                    Socket socket = serverSocket.accept();
                    synchronized (clientSockets) {
                        clientSockets.add(socket);
                    }
                    Log.d(TAG, "New client connected");
                }
            } catch (IOException e) {
                if (isHosting) listener.onError("Server error: " + e.getMessage());
            }
        }).start();
    }

    private void registerService(String name, int port) {
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(name);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(port);

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo NsdServiceInfo) {
                serviceName = NsdServiceInfo.getServiceName();
                Log.d(TAG, "Service registered: " + serviceName);
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                listener.onError("Registration failed: " + errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo arg0) {}

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {}
        };

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
    }

    public void discoverRooms() {
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                nsdManager.stopServiceDiscovery(this);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                nsdManager.stopServiceDiscovery(this);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "Service discovery started");
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.d(TAG, "Discovery stopped");
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (serviceInfo.getServiceType().equals(SERVICE_TYPE)) {
                    if (serviceName == null || !serviceInfo.getServiceName().equals(serviceName)) {
                        listener.onRoomDiscovered(serviceInfo);
                    }
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Log.e(TAG, "service lost: " + serviceInfo);
            }
        };

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    public void connectToRoom(NsdServiceInfo serviceInfo) {
        nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                listener.onError("Resolve failed: " + errorCode);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo resolvedServiceInfo) {
                new Thread(() -> {
                    try {
                        clientSocket = new Socket(resolvedServiceInfo.getHost(), resolvedServiceInfo.getPort());
                        isClient = true;
                        listener.onConnectionStatusChanged("Connected to: " + resolvedServiceInfo.getServiceName());
                        
                        BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                        String line;
                        while (isClient && (line = in.readLine()) != null) {
                            final String text = line;
                            listener.onSubtitleReceived(text);
                        }
                    } catch (IOException e) {
                        if (isClient) listener.onError("Connection error: " + e.getMessage());
                    }
                }).start();
            }
        });
    }

    public void sendSubtitle(String text) {
        if (!isHosting) return;
        new Thread(() -> {
            synchronized (clientSockets) {
                List<Socket> toRemove = new ArrayList<>();
                for (Socket socket : clientSockets) {
                    try {
                        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                        out.println(text);
                    } catch (IOException e) {
                        toRemove.add(socket);
                    }
                }
                clientSockets.removeAll(toRemove);
            }
        }).start();
    }

    public void stop() {
        isHosting = false;
        isClient = false;
        
        if (registrationListener != null) {
            try { nsdManager.unregisterService(registrationListener); } catch (Exception ignored) {}
            registrationListener = null;
        }
        
        if (discoveryListener != null) {
            try { nsdManager.stopServiceDiscovery(discoveryListener); } catch (Exception ignored) {}
            discoveryListener = null;
        }
        
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        try { if (clientSocket != null) clientSocket.close(); } catch (IOException ignored) {}
        
        synchronized (clientSockets) {
            for (Socket s : clientSockets) {
                try { s.close(); } catch (IOException ignored) {}
            }
            clientSockets.clear();
        }
        
        listener.onConnectionStatusChanged("Not Connected");
    }
}
