package com.fuelize.code;

import static android.view.View.VISIBLE;
import static com.fuelize.code.MyFunc.DecToHex;
import static com.fuelize.code.MyFunc.HexToByteArr;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fuelize.bean.AssistBean;
import com.fuelize.db.DatabaseHelper;
import com.fuelize.model.OrderData;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.text.DecimalFormat;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class EastmanDecantActivity extends AppCompatActivity {

    static int PollingTimer = 2000;

    Handler polhandler, tvHandler, loghandler, rfidhandler;
    Runnable polrunnable, logrunnable, rfidrunnable;

    Context mContext;

    TextView tvTagID, txtCRMNumber, tvDUStatus, tvRFIDStatus, tvFillingStatus, txtOrderQuantity,  tvOutput, tvLogs;

    Button ButtonClear, ButtonSendCOMA, btnStatusMsg,  resume, btnResume, startDeliveryButton, finishOrderButton;

    SerialControl ComPortDISP, ComPortDISPParity, ComA, ComRFID;
    Uri uriData;
    Boolean isOpenA;
    Boolean isRun = true;
    DispQueueThread DispQueue;
    AssistBean AssistData;
    long iRecBytes1 = 0;
    long iRecLines1 = 0;
    ScrollView scrollView;
    DecimalFormat df = new DecimalFormat("#.##");

    StringBuilder sMsg;


    String sFilename = "ComAssistant";
    String sLinename = "AssistData";

    int ProcessStage = 0;
    int DuConnectCounter = 0;
    int RFIDConnectCounter = 0;
    int RFIDReconnectCounter = 0;
    boolean DispensingCompleted = false;
    boolean DispensingStarted = false;
    boolean DLDataReceived = false;
    @SuppressWarnings("unused")
    int DeliveryCloseCounter = 0;
    int reasonTextPos = 0;
    int PrintCounter = 0;
    int presetSetCounter = 0;
    int DecantCounter = 0;

    int presetQty;

    String Otp = "00000";
    String OtpCode = null;
    String DU_ResponseStr = "";
    String Total_Log = "";
    // TCP-IP connection data for RFID Reader


    public static String crmNumber = "NA";
    public static String order_qty = "NA";
    public static String unitPrice = "NA";
    ImageView imgFuelState, imgBack, diagnoseToolBtn;

    @SuppressWarnings("unused")
    private Boolean havepause;
    @SuppressWarnings("unused")
    private Boolean PolStatus = true;
    private Boolean DUNotRespondDialog = false;
    private Boolean RFIDNotRespondDialog = false;
    private Boolean DispensingCmdIssued = false;
    private final Handler UIHandler = new Handler();
    private Boolean IsFirstLoad = true;
    private final Boolean IsTagReading = false;
    private Boolean IsDispensingDone = false;
    private Boolean IsOtpSet = false;
    @SuppressWarnings("unused")
    private final Boolean VolumeFilledStart = false;   // Check Fueling Start from 0
    @SuppressWarnings("FieldCanBeLocal")
    private Boolean IsFuelingStarted = false;    // Check Fueling meter increases
    //  private Boolean IsFuelingCompleted = false;  // Check Fueling Completed
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private Boolean IsDispenserOnline = false;
    private final Boolean IsRFIDStarted = false;
    private Boolean IsDecantationStarted = false;
    private Boolean IsFirstStatusReceived = false;
    private Boolean DecantionGoing = false;

    public static final String OTP = "otp";
    public static final String ORDER_QTY = "order_qty";
    public static final String RFID_TAG = "rfid_tag";
    public static final String ORDER_NO = "order_no";
    String DL_OTP = "1234";
    String DL_ORDER_QTY = "5";
    String DL_RFID_TAG = "";
    String DL_ORDER_NO = "1234";

    DatabaseHelper helper;
    String RFID_ResponseStr = "";
    boolean IsRFID_ONLINE = false;
    boolean IsRFID_TAG_READ_SUCCESSFUL= false;
    String RFID_Tag_Read = "";
    float volumeFloat = 0.0f;
    float volumeFloatFinal = 0.0f;
    float StartTotalizer = 0;
    float EndTotalizer = 0;
    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_eastman_decant);
        tvHandler = new Handler();
        helper = new DatabaseHelper(EastmanDecantActivity.this);
        setControls();
        deepLink();
        ComPortDISP = new SerialControl();
        ComRFID = new SerialControl();
        DispQueue = new DispQueueThread();
        DispQueue.start();
        sMsg = new StringBuilder();
        LoopPolling(PollingTimer);
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @SuppressLint("SuspiciousIndentation")
    @Override
    protected void onResume() {

        super.onResume();
        if (DispQueue != null) return;
        isRun = true;
        isOpenA = true;
        DispQueue = new DispQueueThread();
        DispQueue.setName("DispQueue");
        DispQueue.start();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        CloseComPort(ComA);
    }

    @SuppressLint("SetTextI18n")
    private void setControls() {

        btnResume = findViewById(R.id.btn_resume);

        txtCRMNumber = findViewById(R.id.tv_crm_number);
        tvDUStatus = findViewById(R.id.tv_du_conn_status);
        tvLogs = findViewById(R.id.tvLogs);
        tvLogs.setMovementMethod(new ScrollingMovementMethod());

        tvRFIDStatus = findViewById(R.id.tv_rfid_conn_status);
//        tvNozzleState = findViewById(R.id.tv_nozzle_state);
        txtOrderQuantity = findViewById(R.id.tv_order_quantity);

        // imgFuelState = findViewById(R.id.img_nozzle_state);

        tvFillingStatus = findViewById(R.id.tv_mdu_filling_status);
        finishOrderButton = findViewById(R.id.btn_finish_order);

        scrollView = findViewById(R.id.scrollView);
        tvOutput = findViewById(R.id.tv_output);
        ButtonSendCOMA = (Button) findViewById(R.id.btn_set_preset);
        tvOutput = (TextView) findViewById(R.id.tv_output);
        scrollView = (ScrollView) findViewById(R.id.scrollView);
        btnStatusMsg = (Button) findViewById(R.id.btn_status_message);

        finishOrderButton = findViewById(R.id.btn_finish_order);
        finishOrderButton.setText("Close");
        finishOrderButton.setVisibility(View.INVISIBLE);

        finishOrderButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!DispensingCompleted) {
                    AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(EastmanDecantActivity.this);
                    alertDialogBuilder.setCancelable(false);
                    alertDialogBuilder.setMessage("Dispensing not completed !!!\nDo you really want Finish or Pause delivery ?");
                    alertDialogBuilder.setPositiveButton("Finish Delivery", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            PolStatus = false;
                            DU_ResponseStr = "";
                            stopFueling();
                            Handler handler = new Handler();
                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    DU_ResponseStr = "";
                                    readTOT();
                                }
                            }, 500);
                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    ProcessStage = 8;
                                }
                            }, 500);

                            handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    PolStatus = true;
                                }
                            }, 500);

                            dialog.dismiss();
                        }
                    });

                    alertDialogBuilder.setNegativeButton("Pause Delivery", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            ProcessStage = 9;
                            startDeliveryButton.setText("Resume Delivery");
                            startDeliveryButton.setVisibility(VISIBLE);
                            finishOrderButton.setVisibility(View.INVISIBLE);
                        }
                    });

                    alertDialogBuilder.setNeutralButton("Cancel", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                            return;
                        }
                    });
                    AlertDialog alertDialog = alertDialogBuilder.create();
                    alertDialog.setTitle("Close or Pause dispensing confirmation");
                    if (!alertDialog.isShowing()) {
                        alertDialog.show();
                    }
                }
                else {
                    CloseComPort(ComPortDISP);
                    if (DLDataReceived) {
                        Uri fuelizeApp = Uri.parse("csifuel://driver/delivery_screen?order_quantity=" + df.format(volumeFloat) + "&start_totalizer=" + df.format(StartTotalizer) + "&end_totalizer=" + df.format(EndTotalizer));
                        Intent fuelizeIntent = new Intent(Intent.ACTION_VIEW, fuelizeApp);
                        try {
                            startActivity(fuelizeIntent);
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                finishAndRemoveTask();
                            }
                        } catch (Exception e) {
                        }
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            finishAndRemoveTask();
                        }
                    }
                }
            }
        });

        try {
            CheckComA();
        } catch (Exception e) {
            return;
        }


        startDeliveryButton = findViewById(R.id.btn_start_delivery);
        startDeliveryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!startDeliveryButton.getText().equals("Resume Delivery"))
                {
                    AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(EastmanDecantActivity.this);
                    alertDialogBuilder.setCancelable(false);
                    alertDialogBuilder.setMessage("Do you want to Start Delivery of Order no " + crmNumber + " for Quantity " + order_qty + " Litres ?");
                    alertDialogBuilder.setPositiveButton("YES", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            IsDecantationStarted = true;
                            PolStatus = true;
                            startDeliveryButton.setVisibility(View.INVISIBLE);
                            dialog.dismiss();
                        }
                    });
                    alertDialogBuilder.setNegativeButton("NO", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });
                    AlertDialog alertDialog = alertDialogBuilder.create();
                    alertDialog.setTitle("Start Delivery Confirmation");
                    if (!alertDialog.isShowing()) {
                        alertDialog.show();
                    }
                }
                else {
                    ProcessStage = 10;
                    startDeliveryButton.setVisibility(View.INVISIBLE);
                    finishOrderButton.setVisibility(VISIBLE);

                }
            }
        });
