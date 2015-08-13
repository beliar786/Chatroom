package com.example.chatroom;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

public class ChatService {
	// Debugging
    private static final String TAG = "ChatService";
    private static final boolean D = true;
    
	// Name for the SDP record when creating server socket
    private static final String NAME = "ChatMulti";
    
	// Member fields
    private final BluetoothAdapter mAdapter;
    private final Handler mHandler;
    private AcceptThread mAcceptThread;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private int mState;
    
    private ArrayList<String> mDeviceAddresses; //record the device's address that make a connect to you (maximum:3)
    private ArrayList<ConnectedThread> mConnThreads;
    private ArrayList<AcceptThread> mAcceThreads;
    private ArrayList<BluetoothSocket> mSockets;
    private ArrayList<UUID> mUUIDs;
    private ArrayList<String> mAddressList; //record all the device's address that is in the Bluetooth net
    private ArrayList<String> mNameList; //record all the device's name that is in the Bluetooth net
    
    //record how many AcceptThread is used , if two AcceptThread is used , then cancel all the AcceptThread
    int AcceptAmount = 0;
    
    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_LISTEN = 1;     // now listening for incoming connections
    public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 3;  // now connected to a remote device
    

    
	public ChatService(Context context, Handler handler) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = STATE_NONE;
        mHandler = handler;
        mAcceThreads = new ArrayList<AcceptThread>();
        mConnThreads = new ArrayList<ConnectedThread>();
        mDeviceAddresses = new ArrayList<String>();
        mSockets = new ArrayList<BluetoothSocket>();
        mUUIDs = new ArrayList<UUID>();
        // 3 randomly-generated UUIDs. These must match on both server and client.
        // two for accept a connection and one for request a connection 
        mUUIDs.add(UUID.fromString("b7746a40-c758-4868-aa19-7ac6b3475dfc"));
        mUUIDs.add(UUID.fromString("2d64189d-5a2c-4511-a074-77f199fd0834"));
        mUUIDs.add(UUID.fromString("e442e09a-51f3-4a7b-91cb-f638491d1412"));
        mAddressList = new ArrayList<String>();
        mAddressList.add(mAdapter.getAddress());
        mNameList = new ArrayList<String>();
        mNameList.add(mAdapter.getName());
        
