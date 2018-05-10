package com.example.matchburn.newhmsoftbluetooth;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import com.jjoe64.graphview.series.DataPoint;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

//TODO: Implement the Bluetooth code here

public class MainActivity extends AppCompatActivity {
    private final String TAG = ".MainActivity";

    //Information about the board
    //Change these if the device's address or service/characteristic UUIDs change
    public static String HMSoftAddress = "F0:C7:7F:94:CF:97";
    public static final String HMSoftServ = "0000ffe0-0000-1000-8000-00805f9b34fb";
    public  static final String HMSoftChar = "0000ffe1-0000-1000-8000-00805f9b34fb";

    //Text View

    //Writing to board
    private boolean isRecording;
    private File outFile;
    private FileOutputStream outStream;

    //Reading from settings

    //Needed for Bluetooth
    private int count; //Prevent from scanning forever
    //private boolean readyTo;
    private boolean foundChar;
    private BluetoothAdapter mBluetoothAdapter;
    private boolean mScanning;
    private Handler mHandler;

    private ArrayList<String> deviceList; //Holds number of lists
    private static final int REQUEST_ENABLE_BT = 1;
    // Stops scanning after 10 seconds.
    private static final long SCAN_PERIOD = 10000;

    //Needed after HMSoft is connected
    private BluetoothLeService mBluetoothLeService;
    private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics =
            new ArrayList<ArrayList<BluetoothGattCharacteristic>>();
    private BluetoothGattCharacteristic mNotifyCharacteristic;
    private BluetoothGattCharacteristic bluetoothGattCharacteristicHM_SOFT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        deviceList = new ArrayList<>();
        mHandler = new Handler();
        foundChar = false;
        isRecording = false;

