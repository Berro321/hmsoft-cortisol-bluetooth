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
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.GridLabelRenderer;
import com.jjoe64.graphview.Viewport;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

//TODO: Implement the Bluetooth code here
/*
 *TODO:
 *  Display an empty plot
 *  Add a splash screen
 *  read + display current
 */

public class MainActivity extends AppCompatActivity {
    private final String TAG = ".MainActivity";
    private final String recordTitle = "valIn, valOutUnfiltered, resistanceUnfiltered, valOutFiltered, resistanceFiltered, Frequency Applied, Expected\n";
    private final String recordFFTTitle = "resUnfiltered FFT, resFiltered FFT\n";
    private final Boolean DEBUG_MODE = false; //For testing various things

    //Information about the board
    //Change these if the device's address or service/characteristic UUIDs change
    public static String HMSoftAddress = "F0:C7:7F:94:CF:97";
    public static final String HMSoftServ = "0000ffe0-0000-1000-8000-00805f9b34fb"; //Should be same amongst HM-11 Modules
    public  static final String HMSoftChar = "0000ffe1-0000-1000-8000-00805f9b34fb";

    //Text View
    private TextView voltage_display;
    private TextView impedance_display;

    //Writing to board
    private boolean isRecording;
    private boolean flagFFTRecording; //Flag to indicate when the fft data is recording
    private boolean flagIgnoreTitle; //Flag for ignoring the title of the data when recording
    private File outFile;
    private File outFile_fft;
    private FileOutputStream outStream;

    //Needed for Bluetooth
    private int count; //Prevent from scanning forever
    private boolean foundChar; //Flag for finding BT Characteristic
    private BluetoothAdapter mBluetoothAdapter;
    private boolean mScanning; //Flag for scanning for devices
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

    //Graphview variables
    private LineGraphSeries<DataPoint> series;
    private LineGraphSeries<DataPoint> series_voltage;
    private GraphView graph;
    private GraphView voltage_graph;
    private Boolean isGraphing;

    //Debug mode specific
    //private FileInputStream graph_in;
    private FileOutputStream graph_raw_out;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        deviceList = new ArrayList<>();
        mHandler = new Handler();
        foundChar = false;
        isRecording = false;
        flagIgnoreTitle = false;

        //Bluetooth and writing feature checking
        checkBTSupportedAvailable();
        checkBTAndWritePermissions();

        //Setup the address reading
        handleSettingsRead();

        //Setup graphview
        graph = findViewById(R.id.impedance_graph);
        series = new LineGraphSeries<DataPoint>(); //Create a new series of points to graph
        series.setColor(Color.DKGRAY);
        graph.addSeries(series);
        graph.setTitle("Impedance(Ω)");
        graph.getGridLabelRenderer().setHorizontalAxisTitle("Time (s)");
        //graph.getGridLabelRenderer().setVerticalAxisTitle("Impedance (Ohm)");
        graph.getGridLabelRenderer().setVerticalLabelsAlign(Paint.Align.RIGHT);
        graph.getGridLabelRenderer().setLabelHorizontalHeight(50);
        graph.getGridLabelRenderer().setLabelVerticalWidth(120);
        graph.getGridLabelRenderer().setLabelsSpace(3);
        graph.getGridLabelRenderer().setNumVerticalLabels(3);
        Viewport viewport = graph.getViewport();
        viewport.setScrollable(true);
        viewport.setYAxisBoundsManual(true);
        viewport.setMinY(0);
        viewport.setMaxY(10000);
        viewport.setXAxisBoundsManual(true);
        viewport.setMaxX(10);
        viewport.setMinX(0);

        voltage_graph = findViewById(R.id.output_voltage_graph);
        series_voltage = new LineGraphSeries<DataPoint>(); //Create a new series of points to graph
        series_voltage.setColor(Color.DKGRAY);
        voltage_graph.addSeries(series_voltage);
        voltage_graph.setTitle("Voltage (V)");
        voltage_graph.getGridLabelRenderer().setHorizontalAxisTitle("Time (s)");
        voltage_graph.getGridLabelRenderer().setVerticalLabelsAlign(Paint.Align.RIGHT);
        //voltage_graph.getGridLabelRenderer().setVerticalAxisTitle("Voltage (V)");
        voltage_graph.getGridLabelRenderer().setLabelHorizontalHeight(50);
        voltage_graph.getGridLabelRenderer().setLabelVerticalWidth(120);
        voltage_graph.getGridLabelRenderer().setLabelsSpace(3);
        voltage_graph.getGridLabelRenderer().setNumVerticalLabels(4);
        Viewport viewport1 = voltage_graph.getViewport();
        viewport1.setScrollable(true);
        viewport1.setYAxisBoundsManual(true);
        viewport1.setMinY(0);
        viewport1.setMaxY(30);
        viewport1.setXAxisBoundsManual(true);
        viewport1.setMaxX(10);
        viewport1.setMinX(0);