        for(int i=0;i<3;i++) {
        	 mConnThreads.add(null);
             mDeviceAddresses.add(null);
             mSockets.add(null);
        }
    }
	
	 private synchronized void setState(int state) {
	     if (D) Log.d(TAG, "setState() " + mState + " -> " + state);
	     mState = state;
	     
	     mHandler.obtainMessage(ChatUI.MESSAGE_STATE_CHANGE,mState,-1);
	 }
	
	 public synchronized int getState() {
	        return mState;
	 }
	 
	 public synchronized void start() {
	        if (D) Log.d(TAG, "start");

	        // Cancel any thread attempting to make a connection
	        if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}

	        // Cancel any thread currently running a connection
	        if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}

	        // Start the thread to listen on a BluetoothServerSocket
	        if (mAcceptThread == null) {
	        	for(int i=0;i<3;i++) {
	        		mAcceptThread = new AcceptThread(i);
	                mAcceptThread.start();
	                mAcceThreads.add(mAcceptThread);
	        	}
	        }
	        setState(STATE_LISTEN);
	 }
	 
	 public synchronized void stop() {
	     if (D) Log.d(TAG, "stop");
	     if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}
	     if (mConnectedThread != null) {mConnectedThread.cancel(); mConnectedThread = null;}
	     if (mAcceptThread != null) {mAcceptThread.cancel(); mAcceptThread = null;}
	     setState(STATE_NONE); 
	 }
	 
	 
	 public synchronized void connect(BluetoothDevice device) {
	     if (D) Log.d(TAG, "connect to: " + device);

	     // Cancel any thread attempting to make a connection
	     if (mState == STATE_CONNECTING) {
	         if (mConnectThread != null) {mConnectThread.cancel(); mConnectThread = null;}
	     }

	     // Create a new thread and attempt to connect to each UUID one-by-one.    
	     try {
             mConnectThread = new ConnectThread(device);
             mConnectThread.start();
             setState(STATE_CONNECTING); 
     	} catch (Exception e) {}
	 }
	 
	 public synchronized void connected(BluetoothSocket socket, BluetoothDevice device,int i) {
	     if (D) Log.d(TAG, "connected");
	     
	     //Record the name and address who directly connect to you
	     mSockets.set(i,socket); //**當connect斷掉實有清除嗎? 下一行呢? //**2
 		 mDeviceAddresses.set(i,socket.getRemoteDevice().getAddress()); //**2

	     // Start the thread to manage the connection and perform transmissions
	     mConnectedThread = new ConnectedThread(socket,i);
	     mConnectedThread.start();
	     // Add each connected thread to an array
	     mConnThreads.set(i,mConnectedThread);
	     
	     //Write my address/name list to that device,and if that device has connected to the other device
	     //then the address/name list will pass on those device too.
	     write_addressName(device,i);

	     // Send the name of the connected device back to the UI Activity
	     Message msg = mHandler.obtainMessage(ChatUI.MESSAGE_DEVICE_NAME,-1,i); //**
	     Bundle bundle = new Bundle();
	     bundle.putString(ChatUI.DEVICE_NAME, device.getName());
	     msg.setData(bundle);
	     mHandler.sendMessage(msg);

	     setState(STATE_CONNECTED);
	    }
	 
	private void connectionLost() {
	    setState(STATE_LISTEN); 

	    // Send a failure message back to the Activity
	    mHandler.obtainMessage(ChatUI.MESSAGE_TOAST_LOST).sendToTarget();
	}
	
	private void socketCloseFailed(int i) {
        setState(STATE_LISTEN); 
        
        // Send a failure message back to the Activity
	    mHandler.obtainMessage(ChatUI.MESSAGE_TOAST_FAIL-1,i).sendToTarget();
	}
	
	/**
     * Write to the ConnectedThread in an unsynchronized manner
     * @param out The bytes to write
     * @see ConnectedThread#write(byte[])
     */
    public void write_message(byte[] out) { //**
    	//Add 1 at the first element to tag the byte data - message
    	//out = {X,X,X,X,X ...} , MESSAGE = {1,X,X,X,X,X ...}
        byte[] MESSAGE = new byte[1024];
        MESSAGE[0] = 1;
    	System.arraycopy(out, 0, MESSAGE, 1, out.length); 
    	
    	// When writing, try to write out to all connected threads 
    	for (int i = 0; i < 3; i++) { //**
    		try {
                // Create temporary object
                ConnectedThread r;
                // Synchronize a copy of the ConnectedThread
                synchronized (this) {
                    r = mConnThreads.get(i);
                }
                // Perform the write unsynchronized
                r.write(MESSAGE);
    		} catch (Exception e) {    			
    		}
    	}
    }
    
    //Relay the data you received to other connected devices
    public void write_relay(byte[] out,int I) { 
    	for(int i=0;i<3; i++) { //**
    		try {
    			ConnectedThread r;
    			synchronized (this) {
                    r = mConnThreads.get(i);
    			}
    			
    			if(r.getNumber() != I)
    				r.write_relay(out);
    		} catch(Exception e) {
    		}
    	}
    }
    
    public void write_addressName(BluetoothDevice device,int i) {
    	//Combine all the address(String) and name(String) to a String writen as : address1,name1,address2,name2 ....
    	String addressNameString=mAddressList.get(0)+","+mNameList.get(0); //my address and name
    	for(int j=1;j<mAddressList.size();j++) {
    		if(mAddressList.get(j)!=device.getAddress()) { //we don't want to deliver his address/name to himself
    			addressNameString+=",";
    			addressNameString+=mAddressList.get(j);
    			addressNameString+=",";
    			addressNameString+=mNameList.get(j);
    		}
    	}
    	
    	//Convert the String address/name to byte address/name
    	byte[] addressNameByte = addressNameString.getBytes();
    	
    	//Add 2 at the first element to tag the byte data - address nad name
    	//addressByte = {X,X,X,X ...} , ADDRESSNAME = {2,X,X,X,X ...}
    	byte[] ADDRESSNAME = new byte[addressNameByte.length+1];
    	ADDRESSNAME[0] = 2;
    	System.arraycopy(addressNameByte, 0, ADDRESSNAME, 1, addressNameByte.length);
    	
    	ConnectedThread r;
    	synchronized (this) {
            r = mConnThreads.get(i);
		}
    	
    	r.write_relay(ADDRESSNAME);
    }
    
    
    /*public void write_address(BluetoothDevice device,int i) { //**1
    	//Combine all the address(String) to a String ,separate by ","
    	String addressString=mAddressList.get(0); //my address
    	for(int j=1;j<mAddressList.size();j++) {
    		if(mAddressList.get(j)!=device.getAddress()) { //we don't want to deliver his address to himself
    			addressString+=",";
    			addressString+=mAddressList.get(j);
    		}
    	}
    	
    	//Convert the String address to byte address
    	byte[] addressByte = addressString.getBytes();
    	
    	//Add 2 at the first element to tag the byte data - address
    	//addressByte = {X,X,X,X ...} , ADDRESS = {2,X,X,X,X ...}
    	byte[] ADDRESS = new byte[addressByte.length+1];
    	ADDRESS[0] = 2;
    	System.arraycopy(addressByte, 0, ADDRESS, 1, addressByte.length);
    	
    	ConnectedThread r;
    	synchronized (this) {
            r = mConnThreads.get(i);
		}
    	
    	r.write_relay(ADDRESS);
    }
    
    public void write_name(BluetoothDevice device,int i) {
    	//Combine all the name(String) to a String ,separate by ","
    	String nameString=mNameList.get(0); //my name
    	for(int j=1;j<mNameList.size();j++) {
    		if(mNameList.get(j)!=device.getAddress()) { //we don't want to deliver his name to himself
    			nameString+=",";
    			nameString+=mNameList.get(j);
    		}
    	}
    	
    	//Convert the String name to byte name
    	byte[] nameByte = nameString.getBytes();
    	
    	//Add 3 at the first element to tag the byte data - name
    	//nameByte = {X,X,X,X ...} ,NAME = {3,X,X,X,X ...}
    	byte[] NAME = new byte[nameByte.length+1];
    	NAME[0] = 3;
    	System.arraycopy(nameByte, 0, NAME, 1, nameByte.length);
    	
    	ConnectedThread r;
    	synchronized (this) {
            r = mConnThreads.get(i);
		}
    	
    	r.write_relay(NAME);
    }*/
	
    private class AcceptThread extends Thread {
    	BluetoothServerSocket serverSocket = null;
    	int I=0;
        
        public AcceptThread(int i) {
        	I=i;
        }

        public void run() {
            if (D) Log.d(TAG, "BEGIN mAcceptThread" + this);
            setName("AcceptThread");
            BluetoothSocket socket = null;
            try {
            	serverSocket = mAdapter.listenUsingInsecureRfcommWithServiceRecord(NAME, mUUIDs.get(I));
                socket = serverSocket.accept();
                if (socket != null) {
                	mHandler.obtainMessage(ChatUI.MESSAGE_TEST,I,-1).sendToTarget();
                 	
                    connected(socket, socket.getRemoteDevice(),I);
                    AcceptAmount++;
               }	                    
            } catch (IOException e) {
                Log.e(TAG, "accept() failed", e);
            }
            cancel(); //To prevent another device connect us by this UUID
            if (D) Log.i(TAG, "END mAcceptThread");
            
            if(AcceptAmount >=2 ) {
            	AcceptThread a;
            	synchronized (this) {
                    for(int i=0;i<3;i++) {
                    	a = mAcceThreads.get(i);
                    	if(a != null) {
                    		a.cancel();
                        	a = null;
                    	}
                    }
        		}
            }
            
        }

        public void cancel() { 
            if (D) Log.d(TAG, "cancel " + this);
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of server failed", e);
            }
        }
    }
    
	/*private class AcceptThread extends Thread{
		BluetoothServerSocket serverSocket = null;
		int count = 0;
		 
		public AcceptThread(){}
		 
		public void run(){
			if (D) Log.d(TAG, "BEGIN mAcceptThread" + this);
			setName("AcceptThread");
			BluetoothSocket socket = null;
			try {
	            // Listen for all 3 UUIDs
	            for (int i = 0; i < 3; i++) { 
	            	boolean repeat = false;
	            	serverSocket = mAdapter.listenUsingInsecureRfcommWithServiceRecord(NAME, mUUIDs.get(i));
	            	socket = serverSocket.accept();
	            	if (socket != null) {
	            		String address = socket.getRemoteDevice().getAddress();
	            		mHandler.obtainMessage(ChatUI.MESSAGE_TEST,i,-1).sendToTarget();//**
	            		
	            		//To judge whether this device has connected to me 
	            		for(int j=0;j<mAddressList.size();j++) { //**
	            			if(address == mAddressList.get(j)) {
                    			socket.close();
                    			i--;
                    			repeat=true;
                    			break;
                    		}
	            		}
	            		if(!repeat) {
	            			/* mSockets.add(i,socket); //**當connect斷掉實有清除嗎? 下一行呢? //**2
		            		mDeviceAddresses.add(i,address); //**2
		            		mAddressList.add(address); //**1 *+/
		            		connected(socket, socket.getRemoteDevice(),i);
		            		count++;
	            		}
	            	}
	            	
	            	//If we have accept two connection,then stop accept
	            	if(count >= 2) {
	            		synchronized (ChatService.this) {
	       	             mAcceptThread = null;
	       	         	}
	            	}
	            }
			} catch (IOException e) {
	             Log.e(TAG, "accept() failed", e);
	        }
	        if (D) Log.i(TAG, "END mAcceptThread");
		}

	    public void cancel() {
	        if (D) Log.d(TAG, "cancel " + this);
	        try {
	            serverSocket.close();
	        } catch (IOException e) {
	            Log.e(TAG, "close() of server failed", e);
	        }
	    }	 
	}*/
	
	private class ConnectThread extends Thread {
        private  BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;
        boolean connect_success;
        int I=0;

        public ConnectThread(BluetoothDevice device) { //***
            mmDevice = device;
            mmSocket = null;
        }
        
        public void run() {
            // Always cancel discovery because it will slow down a connection
            mAdapter.cancelDiscovery();
                
            for(int i=0;i<3;i++) {
                I=i;
                connect_success = true;
                	
                try {
                	mmSocket = mmDevice.createInsecureRfcommSocketToServiceRecord(mUUIDs.get(i));
                	mHandler.obtainMessage(ChatUI.MESSAGE_UUID_USE, -1, i).sendToTarget();
                } catch(IOException e) {}
                	
                try {
                	mmSocket.connect();
                } catch (IOException e) {
                	connect_success = false;
                    // Close the socket
                    try {
                        mmSocket.close();
                    } catch (IOException e2) {
                        Log.e(TAG, "unable to close() socket during connection failure", e2);
                    }
                }
                	
                //If socket connect success , jump out the loop
                if(connect_success) {
                	//close the UUID[I]'s AcceptThread , because we have used it to connect other device
                	synchronized (mAcceThreads.get(I)) {
                		AcceptThread a = mAcceThreads.get(I);
                		a.cancel();
                		a= null;
                	}
                	break;
                }
            }
                
            //If all connect fail , jump out the ConnectThread 
            if(!connect_success) {
                return;
            }
                
            // Reset the ConnectThread because we're done
            synchronized (ChatService.this) {
                mConnectThread = null;
            }

            // Start the connected thread
            connected(mmSocket, mmDevice,I);
        }
        
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }
	 
	 /*private class ConnectThread extends Thread{
	     private final BluetoothSocket mmSocket;
	     private final BluetoothDevice mmDevice;
	     private UUID tempUuid;
	     int I=0;
	     
		 public ConnectThread(BluetoothDevice device,UUID uuidToTry,int i) {
	         mmDevice = device;
	         BluetoothSocket tmp = null;
	         tempUuid = uuidToTry;
	         I=i;
	         
	         // Get a BluetoothSocket for a connection with the given BluetoothDevice
	         try {
	             tmp = device.createInsecureRfcommSocketToServiceRecord(uuidToTry);        	
	         } catch (IOException e) {
	             Log.e(TAG, "create() failed", e);
	         }
	         mmSocket = tmp;
		 }
		 
		 public void run() {
			 Log.i(TAG, "BEGIN mConnectThread");
	         setName("ConnectThread");
	         
	         // Always cancel discovery because it will slow down a connection
	         mAdapter.cancelDiscovery();
	         
	         // Make a connection to the BluetoothSocket
	         try {
	             // This is a blocking call and will only return on a
	             // successful connection or an exception
	             mmSocket.connect();
	         } catch (IOException e) {
	             // Close the socket
	             try {
	                 mmSocket.close();
	             } catch (IOException e2) {
	                 Log.e(TAG, "unable to close() socket during connection failure", e2);
	                 socketCloseFailed(I); //**
	             }
	             // Start the service over to restart listening mode
	             ChatService.this.start(); //*** 應該改成，釋出這個UUID
	             return;
	         }
	         
	         // Reset the ConnectThread because we're done
	         synchronized (ChatService.this) {
	             mConnectThread = null;
	         }

	         // Start the connected thread
	         connected(mmSocket, mmDevice,I);
		 }
		 
		 public void cancel() {
			 try {
	                mmSocket.close();
	         } catch (IOException e) {
	                Log.e(TAG, "close() of connect socket failed", e);
	            }
		 }
	 }*/
	 
	 private class ConnectedThread extends Thread{
		 private final BluetoothSocket mmSocket;
	     private final InputStream mmInStream;
	     private final OutputStream mmOutStream;
	     int I=0;
	        
		 public ConnectedThread(BluetoothSocket socket,int i){
			 Log.d(TAG, "create ConnectedThread");
			 
			 mmSocket = socket;
			 InputStream tmpIn = null;
	         OutputStream tmpOut = null;
	         I=i;
	         
	         try{
	        	 tmpIn = mmSocket.getInputStream();
	        	 tmpOut = mmSocket.getOutputStream();
	         } catch(IOException e){
	        	 Log.e(TAG, "temp sockets not created", e);
	         }
	         
	         mmInStream = tmpIn;
	         mmOutStream = tmpOut;
		 }
	       
		 public void run() {
			 Log.i(TAG, "BEGIN mConnectedThread");
			 byte[] buffer = new byte[1024];
			 int bytes;
	         
			 while(true){
				 try{
					 // Read from the InputStream
					 bytes = mmInStream.read(buffer);
	                 
					//We tag the data at the fist element before send it out, now we need to distinguish them
		             switch(buffer[0]) {
		             case 1: // 1 represent a message , we need to show it on the screen
		            	 // Send the obtained bytes to the UI Activity
		            	 mHandler.obtainMessage(ChatUI.MESSAGE_READ, bytes, I, buffer).sendToTarget();
		                 break;
		             case 2: //2 represent a address/name list , we need to add this to mAddress/NameList
		            	 String addressNameList = new String(buffer,1,buffer.length-1);//Ignore 2 and convert it to string
		            	 //received data : address1,name1,address2,name2 ...
		            	 String[] addressNameArray = addressNameList.split(",");
		            	 
		            	 //add the String[] to arraylist<String>
		            	 for(int j=0;j<addressNameArray.length;j+=2)  {
		            		 mAddressList.add(addressNameArray[j]);
		            	 }
		            	 for(int k=1;k<addressNameArray.length;k+=2) {
		            		 mNameList.add(addressNameArray[k]);
		            	 }
		            	 
		            	 //send the address/name list to UI,and UI will send it to other connected devices
		            	 mHandler.obtainMessage(ChatUI.MESSAGE_READ, bytes, I, buffer).sendToTarget();
		            	 break;
		             }
				 } catch(IOException e){
	         	   Log.e(TAG, "disconnected", e);
	         	   connectionLost();
	         	   break;
				 }
			 }
		 }
		 
		 public int getNumber() { //**訊息接力
	        	return I;
	     }
		 
		 public void write(byte[] buffer) {
			 try {
				 mmOutStream.write(buffer);

	             // Share the sent message back to the UI Activity
	             mHandler.obtainMessage(ChatUI.MESSAGE_WRITE, -1, -1, buffer)
	                     .sendToTarget();
	         } catch (IOException e) {
	             Log.e(TAG, "Exception during write", e);
	         }
		 }
		 
		 public void write_relay(byte[] buffer){
	        try {
	        	mmOutStream.write(buffer);
	        } catch (IOException e) {
	            Log.e(TAG, "Exception during write", e);
	        }
	    }
	 
		 public void cancel(){
			 try {
	             mmSocket.close();
	         } catch (IOException e) {
	             Log.e(TAG, "close() of connect socket failed", e);
	         }
		 }
	 }
}


