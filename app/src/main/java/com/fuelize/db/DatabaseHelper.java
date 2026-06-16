package com.fuelize.db;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.text.TextUtils;
import android.util.Log;

import com.fuelize.model.OrderData;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DatabaseHelper extends SQLiteOpenHelper {

    public static final String TABLE_ORDER_NAME = "OrderData";

    // Table Columns

    public static final String COLUMN_ORDER_QUANTITY = "orderQty";
    public static final String COLUMN_ORDER_NUMBER = "orderNo";
    public static final String COLUMN_OTP = "otp";
    public static final String COLUMN_RFID = "rfidTag";
    public static final String COLUMN_DECANT_QUANTITY = "decantQty";
    public static final String COLUMN_START_TOTALIZER = "startTotalizer";
    public static final String COLUMN_END_TOTALIZER = "endTotalizer";
    public static final String COLUMN_STATUS = "status";
    public static final String COLUMN_TIMESTAMP = "timestamp";
    public static final String COLUMN_SKIP_AUTHENTICATION = "skipAuthentication";


    // Create table SQL query
    public static final String CREATE_TABLE_ORDER =
            "CREATE TABLE " + TABLE_ORDER_NAME + "(" + COLUMN_ORDER_NUMBER + " TEXT PRIMARY KEY,"
                    + COLUMN_OTP + " TEXT ,"
                    + COLUMN_ORDER_QUANTITY + " TEXT,"
                    + COLUMN_RFID + " TEXT,"
                    + COLUMN_DECANT_QUANTITY + " TEXT,"
                    + COLUMN_START_TOTALIZER + " TEXT,"
                    + COLUMN_END_TOTALIZER + " TEXT,"
                    + COLUMN_TIMESTAMP + " TEXT,"
                    + COLUMN_SKIP_AUTHENTICATION + " TEXT,"
                    + COLUMN_STATUS + " TEXT" + ")";

    // Database Version
    private static final int DATABASE_VERSION = 1;
    // Database Name
    private static final String DATABASE_NAME = "MDU_DB.db";

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    // Creating Tables
    @Override
    public void onCreate(SQLiteDatabase db) {
        // create OrderData table
        db.execSQL(CREATE_TABLE_ORDER);
        Log.e("TAG", "onCreate: TABLE CREATED ---> ");
    }

    // Upgrading database
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Drop older table if existed
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_ORDER_NAME);
        // Create tables again
        onCreate(db);
    }

    public void insertOrderData(OrderData orderData) {

        try {
            // get writable database as we want to write data
            SQLiteDatabase db = this.getWritableDatabase();

            ContentValues values = new ContentValues();

            values.put(COLUMN_ORDER_NUMBER, orderData.getOrderNo());
            values.put(COLUMN_OTP, orderData.getOtp());
            values.put(COLUMN_ORDER_QUANTITY, orderData.getorderQty());
            values.put(COLUMN_RFID, orderData.getRfidTag());
            // ===================== FIX A1 (START) =====================
            // BEFORE:
            //   values.put(COLUMN_DECANT_QUANTITY, "0.00");
            //   values.put(COLUMN_START_TOTALIZER, "0.00");
            //   values.put(COLUMN_END_TOTALIZER, "0.00");
            // Reason: hardcoded zeros discarded the real dispensed result,
            //         making the saved record useless for recovery / re-send.
            // AFTER: persist the actual values carried on the OrderData object.
            values.put(COLUMN_DECANT_QUANTITY, orderData.getDecantQty());
            values.put(COLUMN_START_TOTALIZER, orderData.getStartTotalizer());
            values.put(COLUMN_END_TOTALIZER, orderData.getEndTotalizer());
            // ===================== FIX A1 (END) =======================
            values.put(COLUMN_TIMESTAMP, getDateTime());

            // insert row
            db.insert(TABLE_ORDER_NAME, null, values);
            // close db connection
            db.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @SuppressLint("Range")
    public OrderData getOrderData(String OrderNumber) {
        // prepare Order object
        OrderData order = new OrderData();
        try {
            // get readable database as we are not inserting anything
            SQLiteDatabase db = this.getReadableDatabase();

            Cursor cursor = db.query(TABLE_ORDER_NAME,
                    new String[]{COLUMN_ORDER_NUMBER,
                            COLUMN_ORDER_QUANTITY,
                            COLUMN_OTP,
                            COLUMN_RFID,
                            COLUMN_DECANT_QUANTITY,
                            COLUMN_START_TOTALIZER,
                            COLUMN_END_TOTALIZER,
                            COLUMN_SKIP_AUTHENTICATION,
                            COLUMN_STATUS,
                            COLUMN_TIMESTAMP
                    }, COLUMN_ORDER_NUMBER + "=?", new String[]{OrderNumber}, null, null, null, null);

            if (cursor != null) {
                cursor.moveToFirst();
                order.setOrderNo(cursor.getString(cursor.getColumnIndex(COLUMN_ORDER_NUMBER)));
                order.setorderQty(cursor.getString(cursor.getColumnIndex(COLUMN_ORDER_QUANTITY)));
                order.setRfidTag(cursor.getString(cursor.getColumnIndex(COLUMN_RFID)));
                order.setOtp(cursor.getString(cursor.getColumnIndex(COLUMN_OTP)));
                order.setDecantQty(cursor.getString(cursor.getColumnIndex(COLUMN_DECANT_QUANTITY)));
                order.setStartTotalizer(cursor.getString(cursor.getColumnIndex(COLUMN_START_TOTALIZER)));
                order.setEndTotalizer(cursor.getString(cursor.getColumnIndex(COLUMN_END_TOTALIZER)));
                order.setStatus(cursor.getString(cursor.getColumnIndex(COLUMN_STATUS)));
                order.setSkipAuthentication(cursor.getString(cursor.getColumnIndex(COLUMN_SKIP_AUTHENTICATION)));
                order.setTimestamp(cursor.getString(cursor.getColumnIndex(COLUMN_TIMESTAMP)));

                // close the db connection
                cursor.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return order;
    }

//    public List<OrderData> getAllOrderData() {
//        List<OrderData> OrderData = new ArrayList<>();
//        try {
//            // Select All Query
//            String selectQuery = "SELECT * FROM " + TABLE_ORDER_NAME;
//
//            SQLiteDatabase db = this.getReadableDatabase();
//            @SuppressLint("Recycle") Cursor cursor = db.rawQuery(selectQuery, null);
//            // looping through all rows and adding to list
//            if (cursor.getCount() > 0) {
//                if (cursor.moveToFirst()) {
//                    do {
//                        OrderData order = new OrderData();
//                        order.setOrderCRMNumber(cursor.getString(cursor.getColumnIndex(COLUMN_CRM_NUMBER)));
//                        order.setMduID(cursor.getString(cursor.getColumnIndex(COLUMN_MDU_ID)));
//                        order.setTripID(cursor.getString(cursor.getColumnIndex(COLUMN_TRIP_ID)));
//                        order.setOrderCustomerAddress(cursor.getString(cursor.getColumnIndex(COLUMN_ADDRESS)));
//                        order.setCustomerMobileNo(cursor.getString(cursor.getColumnIndex(COLUMN_CUSTOMER_MOBILE_NO)));
//                        order.setCustomerName(cursor.getString(cursor.getColumnIndex(COLUMN_CUSTOMER_NAME)));
//                        order.setDriverId(cursor.getString(cursor.getColumnIndex(COLUMN_DRIVER_ID)));
//                        order.setStatus(cursor.getString(cursor.getColumnIndex(COLUMN_STATUS)));
//                        order.setOrderQuantity(cursor.getString(cursor.getColumnIndex(COLUMN_QUANTITY)));
//                        order.setDecantQty(cursor.getString(cursor.getColumnIndex(COLUMN_DECANT_QUANTITY)));
//                        order.setOrderUnitPrice(cursor.getString(cursor.getColumnIndex(COLUMN_PER_UNIT_PRICE)));
//                        order.setDecantLatitude(cursor.getString(cursor.getColumnIndex(COLUMN_DECANT_LATITUDE)));
//                        order.setDecantLongitude(cursor.getString(cursor.getColumnIndex(COLUMN_DECANT_LONGITUDE)));
//                        order.setOrderInvoiceNumber(cursor.getString(cursor.getColumnIndex(COLUMN_INVOICE_NUMBER)));
//                        order.setDgTag(cursor.getString(cursor.getColumnIndex(COLUMN_DG_TAG)));
//                        order.setMasterTag(cursor.getString(cursor.getColumnIndex(COLUMN_MASTER_TAG)));
//                        order.setProduct(cursor.getString(cursor.getColumnIndex(COLUMN_PRODUCT)));
//                        order.setOtp(cursor.getString(cursor.getColumnIndex(COLUMN_OTP)));
//                        order.setSkipAuthentication(cursor.getString(cursor.getColumnIndex(COLUMN_SKIP_AUTHENTICATION)));
//                        order.setResellerInvoiceOrganizationName(cursor.getString(cursor.getColumnIndex(RESELLER_INVOICE_ORGANIZATION_NAME)));
//                        order.setResellerInvoiceCashMemo(cursor.getString(cursor.getColumnIndex(RESELLER_INVOICE_CASH_MEMO)));
//                        order.setResellerInvoiceMethodOfPayment(cursor.getString(cursor.getColumnIndex(RESELLER_INVOICE_METHOD_OF_PAYMENT)));
//                        order.setResellerInvoiceQueryFeedback(cursor.getString(cursor.getColumnIndex(RESELLER_INVOICE_QUERY_FEEDBACK)));
//                        order.setResellerInvoiceInformation(cursor.getString(cursor.getColumnIndex(RESELLER_INVOICEING_FORMATION)));
//                        order.setCstNo(cursor.getString(cursor.getColumnIndex(CST_NO)));
//                        order.setRslNo(cursor.getString(cursor.getColumnIndex(RSL_NO)));
//                        order.setCinNo(cursor.getString(cursor.getColumnIndex(CIN_NO)));
//                        order.setVatNo(cursor.getString(cursor.getColumnIndex(VAT_NUMBER)));
//                        order.setVatEffectiveDate(cursor.getString(cursor.getColumnIndex(VAT_EFFECTIVE_DATE)));
//                        order.setServiceProviderGstNoValidFrom(cursor.getString(cursor.getColumnIndex(SERVICE_PROVIDER_GST_NO_VALID_FROM)));
//                        order.setServiceProviderGstNo(cursor.getString(cursor.getColumnIndex(SERVICE_PROVIDER_GST_NO)));
//                        order.setServiceProviderContactNo(cursor.getString(cursor.getColumnIndex(SERVICE_PROVIDER_CONTACT_NO)));
//                        order.setServiceProviderStateCode(cursor.getString(cursor.getColumnIndex(SERVICE_PROVIDER_STATE_CODE)));
//                        order.setServiceProviderState(cursor.getString(cursor.getColumnIndex(SERVICE_PROVIDER_STATE)));
//                        order.setServiceProviderAddress(cursor.getString(cursor.getColumnIndex(SERVICE_PROVIDER_ADDRESS)));
//                        order.setRoStateCode(cursor.getString(cursor.getColumnIndex(RO_STATE_CODE)));
//                        order.setRoState(cursor.getString(cursor.getColumnIndex(RO_STATE)));
//                        order.setRoAddress(cursor.getString(cursor.getColumnIndex(RO_ADDRESS)));
//                        order.setRoId(cursor.getString(cursor.getColumnIndex(RO_ID)));
//                        order.setAssetId(cursor.getString(cursor.getColumnIndex(ASSET_ID)));
//                        order.setPlaceOfSupply(cursor.getString(cursor.getColumnIndex(PLACE_OF_SUPPLY)));
//                        order.setRegisterOffice(cursor.getString(cursor.getColumnIndex(REGISTERED_OFFICE)));
//                        order.setAssetlat(cursor.getString(cursor.getColumnIndex(RO_ASSET_LATITUDE)));
//                        order.setAssetlong(cursor.getString(cursor.getColumnIndex(RO_ASSET_LONGITUDE)));
//                        order.setGeofenceradius(cursor.getString(cursor.getColumnIndex(RO_GEO_FENCE_RADIUS)));
//                        order.setGeofencereason(cursor.getString(cursor.getColumnIndex(RO_GEO_FENCE_REASON)));
//                        order.setGeofenceviolateflg(cursor.getString(cursor.getColumnIndex(RO_GEO_FENCE_VIOLATE_FLG)));
//                        order.setReason(cursor.getString(cursor.getColumnIndex(RO_GEO_FENCE_SELECTED_REASON)));
//                        order.setIsSync(cursor.getString(cursor.getColumnIndex(COLUMN_IS_SYNC)));
//                        if (cursor.getString(cursor.getColumnIndex(COLUMN_STATUS)) != null
//                                && cursor.getString(cursor.getColumnIndex(COLUMN_STATUS)).equals("COMPLETED")
//                                && cursor.getString(cursor.getColumnIndex(COLUMN_IS_SYNC)) != null
//                                && cursor.getString(cursor.getColumnIndex(COLUMN_IS_SYNC)).equals("FALSE")) {
//                            OrderData.add(order);
//                        }
//                    } while (cursor.moveToNext());
//                }
//            }
//            cursor.close();
//            // close db connection
//            db.close();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//        // return OrderData list
//        return OrderData;
//    }

    public void updateOrderData(OrderData orderData) {
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(COLUMN_STATUS, orderData.getStatus());
            values.put(COLUMN_DECANT_QUANTITY, orderData.getDecantQty());
            values.put(COLUMN_START_TOTALIZER, orderData.getStartTotalizer());
            values.put(COLUMN_END_TOTALIZER, orderData.getEndTotalizer());
            values.put(COLUMN_SKIP_AUTHENTICATION, orderData.getSkipAuthentication());
            // updating row
            db.update(TABLE_ORDER_NAME, values, COLUMN_ORDER_NUMBER + " = '" + orderData.getOrderNo() + "'", null);
            db.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


//    public void deleteOrderData() {
//        try {
//            SQLiteDatabase db = this.getWritableDatabase();
//            db.delete(TABLE_ORDER_NAME, COLUMN_IS_SYNC + " = 'TRUE'", null);
//            db.close();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }

    @SuppressLint("Range")
    public String checkOrderInProgress() {

        String InProgressOrder = null;
        try {
            // Select All Query
            String selectQuery = "SELECT * FROM " + TABLE_ORDER_NAME + " where " + COLUMN_STATUS + " = 'INPROGRESS'";

            SQLiteDatabase db = this.getReadableDatabase();
            @SuppressLint("Recycle") Cursor cursor = db.rawQuery(selectQuery, null);
            // looping through all rows and adding to list
            if (cursor.getCount() > 0) {
                if (cursor.moveToFirst()) {
                    if (!TextUtils.isEmpty(cursor.getString(cursor.getColumnIndex(COLUMN_ORDER_NUMBER)))) {
                        InProgressOrder = cursor.getString(cursor.getColumnIndex(COLUMN_ORDER_NUMBER));
                    }
                }
            }
            cursor.close();
            // close db connection
            db.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return InProgressOrder;
    }

    public boolean checkOrderCompleted(String OrderNumber) {

        boolean IfOrderCompleted = false;
        try {
            // Select All Query
            String selectQuery = "SELECT * FROM " + TABLE_ORDER_NAME + " where " + COLUMN_ORDER_NUMBER + " = '" + OrderNumber + "' and " + COLUMN_STATUS + " = 'COMPLETED'";

            SQLiteDatabase db = this.getReadableDatabase();
            @SuppressLint("Recycle") Cursor cursor = db.rawQuery(selectQuery, null);
            // looping through all rows and adding to list
            if (cursor.getCount() > 0) {
                IfOrderCompleted = true;
            }
            cursor.close();
            // close db connection
            db.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return IfOrderCompleted;
    }

    public boolean checkOrderExists(String OrderNumber) {

        boolean IfOrderExists = false;
        try {
            // Select All Query
            String selectQuery = "SELECT * FROM " + TABLE_ORDER_NAME + " where " + COLUMN_ORDER_NUMBER + " = '" + OrderNumber + "'";

            SQLiteDatabase db = this.getReadableDatabase();
            @SuppressLint("Recycle") Cursor cursor = db.rawQuery(selectQuery, null);
            // looping through all rows and adding to list
            if (cursor.getCount() > 0) {
                IfOrderExists = true;
            }
            cursor.close();
            // close db connection
            db.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return IfOrderExists;
    }


    @SuppressLint("Range")
    public String checkOrderOtpCode(String OrderNumber) {

        String IsOtpSet = null;
        try {
            // Select All Query
            String selectQuery = "SELECT * FROM " + TABLE_ORDER_NAME + " where " + COLUMN_ORDER_NUMBER + " = '" + OrderNumber + "'";

            SQLiteDatabase db = this.getReadableDatabase();
            @SuppressLint("Recycle") Cursor cursor = db.rawQuery(selectQuery, null);
            // looping through all rows and adding to list
            if (cursor.getCount() > 0) {
                if (cursor.moveToFirst()) {
                    if (!TextUtils.isEmpty(cursor.getString(cursor.getColumnIndex(COLUMN_SKIP_AUTHENTICATION)))) {
                        IsOtpSet = cursor.getString(cursor.getColumnIndex(COLUMN_SKIP_AUTHENTICATION));
                    }
                }
            }
            cursor.close();
            // close db connection
            db.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return IsOtpSet;
    }

    private String getDateTime() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        Date date = new Date();
        return dateFormat.format(date);
    }

    public void ExecuteQuery(String query) {
        SQLiteDatabase writeDatabase = this.getWritableDatabase();
        writeDatabase.execSQL(query);
        writeDatabase.close();
    }
}