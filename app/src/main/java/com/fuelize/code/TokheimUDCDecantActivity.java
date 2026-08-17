package com.fuelize.code;

import static android.view.View.VISIBLE;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
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
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fuelize.db.DatabaseHelper;
import com.fuelize.model.OrderData;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * TokheimUDCDecantActivity - Tokheim UDC Protocol Implementation
 *
 * PROTOCOL: Tokheim UDC (Universal Dispenser Controller) Protocol V01.05
 *
 * COMMUNICATION: RS-485, 9600 bps, 8N1, Doubletalk encoding
 * ADDRESS: F0 = Fuelling Point 1
 *
 * FUNCTION CODES:
 * A0: FP ID | A1: Display Data | A2: Status | A3: Halt | A4: Resume
 * A5: Authorize | A7: Reset | A9: Totals | AB 01: Pre-Auth | AB 26: Preset
 *
 * STATUS: 0x20=Idle | 0xA0=Handle Up | 0x50=PreAuth | 0x90=Auth | 0xF0=Fueling
 */
public class TokheimUDCDecantActivity extends AppCompatActivity {

    private static final String TAG = "TokheimUDC";
    static int PollingTimer = 1000;

    // RS-485 serial port. Kept identical to the port EastmanDecantActivity uses
    // (Eastman relies on the SerialHelper default, which is "/dev/ttyUSB0").
    private static final String SERIAL_PORT = "/dev/ttyUSB0";

    // UDC Protocol Constants
    private static final byte UDC_FP_ADDRESS = (byte) 0xF0;
    private static final byte FC_REQUEST_DISPLAY_DATA = (byte) 0xA1;
    private static final byte FC_REQUEST_STATUS = (byte) 0xA2;
    private static final byte FC_HALT_SALE = (byte) 0xA3;
    private static final byte FC_RESUME_SALE = (byte) 0xA4;
    private static final byte FC_AUTHORIZE = (byte) 0xA5;
    private static final byte FC_REQUEST_TOTALS = (byte) 0xA9;
    private static final byte FC_EXTENDED_AB = (byte) 0xAB;
    private static final byte FC_AB_PRE_AUTHORIZE = 0x01;
    private static final byte FC_AB_REMOTE_PRESET = 0x26;   // NOTE: not a real UDC command; no longer used

    // A5 Authorize "slow flow offset" byte. The UDC spec example uses 05 - the small
    // volume before the preset at which the pump switches to slow flow for an accurate
    // cut-off. Tune here if the pump overshoots or stops short of the ordered quantity.
    private static final byte SLOW_FLOW_OFFSET = 0x05;

    // Show the ordered quantity on the PUMP's own display before the nozzle is lifted,
    // using the A6 "Send Data for Main Displays" command (volume field = preset). Per the
    // UDC spec this updates the idle display only if the pump is programmed to allow it,
    // so it is best-effort. Set false to disable without affecting the working A5 flow.
    private static final boolean SHOW_PRESET_ON_PUMP = true;
    // Extra "show the preset on the pump" method: reset the fueling point (A7) so it goes
    // uninitialised (0x2F), then send A6 - per the UDC spec, A6 to a 0x2F point UNCONDITIONALLY
    // writes money/volume/price to the display (unlike an already-initialised point, which only
    // does so if programmed for "price change with handle up"). A 0x2F handler in Stage 5 both
    // shows the value and recovers the pump to idle. This is the more aggressive method - if the
    // pump ever misbehaves, set this to false; the A6-only methods above still run.
    private static final boolean TRY_RESET_THEN_A6 = true;
    private static final byte FC_RESET_FP = (byte) 0xA7;   // Reset fueling point

    // Status Bytes (from UDC Protocol Specification)
    // Normal dispenser status bytes - when status doesn't match, evaluate specific bits
    private static final byte STATUS_UNINITIALIZED = 0x2F;          // Requires A6 main display data
    private static final byte STATUS_IDLE_HANDLE_DOWN = 0x20;       // Idle - handle down
    private static final byte STATUS_IDLE_HANDLE_UP = (byte) 0xA0;  // Idle - handle up - NOT authorized (request for auth)
    private static final byte STATUS_PRINT_READY = (byte) 0xA1;     // Print ready - can ask for vehicle number
    private static final byte STATUS_DISPENSER_ERROR = (byte) 0xA2; // Dispenser error - can ask for FP error
    private static final byte STATUS_PRESET_READY = (byte) 0xA3;    // Preset ready - can ask for preset data
    private static final byte STATUS_AUTHORIZED = (byte) 0x90;      // Handle up - authorized - no valves open yet
    private static final byte STATUS_FUELING_SLOW_FLOW = (byte) 0xD0;       // Sale in progress - slow flow valve only
    private static final byte STATUS_FUELING_BOTH_VALVES = (byte) 0xF0;     // Sale in progress - both valves open
    private static final byte STATUS_FUELING_APPROACHING_MAX = (byte) 0xD4; // Sale in progress - slow flow - approaching max
    private static final byte STATUS_SALE_HALTED = (byte) 0x98;     // In-progress sale halted
    private static final byte STATUS_INVALID_DATA = 0x00;           // Invalid data received from FCC
    private static final byte UDC_ACK = (byte) 0xB0;                // Acknowledgement for data acceptance

    // Note: STATUS_PRE_AUTHORIZED (0x50) and STATUS_FUELING_AUTHORIZED (0x91) removed
    // as they are not in the official spec - may have been incorrect assumptions

    // Additional Function Code for initialization
    private static final byte FC_SEND_DISPLAY_DATA = (byte) 0xA6;

    Handler polhandler, tvHandler;
    Runnable polrunnable;
    Context mContext;

    TextView txtCRMNumber, tvDUStatus, tvRFIDStatus, tvFillingStatus, txtOrderQuantity, tvOutput, tvLogs;
    Button btnResume, startDeliveryButton, finishOrderButton, ButtonSendCOMA, btnStatusMsg;
    SerialControl ComA;
    Uri uriData;
    Boolean isRun = true;
    DispQueueThread DispQueue;
    ScrollView scrollView;
    // Pinned to Locale.US on purpose. df.format() feeds the csifuel:// callback
    // (order_quantity / start_totalizer / end_totalizer), which the driver app reads
    // with double.tryParse(). Under a comma-decimal device locale, 10.5 formats as
    // "10,5", tryParse returns null -> 0.0, and DeliveryScreen pops "Failed to
    // deliver order" on a delivery that actually succeeded.
    DecimalFormat df = new DecimalFormat("#.##", DecimalFormatSymbols.getInstance(Locale.US));
    StringBuilder sMsg;

    int ProcessStage = 0;
    int DuConnectCounter = 0;
    int RFIDConnectCounter = 0;

    String DU_ResponseStr = "";
    // Last preset (AB 26) frame sent, shown in the on-screen failure Toast/log so the
    // exact bytes and the pump's reply can be captured and sent for diagnosis.
    String lastPresetTxHex = "";
    // Last A6 (Send Data for Main Displays) frame sent, and a small retry budget for the
    // A6-reply diagnostic (Stage 15), so the exact bytes and the pump's reply can be
    // captured in the on-screen log to see why the preset volume is / isn't displayed.
    String lastA6TxHex = "";
    int A6DiagRetry = 0;
    // Counter for refreshing the A6 preset display while the nozzle is down (idle wait).
    int a6RefreshTick = 0;
    public static String crmNumber = "NA";
    public static String order_qty = "NA";
    // NOTE: the former "unitPrice" static was removed. It was never assigned by
    // anything, yet it fed the A5 Authorize price via a 100.00 default - i.e. it
    // looked like a price knob while guaranteeing a fabricated rate. The price is
    // now read from the pump itself (Stage 12/13) and there is no default.

    private Boolean PolStatus = true;
    private Boolean DUNotRespondDialog = false;
    private Boolean RFIDNotRespondDialog = false;
    private Boolean IsOtpSet = false;
    private Boolean IsFuelingStarted = false;
    private Boolean IsDecantationStarted = false;
    private Boolean IsPreAuthorized = false;
    private Boolean IsDispenserAuthorized = false;
    private Boolean DispensingStarted = false;
    private Boolean DispensingCompleted = false;
    private Boolean DLDataReceived = false;

    private byte lastFunctionCodeSent = 0;
    private byte lastStatusReceived = 0;

    public static final String OTP = "otp";
    public static final String ORDER_QTY = "order_qty";
    public static final String RFID_TAG = "rfid_tag";
    public static final String ORDER_NO = "order_no";
    String DL_OTP = "1234";
    String DL_ORDER_QTY = "15";
    String DL_RFID_TAG = "";
    String DL_ORDER_NO = "1234";

    DatabaseHelper helper;
    float volumeFloat = 0.0f;
    float volumeFloatFinal = 0.0f;
    float StartTotalizer = 0;
    float EndTotalizer = 0;

    // Retry budgets for the totalizer reads (A9). Bounded so a pump that never
    // answers A9 can never deadlock the state machine (see Stage 3 / Stage 8).
    private static final int START_TOT_MAX_RETRY = 3;
    private static final int END_TOT_MAX_RETRY = 5;
    private int StartTotRetryCount = 0;
    private int EndTotRetryCount = 0;

    // True only when the corresponding totalizer was actually read from the pump.
    private boolean StartTotalizerValid = false;
    private boolean EndTotalizerValid = false;

    // Unit price read back from the pump itself via A1 (Display Data), bytes 0-1.
    // The FP is configured with its own price-per-litre (e.g. 20.93 Pula). If the
    // price carried in the A5 Authorize frame does not match it, the FP can refuse
    // the authorization and the operator display stays on "Waiting for FCC
    // Authorization" forever. Reading it from the pump keeps this site-independent
    // and also stops the transaction being priced at the hardcoded 100.00 fallback.
    private static final int PRICE_MAX_RETRY = 3;
    private int PriceRetryCount = 0;
    private float PumpUnitPrice = 0f;
    private boolean PumpUnitPriceValid = false;

    // Preset acknowledge-and-retry, modelled on EastmanDecantActivity Stage 5, which
    // re-sends its preset every poll tick until the DU answers with a known ACK and
    // only then advances. Tokheim previously fired AB 26 exactly once behind the
    // IsPreAuthorized latch and advanced regardless of the reply, so a preset that
    // was lost or refused went completely unnoticed.
    // Success signal here is the FP reaching 0xA3 (Preset Ready) - a documented
    // protocol state rather than a magic byte, so it holds whether or not this FP
    // bothers to send the 0xB0 ACK.
    private static final int PRESET_MAX_RETRY = 3;
    private int PresetRetryCount = 0;
    private boolean PresetConfirmed = false;
    private boolean PresetAckSeen = false;