        isGraphing = false;

        //Setting up the text views
        voltage_display = findViewById(R.id.display_voltage);
        impedance_display = findViewById(R.id.display_impedance);

        //Debug mode
        if(DEBUG_MODE){
            try {
                //graph_in = new FileInputStream(new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/HMSOFTCORTISOL/testRead.txt"));
                graph_raw_out = new FileOutputStream(new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/HMSOFTCORTISOL/raw.txt"));
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
        }
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

        //Start scanning for devices if no device is connected
        if(!foundChar && BluetoothApp.getApplication().getService()==null)
            scanLeDevice(true);
        else{ //Otherwise retrive bluetooth service from BluetoothApp
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

    public void handleSettingsRead(){
        if(!isExternalStorageWritable())
            return; //Can't read
        File readDir = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/HMSOFTCORTISOL/SETTINGS");
        Log.i(TAG,readDir.getAbsolutePath());
        if(!readDir.exists()) {
            //Create parent first
            File parent = new File (Environment.getExternalStorageDirectory().getAbsolutePath() + "/HMSOFTCORTISOL");
            if (parent.mkdir() && readDir.mkdir())
                Log.i(TAG, "Created a new directory for app");
            else
                Log.i(TAG, "Unable to make new directory for app");
        }
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
                    char charChar = (char)charNum;
                    if((charChar != ' ' && charChar != '\n' && charChar != '\t') && record)
                        address += (char)charNum;
                    if((char)charNum == ':')
                        record = true;
                    if((char)charNum == '\n')
                        record = false;
                }
                HMSoftAddress = address;
                Log.i(TAG, "HMSOFT recorded address:" +  HMSoftAddress + "|\n");
                is.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    //Check bluetooth functionality, turns off app if not
    public void checkBTSupportedAvailable(){
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

    String temp_Inc;
    double time_since_start;
    int temp_x = 0;
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
                if(DEBUG_MODE) {
                    try {
                        graph_raw_out.write(returnedVal.getBytes());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                //Log.i(TAG,returnedVal);
                if(!isRecording) { //Create the output file and file stream
                    //Wait until we hit a normal recording
                    if(returnedVal.indexOf('?') == -1)
                        return;
                    returnedVal = returnedVal.split("\\?",2)[1];
                    File[] pair = createFileFFTPair();
                    outFile = pair[0];
                    outFile_fft = pair[1];
                    isRecording = true;
                    flagIgnoreTitle = true;
                    try {
                        outStream = new FileOutputStream(outFile);
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    }
                    time_since_start = SystemClock.elapsedRealtime(); //Starting time
                }

                //Activate FFT recording, to record fft in a separate file
                if(!flagFFTRecording && returnedVal.indexOf('!') != -1){
                    //get the last part before ! and append to file
                    String[] strs = returnedVal.split("!",2);
                    try {
                        temp_Inc += strs[0];
                        //String temp_message = "Switching to FFT..\n";
                        outStream.write(strs[0].getBytes());
                        //outStream.write(temp_message.getBytes());
                        outStream.close();
                        //Now start writing on the fft_file
                        outStream = new FileOutputStream(outFile_fft,true);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    returnedVal = strs[1];
                    flagFFTRecording = true;
                    flagIgnoreTitle = true;
                }

                //Deactivate FFt recording, and go back to the previous file that was writing
                if(flagFFTRecording && returnedVal.indexOf('?') != -1){
                    //get the last part before ? and append to file
                    String[] strs = returnedVal.split("\\?",2);
                    try {
                        outStream.write(strs[0].getBytes());
                        outStream.close();
                        //Now start writing on the fft_file
                        outStream = new FileOutputStream(outFile,true);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    returnedVal = strs[1];
                    flagFFTRecording = false;
                    flagIgnoreTitle = true;
                }

                //Ignore the rest of the line
                if(flagIgnoreTitle && returnedVal.indexOf('\n') != -1){
                    //Split up into two
                    returnedVal = returnedVal.split("\\n",2)[1];
                    flagIgnoreTitle = false;
                }


                //Write to the output file
                if(flagIgnoreTitle)
                    return;

                //Save a temporary string of the current line
                if(!flagFFTRecording && returnedVal.indexOf('\n') != -1){
                    String[] st = returnedVal.split("\\n",2);
                    temp_Inc += st[0];
                    String[] vals = temp_Inc.split(",",7);
                    /*
                        In Order of this if correct:
                        Voltage input, voltage out unfiltered, resistance unfiltered, voltage out
                        filtered, resistance filtered, frequency applied, expected (should be empty)
                    */
                    //TODO: Handle cases where the string is not long enough
                    if(vals.length >= 7) {
                        Log.i(TAG, "The string is : " + temp_Inc);
                        double v;
                        try {
                            v = Double.parseDouble(vals[4]); //parse the  resistance UF to a double
                            impedance_display.setText(vals[4]); //View it in the graph
                            double time = (SystemClock.elapsedRealtime() - time_since_start) / 1000;
                            series.appendData(new DataPoint(time, v), true, 200);

                            v = Double.parseDouble(vals[1]);
                            voltage_display.setText(vals[1]);
                            series_voltage.appendData(new DataPoint(time, v), true, 200);
                        }
                        catch(RuntimeException e){
                            e.printStackTrace();
                        }
                    }
                    temp_Inc = st[1];
                }
                else if(!flagFFTRecording)
                    temp_Inc += returnedVal;

                try {
                    outStream.write(returnedVal.getBytes());
                } catch (IOException e) {
                    e.printStackTrace();
                }

                //TODO: Plot the points received on the graph (Double check)
            }
        }
    };

    //TODO: Separate into separate folders (FFT and Normal)
    //Creates two files that with the same name except the 2nd element has _fft appended to the end
    private File[] createFileFFTPair(){
        File[] pair = new File[2];
        pair[0] = createFile();
        pair[1] = createFile(pair[0].getName().substring(0,pair[0].getName().indexOf('.')) + "_fft");
        return pair;
    }
    private File createFile(){
        return createFile("","");
    }
    private File createFile(String name){
        return createFile(name,"");
    }
    private File createFile(String name, String relativePath){
        File dir = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/HMSOFTCORTISOL" + relativePath);
        if(!dir.exists()) //Create directory if it does not exist
            dir.mkdir();
        String title = BluetoothApp.getDateString() + "_" + BluetoothApp.getTimeString();
        if(!name.equals(""))
            title = name;
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

    //Counts the number of occurrences of a character
    private int countChar(String str, char c){
        int total = str.length(), count_total = 0;
        for(int i = 0; i < total; i++)
            if(str.charAt(i) == c)
                count_total++;
        return count_total;
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

    //DEBUG FUNCTIONS
    public void Debug_Graph_Vals(View ve) {
        if(!DEBUG_MODE)
            return;
//        int c;
//        String s = "";
//        try {
//            while ((c = graph_in.read()) != -1) {
//                s += (char) c;
//                if ((char) c == '\n'){
//                    if(s.indexOf('v') != -1){
//                        return;
//                    }
//                    break;
//                }
//            }
//            Log.i(TAG, "current string extracted = : " + s);
//            String[] vals = s.split(",",7);
//                    /*
//                        In Order of this if correct:
//                        Voltage input, voltage out unfiltered, resistance unfiltered, voltage out
//                        filtered, resistance filtered, frequency applied, expected (should be empty)
//                    */
//            double v = Double.parseDouble(vals[4]); //parse the  resistance UF to a double
//            impedance_display.setText(vals[4]); //View it in the graph
//            series.appendData(new DataPoint(temp_x,v),true,200);
//
//            v = Double.parseDouble(vals[1]);
//            voltage_display.setText(vals[1]);
//            series_voltage.appendData(new DataPoint(temp_x++,v),true,200);
//        } catch(IOException e){
//            e.printStackTrace();
//        }

    }
}