//        IsDecantationStarted = true;
//        PolStatus = true;
    }



    @SuppressLint("SetTextI18n")
    public void deepLink() {
        Intent intent = getIntent();
        if (intent != null) {
            uriData = intent.getData();
            if (uriData != null) {
                DL_OTP = uriData.getQueryParameter(OTP);
                DL_RFID_TAG = uriData.getQueryParameter(RFID_TAG);
                DL_ORDER_QTY = uriData.getQueryParameter(ORDER_QTY);
                DL_ORDER_NO = uriData.getQueryParameter(ORDER_NO);
                DLDataReceived = true;

                tvLogs.append("\nDL data received");

                if (helper.checkOrderExists(DL_ORDER_NO))
                {
                    tvLogs.append("\nOrder exists in database");
                    android.app.AlertDialog.Builder alertDialogBuilder = new android.app.AlertDialog.Builder(EastmanDecantActivity.this);
                    alertDialogBuilder.setCancelable(false);

                    alertDialogBuilder.setMessage("Order " + uriData.getQueryParameter(ORDER_NO) + " with Order Qty " + uriData.getQueryParameter(ORDER_QTY) + "  has been already Decanted ! Kindly Proceed for next Order.");
                    // ===================== FIX A2 (START) =====================
                    // BEFORE: the dialog button only closed the app and never sent
                    //         the result back, so an order whose result failed to
                    //         reach the Driver App stayed stuck on "Get Started":
                    //   alertDialogBuilder.setPositiveButton("Close", new DialogInterface.OnClickListener() {
                    //       @Override
                    //       public void onClick(DialogInterface dialog, int which) {
                    //           dialog.dismiss();
                    //           if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    //               finishAndRemoveTask();
                    //           }
                    //       }
                    //   });
                    // AFTER: re-send the stored result to the Driver App (idempotent
                    //        re-sync; pump is NOT re-engaged) using the same csifuel://
                    //        callback as the normal "Finish" button, then close.
                    alertDialogBuilder.setPositiveButton("Re-send to App", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                            try {
                                OrderData o = helper.getOrderData(DL_ORDER_NO);
                                Uri fuelizeApp = Uri.parse("csifuel://driver/delivery_screen?order_quantity=" + o.getDecantQty()
                                        + "&start_totalizer=" + o.getStartTotalizer()
                                        + "&end_totalizer=" + o.getEndTotalizer());
                                startActivity(new Intent(Intent.ACTION_VIEW, fuelizeApp));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                finishAndRemoveTask();
                            }
                        }
                    });
                    // ===================== FIX A2 (END) =======================
                    android.app.AlertDialog alertDialog = alertDialogBuilder.create();
                    alertDialog.setTitle("Order already Decanted");
                    if (!alertDialog.isShowing()) {
                        alertDialog.show();
                    }
                }
            }
            else
            {
                PolStatus = false;
                finishOrderButton.setText("DL Data N/A - Close");
                finishOrderButton.setBackgroundColor(Color.RED);
                finishOrderButton.setVisibility(VISIBLE);
                startDeliveryButton.setVisibility(View.INVISIBLE);
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private void getOrderFromDatabase(String order_no) {
        try {
            DatabaseHelper databaseHelper = new DatabaseHelper(EastmanDecantActivity.this);
            OrderData orderData = databaseHelper.getOrderData(order_no);
            DL_ORDER_NO = orderData.getOrderNo();
            DL_OTP = orderData.getOtp();
            DL_ORDER_QTY = orderData.getorderQty();
            DL_RFID_TAG = orderData.getRfidTag();

            txtCRMNumber.setText(crmNumber);
            txtOrderQuantity.setText(DL_ORDER_QTY + " Litres");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void LoopPolling(final int timer) {
        polhandler = new Handler();
        polrunnable = new Runnable() {
            @SuppressLint("SetTextI18n")
            @Override
            public void run() {
                try {
                    if (IsDecantationStarted) {
                        if (PolStatus) {

                            Log.e("Polling Loop", "Inside Loop Polling...");
                            final Handler handler = new Handler();

                            if (ProcessStage == 0) {
                                //tvTagID.setText("Detecting...");
                                txtCRMNumber.setText(DL_ORDER_NO);
                                txtOrderQuantity.setText(DL_ORDER_QTY + " Liters");
                                tvDUStatus.setText("Detecting...");
                                tvRFIDStatus.setText("Detecting...");
                                tvHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        tvFillingStatus.setText("0.00");
                                    }
                                });

                                tvOutput.setText("0.00");
                                DU_ResponseStr = "";
                                ProcessStage = 1;
                                DuConnectCounter++;
                                if (DuConnectCounter > 5) {
                                    tvDUStatus.setText("OFFLINE");
                                    tvDUStatus.setTextColor(Color.RED);
                                    if (DuConnectCounter % 40 == 0 && !DUNotRespondDialog) {
                                        finishOrderButton.setText("Close");
                                        finishOrderButton.setVisibility(VISIBLE);
                                        PolStatus = false;
                                        //DUNotRespondingCloseAlert();
                                    }
                                }
                                else
                                    status();
                            }
                            else if ((ProcessStage == 1)) {
                                if (DU_ResponseStr.length() >= 9) {
                                    if (DU_ResponseStr.substring(0, 2).equals("0A")) {
                                        tvDUStatus.setText("ONLINE");
                                        ProcessStage = 2; // implies DU is ONLINE and ready for PRESET

                                        // Read RFID TAG or GET OTP
                                        if (RFIDConnectCounter == 0) {
                                            tvRFIDStatus.setText("OFFLINE");
                                            tvRFIDStatus.setTextColor(Color.RED);

                                            if (IsOtpSet) {
                                                tvRFIDStatus.setText("OTP successfully verified for delivery, No RFID present");
                                                tvRFIDStatus.setTextColor(Color.GREEN);
                                            }
                                            if (!RFIDNotRespondDialog && !IsOtpSet) {
                                                RFIDNotRespondingCloseAlert();
                                            }
                                        }
                                    }
                                    else if (DuConnectCounter < 3) {
                                        DuConnectCounter++;
                                        ProcessStage = 0;
                                        DU_ResponseStr = "";
                                        status();
                                    }
                                    else {
                                        tvDUStatus.setText("OFFLINE");
                                        tvDUStatus.setTextColor(Color.RED);
                                        tvFillingStatus.setTextColor(Color.RED);
                                        tvHandler.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                tvFillingStatus.setText("Failed communication with DU, aborting dispensing");
                                            }
                                        });
                                        finishOrderButton.setText("Close");
                                        finishOrderButton.setVisibility(VISIBLE);
                                        PolStatus = false;
                                    }
                                }
                                else {
                                    DU_ResponseStr = "";
                                    status();
                                }
                            }
                            else if ((ProcessStage == 2) && (IsOtpSet)) {
                                DU_ResponseStr = "";
                                ProcessStage = 3; //implies Read Totalizer command issued
                                readTOT();
                            }
                            else if ((ProcessStage == 3))
                            {
                                //ShowMessage("Inside ProcessStage 3");
                                // ===================== FIX P1/P2 (START) =====================
                                // BEFORE:
                                //   if (DU_ResponseStr.length() > 20) {
                                //       String strStartTotalizer = DU_ResponseStr.substring(6,23)...
                                //       ... Long.valueOf(strStartTotalizer) ...
                                //       ProcessStage = 4;
                                //   } else { DU_ResponseStr = ""; readTOT(); }
                                // Problem: guard ">20" let lengths 21-22 reach substring(6,23)
                                //   (needs >=23) -> StringIndexOutOfBounds; and Long.valueOf ran
                                //   unguarded -> NumberFormatException on a stale/garbled response.
                                //   Both were swallowed by the outer loop try/catch, which then
                                //   skipped re-scheduling the poll -> dispensing froze.
                                // AFTER: require full length (>=23) and parse inside try/catch;
                                //   any malformed/partial response just re-reads (self-heal).
                                if (DU_ResponseStr.length() >= 23)
                                {
                                    try {
                                        String strStartTotalizer = DU_ResponseStr.substring(6,23).replace(" ", "");
                                        strStartTotalizer = strStartTotalizer.substring(10) + strStartTotalizer.substring(8,10) + strStartTotalizer.substring(6,8) + strStartTotalizer.substring(4,6) + strStartTotalizer.substring(2,4) + strStartTotalizer.substring(0,2);
                                        StartTotalizer = ((float) Long.valueOf(strStartTotalizer) / 100);
                                        ProcessStage = 4; //implies start totalizer read and ready for preset
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                        DU_ResponseStr = "";
                                        readTOT();
                                    }
                                }
                                else {
                                    DU_ResponseStr = "";
                                    readTOT();
                                }
                                // ===================== FIX P1/P2 (END) =======================
                            }
                            else if (ProcessStage == 4) {
                                DU_ResponseStr = "";
                                ProcessStage = 5;  // implies PRESET command sent
                                try {
                                    int qty = Integer.parseInt(DL_ORDER_QTY);
                                    presetSet(qty);
                                    finishOrderButton.setText("Hard Stop");
                                    finishOrderButton.setBackgroundColor(Color.DKGRAY);
                                    finishOrderButton.setVisibility(VISIBLE);
                                } catch (Exception e) {
                                    ProcessStage = 9; // Implies dispensing ABORTED
                                    tvFillingStatus.setTextColor(Color.RED);
                                    tvHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            tvFillingStatus.setText("Invalid order qty, aborting dispensing...");
                                        }
                                    });

                                    finishOrderButton.setVisibility(VISIBLE);
                                    PolStatus = false;
                                    finishOrderButton.setText("Abort");
                                    finishOrderButton.setBackgroundColor(Color.DKGRAY);
                                    finishOrderButton.setVisibility(VISIBLE);
                                    e.printStackTrace();
                                }
                            }
                            else if ((ProcessStage == 5)) {
                                if ((DU_ResponseStr.equals("03 1B E2 ")) || (DU_ResponseStr.equals("03 1F DE ")) || (DU_ResponseStr.equals("03 0F EE "))) {
                                    ProcessStage = 6;
                                }
                                else
                                {
                                    DU_ResponseStr = "";
                                    int qty = Integer.parseInt(DL_ORDER_QTY);
                                    presetSet(qty);
                                }
                            }
                            if (ProcessStage == 6) {
                                DU_ResponseStr = "";
                                ProcessStage = 7; // implies command to read LIVE fuelling data sent
                                GetFuellingData();
                            }
                            else if ((ProcessStage == 7)) {
                                if (DU_ResponseStr.length() >= 36) {
                                    if ((DU_ResponseStr.substring(0, 5).equals("0A 1F")) || (DU_ResponseStr.substring(0, 5).equals("0D 1F")) || (DU_ResponseStr.substring(0, 5).equals("0D 0F")) || (DU_ResponseStr.substring(0, 5).equals("0A 0F")) || (DU_ResponseStr.substring(0, 5).equals("0D 1B"))) {
                                        // implies dispensing is still going on
                                        // read current volume of diesel dispenses
                                        // Setting state to dispensing started
                                        tvLogs.setText("LIVE DATA received: " + DU_ResponseStr);
                                        DispensingStarted = true;
                                        finishOrderButton.setVisibility(VISIBLE);
                                        String volume = "";
                                        int volumeInt = 0;
                                        volume = DU_ResponseStr.substring(18, 29).replace(" ", "");
                                        volumeInt = MyFunc.HexToInt(volume);
                                        volumeFloat = ((float) volumeInt / 100);
                                        tvFillingStatus.setText(df.format(volumeFloat));

                                        DU_ResponseStr = "";
                                        GetFuellingData(); // issue cmd again to get LIVE data
                                    }
                                    // Added logic to detect dispensing start and accordingly read fuelling data values in "NOT DISPENSING" STATE BELOW
                                    else if (((DU_ResponseStr.substring(0, 5).equals("0A 17")) || (DU_ResponseStr.substring(0, 5).equals("0D 17")) || (DU_ResponseStr.substring(0, 5).equals("0A 07")) || (DU_ResponseStr.substring(0, 5).equals("0D 07")) || (DU_ResponseStr.substring(0, 5).equals("0D 1B")) || (DU_ResponseStr.substring(0, 5).equals("03 1F")) || (DU_ResponseStr.substring(0, 5).equals("03 17"))) && (DispensingStarted)) {
                                        tvLogs.setText("LIVE DATA received: " + DU_ResponseStr);
                                        String volume = "";
                                        int volumeInt = 0;
                                        volume = DU_ResponseStr.substring(18, 29).replace(" ", "");
                                        volumeInt = MyFunc.HexToInt(volume);
                                        volumeFloat = ((float) volumeInt / 100);
                                        tvFillingStatus.setText(df.format(volumeFloat));

                                        DU_ResponseStr = "";
                                        ProcessStage = 8; // implies dispensing completed and now read END Totalizer
                                        EndTotalizer = 0;
                                        DU_ResponseStr = "";
                                        readTOT();
                                    }
                                    else {
                                        DU_ResponseStr = "";
                                        GetFuellingData(); // issue cmd again to get LIVE data
                                    }
                                }
                                else {
                                    DU_ResponseStr = "";
                                    GetFuellingData();
                                }
                            }
                            else if ((ProcessStage == 8))
                            {
                                // ===================== FIX P1/P2 (START) =====================
                                // BEFORE:
                                //   if (DU_ResponseStr.length() > 20) {
                                //       String strEndTotalizer = DU_ResponseStr.substring(6, 23)...
                                //       ... Long.parseLong(strEndTotalizer) ...
                                //       volumeFloatFinal = EndTotalizer-StartTotalizer;
                                //       ... (save order + mark COMPLETED) ...
                                //   } else { DU_ResponseStr = ""; readTOT(); }
                                // Problem: on the manual partial-stop path, readTOT() and
                                //   ProcessStage=8 are scheduled on the SAME 500ms timer (race), so
                                //   Stage 8 often parsed a short/stale response. Guard ">20" let
                                //   lengths 21-22 reach substring(6,23) (needs >=23) and Long.parseLong
                                //   ran unguarded -> StringIndexOutOfBounds / NumberFormatException.
                                //   The outer loop try/catch swallowed it and skipped re-scheduling
                                //   the poll -> MDU froze / errored after a partial dispense.
                                // AFTER: require full length (>=23) and parse in try/catch; only
                                //   save/complete when parsing succeeded. If not, the existing
                                //   else-branch below re-reads the totalizer (self-heal).
                                boolean endTotParsedOk = false;
                                if (DU_ResponseStr.length() >= 23) {
                                    try {
                                        String strEndTotalizer = DU_ResponseStr.substring(6, 23).replace(" ", "");
                                        strEndTotalizer = strEndTotalizer.substring(10) + strEndTotalizer.substring(8, 10) + strEndTotalizer.substring(6, 8) + strEndTotalizer.substring(4, 6) + strEndTotalizer.substring(2, 4) + strEndTotalizer.substring(0, 2);
                                        EndTotalizer = ((float) Long.parseLong(strEndTotalizer) / 100);
                                        endTotParsedOk = true;
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                                if (endTotParsedOk) {
                                    volumeFloatFinal = EndTotalizer-StartTotalizer;
                                    if (volumeFloatFinal >= 0)
                                    {
                                        volumeFloat = volumeFloatFinal;
                                        // FIX P1/P2: declare locally (original 'strEndTotalizer' is now scoped to the parse try-block)
                                        String strEndTotalizer = "Qty Dispensed: " + df.format(volumeFloatFinal) + " Ltrs | Totalizer Start/End: " + df.format(StartTotalizer) + " / " + df.format(EndTotalizer);
                                        String finalStrEndTotalizer = strEndTotalizer;
                                        tvFillingStatus.setText(finalStrEndTotalizer);
                                    }
                                    else
                                    {
                                        volumeFloatFinal = volumeFloat;
                                        if (StartTotalizer > EndTotalizer)
                                        {
                                            if ((EndTotalizer - (StartTotalizer/100)) == volumeFloat)
                                            {
                                                StartTotalizer = StartTotalizer/100;
                                            }
                                        }
                                        // FIX P1/P2: declare locally (original 'strEndTotalizer' is now scoped to the parse try-block)
                                        String strEndTotalizer = "Qty Dispensed: " + df.format(volumeFloatFinal);
                                        String finalStrEndTotalizer = strEndTotalizer;
                                        tvFillingStatus.setText(finalStrEndTotalizer);
                                    }

                                    // Save complete order details in database
                                    OrderData orderData = new OrderData();
                                    orderData.setOrderNo(DL_ORDER_NO);
                                    orderData.setOtp(DL_OTP);
                                    orderData.setRfidTag(DL_RFID_TAG);
                                    orderData.setorderQty(DL_ORDER_QTY);
                                    orderData.setStatus("COMPLETED");
                                    orderData.setStartTotalizer(df.format(StartTotalizer));
                                    orderData.setEndTotalizer(df.format(EndTotalizer));
                                    orderData.setDecantQty(df.format(volumeFloat));
                                    orderData.setSkipAuthentication("Y");
                                    helper.insertOrderData(orderData);

                                    DispensingStarted = false;
                                    DispensingCompleted = true;
                                    finishOrderButton.setText("Finish");
                                    finishOrderButton.setTextColor(Color.BLACK);
                                    finishOrderButton.setBackgroundColor(Color.CYAN);
                                    finishOrderButton.setVisibility(VISIBLE);
                                    PolStatus = false;
                                }
                                else {
                                    DU_ResponseStr = "";
                                    readTOT();
                                }
                            }
                            else if (ProcessStage == 9)
                            {
                                pauseTxn();
                                tvFillingStatus.setTextColor(Color.RED);
                                tvHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        tvFillingStatus.setText("Dispensing PAUSED");
                                    }
                                });

                                ProcessStage = 11; // nothing to do, just wait
                            }
                            else if (ProcessStage == 10)
                            {
                                resumeTxn();
                                tvLogs.append("\nResuming pump...");
                                ProcessStage = 6;
                            }
                            else if (ProcessStage == 11)
                            {
                                tvFillingStatus.setTextColor(Color.RED);
                                tvHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        tvFillingStatus.setText("Dispensing PAUSED");
                                    }
                                });

                            }
                        }
                    }
                    polhandler.postDelayed(this, timer);
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
        polhandler.post(polrunnable);
    }