        //Bluetooth and writing feature checking
        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "Bluetooth not supported!", Toast.LENGTH_SHORT).show();
            finish();
        }
        // Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to
        // BluetoothAdapter through BluetoothManager.
        final BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();

        // Checks if Bluetooth is supported on the device.
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported!", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        checkBTAndWritePermissions();
        //Setup the address reading
        File readDir = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/HMSOFTCORTISOL/SETTINGS");
        if(!readDir.exists())
            readDir.mkdir();
        File settings = new File(readDir,"settings.txt");
        if(!settings.exists()){ //If settings file does not exist, create it with default address
            try {
                settings.createNewFile();
                FileOutputStream os = new FileOutputStream(settings);
                String mess = "Address: " + HMSoftAddress + "\n";
                os.write(mess.getBytes());
                os.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }else{ //Read the address
            try {
                FileInputStream is = new FileInputStream(settings);
                String address = "";
                boolean record = false;
                int charNum;
                while((charNum = is.read()) != -1){ //While there are things to read, only record address
                    if((char)charNum != ' ' && record)
                        address += (char)charNum;
                    if((char)charNum == ':')
                        record = true;
                    if((char)charNum == '\n')
                        record = false;
                }
                HMSoftAddress = address;
                is.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        //Setup other stuff TODO
    }

    @Override
    protected void onResume(){
        super.onResume();

        // Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
        // fire an intent to display a dialog asking the user to grant permission to enable it.
        if (!mBluetoothAdapter.isEnabled() && !foundChar) {
            if (!mBluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }
        }
        //Start scanning for devices
        if(!foundChar && BluetoothApp.getApplication().getService()==null) //already stored BluetoothLeService
            scanLeDevice(true);

        if(foundChar || BluetoothApp.getApplication().getService()!=null){
            foundChar= true; //No need to scan again
            checkIfCharacteristic(BluetoothApp.getApplication().getService().getSupportedGattServices());
            //If restarted, bypass scanning
            if(BluetoothApp.getApplication().getService()!=null && mBluetoothLeService==null) {
                bluetoothGattCharacteristicHM_SOFT = BluetoothApp.getApplication().getGattCharacteristic();
                mBluetoothLeService = BluetoothApp.getApplication().getService();
                Log.i(TAG,"mBluetoothLeService has been set, null?: " + (mBluetoothLeService==null));
            }
            registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
            if (mBluetoothLeService != null) {
                final boolean result = mBluetoothLeService.connect(HMSoftAddress);
                Log.d(TAG, "Connect request result=" + result);
            }
        }
    }

    @Override
    protected void onDestroy(){
        super.onDestroy();
        if(outStream != null) {
            try {
                outStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    //Checks if external storage is available for read and write
    public boolean isExternalStorageWritable(){
        String state = Environment.getExternalStorageState();
        if(Environment.MEDIA_MOUNTED.equals(state))
            return true;
        return false;
    }

    //Check permissions of device (Location and writing permissions)
    private void checkBTAndWritePermissions() {
        if(Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP){
            int permissionCheck = this.checkSelfPermission("Manifest.permission.ACCESS_FINE_LOCATION");
            permissionCheck += this.checkSelfPermission("Manifest.permission.ACCESS_COARSE_LOCATION");
            if (permissionCheck != 0) {

                this.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.WRITE_EXTERNAL_STORAGE }, 1); //Any number
            }
        }else{
            Log.d(TAG, "checkBTandWritePermissions: No need to check permissions. SDK version < LOLLIPOP.");
        }
    }

    //Bluetooth functions
    private void scanLeDevice(final boolean enable) {
        if (enable) {
            // Stops scanning after a pre-defined scan period.
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    //Make sure scanning is turned off
                    mScanning = false;
                    mBluetoothAdapter.stopLeScan(mLeScanCallback);
                    invalidateOptionsMenu(); //Double check this
                    //Start scanning again,
                    count++;
                    if(count < 3) {
                        Log.i(TAG,"Did not find HMSoft device, searching again");
                        scanLeDevice(true);
                    }
                }
            }, SCAN_PERIOD);

            mScanning = true;
            mBluetoothAdapter.startLeScan(mLeScanCallback);
        } else {
            mScanning = false;
            mBluetoothAdapter.stopLeScan(mLeScanCallback);
        }
        invalidateOptionsMenu();
    }

    // Device scan callback.
    private BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {

                @Override
                public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            checkDeviceName(device);
                        }
                    });
                }
            };

    private void checkDeviceName(BluetoothDevice device){
        //Prevent spam of the log by keeping track of what was already found
        if(device.getAddress()!=null && ! deviceList.contains(device.getAddress())) {
            Log.i(TAG, "Found Device: " + device.getName() + "\n" + device.getAddress());
            deviceList.add(device.getAddress());
            if (device.getAddress().equals(HMSoftAddress)) {
                Log.i(TAG, "Found HMSoft!");
                //Stop scanning
                count = 3;
                if (mScanning) {
                    mBluetoothAdapter.stopLeScan(mLeScanCallback);
                    mScanning = false;
                }
                //readyTo = true;
                Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
                bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
                registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
            }
        }
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }

    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) { //When it receives something from the device
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                Log.i(TAG,"Connected to HMSOFT!");
                Toast.makeText(getApplicationContext(),"Connected to HMSoft!", Toast.LENGTH_SHORT).show();
                invalidateOptionsMenu();
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                invalidateOptionsMenu();
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                Log.i(TAG,"Found Services!");
                checkIfCharacteristic(mBluetoothLeService.getSupportedGattServices());
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                //Read current
                String returnedVal = intent.getStringExtra(BluetoothLeService.EXTRA_DATA);
                Log.i(TAG,returnedVal);
                if(!isRecording) { //Create the output file and file stream
                    outFile = createFile();
                    isRecording = true;
                    try {
                        outStream = new FileOutputStream(outFile);
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    }
                }
                //TODO: Write the fft in a new column in the same file
                if(returnedVal.equals("resUnfiltered FFT, resFiltered FFT")){}
                //Write to the output file
                try {
                    outStream.write(returnedVal.getBytes());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    };

    private File createFile(){
        File dir = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/HMSOFTCORTISOL");
        if(!dir.exists()) //Create directory if it does not exist
            dir.mkdir();
        String title = BluetoothApp.getDateString() + "_" + BluetoothApp.getTimeString();
        File outputFile = new File(dir,title + ".txt");
        if(!outputFile.exists()){
            try {
                outputFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return outputFile;
    }

    //Checks to see if it is the data from the board we need (current)
    private void checkIfCharacteristic(List<BluetoothGattService> gattServices){
        if(gattServices==null || foundChar)
            return;
        Log.i(TAG,"Checking characteristics...");
        String tempUUID;
        UUID UUID_HM_SOFT = UUID.fromString(HMSoftChar);
        //Loop through services
        for(BluetoothGattService gattService : gattServices){
            tempUUID = gattService.getUuid().toString();
            Log.i(TAG,"Service: " + tempUUID);
            if(tempUUID.equals(HMSoftServ)){
                List<BluetoothGattCharacteristic> gattCharacteristics =
                        gattService.getCharacteristics();
                //Loop through characteristics
                for(BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics){
                    tempUUID = gattCharacteristic.getUuid().toString();
                    Log.i(TAG,"Characteristic: " + tempUUID);
                    if(tempUUID.equals(HMSoftChar)){
                        Log.i(TAG,"Found Characteristics, Reading....");
                        //Toast.makeText(getApplicationContext(),"Found data!",Toast.LENGTH_SHORT).show();
                        foundChar = true;
                        Log.i(TAG,"Obtained characteristic");
                        bluetoothGattCharacteristicHM_SOFT = gattService.getCharacteristic(UUID_HM_SOFT);
                        //Add to application file
                        BluetoothApp.getApplication().setBluetoothGattCharacteristic(bluetoothGattCharacteristicHM_SOFT);
                        activateCharacteristic(gattCharacteristic);
                    }
                }
            }
        }
    }

    //Start reading the data from the board
    private void activateCharacteristic(BluetoothGattCharacteristic gattChar){
        final int charaProp = gattChar.getProperties();
        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
            // If there is an active notification on a characteristic, clear
            // it first so it doesn't update the data field on the user interface.
            if (mNotifyCharacteristic != null) {
                mBluetoothLeService.setCharacteristicNotification(
                        mNotifyCharacteristic, false);
                mNotifyCharacteristic = null;
            }
            mBluetoothLeService.readCharacteristic(gattChar);
        }
        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
            mNotifyCharacteristic = gattChar;
            mBluetoothLeService.setCharacteristicNotification(
                    gattChar, true);
        }
        //foundChar = true;
    }

    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(HMSoftAddress);
            //Used so it can later be accessed in another activity
            BluetoothApp.getApplication().setBluetoothLe(mBluetoothLeService);
            Log.i(TAG,"Connected to hmSoft!");
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };
}
