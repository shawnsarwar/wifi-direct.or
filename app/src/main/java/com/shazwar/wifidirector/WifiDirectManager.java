package com.shazwar.wifidirector;

import android.app.IntentService;
import android.content.Intent;
import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Handler;
import android.util.Log;

import java.lang.reflect.Method;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import be.shouldit.proxy.lib.APL;

import static java.util.Arrays.asList;

/**
 * An {@link IntentService} subclass for handling asynchronous task requests in
 * a service on a separate handler thread.
 * <p>
 * TODO: Customize class - update intent actions, extra parameters and static
 * helper methods.
 */

public class WifiDirectManager extends NonStopIntentService {

    //USER intent endpoints
    //required start initent, can be started via static initialize function
    public static final String ACTION_INITIALIZE = "com.shazwar.wifidirector.action.initialize";
    //ordinary action endpoints
    public static final String ACTION_FIND_PEERS = "com.shazwar.wifidirector.action.findpeers";
    public static final String ACTION_REGISTER_ENDPOINT= "com.shazwar.wifidirector.action.registerendpoint";
    public static final String ACTION_HOST= "com.shazwar.wifidirector.action.host";
    public static final String ACTION_STOP_HOST = "com.shazwar.wifidirector.action.stophost";
    public static final String ACTION_CONNECT= "com.shazwar.wifidirector.action.connect";
    public static final String ACTION_ADVERTISE_SERVICE= "com.shazwar.wifidirector.action.advertiseservice";
    public static final String ACTION_STOP_ADVERTISE_SERVICE= "com.shazwar.wifidirector.action.stopadvertise";
    public static final String ACTION_STOP_SERVICE= "com.shazwar.wifidirector.action.stopservice";
    public static final String ACTION_FIND_SERVICE= "com.shazwar.wifidirector.action.findservice";

    public static final String POST_PEER_LIST= "com.shazwar.wifidirector.post.peerlist";
    public static final String POST_CONNECTION_STATUS= "com.shazwar.wifidirector.post.connectionstatus";

    // parameter names for various endpoints
    public static final String EX_REQUEST_ID = "com.shazwar.wifidirector.extra.REQUEST_ID";
    public static final String EX_APP_NAME = "com.shazwar.wifidirector.extra.APP_NAME";
    public static final String EX_PORT= "com.shazwar.wifidirector.extra.PORT";
    public static final String EX_SERVICE_DETAILS= "com.shazwar.wifidirector.extra.SERVICE_DETAILS";
    // non-op yet
    public static final String EX_MULTICAST= "com.shazwar.wifidirector.extra.MULTICAST";

    //system intents we listen for. Order matters and is used later to avoid case/ switch statement
    private final List<String> SYSTEM_INTENTS = new ArrayList<String>(asList(
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION,       //0
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION,       //1
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION,  //2
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION  //3
    ));

    //long lived system instances
    private WifiP2pManager mWifiP2pManager;
    private WifiP2pManager.Channel mP2PChannel;
    private WifiP2pDnsSdServiceRequest mServiceRequest;
    private WifiP2pManager.DnsSdTxtRecordListener mTxTRecordListener;
    private WifiP2pManager.DnsSdServiceResponseListener mSdServiceResponseListener;
    private WifiP2pDeviceList mPeers;
    private WifiManager mWifiManager;
    private Handler mServiceReceiverHandler;
    private Map<String, Handler> mServiceBroadcastHandlers;
    //private Map<String, ServiceBroadcastThread> mServiceBroadCastThreads;
    private Map<String, HashMap<String,WifiP2pDevice>> availableServices; //service -> deviceid -> info
    private Map<String, WifiP2pDnsSdServiceInfo> activeBroadcasts; //services that this device is broadcasting.

    //TODO kill!
    //private SimpleProxy mProxy;
    private ProxyServer mProxy;


    //status flags
    public static boolean LAUNCHED = false;
    private boolean P2P_ENABLED = false;
    private boolean FINDING_SERVICES = false;
    private AtomicInteger COUNTER;


    //constants
    private static final String TAG = "WifiDirectManager";
    final Map<Integer, String> P2P_ERRORS = new HashMap<Integer, String>(){
        {
            put(WifiP2pManager.P2P_UNSUPPORTED,"P2P_UNSUPPORTED");
            put(WifiP2pManager.BUSY,"BUSY");
            put(WifiP2pManager.ERROR,"ERROR");
        }
    };
    private final int SERVICE_BROADCASTING_INTERVAL = 5000;

    public WifiDirectManager() {
        super("WifiDirectManager");
    }