    // Bound on Stage 1's unrecognised-status poll. Monotonic for the life of one
    // delivery attempt - deliberately not reset, since Stage 1 is entered once and
    // any re-entry (0x2F init, halted-sale recovery) should still count toward the
    // same budget rather than resetting it and risking an endless alternation.
    // ~15 polls at 1s each before the operator is told what the DU is stuck in.
    private static final int UNKNOWN_STATUS_MAX = 15;
    private int UnknownStatusCount = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_eastman_decant);
        mContext = this;
        tvHandler = new Handler();
        helper = new DatabaseHelper(TokheimUDCDecantActivity.this);
        setControls();
        deepLink();
        ComA = new SerialControl();
        DispQueue = new DispQueueThread();
        DispQueue.start();
        sMsg = new StringBuilder();
        LoopPolling(PollingTimer);
        Log.i(TAG, "TokheimUDCDecantActivity created - UDC Protocol");
    }

    @Override public void onPause() { super.onPause(); }

    @SuppressLint("SuspiciousIndentation")
    @Override
    protected void onResume() {
        super.onResume();
        if (DispQueue != null) return;
        isRun = true;
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
        txtOrderQuantity = findViewById(R.id.tv_order_quantity);
        tvFillingStatus = findViewById(R.id.tv_mdu_filling_status);
        finishOrderButton = findViewById(R.id.btn_finish_order);
        scrollView = findViewById(R.id.scrollView);
        tvOutput = findViewById(R.id.tv_output);
        ButtonSendCOMA = findViewById(R.id.btn_set_preset);
        btnStatusMsg = findViewById(R.id.btn_status_message);

        finishOrderButton.setText("Close");
        finishOrderButton.setVisibility(View.INVISIBLE);

        finishOrderButton.setOnClickListener(v -> {
            if (!DispensingCompleted) {
                AlertDialog.Builder builder = new AlertDialog.Builder(TokheimUDCDecantActivity.this);
                builder.setCancelable(false);
                builder.setMessage("Dispensing not completed!\nDo you want to Finish or Pause?");
                builder.setPositiveButton("Finish", (dialog, which) -> {
                    PolStatus = false;
                    DU_ResponseStr = "";
                    haltSale();
                    new Handler().postDelayed(() -> { DU_ResponseStr = ""; requestTotals(); }, 500);
                    new Handler().postDelayed(() -> ProcessStage = 8, 600);
                    new Handler().postDelayed(() -> PolStatus = true, 700);
                    dialog.dismiss();
                });
                builder.setNegativeButton("Pause", (dialog, which) -> {
                    ProcessStage = 9;
                    startDeliveryButton.setText("Resume Delivery");
                    startDeliveryButton.setVisibility(VISIBLE);
                    finishOrderButton.setVisibility(View.INVISIBLE);
                });
                builder.setNeutralButton("Cancel", (dialog, which) -> dialog.dismiss());
                AlertDialog alertDialog = builder.create();
                alertDialog.setTitle("Close or Pause confirmation");
                if (!alertDialog.isShowing()) alertDialog.show();
            } else {
                CloseComPort(ComA);
                if (DLDataReceived) {
                    // Both-or-neither totalizers - see totalizersUsable().
                    Uri uri = Uri.parse("csifuel://driver/delivery_screen?order_quantity=" + df.format(volumeFloat) +
                            "&start_totalizer=" + startTotForCallback() + "&end_totalizer=" + endTotForCallback());
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) finishAndRemoveTask();
                    } catch (Exception e) { Log.e(TAG, "Intent error: " + e.getMessage()); }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) finishAndRemoveTask();
                }
            }
        });

        try { initializeSerialPort(); } catch (Exception e) { Log.e(TAG, "Serial init error: " + e.getMessage()); return; }

        startDeliveryButton = findViewById(R.id.btn_start_delivery);
        startDeliveryButton.setOnClickListener(v -> {
            if (!startDeliveryButton.getText().equals("Resume Delivery")) {
                AlertDialog.Builder builder = new AlertDialog.Builder(TokheimUDCDecantActivity.this);
                builder.setCancelable(false);
                builder.setMessage("Start Delivery of Order " + crmNumber + " for " + order_qty + " Litres?");
                builder.setPositiveButton("YES", (dialog, which) -> {
                    IsDecantationStarted = true;
                    PolStatus = true;
                    startDeliveryButton.setVisibility(View.INVISIBLE);
                    dialog.dismiss();
                });
                builder.setNegativeButton("NO", (dialog, which) -> dialog.dismiss());
                AlertDialog alertDialog = builder.create();
                alertDialog.setTitle("Start Delivery Confirmation");
                if (!alertDialog.isShowing()) alertDialog.show();
            } else {
                ProcessStage = 10;
                startDeliveryButton.setVisibility(View.INVISIBLE);
                finishOrderButton.setVisibility(VISIBLE);
            }
        });

        btnResume.setOnClickListener(v -> { resumeSale(); ProcessStage = 6; btnResume.setVisibility(View.INVISIBLE); });
    }

    private void initializeSerialPort() {
        Log.i(TAG, "initializeSerialPort() called");
        ComA = new SerialControl();
        // Match the Eastman activity's RS-485 port. Eastman never calls setPort(),
        // so it uses the SerialHelper default ("/dev/ttyUSB0"). Previously this was
        // hardcoded to "/dev/ttyCOM10", which put the two protocols on different ports.
        ComA.setPort(SERIAL_PORT);
        ComA.setBaudRate(9600);
        ComA.setParity(0);
        OpenComPort(ComA);
        Log.i(TAG, "Serial port init complete - Port: " + SERIAL_PORT + " | Baud: 9600 | isOpen: " + (ComA != null ? ComA.isOpen() : "null"));
    }

    @SuppressLint("SetTextI18n")
    public void deepLink() {
        Intent intent = getIntent();
        if (intent != null && intent.getData() != null) {
            uriData = intent.getData();
            DL_OTP = uriData.getQueryParameter(OTP);
            DL_RFID_TAG = uriData.getQueryParameter(RFID_TAG);
            DL_ORDER_QTY = uriData.getQueryParameter(ORDER_QTY);
            DL_ORDER_NO = uriData.getQueryParameter(ORDER_NO);
            DLDataReceived = true;
            tvLogs.append("\nDL data received");
            if (helper.checkOrderExists(DL_ORDER_NO)) {
                tvLogs.append("\nOrder exists");
                android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
                builder.setCancelable(false);
                builder.setMessage("Order " + DL_ORDER_NO + " already Decanted!");
                // ===================== FIX A3 (START) =====================
                // BEFORE: the button only closed the app and never re-sent the
                //         result, leaving the order stuck on "Get Started":
                //   builder.setPositiveButton("Close", (d, w) -> { d.dismiss(); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) finishAndRemoveTask(); });
                // AFTER: re-send the stored result to the Driver App (idempotent
                //        re-sync; pump is NOT re-engaged) using the same csifuel://
                //        callback as the normal "Finish" button, then close.
                builder.setPositiveButton("Re-send to App", (d, w) -> {
                    d.dismiss();
                    try {
                        OrderData o = helper.getOrderData(DL_ORDER_NO);
                        Uri uri = Uri.parse("csifuel://driver/delivery_screen?order_quantity=" + o.getDecantQty()
                                + "&start_totalizer=" + totOrZero(o.getStartTotalizer())
                                + "&end_totalizer=" + totOrZero(o.getEndTotalizer()));
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception e) {
                        Log.e(TAG, "Re-send error: " + e.getMessage());
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) finishAndRemoveTask();
                });
                // ===================== FIX A3 (END) =======================
                android.app.AlertDialog ad = builder.create();
                ad.setTitle("Order already Decanted");
                if (!ad.isShowing()) ad.show();
            }
        } else {
            PolStatus = false;
            finishOrderButton.setText("DL Data N/A - Close");
            finishOrderButton.setBackgroundColor(Color.RED);
            finishOrderButton.setVisibility(VISIBLE);
            startDeliveryButton.setVisibility(View.INVISIBLE);
        }
    }

    // =====================================================================================
    // UDC PROTOCOL - COMMAND METHODS
    // =====================================================================================

    private void sendUDCCommand(byte functionCode) {
        try {
            byte[] cmd = new byte[]{UDC_FP_ADDRESS, functionCode};
            byte[] dtCmd = applyDoubletalk(cmd);
            lastFunctionCodeSent = functionCode;
            DU_ResponseStr = "";
            String hexStr = bytesToHex(dtCmd);
            tvLogs.append("\nUDC: " + getFunctionCodeName(functionCode) + " [" + hexStr + "]");
            Log.i(TAG, "Sent: " + getFunctionCodeName(functionCode) + " | CMD HEX: " + hexStr);
            sendHexData(dtCmd);
        } catch (Exception e) { Log.e(TAG, "Cmd error: " + e.getMessage()); }
    }

    private void sendUDCCommandWithData(byte functionCode, byte[] data) {
        try {
            byte[] cmd = new byte[2 + data.length];
            cmd[0] = UDC_FP_ADDRESS;
            cmd[1] = functionCode;
            System.arraycopy(data, 0, cmd, 2, data.length);
            byte[] dtCmd = applyDoubletalk(cmd);
            lastFunctionCodeSent = functionCode;
            DU_ResponseStr = "";
            tvLogs.append("\nUDC+Data: " + getFunctionCodeName(functionCode));
            sendHexData(dtCmd);
        } catch (Exception e) { Log.e(TAG, "Cmd+Data error: " + e.getMessage()); }
    }

    private void sendUDCExtendedCommand(byte prefix, byte subCode, byte[] data) {
        try {
            int dataLen = (data != null) ? data.length : 0;
            byte[] cmd = new byte[3 + dataLen];
            cmd[0] = UDC_FP_ADDRESS;
            cmd[1] = prefix;
            cmd[2] = subCode;
            if (data != null) System.arraycopy(data, 0, cmd, 3, dataLen);
            byte[] dtCmd = applyDoubletalk(cmd);
            lastFunctionCodeSent = prefix;
            DU_ResponseStr = "";
            tvLogs.append("\nUDC Ext: " + String.format("%02X %02X", prefix, subCode));
            sendHexData(dtCmd);
        } catch (Exception e) { Log.e(TAG, "Extended cmd error: " + e.getMessage()); }
    }

    private void requestStatus() { sendUDCCommand(FC_REQUEST_STATUS); tvLogs.setText("Cmd: STATUS (A2)"); }
    private void requestDisplayData() { sendUDCCommand(FC_REQUEST_DISPLAY_DATA); tvLogs.setText("Cmd: DISPLAY (A1)"); }
    private void requestTotals() { sendUDCCommandWithData(FC_REQUEST_TOTALS, new byte[]{0x40}); tvLogs.setText("Cmd: TOTALS (A9)"); }
    private void haltSale() { sendUDCCommand(FC_HALT_SALE); tvLogs.setText("Cmd: HALT (A3)"); }
    private void resumeSale() { sendUDCCommand(FC_RESUME_SALE); tvLogs.setText("Cmd: RESUME (A4)"); }

    /**
     * Send Display Data (A6) - Initialize fuelling point when status is 0x2F (Uninitialized)
     * This sends main display data to initialize the FP
     * Sample command (with doubletalk): F0 0F A6 59 12 ED 34 CB 00 FF 00 FF 00 FF 00 FF 00 FF 00 FF
     * Data bytes: 12 34 00 00 00 00 00 00 00 (9 bytes of display initialization data)
     */
    private void sendDisplayDataForInit() {
        try {
            // Display data bytes (without doubletalk): 12 34 00 00 00 00 00 00 00
            // These are the initialization values for the display
            byte[] displayData = new byte[]{0x12, 0x34, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
            sendUDCCommandWithData(FC_SEND_DISPLAY_DATA, displayData);
            tvLogs.setText("Cmd: SEND DISPLAY DATA (A6) - Initializing FP");
            Log.i(TAG, "Sent A6 command to initialize uninitialized fuelling point");
        } catch (Exception e) {
            Log.e(TAG, "Error sending display data for init: " + e.getMessage());
        }
    }

    private void preAuthorize(int mop, int hose) {
        byte ctrl = (byte) ((mop << 4) | (hose & 0x0F));
        sendUDCExtendedCommand(FC_EXTENDED_AB, FC_AB_PRE_AUTHORIZE, new byte[]{ctrl});
        tvLogs.setText("Cmd: PRE-AUTH (AB 01)");
    }

    private void sendRemotePreset(double volume) {
        try {
            // The preset value must be encoded the SAME way as every other numeric field
            // this class sends/parses over UDC - BCD, LSB first. authorize() already sends
            // its volume this way (intToBCD, below), and the totalizers / unit price are
            // decoded as BCD (bcdToDouble). The preset was the ONLY field packed as raw
            // little-endian binary (e.g. 2 L -> 0xC8), which a BCD-expecting DU rejects -
            // that is why it never displayed. Now identical to authorize()'s volume field.
            int volInt = (int) Math.round(volume * 100);   // 2.0 L -> 200 ; 2.5 L -> 250
            byte v0 = intToBCD(volInt % 100);              // hundredths (0.01 L)
            byte v1 = intToBCD((volInt / 100) % 100);      // units
            byte v2 = intToBCD((volInt / 10000) % 100);    // hundreds
            byte v3 = intToBCD((volInt / 1000000) % 100);  // ten-thousands
            byte[] data = new byte[]{0, 0, 0, 0, v0, v1, v2, v3};
            lastPresetTxHex = "F0 AB 26 " + bytesToHex(data);   // logical frame (before doubletalk)
            sendUDCExtendedCommand(FC_EXTENDED_AB, FC_AB_REMOTE_PRESET, data);
            Log.i(TAG, "PRESET TX (AB 26) volume=" + volume + " L volInt=" + volInt
                    + " BCD-payload=[" + bytesToHex(data) + "]");
            tvLogs.setText("Cmd: PRESET (AB 26) - " + volume + " L");
        } catch (Exception e) { Log.e(TAG, "Preset error: " + e.getMessage()); }
    }

    /**
     * A5 - Authorize fueling point, per the UDC spec (Columbia extract, "A5 - AUTHORIZE
     * FUELING POINT"). This is where the PRESET lives in UDC - there is no separate
     * preset command. The command carries the maximum-delivery VOLUME limit; the pump
     * enforces it and stops the fill at that quantity.
     *
     * Frame (14 logical bytes -> 28 with doubletalk = spec "SEND LENGTH 28 bytes"):
     *   Fx A5 [SlowFlowOffset:1]
     *         [Price/Unit Vol : 3 BCD LSB-first, PPP.PP]
     *         [Max delivery Money : 4 BCD LSB-first, MMMMM.MM]
     *         [Max delivery Volume: 4 BCD LSB-first, VVVVV.VV]   <-- the preset
     * Verified against the doc example (price 005.69 -> 69 05 00,
     * money 81619.88 -> 88 19 16 08, volume 14337.88 -> 88 37 43 01).
     *
     * BUG BEFORE THIS FIX: the Max-delivery MONEY field was hardcoded to 00 00 00 00.
     * A money limit of 0.00 means "stop at zero delivered", so the authorization never
     * allowed a real fill. It is now set above volume x price, so the VOLUME limit is
     * the binding preset.
     */
    private void authorize(double price, double maxVolume) {
        try {
            byte sfo = SLOW_FLOW_OFFSET;                       // spec example uses 05

            // Price/Unit Volume - PPP.PP, 3 BCD bytes, LSB first.
            int priceInt = (int) Math.round(price * 100);
            byte pLsb = intToBCD(priceInt % 100),
                 p2sb = intToBCD((priceInt / 100) % 100),
                 pMsb = intToBCD((priceInt / 10000) % 100);

            // Max delivery MONEY - MMMMM.MM, 4 BCD bytes, LSB first. Set comfortably
            // above (volume x price) so the VOLUME limit below is what actually stops
            // the sale; capped at the field maximum 99999.99.
            double moneyLimit = Math.min(99999.99, maxVolume * price + 100.0);
            long moneyInt = Math.round(moneyLimit * 100);
            byte mLsb = intToBCD((int) (moneyInt % 100)),
                 m2sb = intToBCD((int) ((moneyInt / 100) % 100)),
                 m3sb = intToBCD((int) ((moneyInt / 10000) % 100)),
                 mMsb = intToBCD((int) ((moneyInt / 1000000) % 100));

            // Max delivery VOLUME - VVVVV.VV, 4 BCD bytes, LSB first = THE PRESET.
            int volInt = (int) Math.round(maxVolume * 100);
            byte vLsb = intToBCD(volInt % 100),
                 v2sb = intToBCD((volInt / 100) % 100),
                 v3sb = intToBCD((volInt / 10000) % 100),
                 vMsb = intToBCD((volInt / 1000000) % 100);

            byte[] data = new byte[]{sfo, pLsb, p2sb, pMsb, mLsb, m2sb, m3sb, mMsb, vLsb, v2sb, v3sb, vMsb};
            sendUDCCommandWithData(FC_AUTHORIZE, data);
            Log.i(TAG, "A5 AUTHORIZE price=" + price + " preset(maxVol)=" + maxVolume
                    + " L maxMoney=" + moneyLimit + " data=[F0 A5 " + bytesToHex(data) + "]");
            tvLogs.append("\nA5 AUTHORIZE preset " + maxVolume + " L [F0 A5 " + bytesToHex(data) + "]");
            tvLogs.setText("Cmd: AUTHORIZE (A5) - preset " + maxVolume + " L");
        } catch (Exception e) { Log.e(TAG, "Auth error: " + e.getMessage()); }
    }

    /**
     * A6 - Send Data for Fueling Point Main Displays (UDC spec, "A6 - SEND DATA FOR
     * FUELING POINT MAIN DISPLAYS"). Used here to show the ORDERED QUANTITY on the pump's
     * own display BEFORE the nozzle is lifted, so the customer sees how much will be
     * dispensed. This does not authorize or start anything - it only writes the display.
     *
     * Frame (13 logical bytes -> 26 with doubletalk = spec "SEND LENGTH 26 bytes"):
     *   Fx A6 [Price/Unit Vol: 3 BCD LSB-first, PPP.PP]
     *         [Money : 4 BCD LSB-first, MMMMM.MM]
     *         [Volume: 4 BCD LSB-first, VVVVV.VV]   <-- shows the preset quantity
     * Same BCD encoding verified against the A5/A6 doc examples.
     *
     * NOTE: per the spec the pump updates its idle display from A6 only if it is
     * programmed to allow it, so this is best-effort. Guarded by SHOW_PRESET_ON_PUMP.
     */
    private void sendPresetDisplay(double volume) {
        try {
            double price = PumpUnitPriceValid ? PumpUnitPrice : 0.0;
            double money = Math.min(99999.99, volume * price);   // shows expected amount too

            int priceInt = (int) Math.round(price * 100);
            byte pLsb = intToBCD(priceInt % 100),
                 p2sb = intToBCD((priceInt / 100) % 100),
                 pMsb = intToBCD((priceInt / 10000) % 100);

            long moneyInt = Math.round(money * 100);
            byte mLsb = intToBCD((int) (moneyInt % 100)),
                 m2sb = intToBCD((int) ((moneyInt / 100) % 100)),
                 m3sb = intToBCD((int) ((moneyInt / 10000) % 100)),
                 mMsb = intToBCD((int) ((moneyInt / 1000000) % 100));

            int volInt = (int) Math.round(volume * 100);
            byte vLsb = intToBCD(volInt % 100),
                 v2sb = intToBCD((volInt / 100) % 100),
                 v3sb = intToBCD((volInt / 10000) % 100),
                 vMsb = intToBCD((volInt / 1000000) % 100);

            byte[] data = new byte[]{pLsb, p2sb, pMsb, mLsb, m2sb, m3sb, mMsb, vLsb, v2sb, v3sb, vMsb};
            lastA6TxHex = "F0 A6 " + bytesToHex(data);   // logical frame (before doubletalk)
            sendUDCCommandWithData(FC_SEND_DISPLAY_DATA, data);   // A6
            Log.i(TAG, "A6 PRESET-DISPLAY vol=" + volume + " L money=" + money
                    + " data=[F0 A6 " + bytesToHex(data) + "]");
            tvLogs.append("\nA6 preset-display " + volume + " L [F0 A6 " + bytesToHex(data) + "]");
        } catch (Exception e) { Log.e(TAG, "A6 preset-display error: " + e.getMessage()); }
    }

    /** A7 - Reset fueling point. Used only by the reset-then-A6 display method. */
    private void sendReset() {
        sendUDCCommand(FC_RESET_FP);
        Log.i(TAG, "RESET TX (A7)");
        tvLogs.append("\nA7 RESET (for preset-display method 3)");
    }

    /**
     * Fire ALL the "show the preset on the pump display" methods in one go, before the nozzle
     * is lifted. Each is independent and none touches the authorize/fueling/stop flow:
     *   Method 1: A6 now (plain display push).
     *   Method 2: A6 refresh - handled continuously by Stage 5's idle-down branch.
     *   Method 3: A7 reset -> A6 (forces 0x2F so A6 unconditionally writes the display; the
     *             Stage 5 0x2F handler shows it and recovers the pump to idle).
     * Polling is paused for the ~2 s reset sequence so it is not interleaved with status polls,
     * then resumed into the normal wait-for-lift flow.
     */
    private void tryAllPresetDisplayMethods(double vol) {
        // Method 1 - plain A6 right now.
        sendPresetDisplay(vol);
        if (!TRY_RESET_THEN_A6) {
            requestStatus();
            return;
        }
        // Method 3 - reset then A6, sequenced with polling paused to avoid bus collisions.
        PolStatus = false;
        new Handler().postDelayed(() -> sendReset(), 500);
        new Handler().postDelayed(() -> { try { sendPresetDisplay(vol); } catch (Exception e) { } }, 1200);
        new Handler().postDelayed(() -> {
            DU_ResponseStr = "";
            PolStatus = true;      // resume normal wait-for-lift polling (Stage 5)
            requestStatus();
        }, 1900);
    }

    // Doubletalk encoding
    private byte[] applyDoubletalk(byte[] data) {
        byte[] result = new byte[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            result[i * 2] = data[i];
            result[i * 2 + 1] = (byte) ~data[i];
        }
        return result;
    }

    private byte[] removeDoubletalk(byte[] data) {
        if (data == null || data.length % 2 != 0) return null;
        byte[] result = new byte[data.length / 2];
        for (int i = 0; i < result.length; i++) result[i] = data[i * 2];
        return result;
    }

    // =====================================================================================
    // TOTALIZER REPORTING TO THE DRIVER APP  (csifuel:// callback)
    // =====================================================================================
    // The driver app (delivery_screen.dart -> finishClickEvent) branches on
    // "widget.endTotalizer != 0":
    //   end_totalizer != 0 -> it trusts BOTH values and posts start_du_left /
    //                         end_du_left straight to the server, no prompt.
    //   end_totalizer == 0 -> it opens its manual totalizer-entry dialog and the
    //                         driver keys the readings in by hand.
    // So a half-real pair (real end, 0 start) is the one genuinely broken case: it
    // skips the dialog and silently posts a 0 start reading. These helpers enforce
    // both-or-neither, so the driver app either gets two real readings or falls
    // into its own manual-entry path, which is its designed fallback.

    private boolean totalizersUsable() {
        return StartTotalizerValid && EndTotalizerValid && EndTotalizer >= StartTotalizer;
    }

    private String startTotForCallback() {
        return totalizersUsable() ? df.format(StartTotalizer) : "0";
    }

    private String endTotForCallback() {
        return totalizersUsable() ? df.format(EndTotalizer) : "0";
    }

    // Stored values may be null for orders written before totalizers were persisted;
    // "0" keeps the URI parseable and routes the driver app to its manual dialog.
    private String totOrZero(String stored) {
        return (stored == null || stored.trim().isEmpty() || stored.equals("null")) ? "0" : stored;
    }

    private byte intToBCD(int value) { return (byte) (((value / 10) << 4) | (value % 10)); }

    /** True only if both nibbles are valid BCD digits (0-9). bcdToDouble does not
     *  validate, so a status byte or a corrupted frame would otherwise decode to a
     *  plausible-looking number instead of being rejected. */
    private boolean isValidBCD(byte b) {
        int hi = (b >> 4) & 0x0F, lo = b & 0x0F;
        return hi <= 9 && lo <= 9;
    }

    /** Stop the delivery and surface why. Used where continuing would mean guessing
     *  at a value that affects what the customer is billed. Mirrors the existing
     *  "Invalid qty" abort: parks in Stage 11 with polling off, so the message stays
     *  on screen instead of being overwritten by the next poll tick. */
    private void abortDelivery(String reason) {
        Log.e(TAG, "Aborting delivery: " + reason);
        tvFillingStatus.setTextColor(Color.RED);
        tvFillingStatus.setText(reason);
        tvLogs.append("\nABORT: " + reason);
        Toast.makeText(mContext, "MDU error: " + reason, Toast.LENGTH_LONG).show();
        finishOrderButton.setText("Close");
        finishOrderButton.setBackgroundColor(Color.RED);
        finishOrderButton.setVisibility(VISIBLE);
        startDeliveryButton.setVisibility(View.INVISIBLE);
        ProcessStage = 11;
        PolStatus = false;
    }

    private double bcdToDouble(long bcd, int dec) {
        long result = 0, mult = 1;
        while (bcd > 0) { result += (bcd & 0x0F) * mult; mult *= 10; bcd >>= 4; }
        return result / Math.pow(10, dec);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }

    private String getFunctionCodeName(byte fc) {
        switch (fc) {
            case FC_REQUEST_DISPLAY_DATA: return "A1-Display";
            case FC_REQUEST_STATUS: return "A2-Status";
            case FC_HALT_SALE: return "A3-Halt";
            case FC_RESUME_SALE: return "A4-Resume";
            case FC_AUTHORIZE: return "A5-Auth";
            case FC_REQUEST_TOTALS: return "A9-Totals";
            case FC_EXTENDED_AB: return "AB-Ext";
            default: return String.format("%02X", fc);
        }
    }

    private String getStatusDescription(byte s) {
        switch (s) {
            case STATUS_UNINITIALIZED: return "Uninitialized";
            case STATUS_IDLE_HANDLE_DOWN: return "Idle (down)";
            case STATUS_IDLE_HANDLE_UP: return "Idle (up) - Need Auth";
            case STATUS_PRINT_READY: return "Print Ready";
            case STATUS_DISPENSER_ERROR: return "Dispenser Error";
            case STATUS_PRESET_READY: return "Preset Ready";
            case STATUS_AUTHORIZED: return "Authorized (no valves)";
            case STATUS_FUELING_SLOW_FLOW: return "Fueling (slow)";
            case STATUS_FUELING_BOTH_VALVES: return "Fueling (both valves)";
            case STATUS_FUELING_APPROACHING_MAX: return "Fueling (approaching max)";
            case STATUS_SALE_HALTED: return "Sale Halted";
            case STATUS_INVALID_DATA: return "Invalid Data";
            case UDC_ACK: return "ACK";
            default:
                // For unknown status, show hex and try to interpret bits
                return "0x" + String.format("%02X", s) + " (check bits)";
        }
    }

    private void sendHexData(byte[] data) {
        //Log.i(TAG, "sendHexData called - ComA null? " + (ComA == null) + " | ComA.isOpen? " + (ComA != null ? ComA.isOpen() : "N/A"));
        if (ComA != null && ComA.isOpen()) {
            String hex = bytesToHex(data).replace(" ", "");
            //Log.i(TAG, "Sending FINAL Hex Data: " + hex);
            ComA.sendData(hex.toCharArray(), false);
        } else {
            Log.e(TAG, "ERROR: Serial port not open! Cannot send data. Attempting to re-open...");
            // Try to re-open the port
            try {
                initializeSerialPort();
                if (ComA != null && ComA.isOpen()) {
                    String hex = bytesToHex(data).replace(" ", "");
                    Log.i(TAG, "Port re-opened. Sending FINAL Hex Data: " + hex);
                    ComA.sendData(hex.toCharArray(), false);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to re-open serial port: " + e.getMessage());
            }
        }
    }

    private byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    // =====================================================================================
    // MAIN POLLING LOOP - STATE MACHINE
    // =====================================================================================

    public void LoopPolling(final int timer) {
        polhandler = new Handler();
        polrunnable = new Runnable() {
            @SuppressLint("SetTextI18n")
            @Override
            public void run() {
                try {
                    if (IsDecantationStarted && PolStatus) {
                        Log.e("Polling", "Stage: " + ProcessStage);
                        final Handler handler = new Handler();

                        // Stage 0: Initialize
                        if (ProcessStage == 0) {
                            txtCRMNumber.setText(DL_ORDER_NO);
                            txtOrderQuantity.setText(DL_ORDER_QTY + " Liters");
                            tvDUStatus.setText("Detecting...");
                            tvRFIDStatus.setText("Detecting...");
                            tvHandler.post(() -> tvFillingStatus.setText("0.00"));
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
                                }
                            } else requestStatus();
                        }
                        // Stage 1: Check Status Response
                        else if (ProcessStage == 1) {
                            Log.i(TAG, "Stage 1 - DU_ResponseStr: [" + DU_ResponseStr + "] Length: " + DU_ResponseStr.length());
                            if (DU_ResponseStr.length() >= 4) {
                                String cleanResp = DU_ResponseStr.replace(" ", "");
                                Log.i(TAG, "Stage 1 - Clean Response (no spaces): [" + cleanResp + "] Length: " + cleanResp.length());
                                byte[] resp = hexStringToByteArray(cleanResp);
                                Log.i(TAG, "Stage 1 - Byte array length: " + resp.length);
                                byte[] decoded = removeDoubletalk(resp);
                                if (decoded != null && decoded.length >= 1) {
                                    lastStatusReceived = decoded[0];
                                    Log.i(TAG, "Stage 1 - Decoded status byte: 0x" + String.format("%02X", lastStatusReceived) + " = " + getStatusDescription(lastStatusReceived));
                                    tvDUStatus.setText("ONLINE - " + getStatusDescription(lastStatusReceived));
                                    tvDUStatus.setTextColor(Color.GREEN);
                                    DuConnectCounter = 0;

                                    // Check if fuelling point is UNINITIALIZED (0x2F)
                                    if (lastStatusReceived == STATUS_UNINITIALIZED) {
                                        // Send A6 (Send Display Data) to initialize the fuelling point
                                        Log.i(TAG, "FP is UNINITIALIZED (0x2F) - Sending A6 command");
                                        tvLogs.append("\nFP Uninitialized - Sending A6 to initialize...");
                                        tvFillingStatus.setText("Initializing Fuelling Point...");
                                        DU_ResponseStr = "";
                                        sendDisplayDataForInit();
                                        // Stay in stage 0 to re-poll status after initialization
                                        new Handler().postDelayed(() -> {
                                            ProcessStage = 0;
                                            DU_ResponseStr = "";
                                        }, 500);
                                    }
                                    else if (lastStatusReceived == STATUS_IDLE_HANDLE_DOWN || lastStatusReceived == STATUS_IDLE_HANDLE_UP ||
                                            lastStatusReceived == STATUS_AUTHORIZED) {
                                        Log.i(TAG, "FP is in valid state - Moving to Stage 2");
                                        ProcessStage = 2;
                                        if (RFIDConnectCounter == 0) {
                                            tvRFIDStatus.setText("OFFLINE");
                                            tvRFIDStatus.setTextColor(Color.RED);
                                            if (IsOtpSet) {
                                                tvRFIDStatus.setText("OTP verified");
                                                tvRFIDStatus.setTextColor(Color.GREEN);
                                            }
                                            if (!RFIDNotRespondDialog && !IsOtpSet) RFIDNotRespondingCloseAlert();
                                        }
                                    } else if (lastStatusReceived == STATUS_SALE_HALTED) {
                                        Log.i(TAG, "FP is HALTED - Sending Resume");
                                        resumeSale();
                                        ProcessStage = 0;
                                    } else {
                                        // Bounded. Previously this re-polled forever with no cap
                                        // and no feedback: a FP left in Print Ready (0xA1) or
                                        // Preset Ready (0xA3) by a previous sale matches none of
                                        // the accepted statuses, so the screen sat on
                                        // "Detecting..." indefinitely. DuConnectCounter cannot
                                        // rescue it either - that only increments in Stage 0,
                                        // which runs once.
                                        UnknownStatusCount++;
                                        Log.i(TAG, "Unknown status 0x" + String.format("%02X", lastStatusReceived)
                                                + " - Re-polling (" + UnknownStatusCount + "/" + UNKNOWN_STATUS_MAX + ")");
                                        if (UnknownStatusCount >= UNKNOWN_STATUS_MAX) {
                                            abortDelivery("DU stuck in " + getStatusDescription(lastStatusReceived)
                                                    + " - cannot start");
                                        } else {
                                            DU_ResponseStr = "";
                                            requestStatus();
                                        }
                                    }
                                } else {
                                    Log.w(TAG, "Stage 1 - Doubletalk decode failed or empty");
                                    DU_ResponseStr = "";
                                    requestStatus();
                                }
                            } else {
                                Log.w(TAG, "Stage 1 - Response too short, re-polling. Current: " + DU_ResponseStr);
                                DU_ResponseStr = "";
                                requestStatus();
                            }
                        }
                        // Stage 2: Read Start Totalizer
                        else if (ProcessStage == 2 && IsOtpSet) {
                            // The start-totalizer read is attempted as originally intended.
                            // Stage 3 now bounds the number of A9 attempts, so a pump that
                            // never answers A9 can no longer stall the state machine.
                            DU_ResponseStr = "";
                            ProcessStage = 3;
                            requestTotals();
                        }
                        // Stage 3: Parse Start Totalizer
                        else if (ProcessStage == 3) {
                            // ===================== FIX (START) =====================
                            // BEFORE: both failure branches were
                            //   else { DU_ResponseStr = ""; requestTotals(); }
                            // with no retry cap. This pump answers A9 (Request Totals) with a
                            // 2-byte status (e.g. "A0 5F" = 0xA0, handle up / awaiting
                            // authorization) instead of totalizer data, so the ">= 22" check
                            // never passed and Stage 3 re-sent A9 forever. Preset (AB 26) and
                            // Authorize (A5) were never sent and the dispenser sat on
                            // "Waiting for FCC authorization".
                            // AFTER: try A9 up to START_TOT_MAX_RETRY times. If it answers, the
                            // original parse runs unchanged and StartTotalizerValid is set. If
                            // it never answers, advance to Stage 4 anyway so dispensing is never
                            // blocked, leaving StartTotalizerValid = false.
                            boolean startTotParsed = false;
                            if (DU_ResponseStr.length() >= 22) {
                                byte[] resp = hexStringToByteArray(DU_ResponseStr.replace(" ", ""));
                                byte[] decoded = removeDoubletalk(resp);
                                if (decoded != null && decoded.length >= 6) {
                                    long totBCD = (decoded[0] & 0xFFL) | ((decoded[1] & 0xFFL) << 8) |
                                            ((decoded[2] & 0xFFL) << 16) | ((decoded[3] & 0xFFL) << 24) |
                                            ((decoded[4] & 0xFFL) << 32);
                                    StartTotalizer = (float) bcdToDouble(totBCD, 2);
                                    startTotParsed = true;
                                    StartTotalizerValid = true;
                                    tvLogs.append("\nStart Tot: " + df.format(StartTotalizer));
                                }
                            }

                            if (startTotParsed) {
                                ProcessStage = 12;
                            } else if (StartTotRetryCount < START_TOT_MAX_RETRY) {
                                StartTotRetryCount++;
                                // Raw reply logged so the correct A9 format can be identified.
                                Log.i(TAG, "Stage 3 - A9 reply [" + DU_ResponseStr + "] not totals; retry "
                                        + StartTotRetryCount + "/" + START_TOT_MAX_RETRY);
                                DU_ResponseStr = "";
                                requestTotals();
                            } else {
                                StartTotalizer = 0;
                                StartTotalizerValid = false;
                                Log.w(TAG, "Stage 3 - Start totalizer unavailable after "
                                        + START_TOT_MAX_RETRY + " attempts, continuing without it");
                                tvLogs.append("\nStart Tot N/A - continuing");
                                ProcessStage = 12;
                            }
                            // ===================== FIX (END) =======================
                        }
                        // Stage 12: Request Display Data (A1) purely to read the pump's own
                        // unit price. Runs once, between the start-totalizer read and the
                        // preset, so the price is known before A5 is ever built.
                        else if (ProcessStage == 12) {
                            DU_ResponseStr = "";
                            ProcessStage = 13;
                            requestDisplayData();
                        }
                        // Stage 13: Parse unit price from the A1 reply.
                        // A1 layout (after doubletalk removal):
                        //   bytes 0-1 Price | 2-4 Money | 5-7 Volume | 8 Status   (all BCD, LSB first)
                        // e.g. 93 20 -> 0x2093 -> 20.93 per litre.
                        else if (ProcessStage == 13) {
                            boolean priceParsed = false;
                            if (DU_ResponseStr.length() >= 6) {
                                byte[] resp = hexStringToByteArray(DU_ResponseStr.replace(" ", ""));
                                byte[] decoded = removeDoubletalk(resp);
                                // A 2-byte status reply (e.g. A0 5F) decodes to length 1 and is
                                // correctly rejected here, exactly as the A9 totals reply is.
                                if (decoded != null && decoded.length >= 2
                                        && isValidBCD(decoded[0]) && isValidBCD(decoded[1])) {
                                    long priceBCD = (decoded[0] & 0xFFL) | ((decoded[1] & 0xFFL) << 8);
                                    double p = bcdToDouble(priceBCD, 2);
                                    if (p > 0 && p <= 99999.99) {
                                        PumpUnitPrice = (float) p;
                                        PumpUnitPriceValid = true;
                                        priceParsed = true;
                                        Log.i(TAG, "Stage 13 - Unit price read from pump: " + df.format(PumpUnitPrice));
                                        tvLogs.append("\nUnit price: " + df.format(PumpUnitPrice));
                                    }
                                }
                            }

                            if (priceParsed) {
                                // Go straight to Stage 5 (wait for handle-up, then A5 Authorize).
                                // The old Stage 4 sent a fictional AB 26 "preset" command that the
                                // pump ignored (no reply). In UDC there is no separate preset - the
                                // fill limit is carried by the A5 Authorize command (see authorize()).
                                // Tablet shows the preset prominently (guaranteed).
                                tvFillingStatus.setText("PRESET " + DL_ORDER_QTY + " L  -  Lift NOZZLE to dispense");
                                DU_ResponseStr = "";     // drop the A1 price reply
                                // Best-effort: push the ordered quantity to the PUMP's own
                                // display via A6 before the nozzle is lifted. When sent, go to
                                // Stage 15 which captures & logs the pump's reply (diagnostic),
                                // then proceeds to Stage 5 unchanged. If disabled/failed, go
                                // straight to Stage 5.
                                boolean a6Sent = false;
                                a6RefreshTick = 0;
                                ProcessStage = 5;
                                if (SHOW_PRESET_ON_PUMP) {
                                    try {
                                        // Fire ALL preset-display methods (A6, and reset->A6).
                                        // Method handles its own polling pause/resume + status poll.
                                        tryAllPresetDisplayMethods(Double.parseDouble(DL_ORDER_QTY.trim()));
                                        a6Sent = true;
                                    } catch (Exception ignore) { }
                                }
                                // Stage 5 then keeps refreshing the A6 display while the nozzle
                                // is down, and authorizes on lift (unchanged).
                                if (!a6Sent) requestStatus();   // tryAll() issues its own poll
                            } else if (PriceRetryCount < PRICE_MAX_RETRY) {
                                PriceRetryCount++;
                                Log.i(TAG, "Stage 13 - A1 reply [" + DU_ResponseStr + "] has no usable price; retry "
                                        + PriceRetryCount + "/" + PRICE_MAX_RETRY);
                                DU_ResponseStr = "";
                                requestDisplayData();
                            } else {
                                // NO FALLBACK BY DESIGN. There is no safe default for a unit
                                // price: authorizing at a value we did not read from the pump
                                // would bill the customer at a fabricated rate. Stop here.
                                PumpUnitPriceValid = false;
                                abortDelivery("Cannot read unit price from pump - aborted");
                            }
                        }
                        // Stage 4: Set Preset only (authorize will be sent when nozzle is lifted)
                        else if (ProcessStage == 4) {
                            if (!IsPreAuthorized) {
                                DU_ResponseStr = "";
                                IsPreAuthorized = true;
                                handler.postDelayed(() -> {
                                    try {
                                        double qty = Double.parseDouble(DL_ORDER_QTY.trim());   // accepts 2 and 2.5
                                        sendRemotePreset(qty);
                                        Log.i(TAG, "Stage 4 - Preset sent for " + qty + " L, awaiting confirmation");
                                        finishOrderButton.setText("Hard Stop");
                                        finishOrderButton.setBackgroundColor(Color.DKGRAY);
                                        finishOrderButton.setVisibility(VISIBLE);
                                        tvFillingStatus.setText("Setting preset " + qty + " L...");
                                        // Stage 14 verifies the preset landed before we go on.
                                        ProcessStage = 14;
                                    } catch (Exception e) {
                                        ProcessStage = 11;
                                        tvFillingStatus.setTextColor(Color.RED);
                                        tvFillingStatus.setText("Invalid qty, aborting...");
                                        finishOrderButton.setText("Close");
                                        finishOrderButton.setVisibility(VISIBLE);
                                        PolStatus = false;
                                    }
                                }, 300);
                            }
                        }
                        // Stage 14: Verify the preset was actually accepted, and re-send if not.
                        // Mirrors Eastman's Stage 5 ACK loop. Confirmation is the FP reaching
                        // 0xA3 Preset Ready; a 0xB0 ACK is accepted as an interim signal and
                        // followed by a status poll to confirm the state change.
                        else if (ProcessStage == 14) {
                            byte presetStatus = 0;
                            boolean decodedOk = false;
                            if (DU_ResponseStr.length() >= 4) {
                                byte[] resp = hexStringToByteArray(DU_ResponseStr.replace(" ", ""));
                                byte[] decoded = removeDoubletalk(resp);
                                if (decoded != null && decoded.length >= 1) {
                                    presetStatus = decoded[0];
                                    decodedOk = true;
                                }
                            }

                            if (decodedOk && presetStatus == STATUS_PRESET_READY) {
                                PresetConfirmed = true;
                                Log.i(TAG, "Stage 14 - Preset CONFIRMED, FP in Preset Ready (0xA3)");
                                tvLogs.append("\nPreset confirmed (A3)");
                                tvFillingStatus.setText("Preset " + DL_ORDER_QTY + " L set - Lift NOZZLE...");
                                DU_ResponseStr = "";
                                ProcessStage = 5;
                            } else if (decodedOk && presetStatus == UDC_ACK
                                    && PresetRetryCount < PRESET_MAX_RETRY) {
                                // Data accepted. Poll status to see the state actually change.
                                // Counts against the same budget so a pump that answers 0xB0
                                // forever falls through to the give-up branch instead of
                                // spinning here indefinitely.
                                PresetRetryCount++;
                                PresetAckSeen = true;
                                Log.i(TAG, "Stage 14 - Preset ACK (0xB0), polling for Preset Ready ("
                                        + PresetRetryCount + "/" + PRESET_MAX_RETRY + ")");
                                DU_ResponseStr = "";
                                requestStatus();
                            } else if (PresetRetryCount < PRESET_MAX_RETRY) {
                                PresetRetryCount++;
                                Log.i(TAG, "Stage 14 - preset unconfirmed, reply [" + DU_ResponseStr
                                        + "]; re-send " + PresetRetryCount + "/" + PRESET_MAX_RETRY);
                                try {
                                    sendRemotePreset(Double.parseDouble(DL_ORDER_QTY.trim()));
                                } catch (Exception e) {
                                    Log.e(TAG, "Stage 14 - preset re-send failed: " + e.getMessage());
                                    DU_ResponseStr = "";
                                    ProcessStage = 5;
                                }
                            } else {
                                // Preset could not be confirmed. Surface the exact bytes on screen
                                // (Toast + persistent status + log) so they can be captured and sent
                                // for diagnosis, and STOP here so the message stays on screen.
                                PresetConfirmed = false;
                                String reply = DU_ResponseStr.trim().isEmpty() ? "(no reply)" : DU_ResponseStr.trim();
                                String diag = "PRESET FAILED (" + DL_ORDER_QTY + " L)"
                                        + "\nSent: " + lastPresetTxHex
                                        + "\nPump reply: " + reply
                                        + "\nNot confirmed after " + PRESET_MAX_RETRY + " tries (ACK seen: " + PresetAckSeen + ")";
                                Log.w(TAG, "Stage 14 - " + diag.replace("\n", " | "));
                                tvLogs.append("\n===== " + diag + " =====");
                                Toast.makeText(mContext, diag, Toast.LENGTH_LONG).show();
                                tvFillingStatus.setTextColor(Color.RED);
                                tvFillingStatus.setText("Preset NOT set. Sent " + lastPresetTxHex + " | reply " + reply);
                                finishOrderButton.setText("Close");
                                finishOrderButton.setVisibility(VISIBLE);
                                PolStatus = false;      // stop so the error stays on screen to capture
                                ProcessStage = 11;
                                DU_ResponseStr = "";
                            }
                        }
                        // Stage 15: A6 PRESET-DISPLAY DIAGNOSTIC. Captures the pump's reply to
                        // the A6 "Send Data for Main Displays" command and prints a detailed
                        // message in the command/log area, so we can see why the preset volume
                        // is / isn't appearing on the dispenser. Waits a couple of ticks for the
                        // reply, logs once, then proceeds to Stage 5 (nozzle-lift -> A5) unchanged.
                        else if (ProcessStage == 15) {
                            String raw = DU_ResponseStr.trim();
                            if (raw.isEmpty() && A6DiagRetry < 2) {
                                // Give the A6 reply time to arrive before deciding "no reply".
                                A6DiagRetry++;
                                Log.i(TAG, "Stage 15 - awaiting A6 reply (" + A6DiagRetry + "/2)");
                            } else {
                                String decodedStr = "(none)";
                                String interp;
                                if (raw.isEmpty()) {
                                    interp = "NO REPLY - the pump did not answer the A6 command. It is "
                                           + "most likely not programmed to accept main-display data "
                                           + "over A6 while idle, so the Volume window cannot be "
                                           + "pre-filled before nozzle-lift.";
                                } else {
                                    byte[] resp = hexStringToByteArray(raw.replace(" ", ""));
                                    byte[] decoded = removeDoubletalk(resp);
                                    if (decoded != null && decoded.length >= 1) {
                                        byte st = decoded[0];
                                        decodedStr = "0x" + String.format("%02X", st)
                                                + " (" + getStatusDescription(st) + ")";
                                        if (st == STATUS_IDLE_HANDLE_DOWN || st == 0x24) {
                                            interp = "Pump ACCEPTED A6 (status " + decodedStr + "). The "
                                                   + "display data was taken - if the Volume window is "
                                                   + "still blank, this pump's firmware does not render "
                                                   + "A6 volume while idle.";
                                        } else if (st == STATUS_UNINITIALIZED) {
                                            interp = "Pump returned 0x2F (uninitialised) to A6 - it needs "
                                                   + "initialising before it will show display data.";
                                        } else {
                                            interp = "Pump replied status " + decodedStr + " (not 20/24) to "
                                                   + "A6 - it did not update the display in this state.";
                                        }
                                    } else {
                                        interp = "Reply could not be doubletalk-decoded: [" + raw + "]";
                                    }
                                }
                                String diag = "===== A6 PRESET-DISPLAY DIAGNOSTIC =====\n"
                                        + "Order qty : " + DL_ORDER_QTY + " L\n"
                                        + "A6 sent   : " + lastA6TxHex + "\n"
                                        + "Pump reply: " + (raw.isEmpty() ? "(no reply)" : raw) + "\n"
                                        + "Decoded   : " + decodedStr + "\n"
                                        + interp + "\n"
                                        + "========================================";
                                Log.w(TAG, "Stage 15 - " + diag.replace("\n", " | "));
                                // tvLogs is a single-line display (every command calls setText and
                                // wipes it), so FREEZE the flow for 20 s with the full diagnostic on
                                // screen to photograph, then auto-resume the (working) authorize flow.
                                tvLogs.setText(diag);
                                Toast.makeText(mContext, diag, Toast.LENGTH_LONG).show();
                                tvFillingStatus.setTextColor(Color.BLUE);
                                tvFillingStatus.setText("A6 DIAGNOSTIC (screenshot the log below) - "
                                        + "resuming in 20s\nA6 reply: "
                                        + (raw.isEmpty() ? "(NO REPLY)" : raw) + "  status " + decodedStr);
                                DU_ResponseStr = "";
                                PolStatus = false;       // freeze so the log stays for the screenshot
                                new Handler().postDelayed(() -> {
                                    tvFillingStatus.setTextColor(Color.BLACK);
                                    tvFillingStatus.setText("PRESET " + DL_ORDER_QTY
                                            + " L  -  Lift NOZZLE to dispense");
                                    DU_ResponseStr = "";
                                    ProcessStage = 5;    // continue to the (working) authorize flow
                                    PolStatus = true;
                                    requestStatus();
                                }, 20000);
                            }
                        }
                        // Stage 5: Wait for Nozzle Lift, then Authorize
                        else if (ProcessStage == 5) {
                            // Check if we already have a response to process
                            if (DU_ResponseStr.length() >= 4) {
                                String cleanResp = DU_ResponseStr.replace(" ", "");
                                byte[] resp = hexStringToByteArray(cleanResp);
                                byte[] decoded = removeDoubletalk(resp);
                                if (decoded != null && decoded.length >= 1) {
                                    lastStatusReceived = decoded[0];
                                    Log.i(TAG, "Stage 5 - Status received: 0x" + String.format("%02X", lastStatusReceived) + " = " + getStatusDescription(lastStatusReceived));

                                    // Check for fueling states (D0, F0, D4)
                                    if (lastStatusReceived == STATUS_FUELING_BOTH_VALVES ||
                                            lastStatusReceived == STATUS_FUELING_SLOW_FLOW ||
                                            lastStatusReceived == STATUS_FUELING_APPROACHING_MAX) {
                                        // Fueling started! Move to monitor stage
                                        Log.i(TAG, "Stage 5 - Fueling STARTED, moving to Stage 6");
                                        tvFillingStatus.setText("Dispensing...");
                                        IsDispenserAuthorized = true;
                                        ProcessStage = 6;
                                    } else if (lastStatusReceived == STATUS_IDLE_HANDLE_UP) {
                                        // Nozzle lifted (A0) - this is a REQUEST for authorization
                                        Log.i(TAG, "Stage 5 - Nozzle LIFTED (A0), sending Authorize command");
                                        // Defensive: Stage 13 already gates entry here, but never
                                        // build an A5 frame without a price read from the pump.
                                        // NOTE: no early return here - the runnable must reach
                                        // polhandler.postDelayed() at the end of run(), or the
                                        // polling loop stops for good and the Finish button
                                        // (which re-enables PolStatus) would never work again.
                                        if (!PumpUnitPriceValid || PumpUnitPrice <= 0) {
                                            abortDelivery("Unit price not verified - cannot authorize");
                                        } else {
                                            try {
                                                double price = PumpUnitPrice;
                                                double qty = Double.parseDouble(DL_ORDER_QTY.trim());   // accepts 2 and 2.5
                                                authorize(price, qty);
                                                IsDispenserAuthorized = true;
                                                tvFillingStatus.setText("Authorized - Start dispensing...");
                                                // Move to Stage 6 to monitor fueling
                                                ProcessStage = 6;
                                            } catch (Exception e) {
                                                Log.e(TAG, "Auth error: " + e.getMessage());
                                                DU_ResponseStr = "";
                                                requestStatus();
                                            }
                                        }
                                    } else if (lastStatusReceived == STATUS_AUTHORIZED) {
                                        // Already authorized (90), waiting for valves to open
                                        Log.i(TAG, "Stage 5 - Authorized (0x90), waiting for fueling to start");
                                        tvFillingStatus.setText("Authorized - Open valve...");
                                        IsDispenserAuthorized = true;
                                        ProcessStage = 6;
                                    } else if (lastStatusReceived == STATUS_PRESET_READY) {
                                        // REVERTED to the known-good production behaviour.
                                        // Do NOT authorize from Preset Ready (0xA3): the working
                                        // build only ever sent A5 on 0xA0 (handle up), and presets
                                        // displayed correctly. Authorizing here fires A5 while the
                                        // nozzle is still on the hook and marks the FP authorized
                                        // without checking the pump's reply, so a rejected A5
                                        // becomes invisible and unrecoverable.
                                        Log.i(TAG, "Stage 5 - Preset ready (A3), waiting for nozzle lift");
                                        DU_ResponseStr = "";
                                        requestStatus();
                                    } else if (lastStatusReceived == STATUS_UNINITIALIZED) {
                                        // 0x2F (uninitialised) - normally only seen here right after
                                        // the A7 reset display-attempt. Sending A6 now both writes
                                        // the preset to the display (spec: A6 to a 0x2F point sends
                                        // money/volume/price) AND re-initialises the pump to idle
                                        // (0x20), so the normal wait-for-lift flow resumes cleanly.
                                        Log.i(TAG, "Stage 5 - FP 0x2F, A6 (display preset + re-init)");
                                        DU_ResponseStr = "";
                                        if (SHOW_PRESET_ON_PUMP) {
                                            try { sendPresetDisplay(Double.parseDouble(DL_ORDER_QTY.trim())); }
                                            catch (Exception e) { sendDisplayDataForInit(); }
                                        } else {
                                            sendDisplayDataForInit();
                                        }
                                    } else if (lastStatusReceived == STATUS_IDLE_HANDLE_DOWN) {
                                        // Nozzle still down (20), keep waiting for lift. Also keep
                                        // RE-SENDING the preset to the pump display via A6 every
                                        // other idle tick, so a pump that only shows it briefly
                                        // holds it. Alternated with the status poll so handle-up is
                                        // still detected promptly. This runs ONLY in the idle/wait
                                        // state - the authorize / fueling / auto-stop logic is
                                        // completely untouched.
                                        Log.i(TAG, "Stage 5 - Nozzle down, waiting for lift...");
                                        DU_ResponseStr = "";
                                        a6RefreshTick++;
                                        if (SHOW_PRESET_ON_PUMP && (a6RefreshTick % 2 == 0)) {
                                            try {
                                                sendPresetDisplay(Double.parseDouble(DL_ORDER_QTY.trim()));
                                            } catch (Exception e) { requestStatus(); }
                                        } else {
                                            requestStatus();
                                        }
                                    } else if (lastStatusReceived == STATUS_DISPENSER_ERROR) {
                                        // Dispenser error (A2)
                                        Log.e(TAG, "Stage 5 - DISPENSER ERROR (A2)!");
                                        tvFillingStatus.setTextColor(Color.RED);
                                        tvFillingStatus.setText("DISPENSER ERROR");
                                        DU_ResponseStr = "";
                                        requestStatus();
                                    } else if (lastStatusReceived == STATUS_INVALID_DATA) {
                                        // Invalid data (00) - re-poll
                                        Log.w(TAG, "Stage 5 - Invalid data received, re-polling");
                                        DU_ResponseStr = "";
                                        requestStatus();
                                    } else {
                                        // Unknown status - check if it might be a fueling variant
                                        // Per spec: "evaluate specific bits set in the returned status byte"
                                        Log.i(TAG, "Stage 5 - Status: 0x" + String.format("%02X", lastStatusReceived) + ", checking bits...");

                                        // Check if high nibble indicates fueling (D or F)
                                        int highNibble = (lastStatusReceived & 0xF0) & 0xFF;
                                        if (highNibble == 0xD0 || highNibble == 0xF0) {
                                            Log.i(TAG, "Stage 5 - Detected fueling variant, moving to Stage 6");
                                            IsDispenserAuthorized = true;
                                            ProcessStage = 6;
                                        } else {
                                            DU_ResponseStr = "";
                                            requestStatus();
                                        }
                                    }
                                } else {
                                    // Decode failed, re-poll
                                    DU_ResponseStr = "";
                                    requestStatus();
                                }
                            } else {
                                // No response yet, send status request
                                DU_ResponseStr = "";
                                requestStatus();
                            }
                        }
                        // Stage 6: Monitor Fueling
                        else if (ProcessStage == 6) { DU_ResponseStr = ""; requestDisplayData(); }
                        // Stage 7: Parse Display Data
                        // A1 Response (Standard Layout 9 bytes after Doubletalk removal):
                        // Bytes 0-1: Price  (2 bytes BCD, LSB first)
                        // Bytes 2-4: Money  (3 bytes BCD, LSB first)
                        // Bytes 5-7: Volume (3 bytes BCD, LSB first)
                        // Byte 8: Status byte
                        else if (ProcessStage == 7) {
//                            Log.i(TAG, "Stage 7 - DU_ResponseStr: [" + DU_ResponseStr + "] Length: " + DU_ResponseStr.length());
                            if (DU_ResponseStr.length() >= 18) { // 9 bytes * 2 (Doubletalk) = 18 hex chars minimum (without spaces would be 36)
                                String cleanResp = DU_ResponseStr.replace(" ", "");
                                Log.i(TAG, "Stage 7 - Clean Response: [" + cleanResp + "] Length: " + cleanResp.length());
                                byte[] resp = hexStringToByteArray(cleanResp);
                                byte[] decoded = removeDoubletalk(resp);
//                                Log.i(TAG, "Stage 7 - Decoded length: " + (decoded != null ? decoded.length : "null"));

                                if (decoded != null && decoded.length >= 9) {
                                    // Log all decoded bytes for debugging
                                    StringBuilder sb = new StringBuilder();
                                    for (int i = 0; i < decoded.length; i++) {
                                        sb.append(String.format("%02X ", decoded[i]));
                                    }
                                    Log.i(TAG, "Stage 7 - Decoded bytes: " + sb.toString());

                                    // Parse Volume. The A1 reply mirrors the A6 SEND layout this app
                                    // already uses: Price[3] + Money[4] + Volume[4] + Status[1] = 12
                                    // bytes. Volume is 4 BCD bytes, LSB first, at offset 7. The old
                                    // code assumed a 9-byte layout and read bytes 5-7, which landed on
                                    // the wrong bytes of this pump's 12-byte frame and reported a
                                    // garbage volume (e.g. 7801.41 L for a real 673.78 L delivery).
                                    long volumeBCD;
                                    if (decoded.length >= 12) {
                                        volumeBCD = (decoded[7] & 0xFFL) |
                                                ((decoded[8] & 0xFFL) << 8) |
                                                ((decoded[9] & 0xFFL) << 16) |
                                                ((decoded[10] & 0xFFL) << 24);
                                    } else {
                                        // Fallback: older 9-byte layout Price[2]+Money[3]+Volume[3]+Status[1].
                                        volumeBCD = (decoded[5] & 0xFFL) |
                                                ((decoded[6] & 0xFFL) << 8) |
                                                ((decoded[7] & 0xFFL) << 16);
                                    }
                                    double volumeValue = bcdToDouble(volumeBCD, 2);
                                    Log.i(TAG, "Stage 7 - Volume BCD: " + String.format("%08X", volumeBCD) + " = " + volumeValue);

                                    // Status is the LAST byte
                                    lastStatusReceived = decoded[decoded.length - 1];
                                    Log.i(TAG, "Stage 7 - Status byte: 0x" + String.format("%02X", lastStatusReceived) + " = " + getStatusDescription(lastStatusReceived));

                                    volumeFloat = (float) volumeValue;
                                    Log.i(TAG, "Stage 7 - Calculated Volume: " + volumeFloat);

                                    tvFillingStatus.setText(df.format(volumeFloat) + " L");
                                    DispensingStarted = true;
                                    IsFuelingStarted = true;

                                    // Check status to determine next action
                                    // Fueling states: D0, F0, D4
                                    if (lastStatusReceived == STATUS_FUELING_BOTH_VALVES ||
                                            lastStatusReceived == STATUS_FUELING_SLOW_FLOW ||
                                            lastStatusReceived == STATUS_FUELING_APPROACHING_MAX) {
                                        Log.i(TAG, "Stage 7 - Still fueling, continue polling");
                                        ProcessStage = 6;
                                    } else if (lastStatusReceived == STATUS_AUTHORIZED) {
                                        // Authorized but valves not open yet (90)
                                        Log.i(TAG, "Stage 7 - Authorized, waiting for valves to open");
                                        ProcessStage = 6;
                                    } else if (lastStatusReceived == STATUS_IDLE_HANDLE_DOWN || lastStatusReceived == STATUS_IDLE_HANDLE_UP) {
                                        // REVERTED to the known-good production behaviour.
                                        // The 0 L guard only existed to compensate for the Stage 5
                                        // A3-authorize path (now removed), which could enter Stage 7
                                        // with the nozzle still down. With that gone the guard is
                                        // dead weight, and it risks hanging completion if the pump
                                        // blanks its volume display when the nozzle is re-hooked.
                                        // Fueling complete - nozzle returned (20 or A0)
                                        Log.i(TAG, "Stage 7 - Fueling COMPLETE! Status: " + getStatusDescription(lastStatusReceived) + " - Moving to Stage 8");
                                        ProcessStage = 8;
                                        DU_ResponseStr = "";
                                        requestTotals();
                                    } else if (lastStatusReceived == STATUS_SALE_HALTED) {
                                        // Sale halted (98)
                                        Log.i(TAG, "Stage 7 - Sale HALTED");
                                        ProcessStage = 9;
                                        tvFillingStatus.setText("PAUSED - " + df.format(volumeFloat) + " L");
                                        btnResume.setVisibility(VISIBLE);
                                    } else if (lastStatusReceived == STATUS_DISPENSER_ERROR) {
                                        // Dispenser error (A2)
                                        Log.e(TAG, "Stage 7 - DISPENSER ERROR!");
                                        tvFillingStatus.setTextColor(Color.RED);
                                        tvFillingStatus.setText("ERROR - " + df.format(volumeFloat) + " L");
                                        ProcessStage = 6; // Continue polling to see if it recovers
                                    } else {
                                        // Unknown status - check high nibble for fueling variants
                                        int highNibble = (lastStatusReceived & 0xF0) & 0xFF;
                                        if (highNibble == 0xD0 || highNibble == 0xF0) {
                                            // Fueling variant (Dx or Fx)
                                            Log.i(TAG, "Stage 7 - Fueling variant detected, continue polling");
                                            ProcessStage = 6;
                                        } else if (highNibble == 0x90) {
                                            // Authorized variant (9x)
                                            Log.i(TAG, "Stage 7 - Authorized variant, continue polling");
                                            ProcessStage = 6;
                                        } else {
                                            Log.i(TAG, "Stage 7 - Unknown status 0x" + String.format("%02X", lastStatusReceived) + ", continue polling");
                                            ProcessStage = 6;
                                        }
                                    }
                                } else {
                                    Log.w(TAG, "Stage 7 - Decoded length insufficient, re-polling");
                                    DU_ResponseStr = "";
                                    requestDisplayData();
                                }
                            } else {
                                Log.w(TAG, "Stage 7 - Response too short (" + DU_ResponseStr.length() + " chars), re-polling");
                                DU_ResponseStr = "";
                                requestDisplayData();
                            }
                        }
                        // Stage 8: Read End Totalizer
                        else if (ProcessStage == 8) {
                            // ===================== FIX (START) =====================
                            // BEFORE: both failure branches were
                            //   else { DU_ResponseStr = ""; requestTotals(); }
                            // with no retry cap. Because this pump answers A9 with a 2-byte
                            // status instead of totalizer data, the ">= 22" check never passed
                            // and Stage 8 re-sent A9 forever: the order was never saved to the
                            // DB, "Finish" never appeared, and polling never stopped.
                            // AFTER: try A9 up to END_TOT_MAX_RETRY times. If totals arrive the
                            // original totalizer path runs exactly as before. If they never
                            // arrive, fall back to volumeFloat (the A1-derived volume already
                            // shown on screen) and complete the order normally.
                            boolean endTotParsed = false;
                            if (DU_ResponseStr.length() >= 22) {
                                byte[] resp = hexStringToByteArray(DU_ResponseStr.replace(" ", ""));
                                byte[] decoded = removeDoubletalk(resp);
                                if (decoded != null && decoded.length >= 6) {
                                    long totBCD = (decoded[0] & 0xFFL) | ((decoded[1] & 0xFFL) << 8) |
                                            ((decoded[2] & 0xFFL) << 16) | ((decoded[3] & 0xFFL) << 24) |
                                            ((decoded[4] & 0xFFL) << 32);
                                    EndTotalizer = (float) bcdToDouble(totBCD, 2);
                                    endTotParsed = true;
                                    EndTotalizerValid = true;
                                }
                            }

                            if (!endTotParsed && EndTotRetryCount < END_TOT_MAX_RETRY) {
                                EndTotRetryCount++;
                                Log.i(TAG, "Stage 8 - A9 reply [" + DU_ResponseStr + "] not totals; retry "
                                        + EndTotRetryCount + "/" + END_TOT_MAX_RETRY);
                                DU_ResponseStr = "";
                                requestTotals();
                            } else {
                                if (!endTotParsed) {
                                    EndTotalizer = 0;
                                    EndTotalizerValid = false;
                                    Log.w(TAG, "Stage 8 - End totalizer unavailable after "
                                            + END_TOT_MAX_RETRY + " attempts");
                                }
                                volumeFloatFinal = EndTotalizer - StartTotalizer;
                                // Only trust the totalizer difference when BOTH readings are
                                // genuine, otherwise EndTotalizer - 0 would report the pump's
                                // lifetime total as the delivered quantity. Falls through to the
                                // existing volumeFloat (A1-derived) fallback.
                                if (totalizersUsable() && volumeFloatFinal >= 0) {
                                    volumeFloat = volumeFloatFinal;
                                    tvFillingStatus.setText("Qty: " + df.format(volumeFloatFinal) + " L | Tot: " +
                                            df.format(StartTotalizer) + " / " + df.format(EndTotalizer));
                                } else {
                                    volumeFloatFinal = volumeFloat;
                                    tvFillingStatus.setText("Qty: " + df.format(volumeFloatFinal) + " L");
                                }
                                OrderData orderData = new OrderData();
                                orderData.setOrderNo(DL_ORDER_NO);
                                orderData.setOtp(DL_OTP);
                                orderData.setorderQty(DL_ORDER_QTY);
                                orderData.setRfidTag(DL_RFID_TAG);
                                orderData.setDecantQty(df.format(volumeFloat));
                                // Persist the same values the callback will carry, so the
                                // "Re-send to App" path replays an identical payload instead of
                                // writing null into the startTotalizer/endTotalizer columns.
                                orderData.setStartTotalizer(startTotForCallback());
                                orderData.setEndTotalizer(endTotForCallback());
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
                            // ===================== FIX (END) =======================
                        }
                        // Stage 9: Paused
                        else if (ProcessStage == 9) {
                            haltSale();
                            tvFillingStatus.setTextColor(Color.RED);
                            tvFillingStatus.setText("Dispensing PAUSED");
                            ProcessStage = 11;
                        }
                        // Stage 10: Resume
                        else if (ProcessStage == 10) {
                            resumeSale();
                            tvLogs.append("\nResuming...");
                            ProcessStage = 6;
                        }
                        // Stage 11: Waiting
                        else if (ProcessStage == 11) {
                            tvFillingStatus.setTextColor(Color.RED);
                            tvFillingStatus.setText("Dispensing PAUSED");
                        }
                    }
                    polhandler.postDelayed(this, timer);
                } catch (Exception e) { e.printStackTrace(); }
            }
        };
        polhandler.post(polrunnable);
    }

    // =====================================================================================
    // SERIAL PORT CLASSES
    // =====================================================================================

    private class SerialControl extends SerialHelper {
        public SerialControl() {}
        @Override
        protected void onDataReceived(final byte[] ComRecData) {
            DispQueue.AddQueue(ComRecData);
            DispQueue.setResume();
        }
    }

    private class DispQueueThread extends Thread {
        private final BlockingQueue<byte[]> QueueList = new LinkedBlockingQueue<>();
        @Override
        public void run() {
            super.run();
            while (!isInterrupted()) {
                if (!isRun) break;
                while (QueueList.size() != 0) {
                    if (!isRun) break;
                    final byte[] ComData = QueueList.poll();
                    runOnUiThread(() -> DispRecData(ComData));
                    try { Thread.sleep(5); } catch (Exception e) { e.printStackTrace(); }
                }
                synchronized (this) {
                    try { wait(); } catch (InterruptedException e) { e.printStackTrace(); }
                }
            }
        }
        public synchronized void AddQueue(byte[] ComData) { QueueList.add(ComData); }
        public synchronized void setResume() { notify(); }
    }

    @SuppressLint("SetTextI18n")
    private void DispRecData(byte[] AComRecData) {
        int size = AComRecData.length - 1;
        byte[] ComRecData = new byte[size];
        System.arraycopy(AComRecData, 1, ComRecData, 0, size);
        StringBuilder sMsg = new StringBuilder();
        sMsg.append(MyFunc.ByteArrToHex(ComRecData));
        String respHex = sMsg.toString();
        tvLogs.append(" Resp: " + respHex);
        Log.i(TAG, "RESPONSE RECEIVED HEX: " + respHex + " | Length: " + respHex.length());
        DU_ResponseStr = DU_ResponseStr + respHex;
        //Log.i(TAG, "DU_ResponseStr accumulated: " + DU_ResponseStr + " | Total Length: " + DU_ResponseStr.length());
        if (ProcessStage == 6) ProcessStage = 7;
    }

    private void CloseComPort(SerialHelper ComPort) {
        if (ComPort != null) { ComPort.destroySend(); ComPort.close(); }
    }

    private void OpenComPort(SerialHelper ComPort) {
        //Log.i(TAG, "OpenComPort() called");
        try {
            ComPort.open();
            //Log.i(TAG, "OpenComPort() - Port opened successfully, isOpen: " + ComPort.isOpen());
        }
        catch (SecurityException e) {
            Log.e(TAG, "OpenComPort() - SecurityException: " + e.getMessage());
            ShowMessage(getString(R.string.nopermission));
        }
        catch (IOException e) {
            Log.e(TAG, "OpenComPort() - IOException: " + e.getMessage());
            ShowMessage(getString(R.string.unknownerr));
        }
        catch (InvalidParameterException e) {
            Log.e(TAG, "OpenComPort() - InvalidParameterException: " + e.getMessage());
            ShowMessage(getString(R.string.parametererr));
        }
        catch (Exception e) {
            Log.e(TAG, "OpenComPort() - General Exception: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        if (DispQueue != null) { isRun = false; DispQueue.setResume(); }
        CloseComPort(ComA);
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
        super.onDestroy();
    }

    private void ShowMessage(String sMsg) { Toast.makeText(this, sMsg, Toast.LENGTH_SHORT).show(); }

    // =====================================================================================
    // DIALOGS
    // =====================================================================================

    public void DUNotRespondingCloseAlert() {
        try {
            if (ProcessStage <= 1) {
                DUNotRespondDialog = true;
                AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
                builder.setCancelable(false);
                builder.setMessage("Close Screen?\nYES = Close | NO = Retry");
                builder.setPositiveButton("YES", (d, w) -> { CloseComPort(ComA); finish(); });
                builder.setNegativeButton("NO", (d, w) -> { d.cancel(); DUNotRespondDialog = false; DuConnectCounter = 0; });
                AlertDialog ad = builder.create();
                ad.setTitle("DU Not Responding");
                if (!ad.isShowing()) ad.show();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    public void RFIDNotRespondingCloseAlert() {
        try {
            RFIDNotRespondDialog = true;
            AlertDialog.Builder builder = new AlertDialog.Builder(TokheimUDCDecantActivity.this);
            builder.setCancelable(false);
            builder.setNeutralButton("Connect RFID", (d, w) -> {
                d.dismiss();
                RFIDNotRespondDialog = false;
                new Handler().postDelayed(() -> RFIDConnectCounter = 0, 2000);
            });
            builder.setNegativeButton("Cancel", (d, w) -> { d.dismiss(); RFIDNotRespondDialog = false; RFIDConnectCounter = 0; });
            builder.setMessage("Proceed with OTP verification?");
            builder.setPositiveButton("PROCEED WITH OTP", (d, w) -> {
                d.dismiss();
                final Dialog dialog1 = new Dialog(TokheimUDCDecantActivity.this);
                dialog1.setCancelable(false);
                dialog1.requestWindowFeature(Window.FEATURE_NO_TITLE);
                dialog1.setContentView(R.layout.otp_decant_dialog);
                dialog1.getWindow().setGravity(Gravity.TOP);
                dialog1.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                TextView Header = dialog1.findViewById(R.id.tv_header);
                Header.setText("Enter OTP for Order " + crmNumber);
                EditText etOtpCode = dialog1.findViewById(R.id.et_otp_code);
                etOtpCode.setHint("Enter OTP");
                Button cancel = dialog1.findViewById(R.id.btn_cancel_otp_decant);
                Button submit = dialog1.findViewById(R.id.btn_submit_otp_decant);
                Spinner reasonSp = dialog1.findViewById(R.id.sp_Reason_Otp_Decant);
                reasonSp.setVisibility(View.GONE);
                cancel.setOnClickListener(v -> {
                    dialog1.dismiss();
                    tvRFIDStatus.setText("OTP declined");
                    tvRFIDStatus.setTextColor(Color.RED);
                    IsOtpSet = false;
                    RFIDNotRespondDialog = false;
                    RFIDConnectCounter = 0;
                    PolStatus = false;
                    finishOrderButton.setText("Close");
                    finishOrderButton.setVisibility(VISIBLE);
                });
                submit.setOnClickListener(v -> {
                    String OtpCode = etOtpCode.getText().toString();
                    if (OtpCode.equals(DL_OTP)) {
                        tvRFIDStatus.setText("OTP verified");
                        tvRFIDStatus.setTextColor(Color.GREEN);
                        IsOtpSet = true;
                        tvHandler.post(() -> tvFillingStatus.setText("Lift NOZZLE to dispense..."));
                        RFIDConnectCounter = 0;
                        dialog1.dismiss();
                    } else {
                        dialog1.dismiss();
                        tvRFIDStatus.setText("OTP FAILED");
                        tvRFIDStatus.setTextColor(Color.RED);
                        IsOtpSet = false;
                        RFIDNotRespondDialog = false;
                        RFIDConnectCounter = 0;
                        startDeliveryButton.setText("Try Again");
                        startDeliveryButton.setVisibility(VISIBLE);
                        finishOrderButton.setText("Close");
                        finishOrderButton.setVisibility(VISIBLE);
                    }
                });
                dialog1.show();
            });
            AlertDialog ad = builder.create();
            ad.setTitle("RFID Not Responding");
            if (!ad.isShowing()) ad.show();
        } catch (Exception e) { e.printStackTrace(); }
    }
}