//    private void getRfidStatus()
//    {
//        String cmd = "48 03 30 55 02 03 "; //NOTE RFN-ID FIXED AT 2
//        int bcc = XORofHexStringArray(cmd.split(" "));
//
//        cmd  = cmd + Integer.toHexString(bcc);
//        cmd = cmd.replace(" ", "");
//        //if (ComRFID.isOpen()) ComRFID.close();
//        ComRFID = new SerialControl();
//        ComRFID.setPort("/dev/ttyHSL2");
//        //ComRFID.setnPort(2);
//        ComRFID.setBaudRate(115200);
//        ComRFID.setParity(0);
//        char[] cmdArr = cmd.toCharArray();
//        OpenComPort(ComRFID);
//        RFID_ResponseStr = "";
//        sendPortData(ComRFID, cmdArr);
//
//        Handler handler = new Handler();
//        handler.postDelayed(new Runnable() {
//            @Override
//            public void run() {
//                if (RFID_ResponseStr.length() > 0) {
//                    IsRFID_ONLINE = true;
////                    String status = RFID_ResponseStr.substring(15,17);
////                    switch (status) {
////                        case "05":
////                            status = "Address set successfully";
////                            break;
////                        case "06":
////                            status = "No tag data or read tag failed";
////                            break;
////                        case "07":
////                            status = "Reading tag successfully";
////                            break;
////                        case "09":
////                            status = "Idle status";
////                            break;
////                        case "0A":
////                            status = "Battery low";
////                            break;
////                    }
//                }
//                else IsRFID_ONLINE= false;
//            }
//        }, 200);
//        //ComRFID.close();
//    }
//
//    private void readRfidTag()
//    {
//        String cmd = "48 03 50 55 02 03 "; //NOTE RFN-ID FIXED AT 2
//        int bcc = XORofHexStringArray(cmd.split(" "));
//        cmd  = cmd + Integer.toHexString(bcc);
//
//
//        if (ComRFID.isOpen()) ComRFID.close();
//        ComRFID = new SerialControl();
//        ComRFID.setPort("/dev/ttyHSL1");
//        //ComRFID.setnPort(2);
//        ComRFID.setBaudRate(115200);
//        ComRFID.setParity(0); // MARK PArity
//        OpenComPort(ComRFID);
//        RFID_ResponseStr = "";
//        sendPortData(ComRFID, cmd.toCharArray());
//        ComRFID.close();
//        Handler handler = new Handler();
//        handler.postDelayed(new Runnable() {
//            @Override
//            public void run() {
//                if (RFID_ResponseStr.length() > 0) {
//                    IsRFID_ONLINE = true;
//                    String status = RFID_ResponseStr.substring(15,17);
//                    if (status == "07") {
//                        IsRFID_TAG_READ_SUCCESSFUL = true;
//                        RFID_Tag_Read = RFID_ResponseStr.substring(18,RFID_ResponseStr.length()-6);
//                    }
//                    else {
//                        IsRFID_TAG_READ_SUCCESSFUL = false;
//                        RFID_Tag_Read = "";
//                    }
//                }
//                else IsRFID_ONLINE= false;
//            }
//        }, 100);
//    }

    private int XORofHexStringArray(String[] hexStrArray) {
        int bcc = 0;
        boolean skipFirst = true;
        if (hexStrArray.length >= 2)
        {
            bcc = Integer.parseInt(hexStrArray[0], 16);
            for (String s: hexStrArray) {
                if (skipFirst) { skipFirst = false; }
                else
                    bcc= bcc ^ Integer.parseInt(s, 16);
            }
        }
        return bcc;
    }
    public void DUNotRespondingCloseAlert() {
        try {
            if (ProcessStage <= 1) {
                DUNotRespondDialog = true;
                AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(mContext);
                alertDialogBuilder.setCancelable(false);
                alertDialogBuilder.setMessage("Do You Want to Close this Screen? \n If you selects YES then you will be redirected to order list & \n If you selects NO then it will try to reconnect to DU.");
                alertDialogBuilder.setPositiveButton("YES", new DialogInterface.OnClickListener() {
                    @SuppressLint("SetTextI18n")
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            //Remove all Callback and end MDU Activity
                            CloseComPort(ComPortDISP);
                            //TCPCommunicator.closeStreams();
                            // setConnection(true);
                            finish();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
                alertDialogBuilder.setNegativeButton("NO", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                        DUNotRespondDialog = false;
                        DuConnectCounter = 0;
                    }
                });
                AlertDialog alertDialog = alertDialogBuilder.create();
                alertDialog.setTitle("DU Not Responding! Try Again Later.");
                if (!alertDialog.isShowing()) {
                    alertDialog.show();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

//    public void LoopRFIDData(final int timer) {
//        rfidhandler = new Handler();
//        rfidrunnable = new Runnable() {
//            @SuppressLint({"SetTextI18n", "LongLogTag"})
//            @Override
//            public void run() {
//                try {
//                    if (IsDecantationStarted) {
//                        if (PolStatus) {
//                            String END = "0A";
//                            String CR = "0D";
//                            String ConnectionCommand = "AT+CHECK=1";
//                            String CMD = MyFunc.StringToHex(ConnectionCommand);
//                            String CHK = CMD + END + CR;
//                            ComPortGATEWAY.sendHex(CHK);
//                            Log.i("Check Tag", CHK);
//                            if (IsTagReading) {
//                                String CheckTag = "AT+DATA=1";
//                                String SetTAG = MyFunc.StringToHex(CheckTag);
//                                String SendDATA = SetTAG + END + CR;
//                                ComPortGATEWAY.sendHex(SendDATA);
//                                Log.i("Check Tag For Tag Detection", SendDATA);
//                            }
//                        }
//                    }
//                    rfidhandler.postDelayed(rfidrunnable, timer);
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }
//            }
//        };
//        rfidhandler.post(rfidrunnable);
//    }

    public void RFIDNotRespondingCloseAlert() {
        try {
            RFIDNotRespondDialog = true;
            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(EastmanDecantActivity.this);
            alertDialogBuilder.setCancelable(false);
            alertDialogBuilder.setNeutralButton("Connect to RFID", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    //RFIDReconnectCounter++;
                    RFIDNotRespondDialog = false;

                    Handler handler = new Handler();
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            //getRfidStatus();
                            RFIDConnectCounter = 0;
                        }
                    }, 2000);
                }
            });

            alertDialogBuilder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    RFIDNotRespondDialog = false;
                    RFIDConnectCounter = 0;
                }
            });

            // if (RFIDReconnectCounter > 0 && IsRFIDStarted) {
            alertDialogBuilder.setMessage(" Do you want to Proceed with OTP based verification ?");
            alertDialogBuilder.setPositiveButton("PROCEED WITH OTP", new DialogInterface.OnClickListener() {
                @SuppressWarnings({"ConstantConditions", "rawtypes"})
                @SuppressLint("SetTextI18n")
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    final Dialog dialog1 = new Dialog(EastmanDecantActivity.this);
                    dialog1.setCancelable(false);
                    dialog1.requestWindowFeature(Window.FEATURE_NO_TITLE);
                    dialog1.setContentView(R.layout.otp_decant_dialog);
                    dialog1.getWindow().setGravity(Gravity.TOP);
                    dialog1.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    final TextView Header = dialog1.findViewById(R.id.tv_header);
                    Header.setText("Please enter valid OTP Number for Cart Id " + crmNumber + "?");
                    final EditText etOtpCode = dialog1.findViewById(R.id.et_otp_code);
                    etOtpCode.setHint("Enter OTP number");
                    final Button cancel = dialog1.findViewById(R.id.btn_cancel_otp_decant);
                    final Button submit = dialog1.findViewById(R.id.btn_submit_otp_decant);
                    final Spinner reasonSp = dialog1.findViewById(R.id.sp_Reason_Otp_Decant);
                    reasonSp.setVisibility(View.GONE);
                    cancel.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dialog1.dismiss();
                            //Toast.makeText(mContext, "Invalid OTP Code Entered! Please contact Customer for Valid OTP.", Toast.LENGTH_LONG).show();
                            tvRFIDStatus.setText("OTP verification declined, Cannot proceed with dispensing");
                            tvRFIDStatus.setTextColor(Color.RED);
                            IsOtpSet = false;
                            //tvNozzleState.setText("Please lift NOZZLE and start dispensing...");
                            RFIDNotRespondDialog = false;
                            RFIDConnectCounter = 0;
                            PolStatus = false;
                            finishOrderButton.setText("Close");
                            finishOrderButton.setVisibility(VISIBLE);
                        }
                    });
                    submit.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            OtpCode = etOtpCode.getText().toString();

                            if (OtpCode.equals(DL_OTP)) {
                                //Toast.makeText(EastmanDecantActivity.this, "OTP is Valid.", Toast.LENGTH_SHORT).show();
                                tvRFIDStatus.setText("OTP verification successful");
                                tvRFIDStatus.setTextColor(Color.GREEN);
                                IsOtpSet = true;
                                tvHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        tvFillingStatus.setText("Please lift NOZZLE and start dispensing...");
                                    }
                                });

                                RFIDConnectCounter = 0;
                                dialog1.dismiss();
                            } else {
                                dialog1.dismiss();
                                //Toast.makeText(mContext, "Invalid OTP Code Entered! Please contact Customer for Valid OTP.", Toast.LENGTH_LONG).show();
                                tvRFIDStatus.setText("OTP verification FAILED");
                                tvRFIDStatus.setTextColor(Color.RED);
                                IsOtpSet = false;
                                //tvNozzleState.setText("Please lift NOZZLE and start dispensing...");
                                RFIDNotRespondDialog = false;
                                RFIDConnectCounter = 0;
                                startDeliveryButton.setText("Try Again");
                                startDeliveryButton.setVisibility(VISIBLE);
                                finishOrderButton.setText("Close");
                                finishOrderButton.setVisibility(VISIBLE);
                            }
                        }
                    });
                    dialog1.show();
                }
            });
            // }
            AlertDialog alertDialog = alertDialogBuilder.create();
            alertDialog.setTitle("MDU - RFID Not Responding...");
            if (!alertDialog.isShowing()) {
                alertDialog.show();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    @SuppressLint("SetTextI18n")
    private void presetSet(Integer Order_Qty) {
        String STX;
        STX = "01";
        CharSequence t = STX;
        char[] text = new char[t.length()];
        for (int i = 0; i < t.length(); i++) {
            text[i] = t.charAt(i);
        }
        if (ComA.isOpen()) ComA.close();
        ComA = new SerialControl();
        ComA.setBaudRate(2400);
        ComA.setParity(3); // MARK PArity
        OpenComPort(ComA);
        sendPortData(ComA, text);
        ComA.close();
        try {
            Thread.sleep(100);// 显示性能高的话，可以把此数值调小。
        } catch (Exception e) {
            e.printStackTrace();
        }

        //STX = "078B000003E882";
        String CHK = "";
        STX = MakeEastManPresetCmdString(Order_Qty);
        CHK = GenerateEastManChecksum(STX);
        STX = STX + CHK;
        STX = STX.substring(2);
        t = STX;
        text = new char[t.length()];
        for (int i = 0; i < t.length(); i++) {
            text[i] = t.charAt(i);
        }

        ComA = new SerialControl();
        ComA.setBaudRate(2400);
        ComA.setParity(4); // SPACE PArity
        OpenComPort(ComA);
        sendPortData(ComA, text);
        try {
            Thread.sleep(100);// 显示性能高的话，可以把此数值调小。
        } catch (Exception e) {
            e.printStackTrace();
        }
        tvLogs.setText("Command sent: PRESET ");
        //ComA.close();
    }

    @SuppressLint("SetTextI18n")
    private void GetFuellingData() {
        String STX;
        STX = "01";
        CharSequence t = STX;
        char[] text = new char[t.length()];
        for (int i = 0; i < t.length(); i++) {
            text[i] = t.charAt(i);
        }
        if (ComA.isOpen()) ComA.close();
        ComA = new SerialControl();
        ComA.setBaudRate(2400);
        ComA.setParity(3); // MARK PArity
        OpenComPort(ComA);
        sendPortData(ComA, text);

        try {
            Thread.sleep(200);// 显示性能高的话，可以把此数值调小。
        } catch (Exception e) {
            e.printStackTrace();
        }
        ComA.close();
        STX = "038F6D";
        t = STX;
        text = new char[t.length()];
        for (int i = 0; i < t.length(); i++) {
            text[i] = t.charAt(i);
        }

        ComA = new SerialControl();
        ComA.setBaudRate(2400);
        ComA.setParity(4); // SPACE PArity
        OpenComPort(ComA);
        sendPortData(ComA, text);
        try {
            Thread.sleep(200);// 显示性能高的话，可以把此数值调小。
        } catch (Exception e) {
            e.printStackTrace();
        }
        tvLogs.setText("Command sent: GetLiveFuelingData ");
    }

    @SuppressLint("SetTextI18n")
    private void status() {
        String STX;
        STX = "01";
        CharSequence t = STX;
        char[] text = new char[t.length()];
        for (int i = 0; i < t.length(); i++) {
            text[i] = t.charAt(i);
        }
        if (ComA.isOpen()) ComA.close();
        ComA = new SerialControl();
        ComA.setBaudRate(2400);
        ComA.setParity(3); // MARK PArity
        OpenComPort(ComA);
        sendPortData(ComA, text);
        ComA.close();
        try {
            Thread.sleep(100);// 显示性能高的话，可以把此数值调小。
        } catch (Exception e) {
            e.printStackTrace();
        }

        STX = "0312EA";
        t = STX;
        text = new char[t.length()];
        for (int i = 0; i < t.length(); i++) {
            text[i] = t.charAt(i);
        }

//				if (ComA.isOpen())
//					ComA.close();
        ComA = new SerialControl();
        ComA.setBaudRate(2400);
        ComA.setParity(4); // SPACE PArity
        OpenComPort(ComA);
        sendPortData(ComA, text);
        try {
            Thread.sleep(100);// 显示性能高的话，可以把此数值调小。
        } catch (Exception e) {
            e.printStackTrace();
        }
        //ComA.close();
        tvLogs.setText("Command sent: STATUS ");
    }

    private void startFueling() {
        String STX;
        STX = "01";
        CharSequence t = STX;
        char[] text = new char[t.length()];
        for (int i = 0; i < t.length(); i++) {
            text[i] = t.charAt(i);
        }
        if (ComA.isOpen()) ComA.close();
        ComA = new SerialControl();
        ComA.setBaudRate(2400);
        ComA.setParity(3); // MARK PArity
        OpenComPort(ComA);
        sendPortData(ComA, text);
        ComA.close();
        try {
            Thread.sleep(100);// 显示性能高的话，可以把此数值调小。
        } catch (Exception e) {
            e.printStackTrace();
        }

        STX = "0308F4";
        t = STX;
        text = new char[t.length()];
        for (int i = 0; i < t.length(); i++) {
            text[i] = t.charAt(i);
        }

//				if (ComA.isOpen())
//					ComA.close();
        ComA = new SerialControl();
        ComA.setBaudRate(2400);
        ComA.setParity(4); // SPACE PArity
        OpenComPort(ComA);
        sendPortData(ComA, text);
        try {
            Thread.sleep(100);// 显示性能高的话，可以把此数值调小。
        } catch (Exception e) {
            e.printStackTrace();
        }
        //ComA.close();
    }

    private void stopFueling() {
        String STX;
        STX = "01";
        CharSequence t = STX;
        char[] text = new char[t.length()];
        for (int i = 0; i < t.length(); i++) {
            text[i] = t.charAt(i);
        }
        if (ComA.isOpen()) ComA.close();
        ComA = new SerialControl();
        ComA.setBaudRate(2400);
        ComA.setParity(3); // MARK PArity
        OpenComPort(ComA);
        sendPortData(ComA, text);
        ComA.close();
        try {
            Thread.sleep(100);// 显示性能高的话，可以把此数值调小。
        } catch (Exception e) {
            e.printStackTrace();
        }

        STX = "0310EC";
        t = STX;
        text = new char[t.length()];
        for (int i = 0; i < t.length(); i++) {
            text[i] = t.charAt(i);
        }

//				if (ComA.isOpen())
//					ComA.close();
        ComA = new SerialControl();
        ComA.setBaudRate(2400);
        ComA.setParity(4); // SPACE PArity
        OpenComPort(ComA);
        sendPortData(ComA, text);
        try {
            Thread.sleep(100);// 显示性能高的话，可以把此数值调小。
        } catch (Exception e) {
            e.printStackTrace();
        }
        //ComA.close();

    }

    @SuppressLint("SetTextI18n")
    private void readTOT() {
        String STX;
        STX = "01";
        CharSequence t = STX;
        char[] text = new char[t.length()];
        for (int i = 0; i < t.length(); i++) {
            text[i] = t.charAt(i);
        }
        if (ComA.isOpen()) ComA.close();
        ComA = new SerialControl();
        ComA.setBaudRate(2400);
        ComA.setParity(3); // MARK PArity
        OpenComPort(ComA);
        sendPortData(ComA, text);
        ComA.close();
        try {
            Thread.sleep(100);// 显示性能高的话，可以把此数值调小。
        } catch (Exception e) {
            e.printStackTrace();
        }

        STX = "038E6E";
        t = STX;
        text = new char[t.length()];
        for (int i = 0; i < t.length(); i++) {
            text[i] = t.charAt(i);
        }

        ComA = new SerialControl();
        ComA.setBaudRate(2400);
        ComA.setParity(4); // SPACE PArity
        OpenComPort(ComA);
        sendPortData(ComA, text);
        try {
            Thread.sleep(100);// 显示性能高的话，可以把此数值调小。
        } catch (Exception e) {
            e.printStackTrace();
        }
        //ComA.close();
        tvLogs.setText("Command sent: GET-TOTALIZER ");
    }

    @SuppressLint("SetTextI18n")
    private void pauseTxn() {
        String STX;
        STX = "01";
        CharSequence t = STX;
        char[] text = new char[t.length()];
        for (int i = 0; i < t.length(); i++) {
            text[i] = t.charAt(i);
        }
        if (ComA.isOpen()) ComA.close();
        ComA = new SerialControl();
        ComA.setBaudRate(2400);
        ComA.setParity(3); // MARK PArity
        OpenComPort(ComA);
        sendPortData(ComA, text);
        ComA.close();
        try {
            Thread.sleep(100);// 显示性能高的话，可以把此数值调小。
        } catch (Exception e) {
            e.printStackTrace();
        }

        STX = "0342BA";
        t = STX;
        text = new char[t.length()];
        for (int i = 0; i < t.length(); i++) {
            text[i] = t.charAt(i);
        }

        ComA = new SerialControl();
        ComA.setBaudRate(2400);
        ComA.setParity(4); // SPACE PArity
        OpenComPort(ComA);
        sendPortData(ComA, text);
        try {
            Thread.sleep(100);// 显示性能高的话，可以把此数值调小。
        } catch (Exception e) {
            e.printStackTrace();
        }
        tvLogs.append("\nPAUSE command sent");
        //ComA.close();
        tvLogs.setText("Command sent: PAUSE-TXN ");
    }

    @SuppressLint("SetTextI18n")
    private void resumeTxn() {
        String STX;
        STX = "01";
        CharSequence t = STX;
        char[] text = new char[t.length()];
        for (int i = 0; i < t.length(); i++) {
            text[i] = t.charAt(i);
        }
        if (ComA.isOpen()) ComA.close();
        ComA = new SerialControl();
        ComA.setBaudRate(2400);
        ComA.setParity(3); // MARK PArity
        OpenComPort(ComA);
        sendPortData(ComA, text);
        ComA.close();
        try {
            Thread.sleep(100);// 显示性能高的话，可以把此数值调小。
        } catch (Exception e) {
            e.printStackTrace();
        }

        STX = "0343B9";
        t = STX;
        text = new char[t.length()];
        for (int i = 0; i < t.length(); i++) {
            text[i] = t.charAt(i);
        }

        ComA = new SerialControl();
        ComA.setBaudRate(2400);
        ComA.setParity(4); // SPACE PArity
        OpenComPort(ComA);
        sendPortData(ComA, text);
        try {
            Thread.sleep(100);// 显示性能高的话，可以把此数值调小。
        } catch (Exception e) {
            e.printStackTrace();
        }
        //ComA.close();
        tvLogs.setText("Command sent: RESUME-TXN ");
    }

    private void CheckComA() {

        ComA = new SerialControl();
        ComA.setBaudRate(2400);
        ComA.setParity(3);
        OpenComPort(ComA);
    }

    private class SerialControl extends SerialHelper {
        public SerialControl() {
        }
        @Override
        protected void onDataReceived(final byte[] ComRecData) {
            DispQueue.AddQueue(ComRecData);
            DispQueue.setResume();
        }
    }

    private class DispQueueThread extends Thread {
        private final BlockingQueue<byte[]> QueueList = new LinkedBlockingQueue<byte[]>();

        @Override
        public void run() {
            super.run();
            while (!isInterrupted()) {
                if (!isRun) break;
                while ((QueueList.size() != 0)) {
                    if (!isRun) break;
                    final byte[] ComData = QueueList.poll();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            DispRecData(ComData);
                        }
                    });
                    try {
                        Thread.sleep(5);// 显示性能高的话，可以把此数值调小。
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                synchronized (this) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        public synchronized void AddQueue(byte[] ComData) {
            QueueList.add(ComData);
        }


        public synchronized void setResume() {
            notify();
        }
    }
    // ----------------------------------------------------显示接收数据
    @SuppressLint("SetTextI18n")
    private void DispRecData(byte[] AComRecData) {
//        int nComPort = AComRecData[0];
        int size = AComRecData.length - 1;
        byte[] ComRecData = new byte[size];
        System.arraycopy(AComRecData, 1, ComRecData, 0, size);

        StringBuilder sMsg = new StringBuilder();
        sMsg.append(MyFunc.ByteArrToHex(ComRecData));
        tvLogs.append(" Response received: " + sMsg);
        DU_ResponseStr = DU_ResponseStr + sMsg.toString();

    }

    // ----------------------------------------------------串口发送
    private void sendPortData(SerialHelper ComPort, char[] sOut) {
        if (ComPort != null && ComPort.isOpen()) {
            ComPort.sendData(sOut, false);
        }
    }

    // ----------------------------------------------------关闭串口
    private void CloseComPort(SerialHelper ComPort) {
        if (ComPort != null) {
            ComPort.destroySend();
            ComPort.close();
        }
    }

    // ----------------------------------------------------打开串口
    private void OpenComPort(SerialHelper ComPort) {
        try {
            ComPort.open();
        } catch (SecurityException e) {
            //if (ComPort.getnPort() == 0) toggleButtonCOMA.setChecked(false);
            ShowMessage(getString(R.string.nopermission));
        } catch (IOException e) {
            //if (ComPort.getnPort() == 0) toggleButtonCOMA.setChecked(false);
            ShowMessage(getString(R.string.unknownerr));
        } catch (InvalidParameterException e) {
            //if (ComPort.getnPort() == 0) toggleButtonCOMA.setChecked(false);
            ShowMessage(getString(R.string.parametererr));
        }
    }


    @Override
    protected void onDestroy() {
        if (DispQueue != null) {
            isRun = false;
            DispQueue.setResume();
        }
        CloseComPort(ComA);
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
        super.onDestroy();

    }

    // ------------------------------------------显示消息
    private void ShowMessage(String sMsg) {
        Toast.makeText(this, sMsg, Toast.LENGTH_SHORT).show();
    }

    static public String MakeEastManPresetCmdString(int preset_qty) {
        String preset_prefix_str = "01078B";
        String preset_qty_str = DecToHex(preset_qty * 100);
        preset_qty_str = padLeftZeros(preset_qty_str, 8);
        return preset_prefix_str + preset_qty_str;
    }

    static public String padLeftZeros(String inputString, int length) {
        if (inputString.length() >= length) {
            return inputString;
        }
        StringBuilder sb = new StringBuilder();
        while (sb.length() < length - inputString.length()) {
            sb.append('0');
        }
        sb.append(inputString);

        return sb.toString();
    }

    static public String GenerateEastManChecksum(String cmd) {
        byte[] cmd_bytes = HexToByteArr(cmd);
        int sum_of_bytes = 0;
        int final_value = 0;
        for (int i = 0; i < cmd_bytes.length; i++) {
            sum_of_bytes = sum_of_bytes + cmd_bytes[i];
        }
        sum_of_bytes = (~sum_of_bytes) + 1;
        final_value = sum_of_bytes & 0xff;
        return padLeftZeros(DecToHex(final_value), 2);
    }
}