    public static void initialize(Context context) {
        Log.d(TAG, "Initialize Called");
        if (LAUNCHED){return;}
        Intent intent = new Intent(context, WifiDirectManager.class);
        intent.setAction(ACTION_INITIALIZE);
        context.startService(intent);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();
            Log.d(TAG, String.format("handling intent, %s", action));
            if (ACTION_INITIALIZE.equals(action)){
                handleInit();
            }else if(!LAUNCHED){
                throw new IllegalArgumentException("Cannot process Action; Application not initialized");
            }else if(ACTION_FIND_PEERS.equals(action)) {
                final String app_name = intent.getStringExtra(EX_APP_NAME);
                handleFindPeers(app_name);
            }else if(ACTION_ADVERTISE_SERVICE.equals(action)) {
                final String serviceName = intent.getStringExtra(EX_APP_NAME);
                final String requestId = intent.getStringExtra(EX_REQUEST_ID);
                HashMap details = (HashMap) intent.getSerializableExtra(EX_SERVICE_DETAILS);
                handleAdvertiseService(requestId, serviceName, details);
            }else if (ACTION_STOP_ADVERTISE_SERVICE.equals(action)){
                final String requestId = intent.getStringExtra(EX_REQUEST_ID);
                handleStopAdvertiseService(requestId);
            }else if(ACTION_FIND_SERVICE.equals(action)){
                final String serviceName = intent.getStringExtra(EX_APP_NAME);
                handleFindService(serviceName);
            }else if(ACTION_REGISTER_ENDPOINT.equals(action)){
                final String app_name = intent.getStringExtra(EX_APP_NAME);
                final String port = intent.getStringExtra(EX_PORT);
                //TODO What do we mean by this?
            }else if(ACTION_HOST.equals(action)) {
                handleHost(intent.getStringExtra(EX_APP_NAME));
            }else if (ACTION_STOP_HOST.equals(action)){
                handleStopHost();
            }else if(ACTION_CONNECT.equals(action)){

            }else if(SYSTEM_INTENTS.contains(action)){
                handleSystemIntent(intent);
            }
        }else{
            Log.d(TAG, "Got empty intent");
        }
    }

    protected void handleSystemIntent(Intent intent){
        final String action = intent.getAction();
        Log.d(TAG, "Handling system intent: " + action);
        int actionIndex = SYSTEM_INTENTS.indexOf(action);
        switch (actionIndex){
            case 0:
                handleSysP2PStateChanged(intent);
                break;
            case 1:
                handleSysP2PPeersChanged(intent);
                break;
            case 2:
                handleSysP2PConnectionChanged(intent);
                break;
            case 3:
                handleSysP2PThisDeviceChanged(intent);
                break;
        }
    }

    private void handleInit(){
        LAUNCHED = true;
        try {
            Context mContext = getApplicationContext();

            mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
            availableServices = new HashMap<String, HashMap<String, WifiP2pDevice>>();
            mWifiP2pManager = (WifiP2pManager) mContext.getSystemService(Context.WIFI_P2P_SERVICE);
            mP2PChannel = mWifiP2pManager.initialize(this, getMainLooper(), null);
            mPeers = new WifiP2pDeviceList();
            mServiceReceiverHandler = new Handler();
            mServiceBroadcastHandlers = new HashMap<String, Handler>();
            activeBroadcasts = new HashMap<String, WifiP2pDnsSdServiceInfo>();
            mWifiP2pManager.setDnsSdResponseListeners(mP2PChannel, getServiceResponseListener(), getTxtRecordListener());
            mWifiP2pManager.clearLocalServices(mP2PChannel, new WifiP2pManager.ActionListener() {
                        @Override
                        public void onSuccess() {
                        }
                        @Override
                        public void onFailure(int errorCode) {
                        }
            });
            COUNTER = new AtomicInteger(0);
            mServiceRequest = WifiP2pDnsSdServiceRequest.newInstance();

        }catch(Exception ex){
            Log.e(TAG, "error in initialization");
            ex.printStackTrace();
            LAUNCHED = false;
        }

    }

    private void handleAdvertiseService(final String requestId, final String serviceName, final HashMap serviceDetails){
        if(activeBroadcasts.keySet().contains(requestId)){
            Log.e(TAG, String.format("%s is already registered so we won't do it again", requestId));
            return;
        }
        Handler h = new Handler();
        mServiceBroadcastHandlers.put(serviceName,h);
        //TODO needs some work. Do we need to thread this out?
        //TODO remove // testing mutability /rebroadcast of service details over time
        final HashMap temp = new HashMap();
        temp.putAll(serviceDetails);
        temp.put("C", Integer.toString(COUNTER.getAndIncrement()));


        WifiP2pDnsSdServiceInfo serviceInfo =
                WifiP2pDnsSdServiceInfo.newInstance(serviceName, "_presence._tcp", temp);
        activeBroadcasts.put(requestId, serviceInfo);
        mWifiP2pManager.addLocalService(mP2PChannel, serviceInfo, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, String.format("New service: %s is being advertised | details: %s", serviceName, Arrays.toString(temp.entrySet().toArray())));
            }
            @Override
            public void onFailure(int errorCode) {
                Log.e(TAG, String.format("failed to add service: %s, error: %s", serviceName, P2P_ERRORS.get(errorCode)));
            }
        });

    }

    //remove a service broadcast via the request ID used to start it.
    private void handleStopAdvertiseService(final String requestId){
        try {
            WifiP2pDnsSdServiceInfo serviceInfo = activeBroadcasts.get(requestId);

            mWifiP2pManager.removeLocalService(mP2PChannel, serviceInfo, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.d(TAG, String.format("Service : %s has been cancelled", requestId));
                    activeBroadcasts.remove(requestId);
                }

                @Override
                public void onFailure(int errorCode) {
                    Log.e(TAG, String.format("failed to cancel service: %s, error: %s", requestId, P2P_ERRORS.get(errorCode)));
                }
            });
        }catch (IllegalArgumentException ex){
            Log.e(TAG, String.format("Couldn't stop process %s as it doesn't exist. Active broadcasts: %s", requestId, Arrays.toString(activeBroadcasts.keySet().toArray())));

        }
    }

    private void addDiscoveryRequest(){
        mWifiP2pManager.addServiceRequest(mP2PChannel, mServiceRequest, new WifiP2pManager.ActionListener(){
            @Override
            public void onSuccess() {
                Log.v(TAG, "Added Service Request... OK");
                mWifiP2pManager.discoverServices(mP2PChannel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        Log.d(TAG, "Started Service Discovery");
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        Log.e(TAG, String.format("failed to initiate discovery request: %s", P2P_ERRORS.get(errorCode)));
                    }
                });
            }
            @Override
            public void onFailure ( int errorCode){
                Log.e(TAG, String.format("failed create service request: %s", P2P_ERRORS.get(errorCode)));
            }

        });
    }

    private void handleFindService(String serviceName){
        FINDING_SERVICES = true;
        if (mServiceRequest != null){
            mWifiP2pManager.removeServiceRequest(mP2PChannel, mServiceRequest, new  WifiP2pManager.ActionListener(){
                @Override
                public void onSuccess() {
                    Log.v(TAG, "Removed Service Request... OK");
                    addDiscoveryRequest();
                }

                @Override
                public void onFailure(int errorCode) {
                    Log.e(TAG, String.format("failed remove service request: %s", P2P_ERRORS.get(errorCode)));
                }
            });
        }else {
            addDiscoveryRequest();
        }
    }

    private void handleFindPeers(String app_name){

    }

    private void handleRegisterEndpoint(){

    }

    private void handleConnect(){

    }

    private void handleStopHost(){
        mWifiP2pManager.removeGroup(mP2PChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Removed group! No longer hosting.");
                if (mProxy != null ){
                    try {
                        mProxy.stopServer();
                    }catch (NullPointerException ex){
                        Log.d(TAG, "tried to stop proxy service that wasn't running...");
                    }
                }


            }

            @Override
            public void onFailure(int errorCode) {
                Log.e(TAG, String.format("failed to stop hosting on channel, error: %s", P2P_ERRORS.get(errorCode)));
            }
        });

    }


    private void handleHost(final String serviceName){
        mWifiP2pManager.createGroup(mP2PChannel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                // Device is ready to accept incoming connections from peers.
                try {
                    Log.d(TAG, "sleeping for a second before requesting group information");
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                mWifiP2pManager.requestConnectionInfo(mP2PChannel, new WifiP2pManager.ConnectionInfoListener() {
                    @Override
                    public void onConnectionInfoAvailable(WifiP2pInfo info) {
                        Log.d(TAG, String.format("Got connection information! | hosting @ %s", info.groupOwnerAddress.getHostAddress()));
                    }
                });
                mWifiP2pManager.requestGroupInfo(mP2PChannel, new WifiP2pManager.GroupInfoListener(){
                    @Override
                    public void onGroupInfoAvailable(WifiP2pGroup group){
                        try {
                            final ArrayList<String> info = new ArrayList<String>();
                            info.add(group.getInterface());
                            info.add(group.getNetworkName());
                            info.add(group.getPassphrase());

                            Log.d(TAG, String.format("hosting group for %s! : info %s", serviceName,
                                    Arrays.toString(info.toArray())));

                        }catch (NullPointerException ex){
                            ex.printStackTrace();
                        }finally{
                            Thread thread = new Thread() {
                                @Override
                                public void run() {
                                    try {
                                        mProxy = new ProxyServer(7700);
                                        mProxy.startServer();
                                        /*
                                        mProxy = new SimpleProxy(7700, "simple-proxy");
                                        mProxy.runServer();
                                        */

                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                            };
                            thread.start();
                        }


                    }
                });
            }

            @Override
            public void onFailure(int errorCode) {
                Log.e(TAG, String.format("failed initialize host for service: %s, error: %s", serviceName, P2P_ERRORS.get(errorCode)));
            }
        });
    }

    private void handleSysP2PStateChanged(Intent intent){
        // Determine if Wifi P2P mode is enabled or not
        int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
        Log.d(TAG, String.format("WIFI P2P state changed. Enabled: %s", state == WifiP2pManager.WIFI_P2P_STATE_ENABLED));
        if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
            P2P_ENABLED = true;
        } else {
            P2P_ENABLED = false;
        }
    }


    private void handleSysP2PPeersChanged(Intent intent){
        Log.d(TAG, "Peers Changed! Requesting data");
        mWifiP2pManager.requestPeers(mP2PChannel, getPeerListener());

    }

    private void handleSysP2PConnectionChanged(Intent intent){
        Log.d(TAG, "P2P Connection Changed");

    }

    private void handleSysP2PThisDeviceChanged(Intent intent){
        WifiP2pDevice device = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
        Log.d(TAG, String.format("This device changed status:\naddress) %s\nname) %s\ncan be discovered) %s\ngroup owner) %s\ndeviceType) %s",
                device.deviceAddress,
                device.deviceName,
                device.isServiceDiscoveryCapable(),
                device.isGroupOwner(),
                device.primaryDeviceType));
    }

    // creates an instance of peer listener. We need to figure out what we want to do to handle these.
    private WifiP2pManager.PeerListListener getPeerListener(){
        return new WifiP2pManager.PeerListListener() {
            @Override
            public void onPeersAvailable(WifiP2pDeviceList peers) {
                mPeers = peers;

                Log.d(TAG, String.format("new peers list found. count: %s", peers.getDeviceList().size()));
                /*
                for(WifiP2pDevice device: mPeers.getDeviceList()){

                }
                */
            }
        };

    }

    //creates an instance of service listener to be registered with the P2P manager
    private WifiP2pManager.DnsSdServiceResponseListener getServiceResponseListener() {
        if(mSdServiceResponseListener == null) {
            mSdServiceResponseListener =  new WifiP2pManager.DnsSdServiceResponseListener() {
                @Override
                public void onDnsSdServiceAvailable(String serviceName, String protocol, WifiP2pDevice deviceInfo) {
                    HashMap<String, WifiP2pDevice> availableDevices;
                    if (!availableServices.containsKey(serviceName)) {
                        Log.d(TAG, String.format("found new service broadcast of type %s", serviceName));
                        availableDevices = new HashMap<String, WifiP2pDevice>();
                    } else {
                        availableDevices = availableServices.get(serviceName);
                    }
                    Log.d(TAG, String.format("device %s found broadcasting service %s @ protocol %s", deviceInfo.deviceAddress, serviceName, protocol));
                    availableDevices.put(deviceInfo.deviceAddress, deviceInfo);
                    availableServices.put(serviceName, availableDevices);
                }
            };
        }
        return mSdServiceResponseListener;

    }

    private WifiP2pManager.DnsSdTxtRecordListener getTxtRecordListener() {
        if(mTxTRecordListener == null) {
            mTxTRecordListener = new WifiP2pManager.DnsSdTxtRecordListener() {
                @Override
                public void onDnsSdTxtRecordAvailable(String domainName, Map<String, String> txtRecordMap, WifiP2pDevice srcDevice) {
                    Log.d(TAG, String.format("hit to TXTRecord Available for domain: %s", domainName));
                    Log.d(TAG, String.format("device info | canDiscover: %s, address: %s | info: %s", srcDevice.isServiceDiscoveryCapable(), srcDevice.deviceAddress, Arrays.toString(txtRecordMap.entrySet().toArray())));
                }
            };
        }
        return mTxTRecordListener;

    }

    /*
    protected class ServiceBroadcastThread implements Runnable {
        private String serviceName;
        private boolean stopService = false;
        private ServiceBroadcastThread(String service){
            serviceName = service;

        }
        @Override
        public void run() {

            mWifiP2pManager.discoverPeers(mP2PChannel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.d(TAG, "success! now discovering peers.");
                }

                @Override
                public void onFailure(int errorCode) {
                    Log.e(TAG, String.format("failed to start discovery with error: %s", P2P_ERRORS.get(errorCode)));
                }
            });
            if (!stopService) {
                mServiceBroadcastHandlers.get(serviceName)
                        .postDelayed(this, SERVICE_BROADCASTING_INTERVAL);
            }else{
                try {
                    mServiceBroadcastHandlers.remove(serviceName);
                }catch(Exception ex){
                    Log.e(TAG, "couldn't stop service " + serviceName);
                    ex.printStackTrace();
                }
            }
        }

        public void stop(){
            stopService = true;
        }

    };
    */


}
