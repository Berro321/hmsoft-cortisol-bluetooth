package com.example.matchburn.newhmsoftbluetooth;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.View;

/**
 * Original code by Francisco Ramirez
 */

public class SplashScreen extends AppCompatActivity{

    View view;

    private static final int Splash_Time_Out = 1000; //OG: 5000

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash_screen);

        view = this.getWindow().getDecorView();
        view.setBackgroundResource(R.color.White);

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                Intent homeIntent = new Intent(SplashScreen.this, MainActivity.class);
                startActivity(homeIntent);
                finish();
            }
        }, Splash_Time_Out);
    }
}
