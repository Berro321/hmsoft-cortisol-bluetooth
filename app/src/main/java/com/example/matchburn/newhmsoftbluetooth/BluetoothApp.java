package com.example.matchburn.newhmsoftbluetooth;

import android.Manifest;
import android.app.Application;
import android.bluetooth.BluetoothGattCharacteristic;
import android.content.Context;
import android.os.Build;
import android.support.v4.app.ActivityCompat;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

/**
 * Created by Matchburn321
 * Contains the bluetoothLeService that communicates with the board + other functions
 */

public class BluetoothApp extends Application {
    //Singleton
    private static BluetoothApp sInstance;

    public static BluetoothApp getApplication(){
        return sInstance;
    }

    BluetoothLeService mBLE = null;
    BluetoothGattCharacteristic mBGC = null;

    public void onCreate(){
        super.onCreate();
        sInstance = this;
    }

    public void setBluetoothLe(BluetoothLeService in){
        mBLE = in;
    }
    public void setBluetoothGattCharacteristic(BluetoothGattCharacteristic in){mBGC=in;}
    public BluetoothLeService getService(){
        return mBLE;
    }
    public BluetoothGattCharacteristic getGattCharacteristic(){return mBGC;}

    //Other functions

    //returns a string containing today's date
    public static String getDateString(){
        Date time = Calendar.getInstance().getTime();
        SimpleDateFormat outputFmt = new SimpleDateFormat("MM-dd-yyyy");
        return outputFmt.format(time);
    }

    //returns a string containing a timestamp in format (HH-mm-ss)
    public static String getTimeString(){
        Date time = Calendar.getInstance().getTime();
        SimpleDateFormat outputFmt = new SimpleDateFormat("HH-mm-ss");
        return outputFmt.format(time);
    }

    public static String getTimeStringWithColons() {
        Date time = Calendar.getInstance().getTime();
        SimpleDateFormat outputFmt = new SimpleDateFormat("HH:mm:ss");
        return outputFmt.format(time);
    }
}
