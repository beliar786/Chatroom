package com.example.chatroom;


import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class ChatUI extends ActionBarActivity {
	// Debugging
    private static final String TAG = "BluetoothChat";
    private static final boolean D = true;
    
    // Layout Views
    private ListView mConversationView;
    private EditText mOutEditText;
    private Button mSendButton;
    
	// Local Bluetooth adapter
    private BluetoothAdapter mBluetoothAdapter = null;
    // Array adapter for the conversation thread
    private ArrayAdapter<String> mConversationArrayAdapter;
    // Member object for the chat services
    private ChatService mChatService = null;
    // Name of the connected device
    private String[] mConnectedDeviceName = {null,null,null};
    
    // Key names received from the BluetoothChatService Handler
    public static final String DEVICE_NAME = "device_name";
    
    // Intent request codes
    private static final int REQUEST_ENABLE_BT = 2;
    
    // Message types sent from the BluetoothChatService Handler
    public static final int MESSAGE_READ = 1;
    public static final int MESSAGE_WRITE = 2;
    public static final int MESSAGE_DEVICE_NAME = 3;
    public static final int MESSAGE_TOAST_LOST = 4;
    public static final int MESSAGE_TOAST_FAIL = 5;
    public static final int MESSAGE_TEST = 6; //**test
    public static final int MESSAGE_UUID_USE = 7; //**test
    public static final int MESSAGE_STATE_CHANGE = 8;
    
    // Intent request codes
    private static final int REQUEST_CONNECT_DEVICE = 1;
    

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		
		// Register for broadcasts when a device is discovered
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        this.registerReceiver(mReceiver, filter);
		
		// Get local Bluetooth adapter
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // If the adapter is null, then Bluetooth is not supported
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

	    // If BT is not on, request that it be enabled.
	    // setupChat() will then be called during onActivityResult
	    if (!mBluetoothAdapter.isEnabled()) {
	        Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
	        startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
	    // Otherwise, setup the chat session
	    } else {
	        if (mChatService == null); setupChat();
	    }
        
        //Ensure to be visible
	    ensureDiscoverable();
	}
	
	 @Override
	 public void onStart() {
	     super.onStart();
	     if(D) Log.e(TAG, "++ ON START ++");
	 }
	 
	 @Override
	 public synchronized void onResume() {
	     super.onResume();
	     if(D) Log.e(TAG, "+ ON RESUME +");

	     // Performing this check in onResume() covers the case in which BT was
	     // not enabled during onStart(), so we were paused to enable it...
	     // onResume() will be called when ACTION_REQUEST_ENABLE activity returns.
	     if (mChatService != null) {
	         // Only if the state is STATE_NONE, do we know that we haven't started already
	         if (mChatService.getState() == ChatService.STATE_NONE) {
	           // Start the Bluetooth chat services
	           mChatService.start();
	           doDiscovery();
	         }
	     }
	     
	     /*if (mChatService.getState() == ChatService.STATE_NONE) { //**error
	    	 doDiscovery();
	     }*/
	 }
	 
	//Ensure dicoverable
	 private void ensureDiscoverable() {
	     if (mBluetoothAdapter.getScanMode() !=
	             BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
	             Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
	             discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
	             startActivity(discoverableIntent);
	     }
	 }
	 
	 //search for device around you
	 private void doDiscovery() {
		// If we're already discovering, stop it
	    if (mBluetoothAdapter.isDiscovering()) {
	        mBluetoothAdapter.cancelDiscovery();
	    }

	    // Request discover from BluetoothAdapter
	    mBluetoothAdapter.startDiscovery();
	 }
	 
	 private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
	        @Override
	        public void onReceive(Context context, Intent intent) {
	            String action = intent.getAction();

	            // When discovery finds a device
	            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
	                // Get the BluetoothDevice object from the Intent
	                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
	                
	                //Cancel discovery first,then connect the device
	                mBluetoothAdapter.cancelDiscovery();
	                mChatService.connect(device);
	            }
	        }
	 };

	 private void setupChat() {
	     Log.d(TAG, "setupChat()");

	     // Initialize the array adapter for the conversation thread
	     mConversationArrayAdapter = new ArrayAdapter<String>(this, R.layout.message);
	     mConversationView = (ListView) findViewById(R.id.in);
	     mConversationView.setAdapter(mConversationArrayAdapter);

	     // Initialize the compose field with a listener for the return key
	     mOutEditText = (EditText) findViewById(R.id.edit_text_out);
	     mOutEditText.setOnEditorActionListener(mWriteListener);
	     
	     //Initialize the send button
	     mSendButton = (Button) findViewById(R.id.button_send);
	     mSendButton.setOnClickListener(new OnClickListener() {
	    	 public void onClick(View v) {
	    		 TextView t = (TextView) findViewById(R.id.edit_text_out);
	    		 String message = t.getText().toString();
	    		 sendMessage(message);
	    	 }
	     });
	     
	     // Initialize the BluetoothChatService to perform bluetooth connections
	     mChatService = new ChatService(this, mHandler);
	 }
	 
	private TextView.OnEditorActionListener mWriteListener =
	    new TextView.OnEditorActionListener(){
		public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
			String message = view.getText().toString();
            sendMessage(message);
			return true;
			
			
		}
	};
	 
	private void sendMessage(String message){
		// Check that there's actually something to send
        if (message.length() > 0) {
        	// Get the message bytes and tell the BluetoothChatService to write
        	String MyName = mBluetoothAdapter.getName();
        	String fullMessage = MyName + ":  " + message;
            byte[] send = fullMessage.getBytes();
            mChatService.write_message(send);
        	
            //Clear the edit text field
        	mOutEditText.setText("");
        }
	}
	
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MESSAGE_STATE_CHANGE:
                if(D) Log.i(TAG, "MESSAGE_STATE_CHANGE: " + msg.arg1);
                switch (msg.arg1) {
                case ChatService.STATE_CONNECTED:
                case ChatService.STATE_CONNECTING:
                case ChatService.STATE_LISTEN:
                case ChatService.STATE_NONE:
                }
                break;
            case MESSAGE_DEVICE_NAME:
                // save the connected device's name
                mConnectedDeviceName[msg.arg2] = msg.getData().getString(DEVICE_NAME);
                Toast.makeText(getApplicationContext(), "Connected to "
                               + mConnectedDeviceName[msg.arg2], Toast.LENGTH_LONG).show();
                break;
            case MESSAGE_READ:
                byte[] readBuf = (byte[]) msg.obj;
                mChatService.write_relay(readBuf,msg.arg2);
                
                /*String readMessage = new String(readBuf, 1, msg.arg1-1);
                if (readMessage.length() > 0) {
                	mConversationArrayAdapter.add(readMessage);
                	Toast.makeText(getApplicationContext(),"READ",
                            Toast.LENGTH_SHORT).show();
                }*/
                
                //We tag the data at the fist element before send it out, now we need to distinguish them
                switch(readBuf[0]) {
                	case 1:// 1 represent a message , we need to show it on the screen
                	case 2:
                		// construct a string from the valid bytes in the buffer
                        String readMessage = new String(readBuf, 1, msg.arg1-1);
                        if (readMessage.length() > 0) {
                        	mConversationArrayAdapter.add(readMessage);
                        	Toast.makeText(getApplicationContext(),"READ",
                                Toast.LENGTH_SHORT).show();
                        }
                        break;
                }
                break;  
            case MESSAGE_WRITE:
                byte[] writeBuf = (byte[]) msg.obj;
                // construct a string from the buffer
                String writeMessage = new String(writeBuf);
                mConversationArrayAdapter.add(writeMessage);
                break;
            	
            case MESSAGE_TOAST_LOST:
                Toast.makeText(getApplicationContext(),"Device connection was lost",
                        Toast.LENGTH_SHORT).show();
                break;
            case MESSAGE_TOAST_FAIL:
            	Toast.makeText(getApplicationContext(),"Socket "+msg.arg2+" close fail.",
            			Toast.LENGTH_SHORT).show();
            	break;
            case MESSAGE_TEST: //**test
            	Toast .makeText(getApplicationContext(),"UUID: "+msg.arg1,
                Toast.LENGTH_SHORT).show();
            	mBluetoothAdapter.cancelDiscovery();
            	break;
            case MESSAGE_UUID_USE: //**test
            	Toast .makeText(getApplicationContext(),"use UUID"+msg.arg2,
                        Toast.LENGTH_SHORT).show();
            	break;
            }
        }
    };
	
    @Override
    public synchronized void onPause() {
        super.onPause();
        if(D) Log.e(TAG, "- ON PAUSE -");
    }

    @Override
    public void onStop() {
        super.onStop();
        if(D) Log.e(TAG, "-- ON STOP --");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Stop the Bluetooth chat services
        if (mChatService != null) mChatService.stop();
        if(D) Log.e(TAG, "--- ON DESTROY ---");
    }
    
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(D) Log.d(TAG, "onActivityResult " + resultCode);
        switch (requestCode) {
        /*case REQUEST_CONNECT_DEVICE:
            // When DeviceListActivity returns with a device to connect
            if (resultCode == Activity.RESULT_OK) {
                // Get the device MAC address
                String address = data.getExtras()
                                     .getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
                // Get the BLuetoothDevice object
                BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
                // Attempt to connect to the device
                mChatService.connect(device);
            }
            break;*/
        case REQUEST_ENABLE_BT:
            // When the request to enable Bluetooth returns
            if (resultCode == Activity.RESULT_OK) {
                // Bluetooth is now enabled, so set up a chat session
                setupChat();
            } else {
                // User did not enable Bluetooth or an error occured
                Log.d(TAG, "BT not enabled");
                Toast.makeText(this, R.string.bt_not_enabled_leaving, Toast.LENGTH_LONG).show();
                finish();
            }
            break;
        }
    }
	
	/*@Override //**
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}*/

	/*@Override //**
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
		if (id == R.id.action_connect) {
			// Launch the DeviceListActivity to see devices and do scan
            Intent serverIntent = new Intent(this, DeviceListActivity.class);
            startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
            //**return true;
		}
		return super.onOptionsItemSelected(item);
	}*/
}
